#!/usr/bin/env bash
# Local javac check for the three source sets of the multi-module layout
# (no Gradle, no network). Rebuilt 2026-11-03 for common/ + fabric/.
# MC jars come from D:/Gradle/caches/fabric-loom/26.3, every dependency jar from
# D:/Gradle/caches/modules-2 (Fabric Loader / Fabric API modules + libs).
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
# Output (identical to compile-check.ps1):
#   [gate] common/server-safety OK|FAILED ...
#   [<pass>] sources=N / exit=N / errors=N / classes=N / log=...
#   --- first errors (up to 40) ---
#   [summary] common errors=N classes=N | fabric-main errors=N classes=N | fabric-client errors=N classes=N
# Exit: 0 no errors, 1 compile errors or gate violation, 2 setup problem.

set -u

COMPILE_CHECK_ROOT="${COMPILE_CHECK_ROOT:-}"
if [ -n "$COMPILE_CHECK_ROOT" ]; then
  T="$(cd "$COMPILE_CHECK_ROOT" && pwd)"
else
  T="$(cd "$(dirname "$0")/.." && pwd)"
fi
cd "$T" || { echo "compile-check: cannot cd to project root" >&2; exit 2; }
if [ ! -f build.gradle ] || [ ! -d buildSrc ] || [ ! -d common ] || [ ! -d fabric ]; then
  echo "compile-check: not the multi-module project root (build.gradle/buildSrc/common/fabric): $T" >&2
  exit 2
fi
TW="$(pwd -W 2>/dev/null || pwd)"
COMMON_SRC="common/src/main/java"

# --- toolchain ----------------------------------------------------------
JAVAC=""
JAVAC_VER=""
CANDIDATES=()
if [ -n "${JAVA_HOME:-}" ]; then CANDIDATES+=("$JAVA_HOME/bin/javac.exe"); fi
CANDIDATES+=("/c/Program Files/Java/jdk-25/bin/javac.exe")
if command -v javac >/dev/null 2>&1; then CANDIDATES+=("javac"); fi
for cand in "${CANDIDATES[@]}"; do
  if [ "$cand" != "javac" ] && [ ! -x "$cand" ]; then continue; fi
  ver="$("$cand" -version 2>&1 | head -n 1)"
  case "$ver" in
    *25*) JAVAC="$cand"; JAVAC_VER="$ver"; break ;;
  esac
done
if [ -z "$JAVAC" ]; then
  echo "compile-check: JDK 25 javac not found (set JAVA_HOME to a JDK 25)" >&2
  exit 2
fi

# --- classpath ----------------------------------------------------------
OUT_MSYS="tools/.compile-check"
OUT_WIN="$TW/tools/.compile-check"

CP_JARS=()
for mc in minecraft-common.jar minecraft-client.jar minecraft-client-only.jar; do
  if [ -f "/d/Gradle/caches/fabric-loom/26.3/$mc" ]; then
    CP_JARS+=("D:/Gradle/caches/fabric-loom/26.3/$mc")
  else
    echo "compile-check: missing Minecraft jar: D:/Gradle/caches/fabric-loom/26.3/$mc" >&2
    exit 2
  fi
done
dep_count=0
while IFS= read -r jar; do
  CP_JARS+=("$jar")
  dep_count=$((dep_count + 1))
done < <(find /d/Gradle/caches/modules-2/files-2.1 -name "*.jar" ! -name "*-sources.jar" ! -name "*-javadoc.jar" 2>/dev/null | sort | sed "s#^/d/#D:/#")
if [ "$dep_count" -lt 10 ]; then
  echo "compile-check: dependency cache looks empty under D:/Gradle/caches/modules-2" >&2
  exit 2
fi
# M9 optional integrations: the very jars the modules compile against (run/mods, repo root).
# Same glob list as ext.integrationJars in buildSrc/src/main/groovy/multiloader-common.gradle,
# so a stray jar in run/mods can never silently widen the compile classpath (and
# sources/javadoc jars never sneak in).
INTEGRATION_GLOBS=("modmenu-*.jar" "voicechat-api-*.jar" "voicechat-*.jar" "DistantHorizons-*.jar" "DistantHorizonsApi-*.jar")
INTEGRATION_JARS=()
INTEGRATION_NAMES=()
for jar in run/mods/*.jar; do
  if [ ! -f "$jar" ]; then continue; fi
  base="${jar##*/}"
  case "$base" in
    *-sources.jar|*-javadoc.jar) continue ;;
  esac
  for glob in "${INTEGRATION_GLOBS[@]}"; do
    case "$base" in
      $glob) INTEGRATION_JARS+=("$TW/$jar"); INTEGRATION_NAMES+=("$base"); break ;;
    esac
  done
done
if [ "${#INTEGRATION_JARS[@]}" -gt 0 ]; then
  CP_JARS+=("${INTEGRATION_JARS[@]}")
  joined="$(printf '%s, ' "${INTEGRATION_NAMES[@]}")"
  echo "compile-check: integration jars from run/mods: ${joined%, }"
else
  echo "compile-check: no integration jars matched in run/mods (M9 integration classes cannot compile)" >&2
fi
CP_BASE="$(IFS=";"; echo "${CP_JARS[*]}")"

mkdir -p "$OUT_MSYS"

