#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
launcher="${1:-start-linux.sh}"
temp="$(mktemp -d "${TMPDIR:-/tmp}/myai-server-test.XXXXXX")"
server_pid=''
cleanup() {
  if [[ -n "$server_pid" ]]; then kill "$server_pid" 2>/dev/null || true; wait "$server_pid" 2>/dev/null || true; fi
  rm -rf -- "$temp"
}
trap cleanup EXIT
bash "$root/scripts/stage-server.sh" "$temp/package"
cd "$temp/package"
unset MYAI_STATE_DIR MYAI_DATA_DIR

wait_for_server() {
  for attempt in {1..100}; do
    if grep -q '^Listening' "$temp/server.log"; then return; fi
    if ! kill -0 "$server_pid" 2>/dev/null; then cat "$temp/server.log" >&2; return 1; fi
    sleep 0.15
  done
  cat "$temp/server.log" >&2
  return 1
}

java -jar myai-server.jar > "$temp/server.log" 2>&1 &
server_pid=$!
wait_for_server
grep -q '127.0.0.1:4000' "$temp/server.log"
bash export-pairing.sh
test "$(cat pairing.txt)" = "$(bash show-pairing.sh)"
chmod 644 pairing.txt
bash export-pairing.sh
if [[ "$(uname -s)" == Darwin ]]; then mode="$(stat -f '%Lp' pairing.txt)"; else mode="$(stat -c '%a' pairing.txt)"; fi
test "$mode" = 600
test "$(wc -c < pairing.txt | tr -d ' ')" = 109
kill "$server_pid"
wait "$server_pid" 2>/dev/null || true
server_pid=''

state="$temp/custom state"
bash "$launcher" --port 18400 --state-dir "$state" > "$temp/server.log" 2>&1 &
server_pid=$!
wait_for_server
grep -q '127.0.0.1:18400' "$temp/server.log"
MYAI_STATE_DIR="$state" bash export-pairing.sh
test "$(cat pairing.txt)" = "$(bash show-pairing.sh "$state")"
if bash export-pairing.sh "$temp/missing" > "$temp/error.log" 2>&1; then
  echo 'Export unexpectedly succeeded without a configured gateway' >&2
  exit 1
fi
test "$(cat pairing.txt)" = "$(bash show-pairing.sh "$state")"
echo 'Native startup, platform script startup and private pairing export verified.'
