#!/usr/bin/env bash
set -euo pipefail
project="$(cd "$(dirname "$0")/.." && pwd)"
version="${1:-0.4.0}"
jar="$project/desktop/target/myai-desktop.jar"
work="$project/desktop/target/jpackage"
dest="$project/dist/macos"
test -f "$jar" || { echo 'Run mvn -B verify first.' >&2; exit 1; }
mkdir -p "$work/input" "$work/icons" "$dest"
cp "$jar" "$work/input/"
java -Djava.awt.headless=true -jar "$jar" --write-icons "$work/icons"
jpackage --type dmg --name MyAI --app-version "$version" --dest "$dest" --input "$work/input" \
  --main-jar myai-desktop.jar --main-class myai.desktop.MyAI --icon "$work/icons/MyAI.icns" \
  --mac-package-identifier ai.myai.desktop --add-modules java.desktop,java.logging,java.prefs,jdk.crypto.ec,jdk.charsets
