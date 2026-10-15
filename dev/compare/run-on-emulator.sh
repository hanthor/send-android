#!/usr/bin/env bash
# Runs inside android-emulator-runner: capture Send and Google Messages on the same device.
set -euo pipefail

out=/tmp/compare
mkdir -p "$out/ref"
# Software-GPU emulators throw launcher/System UI ANR dialogs over captures.
adb shell settings put global hide_error_dialogs 1
adb shell wm density | grep -o '[0-9]\+' | tail -1 >"$out/dpi"

./gradlew --no-daemon "-Dorg.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g" :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.send.matrix.UiComparisonScreenshotTest
adb pull /storage/emulated/0/Download/Send/ "$out/"

# Reference capture is best-effort: Messages' first-run prompts change between releases,
# and compare.py reports any missing screen instead of failing the run.
dev/compare/capture-messages.sh /tmp/gm/messages.apk "$out/ref" || echo "::warning::Google Messages capture incomplete"
