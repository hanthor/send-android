#!/usr/bin/env bash
# Capture Google Messages reference screens on the CI emulator.
# Usage: capture-messages.sh MESSAGES_APK OUT_DIR
# Outputs gm-<screen>.png + gm-<screen>.xml (uiautomator) + gm-<transition>.mp4. A screen Messages
# cannot show on the emulator gets gm-<screen>.skipped.txt (the reason, quoted by compare.py)
# and a diagnostic screenshot under debug/.
set -euo pipefail

apk=$1
out=$2
pkg=com.google.android.apps.messaging
ui="python3 $(dirname "$0")/ui.py"
mkdir -p "$out"

shot() {
  if ! timeout 60 adb exec-out screencap -p >"$out/gm-$1.png" || [ ! -s "$out/gm-$1.png" ]; then
    rm -f "$out/gm-$1.png"
    echo "::warning::screencap failed for gm-$1"
    return 1
  fi
  $ui dump "$out/gm-$1.xml" || echo "::warning::no hierarchy for gm-$1"
}

# Log why a best-effort screen is missing and keep what the emulator showed instead.
skip() {
  echo "::warning::gm-$1 not captured: $2"
  echo "$2" >"$out/gm-$1.skipped.txt"
  mkdir -p "$out/debug"
  adb exec-out screencap -p >"$out/debug/gm-$1.png" || true
  $ui labels || true
}

# Tap through Messages' first-run prompts (default-SMS, RCS, sign-in, permissions).
dismiss_onboarding() {
  for _ in $(seq 1 12); do
    $ui tap-any "Wait" "Use Messages without an account" "Not now" "Skip" "No thanks" "Got it" "Continue" "Allow" "OK" \
      "Set default" "Set as default" "Agree" "Done" "Close" || break
    sleep 2
  done
}

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

if ! adb install -r -g "$apk"; then
  # google_apis ships an older Messages as a system app under another key. Remove it
  # (emulator runs with -writable-system) so the current Google-signed build installs.
  system_apk=$(adb shell pm path "$pkg" | head -1 | sed 's/^package://' | tr -d '\r')
  echo "Removing preinstalled $(dirname "$system_apk")"
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

