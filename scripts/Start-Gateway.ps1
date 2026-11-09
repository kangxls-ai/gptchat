param(
    [string]$BindAddress,
    [ValidateRange(1024, 65535)][int]$Port = 4000,
    [switch]$AllowRemote,
    [string]$DataDirectory = (Join-Path (Split-Path -Parent $PSScriptRoot) 'server-data')
)
$ErrorActionPreference = 'Stop'
$java = (Get-Command java -CommandType Application).Source
$codex = (Get-Command codex -CommandType Application).Source
$jar = Join-Path (Split-Path -Parent $PSScriptRoot) 'server/target/myai-server.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Run mvn -B verify first.' }
if (-not $BindAddress) {
    Get-NetIPConfiguration |
        Where-Object { $_.IPv4DefaultGateway -and $_.IPv4Address } |
        Select-Object InterfaceAlias, @{Name = 'IPv4'; Expression = { $_.IPv4Address.IPAddress -join ', ' }} |
        Format-Table -AutoSize
    $BindAddress = Read-Host 'Enter the private IPv4 address of the Wi-Fi adapter'
}
$previousCodex = $env:GATEWAY_CODEX
$state = New-Item -ItemType Directory -Force $DataDirectory
Push-Location $state.FullName
try {
    $env:GATEWAY_CODEX = $codex
    $serverArguments = @($BindAddress, $Port)
    if ($AllowRemote) { $serverArguments += '--allow-remote' }
    & $java -jar $jar @serverArguments
    if ($LASTEXITCODE -ne 0) { throw "Gateway exited with code $LASTEXITCODE." }
} finally {
    $env:GATEWAY_CODEX = $previousCodex
    Pop-Location
}
