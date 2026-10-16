#!/usr/bin/env bash
# Checks that app/src/main/assets/THIRD_PARTY_NOTICES.txt attributes everything Send bundles.
#
#   dev/check-notices.sh --deps <file>   output of `./gradlew :app:dependencies --configuration releaseRuntimeClasspath`
#   dev/check-notices.sh --apk <file>    an APK whose native libraries (lib/*/*.so) must all be attributed
#
# Both may be given, --apk more than once. dev/notices/coverage.txt maps every resolved Maven artifact and every
# native library to the notices entry (its heading line) that covers it. The check fails when
#   - a resolved group:artifact or a bundled .so has no mapping,
#   - a mapping names a heading that THIRD_PARTY_NOTICES.txt doesn't have,
#   - a mapping matches nothing that was checked (stale; remove it together with its notices entry).
# The resolved dependency list (group:artifact:version, deduplicated) is printed for the log.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
coverage="$root/dev/notices/coverage.txt"
notices="$root/app/src/main/assets/THIRD_PARTY_NOTICES.txt"
deps_file=""
apks=()
while [ $# -gt 0 ]; do
  case "$1" in
    --deps) deps_file="$2"; shift 2 ;;
    --apk) apks+=("$2"); shift 2 ;;
    *) echo "usage: $0 [--deps <dependencies report>] [--apk <apk>]..." >&2; exit 2 ;;
  esac
done
if [ -z "$deps_file" ] && [ ${#apks[@]} -eq 0 ]; then
  echo "usage: $0 [--deps <dependencies report>] [--apk <apk>]..." >&2
  exit 2
fi

# Headings: the first line of every blank-line-separated block of the notices file.
headings="$(awk 'prev == "" && $0 != "" { print } { prev = $0 }' "$notices")"
fail=0

# coverage.txt: "<selector> = <heading>"; selectors are group:artifact, group (any artifact of it), group.* (the
# group and its subgroups) or a native library file name (libfoo.so).
mappings="$(sed -e 's/#.*//' -e 's/[[:space:]]*$//' "$coverage" | awk 'NF')"
while IFS= read -r line; do
  heading="${line#*= }"
  if ! grep -qxF -- "$heading" <<< "$headings"; then
    echo "::error::coverage.txt maps to '$heading', which is not a heading in THIRD_PARTY_NOTICES.txt"
    fail=1
  fi
done <<< "$mappings"

# Every license named in the notices ("License: <SPDX expression> [(comment)]" lines and "- <SPDX expression>: ..."
# crate lines) needs its full text in assets/licenses/<id>.txt; the AGPL is assets/LICENSE.txt and LicenseRef-* terms
# are the upstream's own (Send doesn't take them).
license_ids="$(sed -n -E -e 's/^License: ([^(]*)( \(.*)?$/\1/p' -e 's/^- ([^:]*): .*/\1/p' "$notices" |
  tr ' ()' '\n\n\n' | grep -vE '^(OR|AND|WITH|)$' | sed 's/+$//' | sort -u)"
while IFS= read -r id; do
  case "$id" in
    AGPL-3.0-only | AGPL-3.0-or-later) file="$root/app/src/main/assets/LICENSE.txt" ;;
    LicenseRef-*) continue ;;
    *) file="$root/app/src/main/assets/licenses/$id.txt" ;;
  esac
  if [ ! -s "$file" ]; then
    echo "::error::THIRD_PARTY_NOTICES.txt names license '$id' but ${file#"$root"/} is missing"
    fail=1
  fi
done <<< "$license_ids"

# Prints the selector covering $1 (group:artifact) or nothing.
maven_selector() {
  local coordinate="$1" group="${1%%:*}" selector
  while IFS= read -r selector; do
    case "$selector" in
      *.so) ;;
      *:*) [ "$selector" = "$coordinate" ] && { echo "$selector"; return; } ;;
    esac
  done < <(cut -d' ' -f1 <<< "$mappings")
  while IFS= read -r selector; do
    case "$selector" in
      *.so | *:*) ;;
      *.\*) { [ "$group" = "${selector%.\*}" ] || [[ "$group" == "${selector%\*}"* ]]; } && { echo "$selector"; return; } ;;
      *) [ "$selector" = "$group" ] && { echo "$selector"; return; } ;;
    esac
  done < <(cut -d' ' -f1 <<< "$mappings")
  return 0
}

