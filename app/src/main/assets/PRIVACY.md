# Send privacy policy

Last updated: 2026-10-16. Applies to Send for Android (`dev.send.matrix`).

Send is a Matrix client. It has no servers of its own and no account system of
its own: you sign in to a Matrix homeserver that you choose, and almost all of
Send's network traffic goes to that homeserver. The people who run your
homeserver can see what any Matrix homeserver sees (your account, your rooms,
who you talk to and when, and the content of unencrypted messages). Read your
homeserver's own privacy policy too.

Send contains no analytics, no crash reporting, no advertising and no tracking
SDKs. The Matrix SDK is started with Sentry turned off and logs only to the
device log (logcat); nothing is uploaded.

## What leaves your device, and where it goes

### Your homeserver

- **Sign-in.** If you type a server name or a Matrix ID instead of a full URL,
  Send asks `https://<server>/.well-known/matrix/client` which homeserver to
  use. Your password, or the result of a QR-code sign-in, goes to that
  homeserver.
- **Messages, rooms and keys.** Everything a Matrix client does goes to your
  homeserver through the Matrix Rust SDK: sync, messages, reactions, read
  receipts, typing notifications, profile changes, room settings, and the
  end-to-end encryption keys and key backup the protocol needs. Messages in
  encrypted rooms are encrypted on your device before they are sent.
- **Media.** Photos, videos, files and voice messages you send are uploaded to
  your homeserver (encrypted first in encrypted rooms). Media you see is
  downloaded from your homeserver.
- **Link previews.** When "Show web link previews" is on (Settings → Automatic
  previews; on by default), Send asks your homeserver for a preview of the
  first link in a message. Your homeserver then fetches that web page, so it
  learns the links in your messages, including in encrypted rooms. Turn the
  setting off to stop this.
- **Devices.** The Devices page lists your sessions and signs other sessions
  out through your homeserver; the password you type to confirm goes only to
  your homeserver.
- **Background sync.** Without push notifications Send checks your homeserver
  for new messages about every 15 minutes.

### Single sign-on

If you sign in with single sign-on, or your homeserver asks you to confirm a
device sign-out in the browser, Send opens your homeserver's page in a browser
Custom Tab. That page and any identity provider it sends you to are outside
Send; your browser's own privacy policy applies.

### Push notifications (UnifiedPush)

Send uses UnifiedPush, not Google Firebase. Push only works if you install a
UnifiedPush distributor app (for example ntfy) and choose it in Send.

- Your distributor gives Send an endpoint URL. Send asks the push server behind
  that URL whether it is a Matrix gateway (`GET /_matrix/push/v1/notify`). If
  it is not, Send uses the public UnifiedPush gateway
  `https://matrix.gateway.unifiedpush.org`.
- Send registers a pusher on your homeserver with that gateway URL, your
  endpoint, the app name "Send", the device name "Send on <phone model>" and
  your language.
- Pushes use the `event_id_only` format: your homeserver sends only the room ID,
  the event ID and your unread count through the gateway and your
  distributor's push server, never message content or sender names. Send then
  fetches the message from your homeserver itself.

### Calls (Element Call)

Voice and video calls use the native Element Call Android library and
MatrixRTC.

- Call signalling (membership, rings, encryption keys) travels as Matrix events
  through your homeserver.
- Media goes to the LiveKit server your homeserver advertises (MatrixRTC
  transport discovery, or `rtc_foci` in its `.well-known`). Send asks your
  homeserver for an OpenID token and gives it to that server's authorisation
  service (lk-jwt-service) to get a call token; the LiveKit server then relays
  the call's audio and video, which is end-to-end encrypted between
  participants. Whoever runs that server sees who is in a call, when, and
  network metadata such as your IP address.
- The library is native code, not a web page: it does not load a web app,
  remote configuration or remote assets, and it contains no analytics or crash
  reporting. (Checked against the Element Call Android `v0.1.0-rc.7` and
  matrix-rust-rtc `v0.5.0-rc.2` sources.)

### Links and files you open

Tapping a link opens it in your browser. Opening an attachment hands a
read-only copy to the app you pick. Those apps have their own policies.

## What stays on your device

- **Matrix data.** The Matrix SDK keeps its databases (rooms, messages, your
  encryption keys) in Send's private app storage. Their contents are
  encrypted with a random key that is itself encrypted by an AES-GCM key held
  in Android Keystore. Table and entry names stay readable, and one of them
  in the encryption database contains your own Matrix ID.
- **Your session.** Your access token, user ID, device ID and homeserver are
  saved encrypted with the same Keystore key, so Send can reopen your account
  after a restart.
- **Media cache.** Media you open is cached, decrypted, in Send's private cache
  folder so it can be shown and handed to viewer apps.
- **Preferences.** Theme, sounds, link previews, recent emoji, the push
  endpoint and gateway, and which notifications were already shown.
- **No backups.** Send sets `android:allowBackup="false"`, so Android does not
  copy any of this into cloud or device-transfer backups.
- **Sign out** deletes the Matrix database, the media cache, the saved session
  and the Keystore key. Uninstalling Send deletes everything.

Your recovery key is shown to you once and is never stored by Send.

## Permissions

- **Internet**: to talk to your homeserver and the servers above.
- **Camera** (asked when needed): to scan a sign-in QR code and for video
  calls.
- **Microphone** (asked when needed): for voice messages and calls.
- **Notifications**: to show messages and incoming calls.
- **Full-screen intent**: to show an incoming call over the lock screen.

Send does not ask for contacts, location, storage or phone-state access.

## Children

Send is a general-purpose messenger and is not directed at children.

## Changes and contact

Changes to this policy are made in the open in the Send repository and ship
with the app. Questions or concerns: open an issue at
https://github.com/hanthor/send-android/issues
