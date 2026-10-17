# Install + grant overlay appop + write netlink.conf + start + self-check
param(
  [string]$ApkZip = "$env:USERPROFILE\Desktop\edgetx-apk.zip",
  [string]$DestHost = "192.168.1.21"
)
$ErrorActionPreference = "Continue"
$adb = "$env:USERPROFILE\Desktop\platform-tools\adb.exe"
$work = "$env:USERPROFILE\Desktop\edgetx-apk\run11-final"

"=== EXTRACT ==="
Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
Expand-Archive -Path $ApkZip -DestinationPath $work -Force
$apk = (Get-ChildItem $work -Recurse -Filter app-release.apk | Select-Object -First 1).FullName
if (-not $apk) { "FAIL: no app-release.apk after extract"; exit 1 }
"  APK: {0:N1} MB" -f ((Get-Item $apk).Length / 1MB)

"=== INSTALL ==="
& $adb install -r "$apk" 2>&1 | ForEach-Object { "  " + $_.ToString().Trim() }

"=== WHICH PACKAGE GOT UPDATED ==="
foreach ($p in @('com.edgetx.droidui')) {
  $v = ((& $adb shell "dumpsys package $p | grep -E 'versionCode|lastUpdateTime|codePath'" 2>&1) -join ' ').Trim()
  "  {0,-28} {1}" -f $p, $v
}

"=== OVERLAY APPOP + netlink.conf ==="
$conf = "enabled=true`nhost=$DestHost`nport=14560`ncrsf_port=14561`nmode=both`nrate=100`n"
foreach ($p in @('com.edgetx.droidui')) {
  & $adb shell "appops set $p SYSTEM_ALERT_WINDOW allow" 2>&1 | Out-Null
  "  {0,-28} appops: {1}" -f $p, (((& $adb shell "appops get $p SYSTEM_ALERT_WINDOW" 2>&1) -join ' ').Trim())
  $conf | & $adb shell "mkdir -p /sdcard/Android/data/$p/files; cat > /sdcard/Android/data/$p/files/netlink.conf" 2>&1 | Out-Null
  "  {0,-28} conf:   {1}" -f $p, (((& $adb shell "cat /sdcard/Android/data/$p/files/netlink.conf" 2>&1) -join ' ').Trim())
}

"=== START APP ==="
# This build now installs as com.edgetx.droidui itself (see app/build.gradle.kts);
# the ".bridge" suffix is used by a different test app. The app is stopped before
# starting so the log cat below only shows the fresh run.
& $adb shell am force-stop com.edgetx.droidui 2>&1 | Out-Null
Start-Sleep -Seconds 1
& $adb logcat -c 2>&1 | Out-Null
& $adb shell am start -n com.edgetx.droidui/com.edgetx.droidui.EdgeTxActivity 2>&1 | ForEach-Object { "  " + $_.ToString().Trim() }
Start-Sleep -Seconds 15

"=== ANY DIALOG IN FRONT? ==="
$act = ((& $adb shell "dumpsys activity activities | grep -iE 'usb|permission'" 2>&1) -join "|")
($act -split '\|' | Select-Object -First 6) | ForEach-Object { "  " + $_.Trim() }

"=== LOGCAT (app / dji / shizuku / gear / net / module / joystick) ==="
& $adb logcat -d -v brief 2>&1 |
  Select-String -Pattern 'EdgeTXUI|dji|DJI|MSDK|shizuku|gear|net:|module:|joystick|rcbridge|ANR|FATAL' -CaseSensitive:$false |
  Select-Object -Last 70 | ForEach-Object { "  " + $_.Line.Trim() }

"=== NETDUMP 12s (do the channels move with the sticks?) ==="
& powershell -ExecutionPolicy Bypass -File "$PSScriptRoot\netdump.ps1" -Seconds 12 2>&1 |
  Where-Object { $_ -notmatch 'channels \(us\)' } | Select-Object -Last 8 | ForEach-Object { "  " + $_ }
