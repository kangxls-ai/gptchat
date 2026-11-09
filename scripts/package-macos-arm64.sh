#!/usr/bin/env bash
set -euo pipefail

project="$(cd "$(dirname "$0")/.." && pwd)"
jar="$project/desktop/target/myai-desktop.jar"
dest="$project/dist/macos"
test "$(uname -s)" = Darwin || { echo 'Run this script on macOS.' >&2; exit 1; }
test -f "$jar" || { echo 'Run mvn -B verify first.' >&2; exit 1; }

temp="$(mktemp -d "${TMPDIR:-/tmp}/myai-arm64.XXXXXX")"
trap 'rm -rf -- "$temp"' EXIT
staging="$temp/staging"
app="$staging/MyAI.app/Contents"
mkdir -p "$app/MacOS" "$app/Resources" "$temp/jre" "$dest"
cp "$project/desktop/macos/Info.plist" "$app/Info.plist"
cp "$jar" "$app/Resources/myai-desktop.jar"
java -Djava.awt.headless=true -jar "$jar" --write-icons "$temp/icons"
cp "$temp/icons/MyAI.icns" "$app/Resources/"
plutil -lint "$app/Info.plist"

api='https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=aarch64&image_type=jre&os=mac&vendor=eclipse'
curl --fail --silent --show-error --location --retry 3 --max-time 45 "$api" -o "$temp/assets.json"
link="$(jq -er '.[0].binary.package.link' "$temp/assets.json")"
checksum="$(jq -er '.[0].binary.package.checksum' "$temp/assets.json")"
[[ "$link" == https://github.com/adoptium/temurin21-binaries/releases/download/* ]]
[[ "$checksum" =~ ^[0-9a-f]{64}$ ]]
curl --fail --silent --show-error --location --retry 3 --max-time 180 "$link" -o "$temp/jre.tgz"
(cd "$temp" && printf '%s  jre.tgz\n' "$checksum" | shasum -a 256 -c -)
tar -xzf "$temp/jre.tgz" -C "$temp/jre"
runtimes=("$temp"/jre/*/Contents/Home/bin/java)
test "${#runtimes[@]}" -eq 1 && test -x "${runtimes[0]}"
runtime="${runtimes[0]%/Contents/Home/bin/java}"
ditto "$runtime" "$app/runtime"

xcrun clang -arch arm64 -mmacosx-version-min=11.0 -Wall -Wextra -Werror -O2 \
  "$project/desktop/macos/launcher.c" -o "$app/MacOS/MyAI"
test "$(xcrun lipo -archs "$app/MacOS/MyAI")" = arm64
test "$(xcrun lipo -archs "$app/runtime/Contents/Home/bin/java")" = arm64
ln -s /Applications "$staging/Applications"
hdiutil create -volname MyAI -srcfolder "$staging" -ov -format UDZO "$dest/MyAI-arm64.dmg"
hdiutil verify "$dest/MyAI-arm64.dmg"
