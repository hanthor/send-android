# Android-native experience roadmap

Goal: make Send feel native across Android system surfaces while keeping Matrix E2EE and user control intact. Treat this as a staged backlog, not a claim of implemented functionality. Do not copy Google Messages branding, assets, or source code; use it as a visual reference.

## P0 — messaging foundation

- **Conversation notifications — implemented (phase 2):** `MessagingStyle` per conversation on a "Messages" channel, `Person`s with Send's avatar colour/initial, long-lived dynamic conversation shortcuts tied to the notification by shortcut ID and `LocusId` (the activity sets the same locus while a conversation is open, so Android's per-conversation channel settings and priority apply), mark-read and `RemoteInput` reply actions, Android 13+ `POST_NOTIFICATIONS` requested once after sign-in with an explanation, and `VISIBILITY_PRIVATE` with a text-free public version. Decrypted text is never logged. Delivery: SDK sync while the process lives, UnifiedPush (Matrix gateway pusher) or a 15-minute WorkManager sync; see `IMPLEMENTATION_STATUS.md`.
- **Background send/read — implemented (phase 2):** notification actions run as expedited WorkManager jobs that restore the Rust SDK session and crypto store first; reply keeps the encrypted-room check and is only shown as sent after the send queue reports `SentEvent` (otherwise "Reply not sent"); mark-read sends one receipt for the newest notified event. Expired sessions surface as a failed action rather than a silent drop.
- **Direct share — implemented (phase 8):** `ShareActivity` (exported, translucent, no UI of its own) receives `ACTION_SEND` / `ACTION_SEND_MULTIPLE` for `text/*`, `image/*`, `video/*`, `audio/*` and `*/*`. It reads `EXTRA_TEXT` (led by `EXTRA_SUBJECT` when that isn't already part of it) and `EXTRA_STREAM` (single or list) plus `ClipData` URIs, and copies every stream into the app cache while the sender's read grant lasts, with the composer picker's copy code; `file:` URIs and Send's own provider are refused so a share can't pick up Send's private files. The staged share then goes to `MainActivity` in Send's own task (cold or warm start, like a notification deep link; kept across activity recreation). Signed out, sign-in comes first and the share continues afterwards. The recipient is picked from the Forward picker's list rows (encrypted conversations only, blocked DMs hidden), then "Send to <name>?" shows exactly what goes out — the text, photos and videos with thumbnails, other files by name, and anything that couldn't be read — with Send/Cancel (Cancel returns to the picker; Back there drops the share); nothing is sent without Send. Sending uses the encrypted text and attachment paths and opens that conversation. `res/xml/shortcuts.xml` declares a `<share-target>` for the same MIME types with category `dev.send.matrix.category.SHARE_TARGET`, and every long-lived conversation shortcut carries it — pushed for notifications and also when the user sends a message or a share there — so recent conversations show in the system share sheet; a share arriving with `EXTRA_SHORTCUT_ID` skips the picker when that room is still an encrypted joined conversation (Cancel then drops the share). Staged copies are deleted after sending, when the share is dropped and on sign-out. Tests: `ShareTargetUiTest` (fixtures) and `ZZShareTargetIntegrationTest` (local Synapse, Bob as an SDK client). Not yet: a "New message" launcher shortcut; the system share sheet's own UI and ranking can't be driven in CI.

Android guidance: https://developer.android.com/develop/ui/views/notifications/conversations

## P1 — calls and driving

- **Android Auto:** implement notification-powered messaging first, with MessagingStyle plus read/reply actions; spoken replies arrive through RemoteInput. Add templated Android Auto conversation browsing only after notification flows are robust. Declare Auto capabilities accurately. No SMS role or SMS permissions: Matrix is not SMS/RCS.
- **MatrixRTC calls — implemented (phase 3):** Element Call Android `v0.1.0-rc.7` over the authenticated Rust SDK session (native RTC core, mute/audio routing, its ongoing-call foreground service and notification, host picture-in-picture). Incoming rings are a full-screen `CallStyle` notification on a "Calls" channel with a ringing screen that shows over the lock screen (`USE_FULL_SCREEN_INTENT`, checked with `canUseFullScreenIntent` and linked from Account → Notifications when it's off), Answer/Decline (`Room.declineCall`), and stop on decline/answer elsewhere or expiry. Outgoing voice and video calls ring the other person through Element Call and end with "Call declined" when they decline. Calls show in the timeline, the list and a join banner. Microphone and camera are requested only when a call needs them. AndroidX Core-Telecom isn't used: Element Call owns call audio (audio-device controller, audio focus, microphone/camera foreground service), which a self-managed Telecom call would also claim. See `IMPLEMENTATION_STATUS.md`.

Android Auto: https://developer.android.com/training/cars/communication/notification-messaging
CallStyle: https://developer.android.com/develop/ui/compose/notifications/call-style

## P2 — user-selected chat surfaces

- **Conversation bubbles:** opt-in/promotable only for conversations the user chooses; launch directly into a specific chat and support correct task/back behavior. Depends on long-lived shortcuts and compliant conversation notifications; do not force bubbles.
- **Widgets:** glanceable pinned conversation/contact widget(s), with explicit refresh strategy and privacy-safe lock-screen behavior. Avoid showing plaintext by default; expose unread count/last activity or user-selected preview policy. Android Conversation Widgets can surface eligible long-lived shortcuts.
- **Quick Settings tile:** optional “availability”/sync state or open composer shortcut only if it adds value; never an always-on-microphone toggle.

Bubble guidance: https://developer.android.com/develop/ui/compose/notifications/bubbles

## P2 — voice and AI

- **Dictation:** microphone-button-triggered Android speech recognizer or ML Kit GenAI Speech Recognition (Gemini Nano, on supported devices); show transcript for edit/confirmation before send. Provide ordinary keyboard input fallback; model and language availability vary by device.
- **Read aloud:** Android `TextToSpeech` for a user-invoked message or conversation readout; stop/pause controls, respect audio focus and accessibility. Do not automatically speak sensitive content aloud.
- **Assistant/App Actions:** declare supported deep-link capabilities/shortcuts for open conversation, start draft, or compose. Require user confirmation before sending. The public API is App Actions/shortcuts, not a general private Gemini-app plugin API; availability/fulfillment is Assistant/platform dependent.
- **On-device writing assistance:** ML Kit GenAI/Gemini Nano can proofread, rewrite, summarize, or transcribe locally on supported Android devices. Make opt-in, label generated text, require user approval, and never silently send. Cloud model use must be a separate explicit consent path because it transmits decrypted E2EE content outside Matrix encryption.

Assistant actions: https://developer.android.com/develop/devices/assistant/intents
GenAI: https://developer.android.com/ai/gemini-nano

## P3 — API 37 and cross-device

- **Contact Picker (API 37):** invite/start-chat contact discovery from user-selected fields rather than broad READ_CONTACTS permission. Resolve Matrix identity only through explicit user actions and disclose that phone/email matching requires homeserver identity services.
- **Handoff (API 37):** research cross-device continuation for an open conversation/draft, with minimal state in handoff payload; never include message history, access tokens, or crypto secrets. Device/account availability and API ecosystem are constraints; keep optional.
- Keep app compatible with API 34 minimum; guard newer APIs and retain fallbacks.

Android 17 features: https://developer.android.com/about/versions/17/features

## Cross-cutting native polish

- Edge-to-edge, dynamic color, adaptive and themed icons, system bars, haptics, large fonts, TalkBack semantics, predictive back, foldable/tablet responsive layouts, and Android share/chooser contracts.
- Use `MessagingStyle` for messages, `CallStyle` for calls. Do not repurpose Android Live Updates/ProgressStyle for ordinary chat; it represents eligible ongoing journeys, not messages.
- Notification channels are user-controlled and stable. Respect silent/DND settings; test lock-screen previews, conversation priority, doze, process death, profile switching, and direct-boot constraints.
- Every integration must be backed by a real homeserver/SDK path; no mock UI flows or dead buttons. Ensure Matrix E2EE authorization, verification state, and persistence are honored when actions happen outside the foreground Activity.

## Delivery order

1. Reliable session/crypto restoration and conversation action handling. *(Done.)*
2. MessagingStyle, shortcuts, inline reply/read, deep links, notification privacy controls. *(Done in phase 2; CI run [38728600110](https://github.com/hanthor/send-android/actions/runs/38728600110).)*
3. Native call lifecycle and CallStyle *(done in phase 3, without Core-Telecom; CI run [38739979359](https://github.com/hanthor/send-android/actions/runs/38739979359))*, then Auto call/messaging compatibility.
4. Direct share *(done in phase 8)*, widgets, optional bubbles, dictation/read-aloud.
5. On-device AI, App Actions, API 37 contact picker/Handoff; capability-gate and review privacy implications individually.
