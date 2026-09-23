<#
.SYNOPSIS
  One-time setup so SeamlessClip on your phone can send copies to the PC automatically.

.DESCRIPTION
  Grants the phone app permission to read device logs (how it notices that you copied something)
  and to display over other apps (how it briefly reads the clipboard), then restarts it.
  Needs: USB debugging enabled on the phone and the phone connected to this PC by USB.
  Uses adb from PATH, the Android SDK, or a local platform-tools folder; offers to download it if missing.

  Undo at any time:  adb shell pm revoke app.seamlessclip android.permission.READ_LOGS
#>
$ErrorActionPreference = 'Stop'
$Package = 'app.seamlessclip'

function Find-Adb {
    $candidates = @(
        (Get-Command adb -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -ErrorAction SilentlyContinue),
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "$env:ANDROID_HOME\platform-tools\adb.exe",
        "$PSScriptRoot\platform-tools\adb.exe"
    )
    foreach ($c in $candidates) { if ($c -and (Test-Path $c)) { return $c } }
    return $null
}

$adb = Find-Adb
if (-not $adb) {
    Write-Host "adb (Android platform-tools) was not found." -ForegroundColor Yellow
    $answer = Read-Host "Download Google's platform-tools next to this script? [y/N]"
    if ($answer -notmatch '^[yY]') { Write-Host "Install platform-tools and re-run: https://developer.android.com/tools/releases/platform-tools"; exit 1 }
    $zip = Join-Path $env:TEMP 'platform-tools.zip'
    Invoke-WebRequest 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip
    Expand-Archive $zip -DestinationPath $PSScriptRoot -Force
    $adb = "$PSScriptRoot\platform-tools\adb.exe"
}

Write-Host "Using $adb"
& $adb start-server | Out-Null
$devices = & $adb devices | Select-String -Pattern "`tdevice$"
if ($devices.Count -eq 0) {
    Write-Host "No phone found. On the phone: Settings > About phone > tap 'Build number' 7 times," -ForegroundColor Yellow
    Write-Host "then Settings > System > Developer options > USB debugging. Connect by USB and accept the prompt." -ForegroundColor Yellow
    exit 1
}
if ($devices.Count -gt 1) { Write-Host "More than one device connected; unplug the others." -ForegroundColor Yellow; exit 1 }

if (-not (& $adb shell pm list packages $Package | Select-String "package:$Package$")) {
    Write-Host "SeamlessClip isn't installed on the phone yet. Install the APK first." -ForegroundColor Yellow
    exit 1
}

& $adb shell pm grant $Package android.permission.READ_LOGS
& $adb shell appops set $Package SYSTEM_ALERT_WINDOW allow
& $adb shell am force-stop $Package
& $adb shell monkey -p $Package -c android.intent.category.LAUNCHER 1 | Out-Null

Write-Host ""
Write-Host "Done. SeamlessClip has been reopened on the phone." -ForegroundColor Green
Write-Host "Turn on 'Send phone copies automatically' there. If Android asks 'Allow access to all device logs?', choose Allow."
Write-Host "You can now unplug the phone. USB debugging can be turned off again; the grant stays until the app is uninstalled."
