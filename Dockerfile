# syntax=docker/dockerfile:1.7
# LYNXgwas Server in a container: the app, its Java runtime and every analysis program, at the versions
# used for the article (the same as the desktop edition's image), running as an unprivileged user:
#   R 4.6.1; susieR 0.14.2, bigsnpr 1.12.21, bigstatsr 1.6.2, data.table 1.18.4, Matrix 1.7.6, coloc 5.2.3,
#   RSpectra 0.16.2, jsonlite 2.0.0; joint fine-mapping library (LYNXGWAS_R_LIB): susieR 0.16.6
#   (stephenslab/susieR@8e56a8e) and mvsusieR 0.3.0 (stephenslab/mvsusieR@8edc67a), kept apart because
#   mvsusieR needs susieR >= 0.15.54; PLINK 1.9.0-b.7.11 (19 Aug 2025); GCTA 1.94.1; MAGMA 1.10;
#   GWAMA 2.2.2; PLINK 2 (setup only: builds the reference panels); Java 21.
# Reference data (gene annotation, LD panels, LD scores, CADD, Roadmap peaks, liftOver chains) is NOT in
# the image: it lives on the /reference volume, filled once by "setup" (see docs/DEPLOY.md):
#   docker compose run --rm setup                 # GRCh37 (default); add --build all for GRCh38 too

# ── 1. Compile the app ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /src
# Third-party jar, pinned by checksum (htsjdk "all" jar = the one the desktop app bundles)
ADD --checksum=sha256:0d3f0ea8863d660f667b131a5103211279f1a1f638341f44bde3654195b0fd0d \
    https://github.com/AlsammanAlsamman/LYNXgwas/raw/main/python/lynxgwas/_bundled/lib/htsjdk-5.0.0-all.jar lib/
COPY src/ src/
COPY build.sh index.html viewer.html serpent_plot.html gene_constellation.html summary.html ./
COPY assets/ assets/
RUN sh build.sh && test -f bin/ServerMain.class

