#!/usr/bin/env bash
# Capture Google Messages reference screens on the CI emulator.
# Usage: capture-messages.sh MESSAGES_APK OUT_DIR
# Outputs gm-<screen>.png + gm-<screen>.xml (uiautomator) + gm-<transition>.mp4.
set -euo pipefail

apk=$1
out=$2
pkg=com.google.android.apps.messaging
ui="python3 $(dirname "$0")/ui.py"
mkdir -p "$out"

shot() {
  adb exec-out screencap -p >"$out/gm-$1.png"
  $ui dump "$out/gm-$1.xml"
}

# Tap through Messages' first-run prompts (default-SMS, RCS, sign-in, permissions).
dismiss_onboarding() {
  for _ in $(seq 1 12); do
    $ui tap-any "Not now" "Skip" "No thanks" "Got it" "Continue" "Allow" "OK" \
      "Set default" "Set as default" "Agree" "Done" "Close" || break
    sleep 2
  done
}

# The google_apis image preinstalls Messages under a different key, so a newer
# store build may be rejected; fall back to whichever Messages is on the device.
adb install -r -g "$apk" || echo "::notice::Using the emulator's preinstalled Google Messages"
adb shell dumpsys package "$pkg" | grep -m1 versionName | tr -d ' ' >"$out/gm-version.txt" || true
cat "$out/gm-version.txt"
adb shell pm grant "$pkg" android.permission.READ_SMS 2>/dev/null || true
adb shell pm grant "$pkg" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb shell cmd role add-role-holder android.app.role.SMS "$pkg" 0 || true

# Seed a realistic two-sided thread through the emulator modem.
adb emu sms send 5551234 "Hey, are you free to talk?"
sleep 1
adb emu sms send 5551234 "Made it home. Thanks for today!"
sleep 1
adb emu sms send 5559876 "Lunch tomorrow?"

adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 6
dismiss_onboarding
sleep 2
shot home

# Conversation open transition: record, then open the seeded thread.
adb shell screenrecord --time-limit 5 /sdcard/gm-open.mp4 &
rec=$!
sleep 1
$ui tap-any "555-1234" "(555) 1234" "5551234" "+1 555-123-4" || $ui tap-any "Hey, are you free to talk?" "Made it home. Thanks for today!" || echo "::warning::conversation row not found"
wait $rec || true
adb pull /sdcard/gm-open.mp4 "$out/gm-open.mp4" >/dev/null
sleep 1
shot conversation

# Composer morph: mic -> send when a draft exists.
adb shell input text "Sure%swhat's%sup?"
sleep 1
shot composer-typing
adb shell input keyevent KEYCODE_BACK
adb shell input keyevent KEYCODE_BACK
sleep 1
adb shell input keyevent KEYCODE_BACK
sleep 2

# New conversation screen from the FAB.
$ui tap-any "Start chat" "New conversation" "Start new conversation" || echo "::warning::Start chat not found"
sleep 2
shot new-chat

ls -la "$out"