# --- server-safety gate -------------------------------------------------
# common/ ships on the server-side classpath of every loader module, so client-only
# classes must never leak into it. Prints "file:line:text" for each hit.
GATE_LOG="$OUT_MSYS/gate-common.log"
run_gate() {
  : > "$GATE_LOG"
  if [ -d "$COMMON_SRC" ]; then
    grep -rnE 'net[.]minecraft[.]client[.]|com[.]mojang[.]blaze3d' "$COMMON_SRC" >> "$GATE_LOG" 2>/dev/null || true
  fi
  GATE_HITS="$(wc -l < "$GATE_LOG" | tr -d ' ')"
}
run_gate
if [ "$GATE_HITS" -gt 0 ]; then
  echo "[gate] common/server-safety FAILED: $GATE_HITS client-only reference(s) in $COMMON_SRC"
  sed -n '1,40p' "$GATE_LOG"
  echo "[gate] log=$TW/$GATE_LOG"
  exit 1
fi
echo "[gate] common/server-safety OK ($COMMON_SRC: no net.minecraft.client. / com.mojang.blaze3d)"

# --- pass runner --------------------------------------------------------
LAST_ERRORS=0
LAST_CLASSES=0
run_pass() {
  name="$1"; srcdir="$2"; cp="$3"; outdir="$4"; log="$5"
  mkdir -p "$outdir"
  find "$srcdir" -name "*.java" 2>/dev/null | sort > "$OUT_MSYS/src-$name.txt"
  sources="$(wc -l < "$OUT_MSYS/src-$name.txt" | tr -d ' ')"
  echo "-cp $cp" > "$OUT_MSYS/cp-$name.txt"
  : > "$log"
  echo "[$name] sources=$sources"
  if [ "$sources" -gt 0 ]; then
    JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US" \
      "$JAVAC" --release 25 -proc:none -nowarn -encoding UTF-8 -Xmaxerrs 20000 \
      "@$OUT_WIN/cp-$name.txt" "@$OUT_WIN/src-$name.txt" -d "$outdir" > "$log" 2>&1
    echo "[$name] exit=$?"
  fi
  errors="$(grep -c ": error:" "$log" 2>/dev/null || true)"
  errors="${errors:-0}"
  classes="$(find "$outdir" -name "*.class" 2>/dev/null | wc -l | tr -d ' ')"
  echo "[$name] errors=$errors"
  echo "[$name] classes=$classes"
  echo "[$name] log=$TW/$log"
  if [ "$errors" -gt 0 ]; then
    echo "--- first errors (up to 40) ---"
    grep ": error:" "$log" | head -n 40 || true
  fi
  LAST_ERRORS="$errors"
  LAST_CLASSES="$classes"
}

COMMON_OUT="$OUT_WIN/common"
FABRIC_MAIN_OUT="$OUT_WIN/fabric-main"
FABRIC_CLIENT_OUT="$OUT_WIN/fabric-client"

rm -rf "$COMMON_OUT" "$FABRIC_MAIN_OUT" "$FABRIC_CLIENT_OUT"
mkdir -p "$COMMON_OUT" "$FABRIC_MAIN_OUT" "$FABRIC_CLIENT_OUT"

JAVAC_SHOW="$(cygpath -w "$JAVAC" 2>/dev/null || echo "$JAVAC")"
echo "compile-check: javac=$JAVAC_SHOW ($JAVAC_VER)"
echo "compile-check: classpath jars=${#CP_JARS[@]} (MC 3 + modules-2 cache)"
echo "compile-check: modules=common + fabric (3 javac passes)"

run_pass "common" "$COMMON_SRC" "$CP_BASE" "$COMMON_OUT" "$OUT_MSYS/javac-common.log"
COMMON_ERRORS="$LAST_ERRORS"; COMMON_CLASSES="$LAST_CLASSES"

run_pass "fabric-main" "fabric/src/main/java" "$COMMON_OUT;$CP_BASE" "$FABRIC_MAIN_OUT" "$OUT_MSYS/javac-fabric-main.log"
FABRIC_MAIN_ERRORS="$LAST_ERRORS"; FABRIC_MAIN_CLASSES="$LAST_CLASSES"

run_pass "fabric-client" "fabric/src/client/java" "$COMMON_OUT;$FABRIC_MAIN_OUT;$CP_BASE" "$FABRIC_CLIENT_OUT" "$OUT_MSYS/javac-fabric-client.log"
FABRIC_CLIENT_ERRORS="$LAST_ERRORS"; FABRIC_CLIENT_CLASSES="$LAST_CLASSES"

# Re-check the gate after compiling: the pass must still hold once every module has
# been built (cheap, and it keeps the summary honest if sources changed mid-run).
run_gate
if [ "$GATE_HITS" -gt 0 ]; then
  echo "[gate] common/server-safety FAILED after compile: $GATE_HITS client-only reference(s) in $COMMON_SRC"
  sed -n '1,40p' "$GATE_LOG"
  echo "[gate] log=$TW/$GATE_LOG"
  exit 1
fi
echo "[gate] common/server-safety re-check after compile: OK"

echo "[summary] common errors=$COMMON_ERRORS classes=$COMMON_CLASSES | fabric-main errors=$FABRIC_MAIN_ERRORS classes=$FABRIC_MAIN_CLASSES | fabric-client errors=$FABRIC_CLIENT_ERRORS classes=$FABRIC_CLIENT_CLASSES"
if [ "$COMMON_ERRORS" -eq 0 ] && [ "$FABRIC_MAIN_ERRORS" -eq 0 ] && [ "$FABRIC_CLIENT_ERRORS" -eq 0 ]; then
  exit 0
fi
exit 1
