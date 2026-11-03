# Local javac check for the three source sets of the multi-module layout
# (no Gradle, no network). Rebuilt 2026-11-03 for common/ + fabric/.
# Mirrors tools/compile-check.sh: MC jars from D:/Gradle/caches/fabric-loom/26.3,
# dependency jars from D:/Gradle/caches/modules-2.
#
# Passes (each earlier output dir is on the next pass's classpath):
#   [common]         common/src/main/java
#   [fabric-main]    fabric/src/main/java    (+ common classes)
#   [fabric-client]  fabric/src/client/java  (+ common + fabric-main classes)
#
# Server-safety gate: common/src/main/java is shared with the server side, so it must
# never reference net.minecraft.client.* or com.mojang.blaze3d.* (the same rule the
# split source sets enforce in Gradle). It runs before compiling and is re-checked
# afterwards; any hit exits 1 with the offending file:line list.
#
# Output (identical to compile-check.sh):
#   [gate] common/server-safety OK|FAILED ...
#   [<pass>] sources=N / exit=N / errors=N / classes=N / log=...
#   --- first errors (up to 40) ---
#   [summary] common errors=N classes=N | fabric-main errors=N classes=N | fabric-client errors=N classes=N
# Exit: 0 no errors, 1 compile errors or gate violation, 2 setup problem.
param([string]$Pass = "all")
$ErrorActionPreference = "Continue"

if (-not (@("all", "both", "common", "fabric-main", "fabric-client") -contains $Pass)) { $Pass = "all" }

if ($env:COMPILE_CHECK_ROOT) { $T = $env:COMPILE_CHECK_ROOT } else { $T = Split-Path -Parent $PSScriptRoot }
foreach ($required in @("build.gradle", "buildSrc", "common", "fabric")) {
    if (-not (Test-Path (Join-Path $T $required))) {
        Write-Error ("compile-check: not the multi-module project root (build.gradle/buildSrc/common/fabric): " + $T)
        exit 2
    }
}
Set-Location $T

# javac argfiles take forward slashes. Building the slash from its code point keeps this
# script free of literal backslash escapes, so it stays byte-comparable with the .sh side.
function ToSlash([string]$p) { return $p.Replace([string][char]92, "/") }

# --- toolchain ----------------------------------------------------------
$JAVAC = $null
$JAVAC_VER = $null
$candidates = @("C:/Program Files/Java/jdk-25/bin/javac.exe")
if ($env:JAVA_HOME) { $candidates += (ToSlash (Join-Path $env:JAVA_HOME "bin/javac.exe")) }
$cmdJavac = Get-Command javac -ErrorAction SilentlyContinue
if ($cmdJavac) { $candidates += $cmdJavac.Source }
foreach ($cand in $candidates) {
    if (-not (Test-Path $cand)) { continue }
    try {
        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName = $cand
        $psi.Arguments = "-version"
        $psi.UseShellExecute = $false
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true
        $proc = [System.Diagnostics.Process]::Start($psi)
        $ver = ($proc.StandardOutput.ReadToEnd() + " " + $proc.StandardError.ReadToEnd()).Trim()
        $proc.WaitForExit()
    } catch { continue }
    if ($ver -match "25[.]") { $JAVAC = $cand; $JAVAC_VER = $ver; break }
}
if (-not $JAVAC) {
    Write-Error "compile-check: JDK 25 javac not found (set JAVA_HOME to a JDK 25)"
    exit 2
}

# --- classpath ----------------------------------------------------------
$OUT = Join-Path $T "tools/.compile-check"
$mcDir = "D:/Gradle/caches/fabric-loom/26.3"
$CP_JARS = @()
foreach ($mc in @("minecraft-common.jar", "minecraft-client.jar", "minecraft-client-only.jar")) {
    $p = Join-Path $mcDir $mc
    if (-not (Test-Path $p)) {
        Write-Error ("compile-check: missing Minecraft jar: " + (ToSlash $p))
        exit 2
    }
    $CP_JARS += (ToSlash $p)
}
$depJars = @(Get-ChildItem "D:/Gradle/caches/modules-2/files-2.1" -Recurse -Filter *.jar -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch "-(sources|javadoc)[.]jar$" } |
    Sort-Object FullName |
    ForEach-Object { ToSlash $_.FullName })
if ($depJars.Count -lt 10) {
    Write-Error "compile-check: dependency cache looks empty under D:/Gradle/caches/modules-2"
    exit 2
}
$CP_JARS += $depJars

