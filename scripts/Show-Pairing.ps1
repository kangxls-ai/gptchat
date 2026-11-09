param(
    [switch]$Copy,
    [string]$DataDirectory = (Join-Path (Split-Path -Parent $PSScriptRoot) 'server-data')
)
$ErrorActionPreference = 'Stop'
$state = Join-Path $DataDirectory 'gateway-state'
$fingerprint = (Get-Content -Raw -LiteralPath (Join-Path $state 'fingerprint.txt')).Trim()
$secret = (Get-Content -Raw -LiteralPath (Join-Path $state 'pairing.txt')).Trim()
if ($fingerprint -notmatch '^[0-9a-f]{64}$' -or $secret -notmatch '^[A-Za-z0-9_-]{43}$') {
    throw 'Pairing files are incomplete or invalid.'
}
$password = "$fingerprint.$secret"
if ($Copy) {
    Set-Clipboard -Value $password
    Write-Host 'Pairing password copied. Transfer it privately to your phone and then clear the clipboard.'
} else {
    Write-Output $password
}
