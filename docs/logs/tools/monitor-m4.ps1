# monitor-m4.ps1 - 5号：M4 冒烟测试会话监测（可中途挂载）
# 用法: pwsh -File docs/logs/tools/monitor-m4.ps1 -Cutoff '19:44:00' -MaxWaitMin 30
param(
  [string]$Latest = 'run\logs\latest.log',
  [string]$OutRoot = 'run\dsh-logs',
  [string]$Cutoff = '19:44:00',
  [int]$MaxWaitMin = 30,
  [int]$PollSec = 10,
  [int]$SnapshotSec = 60,
  [int]$IdleEndSec = 60
)
$ErrorActionPreference = 'Continue'
$today = (Get-Date).Date
$cut = $today.Add([TimeSpan]::Parse($Cutoff))
function Trace([string]$dir, [string]$msg) {
  $line = (Get-Date -Format 'HH:mm:ss') + '  ' + $msg
  if ($dir) { Add-Content -Path (Join-Path $dir 'monitor-trace.txt') -Value $line -Encoding utf8 }
  Write-Output $line
}
function HasLoading([string]$p) {
  $h = Get-Content -LiteralPath $p -TotalCount 3 -Encoding utf8 -ErrorAction SilentlyContinue
  return (($h -join ' ') -match 'Loading Minecraft 26')
}

# ---- 挂载或等待 ----
$runDir = $null; $idx = 0
if ((Test-Path $Latest) -and (HasLoading $Latest)) {
  $cr = (Get-Item $Latest).CreationTime
  if ($cr -ge $cut) {
    $runDir = Join-Path $OutRoot ('run-' + $cr.ToString('HHmmss'))
    New-Item -ItemType Directory -Path $runDir -Force | Out-Null
    Trace $runDir ('ATTACH: 会话已在进行中，latest.log 创建于 ' + $cr.ToString('HH:mm:ss') + '（晚于 cutoff ' + $Cutoff + '）')
    Copy-Item -LiteralPath $Latest -Destination (Join-Path $runDir 'snapshot-00-attach.log') -Force
    Trace $runDir ('snapshot-00-attach.log size=' + (Get-Item $Latest).Length)
  }
}
if (-not $runDir) {
  Write-Output ('WAITING: 等待新会话（最多 ' + $MaxWaitMin + ' 分钟）…')
  $baseCreate = $null
  if (Test-Path $Latest) { $baseCreate = (Get-Item $Latest).CreationTime }
  $deadline = (Get-Date).AddMinutes($MaxWaitMin)
  while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds $PollSec
    if (-not (Test-Path $Latest)) { continue }
    $fi = Get-Item $Latest
    if (-not (HasLoading $Latest)) { continue }
    if ($fi.CreationTime -ge $cut -and ($null -eq $baseCreate -or $fi.CreationTime -gt $baseCreate.AddSeconds(1))) {
      $runDir = Join-Path $OutRoot ('run-' + $fi.CreationTime.ToString('HHmmss'))
      New-Item -ItemType Directory -Path $runDir -Force | Out-Null
      Trace $runDir ('SESSION START detected at ' + $fi.CreationTime.ToString('HH:mm:ss'))
      Copy-Item -LiteralPath $Latest -Destination (Join-Path $runDir 'snapshot-00-start.log') -Force
      break
    }
  }
  if (-not $runDir) { Write-Output ('TIMEOUT: ' + $MaxWaitMin + ' 分钟内未检测到新会话'); exit 3 }
}

# ---- 会话监测 ----
$lastLen = -1; $stableSeconds = 0; $lastSnap = Get-Date
$flagPing = $false; $flagErr = $false; $flagWorld = $false
$startWatch = Get-Date
while ($true) {
  Start-Sleep -Seconds $PollSec
  if (-not (Test-Path $Latest)) { continue }
  $fi = Get-Item $Latest
  $len = $fi.Length
  if ($len -eq $lastLen) { $stableSeconds += $PollSec } else { $stableSeconds = 0 }
  $lastLen = $len

  # 强制快照：首次 [PingForIt]
  if (-not $flagPing) {
    $h = Select-String -LiteralPath $Latest -Pattern '\[PingForIt\]' -Encoding utf8 -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($h) {
      $flagPing = $true; $idx++
      Copy-Item -LiteralPath $Latest -Destination (Join-Path $runDir ('snapshot-{0:d2}-first-pingforit.log' -f $idx)) -Force
      Trace $runDir ('FIRST [PingForIt] L' + $h.LineNumber + ' at ' + (Get-Date -Format 'HH:mm:ss') + ' -> snapshot-' + $idx)
      $lastSnap = Get-Date
    }
  }

  # 强制快照：首次 ERROR/FATAL
  if (-not $flagErr) {
    $e = Select-String -LiteralPath $Latest -Pattern '/(ERROR|FATAL)\]' -Encoding utf8 -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($e) {
      $flagErr = $true; $idx++
      Copy-Item -LiteralPath $Latest -Destination (Join-Path $runDir ('snapshot-{0:d2}-first-error.log' -f $idx)) -Force
      Trace $runDir ('FIRST ERROR L' + $e.LineNumber + ' -> snapshot-' + $idx)
      $lastSnap = Get-Date
    }
  }

  # 强制快照：进世界
  if (-not $flagWorld) {
    $lv = Get-ChildItem 'run\saves' -Directory -ErrorAction SilentlyContinue | Where-Object { Test-Path (Join-Path $_.FullName 'level.dat') }
    if ($lv) {
      $flagWorld = $true; $idx++
      Copy-Item -LiteralPath $Latest -Destination (Join-Path $runDir ('snapshot-{0:d2}-world.log' -f $idx)) -Force
      Trace $runDir ('WORLD PRESENT -> snapshot-' + $idx + ' saves=' + ($lv.Name -join ','))
      $lastSnap = Get-Date
    }
  }

  # 周期快照
  if (((Get-Date) - $lastSnap).TotalSeconds -ge $SnapshotSec) {
    $idx++
    Copy-Item -LiteralPath $Latest -Destination (Join-Path $runDir ('snapshot-{0:d2}-tick.log' -f $idx)) -Force
    Trace $runDir ('periodic snapshot-' + $idx + ' size=' + $len)
    $lastSnap = Get-Date
  }

  # 停止条件
  if ($stableSeconds -ge 20) {
    $tail = Get-Content -LiteralPath $Latest -Tail 20 -Encoding utf8 -ErrorAction SilentlyContinue
    if ((($tail -join ' ') -match 'Stopping!')) { Trace $runDir ('END: Stopping! + 20s stable, size=' + $len); break }
  }
  if ($stableSeconds -ge $IdleEndSec) {
    $java = @(Get-Process java -ErrorAction SilentlyContinue).Count
    if ($java -eq 0) { Trace $runDir ('END: 无 Stopping 但 java 进程已退出，日志 ' + $stableSeconds + 's 未增长'); break }
  }
  if (((Get-Date) - $startWatch).TotalMinutes -gt 75) { Trace $runDir 'SAFETY BREAK 75min'; break }
}

Copy-Item -LiteralPath $Latest -Destination (Join-Path $runDir 'snapshot-99-final.log') -Force
Trace $runDir ('snapshot-99-final.log size=' + (Get-Item $Latest).Length)
Trace $runDir ('MONITOR DURATION: ' + [int]((Get-Date) - $startWatch).TotalSeconds + 's')
Write-Output ('OUTDIR: ' + (Resolve-Path $runDir).Path)
Write-Output 'DONE'
