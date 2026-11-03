param(
  [string]$Latest = 'run\logs\latest.log',
  [string]$OutRoot = 'run\dsh-logs',
  [string]$CrashDir = 'run\crash-reports',
  [int]$MaxWaitMin = 30,
  [int]$PollSec = 10,
  [int]$SnapshotSec = 60
)
$ErrorActionPreference = 'Continue'

function Get-Size([string]$p) {
  if (Test-Path -LiteralPath $p) { return [long](Get-Item -LiteralPath $p).Length }
  return [long]-1
}
function Get-Created([string]$p) {
  if (Test-Path -LiteralPath $p) { return (Get-Item -LiteralPath $p).CreationTime }
  return [datetime]::MinValue
}
function Has-Loading([string]$p) {
  $h = Get-Content -LiteralPath $p -TotalCount 3 -Encoding utf8 -ErrorAction SilentlyContinue
  if ($null -eq $h) { return $false }
  $s = $h -join ' '
  if ($s -match 'Loading Minecraft') { return $true }
  return $false
}
function Say([string]$dir, [string]$msg) {
  $line = (Get-Date -Format 'HH:mm:ss') + '  ' + $msg
  if ($dir -ne '') { Add-Content -LiteralPath (Join-Path $dir 'monitor-trace.txt') -Value $line -Encoding utf8 }
  Write-Output $line
}

$baseSize = Get-Size $Latest
$baseCreated = Get-Created $Latest
Say '' ('BASELINE size=' + $baseSize + ' created=' + $baseCreated.ToString('HH:mm:ss'))

# ---------- 等待新会话 ----------
$waitStart = Get-Date
$deadline = $waitStart.AddMinutes($MaxWaitMin)
$runDir = ''
$sessStart = $null
while ((Get-Date) -lt $deadline) {
  Start-Sleep -Seconds $PollSec
  $sz = Get-Size $Latest
  if ($sz -lt 0) { continue }
  $created = Get-Created $Latest
  $newFile = $created -gt $baseCreated.AddSeconds(1)
  if (-not $newFile) { continue }
  if (-not (Has-Loading $Latest)) { continue }
  $sessStart = $created
  $runDir = Join-Path $OutRoot ('run-' + $created.ToString('HHmmss'))
  New-Item -ItemType Directory -Path $runDir -Force | Out-Null
  Say $runDir ('NEW SESSION detected created=' + $created.ToString('HH:mm:ss') + ' size=' + $sz)
  Copy-Item -LiteralPath $Latest -Destination (Join-Path $runDir 'snapshot-00-start.log') -Force
  break
}
if ($runDir -eq '') { Say '' ('TIMEOUT: ' + $MaxWaitMin + ' minutes no new session'); exit 3 }

# ---------- 会话监测 ----------
$snapIdx = 0
$lastSnap = Get-Date
$lastSize = -1
$stableSec = 0
$crashSeen = $false
while ($true) {
  Start-Sleep -Seconds $PollSec
  $sz = Get-Size $Latest
  if ($sz -lt 0) { continue }
  if ($sz -eq $lastSize) { $stableSec = $stableSec + $PollSec } else { $stableSec = 0; $lastSize = $sz }

  if (((Get-Date) - $lastSnap).TotalSeconds -ge $SnapshotSec) {
    $snapIdx = $snapIdx + 1
    $name = 'snapshot-{0:d2}.log' -f $snapIdx
    Copy-Item -LiteralPath $Latest -Destination (Join-Path $runDir $name) -Force
    Say $runDir ('snapshot ' + $name + ' size=' + $sz)
    $lastSnap = Get-Date
  }

  $tail = Get-Content -LiteralPath $Latest -Tail 15 -Encoding utf8 -ErrorAction SilentlyContinue
  $tailStr = ''
  if ($null -ne $tail) { $tailStr = $tail -join ' ' }

  if ($tailStr -match 'Stopping!' -and $stableSec -ge 20) {
    Say $runDir ('END-A: Stopping! + 20s stable, size=' + $sz)
    break
  }

  $cr = Get-ChildItem -LiteralPath $CrashDir -Filter '*.txt' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if ($null -ne $cr) {
    if ($cr.LastWriteTime -gt $sessStart) {
      if (-not $crashSeen) { $crashSeen = $true; Say $runDir ('CRASH REPORT found: ' + $cr.Name + ' at ' + $cr.LastWriteTime.ToString('HH:mm:ss')) }
      if ($stableSec -ge 30) { Say $runDir ('END-B: crash-report + 30s stable'); break }
    }
  }

  if (((Get-Date) - $sessStart).TotalMinutes -gt 30) { Say $runDir 'END-C: 30min timeout'; break }
}

Copy-Item -LiteralPath $Latest -Destination (Join-Path $runDir 'snapshot-99-final.log') -Force
Say $runDir ('snapshot-99-final.log size=' + (Get-Size $Latest))
Say $runDir ('DURATION from created: ' + [int]((Get-Date) - $sessStart).TotalSeconds + 's')
Write-Output ('OUTDIR: ' + $runDir)
Write-Output 'DONE'
