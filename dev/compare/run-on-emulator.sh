#!/usr/bin/env bash
# Runs inside android-emulator-runner: capture Send and Google Messages on the same device.
set -euo pipefail

out=/tmp/compare
mkdir -p "$out/ref"
# Software-GPU emulators throw launcher/System UI ANR dialogs over captures.
adb shell settings put global hide_error_dialogs 1
adb shell wm density | grep -o '[0-9]\+' | tail -1 >"$out/dpi"

# Send fixture shots are best-effort too: a failing (or not yet merged) test class only loses
# its own send-* screens, while the Messages capture and the report still run.
send_tests=(
  dev.send.matrix.UiComparisonScreenshotTest
  dev.send.matrix.SecurityUiTest
  dev.send.matrix.NotificationsUiTest
  dev.send.matrix.ConversationActionsUiTest
)
classes=$(IFS=,; echo "${send_tests[*]}")
./gradlew --no-daemon "-Dorg.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g" :app:connectedDebugAndroidTest \
  "-Pandroid.testInstrumentationRunnerArguments.class=$classes" ||
  echo "::warning::Send fixture tests failed; their missing screens are reported by compare.py"
mkdir -p "$out/Send"
adb pull /storage/emulated/0/Download/Send/ "$out/" || echo "::warning::no Send captures on the device"
cp -r app/build/outputs/androidTest-results/connected "$out/send-test-results" 2>/dev/null || true

# Reference capture is best-effort: Messages' first-run prompts change between releases,
# and compare.py reports any missing screen instead of failing the run.
dev/compare/capture-messages.sh /tmp/gm/messages.apk "$out/ref" || echo "::warning::Google Messages capture incomplete"
