# Progress / hand-off notes

_Last updated: 2026-09-23_

This file is for picking the work back up in a later session. Read it first.

## Status: v0.1 code-complete, **not yet compiled or run**

By request, nothing was built or executed locally in the first session. All code was written
"blind", so expect some compile errors on the first CI run. The GitHub Actions workflows in
`.github/workflows/` are the first thing to check.

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
1. **Get CI green.** Fix any compile errors in both workflows. The Android build also runs the unit tests.
2. **Commit the Gradle wrapper.** Run `gradle wrapper --gradle-version 8.10.2` in `android/` and commit
   `gradlew`, `gradlew.bat` and `gradle/wrapper/gradle-wrapper.jar`, then switch CI to `./gradlew`.
3. **Cross-platform protocol test.** Add a fixed test vector (known key and nonces → expected ciphertext)
   to both a .NET test project and the Android unit tests, so the two implementations are proven to agree.
   Also consider a small .NET console "fake phone" for testing the server without a device.
4. **Manual end-to-end test** on the real Pixel and laptop: pairing, PC→phone while the phone is locked,
   every phone→PC entry point, IP change (beacon re-discovery), key rotation, firewall prompt.
5. Proper app icon (.ico for Windows, launcher icon polish on Android).

### Known gaps / ideas for later
- **Automatic phone → PC sync in the background.** Android 10+ blocks background clipboard reads.
  Known workaround (used by apps like "Clipboard Sync"): grant `READ_LOGS` over ADB once
  (`adb shell pm grant app.seamlessclip android.permission.READ_LOGS`), watch logcat for the
  `ClipboardService` "Denying clipboard access" line, then briefly launch `ClipboardSendActivity`
  to read the clipboard. This could be an opt-in "advanced mode".
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
