#!/usr/bin/env bash
# Offline locale consistency check (B7). Mirrors tools/compile-check.sh style.
# Requires only JDK 25 + bash (java single-file source launcher).
# Read-only: never edits locale JSON or source files.
#
# Self-check (temporary copy; no repo files touched):
#   tmp=$(mktemp -d)
#   mkdir -p "$tmp/common/src/main/resources/assets/pingforit/lang" "$tmp/tools"
#   cp common/src/main/resources/assets/pingforit/lang/*.json "$tmp/common/src/main/resources/assets/pingforit/lang/"
#   cp tools/locale-allowlist.txt "$tmp/tools/"
#   grep -v '"settings.pingforit.title"' common/src/main/resources/assets/pingforit/lang/zh_cn.json > "$tmp/common/src/main/resources/assets/pingforit/lang/zh_cn.json"
#   bash tools/locale-check.sh --root "$tmp" --strict   # must exit 1 and print "missing zh_cn settings.pingforit.title"
set -u

T="$(cd "$(dirname "$0")/.." && pwd)"
cd "$T" || { echo "locale-check: cannot cd to project root" >&2; exit 2; }

is_jdk25() {
  [ -x "$1" ] && "$1" -version 2>&1 | grep -q 'version "25'
}
JAVA=""
if [ -n "${JAVA_HOME:-}" ] && is_jdk25 "$JAVA_HOME/bin/java.exe"; then
  JAVA="$JAVA_HOME/bin/java.exe"
elif is_jdk25 "/c/Program Files/Java/jdk-25/bin/java.exe"; then
  JAVA="/c/Program Files/Java/jdk-25/bin/java.exe"
elif command -v java >/dev/null 2>&1 && is_jdk25 "$(command -v java)"; then
  JAVA="java"
fi
if [ -z "$JAVA" ]; then
  echo "locale-check: JDK 25 java not found (set JAVA_HOME)" >&2
  exit 2
fi

echo "locale-check: java=$JAVA"
echo "locale-check: root=$T"
echo "locale-check: cmd=java tools/LocaleCheck.java $*"
echo "------------------------------------------------------------"
"$JAVA" tools/LocaleCheck.java "$@"
code=$?
echo "------------------------------------------------------------"
echo "[summary] locale-check exit=$code"
exit $code
