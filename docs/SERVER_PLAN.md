# LYNXgwas Server: plan

`lynxgwas-server` is the online, multi-user edition of LYNXgwas. It started as a copy of the
desktop app at LYNXgwas commit `850479c` plus the uncommitted fixes from 2026-09-29 (GREML
removal, ToolLocator, PlinkRunner). From now on it is maintained separately. Fixes found here
that also apply to the desktop app should be ported back by hand.

## Goals

1. **No AI agent on the server.** The local-LLM chat, its tool registry and every `/api/agent/*`
   route are removed.
2. **Accounts with email verification.** A user registers with email and password and receives a
   6-digit code by email. The account works only after the code is entered.
3. **15-day retention.** Every user project is deleted 15 days after creation.
   - Each project card shows a countdown bar that turns from green to red.
   - The owner gets a warning email 3 days before deletion and a notice when it happens.
4. **Public datasets.** The existing curated datasets are public and read-only for everyone.
   A button on the home page switches between **Public datasets** and **My projects**.
5. **A high level of security.** See the threat model below.

## Threat model

The desktop app trusted its user completely: it read and wrote any path, opened native file
dialogs, ran commands built from request fields, and sent `Access-Control-Allow-Origin: *`.
Online, every request comes from an untrusted stranger, so the port removes or fences off
everything that assumed otherwise.

| Desktop behaviour | Risk online | Server version |
|---|---|---|
| Config stores absolute paths (`gwas.file`, `ref.panel.path`, `gff3.file`, `loci.file`, `top.snp.file`) | Read any server file; point PLINK at anything | Clients cannot set paths. GWAS input is **uploaded** into the project. Reference panel and GFF come from `server.properties` |
| `/api/peek-file-header?path=`, `/api/rsid-detect?gwas_file=`, `annotation-file?path=` | Arbitrary file read | Removed, or confined to the project folder |
| `/save-pdf`, `export-excel output_folder` | Arbitrary file write | Removed. Exports are written inside the project and downloaded |
| `analysis/run` `*_path` params, `gwama_path` in a `sh -c` template, `susiex_path`, `trait2_file` | Remote command execution | Program paths come from the server only. Every client param must be a plain number or token |
| `/api/global-config` POST, shared storage (`git clone`), GitHub token/push/import, HPC/SSH | Server takeover, credential abuse | Removed. Global config is admin-only (a file on disk) |
| `/pick-file`, `/pick-folder` (Swing dialogs) | Hangs server threads | Removed |
| Static handler serves the whole working directory, no traversal check | Leaks configs, API keys, every project | Allowlist of the four HTML pages, JS and images only |
| `%2E%2E` project id → `projects/..` | Delete or overwrite the app folder | Ids validated everywhere (`FileSafety.validProjectId`) |
| Global lists (`/api/projects`, search, locus-matrix jobs, missing rsIDs, projects info) | Other users' data visible | Filtered to public plus own. Jobs carry an owner |
| CORS `*` | Any website can drive the API | No CORS. Same-origin check plus a CSRF token on every state change |

## Architecture

```
Internet ──HTTPS──> Caddy (TLS, HTTP/2, request logging) ──http 127.0.0.1:8765──> ServerMain (Java)
                                                                   │
                                   SecurityGate filter (every request)
                                     headers · rate limit · route allowlist · session · CSRF · body size
                                                                   │
                         ServerApi (auth, my-projects, uploads, downloads)   LocalServer (ported handlers,
                                                                   │           per-project access checks)
                          AccountService · ProjectRegistry · Mailer · RetentionScheduler · JobLimiter
```

- **`server-data/`** (private and never served) holds:
  - `accounts/` (one properties file per account)
  - `registry/` (project owner and expiry records)
  - `outbox/` (development-mode emails)
  - `pepper.key` (the server secret, when not supplied by an environment variable)
- **`projects/`** holds public datasets and user projects (`u-xxxxxxxxxxxx`).
  - A project with a registry record is private to its owner.
  - A project without one is public and read-only.
- **Sessions** use an HttpOnly, SameSite=Strict cookie (`__Host-` prefix and `Secure` behind HTTPS).
  - Tokens are 256-bit random values, stored only as HMAC digests.
  - A session ends after 2 hours idle or 24 hours at most.
- **Passwords** use PBKDF2-HMAC-SHA256 with 600k iterations and a per-user salt, and are rehashed
  when the cost is raised.
  - At least 10 characters; common patterns are rejected.
  - 5 failed logins lock the account for 15 minutes; per-IP rate limits apply on top.
