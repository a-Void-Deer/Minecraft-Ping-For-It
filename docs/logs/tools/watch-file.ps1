<#
  watch-file.ps1 - 5号 log-monitor 的单进程增量日志监视器
  目的：替代 tail -F / tee / grep 多进程管道链（本机 msys 下会 fatal CreateFileMapping Win32 error 5）
  特点：按字节偏移增量读取 / GBK(936) 安全解码 / 自带超时 / 不 fork 子进程

  用法：
    pwsh -File docs/logs/tools/watch-file.ps1 -Path 'run\logs\latest.log' -Seconds 150
    pwsh -File docs/logs/tools/watch-file.ps1 -Path 'run\logs\latest.log' -Seconds 60 -Pattern 'Mixin' -FromStart
#>
param(
  [Parameter(Mandatory=$true)][string]$Path,
  [int]$Seconds = 150,
  [string]$Pattern = 'ERROR|FATAL|Exception|Caused by|Mixin apply failed|InvalidInjection|crash|OutOfMemoryError',
  [switch]$FromStart,
  [int]$Encoding = 936,
  [int]$PollMs = 1000
)
$ErrorActionPreference = 'Stop'
$full = (Resolve-Path -LiteralPath $Path -ErrorAction SilentlyContinue)
if (-not $full) { Write-Output "MISSING: $Path (等待生成)"; exit 2 }
$full = $full.Path
$enc  = [System.Text.Encoding]::GetEncoding($Encoding)

$fs = [System.IO.File]::Open($full, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
$start = if ($FromStart) { 0 } else { $fs.Length }
[void]$fs.Seek($start, [System.IO.SeekOrigin]::Begin)
$reader = New-Object System.IO.StreamReader($fs, $enc)

Write-Output "=== WATCH START $([DateTime]::Now.ToString('HH:mm:ss')) file=$full offset=$start secs=$Seconds ==="
$deadline = (Get-Date).AddSeconds($Seconds)
$hits = 0; $total = 0
$tail = ''
while ((Get-Date) -lt $deadline) {
  $chunk = $tail + $reader.ReadToEnd()
  if ($chunk) {
    $arr = $chunk -split "`r?`n"
    $tail = $arr[-1]
    $complete = if ($arr.Count -gt 1) { $arr[0..($arr.Count-2)] } else { @() }
    foreach ($line in $complete) {
      $total++
      if ($line -match $Pattern) { Write-Output $line; $hits++ }
    }
  }
  Start-Sleep -Milliseconds $PollMs
}
Write-Output "=== WATCH END $([DateTime]::Now.ToString('HH:mm:ss')) lines=$total hits=$hits finalLength=$($fs.Length) ==="
$reader.Dispose(); $fs.Dispose()
