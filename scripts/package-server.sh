#!/usr/bin/env bash
# Builds a self-contained LYNXgwas Server package: the app plus its own trimmed Java runtime,
# so the server machine needs NO Java installation (and no admin rights): unpack and run.
#
#   scripts/package-server.sh linux-x64   [TARGET_JDK_HOME]
#   scripts/package-server.sh windows-x64 [TARGET_JDK_HOME]
#
# jlink must use the target platform's jmods, from a JDK of the SAME version as the jlink
# running here (17.0.10). For the machine's own platform the local JDK is used; for another
# platform pass that platform's JDK folder (unpacked tarball/zip), e.g.
#   https://aka.ms/download-jdk/microsoft-jdk-17.0.10-linux-x64.tar.gz
#
# Output: dist/lynxgwas-server-<target>.tar.gz (linux) or .zip (windows)
set -euo pipefail

TARGET="${1:-linux-x64}"
TARGET_JDK="${2:-}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

case "$TARGET" in
  linux-x64|windows-x64) ;;
  *) echo "target must be linux-x64 or windows-x64" >&2; exit 2 ;;
esac

HOST_WINDOWS=false
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) HOST_WINDOWS=true ;; esac

# Modules the server needs (jdeps) + service providers jdeps cannot see: jdk.crypto.ec (TLS to the
# SMTP server), java.naming/java.xml/jdk.charsets (used by Jakarta Mail at runtime).
MODULES="java.base,java.desktop,java.logging,java.management,java.naming,java.net.http,java.scripting,java.xml,jdk.charsets,jdk.crypto.ec,jdk.httpserver,jdk.management"

echo "=== Compiling ==="
if $HOST_WINDOWS; then MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL="*" cmd.exe /c "cd /d $(cygpath -w "$ROOT") && .\build.bat" >/dev/null; else sh build.sh >/dev/null; fi
test -f bin/ServerMain.class || { echo "build failed: bin/ServerMain.class missing" >&2; exit 1; }

if [ -z "$TARGET_JDK" ]; then
  if { $HOST_WINDOWS && [ "$TARGET" = windows-x64 ]; } || { ! $HOST_WINDOWS && [ "$TARGET" = linux-x64 ]; }; then
    TARGET_JDK="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
  else
    echo "Building for $TARGET on this machine needs that platform's JDK folder as the 2nd argument." >&2
    exit 2
  fi
fi
test -d "$TARGET_JDK/jmods" || { echo "no jmods folder in $TARGET_JDK" >&2; exit 2; }

NAME="lynxgwas-server-$TARGET"
OUT="dist/$NAME"
# Never wipe a package folder that is in use (live settings, linked datasets, server state)
if [ -e "$OUT/server.properties" ] || [ -n "$(ls -A "$OUT/app/projects" 2>/dev/null)" ]; then
  echo "$OUT is in use (server.properties or app/projects present). Move it aside first; not overwriting." >&2
  exit 3
fi
rm -rf "$OUT"
mkdir -p "$OUT/app"

echo "=== Building Java runtime for $TARGET ==="
jlink --module-path "$TARGET_JDK/jmods" --add-modules "$MODULES" \
      --strip-debug --no-man-pages --no-header-files --compress=2 \
      --output "$OUT/runtime"

echo "=== Copying the app ==="
mkdir -p "$OUT/app/bin" "$OUT/app/docs" "$OUT/app/projects" "$OUT/app/output"
find bin -maxdepth 1 -name '*.class' -exec cp {} "$OUT/app/bin/" \;
for d in rsid loci export catalog opentargets; do [ -d "bin/$d" ] && cp -r "bin/$d" "$OUT/app/bin/"; done
cp -r lib tools assets web "$OUT/app/"
mkdir -p "$OUT/app/scripts" && cp scripts/ukbb_ld.py "$OUT/app/scripts/"   # UK Biobank LD reader (SuSiE, mvSuSiE)
cp -r docs/images "$OUT/app/docs/"
cp index.html viewer.html serpent_plot.html gene_constellation.html summary.html "$OUT/app/"
mkdir -p "$OUT/app/config"
[ -f config/global.json ] && cp config/global.json "$OUT/app/config/global.json.example"
cp server.properties.example LICENSE "$OUT/"
cp docs/DEPLOY.md docs/SECURITY.md "$OUT/" 2>/dev/null || true
cp -r deploy "$OUT/" 2>/dev/null || true

echo "=== Launchers ==="
cat > "$OUT/lynxgwas-server" <<'EOF'
#!/bin/sh
# Starts LYNXgwas Server with its bundled Java runtime (no Java installation needed).
# Settings: ./server.properties (or $LYNX_SERVER_CONFIG). Extra JVM options: $LYNX_JAVA_OPTS.
HERE="$(cd "$(dirname "$0")" && pwd)"
export LYNX_SERVER_CONFIG="${LYNX_SERVER_CONFIG:-$HERE/server.properties}"
if [ ! -f "$LYNX_SERVER_CONFIG" ]; then
  echo "No settings file at $LYNX_SERVER_CONFIG. Copy server.properties.example to server.properties and edit it." >&2
  exit 1
fi
cd "$HERE/app" || exit 1
umask 077
exec "$HERE/runtime/bin/java" ${LYNX_JAVA_OPTS:--Xmx8g} -Djava.awt.headless=true -cp "bin:lib/*" ServerMain "$@"
EOF
chmod +x "$OUT/lynxgwas-server"

cat > "$OUT/lynxgwas-server.bat" <<'EOF'
@echo off
rem Starts LYNXgwas Server with its bundled Java runtime (no Java installation needed).
set "HERE=%~dp0"
if "%LYNX_SERVER_CONFIG%"=="" set "LYNX_SERVER_CONFIG=%HERE%server.properties"
if not exist "%LYNX_SERVER_CONFIG%" (
  echo No settings file at %LYNX_SERVER_CONFIG%. Copy server.properties.example to server.properties and edit it.
  exit /b 1
)
if "%LYNX_JAVA_OPTS%"=="" set "LYNX_JAVA_OPTS=-Xmx8g"
cd /d "%HERE%app"
"%HERE%runtime\bin\java.exe" %LYNX_JAVA_OPTS% -Djava.awt.headless=true -cp "bin;lib/*" ServerMain %*
EOF

echo "=== Archiving ==="
cd dist
rm -f "$NAME.tar.gz" "$NAME.zip"
if [ "$TARGET" = linux-x64 ]; then
  tar -czf "$NAME.tar.gz" "$NAME"
  ls -la "$NAME.tar.gz"
else
  if command -v zip >/dev/null; then zip -qr "$NAME.zip" "$NAME"; else powershell.exe -NoProfile -Command "Compress-Archive -Path '$NAME' -DestinationPath '$NAME.zip'"; fi
  ls -la "$NAME.zip"
fi
echo "Done: dist/$NAME"
