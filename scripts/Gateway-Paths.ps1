function Get-MyAIJarPath {
    $adjacent = Join-Path $PSScriptRoot 'myai-server.jar'
    if (Test-Path -LiteralPath $adjacent -PathType Leaf) { return $adjacent }
    return Join-Path (Split-Path -Parent $PSScriptRoot) 'server/target/myai-server.jar'
}

function Get-MyAIStatePath {
    param([string]$StateDirectory, [string]$DataDirectory)
    if ($StateDirectory -and $DataDirectory) { throw 'Specify -StateDirectory or -DataDirectory.' }
    if ($StateDirectory) { return $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($StateDirectory) }
    if ($DataDirectory) {
        return $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath((Join-Path $DataDirectory 'gateway-state'))
    }
    if ($env:MYAI_STATE_DIR) { return $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($env:MYAI_STATE_DIR) }
    if ($env:MYAI_DATA_DIR) {
        return $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath((Join-Path $env:MYAI_DATA_DIR 'gateway-state'))
    }
    if (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'myai-server.jar') -PathType Leaf) {
        return Join-Path $PSScriptRoot 'gateway-state'
    }
    return Join-Path (Split-Path -Parent $PSScriptRoot) 'server-data/gateway-state'
}
