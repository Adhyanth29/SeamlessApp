# Security review

_2026-09-23: manual review of v0.1 plus automated interop/abuse tests (`tests/interop/run.sh`)._

## Threat model
- **In scope:** other devices on the same Wi-Fi/LAN (including a hostile one on public or shared
  networks), malicious web pages or apps on the phone, and other processes on the PC.
- **Out of scope:** malware already running as your Windows user or with root on the phone
  (it can read the clipboard directly anyway).

## What holds up
| Area | Verdict |
|------|---------|
| Confidentiality/integrity on the wire | AES-256-GCM with a per-session key derived by HKDF from a 256-bit pairing key and fresh nonces from both sides. Tested: tampered, replayed and reflected frames are rejected (Android unit tests). |
| Authentication | Both sides prove they know the key: the PC by producing a valid `auth_ok`, the phone by producing a valid `auth`. No clip is sent before this. Tested: a wrong key is rejected, and the phone reports it. |
| Parser robustness | Pre-auth frames are capped at 4 KiB, post-auth frames at 8 MiB, and text at 4 MiB. Tested: oversized length, junk JSON and junk ciphertext don't crash the server. |
| Key storage | Windows uses DPAPI (current user). Android uses a non-exportable Android Keystore key, with `allowBackup=false`. |
| Logs | Clipboard contents are never logged, only lengths. |
| Deep-link pairing | Any web page can open `seamlessclip://pair?...`, so the app always asks for confirmation. |

## Fixed in this review
| # | Severity | Issue | Fix |
|---|----------|-------|-----|
| 1 | Medium | The Windows clipboard privacy check read custom formats with WinForms `GetData`. On .NET 8 this can BinaryFormatter-deserialize data planted by any process on the PC. | Read those formats with raw Win32 (`GetClipboardData` + `GlobalLock`). The only thing WinForms reads now is Unicode text. |
| 2 | Medium | *Copy link* in the pairing window put the **pairing key** on the clipboard, where it was saved to Windows clipboard history and possibly synced to the Microsoft cloud clipboard. | The link is copied with `ExcludeClipboardContentFromMonitorProcessing`, `CanIncludeInClipboardHistory=0` and `CanUploadToCloudClipboard=0`. The phone clears the clipboard after pairing from a pasted link. |
| 3 | Medium | The "Copied from PC" notification showed clipboard text (possibly passwords or OTPs) **on the lock screen**. | `VISIBILITY_PRIVATE` with a redacted public version. |
| 4 | Low | There was no limit on unauthenticated connections, so a LAN host could exhaust server resources. | At most 16 handshakes can be pending at once. Tested: 50 idle sockets → 34 dropped, and a real phone still connects. |
| 5 | Low | The firewall rule allowed `profile=any`, which opened the port on public Wi-Fi. | `profile=private,domain remoteip=localsubnet`. |
| 6 | Low | A phone-supplied device name went unfiltered into logs and notifications (log-line forging). | Control characters are stripped and the name is capped at 64 characters. |
| 7 | Low | A malicious pairing link could silently replace an existing pairing if the user tapped through. | The confirmation dialog warns when it would replace the current PC. |

## Automatic phone → PC (opt-in)
- `READ_LOGS` lets the app read **all** device logs, and some apps log sensitive data. SeamlessClip only
  ever runs logcat filtered to `ClipboardService:E`, never stores or forwards log lines, and the feature
  is off by default. Only grant it on your own device.
- `SYSTEM_ALERT_WINDOW` is used only for a 1×1, invisible, non-touchable window that exists for
  the few milliseconds needed to read the clipboard.
- Clips marked sensitive (`ClipDescription.EXTRA_IS_SENSITIVE`, which password managers and Gboard set)
  are never auto-sent.
- Revoke: `adb shell pm revoke app.seamlessclip android.permission.READ_LOGS`.

## Accepted / open risks
- **Any app on the phone can push text to the PC clipboard** through the exported share target
  (`ACTION_SEND` / `PROCESS_TEXT`). A malicious app could plant a command for you to paste into
  a terminal ("pastejacking"). This is inherent to having a share target. Mitigation idea: an
  optional setting to confirm shares that don't come from the system chooser or clipboard overlay.
- **One key for all phones.** Rotating the key revokes every phone. Per-device keys are on the roadmap.
- **Key-rotation race.** A handshake that started before *New key* could finish with the old key.
  The window is only milliseconds wide.
- **The beacon reveals the PC name and a random install ID** to everyone on the LAN. A spoofed
  beacon can make the phone try the wrong IP first. That attempt fails the handshake and the
  phone moves on to its other known addresses.
- **Clipboard sync is powerful by nature.** Anything you copy on either device (unless the
  source app marks it private) lands on the other one. Password managers that set the Windows
  privacy formats are respected. On Android, automatic sending skips clips marked `IS_SENSITIVE`; manual sends always go through.
