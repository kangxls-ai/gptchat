function Resolve-MyAICodex {
    if ($env:GATEWAY_CODEX) {
        if (Test-Path -LiteralPath $env:GATEWAY_CODEX -PathType Leaf) {
            return (Get-Item -LiteralPath $env:GATEWAY_CODEX).FullName
        }
        $configured = Get-Command $env:GATEWAY_CODEX -CommandType Application -ErrorAction SilentlyContinue
        if ($configured) { return $configured.Source }
        throw 'GATEWAY_CODEX points to a missing executable. Update it to the current codex.exe path.'
    }
    $command = Get-Command codex.exe -CommandType Application -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }

    if (Get-Command Get-AppxPackage -ErrorAction SilentlyContinue) {
        $packages = Get-AppxPackage -Name 'OpenAI.Codex' -ErrorAction SilentlyContinue |
            Sort-Object Version -Descending
        foreach ($package in $packages) {
            $candidate = Join-Path $package.InstallLocation 'app/resources/codex.exe'
            if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
        }
    }
    $bundled = Join-Path $env:LOCALAPPDATA 'OpenAI/Codex/bin'
    if (Test-Path -LiteralPath $bundled -PathType Container) {
        $candidate = Get-ChildItem -LiteralPath $bundled -Filter codex.exe -File -Recurse |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($candidate) { return $candidate.FullName }
    }
    throw 'Codex CLI was not found. Install Codex or set GATEWAY_CODEX to the absolute path of codex.exe.'
}