# The emulator has no real SIM, but its modem delivers `adb emu sms send` as genuine incoming
# SMS, so Messages (the default SMS app) stores a real two-sided thread with 555-1234. Every
# conversation capture below (bubble long-press, swipe, composer panels, menus) uses that thread.
#
# Full-screen photo needs a received picture message, which the emulator modem cannot deliver
# (no MMS). As root, one from 555-0100 (an hour old) is written straight into the telephony
# provider before Messages is first opened; Messages imports provider changes made by other
# apps (logcat: "Message exists in Telephony but not in Bugle. Requesting Spot Sync").
python3 - <<'EOF'
from PIL import Image
Image.new("RGB", (640, 480), (66, 133, 244)).save("/tmp/gm-sample.jpg")
EOF
inject_photo_mms() {
  as_root || { why="adbd could not restart as root to write the telephony provider"; return 1; }
  local tid mid pid sent sub data bytes size
  tid=$(adb shell "content query --uri 'content://mms-sms/threadID?recipient=5550100'" | grep -o '_id=[0-9]*' | head -1 | cut -d= -f2)
  [ -n "$tid" ] || { why="telephony provider returned no thread for 555-0100"; return 1; }
  sub=$(adb shell "content query --uri content://telephony/siminfo --projection _id" | grep -o '_id=[0-9]*' | head -1 | cut -d= -f2)
  sent=$(($(date +%s) - 3600))
  size=$(stat -c %s /tmp/gm-sample.jpg)
  # Messages spot-syncs a provider row the moment it appears, so the row starts with m_type 0
  # (not a message type it imports) and only becomes a downloaded picture message (m_type 132 =
  # retrieve-conf, retr_st 128 = ok) once its sender and image part are in place.
  adb shell content insert --uri content://mms --bind thread_id:l:"$tid" --bind date:l:"$sent" --bind date_sent:l:"$sent" \
    --bind msg_box:i:1 --bind read:i:0 --bind seen:i:0 --bind m_type:i:0 --bind v:i:18 --bind text_only:i:0 \
    --bind pri:i:129 --bind retr_st:i:128 --bind m_size:i:"$size" --bind sub_id:i:"${sub:-1}" \
    --bind ct_t:s:application/vnd.wap.multipart.related --bind m_id:s:gm"$sent" --bind tr_id:s:gm"$sent"
  mid=$(adb shell "content query --uri content://mms --projection _id --sort '_id DESC'" | grep -o '_id=[0-9]*' | head -1 | cut -d= -f2)
  [ -n "$mid" ] || { why="telephony provider rejected the MMS row"; return 1; }
  adb shell content insert --uri "content://mms/$mid/addr" --bind address:s:5550100 --bind type:i:137 --bind charset:i:106
  adb shell content insert --uri "content://mms/$mid/part" --bind seq:i:0 --bind ct:s:image/jpeg --bind name:s:photo.jpg \
    --bind cl:s:photo.jpg --bind cid:s:photo
  pid=$(adb shell "content query --uri content://mms/$mid/part --projection _id" | grep -o '_id=[0-9]*' | head -1 | cut -d= -f2)
  [ -n "$pid" ] || { why="telephony provider rejected the MMS image part"; return 1; }
  adb shell "content write --uri content://mms/part/$pid" </tmp/gm-sample.jpg || true
  bytes=$(adb shell "content read --uri content://mms/part/$pid" | wc -c)
  if [ "$bytes" != "$size" ]; then
    # Fall back to writing the provider's part file directly (still owned by the provider).
    data=$(adb shell "content query --uri content://mms/$mid/part --projection _data" | grep -o '_data=[^,]*' | head -1 | cut -d= -f2 | tr -d '\r')
    adb push /tmp/gm-sample.jpg /data/local/tmp/gm-sample.jpg >/dev/null
    adb shell "cat /data/local/tmp/gm-sample.jpg > '$data' && chown radio:radio '$data'" || true
    bytes=$(adb shell "content read --uri content://mms/part/$pid" | wc -c)
  fi
  [ "$bytes" = "$size" ] || { why="the MMS image part reads back $bytes of $size bytes"; return 1; }
  adb shell content update --uri "content://mms/$mid" --bind m_type:i:132
  adb shell "content query --uri content://mms/$mid --projection _id:thread_id:m_type:sub_id:m_size"
  adb shell "content query --uri content://mms/$mid/addr --projection address:type"
  adb shell "content query --uri content://mms/$mid/part --projection _id:ct:_data"
}
photo_why=""
if ! inject_photo_mms; then
  photo_why=$why
  echo "::warning::photo MMS not injected: $why"
fi

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
mkdir -p "$out/debug" && adb logcat -d >"$out/debug/logcat-first-launch.txt" 2>&1 || true

# Open transition: locate the row first (uiautomator dumps take seconds), then record
# and tap immediately so the whole animation lands inside the recording.
row=$($ui center "555-1234" "(555) 1234" "5551234" "+1 555-123-4" || $ui center "Hey, are you free to talk?" "Made it home. Thanks for today!" || true)
if [ -n "$row" ]; then
  adb shell screenrecord --time-limit 10 /sdcard/gm-open.mp4 &
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

# Composer with a draft (send-state of the action button). The field needs focus first, and
# the draft is then sent (the emulator modem accepts outgoing SMS) so later captures see the
# empty composer with its voice button, plus one outgoing bubble.
if $ui tap-any id:compose_message_text "Text message"; then
  sleep 1
  adb shell input text "Sure%sthing"
  sleep 1
  shot composer-typing
  $ui tap-any "Send SMS" "Send message" "Send" "id:Compose:Draft:Send" || echo "::warning::send button not found"
  sleep 2
else
  skip composer-typing "message field not found in the 555-1234 thread"
fi

# Fresh conversation list: force-stop + launch instead of guessing back presses.
relaunch() {
  adb shell am force-stop "$pkg"
  adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null
  $ui wait "Start chat" --timeout 30 || echo "::warning::conversation list did not reappear"
  # The one-time "Spam protection" card on the list.
  if $ui center "Spam protection" >/dev/null; then $ui tap-any "OK" || true; sleep 1; fi
}

