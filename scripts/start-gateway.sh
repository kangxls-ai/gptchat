#!/usr/bin/env bash
set -euo pipefail
umask 077
source "$(dirname "$0")/gateway-paths.sh"
test -f "$myai_jar" || { echo 'Place myai-server.jar next to this script or run mvn -B verify.' >&2; exit 1; }
export MYAI_STATE_DIR="$(myai_state_dir)"
exec java -jar "$myai_jar" "$@"