# M9 optional integrations: the very jars the modules compile against (run/mods, repo root).
# Same glob list as ext.integrationJars in buildSrc/src/main/groovy/multiloader-common.gradle,
# so a stray jar in run/mods can never silently widen the compile classpath (and
# sources/javadoc jars never sneak in).
$integrationGlobs = @("modmenu-*.jar", "voicechat-api-*.jar", "voicechat-*.jar", "DistantHorizons-*.jar", "DistantHorizonsApi-*.jar")
$integrationJars = @()
$modDir = Join-Path $T "run/mods"
if (Test-Path $modDir) {
    foreach ($jar in @(Get-ChildItem $modDir -Filter *.jar -File -ErrorAction SilentlyContinue | Sort-Object Name)) {
        if ($jar.Name -match "-(sources|javadoc)[.]jar$") { continue }
        foreach ($glob in $integrationGlobs) {
            if ($jar.Name -like $glob) { $integrationJars += $jar; break }
        }
    }
}
if ($integrationJars.Count -gt 0) {
    $CP_JARS += @($integrationJars | ForEach-Object { ToSlash $_.FullName })
    Write-Output ("compile-check: integration jars from run/mods: " + (($integrationJars | ForEach-Object { $_.Name }) -join ", "))
} else {
    Write-Output "compile-check: no integration jars matched in run/mods (M9 integration classes cannot compile)"
}
$CP_BASE = ($CP_JARS -join ";")

New-Item -ItemType Directory -Force -Path $OUT | Out-Null

# --- server-safety gate -------------------------------------------------
# common/ ships on the server-side classpath of every loader module, so client-only
# classes must never leak into it. Prints "file:line:text" for each hit.
$COMMON_SRC = Join-Path $T "common/src/main/java"
$gatePattern = "net[.]minecraft[.]client[.]|com[.]mojang[.]blaze3d"
function Invoke-Gate {
    $script:GATE_HITS = @()
    if (Test-Path $COMMON_SRC) {
        $script:GATE_HITS = @(Get-ChildItem -LiteralPath $COMMON_SRC -Recurse -Filter *.java -File -ErrorAction SilentlyContinue |
            Select-String -Pattern $gatePattern -AllMatches)
    }
}
Invoke-Gate
if ($script:GATE_HITS.Count -gt 0) {
    Write-Output ("[gate] common/server-safety FAILED: " + $script:GATE_HITS.Count + " client-only reference(s) in common/src/main/java")
    $script:GATE_HITS | Select-Object -First 40 | ForEach-Object {
        $rel = $_.Path.Substring($T.Length + 1)
        Write-Output ($rel + ":" + $_.LineNumber + ":" + $_.Line.Trim())
    }
    exit 1
}
Write-Output "[gate] common/server-safety OK (common/src/main/java: no net.minecraft.client. / com.mojang.blaze3d)"

# --- pass runner --------------------------------------------------------
function Invoke-Pass([string]$Name, [string]$SrcDir, [string]$Cp, [string]$OutDir) {
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    $srcFile = Join-Path $OUT ("src-" + $Name + ".txt")
    $cpFile = Join-Path $OUT ("cp-" + $Name + ".txt")
    $log = Join-Path $OUT ("javac-" + $Name + ".log")
    $src = @()
    if (Test-Path $SrcDir) {
        $src = Get-ChildItem $SrcDir -Recurse -Filter *.java |
            Sort-Object FullName |
            ForEach-Object { ToSlash $_.FullName }
    }
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [IO.File]::WriteAllLines($srcFile, [string[]]$src, $utf8)
    [IO.File]::WriteAllText($cpFile, ("-cp " + $Cp) + [Environment]::NewLine, $utf8)
    if (Test-Path $log) { Remove-Item $log -Force }
    Write-Output ("[" + $Name + "] sources=" + $src.Count)
    if ($src.Count -gt 0) {
        $env:JAVA_TOOL_OPTIONS = "-Duser.language=en -Duser.country=US"
        $argLine = '--release 25 -proc:none -nowarn -encoding UTF-8 -Xmaxerrs 20000 "@' + $cpFile + '" "@' + $srcFile + '" -d "' + $OutDir + '"'
        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName = $JAVAC
        $psi.Arguments = $argLine
        $psi.WorkingDirectory = $T
        $psi.UseShellExecute = $false
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true
        $proc = [System.Diagnostics.Process]::Start($psi)
        $errTask = $proc.StandardError.ReadToEndAsync()
        $stdout = $proc.StandardOutput.ReadToEnd()
        $stderr = $errTask.GetAwaiter().GetResult()
        $proc.WaitForExit()
        [IO.File]::WriteAllText($log, $stdout + $stderr, $utf8)
        Write-Output ("[" + $Name + "] exit=" + $proc.ExitCode)
    } else {
        [IO.File]::WriteAllText($log, "", $utf8)
    }
    $errors = @()
    if (Test-Path $log) { $errors = @(Select-String -LiteralPath $log -Pattern ": error:" -AllMatches) }
    $errCount = $errors.Count
    $classCount = @(Get-ChildItem $OutDir -Recurse -Filter *.class -ErrorAction SilentlyContinue).Count
    Write-Output ("[" + $Name + "] errors=" + $errCount)
    Write-Output ("[" + $Name + "] classes=" + $classCount)
    Write-Output ("[" + $Name + "] log=" + (ToSlash $log))
    if ($errCount -gt 0) {
        Write-Output "--- first errors (up to 40) ---"
        $errors | Select-Object -First 40 | ForEach-Object { $_.Line }
    }
    $script:LAST_ERRORS = $errCount
    $script:LAST_CLASSES = $classCount
}

