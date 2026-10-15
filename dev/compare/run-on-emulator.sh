#!/usr/bin/env bash
# Runs inside android-emulator-runner: capture Send and Google Messages on the same device.
set -euo pipefail

out=/tmp/compare
mkdir -p "$out/ref" "$out/Send"
# Software-GPU emulators throw launcher/System UI ANR dialogs over captures.
adb shell settings put global hide_error_dialogs 1
adb shell wm density | grep -o '[0-9]\+' | tail -1 >"$out/dpi"

# Send fixture shots (auditShot → Download/Send). `connectedDebugAndroidTest` only honours the
# first entry of a comma-separated class filter, so the fixture classes run through
# `am instrument` instead. Everything here is best-effort: a failing or not yet merged class
# only loses its own send-* screens, while the Messages capture and the report still run.
send_tests=(
  dev.send.matrix.UiComparisonScreenshotTest
  dev.send.matrix.SecurityUiTest
  dev.send.matrix.NotificationsUiTest
  dev.send.matrix.ConversationActionsUiTest
)
if ./gradlew --no-daemon "-Dorg.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g" :app:assembleDebug :app:assembleDebugAndroidTest &&
  adb install -r -g app/build/outputs/apk/debug/app-debug.apk &&
  adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk; then
  for test_class in "${send_tests[@]}"; do
    echo "::group::$test_class"
    adb shell am instrument -w -e class "$test_class" dev.send.matrix.test/androidx.test.runner.AndroidJUnitRunner |
      tee "$out/Send/instrument-${test_class##*.}.txt" || true
    echo "::endgroup::"
  done
else
  echo "::warning::Send fixture build or install failed; its screens are reported missing"
fi
adb pull /storage/emulated/0/Download/Send/ "$out/" || echo "::warning::no Send captures on the device"

# Reference capture is best-effort: Messages' first-run prompts change between releases,
# and compare.py reports any missing screen instead of failing the run.
dev/compare/capture-messages.sh /tmp/gm/messages.apk "$out/ref" || echo "::warning::Google Messages capture incomplete"
