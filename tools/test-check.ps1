# Offline test runner (B6), PowerShell wrapper.
# The canonical implementation is tools/test-check.sh (git-bash); this wrapper
# keeps one implementation to avoid shell drift. Requires bash on PATH.
$T = Split-Path -Parent $PSScriptRoot
Set-Location $T
$bash = Get-Command bash -ErrorAction SilentlyContinue
if (-not $bash) {
    Write-Error "test-check: bash not found; run tools/test-check.sh under git-bash"
    exit 2
}
& $bash.Source (Join-Path $T "tools/test-check.sh") @args
exit $LASTEXITCODE