$COMMON_OUT = Join-Path $OUT "common"
$FABRIC_MAIN_OUT = Join-Path $OUT "fabric-main"
$FABRIC_CLIENT_OUT = Join-Path $OUT "fabric-client"

foreach ($d in @($COMMON_OUT, $FABRIC_MAIN_OUT, $FABRIC_CLIENT_OUT)) {
    if (Test-Path $d) { Remove-Item $d -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $d | Out-Null
}

Write-Output ("compile-check: javac=" + $JAVAC + " (" + $JAVAC_VER + ")")
Write-Output ("compile-check: classpath jars=" + $CP_JARS.Count + " (MC 3 + modules-2 cache)")
Write-Output "compile-check: modules=common + fabric (3 javac passes)"

$runCommon = ($Pass -eq "all" -or $Pass -eq "both" -or $Pass -eq "common")
$runFabricMain = ($Pass -eq "all" -or $Pass -eq "both" -or $Pass -eq "fabric-main")
$runFabricClient = ($Pass -eq "all" -or $Pass -eq "both" -or $Pass -eq "fabric-client")

if ($runCommon) {
    Invoke-Pass "common" $COMMON_SRC $CP_BASE $COMMON_OUT
    $COMMON_ERRORS = $script:LAST_ERRORS
    $COMMON_CLASSES = $script:LAST_CLASSES
} else { $COMMON_ERRORS = 0; $COMMON_CLASSES = 0 }

if ($runFabricMain) {
    $fabricMainCp = (ToSlash $COMMON_OUT) + ";" + $CP_BASE
    Invoke-Pass "fabric-main" (Join-Path $T "fabric/src/main/java") $fabricMainCp $FABRIC_MAIN_OUT
    $FABRIC_MAIN_ERRORS = $script:LAST_ERRORS
    $FABRIC_MAIN_CLASSES = $script:LAST_CLASSES
} else { $FABRIC_MAIN_ERRORS = 0; $FABRIC_MAIN_CLASSES = 0 }

if ($runFabricClient) {
    $fabricClientCp = (ToSlash $COMMON_OUT) + ";" + (ToSlash $FABRIC_MAIN_OUT) + ";" + $CP_BASE
    Invoke-Pass "fabric-client" (Join-Path $T "fabric/src/client/java") $fabricClientCp $FABRIC_CLIENT_OUT
    $FABRIC_CLIENT_ERRORS = $script:LAST_ERRORS
    $FABRIC_CLIENT_CLASSES = $script:LAST_CLASSES
} else { $FABRIC_CLIENT_ERRORS = 0; $FABRIC_CLIENT_CLASSES = 0 }

# Re-check the gate after compiling: the pass must still hold once every module has
# been built (cheap, and it keeps the summary honest if sources changed mid-run).
Invoke-Gate
if ($script:GATE_HITS.Count -gt 0) {
    Write-Output ("[gate] common/server-safety FAILED after compile: " + $script:GATE_HITS.Count + " client-only reference(s) in common/src/main/java")
    $script:GATE_HITS | Select-Object -First 40 | ForEach-Object {
        $rel = $_.Path.Substring($T.Length + 1)
        Write-Output ($rel + ":" + $_.LineNumber + ":" + $_.Line.Trim())
    }
    exit 1
}
Write-Output "[gate] common/server-safety re-check after compile: OK"

Write-Output ("[summary] common errors=" + $COMMON_ERRORS + " classes=" + $COMMON_CLASSES + " | fabric-main errors=" + $FABRIC_MAIN_ERRORS + " classes=" + $FABRIC_MAIN_CLASSES + " | fabric-client errors=" + $FABRIC_CLIENT_ERRORS + " classes=" + $FABRIC_CLIENT_CLASSES)
if ($COMMON_ERRORS -eq 0 -and $FABRIC_MAIN_ERRORS -eq 0 -and $FABRIC_CLIENT_ERRORS -eq 0) { exit 0 }
exit 1
