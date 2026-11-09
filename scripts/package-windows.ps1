param([string]$Version = '0.4.0')
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $project 'desktop/target/myai-desktop.jar'
$work = Join-Path $project 'desktop/target/jpackage'
$inputFolder = Join-Path $work 'input'
$icons = Join-Path $work 'icons'
$dest = Join-Path $project 'dist/windows'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Run mvn -B verify first.' }
if (Test-Path -LiteralPath (Join-Path $dest 'MyAI')) { throw 'Move dist/windows/MyAI before rebuilding.' }
New-Item -ItemType Directory -Force $inputFolder,$icons,$dest | Out-Null
Copy-Item -LiteralPath $jar -Destination $inputFolder -Force
& java '-Djava.awt.headless=true' -jar $jar --write-icons $icons
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& jpackage --type app-image --name MyAI --app-version $Version --dest $dest --input $inputFolder `
    --main-jar myai-desktop.jar --main-class myai.desktop.MyAI --icon (Join-Path $icons 'MyAI.ico') `
    --add-modules 'java.desktop,java.logging,java.prefs,jdk.crypto.ec,jdk.charsets'
exit $LASTEXITCODE
