# Automated feature walkthrough

GitHub Actions provisions a fresh Synapse server, creates demo users, and runs
Send in an Android emulator. Instrumentation taps through the Pixel onboarding
hero, signs into `@alice:send.test` with a bare `http://10.0.2.2:8008` test
address, creates an encrypted direct conversation with `@bob:send.test`, sends
a sample message through the Matrix Rust SDK, and verifies it appears in the
local timeline. Screenshots are captured at native emulator resolution.

## Screenshots

### Onboarding hero

![Pixel onboarding hero](screenshots/onboarding-hero.png)

### Homeserver setup

![Homeserver setup](screenshots/sign-in.png)

Bare server names, full Matrix IDs, and full URLs are all accepted; connection
details resolve via `.well-known/matrix/client`.

### Conversation list

![Conversation list](screenshots/conversations.png)

### New encrypted conversation

![Create encrypted conversation](screenshots/new-chat.png)

The UI test checks that Create remains disabled without a Matrix user ID and
becomes enabled after a valid ID is entered.

### Encrypted message send

![Encrypted conversation](screenshots/conversation.png)

Grouped bubbles (primary for sent, `surfaceContainerHigh` for received),
overlapping reaction pills, timestamps per cluster, and the filled pill
composer with its circular send button.

### Reaction picker

![Reaction picker](screenshots/reaction-picker.png)

The UI test long-presses a message, selects 😂, and verifies the event ID and
reaction passed to the handler.

### Live Synapse walkthrough

![Create encrypted conversation with Bob](screenshots/demo-create-chat.png)

![Live demo conversation list](screenshots/demo-conversations.png)

![Live encrypted message](screenshots/demo-conversation.png)

## Automation coverage and limits

The UI suite verifies homeserver validation (including bare server names and
Matrix-ID-derived servers), new-chat recipient validation, long-press reaction
selection, legal notices, and the sign-in/home/conversation screens. The live
Synapse walkthrough proves password authentication, encrypted room creation,
navigation, and encrypted message send/render in the local timeline, plus
delivery/decryption of that message and a 👍 reaction on Bob's independent SDK
client after Bob joins the room. It does not verify reliable background
room-list synchronization.

Synapse is not configured with MatrixRTC/LiveKit; this does not verify calls.
Push notifications, Android Auto, widgets, dictation, and AI are roadmap items,
not implemented features. CI cannot resolve `matrix.reilly.asia`, so production
SSO/login is not covered.

`UiComparisonScreenshotTest` and `LocalSynapseIntegrationTest` capture the
screenshots. GitHub Actions uploads their original PNGs as `Send-UI-Screenshots`;
the PNGs checked into this folder are taken from that artifact, not recreated
or edited from mockups.
