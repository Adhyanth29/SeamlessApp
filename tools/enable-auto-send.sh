#!/usr/bin/env bash
# macOS/Linux equivalent of enable-auto-send.ps1. Needs adb on PATH and USB debugging enabled.
set -euo pipefail
PKG=app.seamlessclip
command -v adb >/dev/null || { echo "adb not found: install Android platform-tools"; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "No phone connected (enable USB debugging and accept the prompt)"; exit 1; }
adb shell pm grant "$PKG" android.permission.READ_LOGS
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
adb shell am force-stop "$PKG"
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null
echo "Done. Turn on 'Send phone copies automatically' in SeamlessClip; allow log access if Android asks."