# Relaunch and open a thread; dismisses the first-open "Save <number>?" contact card, which
# is specific to unknown SMS numbers and would push every bubble down.
open_thread() {
  relaunch
  $ui tap-any "$1" || return 1
  $ui wait id:compose_message_text "Text message" --timeout 20 || return 1
  if $ui center "Save $1?" >/dev/null; then $ui tap-any "Close" || true; sleep 1; fi
}

# Attachments are MMS-only and the emulator's carrier has no MMS, so some panels answer
# with this dialog instead of opening.
no_mms_dialog() {
  if $ui center "Attachments are not supported in this conversation" >/dev/null; then
    $ui tap-any "OK" || true
    return 0
  fi
  return 1
}

long_press() { adb shell input swipe "$1" "$2" "$1" "$2" 1000; }

# New conversation screen from the FAB: relaunch to the list instead of guessing back presses.
# A cold-started list can swallow the first tap, so the FAB is tapped again if New chat
# (with its Create group entry) did not open.
relaunch
sleep 2
fab=$($ui center "Start chat" "New conversation" "Start new conversation" || true)
if [ -n "$fab" ]; then
  adb shell screenrecord --time-limit 10 /sdcard/gm-new-chat.mp4 &
  rec=$!
  sleep 1
  adb shell input tap $fab
  wait $rec || true
  adb pull /sdcard/gm-new-chat.mp4 "$out/gm-new-chat.mp4" >/dev/null
  if ! $ui center "Create group" >/dev/null && $ui tap-any "Start chat"; then
    $ui wait "Create group" --timeout 10 || true
  fi
  sleep 1
  shot new-chat
else
  skip new-chat "Start chat not found on the conversation list"
fi

# ---- Visual audit of Send's newer features: matching Messages surfaces (all best-effort). ----
# Create group (from New chat).
if $ui wait "Create group" --timeout 10 && $ui tap-any "Create group"; then sleep 2; shot new-group; else skip new-group "Create group not shown on New chat"; fi

# Account menu (top-right account disc; "Sign in" while no Google account is signed in) and
# its "Messages settings" entry.
open_account_menu() {
  relaunch
  $ui tap-any id:selected_account_disc "Sign in" "Account and settings" "Open account menu" || return 1
  $ui wait "Messages settings" --timeout 15
}
capture_account() {
  open_account_menu || return 1
  sleep 1
  shot account
}
capture_settings() {
  $ui tap-any "Messages settings" || return 1
  sleep 3
  $ui center "Messages settings" "Settings" "General" "~notifications" >/dev/null || return 1
  shot settings
}
if capture_account; then
  capture_settings || skip settings "Messages settings did not open from the account menu"
else
  skip account "account disc did not open a menu with Messages settings"
  skip settings "account menu (and so Messages settings) unavailable"
fi

# Conversation overflow (⋮, content-desc "More") and Search in conversation with a query.
capture_conversation_menu() {
  open_thread 555-1234 || return 1
  $ui tap-any "More" "More options" || return 1
  sleep 1.5
  shot conversation-menu
}
capture_conversation_search() {
  $ui tap-any "Search" "Search conversation" "Search in conversation" || return 1
  sleep 1.5
  adb shell input text "home"
  sleep 1
  # Run the search so matches are highlighted in the thread (not just the query suggestion).
  adb shell input keyevent KEYCODE_ENTER
  $ui wait "~result" --timeout 15 || return 1
  sleep 1
  shot conversation-search
}
if capture_conversation_menu; then
  capture_conversation_search || skip conversation-search "Search from the conversation overflow showed no result count for \"home\""
else
  skip conversation-menu "conversation overflow (More) not found"
  skip conversation-search "conversation overflow (More) not found"
fi

