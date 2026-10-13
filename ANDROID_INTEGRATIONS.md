# Android-native experience roadmap

Goal: make Send feel native across Android system surfaces while keeping Matrix E2EE and user control intact. Treat this as a staged backlog, not a claim of implemented functionality. Do not copy Google Messages branding, assets, or source code; use it as a visual reference.

## P0 — messaging foundation

- **Conversation notifications:** AndroidX `NotificationCompat.MessagingStyle`, Person metadata/avatar, long-lived dynamic conversation shortcuts, and per-conversation channel controls. Associate notifications with shortcut IDs and conversation locus. Add mark-read and `RemoteInput` inline reply actions. Respect Android 13+ notification permission, user DND/conversation priority controls, and lock-screen visibility settings. Default to hiding message bodies on lock screen; do not put decrypted plaintext in logs or broad system surfaces.
- **Background send/read:** action receivers/services must restore the Rust SDK client and crypto store before acting; handle expired session, locked/unavailable crypto, duplicate actions, and offline send safely. Never acknowledge send success until the SDK confirms queue/transaction result. Notification actions must not bypass room encryption checks.
- **Launcher entry points/direct share:** “New message” and recently used conversation shortcuts; `SharingShortcuts`/Chooser targets to share text and selected media into a chat. Require explicit recipient confirmation before sending shared content.

Android guidance: https://developer.android.com/develop/ui/views/notifications/conversations

## P1 — calls and driving

- **Android Auto:** implement notification-powered messaging first, with MessagingStyle plus read/reply actions; spoken replies arrive through RemoteInput. Add templated Android Auto conversation browsing only after notification flows are robust. Declare Auto capabilities accurately. No SMS role or SMS permissions: Matrix is not SMS/RCS.
- **MatrixRTC audio calls:** Send now uses Element Call Android `v0.1.0-rc.7` over the authenticated Rust SDK session, with its native RTC core, microphone-gated call start, mute/audio routing, upstream ongoing-call foreground service/notification, and host picture-in-picture wiring (`supportsPictureInPicture` + `ElementCallPictureInPicture.attach/onUserLeaveHint`). The release's public controller has no host-facing incoming-invitation or accept/decline API; inbound call UI requires a real invitation-delivery integration before it can be claimed complete. Add AndroidX Core-Telecom/CallStyle only when the MatrixRTC lifecycle integration supports it. Microphone permission is requested only after an audio call is initiated.

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

1. Reliable session/crypto restoration and conversation action handling.
2. MessagingStyle, shortcuts, inline reply/read, deep links, notification privacy controls.
3. Native call lifecycle and CallStyle, then Auto call/messaging compatibility.
4. Direct share, widgets, optional bubbles, dictation/read-aloud.
5. On-device AI, App Actions, API 37 contact picker/Handoff; capability-gate and review privacy implications individually.