# ── 2. Programs, pinned by checksum ─────────────────────────────────────────────
FROM debian:bookworm-slim AS binaries
RUN apt-get update && apt-get install -y --no-install-recommends unzip build-essential zlib1g-dev \
 && rm -rf /var/lib/apt/lists/*
ADD --checksum=sha256:0be346f0ffc5d68bc3ae0098ec3f997b470e7d75d9548e0d64a5f40f6bb8caa2 \
    https://s3.amazonaws.com/plink1-assets/plink_linux_x86_64_20250819.zip /tmp/plink.zip
ADD --checksum=sha256:7a93097ebd6283eea901b6f85a839779fdca5e572d329d430ee6349215c6cab9 \
    https://vu.data.surfsara.nl/index.php/s/zkKbNeNOZAhFXZB/download /tmp/magma.zip
ADD --checksum=sha256:dbb2b5e5d51e278328ff297a403d3dd8ee5f5280f0b6d3d8db74b3e7fead7b49 \
    https://github.com/jianyangqt/gcta/releases/download/v1.94.1/gcta-1.94.1-linux-x86_64-static /opt/bin/gcta64
ADD --checksum=sha256:f0e1f9d4bb739aa344ee7215c76d79ea22302bc18f7f318598ca95486f89ae8a \
    https://www.geenivaramu.ee/tools/GWAMA_v2.2.2.zip /tmp/gwama.zip
ADD --checksum=sha256:214ceb3507cd93818cd1b6f915863290e77d235d3d18ffebc1a09ddbebb96af3 \
    https://s3.amazonaws.com/plink2-assets/alpha7/plink2_linux_x86_64_20261024.zip /tmp/plink2.zip
RUN mkdir -p /opt/bin /tmp/gwama \
 && unzip -q -j /tmp/plink.zip plink -d /opt/bin \
 && unzip -q -j /tmp/magma.zip magma -d /opt/bin \
 && unzip -q -j /tmp/plink2.zip plink2 -d /opt/bin \
 && unzip -q /tmp/gwama.zip -d /tmp/gwama \
 && make -C "$(dirname "$(find /tmp/gwama -name Makefile | head -1)")" \
 && find /tmp/gwama -name GWAMA -type f -exec cp {} /opt/bin/ \; \
 && chmod 0755 /opt/bin/*

# ── 3. Runtime: R 4.6.1 with the article's package versions ─────────────────────
FROM rocker/r-ver:4.6.1
RUN apt-get update && apt-get install -y --no-install-recommends openjdk-21-jre-headless curl \
 && rm -rf /var/lib/apt/lists/*
RUN R -q -e 'install.packages("remotes")' \
 && R -q -e 'v <- c(susieR="0.14.2", bigstatsr="1.6.2", bigsnpr="1.12.21", data.table="1.18.4", Matrix="1.7.6", \
                    coloc="5.2.3", RSpectra="0.16.2", jsonlite="2.0.0"); \
             for (p in names(v)) remotes::install_version(p, v[[p]], upgrade = "never"); \
             stopifnot(all(sapply(names(v), function(p) as.character(packageVersion(p)) == v[[p]])))'
ENV LYNXGWAS_R_LIB=/opt/mvsusie-lib
RUN mkdir -p "$LYNXGWAS_R_LIB" \
 && R -q -e 'lib <- Sys.getenv("LYNXGWAS_R_LIB"); \
             remotes::install_github("stephenslab/susieR@8e56a8e038e989856d106d9ca5175cc664fea9d2", lib = lib, upgrade = "never"); \
             remotes::install_github("stephenslab/mvsusieR@8edc67afa11cfcfc7e057595da96db40962cdd7f", lib = lib, upgrade = "never"); \
             stopifnot(packageVersion("mvsusieR", lib.loc = lib) == "0.3.0")'

COPY --from=binaries /opt/bin/ /opt/tools/bin/
RUN ln -s /opt/tools/bin/gcta64 /opt/tools/bin/gcta64.exe   # COJO's default program name (tools/cojo_conditional.yaml)

WORKDIR /app
COPY --from=build /src/bin/ bin/
COPY --from=build /src/lib/ lib/
COPY --from=build /src/index.html /src/viewer.html /src/serpent_plot.html /src/gene_constellation.html /src/summary.html ./
COPY assets/ assets/
COPY web/ web/
COPY tools/ tools/
COPY regulatory_links/ regulatory_links/
COPY docs/images/ docs/images/
COPY config/global.default.json /app/defaults/global.json
COPY config/required_data.json /app/defaults/
COPY docker/entrypoint.sh /usr/local/bin/lynxgwas-entrypoint

# Writable state lives on the /data volume; reference data on the /reference volume (read-only while the
# server runs, writable for "setup"). The app uses paths relative to /app, so link them there.
RUN groupadd --system --gid 10001 lynxgwas \
 && useradd --system --uid 10001 --gid 10001 --home-dir /data --shell /usr/sbin/nologin lynxgwas \
 && ln -s /opt/tools/bin/plink bin/plink \
 && for d in projects output config gwascatalog_data; do ln -s /data/$d /app/$d; done \
 && ln -s /reference /app/resources \
 && ln -s /reference/regulatory_data /app/regulatory_data \
 && mkdir -p /data /reference \
 && chown lynxgwas:lynxgwas /data /reference \
 && chmod 0750 /data \
 && chmod 0755 /usr/local/bin/lynxgwas-entrypoint

ENV PATH="/opt/tools/bin:${PATH}" \
    LYNXGWAS_HOME=/app \
    LYNX_SERVER_CONFIG=/config/server.properties \
    HOME=/tmp \
    TMPDIR=/tmp \
    JAVA_OPTS="-Xmx6g" \
    LANG=C.UTF-8

USER 10001:10001
EXPOSE 8765
VOLUME ["/data"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -fsS -o /dev/null http://127.0.0.1:8765/api/auth/me || exit 1
ENTRYPOINT ["/usr/local/bin/lynxgwas-entrypoint"]
