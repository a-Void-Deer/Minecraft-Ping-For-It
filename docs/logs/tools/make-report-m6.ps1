# make-report-m6.ps1 - 5号：M6 轮盘验收报告（三方比对 select/send/marker 的 pingType）
param(
  [string]$Latest = 'run\logs\latest.log',
  [string]$DebugLog = 'run\logs\debug.log',
  [string]$RunDir = 'run\dsh-logs\run-m6',
  [string]$CrashDir = 'run\crash-reports'
)
$ErrorActionPreference = 'Continue'
if (-not (Test-Path $RunDir)) { New-Item -ItemType Directory -Path $RunDir -Force | Out-Null }
$out = Join-Path $RunDir 'REPORT.md'
$b = New-Object System.Collections.Generic.List[string]
function A([string]$s) { $b.Add($s) }
function Sec([string]$t) { A ''; A ('## ' + $t); A '' }
function Fence([string]$s) { A '~~~'; A $s; A '~~~' }
if (-not (Test-Path $Latest)) { Write-Output ('MISSING ' + $Latest); exit 2 }
$lines = Get-Content -LiteralPath $Latest -Encoding utf8
$n = $lines.Count
A '# M6 轮盘验收 · 会话报告（5号 log-monitor）'
A ''
A ('- 分析文件: ' + $Latest + '（' + $n + ' 行）')
A ('- 生成时间: ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
A '- 只含原文与行号，不做归因。'

# ---- 0 首末时长 ----
Sec '0) 会话首末与总时长'
Fence ('L1: ' + $lines[0] + [Environment]::NewLine + 'L' + $n + ': ' + $lines[$n-1])
$tsRe = '^\[(\d{2}:\d{2}:\d{2})\]'
$t1=$null; $t2=$null
if ($lines[0] -match $tsRe) { $t1=[TimeSpan]::Parse($Matches[1]) }
if ($lines[$n-1] -match $tsRe) { $t2=[TimeSpan]::Parse($Matches[1]) }
if ($t1 -and $t2) { A ('- 跨度: **' + [int](($t2-$t1).TotalSeconds) + ' 秒**') }
$stop = Select-String -LiteralPath $Latest -Pattern 'Stopping!' -Encoding utf8 | Select-Object -Last 1
if ($stop) { A ('- 正常退出: **是**（L' + $stop.LineNumber + '）') } else { A '- 正常退出: **否/未采到**' }

# ---- 1 进世界 ----
Sec '1) 是否进世界'
$srv = @(Select-String -LiteralPath $Latest -Pattern 'Starting integrated minecraft server' -Encoding utf8)
$join = @(Select-String -LiteralPath $Latest -Pattern 'logged in with entity id' -Encoding utf8)
A ('- Starting integrated minecraft server: **' + $srv.Count + '** 条')
if ($srv.Count -gt 0) { Fence (($srv | ForEach-Object { 'L' + $_.LineNumber + ': ' + $_.Line.Trim() }) -join [Environment]::NewLine) }
A ('- logged in with entity id: **' + $join.Count + '** 条')
if ($join.Count -gt 0) { Fence (($join | ForEach-Object { 'L' + $_.LineNumber + ': ' + $_.Line.Trim() }) -join [Environment]::NewLine) }

# ---- 2 资源重载判据 ----
Sec '2) 资源重载完成判据（防回退）'
$atlas = @(Select-String -LiteralPath $Latest -Pattern 'Created: .*atlas' -Encoding utf8)
$sound = @(Select-String -LiteralPath $Latest -Pattern 'Sound engine started' -Encoding utf8)
$adv   = @(Select-String -LiteralPath $Latest -Pattern 'Loaded [0-9]+ advancements' -Encoding utf8)
A ('- atlas: **' + $atlas.Count + '**；Sound engine: **' + $sound.Count + '**；advancements: **' + $adv.Count + '**')
if ($sound.Count -gt 0) { A ('- Sound engine 首条 L' + $sound[0].LineNumber) }
if ($adv.Count -gt 0) { Fence (($adv | ForEach-Object { 'L' + $_.LineNumber + ': ' + $_.Line.Trim() }) -join [Environment]::NewLine) }

# ---- 3 轮盘事件清单 ----
Sec '3) 轮盘事件逐条（open / select / cancel / timeout / close）'
$wOpen   = @(Select-String -LiteralPath $Latest -Pattern '\[PingForIt\] wheel open' -Encoding utf8)
$wSelect = @(Select-String -LiteralPath $Latest -Pattern '\[PingForIt\] wheel select' -Encoding utf8)
$wCancel = @(Select-String -LiteralPath $Latest -Pattern '\[PingForIt\] wheel cancel' -Encoding utf8)
$wTime   = @(Select-String -LiteralPath $Latest -Pattern '\[PingForIt\] wheel timeout' -Encoding utf8)
$wClose  = @(Select-String -LiteralPath $Latest -Pattern '\[PingForIt\] wheel close' -Encoding utf8)
A ('- wheel open: **' + $wOpen.Count + '**')
if ($wOpen.Count -gt 0) { Fence (($wOpen | ForEach-Object { 'L' + $_.LineNumber + ': ' + $_.Line.Trim() }) -join [Environment]::NewLine) }
A ('- wheel select: **' + $wSelect.Count + '**')
if ($wSelect.Count -gt 0) { Fence (($wSelect | ForEach-Object { 'L' + $_.LineNumber + ': ' + $_.Line.Trim() }) -join [Environment]::NewLine) }
A ('- wheel cancel: **' + $wCancel.Count + '** / wheel timeout: **' + $wTime.Count + '** / wheel close: **' + $wClose.Count + '**')
if ($wCancel.Count + $wTime.Count + $wClose.Count -gt 0) { Fence ((@($wCancel) + @($wTime) + @($wClose) | Sort-Object LineNumber | ForEach-Object { 'L' + $_.LineNumber + ': ' + $_.Line.Trim() }) -join [Environment]::NewLine) }

# ---- 4 三方比对 ----
Sec '4) 核心：select pingType ↔ send ping ↔ marker created 三方比对'
$send = @(Select-String -LiteralPath $Latest -Pattern '\[PingForIt\] send ping:' -Encoding utf8)
$mark = @(Select-String -LiteralPath $Latest -Pattern '\[PingForIt\] marker created:' -Encoding utf8)
A ('- send ping: **' + $send.Count + '**，marker created: **' + $mark.Count + '**')

# 按行号顺序遍历，记录每行事件类型
$ev = @()
foreach ($x in $wSelect) { $ev += [pscustomobject]@{ Kind='select'; Line=$x.LineNumber; Text=$x.Line } }
foreach ($x in $send)    { $ev += [pscustomobject]@{ Kind='send';   Line=$x.LineNumber; Text=$x.Line } }
foreach ($x in $mark)    { $ev += [pscustomobject]@{ Kind='marker'; Line=$x.LineNumber; Text=$x.Line } }
$ev = $ev | Sort-Object Line
$lastSel = ''; $lastSend = ''
$rows = @(); $mismatch = 0; $shortPress = 0
foreach ($e in $ev) {
  if ($e.Kind -eq 'select') {
    if ($e.Text -match 'pingType=([A-Za-z_]+)') { $lastSel = $Matches[1] }
  } elseif ($e.Kind -eq 'send') {
    if ($e.Text -match 'pingType=([A-Za-z_]+)') { $lastSend = $Matches[1] }
  } else {
    $mk = ''
    if ($e.Text -match 'pingType=([A-Za-z_]+)') { $mk = $Matches[1] }
    $cmp = 'n/a'
    if ($lastSel -ne '') { if ($lastSel -eq $mk) { $cmp = 'OK' } else { $cmp = 'MISMATCH'; $mismatch = $mismatch + 1 } }
    $isShort = ($lastSel -eq '' -and $lastSend -ne '')
    if ($isShort) { $shortPress = $shortPress + 1 }
    $rows += [pscustomobject]@{ MarkerLine=$e.Line; Marker=$mk; Send=$lastSend; Select=$lastSel; Cmp=$cmp; Short=$isShort }
    $lastSel = ''
  }
}
if ($rows.Count -gt 0) {
  A ''; A '| marker 行 | select | send | marker | select==marker | 短按路径 |'
  A '| --- | --- | --- | --- | --- | --- |'
  foreach ($r in $rows) {
    $sp = if ($r.Short) { 'YES' } else { '' }
    $sl = if ($r.Select -eq '') { '(无)' } else { $r.Select }
    A ('| L' + $r.MarkerLine + ' | ' + $sl + ' | ' + $r.Send + ' | ' + $r.Marker + ' | **' + $r.Cmp + '** | ' + $sp + ' |')
  }
}

A ''
A ('- 不一致条数（select != marker）：**' + $mismatch + '**')
A ('- 短按路径条数（无 wheel select 直接 marker，send=attention）：**' + $shortPress + '**')
$pairOk = ($send.Count -eq $mark.Count)
A ('- send/marker 计数是否相等：**' + $pairOk + '**（' + $send.Count + ' : ' + $mark.Count + '）')

# ---- 5 HUD 相关 ----
Sec '5) HUD 相关告警'
$hudFail = @(Select-String -LiteralPath $Latest -Pattern 'marker HUD pass failed' -Encoding utf8)
$hudAny  = @(Select-String -LiteralPath $Latest -Pattern 'wheel HUD|WheelHud|wheel render' -Encoding utf8)
A ('- marker HUD pass failed: **' + $hudFail.Count + '**')
if ($hudFail.Count -gt 0) { Fence (($hudFail | ForEach-Object { 'L' + $_.LineNumber + ': ' + $_.Line.Trim() }) -join [Environment]::NewLine) }
A ('- wheel HUD 相关行: **' + $hudAny.Count + '**')
if ($hudAny.Count -gt 0) { Fence (($hudAny | Select-Object -First 10 | ForEach-Object { 'L' + $_.LineNumber + ': ' + $_.Line.Trim() }) -join [Environment]::NewLine) }

# ---- 6 ERROR/WARN ----
Sec '6) 全部 ERROR / WARN'
$lev = @(Select-String -LiteralPath $Latest -Pattern '/(ERROR|WARN|FATAL)\]' -Encoding utf8)
$noisePat = 'Perfmon|PerfOS|Realms|realms|status=401|user properties|end_of_frame|unexpected shutdown'
$noise = @($lev | Where-Object { $_.Line -match $noisePat })
$other = @($lev | Where-Object { $_.Line -notmatch $noisePat })
A ('- 总计 **' + $lev.Count + '**（已知噪音 ' + $noise.Count + '，其它 ' + $other.Count + '）')
A ''; A '### 其它（需关注）'
if ($other.Count -gt 0) { Fence (($other | ForEach-Object { 'L' + $_.LineNumber + ': ' + $_.Line.Trim() }) -join [Environment]::NewLine) } else { A '无' }
A ''; A '### 已知噪音'
if ($noise.Count -gt 0) { Fence (($noise | ForEach-Object { 'L' + $_.LineNumber + ': ' + $_.Line.Trim() }) -join [Environment]::NewLine) } else { A '无' }

# ---- 7 crash / mixin ----
Sec '7) 崩溃与 Mixin'
$cr = Get-ChildItem $CrashDir -Filter '*.txt' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($cr) { A ('- 最新 crash-report: ' + $cr.Name + '（' + $cr.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss') + '，' + $cr.Length + ' B）') } else { A '- 无 crash-report 目录内容' }
$mxf = @()
foreach ($p in @($Latest, $DebugLog)) { if (Test-Path $p) { $mxf += Select-String -LiteralPath $p -Pattern 'Mixin apply failed|InvalidInjectionException|MixinTransformerError' -Encoding utf8 } }
A ('- Mixin apply failed 等命中: **' + $mxf.Count + '**')
if ($mxf.Count -gt 0) { Fence (($mxf | ForEach-Object { 'L' + $_.LineNumber + ': ' + $_.Line.Trim() }) -join [Environment]::NewLine) }
if (Test-Path $DebugLog) {
  $mix = @(Select-String -LiteralPath $DebugLog -Pattern 'Mixing .* from pingforit\.mixins\.json' -Encoding utf8)
  A ('- debug.log 中 pingforit.mixins.json 织入行: **' + $mix.Count + '**')
  if ($mix.Count -gt 0) { Fence (($mix | Select-Object -First 6 | ForEach-Object { 'L' + $_.LineNumber + ': ' + $_.Line.Trim() }) -join [Environment]::NewLine) }
}

# ---- 8 快照 ----
Sec '8) 本目录快照'
$snaps = Get-ChildItem $RunDir -Filter 'snapshot-*.log' -ErrorAction SilentlyContinue | Sort-Object Name
if ($snaps) { A ((($snaps | ForEach-Object { $_.Name + '  ' + $_.Length + ' B' }) -join [Environment]::NewLine)) } else { A '无' }

A ''; A '---'; A '（5号 log-monitor 自动生成，无归因结论）'
$b | Set-Content -Path $out -Encoding utf8
Write-Output ('REPORT: ' + (Resolve-Path $out).Path)
Write-Output ('LINES: ' + $b.Count)