used=""
if [ -n "$deps_file" ]; then
  # Tree lines look like "+--- g:a:v", "\--- g:a:1.0 -> 1.2 (*)", "+--- g:a -> 1.2" (BOM-managed) or
  # "+--- g:a:{strictly 1.0} -> 1.0"; "(c)" lines are constraints, not dependencies.
  if grep -q ' FAILED$' "$deps_file"; then
    echo "::error::Unresolved dependencies in $deps_file:"
    grep ' FAILED$' "$deps_file"
    exit 1
  fi
  resolved="$(sed -n 's/^[| +\\-]*--- //p' "$deps_file" | grep -v ' (c)$' | grep -v '^project ' |
    sed -E -e 's/ \((\*|n)\)$//' -e 's/\{(strictly|require|prefer) ([^;}]*)[^}]*\}/\2/g' |
    awk '{ split($1, c, ":"); version = c[3]; if ($2 == "->") version = $3; print c[1] ":" c[2] ":" version }' |
    sort -u)"
  if [ -z "$resolved" ]; then
    echo "::error::No dependencies found in $deps_file"
    exit 1
  fi
  echo "Resolved release runtime dependencies ($(wc -l <<< "$resolved")):"
  echo "$resolved"
  while IFS= read -r dependency; do
    selector="$(maven_selector "${dependency%:*}")"
    if [ -z "$selector" ]; then
      echo "::error::$dependency is bundled but not covered by dev/notices/coverage.txt / THIRD_PARTY_NOTICES.txt"
      fail=1
    else
      used+="$selector"$'\n'
    fi
  done <<< "$resolved"
  while IFS= read -r selector; do
    case "$selector" in *.so) continue ;; esac
    if ! grep -qxF -- "$selector" <<< "$used"; then
      echo "::error::coverage.txt entry '$selector' matches no resolved dependency (stale?)"
      fail=1
    fi
  done < <(cut -d' ' -f1 <<< "$mappings")
  # Where the notices state a version ("group:artifact:1.2.3"), it must be the resolved one.
  while IFS= read -r dependency; do
    coordinate="${dependency%:*}"
    while IFS= read -r stated; do
      [ -n "$stated" ] || continue
      if [ "$stated" != "$dependency" ]; then
        echo "::error::THIRD_PARTY_NOTICES.txt names $stated but the release build resolves $dependency"
        fail=1
      fi
    done < <(grep -oE -- "$(sed 's/[.]/\\./g' <<< "$coordinate"):[0-9][A-Za-z0-9.+-]*[A-Za-z0-9]" "$notices" | sort -u || true)
  done <<< "$resolved"
fi

if [ ${#apks[@]} -gt 0 ]; then
  libs="$(for apk in "${apks[@]}"; do unzip -Z1 "$apk" 'lib/*' 2>/dev/null || true; done | awk -F/ '{ print $NF }' | sort -u)"
  echo "Native libraries in ${apks[*]}:"
  echo "${libs:-(none)}"
  while IFS= read -r lib; do
    [ -n "$lib" ] || continue
    if ! cut -d' ' -f1 <<< "$mappings" | grep -qxF -- "$lib"; then
      echo "::error::$lib is bundled but not covered by dev/notices/coverage.txt / THIRD_PARTY_NOTICES.txt"
      fail=1
    fi
  done <<< "$libs"
  while IFS= read -r selector; do
    case "$selector" in *.so) ;; *) continue ;; esac
    if ! grep -qxF -- "$selector" <<< "$libs"; then
      echo "::error::coverage.txt entry '$selector' matches no bundled native library (stale?)"
      fail=1
    fi
  done < <(cut -d' ' -f1 <<< "$mappings")
fi

if [ "$fail" -ne 0 ]; then
  echo "Update app/src/main/assets/THIRD_PARTY_NOTICES.txt and dev/notices/coverage.txt (see DEVELOPMENT.md, \"Third-party notices\")."
  exit 1
fi
echo "THIRD_PARTY_NOTICES.txt covers every checked dependency and native library."
