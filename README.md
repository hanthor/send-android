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

## Build and verify

Builds and automated tests run in GitHub Actions (see
[.github/workflows/android.yml](.github/workflows/android.yml)):

- `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`
- Android-emulator instrumentation, including local Synapse password sign-in,
  login validation, new-chat validation, the long-press reaction flow, and
  sign-in/home/conversation screenshots.
- Successful runs upload the debug APK (`Send-debug-APK`), test results
  (`Send-Android-Test-Results`), and screenshots (`Send-UI-Screenshots`).

## Docs

- [DEVELOPMENT.md](DEVELOPMENT.md) — disposable local Synapse backend and UI workflow.
- [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md) — implemented paths and known limits.
- [ANDROID_INTEGRATIONS.md](ANDROID_INTEGRATIONS.md) — staged native-platform backlog.
