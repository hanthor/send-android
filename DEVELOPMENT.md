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
- CI cannot resolve `matrix.reilly.asia`; production SSO is not tested.

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

