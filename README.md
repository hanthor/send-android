# Send

Send is a standalone Android Matrix client with a conversation-first interface.
It uses the Matrix Rust SDK (Kotlin/Android binding) for messaging and
Element Call Android for native MatrixRTC audio calls. Only end-to-end
encrypted rooms can be opened for messaging.

## License

Send is licensed under the **GNU Affero General Public License v3.0 only**
(`AGPL-3.0-only`). See [LICENSE](LICENSE) for the full text.

This license was chosen because the app links Element Call Android
(`v0.1.0-rc.7`) and the Matrix Rust RTC core, which are dual-licensed
`AGPL-3.0-only OR LicenseRef-Element-Commercial` by Element Creations Ltd.;
Send is distributed under the AGPL-3.0-only option. The public repository at
<https://github.com/hanthor/send-android> is the Corresponding Source for
distributed builds. Other bundled libraries (Matrix Rust SDK, ZXing Android
Embedded, AndroidX) are Apache-2.0; see
[app/src/main/assets/THIRD_PARTY_NOTICES.txt](app/src/main/assets/THIRD_PARTY_NOTICES.txt)
and the in-app "About & licenses" dialog.

## Screenshots

The sign-in screenshot is a Compose UI fixture. Screenshots showing the demo
conversation list and conversation are captured from the live Android app
connected to disposable Synapse. All PNGs retain their original emulator
resolution and are linked to the full-size files.

### Sign-in options

[![Send sign-in screen](docs/screenshots/sign-in.png)](docs/screenshots/sign-in.png)

### New encrypted conversation

[![Create encrypted conversation with Bob](docs/screenshots/demo-create-chat.png)](docs/screenshots/demo-create-chat.png)

### Live demo conversation list

[![Live Send demo conversation list](docs/screenshots/demo-conversations.png)](docs/screenshots/demo-conversations.png)

### Encrypted message send

[![Live encrypted Send conversation](docs/screenshots/demo-conversation.png)](docs/screenshots/demo-conversation.png)

The emulator walkthrough signs in as the demo user, creates an encrypted
conversation with Bob, sends a sample message through the Matrix Rust SDK, and
verifies it appears in the local timeline. QR/SSO/password sign-in,
encrypted-room list and creation, encrypted text send, pagination, reactions,
and outgoing audio-call controls are implemented. Incoming-message sync and
calls need their respective working backend paths; calls require MatrixRTC
infrastructure.

## Build and verify

Builds and automated tests run in GitHub Actions (see
[.github/workflows/android.yml](.github/workflows/android.yml)):

- `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`
- Android emulator instrumentation against disposable Synapse, including real
  password sign-in and encrypted message exchange.
- Successful runs upload the debug APK (`Send-debug-APK`), test results
  (`Send-Android-Test-Results`), and full-resolution screenshots
  (`Send-UI-Screenshots`).

## Docs

- [Feature screenshot walkthrough](docs/SCREENSHOT_WALKTHROUGH.md) — automated scenarios and full-size screenshots.
- [DEVELOPMENT.md](DEVELOPMENT.md) — disposable local Synapse backend and UI workflow.
- [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md) — implemented paths and known limits.
- [ANDROID_INTEGRATIONS.md](ANDROID_INTEGRATIONS.md) — staged native-platform backlog.
