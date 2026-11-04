# Offline locale consistency check (B7), PowerShell wrapper.
# Mirrors tools/locale-check.sh: JDK 25 single-file source launcher, read-only.
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Rest)
$T = Split-Path -Parent $PSScriptRoot
Set-Location $T

function Test-Jdk25([string]$Path) {
    if (-not (Test-Path $Path)) { return $false }
    try { $v = (& $Path -version 2>&1 | Out-String); return ($v -match 'version "25') } catch { return $false }
}
$JAVA = $null
if ($env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME "bin/java.exe"
    if (Test-Jdk25 $candidate) { $JAVA = $candidate }
}
if (-not $JAVA -and (Test-Jdk25 "C:/Program Files/Java/jdk-25/bin/java.exe")) {
    $JAVA = "C:/Program Files/Java/jdk-25/bin/java.exe"
}
if (-not $JAVA) {
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if ($cmd -and (Test-Jdk25 $cmd.Source)) { $JAVA = $cmd.Source }
}
if (-not $JAVA) {
    Write-Error "locale-check: JDK 25 java not found (set JAVA_HOME)"
    exit 2
}

Write-Output ("locale-check: java=" + $JAVA)
Write-Output ("locale-check: root=" + $T)
Write-Output ("locale-check: cmd=java tools/LocaleCheck.java " + ($Rest -join " "))
Write-Output "------------------------------------------------------------"
& $JAVA tools/LocaleCheck.java @Rest
$code = $LASTEXITCODE
Write-Output "------------------------------------------------------------"
Write-Output ("[summary] locale-check exit=" + $code)
exit $code
