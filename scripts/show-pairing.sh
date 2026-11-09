#!/usr/bin/env bash
set -euo pipefail
project="$(cd "$(dirname "$0")/.." && pwd)"
state="${MYAI_DATA_DIR:-$project/server-data}/gateway-state"
pin="$(cat "$state/fingerprint.txt")"
token="$(cat "$state/pairing.txt")"
[[ "$pin" =~ ^[a-f0-9]{64}$ && "$token" =~ ^[A-Za-z0-9_-]{43}$ ]] || {
  echo 'Pairing files are incomplete or invalid.' >&2
  exit 1
}
printf '%s.%s\n' "$pin" "$token"
