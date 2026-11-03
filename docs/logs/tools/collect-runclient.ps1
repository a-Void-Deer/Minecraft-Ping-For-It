<#
  collect-runclient.ps1 - 5号 log-monitor：runClient 首启 6 项固定采集
  产出：docs/logs/raw/<stamp>-runclient-collect.md（只含原文+定位，不做归因）
  1号 要求的 6 项：
    1) Gradle JVM 版本行（确认 25 不是 24）
    2) 任务名（build / genSources / runClient）
    3) Launching / Loom run 配置行
    4) Mixin 全部 WARN/ERROR 原文（compatibilityLevel 提示=正常）
    5) 首启里程碑 LWJGL / Sound engine / 窗口
    6) 崩溃顶层异常类名 + 第一段 Caused by
  注意：daemon 日志选择按「最后一次构建启动时间」，不是 mtime ——
        空闲 daemon 会因健康检查刷新 mtime，按 mtime 会取到无关的旧日志（实测踩过）。
  用法：pwsh -File docs/logs/tools/collect-runclient.ps1
#>
param(
  [string]$LatestLog  = 'run\logs\latest.log',
  [string]$CrashDir   = 'run\crash-reports',
  [string]$DaemonDir  = 'D:\Gradle\daemon\9.8.2',
  [string]$OutDir     = 'docs\logs\raw',
  [int]$MaxMixinLines = 80
)
$ErrorActionPreference = 'Continue'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }
$out = Join-Path $OutDir "$stamp-runclient-collect.md"
$buf = New-Object System.Collections.Generic.List[string]
function Add-Line([string]$s) { $buf.Add($s) }
function Add-Section([string]$title) { Add-Line ''; Add-Line "## $title"; Add-Line '' }

# --- 选出「最近真正跑过构建」的 daemon 日志 ---
$logs = Get-ChildItem $DaemonDir -Filter *.log -ErrorAction SilentlyContinue
$active = $null
$activeTs = [datetime]::MinValue
foreach ($lg in $logs) {
  $st = Select-String -Path $lg.FullName -Pattern 'Starting \d+.. build in daemon' -Encoding utf8 | Select-Object -Last 1
  if ($st) {
    if ($st.Line -match '^(\d{4}-\d{2}-\d{2}T[\d:.]+)') {
      try {
        $ts = [datetime]::ParseExact($Matches[1], 'yyyy-MM-ddTHH:mm:ss.fff', [System.Globalization.CultureInfo]::InvariantCulture)
        if ($ts -gt $activeTs) { $activeTs = $ts; $active = $lg }
      } catch { }
    }
  }
}
if ($null -eq $active -and $logs) { $active = $logs | Sort-Object LastWriteTime -Descending | Select-Object -First 1 }

