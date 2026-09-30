# LYNXgwas Server: security model

## What is protected, and how

| Area | Protection | Code |
|---|---|---|
| Transport | HTTPS only, through a reverse proxy. HSTS. The Java server binds to 127.0.0.1 | `deploy/Caddyfile`, `SecurityGate` |
| Passwords | PBKDF2-HMAC-SHA256 with 600k iterations and a per-user salt, rehashed when the cost rises. At least 10 characters; common patterns refused | `Crypto`, `AccountService` |
| Email verification | 6-digit `SecureRandom` code, 15 minutes, single use, 5 guesses then burned, stored only as an HMAC. Accounts are unusable until verified. Unverified accounts are deleted after 48 h | `AccountService` |
| Account enumeration | Register, resend and forgot-password answer identically for any email. Mail goes out from a queue (no timing signal). A duplicate sign-up emails the real owner instead | `AccountService`, `Mailer` |
| Brute force | Lockout after 5 failed logins (15 min). Per-IP limits on auth (20 burst, 10/min) and on emails sent (5 burst, 2/min). Global per-IP limit | `AccountService`, `RequestLimiter` |
| Sessions | 256-bit random token in an `HttpOnly`, `SameSite=Strict`, `Secure`, `__Host-` cookie. Stored server-side only as an HMAC. 2 h idle and 24 h absolute expiry. A password reset revokes all sessions. The session is replaced at every sign-in (no fixation) | `AccountService`, `SecurityGate` |
| CSRF | Every POST/DELETE needs a matching `Origin` (or `Sec-Fetch-Site: same-origin`) **and**, when signed in, the per-session `X-Lynx-CSRF` token. No CORS headers at all | `SecurityGate`, `web/lynx-server.js` |
| Authorization | Route allowlist: unlisted paths are 404 before any handler runs. Every project action checks access at the point of use: public datasets are read-only, user projects are owner-only, and other users get 404 | `SecurityGate`, `ProjectRegistry`, `LocalServer` |
| Jobs | Cross-dataset runs and SuSiEx jobs have owners. Analysis jobs are tied to their project. Other users can't see, poll or delete them | `LocalServer` |
| Files | Clients never supply paths. GWAS files are uploaded into the project and normalised to TSV. Gzip is size-capped. Binary content is refused. Reference paths come from server.properties. Downloads are limited to `exports/`. All ids are validated. Deletion never follows symlinks | `ServerApi`, `ConfigPolicy`, `FileSafety` |
| Commands | Program locations come from the server install. Every analysis parameter must be a plain token (`[A-Za-z0-9._+-]`), so nothing reaches a shell, R script or path as syntax | `LocalServer.projectAnalysisRun`, `ConfigPolicy` |
| Resources | Quotas on projects, storage and upload size. One heavy job per user, 4 in total. PLINK memory is capped. The search threshold is fixed (no forced index rebuilds). Request bodies are limited | `JobLimiter`, `PlinkRunner`, `SecurityGate` |
| Headers | CSP (`object-src 'none'`, `frame-ancestors 'none'`, `connect-src 'self'`), `nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, COOP/CORP, `Cache-Control: no-store` | `SecurityGate` |
| Static files | Explicit allowlist: four pages, the server JS/CSS, and images/vendor scripts under `assets/` and `docs/images/`. Nothing else in the app folder is reachable | `LocalServer.staticFiles` |
| Retention | User projects are deleted 15 days after creation. Warning email 3 days before; notice on deletion. Users' runs expire too | `ProjectRegistry.sweep`, `ServerMain` |
| Secrets | SMTP password and pepper come from environment variables. `data.dir` is owner-only (0700) and never web-served | `ServerConfig`, `ServerContext` |

## Removed from the desktop app (they cannot be made safe for untrusted users)

- AI agent (LLM chat and tool calls)
- GitHub token, push and import
- Shared storage (`git clone`)
- HPC/SSH submission
- Native file and folder pickers
- `save-pdf`
- `/api/global-config` editing
- `peek-file-header` and `rsid-detect` (they took arbitrary paths)
- Annotation file read/write by path
- Search index rebuild on demand
- Cross-project info export
- The legacy single-project routes

## Known limits and follow-ups

- **Inline scripts.** The UI still uses inline scripts and `onclick` attributes, so the CSP allows
  `'unsafe-inline'` for scripts. User-supplied text is escaped when rendered, and only the owner
  ever sees their own project names. Moving to external scripts with a nonce-based CSP would
  remove this class of risk entirely.
- **Sessions are in memory.** A restart signs everyone out. That is the safe direction, but it
  is visible to users.
- **One-server design.** Rate limits and job limits are per process. Run a single instance.
- **Two-factor authentication** is not implemented.
- **Public datasets** are shared caches: GET requests on them may write cache files (for example,
  Open Targets lookups) into their folders.

## Tests

- `tests/ServerSecurityTest.java`: unit tests for crypto, accounts, codes, lockout, sessions,
  registry and retention (with a fake clock), path safety, config policy, upload normalisation
  and limiters.
- `tests/ServerHttpTest.java`: end-to-end HTTP tests against a live server as an anonymous
  visitor, two users and a cross-site attacker. They cover traversal, removed endpoints, CORS,
  CSRF, cross-user isolation, read-only public data, config path injection and account deletion.
  They also include a regression test for per-request state leaking between users.