# Composer panels: emoji/stickers, gallery (media), and the + attach panel.
capture_panel() {
  local name=$1
  shift
  open_thread 555-1234 || return 1
  $ui tap-any "$@" || return 1
  sleep 3
  if no_mms_dialog; then return 1; fi
  # The media panel may ask for photo access the first time.
  if $ui tap-any "Allow" "Allow all" "Allow access to all photos"; then sleep 2; fi
  shot "$name"
}
capture_panel emoji-picker "Show attach emoji and stickers screen" "Emoji" "Emoji, stickers & GIFs" ||
  skip emoji-picker "composer emoji button did not open a panel"
# A real photo on the device so the media panel has something to show. Send's audit shots
# and the transition recordings (already pulled) leave the device, so the gallery shows only
# this photo.
adb shell "content delete --uri content://media/external/file --where \"relative_path LIKE 'Download/Send%' OR _display_name LIKE 'gm-%.mp4'\"" || true
adb shell "rm -rf /sdcard/Download/Send /sdcard/gm-*.mp4" || true
# The sample photo itself was created (and written as an MMS) before Messages' first launch.
adb push /tmp/gm-sample.jpg /sdcard/Pictures/gm-sample.jpg >/dev/null
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/gm-sample.jpg >/dev/null
capture_panel gallery "Show attach media screen" "Gallery" "Attach media" ||
  skip gallery "composer gallery button did not open the media panel"
capture_panel attachments "Show attach content screen" "Attach" "Add" ||
  skip attachments "composer + button did not open the attach panel"

# Voice recording: hold the voice button (released after 5 s: SMS threads then refuse the
# audio with the no-MMS dialog), then hold and slide up onto the lock.
voice_button() { $ui center "Show record a voice message screen" "id:Compose:Draft:Send" "Record voice message" "Voice message"; }
capture_voice_recording() {
  open_thread 555-1234 || return 1
  local mic
  mic=$(voice_button) || return 1
  adb shell input swipe $mic $mic 5000 &
  local hold=$!
  sleep 2.5
  shot voice-recording
  wait $hold || true
  sleep 2
  no_mms_dialog || true
}
capture_voice_recording || skip voice-recording "voice button not found"
capture_voice_locked() {
  open_thread 555-1234 || return 1
  local x y
  read -r x y < <(voice_button) || return 1
  adb shell input motionevent DOWN "$x" "$y"
  sleep 1.5
  for dy in 60 120 180 240 300 360; do adb shell input motionevent MOVE "$x" $((y - dy)); done
  sleep 0.5
  adb shell input motionevent UP "$x" $((y - 360))
  sleep 1.5
  if no_mms_dialog; then return 1; fi
  shot voice-locked
  # Discard the locked recording instead of sending it.
  $ui tap-any "Delete" "Discard" "Delete recording" "Cancel" || adb shell input keyevent KEYCODE_BACK
  sleep 1
  $ui tap-any "Discard" "Delete" || true
}
capture_voice_locked || skip voice-locked "hold + slide-up onto the lock did not keep the recording going"

# Open the injected 555-0100 photo full screen.
capture_media_viewer() {
  [ -z "$photo_why" ] || { why=$photo_why; return 1; }
  open_thread 555-0100 || { why="Messages did not import the injected 555-0100 photo message"; return 1; }
  if ! $ui tap-any "~photo" "~image" "~picture" "id:image_attachment_view"; then
    mkdir -p "$out/debug"
    adb logcat -d >"$out/debug/logcat-media-viewer.txt" 2>&1 || true
    why="Messages imported the 555-0100 thread but shows no photo bubble in it"
    return 1
  fi
  sleep 2.5
  shot media-viewer || { why="screencap failed"; return 1; }
}
capture_media_viewer || skip media-viewer "no photo message to open: $why (the emulator has no MMS)"

# Typing dots are an RCS signal sent by the other participant's client; the emulator has no
# RCS registration and no second RCS party, and plain SMS never carries typing state.
typing_reason="typing indicators need a second RCS participant; the emulator only has SMS"
echo "::warning::gm-typing not captured: $typing_reason"
echo "$typing_reason" >"$out/gm-typing.skipped.txt"

