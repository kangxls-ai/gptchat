#!/usr/bin/env bash
set -euo pipefail
umask 077
source "$(dirname "$0")/gateway-paths.sh"

if (( $# > 1 )); then
  echo "Usage: $0 [state-directory]" >&2
  exit 1
fi
state="${1:-$(myai_state_dir)}"
target="$myai_scripts/pairing.txt"
if [[ -L "$target" || ( -e "$target" && ! -f "$target" ) ]]; then
  echo 'Refusing to replace a pairing.txt symlink or non-file.' >&2
  exit 1
fi
if [[ -d "$state" && "$(cd "$state" && pwd -P)" == "$(cd "$myai_scripts" && pwd -P)" ]]; then
  echo 'Choose a state directory different from the script directory.' >&2
  exit 1
fi

password="$(bash "$myai_scripts/show-pairing.sh" "$state")"
temporary="$(mktemp "$myai_scripts/.myai-pairing.XXXXXX")"
trap 'rm -f -- "$temporary"' EXIT
chmod 600 "$temporary"
printf '%s\n' "$password" > "$temporary"
mv -f -- "$temporary" "$target"
printf 'Pairing password written to %s\n' "$target"
