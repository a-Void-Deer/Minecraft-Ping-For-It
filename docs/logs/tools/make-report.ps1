<#
  make-report.ps1 - 5号：把一次 runClient 会话整理成 REPORT.md
  输入：run\logs\latest.log（或 final 快照）+ run\crash-reports
  输出：<RunDir>\REPORT.md
  只记原文 + 行号，不做归因（归因交 1号）
#>
param(
  [string]$Latest  = 'run\logs\latest.log',
  [string]$RunDir  = 'run\dsh-logs\run-latest',
  [string]$CrashDir = 'run\crash-reports'
)
$ErrorActionPreference = 'Continue'
if (-not (Test-Path $RunDir)) { New-Item -ItemType Directory -Path $RunDir -Force | Out-Null }
$out = Join-Path $RunDir 'REPORT.md'
$b = New-Object System.Collections.Generic.List[string]
function A([string]$s) { $b.Add($s) }
function Sec([string]$t) { A ''; A ('## ' + $t); A '' }
function Code() { A '```' }

if (-not (Test-Path $Latest)) { Write-Output ('MISSING ' + $Latest); exit 2 }
$lines = Get-Content -LiteralPath $Latest -Encoding utf8
$n = $lines.Count
A ('# runClient 会话报告（5号 log-monitor）')
A ''
A ('- 分析文件：`' + $Latest + '`（' + $n + ' 行）')
A ('- 生成时间：' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
A ('- 说明：只含原文与行号，不做归因；归因按 AGENTS.md 分诊顺序交 1号。')

# ---- 首末行与总时长 ----
Sec '0) 会话首末与总时长'
$first = $lines[0]; $last = $lines[$n-1]
A '```'; A ('L1: ' + $first); A ('L' + $n + ': ' + $last); A '```'
$tsRe = '^\[(\d{2}:\d{2}:\d{2})\]'
$t1 = $null; $t2 = $null
if ($first -match $tsRe) { $t1 = [TimeSpan]::Parse($Matches[1]) }
if ($last  -match $tsRe) { $t2 = [TimeSpan]::Parse($Matches[1]) }
if ($t1 -and $t2) { A ('- 首行时间 ' + $t1 + ' → 末行时间 ' + $t2 + '，跨度 ' + [int](($t2 - $t1).TotalSeconds) + ' 秒') }

# ---- 1) mod 加载 ----
Sec '1) mod 加载与入口'
foreach ($pat in @('Loading Minecraft', 'Loading \d+ mods', '- ping-for-it', 'Hello Fabric world')) {
  $h = Select-String -Path $Latest -Pattern $pat -Encoding utf8 | Select-Object -First 3
  if ($h) { A '```'; foreach ($x in $h) { A ('L' + $x.LineNumber + ': ' + $x.Line.Trim()) }; A '```' } else { A ('**未采到**：' + $pat) }
}

# ---- 2) 核心：PingForIt 两条 ----
Sec '2) 核心验证：[PingForIt] 两条（本轮重点）'
$p1 = Select-String -Path $Latest -Pattern '\[PingForIt\] server world loading' -Encoding utf8
$p2 = Select-String -Path $Latest -Pattern '\[PingForIt\] client main loop started' -Encoding utf8
A ('- `[PingForIt] server world loading`（MinecraftServer#loadLevel）：**' + $p1.Count + ' 次**')
A ('- `[PingForIt] client main loop started`（Minecraft#run）：**' + $p2.Count + ' 次**')
if ($p1.Count -gt 0) { A '```'; foreach ($x in $p1) { A ('L' + $x.LineNumber + ': ' + $x.Line.Trim()) }; A '```' }
if ($p2.Count -gt 0) { A '```'; foreach ($x in $p2) { A ('L' + $x.LineNumber + ': ' + $x.Line.Trim()) }; A '```' }
if ($p1.Count -eq 0 -and $p2.Count -eq 0) { A ''; A '**两条均未出现** —— 这是本轮的关键负面结论，原文见下方 Mixin 段。' }

# ---- 3) 进世界相关 ----
Sec '3) 进世界相关（集成服务器 / 世界准备 / 区块 / 保存）'
$cats = [ordered]@{
  '集成服务器' = 'Starting integrated|IntegratedServer|Started serving|Starting minecraft server'
  '世界准备/生成' = 'Preparing start region|Preparing spawn area|Time elapsed|Generating|level\.dat'
  '玩家进服' = 'Setting user|joined the game|Player\d+'
  '保存/退出' = 'Saving and pausing|Saving chunks|Saving worlds|Stopping server|Stopping!'
}
foreach ($k in $cats.Keys) {
  $h = Select-String -Path $Latest -Pattern $cats[$k] -Encoding utf8 | Select-Object -First 15
  A ('### ' + $k + '（命中 ' + $h.Count + '）')
  if ($h) { A '```'; foreach ($x in $h) { A ('L' + $x.LineNumber + ': ' + $x.Line.Trim()) }; A '```' } else { A '未采到' }
}

# ---- 4) ERROR/WARN 分类 ----
Sec '4) 全部 ERROR / WARN（按类别）'
A '> 级别格式为 `[thread/LEVEL]`，故用 `/(ERROR|WARN|FATAL)\]`，不要用 `\[ERROR\]`（会假阴性 0 条）。'
$lev = Select-String -Path $Latest -Pattern '/(ERROR|WARN|FATAL)\]' -Encoding utf8
A ('- 总计 **' + $lev.Count + '** 条（ERROR/FATAL ' + (@($lev | Where-Object { $_.Line -match '/(ERROR|FATAL)\]' })).Count + '，WARN ' + (@($lev | Where-Object { $_.Line -match '/WARN\]' })).Count + '）')
$groups = [ordered]@{
  '渲染/图形' = 'Render|GL |OpenGL|Framebuffer|Texture|Shader|atlas'
  '网络/Realms/会话' = 'Realms|connect|Connect|auth|Auth|session|Session|HTTP_ERROR|status=401'
  '资源包/数据' = 'ResourcePack|resource pack|datapack|Data Pack'
  '声音/音频' = 'OpenAL|Sound|Audio|AL '
  'Mixin/类加载' = 'Mixin|mixin|ClassNotFound|NoClassDef|NoSuchMethod'
}
foreach ($k in $groups.Keys) {
  $h = $lev | Where-Object { $_.Line -match $groups[$k] } | Select-Object -First 12
  A ('### ' + $k + '（' + $h.Count + '）')
  if ($h) { A '```'; foreach ($x in $h) { A ('L' + $x.LineNumber + ': ' + $x.Line.Trim()) }; A '```' } else { A '无' }
}
$other = $lev | Where-Object { $ln = $_.Line; -not ($groups.Values | Where-Object { $ln -match $_ }) } | Select-Object -First 15
A '### 未归类（' + $other.Count + '）'
if ($other) { A '```'; foreach ($x in $other) { A ('L' + $x.LineNumber + ': ' + $x.Line.Trim()) }; A '```' } else { A '无' }

# ---- 5) 崩溃 ----
Sec '5) 崩溃栈（如有）'
$cr = Get-ChildItem $CrashDir -Filter '*.txt' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($cr) {
  A ('- crash-report：`' + $cr.FullName + '`（' + $cr.Length + ' B, ' + $cr.LastWriteTime.ToString('HH:mm:ss') + '）')
  $top = Get-Content $cr.FullName -Encoding utf8 | Select-Object -First 25
  A '```'; foreach ($x in $top) { A $x }; A '```'
  $cb = Select-String -Path $cr.FullName -Pattern 'Caused by:' -Encoding utf8 | Select-Object -First 1
  if ($cb) { A ('**第一段 Caused by**：L' + $cb.LineNumber + ': ' + $cb.Line) }
} else {
  A '- **无 crash-report**'
  $ex = Select-String -Path $Latest -Pattern 'Exception in thread|Caused by:' -Encoding utf8 | Select-Object -First 6
  if ($ex) { A '```'; foreach ($x in $ex) { A ('L' + $x.LineNumber + ': ' + $x.Line.Trim()) }; A '```' } else { A '- latest.log 中也无 `Exception in thread` / `Caused by:`' }
}

# ---- 6) Mixin ----
Sec '6) Mixin 相关（latest.log 级别）'
$mx = Select-String -Path $Latest -Pattern 'mixin|Mixin' -Encoding utf8
A ('- latest.log 中 mixin 相关行 **' + $mx.Count + '** 条')
$mxf = $mx | Where-Object { $_.Line -match 'failed|Failed|ERROR|Exception' }
A ('- 其中含 failed/ERROR/Exception 的 **' + $mxf.Count + '** 条')
if ($mxf) { A '```'; foreach ($x in $mxf | Select-Object -First 12) { A ('L' + $x.LineNumber + ': ' + $x.Line.Trim()) }; A '```' }
A ''
A '注：`Mixing ... into ...` 这类织入证据只在 `run/logs/debug.log`（DEBUG 级），不在 latest.log；'
A '需要确认织入时另查 debug.log。'

# ---- 7) 快照清单 ----
Sec '7) 本目录快照'
$snaps = Get-ChildItem $RunDir -Filter 'snapshot-*.log' -ErrorAction SilentlyContinue | Sort-Object Name
if ($snaps) { A '```'; foreach ($s in $snaps) { A ($s.Name + '  ' + $s.Length + ' B  ' + $s.LastWriteTime.ToString('HH:mm:ss')) }; A '```' } else { A '无快照' }

A ''
A '---'
A '（5号 log-monitor 自动生成；无归因结论）'

$b | Set-Content -Path $out -Encoding utf8
Write-Output ('REPORT: ' + (Resolve-Path $out).Path)
Write-Output ('LINES: ' + $b.Count)
