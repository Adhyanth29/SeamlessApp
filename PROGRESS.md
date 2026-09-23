# Progress / hand-off notes

_Last updated: 2026-09-23_

This file is for picking the work back up in a later session. Read it first.

## Status: v0.1 compiles, protocol verified end-to-end; not yet tried on real devices

- CI is green: the Windows single-file exe builds, and the Android APK builds and passes its unit tests.
- `tests/interop/run.sh` (also in CI) runs the **real** C# `SyncServer` against the **real** Kotlin
  `SyncClient`/`SyncHub` over TCP: a unicode round trip, wrong-key rejection, and abuse resistance. All pass.
- Security review done: see `docs/SECURITY.md` (7 issues fixed, remaining risks listed).

### Done
- [x] Protocol spec: `docs/PROTOCOL.md` (TCP + length-prefixed frames, HKDF + AES-256-GCM, UDP beacon, QR pairing)
- [x] **Windows tray app** (`windows/SeamlessClip`, .NET 8 WinForms)
  - [x] Clipboard listener (`AddClipboardFormatListener`) with 150 ms debounce + echo suppression
  - [x] Honours password-manager "don't sync" clipboard formats
  - [x] TCP server with handshake, per-session cipher, ping/pong, idle timeout, multi-phone broadcast
  - [x] UDP discovery beacon (limited + per-interface directed broadcast)
  - [x] Pairing window with QR code (QRCoder), copyable link, key rotation
  - [x] Settings in `%APPDATA%\SeamlessClip\settings.json`, key protected with DPAPI
  - [x] Tray menu: status, send now, toggles, start with Windows, firewall rule, open data folder
  - [x] Single-instance mutex, file log
- [x] **Android app** (`android/`, Kotlin, Compose, minSdk 29 / targetSdk 35)
  - [x] Foreground service (`connectedDevice` type) with reconnect/backoff, network callback, beacon listener
  - [x] Applies PC clips to the clipboard in the background, with an optional notification
  - [x] Phone → PC entry points: share sheet, text-selection `PROCESS_TEXT`, Quick Settings tile,
        notification action, in-app button
  - [x] QR pairing (zxing-android-embedded), `seamlessclip://pair` deep link with confirmation dialog,
        paste-link fallback
  - [x] Pairing key wrapped by Android Keystore
  - [x] Boot / app-update auto-start, battery-optimisation prompt, Material You UI, recent-activity list
  - [x] JVM unit tests for HKDF (RFC 5869 vector) and SessionCipher
- [x] CI: Windows single-file exe + Android debug APK as build artifacts

### Next steps (in order)
1. **Decide on automatic phone → PC sync** (see "Automatic phone → PC" below) and build it.
2. **Commit the Gradle wrapper.** Run `gradle wrapper --gradle-version 8.10.2` in `android/` and commit
   `gradlew`, `gradlew.bat` and `gradle/wrapper/gradle-wrapper.jar`, then switch CI to `./gradlew`.
3. **Manual end-to-end test** on the real Pixel and laptop: pairing, PC→phone while the phone is locked,
   every phone→PC entry point, IP change (beacon re-discovery), key rotation, firewall prompt.
4. Proper app icon (.ico for Windows, launcher icon polish on Android).

### Known gaps / ideas for later
- **Automatic phone → PC sync in the background.** Android 10+ lets only the focused app or the
  default keyboard read the clipboard. Options, best first:
  1. *ADB-assisted "copy detector"* (the KDE Connect approach): a one-time
     `adb shell pm grant app.seamlessclip android.permission.READ_LOGS` plus the "display over other
     apps" permission. The service registers a clipboard listener; Android logs
     `Denying clipboard access to app.seamlessclip` on every copy; the service sees that line in logcat
     and flashes `ClipboardSendActivity` (~100 ms, invisible) to read and send the clip. No tap is needed.
     Caveats to verify on the Pixel: Android 13+ may show a "allow access to device logs?" prompt, and
     the grant survives reboots but not a reinstall.
  2. *Shizuku* (wireless-debugging privileges, re-enabled after each reboot): read the clipboard as the
     shell user. Needs research into whether shell may read the clipboard in the background on Android 15/16.
  3. *Be the keyboard (IME)*: the default keyboard may read the clipboard freely. This is how
     SwiftKey syncs clipboard with Windows, but you'd have to use our keyboard instead of Gboard.
  4. *Accessibility service* spotting taps on "Copy", then flashing the reader activity. Heuristic, and misses Ctrl+C.
- Images and files (the `mime` field is already in the protocol). Windows: `Clipboard.GetImage`. Android: a
  `FileProvider` content URI with `ClipData.newUri`.
- A clip that fails to send mid-connection is dropped. Consider an ack plus retry.
- Only one pairing key per PC, so every phone shares it. Per-device keys would allow revoking one phone.
- Windows toast notifications (currently `NotifyIcon` balloon tips).
- Installer (MSIX or Inno Setup) and a signed release APK.
- Connecting over the internet or a hotspot when not on the same LAN (e.g. via Tailscale IPs in the QR host list).

## Design decisions (and why)
- **The phone dials the PC.** Android can't reliably accept inbound connections in the background,
  but a foreground service can hold an outbound socket.
- **Raw TCP + our own framing instead of WebSockets or HTTP.** There are no extra dependencies on
  either side, and Windows `HttpListener` would need admin URL ACLs.
- **Counter nonces with a per-session HKDF key.** There is no nonce reuse risk, replay protection
  comes for free, and nonces never go on the wire.
- **The beacon is unauthenticated.** It only hints at an IP address. A spoofed beacon just leads to
  a failed handshake.
- **Text only in v1.** This covers ~95% of copy-paste use and keeps the first version small.
