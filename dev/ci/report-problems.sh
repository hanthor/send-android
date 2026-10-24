#!/usr/bin/env bash
# After a failed CI step: surfaces what went wrong as GitHub Actions annotations, which the checks API and
# the PR's Files view show (the raw log is only reachable through the Actions UI). Covers Kotlin compile
# errors ("e: file:///…:line:col message"), Gradle's "What went wrong" block and failing unit /
# instrumentation tests from their JUnit XML reports. GitHub keeps 10 annotations per step, so the first
# ten problems are reported and the rest counted.
# Usage: dev/ci/report-problems.sh <gradle log>…   (missing logs are skipped)
set -u
limit=${ANNOTATION_LIMIT:-10}
problems=$(mktemp)

# Workflow commands take one line; newlines are encoded as %0A. Fields: file, line, title, message.
emit() {
  local file="$1" line="$2" title="$3" message="$4"
  message=$(printf '%s' "$message" | head -c 6000 | sed ':a;N;$!ba;s/\n/%0A/g')
  if [ -n "$file" ]; then
    printf '::error file=%s,line=%s,title=%s::%s\n' "$file" "$line" "$title" "$message" >> "$problems"
  else
    printf '::error title=%s::%s\n' "$title" "$message" >> "$problems"
  fi
}

for log in "$@"; do
  [ -f "$log" ] || continue
  while IFS= read -r line; do
    path=$(printf '%s' "$line" | sed -E 's#^e: file://([^:]+):([0-9]+):([0-9]+) .*#\1#')
    lineno=$(printf '%s' "$line" | sed -E 's#^e: file://([^:]+):([0-9]+):([0-9]+) .*#\2#')
    message=$(printf '%s' "$line" | sed -E 's#^e: file://([^:]+):([0-9]+):([0-9]+) ##')
    emit "${path#"${GITHUB_WORKSPACE:-/nonexistent}"/}" "$lineno" "Kotlin compile error" "$message"
  done < <(grep -E '^e: file://' "$log" | sort -u)
  if grep -q '^\* What went wrong:' "$log"; then
    emit "" "" "Gradle" "$(sed -n '/^\* What went wrong:/,/^\* Try:/p' "$log" | head -n 20)"
  fi
done

# JUnit XML from the unit tests and the connected (emulator) tests: one line per failing test.
shopt -s nullglob globstar
for xml in app/build/test-results/testDebugUnitTest/*.xml app/build/outputs/androidTest-results/connected/**/*.xml; do
  while IFS=$'\t' read -r name message detail; do
    emit "" "" "Failed test: $name" "$message%0A$detail"
  done < <(python3 - "$xml" <<'PY'
import sys, xml.etree.ElementTree as ET
try:
    root = ET.parse(sys.argv[1]).getroot()
except Exception:
    sys.exit(0)
for case in root.iter("testcase"):
    for kind in ("failure", "error"):
        problem = case.find(kind)
        if problem is None:
            continue
        name = case.get("classname", "").split(".")[-1] + "." + case.get("name", "")
        text = (problem.text or "").strip()
        message = problem.get("message") or (text.splitlines()[0] if text else kind)
        # One line per test for the shell loop; the annotation re-expands %0A as line breaks.
        detail = "%0A".join(text.splitlines()[:12])
        print("\t".join(part.replace("\t", " ").replace("\n", " ") for part in (name, message, detail)))
PY
)
done

total=$(wc -l < "$problems")
head -n "$limit" "$problems"
if [ "$total" -gt "$limit" ]; then
  echo "::warning title=More problems::$((total - limit)) more problem(s) not annotated; see the step log."
fi
echo "Reported $total problem(s)."
rm -f "$problems"
exit 0
