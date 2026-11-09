#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/gateway-paths.sh"
if (( $# > 1 )); then
  echo "Usage: $0 [state-directory]" >&2
  exit 1
fi
state="${1:-$(myai_state_dir)}"
if [[ ! -r "$state/fingerprint.txt" || ! -r "$state/pairing.txt" ]]; then
  printf 'Start MyAI first, or specify the correct state directory: %s\n' "$state" >&2
  exit 1
fi
pin="$(cat "$state/fingerprint.txt")"
token="$(cat "$state/pairing.txt")"
[[ "$pin" =~ ^[a-f0-9]{64}$ && "$token" =~ ^[A-Za-z0-9_-]{43}$ ]] || {
  echo 'Pairing files are incomplete or invalid.' >&2
  exit 1
}
printf '%s.%s\n' "$pin" "$token"
