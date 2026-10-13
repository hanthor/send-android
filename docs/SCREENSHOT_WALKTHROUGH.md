# Automated feature walkthrough

GitHub Actions provisions a fresh Synapse server, creates demo users, and runs
Send in an Android emulator. Instrumentation signs into `@alice:send.test`,
creates an encrypted direct conversation with `@bob:send.test`, sends a message
through the Matrix Rust SDK, and verifies that it appears in the timeline.
Screenshots are captured from the live app at native emulator resolution.

## Screenshots

### Sign-in

![Sign-in screen](screenshots/sign-in.png)

### New encrypted conversation

![Create encrypted conversation](screenshots/demo-create-chat.png)

### Conversation list

![Live encrypted conversation list](screenshots/demo-conversations.png)

### Encrypted message exchange

![Live encrypted conversation](screenshots/demo-conversation.png)

## Automation coverage and limits

The UI suite also verifies homeserver URL validation, new-chat recipient
validation, the long-press reaction action, and sign-in/home/conversation
screenshots. The Synapse walkthrough proves password authentication, encrypted
room creation, sync, and encrypted send/render from the actual Android app.

Synapse is not configured with MatrixRTC/LiveKit; this does not verify calls.
Push notifications, Android Auto, widgets, dictation, and AI are roadmap items,
not implemented features. CI cannot resolve `matrix.reilly.asia`, so production
SSO/login is not covered.

`UiComparisonScreenshotTest` and `LocalSynapseIntegrationTest` capture the
screenshots. GitHub Actions uploads their original PNGs as `Send-UI-Screenshots`;
the PNGs checked into this folder are taken from that artifact, not recreated
or edited from mockups.
