# make-report-m4.ps1 - 5号：M4 冒烟测试会话报告（逐行带行号，不做归因）
param(
  [string]$Latest = 'run\logs\latest.log',
  [string]$DebugLog = 'run\logs\debug.log',
  [string]$RunDir = 'run\dsh-logs\run-latest',
  [string]$CrashDir = 'run\crash-reports'
)
$ErrorActionPreference = 'Continue'
if (-not (Test-Path $RunDir)) { New-Item -ItemType Directory -Path $RunDir -Force | Out-Null }
$out = Join-Path $RunDir 'REPORT.md'
$b = New-Object System.Collections.Generic.List[string]
function A([string]$s) { $b.Add($s) }
function Sec([string]$t) { A ''; A ('## ' + $t); A '' }
if (-not (Test-Path $Latest)) { Write-Output ('MISSING ' + $Latest); exit 2 }
$lines = Get-Content -LiteralPath $Latest -Encoding utf8
$n = $lines.Count
A '# M4 冒烟测试 · 会话报告（5号 log-monitor）'
A ''
A ('- 分析文件: `' + $Latest + '`（' + $n + ' 行）')
A ('- 生成时间: ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
A '- 只含原文与行号，不做归因。'

Sec '0) 会话首末与总时长'
A '```'; A ('L1: ' + $lines[0]); A ('L' + $n + ': ' + $lines[$n-1]); A '```'
$tsRe = '^\[(\d{2}:\d{2}:\d{2})\]'
$t1=$null;$t2=$null
if ($lines[0] -match $tsRe) { $t1=[TimeSpan]::Parse($Matches[1]) }
if ($lines[$n-1] -match $tsRe) { $t2=[TimeSpan]::Parse($Matches[1]) }
if ($t1 -and $t2) { A ('- 首行 ' + $t1 + ' → 末行 ' + $t2 + '，跨度 **' + [int](($t2-$t1).TotalSeconds) + ' 秒**') }
$stop = Select-String -LiteralPath $Latest -Pattern 'Stopping!' -Encoding utf8 | Select-Object -Last 1
if ($stop) { A ('- 正常退出：**是**（L' + $stop.LineNumber + ': ' + $stop.Line.Trim() + '）') } else { A '- 正常退出：**否 / 未采到 Stopping!**' }

Sec '1) mod 加载与解析'
foreach ($pat in @('Loading Minecraft', 'Loading \d+ mods', 'pingforit', 'duplicate mod', 'ModResolutionException')) {
  $h = Select-String -LiteralPath $Latest -Pattern $pat -Encoding utf8 | Select-Object -First 5
  A ('### ' + $pat + '（' + @($h).Count + '）')
  if ($h) { A '```'; foreach ($x in $h) { A ('L' + $x.LineNumber + ': ' + $x.Line.Trim()) }; A '```' } else { A '无' }
}

Sec '1b) 启动阶段与是否到主界面'
foreach ($pat in @('Backend library|LWJGL', 'Created window|SDL video driver', 'Sound engine started', 'Setting user', '\[PingForIt\] Init')) {
  $h = Select-String -LiteralPath $Latest -Pattern $pat -Encoding utf8 | Select-Object -First 2
  A ('- ' + $pat + '  → 命中 ' + @($h).Count)
  if ($h) { A '```'; foreach ($x in $h) { A ('L' + $x.LineNumber + ': ' + $x.Line.Trim()) }; A '```' }
}

Sec '1b) 启动阶段与是否到主界面'
foreach ($pat in @('Backend library|LWJGL', 'Created window|SDL video driver', 'Sound engine started', 'Setting user', '\[PingForIt\] Init')) {
  $h = Select-String -LiteralPath $Latest -Pattern $pat -Encoding utf8 | Select-Object -First 2
  A ('- ' + $pat + '  → 命中 ' + @($h).Count)
  if ($h) { A '```'; foreach ($x in $h) { A ('L' + $x.LineNumber + ': ' + $x.Line.Trim()) }; A '```' }
}

Sec '1c) 资源重载完成判据（本轮核心）'
A '> 前两次会话这三项全为 0（卡住）；本次若出现即代表资源重载完成。'
$atlas = @(Select-String -LiteralPath $Latest -Pattern 'Created: .*atlas' -Encoding utf8)
$sound = @(Select-String -LiteralPath $Latest -Pattern 'Sound engine started' -Encoding utf8)
$adv   = @(Select-String -LiteralPath $Latest -Pattern 'Loaded [0-9]+ advancements' -Encoding utf8)
A ('- `Sound engine started`：**' + $sound.Count + '** 条')
if ($sound.Count -gt 0) { A '```'; foreach ($x in $sound) { A ('L' + $x.LineNumber + ': ' + $x.Line.Trim()) }; A '```' }
A ('- `Created: ...-atlas`：**' + $atlas.Count + '** 条' + $(if ($atlas.Count -gt 0) { '（首条 L' + $atlas[0].LineNumber + '，末条 L' + $atlas[$atlas.Count-1].LineNumber + '）' } else { '' }))
if ($atlas.Count -gt 0) { A '```'; foreach ($x in $atlas | Select-Object -First 3) { A ('L' + $x.LineNumber + ': ' + $x.Line.Trim()) }; A ('... 共 ' + $atlas.Count + ' 条'); A '```' }
A ('- `Loaded N advancements`：**' + $adv.Count + '** 条（0 不一定代表异常：26.3 可能不在该阶段打印）')
if ($adv.Count -gt 0) { A '```'; foreach ($x in $adv) { A ('L' + $x.LineNumber + ': ' + $x.Line.Trim()) }; A '```' }
$verdict = if ($sound.Count -gt 0 -and $atlas.Count -gt 0) { '**资源重载完成**' } else { '**资源重载未完成（按判据）**' }
A ('- 判定：' + $verdict)

Sec '2) Mixin 织入与失败'
A '### latest.log 中 Mixin 相关'
$mx = Select-String -LiteralPath $Latest -Pattern 'mixin|Mixin' -Encoding utf8
A ('- 行数 **' + @($mx).Count + '**')
if ($mx) { A '```'; foreach ($x in $mx | Select-Object -First 10) { A ('L' + $x.LineNumber + ': ' + $x.Line.Trim()) }; A '```' }
A ''
A '### debug.log 织入行（DEBUG 级）'
if (Test-Path $DebugLog) {
  $mix = Select-String -LiteralPath $DebugLog -Pattern 'Mixing (KeyMappingMixin|MinecraftMixin) from' -Encoding utf8 | Select-Object -Last 6
  if ($mix) { A '```'; foreach ($x in $mix) { A ('L' + $x.LineNumber + ': ' + $x.Line.Trim()) }; A '```' } else { A '未采到 KeyMappingMixin/MinecraftMixin 织入行' }
} else { A 'debug.log 不存在' }
$failSandbox = @()
foreach ($p in @($Latest, $DebugLog)) { if (Test-Path $p) { $failSandbox += Select-String -LiteralPath $p -Pattern 'Mixin apply failed|InvalidInjectionException|MixinTransformerError' -Encoding utf8 } }
A ''
A ('- **Mixin apply failed / 注入异常命中数（应为 0）：' + @($failSandbox).Count + '**')
if (@($failSandbox).Count -gt 0) { A '```'; foreach ($x in $failSandbox | Select-Object -First 10) { A ('L' + $x.LineNumber + ': ' + $x.Line.Trim()) }; A '```' }

Sec '3) 核心：send ping 与 marker created'
$sp = @(Select-String -LiteralPath $Latest -Pattern '\[PingForIt\] send ping:' -Encoding utf8)
$mc = @(Select-String -LiteralPath $Latest -Pattern '\[PingForIt\] marker created:' -Encoding utf8)
A ('- `[PingForIt] send ping:` 共 **' + $sp.Count + '** 条')
if ($sp.Count -gt 0) { A '```'; foreach ($x in $sp) { A ('L' + $x.LineNumber + ': ' + $x.Line.Trim()) }; A '```' }
A ('- `[PingForIt] marker created:` 共 **' + $mc.Count + '** 条')
if ($mc.Count -gt 0) { A '```'; foreach ($x in $mc) { A ('L' + $x.LineNumber + ': ' + $x.Line.Trim()) }; A '```' }
A ''
A '### 配对判断（按出现顺序，只陈述事实）'
if ($sp.Count -eq 0 -and $mc.Count -eq 0) { A '- 两者均为 0 —— **未采到任何 ping 操作痕迹**' }
elseif ($sp.Count -eq $mc.Count) { A ('- 计数相等（' + $sp.Count + ':' + $mc.Count + '）') }
else { A ('- **计数不等（send ping ' + $sp.Count + ' : marker created ' + $mc.Count + '）**') }
$k = [Math]::Min($sp.Count, $mc.Count)
if ($k -gt 0) {
  A ''; A '| # | send ping 行 | 时间 | marker created 行 | 时间 | 先后 |'
  A '| --- | --- | --- | --- | --- | --- |'
  for ($i = 0; $i -lt $k; $i++) {
    $s = $sp[$i]; $m = $mc[$i]
    $st = if ($s.Line -match $tsRe) { $Matches[1] } else { '?' }
    $mt = if ($m.Line -match $tsRe) { $Matches[1] } else { '?' }
    $order = if ($s.LineNumber -lt $m.LineNumber) { 'send 先' } else { '**marker 先（异常）**' }
    A ('| ' + ($i+1) + ' | L' + $s.LineNumber + ' | ' + $st + ' | L' + $m.LineNumber + ' | ' + $mt + ' | ' + $order + ' |')
  }
}
A ''
A '### 全部 [PingForIt] 行'
$all = Select-String -LiteralPath $Latest -Pattern '\[PingForIt\]' -Encoding utf8
A ('- 共 ' + @($all).Count + ' 条')
if ($all) { A '```'; foreach ($x in $all) { A ('L' + $x.LineNumber + ': ' + $x.Line.Trim()) }; A '```' }

Sec '4) marker HUD pass failed'
$hud = Select-String -LiteralPath $Latest -Pattern 'marker HUD pass failed' -Encoding utf8
A ('- 命中 **' + @($hud).Count + '** 条')
if ($hud) { A '```'; foreach ($x in $hud | Select-Object -First 8) { A ('L' + $x.LineNumber + ': ' + $x.Line.Trim()) }; A '```' }

Sec '5) 全部 ERROR / WARN（分已知噪音 / 其它）'
A '> 级别格式为 `[thread/LEVEL]`，用 `/(ERROR|WARN|FATAL)\]`；不要用 `\[ERROR\]`（会假阴性）。'
$lev = @(Select-String -LiteralPath $Latest -Pattern '/(ERROR|WARN|FATAL)\]' -Encoding utf8)
A ('- 总计 **' + $lev.Count + '**（ERROR/FATAL ' + @($lev | Where-Object { $_.Line -match '/(ERROR|FATAL)\]' }).Count + '）')
$noisePat = 'Perfmon|PerfOS|Realms|realms|status=401|user properties|end_of_frame'
$noise = @($lev | Where-Object { $_.Line -match $noisePat })
$other = @($lev | Where-Object { $_.Line -notmatch $noisePat })
A ''; A ('### 已知噪音（Realms/Perfmon/end_of_frame）共 ' + $noise.Count + ' 条')
if ($noise) { A '```'; foreach ($x in $noise) { A ('L' + $x.LineNumber + ': ' + $x.Line.Trim()) }; A '```' } else { A '无' }
A ''; A ('### 其它（**需关注**）共 ' + $other.Count + ' 条')
if ($other) { A '```'; foreach ($x in $other) { A ('L' + $x.LineNumber + ': ' + $x.Line.Trim()) }; A '```' } else { A '无' }

Sec '6) 崩溃与退出'
$cr = Get-ChildItem $CrashDir -Filter '*.txt' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($cr) {
  A ('- **有 crash-report**: `' + $cr.FullName + '`（' + $cr.Length + ' B, ' + $cr.LastWriteTime.ToString('HH:mm:ss') + '）')
  $top = Get-Content $cr.FullName -Encoding utf8 | Select-Object -First 20
  A '```'; foreach ($x in $top) { A $x }; A '```'
  $cb = Select-String -LiteralPath $cr.FullName -Pattern 'Caused by:' -Encoding utf8 | Select-Object -First 1
  if ($cb) { A ('**第一段 Caused by**: L' + $cb.LineNumber + ': ' + $cb.Line) }
} else { A '- **无 crash-report**' }
$ex = Select-String -LiteralPath $Latest -Pattern 'Exception in thread|Caused by:' -Encoding utf8 | Select-Object -First 6
if ($ex) { A ''; A 'latest.log 中异常行:'; A '```'; foreach ($x in $ex) { A ('L' + $x.LineNumber + ': ' + $x.Line.Trim()) }; A '```' }

Sec '7) 用户操作痕迹（**推断**，非直接证据）'
A '> 以下由日志间接推断，标注置信度；日志无法证明的操作（如 F1 隐藏 HUD）明确写「无证据」。'
$ev = [ordered]@{
  '集成服务器启动（=进入世界）' = 'Starting integrated minecraft server'
  '玩家登录世界' = 'logged in with entity id'
  '离开世界/断开' = 'lost connection|Stopping server'
  '维度/存档保存' = 'Saving chunks for level'
}
foreach ($k2 in $ev.Keys) {
  $h = Select-String -LiteralPath $Latest -Pattern $ev[$k2] -Encoding utf8
  A ('- **' + $k2 + '**：命中 ' + @($h).Count + ' 条' + $(if (@($h).Count -gt 0) { '（首条 L' + $h[0].LineNumber + '）' } else { '' }))
  if ($h) { A '```'; foreach ($x in $h | Select-Object -First 6) { A ('L' + $x.LineNumber + ': ' + $x.Line.Trim()) }; A '```' }
}
A '- **F1 / 隐藏 HUD**：日志无对应输出，**无证据**，只能靠用户自述。'
A '- **切换维度**：日志无专门 INFO 行；若 `Saving chunks for level` 出现多个维度名，可作弱推断（置信度低）。'

Sec '8) 本目录快照'
$snaps = Get-ChildItem $RunDir -Filter 'snapshot-*.log' -ErrorAction SilentlyContinue | Sort-Object Name
if ($snaps) { A '```'; foreach ($s in $snaps) { A ($s.Name + '  ' + $s.Length + ' B') }; A '```' } else { A '无' }

A ''; A '---'; A '（5号 log-monitor 自动生成，无归因结论）'
$b | Set-Content -Path $out -Encoding utf8
Write-Output ('REPORT: ' + (Resolve-Path $out).Path)
Write-Output ('LINES: ' + $b.Count)
