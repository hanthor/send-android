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
    $ui tap-any "Wait" "Use Messages without an account" "Not now" "Skip" "No thanks" "Got it" "Continue" "Allow" "OK" \
      "Set default" "Set as default" "Agree" "Done" "Close" || break
    sleep 2
  done
}

if ! adb install -r -g "$apk"; then
  # google_apis ships an older Messages as a system app under another key. Remove it
  # (emulator runs with -writable-system) so the current Google-signed build installs.
  system_apk=$(adb shell pm path "$pkg" | head -1 | sed 's/^package://' | tr -d '\r')
  echo "Removing preinstalled $(dirname "$system_apk")"
  reboot_and_wait() {
    adb reboot
    adb wait-for-device
    until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done
  }
  # adbd restarts after boot and on `adb root`; retry until it is root and reachable.
  as_root() {
    for _ in $(seq 1 15); do
      if adb root && adb wait-for-device && [ "$(adb shell id -u | tr -d '\r')" = 0 ]; then return 0; fi
      sleep 3
    done
    return 1
  }
  # First remount disables verity and enables overlayfs; it only takes effect after a reboot.
  as_root
  adb remount || true
  reboot_and_wait
  as_root
  adb remount
  adb shell rm -rf "$(dirname "$system_apk")"
  adb shell pm uninstall "$pkg" || true
  reboot_and_wait
  adb install -r -g "$apk"
fi
# Software-GPU emulators lag after reboots; ANR dialogs would cover the captures.
adb shell settings put global hide_error_dialogs 1
sleep 20
adb shell dumpsys package "$pkg" | grep -m1 versionName | tr -d ' ' >"$out/gm-version.txt" || true
cat "$out/gm-version.txt"
adb shell pm grant "$pkg" android.permission.READ_SMS 2>/dev/null || true
adb shell pm grant "$pkg" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb shell cmd role add-role-holder android.app.role.SMS "$pkg" 0 || true
# Heads-up notifications from the seeded SMS would cover the captured screens.
adb shell settings put global heads_up_notifications_enabled 0

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

# Open transition: locate the row first (uiautomator dumps take seconds), then record
# and tap immediately so the whole animation lands inside the recording.
row=$($ui center "555-1234" "(555) 1234" "5551234" "+1 555-123-4" || $ui center "Hey, are you free to talk?" "Made it home. Thanks for today!" || true)
if [ -n "$row" ]; then
  adb shell screenrecord --time-limit 4 /sdcard/gm-open.mp4 &
  rec=$!
  sleep 1
  adb shell input tap $row
  wait $rec || true
  adb pull /sdcard/gm-open.mp4 "$out/gm-open.mp4" >/dev/null
else
  echo "::warning::conversation row not found"
fi
sleep 2
shot conversation

# Composer with a draft (send-state of the action button).
adb shell input text "Sure%swhat's%sup?"
sleep 1
shot composer-typing

# New conversation screen from the FAB: relaunch to the list instead of guessing back presses.
adb shell am force-stop "$pkg"
adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null
$ui wait "Start chat" --timeout 30 || echo "::warning::conversation list did not reappear"
fab=$($ui center "Start chat" "New conversation" "Start new conversation" || true)
if [ -n "$fab" ]; then
  adb shell screenrecord --time-limit 4 /sdcard/gm-new-chat.mp4 &
  rec=$!
  sleep 1
  adb shell input tap $fab
  wait $rec || true
  adb pull /sdcard/gm-new-chat.mp4 "$out/gm-new-chat.mp4" >/dev/null
  sleep 1
  shot new-chat
else
  echo "::warning::Start chat not found"
fi

ls -la "$out"
