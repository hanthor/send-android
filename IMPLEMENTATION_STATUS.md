# Implementation status

Send's Android shell and conversation-first Compose reference UI are present. The UI is currently a visual scaffold: sign-in, room list, chat, QR and call controls are not connected to a homeserver and must not be treated as working Matrix features.

## Verified dependency research

- Matrix Rust SDK Kotlin Android release `org.matrix.rustcomponents:sdk-android:26.09.28` is published on Maven Central. The latest published crypto artifact found is `org.matrix.rustcomponents:crypto-android:26.05.12`.
- Element Call Android is a native MatrixRTC implementation under `element-hq/element-call-android`. Release `0.1.0-rc.7` exists as GitHub-release Ivy assets, not Maven Central, and includes source/API archives. Its published module metadata requires Kotlin 2.4.10, Matrix RTC Android `0.5.0-rc.2`, JNA 5.19.1, and AndroidX Core 1.18.0, Activity 1.13.0, Lifecycle 2.11.0. Element X Android pins Rust SDK `26.10.05-nightly` for this integration; the latest stable SDK found on Maven Central was `26.09.28`. Mixing the RTC and stable SDK versions without compatibility checks is unsafe.
- Upstream requires a Rust RTC AAR, compatible Rust SDK version, and a homeserver configured with MatrixRTC transport/LiveKit. The release is prerelease and API changes are possible. The sample app uses fakes and has no login or server; it does not prove live call behavior.
- The requested `github.com/hanthor/fern` repo could not be found: GitHub API and `git ls-remote` both returned repository-not-found. Web search did not identify the intended project.

## Build environment and CI

- GitHub Actions workflow `.github/workflows/android.yml` runs `assembleDebug testDebugUnitTest lintDebug` after installing Android API 37 SDK/build tools. These checks have not been run; no GitHub remote/run result is available here.
- Current workspace has Java 17, Android platform 34/build-tools 34, and an API 34 emulator image; API 37 platform/build-tools are absent. No local builds/tests were run, per the request to run checks in GitHub CI only.
- Gradle wrapper creation was attempted, but the wrapper files have not been confirmed present. CI currently invokes `./gradlew`, so wrapper completion is necessary before GitHub checks can pass.

## Remaining implementation blockers

This checkout does not yet integrate/authenticate with the Matrix Rust SDK. No QR/password/SSO login, persisted session restore, encrypted-room gating, timeline, reaction, sync, or pagination behavior is implemented. The QR and call controls are no-ops, not functional paths.

A functioning MVP requires implementing the SDK APIs and a compatible Element Call Android/Rust RTC release pair, then testing against two accounts. The homeserver must provide encrypted direct rooms and MatrixRTC/LiveKit; those test accounts and services are not available in the workspace.
