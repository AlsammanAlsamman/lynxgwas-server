# Deploying LYNXgwas Server

The server ships as a **self-contained package**: the app plus its own Java runtime.
Nothing needs to be installed on the server and no admin rights are needed to run it.

## 1. Build the package (on a developer machine that has a JDK 17)

```sh
# Linux server (most common); pass the Linux JDK 17.0.10 folder when building on Windows/macOS
scripts/package-server.sh linux-x64 /path/to/jdk-17.0.10+7
# Windows server
scripts/package-server.sh windows-x64
```

This produces `dist/lynxgwas-server-linux-x64.tar.gz` (or `-windows-x64.zip`):

```
lynxgwas-server-linux-x64/
  lynxgwas-server          launcher (uses runtime/, not any system Java)
  lynxgwas-server.bat      Windows launcher
  runtime/                 trimmed Java 17 runtime (~50 MB)
  app/                     compiled server, web pages, tool descriptors
  server.properties.example
  deploy/                  Caddyfile, systemd unit
```

## 2. Unpack on the server

```sh
tar -xzf lynxgwas-server-linux-x64.tar.gz
cd lynxgwas-server-linux-x64
cp server.properties.example server.properties     # then edit it
```

## 3. Data folders

| What | Where | Notes |
|---|---|---|
| Server state (accounts, ownership, secret key) | `data.dir` in server.properties | Private: owner-only permissions. Back it up. |
| Projects (public datasets and user projects) | `app/projects` | Make it a symlink to a data disk: `ln -s /srv/lynxgwas/projects app/projects` |
| Cross-dataset runs | `app/output` | Can also be a symlink |
| Reference panel and GFF | `ref.panel.path`, `gff3.file` | Read-only for the server |

**Public datasets.** Copy (or symlink) each curated dataset folder into `app/projects/`. With
`public.projects=*`, every project folder that has no owner is public and read-only.
Cross-dataset runs over public data that you want everyone to see go in `app/output/multi_locus/`.
A run without an `owner_id` is public.

**Tools.** Analyses call external programs:
- PLINK (`app/bin/plink`)
- GCTA (`app/bin/gcta64`)
- GWAMA (`app/bin/GWAMA`)
- R with `susieR`, `coloc` and `bigsnpr`
- SuSiEx (optional; set `susiex.path`)

Use the Linux builds of these tools. All of them are single binaries or user-level installs,
so no admin rights are needed. R can run from a user-level conda/micromamba environment; put
its `bin` folder on `PATH` or set `LYNXGWAS_RSCRIPT`.

## 4. Email

Verification codes, retention warnings and deletion notices are sent by SMTP over TLS:
- Set `smtp.host`, `smtp.port`, `smtp.security` and `smtp.user` in server.properties.
- Put the password in the environment variable named by `smtp.password.env`, never in the file.

Any SMTP account works, for example an institutional relay, Amazon SES, Mailgun, Postmark or
SendGrid. Leaving `smtp.host` empty writes emails to `data.dir/outbox`. Use that for testing only.

## 5. HTTPS in front (required)

The Java server listens only on `127.0.0.1:8765`. Put a TLS reverse proxy in front of it.
`deploy/Caddyfile` does this with automatic Let's Encrypt certificates. Caddy is also a single
binary with no installation needed:

```sh
./caddy run --config deploy/Caddyfile
```

In server.properties set:
- `public.origin=https://your.domain`
- `secure.cookies=true`
- `trust.proxy=true`

## 6. Run

```sh
export LYNX_SMTP_PASSWORD='...'
export LYNX_CODE_PEPPER="$(head -c 48 /dev/urandom | base64)"   # optional; else a key file is generated
./lynxgwas-server
```

To keep it running:
- **Without admin rights:** use `tmux`/`screen`, or a user service
  (`systemctl --user`, with `loginctl enable-linger` once by an admin).
- **With admin rights:** use `deploy/lynxgwas.service`, which runs as an unprivileged user
  with systemd sandboxing.

## 7. Check before opening to the public

- The startup log shows no `[WARN]` lines.
- `https://your.domain/` loads, and `http://` redirects to https (Caddy does this).
- Registering sends a real email, and the code works.
- Your projects show a green countdown bar.
- `curl -I https://your.domain/` shows the `Content-Security-Policy` and
  `Strict-Transport-Security` headers.
- Port 8765 is **not** reachable from outside: `curl http://SERVER_IP:8765` from another machine must fail.
