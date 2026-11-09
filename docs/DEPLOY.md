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
its `bin` folder on `PATH` or set `LYNXGWAS_RSCRIPT`. The versions used for the article are those of
the Docker image (section 8); `check` reports what is found.

**Reference data: `setup` and `check`.** Everything the analyses need besides the program is listed in
`config/required_data.json`, with its source, size and the analyses that use it. Run these from the app
folder (the one with `projects/`); in Docker, see section 8.

```sh
./lynxgwas-server check                      # what is installed, and which analyses lack what (exit 1 if anything required is missing)
./lynxgwas-server setup                      # lists what is missing for GRCh37 and its download size
./lynxgwas-server setup --yes                # downloads and prepares it
./lynxgwas-server setup --yes --build all    # GRCh37 and GRCh38
./lynxgwas-server setup --yes --skip-optional  # without CADD and the Roadmap peaks
```

| Item | Build | Required | Used by | Download |
|---|---|---|---|---|
| GENCODE v37 gene annotation (GRCh37 lift / GRCh38) | 37 / 38 | yes | genes, MAGMA, ranking, views | 69 / 55 MB |
| 1000 Genomes EUR panel, 503 samples (phase 3 / high coverage), built with PLINK 2 | 37 / 38 | yes | loci, LD, SuSiE, COJO, MAGMA, local h2, joint fine-mapping | 3.8 / 4 GB |
| LD scores `eur_w_ld_chr` (Zenodo 8182036); GRCh38: positions lifted at setup | 37 / 38 | yes | LDSC h2, sample-size check, ranking | 33 MB |
| UCSC liftOver chains hg19->hg38 and hg38->hg19 | both | yes | GWAS Catalog, search, mixed-build runs | 1.5 MB |
| CADD v1.6 SNV PHRED (gnomAD 2.1.1 / 3.0) + index | 37 / 38 | no | ranking (CADD feature) | 2.4 / 6.4 GB |
| Roadmap histone peaks of the mapped tissues; GRCh38: lifted at setup | 37 / 38 | no | regulatory enrichment and tracks, ranking | 40 MB |

`check` also tests the programs (PLINK 1.9, GCTA, MAGMA, GWAMA; PLINK 2 for building the panels) and the
two R libraries (the main one with susieR 0.14.2, coloc, bigsnpr, data.table, jsonlite, Matrix, RSpectra;
the joint one in `LYNXGWAS_R_LIB` with mvsusieR 0.3.0 and susieR 0.16.6). The server logs a `[WARN]` at
startup for every required GRCh37 item that is missing. Point `ref.panel.path` / `gff3.file` (and the
`.grch38` variants) at the panels and annotations `setup` made; the defaults in
`docker/server.docker.properties` already do.

Without an optional item the analysis still runs but says what it lacks: without CADD the ranking gives
every SNP the same CADD value, so that feature has no effect; without the Roadmap peaks the regulatory
enrichment refuses to run instead of reporting zero overlaps.

**Genome builds.** GRCh37 is the default. A GRCh38 project uses the GRCh38 panel, annotation, LD scores,
CADD and peaks (`setup --build 38`). A cross-dataset run over datasets on both builds works in GRCh38:
the GRCh37 datasets are lifted with the UCSC chain (SNPs without a mapping are left out), the run's
reference panel must be a GRCh38 one, and the Serpent Plot and Gene Constellation name the lifted
datasets.

**Public datasets: `import-pack`.** The 30 datasets of the article (results only, no summary statistics)
are one pack, `lynxgwas-example-data-v2.zip`. Install a pack as public, read-only datasets with

```sh
./lynxgwas-server import-pack lynxgwas-example-data-v2.zip   # a pack file
./lynxgwas-server import-pack zenodo                          # the published one (config/example_data.json)
```

Projects and cross-dataset runs a pack installs this way belong to no workspace, so they are public
(`public.projects=*`); existing projects with the same name are kept. Restart the server afterwards. To
make a pack from projects of an installation: `./lynxgwas-server export-pack [--with-gwas] [--with-runs]
--out pack.zip <project-id> ...`. Users export their own projects and import packs into their workspace
from the home page (More > Export my projects / Import a pack).

