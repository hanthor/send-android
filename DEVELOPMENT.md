# Local development

## Local Matrix homeserver

The optional Synapse service provides a disposable backend for account sign-in, encrypted direct-room creation, and message/reaction testing. It binds to the workstation loopback only; do not expose it to a public network.

```sh
sudo ip addr add 10.0.2.2/32 dev lo   # once per boot, for MatrixRTC (below)
docker compose -f dev/matrix/compose.yaml run --rm synapse generate
docker compose -f dev/matrix/compose.yaml up -d
curl --fail http://127.0.0.1:8008/_matrix/client/versions
dev/matrix/seed-demo.sh
dev/matrix/check-rtc.sh
```

This creates `@alice:send.test`, `@bob:send.test` and `@carol:send.test` (only
ever invited, by the details test) with password
`local-dev-password` and sets Bob's display name. It also raises Synapse's
login and message rate limits on this disposable server (and restarts it once),
because the emulator tests sign in several devices within seconds. Sign into
Alice in Send, create a new encrypted conversation with `@bob:send.test`, then
send a message. The Android SDK creates the room encryption state and encrypts
messages; the fixture never injects plaintext events into encrypted rooms.

### MatrixRTC

The same compose file runs the call backend Element Call needs:

- Synapse advertises the LiveKit transport through MSC4143
  (`experimental_features.msc4143_enabled`, `matrix_rtc.transports` →
  `http://10.0.2.2:8090`) and has MSC4140 delayed events
  (`max_event_delay_duration`), both appended by `seed-demo.sh`.
- `lk-jwt-service` (port 8090) swaps a user's Matrix OpenID token for a
  LiveKit token and creates the LiveKit room; it checks the token at
  `https://send.test:8448/_matrix/federation/v1/openid/userinfo`, which the
  `federation` Caddy service serves for Synapse with a throwaway certificate.
- `livekit` is the SFU (signalling on 7880, media on TCP 7881 and UDP
  50100–50200).

The URLs handed to clients name `10.0.2.2`, which is how the Android emulator
reaches the host. The host must answer on that address too, because
lk-jwt-service calls LiveKit's API at the same URL it gives clients: add it to
the loopback interface as above (CI does the same). `check-rtc.sh` walks the
chain once with Alice's account and prints the backend logs if a link is broken.

GitHub Actions provisions this disposable backend and runs the same real app
flow on an Android emulator, including calls between the app and an SDK
client (`ZZRtcCallsIntegrationTest`).

Sharing into Send can be tried without another app:
`adb shell am start -n dev.send.matrix/.ShareActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "Hello"`.
Conversations appear as direct-share targets in the system share sheet once
their shortcut exists (after a message notification, or after sending there).

## UI design and screenshots

- Use `app/src/main/java/dev/send/matrix/UiPreviews.kt` for static Compose previews.
- `docs/SCREENSHOT_WALKTHROUGH.md` documents the automated emulator walkthrough.
  CI captures fixture sign-in visuals and live Synapse conversations at the
  emulator's full resolution, then uploads them with the APK and test reports.
- `LargeScreenUiTest` resizes the emulator's display with `wm size` / `wm density` (reset after each test; the CI
  emulator caps each side at 1920px, so sizes are chosen by dp) to capture the list-detail layout at tablet size
  (`send-tablet-list-detail-device.png`), fold/unfold with a draft open (`send-fold-unfolded-device.png`,
  `send-fold-folded-device.png`) and a landscape phone; it runs in `LargeScreenFixtureActivity` (src/debug and
  src/minified only, never in release), which like `MainActivity` keeps running through size changes.
- CI cannot resolve `matrix.reilly.asia`; production SSO is not tested.
- `docs/accessibility.md` holds the TalkBack/touch-target conventions and the
  WCAG contrast table for both themes; `AccessibilityUiTest` checks 200% font
  scale, 48dp targets and labels, and captures the `dark-*` screens. Recompute
  the table when a colour token changes.

## Google Messages comparison

`.github/workflows/messages-comparison.yml` runs Send's screenshot tests and a
scripted Google Messages session on the same `pixel_6` Google APIs emulator,
then uploads the `Messages-Comparison` artifact: side-by-side composites,
`report.md` (dp-measured element bounds from uiautomator dumps, dominant
palettes, transition timing from screen recordings), and the raw captures.
It runs on `main` pushes touching `app/src/main/**` or `dev/compare/**`, or on
demand via *Run workflow*.

- `dev/compare/capture-messages.sh` drives Messages: seeds SMS through the
  emulator modem, dismisses first-run prompts, captures screens.
- `dev/compare/compare.py` builds the report.
- The Messages APK comes from APKPure as a style/behaviour reference only and
  is never uploaded. Decompiled Google code must never be committed here (AGPL).
- `.github/workflows/messages-motion.yml` decompiles Messages on a runner and
  `dev/compare/motion_scan.py` recovers its springs, tweens and easing curves
  from the obfuscated bytecode (numbers only, `Messages-Motion-Specs`
  artifact). `SendMotion.kt` holds the resulting tokens.

Builds and automated tests remain in GitHub Actions. Debug APKs are available
from the `Send-debug-APK` artifact on a successful workflow run.

## Releases

`.github/workflows/release.yml` runs on every pushed `v*` tag (and on demand via
*Run workflow*, which builds and tests but publishes nothing).

