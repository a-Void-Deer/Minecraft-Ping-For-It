param(
  [string]$LogPath = 'D:\Gradle\daemon\9.8.2\daemon-33824.out.log',
  [int]$Seconds = 150
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path $LogPath)) { Write-Output "MISSING $LogPath"; exit 1 }
$fs = [System.IO.File]::Open($LogPath, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
$start = $fs.Length
[void]$fs.Seek($start, [System.IO.SeekOrigin]::Begin)
$reader = New-Object System.IO.StreamReader($fs)
$deadline = (Get-Date).AddSeconds($Seconds)
$pat = 'BUILD SUCCESSFUL|BUILD FAILED|FAILURE:|What went wrong|Mixin apply failed|Compilation failed|Caused by|OutOfMemoryError|cannot find symbol|invalid source release|Unsupported class file'
$done = $false
$hit = 0
while (-not $done -and (Get-Date) -lt $deadline) {
  $chunk = $reader.ReadToEnd()
  if ($chunk) {
    foreach ($line in ($chunk -split "\r?\n")) {
      if ($line -match $pat) {
        Write-Output $line
        $hit++
        if ($line -match 'BUILD SUCCESSFUL|BUILD FAILED|What went wrong') { $done = $true }
      }
    }
  }
  if ($done) { break }
  Start-Sleep -Seconds 2
}
Write-Output "=== WATCHER END done=$done hits=$hit at $(Get-Date -Format 'HH:mm:ss') startOffset=$start finalLength=$($fs.Length) ==="
$reader.Dispose(); $fs.Dispose()