- **Codes** are 6 digits from `SecureRandom`, valid for 15 minutes and single-use.
  - At most 5 guesses per code, then it is burned.
  - Resending is limited to once a minute.
  - Codes are stored only as HMAC digests.
- **Anti-enumeration:** register, resend and forgot-password answer identically for known and
  unknown emails. Email is sent from a background queue, so timing doesn't reveal account existence.
  Signing up with an already-registered email sends the owner a "someone tried to sign up" notice.
- **Email** uses Jakarta Mail over TLS (STARTTLS or SSL). Plain SMTP is refused and the server
  certificate is verified. With no `smtp.host`, emails go to `server-data/outbox/`.
- **Retention:** an hourly sweep sends warnings, deletes expired projects (symlinks are never
  followed), sends deletion notices, and removes accounts that were never verified after 48 hours.
- **Quotas:** 5 projects and 8 GB per user, 2 GB per upload, and 1 heavy job per user
  (4 in total). All are configurable.
- **Headers** on every response:
  - CSP (`object-src 'none'`, `frame-ancestors 'none'`, `connect-src 'self'`)
  - HSTS (behind HTTPS)
  - `nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, COOP/CORP
  - `Cache-Control: no-store`
- **Errors** never include stack traces or server paths.

## Work plan

1. **Copy and strip.** Create the folder. Delete the AI agent, GitHub sync, shared storage,
   HPC/Snakemake submitter, native pickers, save-pdf and legacy single-project routes, in both
   Java and HTML.
2. **Security core** (`src/server/`): `ServerConfig`, `Crypto`, `AccountService`, `Mailer`,
   `ProjectRegistry`, `RequestLimiter`, `SecurityGate`, `FileSafety`, `JobLimiter`.
3. **New API** (`ServerApi`):
   - `/api/auth/{register,verify,resend,login,logout,forgot,reset,me,delete-account}`
   - `/api/my/projects` (create)
   - `/api/project/{id}/upload-gwas` (streamed upload)
   - `/api/project/{id}/gwas-header`
   - `/api/project/{id}/download`
4. **Port the handlers** in `LocalServer`:
   - Bind address and port come from the config; CORS is removed.
   - Every project-scoped action checks access: GET needs read access, POST/DELETE need the owner.
   - Config POST ignores client paths and forces server paths.
   - Analysis parameters are validated, and program paths are ignored.
   - Jobs get owners.
   - Lists and search are filtered to what the caller may see.
   - Exports are written inside the project.
   - Static files come from an allowlist.
5. **Frontend:**
   - Account bar (sign in, register, verify, forgot password, sign out).
   - Public / My projects toggle.
   - Countdown bars.
   - Upload instead of Browse.
   - Read-only mode for public datasets.
   - `lynx-server.js` adds the CSRF header to every same-origin request.
6. **Operations:** `ServerMain`, `server.properties.example`, `deploy/Caddyfile`,
   `deploy/lynxgwas.service` (systemd, unprivileged user, sandboxing), and `docs/DEPLOY.md`.
7. **Tests:**
   - Unit tests for crypto, accounts, codes, lockout, registry and retention (with a fake clock),
     path safety and the limiter.
   - An end-to-end HTTP test: register, code from the outbox, verify, create a project, upload,
     then confirm another user gets 404, a public write gets 403, a missing CSRF token gets 403,
     a traversal attempt gets 400, and a disabled route gets 404.

## Decisions made without asking (easy to change in `server.properties`)

- **Public datasets are read-only.** Anyone can browse them without an account. Running
  anything that computes (cross-dataset runs, SuSiEx) requires signing in, and the results are
  owned by that user.
- **Retention clock:** 15 days from creation, not from last use, so the deletion date shown on
  the card never moves. The warning email goes out 3 days before deletion.
- **Accounts are kept** when their projects expire. Only data is deleted on the 15-day cycle.
  Users can delete their own account at any time, which deletes all their projects.
- **Deployment target:** a Linux VM behind Caddy for automatic HTTPS. The Java server listens
  only on 127.0.0.1.

## Needed from the site owner before going live

- A domain name, pointed at the server.
- SMTP credentials for the sending address, for example an institutional relay, Amazon SES,
  Mailgun or Postmark.
- The server machine, with the reference panel, GFF, R and the tools installed. See `DEPLOY.md`.
