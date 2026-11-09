#!/usr/bin/env bash

myai_scripts="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -f "$myai_scripts/myai-server.jar" ]]; then
  myai_jar="$myai_scripts/myai-server.jar"
  myai_default_state="$myai_scripts/gateway-state"
else
  myai_project="$(cd "$myai_scripts/.." && pwd)"
  myai_jar="$myai_project/server/target/myai-server.jar"
  myai_default_state="$myai_project/server-data/gateway-state"
fi

myai_state_dir() {
  if [[ -n "${MYAI_STATE_DIR:-}" ]]; then
    printf '%s\n' "$MYAI_STATE_DIR"
  elif [[ -n "${MYAI_DATA_DIR:-}" ]]; then
    printf '%s/gateway-state\n' "$MYAI_DATA_DIR"
  else
    printf '%s\n' "$myai_default_state"
  fi
}
