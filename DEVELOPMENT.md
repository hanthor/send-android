# Local development

## Local Matrix homeserver

The optional Synapse service provides a disposable backend for account sign-in, encrypted direct-room creation, and message/reaction testing. It binds to the workstation loopback only; do not expose it to a public network.

```sh
docker compose -f dev/matrix/compose.yaml run --rm synapse generate
docker compose -f dev/matrix/compose.yaml up -d
curl --fail http://127.0.0.1:8008/_matrix/client/versions
dev/matrix/seed-demo.sh
```

This creates `@alice:send.test` and `@bob:send.test` with password
`local-dev-password` and sets Bob's display name. Sign into Alice in Send,
create a new encrypted conversation with `@bob:send.test`, then send a message.
The Android SDK creates the room encryption state and encrypts messages; the
fixture never injects plaintext events into encrypted rooms.

GitHub Actions provisions this disposable backend and runs the same real app
flow on an Android emulator. Synapse is messaging-only; it has no MatrixRTC/
LiveKit service, so it cannot verify calls.

## UI design and screenshots

- Use `app/src/main/java/dev/send/matrix/UiPreviews.kt` for static Compose previews.
- `docs/SCREENSHOT_WALKTHROUGH.md` documents the automated emulator walkthrough.
  CI captures fixture sign-in visuals and live Synapse conversations at the
  emulator's full resolution, then uploads them with the APK and test reports.
- CI cannot resolve `matrix.reilly.asia`; production SSO is not tested.

Builds and automated tests remain in GitHub Actions. Debug APKs are available
from the `Send-debug-APK` artifact on a successful workflow run.

