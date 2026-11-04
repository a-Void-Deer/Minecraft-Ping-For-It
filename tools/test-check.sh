#!/usr/bin/env bash
# Offline test runner (B6): JDK 25 javac + java, no Gradle/JUnit.
# Output goes under the already-gitignored tools/.compile-check/test-check/.
set -u
T="$(cd "$(dirname "$0")/.." && pwd)"; cd "$T" || exit 2
TW="$(pwd -W 2>/dev/null || pwd)"
is25() { [ -x "$1" ] && "$1" -version 2>&1 | grep -q 'version "25'; }
JAVA=""
if [ -n "${JAVA_HOME:-}" ] && is25 "$JAVA_HOME/bin/java.exe"; then JAVA="$JAVA_HOME/bin/java.exe"
elif is25 "/c/Program Files/Java/jdk-25/bin/java.exe"; then JAVA="/c/Program Files/Java/jdk-25/bin/java.exe"
elif command -v java >/dev/null 2>&1 && is25 "$(command -v java)"; then JAVA="java"; fi
[ -n "$JAVA" ] || { echo "test-check: JDK 25 java not found (set JAVA_HOME)" >&2; exit 2; }
JAVAC="$(dirname "$JAVA")/javac.exe"; [ -x "$JAVAC" ] || JAVAC="$(dirname "$JAVA")/javac"
[ -x "$JAVAC" ] || JAVAC="$(command -v javac || true)"
[ -n "$JAVAC" ] || { echo "test-check: javac not found" >&2; exit 2; }

MAIN_SRC="common/src/main/java"; TEST_SRC="common/src/test/java"
RUNNER="nx.pingwheel.common.testing.TestRunner"
OUT="tools/.compile-check/test-check"

CP=()
for mc in minecraft-common.jar minecraft-client.jar minecraft-client-only.jar; do
  CP+=("D:/Gradle/caches/fabric-loom/26.3/$mc")
  [ -f "/d/Gradle/caches/fabric-loom/26.3/$mc" ] || { echo "test-check: missing $mc" >&2; exit 2; }
done
while IFS= read -r j; do CP+=("$j"); done < <(find /d/Gradle/caches/modules-2/files-2.1 -name '*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' 2>/dev/null | sort | sed 's#^/d/#D:/#')
for d in run/mods compile-libs; do
  [ -d "$d" ] || continue
  for j in "$d"/*.jar; do [ -f "$j" ] || continue; case "$j" in *-sources.jar|*-javadoc.jar) continue;; esac; CP+=("$TW/$j"); done
done
CP_STR="$(IFS=';'; echo "${CP[*]}")"
rm -rf "$OUT"; mkdir -p "$OUT/main" "$OUT/test"
echo "test-check: java=$JAVA"
echo "test-check: javac=$JAVAC"
echo "test-check: classpath jars=${#CP[@]} (MC 3 + modules-2 + run/mods/compile-libs)"
echo "test-check: out=$OUT"
echo "------------------------------------------------------------"

if [ ! -d "$TEST_SRC" ] || [ -z "$(find "$TEST_SRC" -name '*.java' 2>/dev/null | head -n 1)" ]; then
  echo "[test] source dir: $TEST_SRC"
  echo "test-check: test base not landed yet (no .java under $TEST_SRC); nothing to run."
  echo "[summary] test-check status=SKIPPED tests=0 passed=0 failed=0 exit=0"
  exit 0
fi

find "$MAIN_SRC" -name '*.java' | sort > "$OUT/src-main.txt"
echo "[main] sources=$(wc -l < "$OUT/src-main.txt" | tr -d ' ')"
JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US" \
  "$JAVAC" --release 25 -proc:none -implicit:none -nowarn -encoding UTF-8 -Xmaxerrs 20000 \
  -cp "$CP_STR" "@$TW/$OUT/src-main.txt" -d "$TW/$OUT/main" > "$OUT/javac-main.log" 2>&1
MAIN_RC=$?
echo "[main] exit=$MAIN_RC errors=$(grep -c ': error:' "$OUT/javac-main.log" 2>/dev/null || true) classes=$(find "$OUT/main" -name '*.class' | wc -l | tr -d ' ')"
if [ "$MAIN_RC" -ne 0 ]; then grep ': error:' "$OUT/javac-main.log" | head -n 30; echo "[summary] test-check status=FAILED phase=main exit=$MAIN_RC"; exit "$MAIN_RC"; fi

find "$TEST_SRC" -name '*.java' | sort > "$OUT/src-test.txt"
echo "[test] sources=$(wc -l < "$OUT/src-test.txt" | tr -d ' ')"
JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US" \
  "$JAVAC" --release 25 -proc:none -implicit:none -nowarn -encoding UTF-8 -Xmaxerrs 20000 \
  -cp "$TW/$OUT/main;$CP_STR" "@$TW/$OUT/src-test.txt" -d "$TW/$OUT/test" > "$OUT/javac-test.log" 2>&1
TEST_RC=$?
echo "[test] exit=$TEST_RC errors=$(grep -c ': error:' "$OUT/javac-test.log" 2>/dev/null || true) classes=$(find "$OUT/test" -name '*.class' | wc -l | tr -d ' ')"
if [ "$TEST_RC" -ne 0 ]; then grep ': error:' "$OUT/javac-test.log" | head -n 30; echo "[summary] test-check status=FAILED phase=test exit=$TEST_RC"; exit "$TEST_RC"; fi

echo "[runner] main=$RUNNER"
JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US" \
  "$JAVA" -cp "$TW/$OUT/test;$TW/$OUT/main;$CP_STR" "$RUNNER"
RC=$?
echo "[runner] exit=$RC"
S=PASSED; [ "$RC" -eq 0 ] || S=FAILED
echo "[summary] test-check status=$S exit=$RC"
exit "$RC"
