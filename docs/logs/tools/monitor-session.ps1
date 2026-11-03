<#
  monitor-session.ps1 - 5号 log-monitor：实时监测一次 runClient 会话（含进世界）
  设计：单进程循环 + Copy-Item 快照，不用 tail -F / tee / grep 管道链（本机 msys 会崩）
  1) 每 10s 轮询，检测 latest.log 被重建且含 'Loading Minecraft' → 新会话开始
  2) 每 60s 快照到 run/dsh-logs/run-<HHmmss>/；另在「进世界」「退出」时强制快照
  3) 出现 'Stopping!' 且文件大小连续 2 次不变 → 会话结束，写 final 快照 + trace
  用法：pwsh -File docs/logs/tools/monitor-session.ps1 -MaxWaitMin 20
#>
param(
  [string]$Latest   = 'run\logs\latest.log',
  [string]$OutRoot  = 'run\dsh-logs',
  [int]$MaxWaitMin  = 20,
  [int]$PollSec     = 10,
  [int]$SnapshotSec = 60
)
$ErrorActionPreference = 'Continue'
function W([string]$s) { Write-Output $s }
function Trace([string]$dir, [string]$msg) {
  $line = (Get-Date -Format 'HH:mm:ss') + '  ' + $msg
  Add-Content -Path (Join-Path $dir 'monitor-trace.txt') -Value $line -Encoding utf8
  W $line
}

# ---------- 基线 ----------
$baseLen = -1; $baseCreate = $null
if (Test-Path $Latest) {
  $fi = Get-Item $Latest
  $baseLen = $fi.Length; $baseCreate = $fi.CreationTime
  W ('BASELINE: ' + $Latest + ' len=' + $baseLen + ' created=' + $baseCreate.ToString('HH:mm:ss'))
} else { W ('BASELINE: ' + $Latest + ' 不存在') }

# ---------- 阶段1：等待新会话 ----------
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$deadline = (Get-Date).AddMinutes($MaxWaitMin)
$started = $false
W ('WAITING: 最多 ' + $MaxWaitMin + ' 分钟，每 ' + $PollSec + 's 轮询…')
while ((Get-Date) -lt $deadline) {
  Start-Sleep -Seconds $PollSec
  if (-not (Test-Path $Latest)) { continue }
  $fi = Get-Item $Latest
  $head = Get-Content -LiteralPath $Latest -TotalCount 5 -Encoding utf8 -ErrorAction SilentlyContinue
  $hasLoading = (($head -join ' ') -match 'Loading Minecraft')
  if (-not $hasLoading) { continue }
  $isNew = $false
  if ($baseLen -lt 0) { $isNew = $true }
  elseif ($fi.CreationTime -gt $baseCreate.AddSeconds(1)) { $isNew = $true }
  elseif ($fi.Length -lt $baseLen) { $isNew = $true }
  if ($isNew) { $started = $true; break }
}
if (-not $started) {
  W ('TIMEOUT: ' + $MaxWaitMin + ' 分钟内未检测到新会话')
  exit 3
}

# ---------- 建立本次输出目录 ----------
$t0 = Get-Date
$runDir = Join-Path $OutRoot ('run-' + $t0.ToString('HHmmss'))
New-Item -ItemType Directory -Path $runDir -Force | Out-Null
Trace $runDir ('SESSION START detected, out=' + $runDir)
Copy-Item -LiteralPath $Latest -Destination (Join-Path $runDir 'snapshot-00-start.log') -Force
Trace $runDir ('snapshot-00-start.log  size=' + (Get-Item $Latest).Length)

# ---------- 阶段2：会话中快照 ----------
$idx = 0; $lastSnap = Get-Date; $lastLen = -1; $stable = 0
$worldSeen = $false; $sessionStart = Get-Date
while ($true) {
  Start-Sleep -Seconds $PollSec
  if (-not (Test-Path $Latest)) { continue }
  $fi = Get-Item $Latest
  $len = $fi.Length
  if ($len -eq $lastLen) { $stable++ } else { $stable = 0 }
  $lastLen = $len

  # 进世界检测：run/saves 下出现 level 目录
  if (-not $worldSeen) {
    $lv = Get-ChildItem 'run\saves' -Directory -ErrorAction SilentlyContinue | Where-Object { Test-Path (Join-Path $_.FullName 'level.dat') }
    if ($lv) {
      $worldSeen = $true
      $idx++
      Copy-Item -LiteralPath $Latest -Destination (Join-Path $runDir ('snapshot-{0:d2}-world.log' -f $idx)) -Force
      Trace $runDir ('WORLD ENTERED -> snapshot-' + $idx + '-world.log  size=' + $len + '  saves=' + ($lv.Name -join ','))
      $lastSnap = Get-Date
    }
  }

  # 周期快照
  if (((Get-Date) - $lastSnap).TotalSeconds -ge $SnapshotSec) {
    $idx++
    $tag = if ($worldSeen) { 'world' } else { 'run' }
    Copy-Item -LiteralPath $Latest -Destination (Join-Path $runDir ('snapshot-{0:d2}-{1}.log' -f $idx, $tag)) -Force
    Trace $runDir ('periodic snapshot-' + $idx + '-' + $tag + '.log size=' + $len)
    $lastSnap = Get-Date
  }

  # 结束检测
  if ($stable -ge 2) {
    $tail = Get-Content -LiteralPath $Latest -Tail 30 -Encoding utf8 -ErrorAction SilentlyContinue
    if ((($tail -join ' ') -match 'Stopping!')) {
      Trace $runDir ('SESSION END detected (Stopping! + size stable), size=' + $len)
      break
    }
  }
  if (((Get-Date) - $sessionStart).TotalMinutes -gt 90) { Trace $runDir 'SAFETY BREAK after 90 min'; break }
}

$t1 = Get-Date
Copy-Item -LiteralPath $Latest -Destination (Join-Path $runDir 'snapshot-99-final.log') -Force
Trace $runDir ('snapshot-99-final.log size=' + (Get-Item $Latest).Length)
Trace $runDir ('DURATION: ' + [int]($t1 - $t0).TotalSeconds + 's')
Trace $runDir ('WAIT TIME: ' + [int]$sw.Elapsed.TotalSeconds + 's')
W ('OUTDIR: ' + (Resolve-Path $runDir).Path)
W 'DONE'
