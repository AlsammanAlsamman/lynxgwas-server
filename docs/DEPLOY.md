# Deploying LYNXgwas Server

The server ships as a **self-contained package**: the app plus its own Java runtime.
Nothing needs to be installed on the server and no admin rights are needed to run it.
To keep the app isolated from the rest of the server instead, run it in Docker (section 8).

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
- MAGMA (`app/bin/magma`) for the per-locus gene-based test
- R with `susieR`, `coloc`, `bigsnpr`, `data.table`, `jsonlite` and `RSpectra` (local heritability)
- For joint fine-mapping: `mvsusieR` 0.3.0, which needs `susieR` >= 0.15.54. Install both into a
  separate library and point `LYNXGWAS_R_LIB` at it, so the main library keeps the `susieR`
  version the single-dataset tools were validated with.
- SuSiEx (optional; set `susiex.path`)

Use the Linux builds of these tools. All of them are single binaries or user-level installs,
so no admin rights are needed. R can run from a user-level conda/micromamba environment; put
its `bin` folder on `PATH` or set `LYNXGWAS_RSCRIPT`.

**Reference data for the analyses** (read-only; set the environment variables in the service
file, `deploy/lynxgwas.service`):

| Data | Used by | Setting | Size |
|---|---|---|---|
| European LD scores `eur_w_ld_chr` (1000 Genomes, HapMap3; Zenodo record 8182036) | SNP-heritability (LD score regression) | `LYNXGWAS_LDSC` = the `eur_w_ld_chr` folder | 33 MB |
| CADD v1.6 GRCh37 prescored SNVs `gnomad.genomes.r2.1.1.snv.tsv.gz` + `.tbi` | Causal-SNP ranking (CADD feature) | `LYNXGWAS_CADD` = the `.tsv.gz` file | 2.4 GB |
| UK Biobank LD matrices (optional) | SuSiE / mvSuSiE LD | `LYNXGWAS_UKBB_LD` | ~9 GB |
| mvSuSiE R library | Joint fine-mapping | `LYNXGWAS_R_LIB` | — |

Without the LD scores, SNP-heritability reports that they are missing; without CADD, the
ranking gives every SNP the same background CADD value, so that feature has no effect. The
trained ranking model (`tools/snp_rank_model.json`) and the R scripts (`tools/r/`) ship in the
package.

**Public results.** Results computed on the public datasets with the desktop app are shared
with the server through the project folders: fine-mapping, MAGMA, local and genome-wide
heritability, and causal-SNP rankings appear read-only to everyone. Joint fine-mapping runs over
a public cross-dataset run are public when they have no owner: copy their `meta.json`,
`result.json` and `manifest.json` into `app/output/mvsusie/<id>/`.

## 4. No accounts, no email

Visitors don't sign up. The first time someone creates a project, their browser gets an anonymous
private workspace (see `docs/SECURITY.md`). No email server is needed and nothing that identifies a
person is stored. Set the abuse limits in server.properties: `max.storage.total.mb` (keep it below the free
space of the data disk) and `max.new.workspaces.per.ip.per.hour`.

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
- "New project" creates a private workspace; your projects show a green countdown bar.
- A workspace link (Workspace > Make a link) opens the same projects in a private browser window.
- `curl -I https://your.domain/` shows the `Content-Security-Policy` and
  `Strict-Transport-Security` headers.
- Port 8765 is **not** reachable from outside: `curl http://SERVER_IP:8765` from another machine must fail.

## 8. Running in Docker (isolated)

The container holds the app, Java, PLINK 1.9, GCTA 1.94.1, MAGMA 1.10 and R 4.3.3 with susieR,
coloc and bigsnpr. Reference data stays outside the image.

**How it is isolated** (`docker-compose.yml`): runs as an unprivileged user (uid 10001), read-only
system, no Linux capabilities, `no-new-privileges`, limits on CPU, memory and processes, a `noexec`
`/tmp`, and the port published on `127.0.0.1` only. The container sees only two host locations:
the `lynxgwas-data` volume (read-write: projects, accounts, results) and the reference folder
(read-only). It still has outbound internet access, which the rsID/GWAS Catalog/Open Targets
lookups need.

```sh
cp .env.example .env                 # reference folder, pepper, CPU/memory limits
nano docker/server.docker.properties # public.origin, storage limits, reference file names
docker compose up -d --build                  # behind your own reverse proxy (127.0.0.1:8765)
docker compose --profile https up -d --build  # or with the bundled Caddy (HTTPS on 80/443)
docker compose logs -f lynxgwas
```

Reference folder layout (mounted at `/reference`; names must match the properties file):

| Path | Used for |
|---|---|
| `gencode.v37lift37.annotation.gff3` | gene annotation |
| `panel/g1000_eur.{bed,bim,fam}` | LD reference panel |
| `panel/g1000_eur_chr1..22.{bed,bim,fam}` | optional: ~4x faster loci identification |
| `ldsc/eur_w_ld_chr/` | SNP-heritability |
| `cadd/gnomad.genomes.r2.1.1.snv.tsv.gz` + `.tbi` | optional: CADD in the causal-SNP ranking |

Per-chromosome panel files: `for c in $(seq 1 22); do plink --bfile g1000_eur --chr $c --make-bed --out g1000_eur_chr$c; done`.

Notes:
- Size the limits from the load test (`benchmark/LYNXgwas_Server_Load_Test_Report.pdf`):
  16 CPUs and 32 GB RAM on the host; give the container `LYNXGWAS_MEMORY=24g` or more, because
  each loci-identification PLINK call reserves 6 GB.
- Back up the `lynxgwas-data` volume (`docker run --rm -v lynxgwas-server_lynxgwas-data:/d -v $PWD:/b
  alpine tar czf /b/lynxgwas-data.tgz -C /d .`).
- Not included in the image: SuSiEx, mvSuSiE and the UK Biobank LD reader (needs Python).
- Update: `git pull && docker compose up -d --build`. The data volume is kept.

