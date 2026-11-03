param(
  [string]$Latest = 'run\logs\latest.log',
  [string]$OutRoot = 'run\dsh-logs',
  [string]$CrashDir = 'run\crash-reports',
  [int]$PollSec = 10,
  [int]$SnapshotSec = 60,
  [int]$MaxSessionMin = 30
)
$ErrorActionPreference = 'Continue'
function GSz([string]$p) { if (Test-Path -LiteralPath $p) { return [long](Get-Item -LiteralPath $p).Length } return [long]-1 }
function GCreated([string]$p) { if (Test-Path -LiteralPath $p) { return (Get-Item -LiteralPath $p).CreationTime } return [datetime]::MinValue }
function Say([string]$dir, [string]$m) { $line = (Get-Date -Format 'HH:mm:ss') + '  ' + $m; if ($dir -ne '') { Add-Content -LiteralPath (Join-Path $dir 'monitor-trace.txt') -Value $line -Encoding utf8 }; Write-Output $line }

$created = GCreated $Latest
$runDir = Join-Path $OutRoot ('run-' + $created.ToString('HHmmss') + '-attach')
New-Item -ItemType Directory -Path $runDir -Force | Out-Null
Say $runDir ('ATTACH baseline created=' + $created.ToString('HH:mm:ss') + ' size=' + (GSz $Latest))
Copy-Item -LiteralPath $Latest -Destination (Join-Path $runDir 'snapshot-00-attach.log') -Force

$idx = 0; $lastSnap = Get-Date; $lastSz = -1; $stable = 0
$sessStart = Get-Date
while ($true) {
  Start-Sleep -Seconds $PollSec
  $cur = GCreated $Latest
  if ($cur -ne $created) {
    $idx = $idx + 1
    $nm = 'snapshot-{0:d2}-relaunch.log' -f $idx
    Copy-Item -LiteralPath $Latest -Destination (Join-Path $runDir $nm) -Force
    Say $runDir ('RELAUNCH detected newCreated=' + $cur.ToString('HH:mm:ss') + ' -> ' + $nm)
    $created = $cur; $sessStart = Get-Date; $lastSz = -1; $stable = 0
    continue
  }
  $sz = GSz $Latest
  if ($sz -lt 0) { continue }
  if ($sz -eq $lastSz) { $stable = $stable + $PollSec } else { $stable = 0; $lastSz = $sz }
  if (((Get-Date) - $lastSnap).TotalSeconds -ge $SnapshotSec) {
    $idx = $idx + 1
    $nm = 'snapshot-{0:d2}.log' -f $idx
    Copy-Item -LiteralPath $Latest -Destination (Join-Path $runDir $nm) -Force
    Say $runDir ('snapshot ' + $nm + ' size=' + $sz)
    $lastSnap = Get-Date
  }
  $tail = Get-Content -LiteralPath $Latest -Tail 15 -Encoding utf8 -ErrorAction SilentlyContinue
  $ts = ''
  if ($null -ne $tail) { $ts = $tail -join ' ' }
  if ($ts -match 'Stopping!' -and $stable -ge 20) { Say $runDir ('END-A Stopping! + 20s stable size=' + $sz); break }
  $cr = Get-ChildItem -LiteralPath $CrashDir -Filter '*.txt' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if ($null -ne $cr) { if ($cr.LastWriteTime -gt $sessStart -and $stable -ge 30) { Say $runDir ('END-B crash-report ' + $cr.Name + ' + 30s stable'); break } }
  if (((Get-Date) - $sessStart).TotalMinutes -gt $MaxSessionMin) { Say $runDir 'END-C session 30min timeout'; break }
}
Copy-Item -LiteralPath $Latest -Destination (Join-Path $runDir 'snapshot-99-final.log') -Force
Say $runDir ('final size=' + (GSz $Latest))
Write-Output ('OUTDIR: ' + $runDir)
Write-Output 'DONE'
