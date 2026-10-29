# syntax=docker/dockerfile:1.7
# LYNXgwas Server in a container: the app, its Java runtime and every analysis tool
# (PLINK 1.9, GCTA, MAGMA, R with susieR/coloc/bigsnpr), running as an unprivileged user.
# Reference data (GFF3, LD panel, LD scores) is NOT in the image: mount it read-only at /reference.
# See docs/DEPLOY.md, "Running in Docker".

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

# ── 2. Analysis tools (R packages and GCTA from conda-forge/bioconda, versions as load-tested) ──
FROM mambaorg/micromamba:2.0.5 AS tools
USER root
COPY docker/environment.yml /tmp/environment.yml
RUN micromamba create -y -p /opt/env -f /tmp/environment.yml && micromamba clean -a -y

FROM debian:bookworm-slim AS binaries
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
ADD --checksum=sha256:1162eca207ecec12b03397a3a9be5eed0527f5d21c173dc24335340ddd191bc3 \
    https://s3.amazonaws.com/plink1-assets/plink_linux_x86_64_20231211.zip /tmp/plink.zip
ADD --checksum=sha256:7a93097ebd6283eea901b6f85a839779fdca5e572d329d430ee6349215c6cab9 \
    https://vu.data.surfsara.nl/index.php/s/zkKbNeNOZAhFXZB/download /tmp/magma.zip
RUN mkdir -p /opt/bin \
 && unzip -q -j /tmp/plink.zip plink -d /opt/bin \
 && unzip -q -j /tmp/magma.zip magma -d /opt/bin \
 && chmod 0755 /opt/bin/plink /opt/bin/magma

# ── 3. Runtime ──────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-noble
RUN apt-get update && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

COPY --from=tools /opt/env /opt/env
COPY --from=binaries /opt/bin/ /opt/tools/bin/
# Only these programs go on PATH (not the whole conda env)
RUN ln -s /opt/env/bin/Rscript /opt/tools/bin/Rscript \
 && ln -s /opt/env/bin/R       /opt/tools/bin/R \
 && ln -s /opt/env/bin/gcta64  /opt/tools/bin/gcta64 \
 # COJO on Linux still looks for "gcta64.exe" (tools/cojo_conditional.yaml default); remove once fixed
 && ln -s /opt/env/bin/gcta64  /opt/tools/bin/gcta64.exe

WORKDIR /app
COPY --from=build /src/bin/ bin/
COPY --from=build /src/lib/ lib/
COPY --from=build /src/index.html /src/viewer.html /src/serpent_plot.html /src/gene_constellation.html /src/summary.html ./
COPY assets/ assets/
COPY web/ web/
COPY tools/ tools/
COPY docs/images/ docs/images/
COPY config/global.json /app/defaults/global.json
COPY docker/entrypoint.sh /usr/local/bin/lynxgwas-entrypoint

# Writable state lives on the /data volume; reference data on the read-only /reference mount.
# The app uses paths relative to /app, so link them there. /app itself stays read-only.
RUN groupadd --system --gid 10001 lynxgwas \
 && useradd --system --uid 10001 --gid 10001 --home-dir /data --shell /usr/sbin/nologin lynxgwas \
 && ln -s /opt/tools/bin/plink bin/plink \
 && for d in projects output config gwascatalog_data; do ln -s /data/$d /app/$d; done \
 && ln -s /reference /app/resources \
 && mkdir -p /data /reference \
 && chown lynxgwas:lynxgwas /data \
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
