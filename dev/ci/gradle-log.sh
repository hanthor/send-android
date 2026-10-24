#!/usr/bin/env bash
# Runs Gradle with its output copied to a log file, keeping Gradle's exit status, so
# dev/ci/report-problems.sh can turn compile errors into GitHub annotations after a failure.
# Usage: dev/ci/gradle-log.sh <log file> <gradle arguments…>
set -u
log="$1"
shift
mkdir -p "$(dirname "$log")"
./gradlew "$@" 2>&1 | tee "$log"
exit "${PIPESTATUS[0]}"
