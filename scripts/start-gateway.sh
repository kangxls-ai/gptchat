#!/usr/bin/env bash
set -euo pipefail
umask 077
project="$(cd "$(dirname "$0")/.." && pwd)"
address="${1:-127.0.0.1}"
port="${2:-4000}"
data="${MYAI_DATA_DIR:-$project/server-data}"
jar="$project/server/target/myai-server.jar"
test -f "$jar" || { echo 'Run mvn -B verify first.' >&2; exit 1; }
command -v "${GATEWAY_CODEX:-codex}" >/dev/null || { echo 'Install and sign in to Codex CLI first.' >&2; exit 1; }
mkdir -p "$data"
cd "$data"
exec java -jar "$jar" "$address" "$port"
