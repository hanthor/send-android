# Local development

## Local Matrix homeserver

The optional Synapse service provides a disposable backend for account sign-in, encrypted direct-room creation, and message/reaction testing. It binds to the workstation loopback only; do not expose it to a public network.

```sh
docker compose -f dev/matrix/compose.yaml run --rm synapse generate
docker compose -f dev/matrix/compose.yaml up -d
curl --fail http://127.0.0.1:8008/_matrix/client/versions
docker compose -f dev/matrix/compose.yaml exec synapse \
  register_new_matrix_user -c /data/homeserver.yaml \
  -u alice -p local-dev-password --no-admin http://localhost:8008
```

The GitHub Actions workflow also starts this disposable Synapse instance, creates the same test account, and signs in through the Android emulator. This exercises the debug-only HTTP route end to end.

The local Synapse instance does not configure a MatrixRTC/LiveKit service, so it is for messaging and UI iteration, not call verification.

## UI design

Open `app/src/main/java/dev/send/matrix/UiPreviews.kt` in Android Studio and use Compose Preview to switch among the sign-in, conversation-list, and conversation fixtures. These fixtures are static and make no homeserver requests. For runtime screenshots, the GitHub Actions run uploads `Send-UI-Screenshots`; it includes the sign-in, home, and conversation screens. The CI instrumentation also starts SSO against `matrix.reilly.asia` to catch regressions in Android rustls initialization; it does not authenticate a user.

Builds and automated tests remain in GitHub Actions. Debug APKs are available from the `Send-debug-APK` artifact on a successful workflow run.