Add-Line "# runClient 首启采集报告 $stamp"
Add-Line ''
Add-Line ('采集时间: ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + '  |  采集者: 5号 log-monitor')

Add-Section '1) Gradle JVM 版本行（必须是 25）'
if ($null -eq $active) {
  Add-Line ('MISSING: 未找到任意 daemon 日志（' + $DaemonDir + '）')
} else {
  Add-Line ('选用 daemon 日志: ' + $active.Name + '（最后构建启动于 ' + $activeTs.ToString('yyyy-MM-dd HH:mm:ss') + '）')
  $ctx = Select-String -Path $active.FullName -Pattern 'DefaultDaemonContext\[' -Encoding utf8 | Select-Object -Last 1
  if ($ctx) { Add-Line ''; Add-Line '```'; Add-Line $ctx.Line; Add-Line '```' } else { Add-Line 'MISSING: 无 DefaultDaemonContext 行' }
}

Add-Section '2) 任务名（区分 build / genSources / runClient）'
if ($null -ne $active) {
  $tg = Select-String -Path $active.FullName -Pattern 'Calculating task graph .* tasks: ' -Encoding utf8 | Select-Object -Last 3
  if ($tg) { Add-Line '```'; foreach ($t in $tg) { Add-Line $t.Line }; Add-Line '```' } else { Add-Line '(无 task graph 行：可能命中 configuration cache)' }
  $st = Select-String -Path $active.FullName -Pattern 'Starting \d+.. build in daemon' -Encoding utf8 | Select-Object -Last 1
  if ($st) { Add-Line ''; Add-Line '```'; Add-Line $st.Line; Add-Line '```' }
  $res = Select-String -Path $active.FullName -Pattern 'BUILD SUCCESSFUL|BUILD FAILED' -Encoding utf8 | Select-Object -Last 2
  if ($res) { Add-Line ''; Add-Line '构建结果行:'; Add-Line '```'; foreach ($x in $res) { Add-Line $x.Line }; Add-Line '```' }
  Add-Line ''; Add-Line '提醒: BUILD SUCCESSFUL 不等于编译；以 build/libs 是否有 jar 为准。'
}

Add-Section '3) Launching / Loom run 配置行'
$launchPat = 'Launching|> Task :runClient|Run config|Fabric Loom|Starting process'
$hitLaunch = $false
if ($null -ne $active) {
  $lg = Select-String -Path $active.FullName -Pattern $launchPat -Encoding utf8 -CaseSensitive | Select-Object -Last 12
  if ($lg) { $hitLaunch = $true; Add-Line 'Gradle 侧:'; Add-Line '```'; foreach ($x in $lg) { Add-Line ($x.Line.Trim()) }; Add-Line '```' }
}
if (Test-Path $LatestLog) {
  $lg2 = Select-String -Path $LatestLog -Pattern $launchPat -Encoding utf8 -CaseSensitive | Select-Object -Last 12
  if ($lg2) { $hitLaunch = $true; Add-Line ''; Add-Line '游戏日志侧:'; Add-Line '```'; foreach ($x in $lg2) { Add-Line $x.Line }; Add-Line '```' }
} else {
  Add-Line ('MISSING: ' + $LatestLog + ' 尚未生成（runClient 未启动或启动即失败）')
}
if (-not $hitLaunch) { Add-Line '未命中 Launching 特征行 —— 记「未采到」，不代表失败。' }

Add-Section '4) Mixin 相关 WARN/ERROR 原文（compatibilityLevel 提示属正常）'
if (Test-Path $LatestLog) {
  $mx = Select-String -Path $LatestLog -Pattern 'mixin' -Encoding utf8 | Where-Object { $_.Line -match 'WARN|ERROR|FATAL|Exception|failed|Failed' }
  Add-Line ('命中行数: ' + $mx.Count + '（最多列 ' + $MaxMixinLines + ' 行）')
  if ($mx.Count -gt 0) { Add-Line ''; Add-Line '```'; $mx | Select-Object -First $MaxMixinLines | ForEach-Object { Add-Line ($LatestLog + ':' + $_.LineNumber + ': ' + $_.Line) }; Add-Line '```' }
} else { Add-Line ('MISSING: ' + $LatestLog) }

Add-Section '5) 首启里程碑（LWJGL / Sound engine / 窗口）'
if (Test-Path $LatestLog) {
  $msPat = 'Backend library|LWJGL|OpenAL|Sound engine|Narrator|Setting user|GL info|Framebuffer|Created: |Window'
  $ms = Select-String -Path $LatestLog -Pattern $msPat -Encoding utf8 | Select-Object -First 30
  if ($ms) { Add-Line '```'; foreach ($x in $ms) { Add-Line ($LatestLog + ':' + $x.LineNumber + ': ' + $x.Line) }; Add-Line '```' } else { Add-Line '未采到里程碑行（记「未采到」）' }
} else { Add-Line ('MISSING: ' + $LatestLog) }

Add-Section '6) 崩溃：顶层异常类名 + 第一段 Caused by'
$cr = Get-ChildItem $CrashDir -Filter '*.txt' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($cr) {
  Add-Line ('crash report: ' + $cr.FullName)
  $desc = Select-String -Path $cr.FullName -Pattern '^Description:' -Encoding utf8 | Select-Object -First 1
  if ($desc) { Add-Line ''; Add-Line '```'; Add-Line $desc.Line; Add-Line '```' }
  $top = Get-Content $cr.FullName -Encoding utf8 | Select-Object -First 30
  Add-Line ''; Add-Line '头部 30 行:'; Add-Line '```'; foreach ($l in $top) { Add-Line $l }; Add-Line '```'
  $cb = Select-String -Path $cr.FullName -Pattern 'Caused by:' -Encoding utf8 | Select-Object -First 1
  if ($cb) { Add-Line ''; Add-Line '第一段 Caused by:'; Add-Line '```'; Add-Line ($cr.Name + ':' + $cb.LineNumber + ': ' + $cb.Line); Add-Line '```' } else { Add-Line ''; Add-Line '无 Caused by（单层异常）' }
} elseif (Test-Path $LatestLog) {
  Add-Line ('无 crash-report；改从 ' + $LatestLog + ' 提取')
  $ex = Select-String -Path $LatestLog -Pattern 'Exception in thread|java\.[a-z.]*Exception|java\.[a-z.]*Error' -Encoding utf8 | Select-Object -First 5
  if ($ex) { Add-Line ''; Add-Line '```'; foreach ($x in $ex) { Add-Line ($LatestLog + ':' + $x.LineNumber + ': ' + $x.Line) }; Add-Line '```' } else { Add-Line '未发现异常行（记「未采到」）' }
  $cb2 = Select-String -Path $LatestLog -Pattern 'Caused by:' -Encoding utf8 | Select-Object -First 1
  if ($cb2) { Add-Line ''; Add-Line '第一段 Caused by:'; Add-Line '```'; Add-Line ($LatestLog + ':' + $cb2.LineNumber + ': ' + $cb2.Line); Add-Line '```' }
} else { Add-Line 'MISSING: 无 crash-report，也没有 latest.log' }

Add-Line ''
Add-Line '---'
Add-Line '（本报告只含原文与定位，不做归因；归因按 AGENTS.md §6-4 分诊顺序）'

$buf | Set-Content -Path $out -Encoding utf8
Write-Output ('REPORT: ' + (Resolve-Path $out).Path)
Write-Output ('LINES: ' + $buf.Count)