# A real incoming-message notification in the expanded shade.
adb shell input keyevent KEYCODE_HOME
adb emu sms send 5559876 "Are you coming tonight?"
sleep 5
adb shell cmd statusbar expand-notifications
sleep 2
shot notification
adb shell cmd statusbar collapse

# ---- Conversation list management (phase 5), all best-effort. ----
screen_width=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1 | cut -dx -f1 || true)
screen_width=${screen_width:-1080}
# 555-9876's row; its latest message is the notification text sent just above.
row_9876() { $ui center "Are you coming tonight?" "555-9876" "5559876" "Lunch tomorrow?"; }
row_1234() { $ui center "Made it home. Thanks for today!" "555-1234" "5551234"; }

# Mid-swipe: hold the finger part-way so the archive track shows, then go back and lift (nothing archived).
capture_swipe_archive() {
  relaunch
  read -r x y < <(row_9876) || { why="555-9876 row not found"; return 1; }
  adb shell input motionevent DOWN "$x" "$y"
  for step in 1 2 3 4 5 6 7; do adb shell input motionevent MOVE $((x + step * screen_width / 20)) "$y"; done
  sleep 0.7
  shot swipe-archive || { why="screencap failed mid-swipe"; adb shell input motionevent UP "$x" "$y"; return 1; }
  adb shell input motionevent MOVE "$x" "$y"
  adb shell input motionevent UP "$x" "$y"
  sleep 1
}
capture_swipe_archive || skip swipe-archive "$why"

# Long-press a row: Messages' selection bar, its overflow, and the Delete / Block confirmations (all cancelled).
capture_conversation_selection() {
  relaunch
  read -r x y < <(row_1234) || { why="555-1234 row not found"; return 1; }
  long_press "$x" "$y"
  sleep 1.5
  $ui center "~archive" "~delete" "~pin" >/dev/null || { why="long-press did not start selection"; return 1; }
  shot conversation-selection
  if $ui tap-any "More options" "More"; then
    sleep 1
    shot conversation-selection-menu
    if $ui tap-any "Block" "~block"; then
      sleep 1.5
      shot block-confirm
      $ui tap-any "Cancel" || adb shell input keyevent KEYCODE_BACK
      sleep 1
    else
      skip block-confirm "no Block entry in the selection overflow"
      adb shell input keyevent KEYCODE_BACK
    fi
  else
    skip conversation-selection-menu "no overflow in the selection bar"
    skip block-confirm "no overflow in the selection bar"
  fi
  # Selection may have ended with the dialog; start it again for Delete.
  if ! $ui center "~delete" >/dev/null; then long_press "$x" "$y"; sleep 1.5; fi
  if $ui tap-any "Delete" "Move to Trash" "~delete"; then
    sleep 1.5
    shot delete-conversation-confirm
    $ui tap-any "Cancel" || adb shell input keyevent KEYCODE_BACK
  else
    skip delete-conversation-confirm "no Delete action in the selection bar"
  fi
}
capture_conversation_selection || skip conversation-selection "$why"

# Swipe 555-9876 all the way (archive with its snackbar), then account menu → Archived lists it.
capture_archive() {
  relaunch
  read -r x y < <(row_9876) || { why="555-9876 row not found"; return 1; }
  adb shell input swipe "$x" "$y" $((x + screen_width * 6 / 10)) "$y" 300
  sleep 1
  $ui center "~archived" >/dev/null || { why="the swipe did not archive (no snackbar)"; return 1; }
  shot archive-snackbar
}
capture_archive || skip archive-snackbar "$why"
capture_archived() {
  open_account_menu || { why="account menu unavailable"; return 1; }
  $ui tap-any "Archived" || { why="no Archived entry in the account menu"; return 1; }
  sleep 2
  shot archived
}
capture_archived || skip archived "$why"
# Account menu → Spam & blocked, then its Blocked numbers list when the overflow offers it.
capture_blocked() {
  open_account_menu || { why="account menu unavailable"; return 1; }
  $ui tap-any "Spam & blocked" "Spam and blocked" "~blocked" || { why="no Spam & blocked entry in the account menu"; return 1; }
  sleep 2
  if $ui tap-any "More options" "More" && $ui tap-any "Blocked numbers" "~blocked"; then sleep 2; fi
  shot blocked-contacts
}
capture_blocked || skip blocked-contacts "$why"

