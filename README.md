# SeamlessClip (SeamlessApp)

Copy on your Windows laptop, paste on your Pixel, and the other way round.
It works over your local Wi-Fi with end-to-end encryption, with no cloud and no account.

```
 ┌──────────────── Windows laptop ────────────────┐        ┌──────────── Pixel ─────────────┐
 │ Tray app (C# / .NET 8 WinForms)                │  TCP   │ Android app (Kotlin / Compose) │
 │  • clipboard listener (WM_CLIPBOARDUPDATE)     │◄──────►│  • foreground service keeps    │
 │  • TCP server :45700, AES-256-GCM per session  │ 45700  │    the link open, writes PC    │
 │  • UDP beacon :45701 (so phone finds new IP)   │───────►│    copies to the clipboard     │
 │  • pairing QR code (key stored with DPAPI)     │ 45701  │  • Share / text-select / tile  │
 └────────────────────────────────────────────────┘        │    to send phone → PC          │
                                                           └────────────────────────────────┘
```

| Direction      | How it works |
|----------------|--------------|
| **PC → phone** | Automatic. Copy anything on the PC and it lands on the phone clipboard within a second, even with the phone locked. |
| **Phone → PC** | One tap. Since Android 10, apps can't read the clipboard in the background, so after copying use any of these: **Share** in the clipboard pop-up → *Send to PC*, select text → ⋮ → *Send to PC*, the **Clipboard → PC** Quick Settings tile, or the notification's *Send clipboard to PC* button. |

The protocol is specified in [`docs/PROTOCOL.md`](docs/PROTOCOL.md).
Current status, known gaps and next steps are in [`PROGRESS.md`](PROGRESS.md).
The security review is in [`docs/SECURITY.md`](docs/SECURITY.md).

## Testing without devices
`tests/interop/run.sh` builds the Windows app's real server code and the Android app's real client
code for the desktop and runs them against each other over TCP (it needs the .NET 8 SDK, JDK 17+ and Gradle).
It also runs in CI.

## Repository layout

```
docs/PROTOCOL.md                wire protocol (both apps must match it)
windows/SeamlessClip/           Windows tray app (.NET 8, WinForms)
android/                        Android app (Gradle, Kotlin, Jetpack Compose)
.github/workflows/              CI: builds SeamlessClip.exe and the debug APK as downloadable artifacts
```

## Getting it running

### Easiest: download CI builds
Every push runs the **Windows** and **Android** GitHub Actions workflows. Open the repo's
**Actions** tab, pick the latest run, and download:
- `SeamlessClip-windows-x64` → `SeamlessClip.exe` (self-contained, no .NET install needed)
- `SeamlessClip-android-debug` → `app-debug.apk` (sideload it on the Pixel)

### Windows: build locally
Requires the .NET 8 SDK.
```powershell
dotnet run --project windows/SeamlessClip
# or a single exe:
dotnet publish windows/SeamlessClip -c Release -r win-x64 --self-contained -p:PublishSingleFile=true -o publish
```

### Android: build locally
Open the `android/` folder in Android Studio (Ladybug or newer) and press Run.
From a terminal you need Gradle 8.10+: `cd android && gradle assembleDebug`
(or run `gradle wrapper` once to generate `./gradlew`).

## First-time setup

1. Start `SeamlessClip.exe`. It sits in the system tray and opens the **pairing window** on first run.
2. If Windows Firewall asks, allow access on **Private** networks. You can also use
   tray menu → *Allow through Windows Firewall (admin)…*
3. On the Pixel, install the APK, open **SeamlessClip**, tap **Scan pairing QR**, and scan the code.
   Allow notifications when asked.
4. Optional but recommended: tap **Allow background use** in the app so battery optimisation
   doesn't drop the connection, and add the **Clipboard → PC** Quick Settings tile.

Both devices need to be on the **same Wi-Fi/LAN**, and the Windows network profile should be
*Private*. On guest or corporate networks that isolate clients, the devices can't see each other.

## Security

- Pairing uses a random 256-bit key that travels only inside the QR code. On Windows it is stored
  with DPAPI (current user). On Android it is wrapped by a non-exportable Android Keystore key.
- Each connection derives a fresh AES-256-GCM key via HKDF from the pairing key and random nonces
  from both sides, with counter nonces. This gives confidentiality, integrity and replay protection.
- On the PC, *Pair a phone… → New key…* rotates the key and immediately disconnects old phones.
- Windows clipboard entries that password managers mark private
  (`ExcludeClipboardContentFromMonitorProcessing`, `CanIncludeInClipboardHistory=0`) are never sent.
- Pairing links opened from outside the app (e.g. a web page) always require confirmation.

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| Phone stuck on "Connecting…" | Check both devices are on the same network, the PC network is *Private*, and the firewall rule exists. |
| "PC rejected this phone" | The PC's key was rotated (or settings reset). Re-scan the QR code. |
| PC copies arrive late when the phone is idle | Allow background use (battery optimisation) for SeamlessClip. |
| Logs | Windows: tray → *Open data folder* → `log.txt`. Android: `adb logcat -s SyncClient SyncService BeaconListener`. |