**Public results.** Results computed on the public datasets with the desktop app are shared
with the server through the project folders: fine-mapping, MAGMA, local and genome-wide
heritability, and causal-SNP rankings appear read-only to everyone. Joint fine-mapping runs over
a public cross-dataset run are public when they have no owner: copy their `meta.json`,
`result.json` and `manifest.json` into `app/output/mvsusie/<id>/`.

A cross-dataset run without an `owner_id` is public; so is a joint fine-mapping run without an owner in
`app/output/mvsusie/<id>/`.

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

The image holds the app, Java 21 and every analysis program at the versions used for the article:
R 4.6.1 with susieR 0.14.2, bigsnpr 1.12.21, coloc 5.2.3 and the other packages, the joint
fine-mapping library (mvsusieR 0.3.0 with susieR 0.16.6, in `LYNXGWAS_R_LIB`), PLINK 1.9 (19 Aug 2025),
GCTA 1.94.1, MAGMA 1.10, GWAMA 2.2.2, and PLINK 2 for `setup`. Every download is pinned by checksum.
Reference data stays outside the image, in the reference folder, filled once by `setup`.

**How it is isolated** (`docker-compose.yml`): runs as an unprivileged user (uid 10001), read-only
system, no Linux capabilities, `no-new-privileges`, limits on CPU, memory and processes, a `noexec`
`/tmp`, and the port published on `127.0.0.1` only. The container sees only two host locations:
the `lynxgwas-data` volume (read-write: projects, accounts, results) and the reference folder
(read-only). It still has outbound internet access, which the rsID/GWAS Catalog/Open Targets
lookups need.

```sh
cp .env.example .env                 # reference folder, pepper, CPU/memory limits
nano docker/server.docker.properties # public.origin, storage limits
docker compose --profile setup run --rm setup             # once: reference data for GRCh37 (several GB)
docker compose --profile setup run --rm setup setup --yes --build all   # GRCh37 and GRCh38
docker compose run --rm lynxgwas check                    # what each analysis still lacks
docker compose --profile setup run --rm -v "$PWD/packs:/packs:ro" setup import-pack /packs/lynxgwas-example-data-v2.zip  # setup: the reference folder is writable (the pack's Roadmap peaks)
docker compose up -d --build                  # behind your own reverse proxy (127.0.0.1:8765)
docker compose --profile https up -d --build  # or with the bundled Caddy (HTTPS on 80/443)
docker compose logs -f lynxgwas
```

The `setup` service is the only one that mounts the reference folder writable; the server mounts it
read-only. `setup` lays it out as the properties file expects:

| Path under the reference folder | Used for |
|---|---|
| `gencode.v37lift37.annotation.gff3`, `gencode.v37.annotation.gff3` | gene annotation (GRCh37, GRCh38) |
| `panels/eur_grch37.{bed,bim,fam}`, `panels/eur_grch38.{bed,bim,fam}` | LD reference panels |
| `ldsc/eur_w_ld_chr/`, `ldsc/eur_w_ld_chr_grch38/` | SNP-heritability, sample-size check, ranking |
| `cadd/gnomad.genomes.r2.1.1.snv.tsv.gz`, `cadd/gnomad.genomes.r3.0.snv.tsv.gz` (+ `.tbi`) | optional: CADD in the ranking |
| `regulatory_data/E*/`, `regulatory_data/GRCh38/E*/` | optional: Roadmap peaks |
| `hg19ToHg38.over.chain`, `hg38ToHg19.over.chain` | liftOver |

Notes:
- Size the limits from the load test (`benchmark/LYNXgwas_Server_Load_Test_Report.pdf`):
  16 CPUs and 32 GB RAM on the host; give the container `LYNXGWAS_MEMORY=24g` or more, because
  each loci-identification PLINK call reserves 6 GB.
- Back up the `lynxgwas-data` volume (`docker run --rm -v lynxgwas-server_lynxgwas-data:/d -v $PWD:/b
  alpine tar czf /b/lynxgwas-data.tgz -C /d .`).
- Not included in the image: SuSiEx and the UK Biobank LD reader (needs Python).
- Update: `git pull && docker compose up -d --build`. The data volume is kept.