**Versions.** The tag is the version: `vMAJOR.MINOR.PATCH` or
`vMAJOR.MINOR.PATCH-rc.N`. Gradle reads it from `-PsendVersion=` (the workflow
passes the tag) or else from `git describe --tags --match 'v*' --abbrev=0`;
without any tag the build is `0.0.0-dev` / versionCode 1. versionCode is
`MAJOR*1_000_000 + MINOR*10_000 + PATCH*100 + N` for `-rc.N` and `+ 99` for the
final release, so `v0.1.0-rc.1` is 10001 and `v0.1.0` is 10099 (MINOR and PATCH
stay below 100, N between 1 and 98). Release notes for a version code go in
`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`; the workflow adds
that file to the GitHub Release when it exists.

**Cutting a release.** From an up-to-date `main` whose `Android CI` run is green:

```sh
git tag v0.1.0-rc.1 origin/main
git push origin v0.1.0-rc.1
```

The workflow then builds `:app:assembleRelease :app:bundleRelease` with R8
(`app/proguard-rules.pro`), checks every APK with `apksigner verify --print-certs`
and the AAB with `jarsigner -verify`, checks that the APKs carry the tag's
version, compressed native libraries of the right ABI, and no
cleartext/local-Synapse allowance, and in parallel runs the whole
instrumentation suite against the disposable Synapse on the `minified` build type
(release R8 rules plus `app/proguard-minified-test.pro`, which only keeps what the
separate test APK links against; debug-signed, with the debug cleartext config;
the emulator gets its x86_64 split APK). Only when both pass does it create the
GitHub Release (a prerelease for `-rc.N`) with the APKs, the AAB, the R8
`mapping.txt` (keep it: it is the only way to read crash stack traces from that
build) and `SHA256SUMS.txt`. To redo a tag, delete the GitHub Release and the tag
(`git push --delete origin v0.1.0-rc.1`), then push it again.

To run the suite on the R8 build yourself in CI, pass
`-PsendTestBuildType=minified` and use `:app:connectedMinifiedAndroidTest`;
without the property tests run on `debug` as usual.

**APKs, AAB and stores.** The Rust SDK and the RTC core are large native
libraries (about 100 MB per ABI uncompressed), so release builds are packaged
for download size:

- With `-PsendAbiSplits=true` (release.yml) `assembleRelease` makes one APK per
  ABI (`arm64-v8a`, `armeabi-v7a`, `x86_64`) plus a universal APK with all three.
  They all share the tag's versionCode: they are for sideloading from GitHub
  Releases, where a user installs exactly one. Debug builds never split
  (`app-debug.apk` stays one file).
- Release APKs compress their native libraries (`useLegacyPackaging`), which
  Android extracts on install; debug keeps AGP's uncompressed default.
- 32-bit `x86` is left out of release APKs and the AAB: no minSdk 34 device
  needs it and matrix-rtc-android ships no x86 core.
- AGP strips the bundled `.so` files with the NDK pinned in `android.ndkVersion`
  (CI installs that version); the upstream libraries already ship stripped, so
  this mainly guards future dependencies.
- Google Play takes the AAB (`.aab`) and builds per-device APKs from it itself.
  AGP can't bundle while ABI splits are on, so release.yml runs `bundleRelease`
  in a second Gradle invocation without `-PsendAbiSplits` and checks that both
  produced the same R8 mapping.
- F-Droid builds from source with its own recipe. A plain
  `gradle assembleRelease` (no `-PsendAbiSplits`) gives one APK with the three
  ABIs, which is what F-Droid would ship under the tag's versionCode. Per-ABI
  F-Droid builds would need a distinct versionCode per ABI (F-Droid's
  `VercodeOperation` plus a matching override here), which the version contract
  does not provide today.

**Signing.** Gradle signs the release build only when `SEND_KEYSTORE_FILE`
(path), `SEND_KEYSTORE_PASSWORD`, `SEND_KEY_ALIAS` and `SEND_KEY_PASSWORD` are
set in the environment; otherwise it stays unsigned. No key is ever committed.
For official builds the owner adds four repository secrets (*Settings → Secrets
and variables → Actions*):

| Secret | Value |
| --- | --- |
| `SEND_KEYSTORE_BASE64` | the keystore file, base64 on one line: `base64 -w0 send-release.jks` (macOS: `base64 -i send-release.jks`) |
| `SEND_KEYSTORE_PASSWORD` | the keystore password |
| `SEND_KEY_ALIAS` | the key's alias |
| `SEND_KEY_PASSWORD` | the key's password (the same as the store password for PKCS12) |

A key can be created once, offline, with
`keytool -genkeypair -keystore send-release.jks -storetype PKCS12 -alias send -keyalg RSA -keysize 4096 -validity 10000`.
Back it up somewhere safe: every future update must be signed with it (or with
the Play upload key, if Play App Signing is used).

Setting only some of the four secrets fails the workflow. With none of them set,
the workflow generates a throwaway key for that run (certificate CN
`Send CI throwaway – NOT FOR DISTRIBUTION`), names the files
`Send-<version>-UNOFFICIAL-ci-signed.*` and puts a warning at the top of the
release notes. Such builds can't be updated by any other build (each run's key
is different and discarded) and must not be distributed.
