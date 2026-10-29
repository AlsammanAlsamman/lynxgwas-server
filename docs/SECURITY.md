# LYNXgwas Server: security model

## What is protected, and how

| Area | Protection | Code |
|---|---|---|
| Transport | HTTPS only, through a reverse proxy. HSTS. The Java server binds to 127.0.0.1 | `deploy/Caddyfile`, `SecurityGate` |
| Personal data | Nothing that identifies a person: no accounts, emails, passwords or names. A workspace file holds only a random id, two timestamps and keyed digests of its tokens. No IP address is stored with it (IPs are used in memory for rate limits only). Note for privacy reviews: the workspace cookie is a persistent pseudonymous identifier, and the reverse proxy's access log contains IPs (set its retention to your policy). Uploaded GWAS summary statistics are aggregate data | `WorkspaceService` |
| Workspaces | Created on demand (first project, "My projects"), never just by visiting. 256-bit random device token in an `HttpOnly`, `SameSite=Strict`, `Secure`, `__Host-` cookie, kept alive while used, stored server-side only as an HMAC with the server pepper. Each browser has its own token; up to 20 per workspace | `WorkspaceService`, `SecurityGate` |
| Workspace links | Optional, made only on request. Opening one always asks first, warning that whoever made the link sees what is uploaded (so nobody can be silently moved into someone else's workspace): a separate 256-bit token, stored only as an HMAC, put in the URL fragment (`#workspace=`), which browsers never send to servers or in `Referer`. The page removes it from the address bar at once. A new link voids the old one. Opening a link is rate-limited per IP | `WorkspaceService`, `ServerApi`, `web/lynx-server.js` |
| Abuse | New workspaces limited per IP per hour (IPv6: per /64; `max.new.workspaces.per.ip.per.hour`), plus the global per-IP request limit. Only failed link attempts count against the link limit. Server-wide storage cap (`max.storage.total.mb`, 100 GB by default). Space is reserved before an upload streams, so parallel uploads cannot overshoot either quota, and the project count is checked atomically. Idle workspaces removed after `workspace.idle.days` | `ServerContext`, `ServerApi`, `ServerMain` |
| CSRF | Every POST/DELETE needs a matching `Origin` (or `Sec-Fetch-Site: same-origin`) **and**, with a workspace, its `X-Lynx-CSRF` token (derived from the device token). No CORS headers at all | `SecurityGate`, `web/lynx-server.js` |
| Authorization | Route allowlist: unlisted paths are 404 before any handler runs. Every project action checks access at the point of use: public datasets are read-only, user projects belong to one workspace, and other workspaces get 404 | `SecurityGate`, `ProjectRegistry`, `LocalServer` |
| Jobs | Cross-dataset runs and SuSiEx jobs have owners. Analysis jobs are tied to their project. Other workspaces can't see, poll or delete them | `LocalServer` |
| Files | Clients never supply paths. GWAS files are uploaded into the project and normalised to TSV. Gzip is size-capped. Binary content is refused. Reference paths come from server.properties. Downloads are limited to `exports/`. All ids are validated. Deletion never follows symlinks | `ServerApi`, `ConfigPolicy`, `FileSafety` |
| Commands | Program locations come from the server install. Every analysis parameter must be a plain token (`[A-Za-z0-9._+-]`), so nothing reaches a shell, R script or path as syntax | `LocalServer.projectAnalysisRun`, `ConfigPolicy` |
| Resources | Quotas on projects, storage and upload size. Server-wide storage cap. One heavy job per workspace, 4 in total. PLINK memory is capped. The search threshold is fixed (no forced index rebuilds). Request bodies are limited | `JobLimiter`, `PlinkRunner`, `SecurityGate` |
| Headers | CSP (`object-src 'none'`, `frame-ancestors 'none'`, `connect-src 'self'`), `nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, COOP/CORP, `Cache-Control: no-store` | `SecurityGate` |
| Static files | Explicit allowlist: four pages, the server JS/CSS, and images/vendor scripts under `assets/` and `docs/images/`. Nothing else in the app folder is reachable | `LocalServer.staticFiles` |
| Retention | User projects are deleted 15 days after creation (the page shows a countdown; there is no email). Users' runs expire too. Workspaces unused for 30 days are removed | `ProjectRegistry.sweep`, `ServerMain` |
| Secrets | The pepper comes from an environment variable (or a generated owner-only key file). `data.dir` is owner-only (0700) and never web-served | `ServerConfig`, `ServerContext` |

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
- **Losing access.** There is no recovery: a workspace opens only from a browser that has it or
  from its link. That is the price of keeping no contact details; projects expire in 15 days anyway.
- **Anyone with the link, or with the browser, has the workspace.** Users are told to keep links
  private. "Forget this browser" and "Delete workspace" are in the Workspace panel.
- **Links are bearer credentials** with no expiry until replaced, and remain in the browser history
  of the computer they were opened on.
- **Script sources.** The CSP allows scripts from public CDNs (unpkg, jsDelivr, cdnjs). Self-hosting
  them or pinning them with Subresource Integrity would harden against a compromised CDN.
- **No bot check.** Abuse is limited by per-IP limits and the storage cap. A privacy-friendly
  challenge (for example a self-hosted proof-of-work such as ALTCHA) can be added if needed.
- **One-server design.** Rate limits and job limits are per process. Run a single instance.
- **Public datasets** are shared caches: GET requests on them may write cache files (for example,
  Open Targets lookups) into their folders.

## Tests

- `tests/ServerSecurityTest.java`: unit tests for tokens, workspaces and links (nothing identifying
  or usable stored, restart, pepper change, device cap, deletion, idle purge), registry and
  retention (with a fake clock), path safety, config policy, upload normalisation and limiters.
- `tests/ServerHttpTest.java`: end-to-end HTTP tests against a live server as a visitor without a
  workspace, two workspaces, a second browser opening a workspace link, and a cross-site attacker.
  They cover traversal, removed endpoints (including the old email/password ones), CORS, CSRF,
  cross-workspace isolation, read-only public data, config path injection, links, forgetting a
  browser, workspace deletion and the per-IP limit on new workspaces.
  They also include a regression test for per-request state leaking between users.
