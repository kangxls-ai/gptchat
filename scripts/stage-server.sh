#!/usr/bin/env bash
set -euo pipefail
project="$(cd "$(dirname "$0")/.." && pwd)"
output="${1:-$project/dist/server}"
mkdir -p "$output"
cp "$project/server/target/myai-server.jar" "$project/docs/DEPLOYMENT.md" "$output/"
for name in start-gateway.sh start-macos.sh start-linux.sh gateway-paths.sh show-pairing.sh export-pairing.sh; do
  install -m 755 "$project/scripts/$name" "$output/$name"
done
for name in Start-Gateway.ps1 Gateway-Paths.ps1 Show-Pairing.ps1 Export-Pairing.ps1; do
  cp "$project/scripts/$name" "$output/$name"
done
