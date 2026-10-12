# Implementation status

Send is an original Kotlin/Compose Matrix Android client with a conversation-first interface inspired by Android messaging patterns. It does not contain code or assets decompiled from Google Messages.

## Implemented paths

- Rust Matrix SDK `26.09.28` authentication: password, QR scan, SSO browser redirect, persisted session restoration, and an encrypted local SDK store whose key is wrapped by Android Keystore.
- Live joined-room updates from the SDK room-list service. Unencrypted rooms are hidden from the conversation list and rejected by room-open, send, and reaction operations.
- Encrypted text timeline receive/send, backward pagination, reactions, and room-keyed composer drafts that survive activity/process restoration.
- Element Call Android `0.1.0-rc.7` MatrixRTC outgoing audio calls in encrypted direct (1:1) rooms using Rust RTC `0.5.0-rc.2`: microphone permission at call start, mute, audio-output selection, end-call control, and upstream foreground call service/notification.
- Instrumentation coverage for login requirements and new-chat dialog validation, plus home/conversation UI screenshot fixtures; GitHub Actions runs assemble, unit tests, lint, and Android-emulator instrumentation.

## Known implementation limits

- Element Call `0.1.0-rc.7` exposes no host-facing incoming-call invitation/event flow, accept/decline API, or supported inbound entry point. Incoming call alerts and accept/decline are not implemented; no fake inbound controls are shown.
- Encrypted 1:1 room creation from a Matrix user ID is implemented. Push notifications, Android Auto, widgets, dictation, text-to-speech, and AI features remain roadmap items in `ANDROID_INTEGRATIONS.md`.
- QR/SSO/password authentication, encryption recovery, messaging, and audio calls have not been exercised against a live homeserver/two-account setup. A working MatrixRTC homeserver/LiveKit configuration is also required for real call runtime verification.
- Element Call is dual-licensed `AGPL-3.0-only OR LicenseRef-Element-Commercial`; its attribution is packaged in `app/src/main/assets/THIRD_PARTY_NOTICES.txt`. Send has no selected project-level license yet, so resolve licensing before redistributing the combined APK.
- Element Call is a release candidate; upstream APIs may change. This repository uses its documented minimum Rust SDK version `26.09.26`; the pinned stable SDK `26.09.28` meets that floor, with build/runtime compatibility still to verify.

## Build and delivery

- Public repository: https://github.com/hanthor/send-android
- GitHub Actions provisions Gradle `9.6.0` through the committed Gradle wrapper and Android API 37; builds/tests/lint run in GitHub CI, not locally, per request.
- CI uploads the debug APK as the `Send-debug-APK` artifact and Compose home/conversation fixture screenshots as `Send-UI-Screenshots` for 30 days. Screenshots use sample content, not a live Matrix account; an APK is only ready to test when the corresponding CI run succeeds.
