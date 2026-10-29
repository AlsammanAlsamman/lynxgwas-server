#!/bin/sh
# Prepares the /data volume and starts LYNXgwas Server (runs as the unprivileged lynxgwas user).
set -eu

for d in projects output config gwascatalog_data server-data; do
  mkdir -p "/data/$d"
done
chmod 0700 /data/server-data            # accounts, sessions, secret key: owner only
[ -f /data/config/global.json ] || cp /app/defaults/global.json /data/config/global.json

if [ ! -f "$LYNX_SERVER_CONFIG" ]; then
  echo "Missing $LYNX_SERVER_CONFIG: mount your settings there (see docker/server.docker.properties)." >&2
  exit 1
fi

cd /app
# shellcheck disable=SC2086  # JAVA_OPTS is a list of options
exec java $JAVA_OPTS -Djava.awt.headless=true -cp "bin:lib/*" ServerMain "$@"
