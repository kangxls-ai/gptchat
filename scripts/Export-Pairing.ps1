param([string]$StateDirectory, [string]$DataDirectory)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Gateway-Paths.ps1')

$state = Get-MyAIStatePath -StateDirectory $StateDirectory -DataDirectory $DataDirectory
$target = Join-Path $PSScriptRoot 'pairing.txt'
if ([IO.Path]::GetFullPath($state).TrimEnd('\', '/') -eq [IO.Path]::GetFullPath($PSScriptRoot).TrimEnd('\', '/')) {
    throw 'Choose a state directory different from the script directory.'
}
$existing = Get-Item -Force -LiteralPath $target -ErrorAction SilentlyContinue
if ($existing -and ($existing.PSIsContainer -or ($existing.Attributes -band [IO.FileAttributes]::ReparsePoint))) {
    throw 'Refusing to replace a pairing.txt link or non-file.'
}

$password = & (Join-Path $PSScriptRoot 'Show-Pairing.ps1') -StateDirectory $state
$temporary = Join-Path $PSScriptRoot ('.myai-pairing-' + [Guid]::NewGuid().ToString('N') + '.tmp')
$security = New-Object System.Security.AccessControl.FileSecurity
$security.SetAccessRuleProtection($true, $false)
$user = [Security.Principal.WindowsIdentity]::GetCurrent().User
$security.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($user, 'FullControl', 'Allow'))
try {
    New-Item -ItemType File -Path $temporary | Out-Null
    Set-Acl -LiteralPath $temporary -AclObject $security
    [IO.File]::WriteAllText($temporary, "$password`n", [Text.UTF8Encoding]::new($false))
    if ($existing) {
        Set-Acl -LiteralPath $target -AclObject $security
        [IO.File]::Replace($temporary, $target, [NullString]::Value)
    } else {
        [IO.File]::Move($temporary, $target)
    }
} finally {
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
}
Write-Output "Pairing password written to $target"