# Swipe a bubble right: Messages' swipe-to-reply puts a reply preview above the composer.
capture_reply_swipe() {
  open_thread 555-1234 || return 1
  read -r x y < <($ui center "Hey, are you free to talk?") || return 1
  adb shell input swipe "$x" "$y" $((x + 450)) "$y" 400
  sleep 1.5
  $ui center "~replying" "~cancel reply" "~reply to" >/dev/null || return 1
  shot reply-preview
}
if capture_reply_swipe; then reply_done=1; else reply_done=0; fi

# Long-press an incoming bubble: reaction row + action menu over the blurred thread. It runs
# last so a hang in the heavy blur overlay cannot cost the captures above.
capture_message_actions() {
  open_thread 555-1234 || { why="could not open the 555-1234 thread"; return 1; }
  read -r x y < <($ui center "Hey, are you free to talk?") || { why="incoming bubble not found"; return 1; }
  long_press "$x" "$y"
  $ui wait "Copy" "Forward" --timeout 10 || { why="long-press did not open the message actions"; return 1; }
  sleep 1
  shot message-actions || { why="screencap of the selection overlay failed (emulator state: $(adb get-state 2>&1))"; return 1; }
}
capture_message_actions || skip message-actions "$why"

# Reply from the selection's Reply action when the swipe did not offer one.
capture_reply_action() {
  $ui tap-any "Reply" || return 1
  sleep 1.5
  shot reply-preview
}
# Messages logs "Can't reply/react reason: replies not supported for this message
# type/protocol" (message_protocol=SIMPLE) for SMS, so this only succeeds on RCS.
if [ "$reply_done" = 0 ]; then
  capture_reply_action || skip reply-preview "Messages offers no reply for SMS (logcat: \"Can't reply/react reason: replies not supported for this message type/protocol\", message_protocol=SIMPLE); swiping the bubble right does nothing"
fi

# Message long-press → Star, then the thread's starred messages (⋮ → Starred, else account menu → Starred).
capture_starred() {
  open_thread 555-1234 || { why="could not open the 555-1234 thread"; return 1; }
  read -r x y < <($ui center "Hey, are you free to talk?") || { why="incoming bubble not found"; return 1; }
  long_press "$x" "$y"
  $ui wait "Copy" "Forward" --timeout 10 || { why="long-press did not open the message actions"; return 1; }
  $ui tap-any "Star" "Star message" "~star" || { why="no Star in the message actions"; adb shell input keyevent KEYCODE_BACK; return 1; }
  sleep 1
  if $ui tap-any "More" "More options" && $ui tap-any "Starred" "Starred messages" "~starred"; then
    sleep 2
  else
    adb shell input keyevent KEYCODE_BACK
    open_account_menu || { why="Starred not in the conversation overflow and no account menu"; return 1; }
    $ui tap-any "Starred" "Starred messages" "~starred" || { why="no Starred entry in the conversation overflow or account menu"; return 1; }
    sleep 2
  fi
  shot starred
}
capture_starred || skip starred "$why"

# Long-press → Select more, then tap a second bubble: Messages' multi-select bar.
capture_message_selection() {
  open_thread 555-1234 || { why="could not open the 555-1234 thread"; return 1; }
  read -r x y < <($ui center "Hey, are you free to talk?") || { why="incoming bubble not found"; return 1; }
  long_press "$x" "$y"
  $ui wait "Copy" "Forward" --timeout 10 || { why="long-press did not open the message actions"; return 1; }
  $ui tap-any "Select more" "Select" "~select" || { why="no Select more in the message actions"; adb shell input keyevent KEYCODE_BACK; return 1; }
  sleep 1
  if read -r x2 y2 < <($ui center "Made it home. Thanks for today!"); then adb shell input tap "$x2" "$y2"; sleep 1; fi
  shot message-selection
}
capture_message_selection || skip message-selection "$why"

ls -la "$out"
