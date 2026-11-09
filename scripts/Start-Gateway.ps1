param(
    [string]$BindAddress = '127.0.0.1',
    [ValidateRange(1, 65535)][int]$Port = 4000,
    [switch]$AllowRemote,
    [string]$DataDirectory,
    [string]$StateDirectory
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Gateway-Paths.ps1')
$java = (Get-Command java -CommandType Application).Source
$jar = Get-MyAIJarPath
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
    throw 'Place myai-server.jar next to this script or run mvn -B verify.'
}
$state = Get-MyAIStatePath -StateDirectory $StateDirectory -DataDirectory $DataDirectory
$previousCodex = $env:GATEWAY_CODEX
try {
    if (-not $previousCodex) {
        $codex = Get-Command codex.exe -CommandType Application -ErrorAction SilentlyContinue
        if ($codex) { $env:GATEWAY_CODEX = $codex.Source }
    }
    $serverArguments = @('--bind', $BindAddress, '--port', $Port, '--state-dir', $state)
    if ($AllowRemote) { $serverArguments += '--allow-remote' }
    & $java -jar $jar @serverArguments
    if ($LASTEXITCODE -ne 0) { throw "Gateway exited with code $LASTEXITCODE." }
} finally {
    $env:GATEWAY_CODEX = $previousCodex
}
