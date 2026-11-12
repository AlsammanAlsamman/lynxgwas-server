#!/bin/sh
# Prepares the /data volume and starts LYNXgwas Server (runs as the unprivileged lynxgwas user).
# The first argument may instead be a command: check, setup, import-pack or export-pack (see docs/DEPLOY.md).
set -eu

for d in projects output config gwascatalog_data example-data server-data; do
  mkdir -p "/data/$d"
done
chmod 0700 /data/server-data            # sessions, secret key: owner only
[ -f /data/config/global.json ] || cp /app/defaults/global.json /data/config/global.json
# The list of required data belongs to the app version, not to the installation: always the image's
cp -f /app/defaults/required_data.json /data/config/required_data.json
# Reference volume writable (setup): the Roadmap peaks go in a folder of their own
if [ -w /reference ]; then mkdir -p /reference/regulatory_data; fi

cd /app
case "${1:-}" in
  check|setup|import-pack|export-pack)
    # shellcheck disable=SC2086
    exec java $JAVA_OPTS -Djava.awt.headless=true -cp "bin:lib/*" ServerMain "$@" ;;
esac

if [ ! -f "$LYNX_SERVER_CONFIG" ]; then
  echo "Missing $LYNX_SERVER_CONFIG: mount your settings there (see docker/server.docker.properties)." >&2
  exit 1
fi

# shellcheck disable=SC2086  # JAVA_OPTS is a list of options
exec java $JAVA_OPTS -Djava.awt.headless=true -cp "bin:lib/*" ServerMain "$@"
