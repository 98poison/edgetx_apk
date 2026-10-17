# Put the freshly built APK on the radio, point the network output at this PC
# and start it - the whole of sections 3 and 4.2 of 使用步骤.md in one go.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File tools\setup-radio.ps1
#   ... -Apk C:\path\app-release.apk      (if it is somewhere else)
#   ... -HostIP 192.168.1.50              (if the auto-detected address is wrong)
#   ... -SkipInstall                      (only rewrite/upload netlink.conf)
#
# It is safe to re-run: it reinstalls the APK, rewrites netlink.conf and
# restarts the app.
#
# After it finishes, start the receiver on this PC (see the note it prints):
#   python tools\netdump.py

param(
    [string]$Apk  = "$env:USERPROFILE\Desktop\edgetx-apk\app-release.apk",
    [string]$Adb  = "$env:USERPROFILE\Desktop\platform-tools\adb.exe",
    [string]$HostIP = "",
    [int]$Port      = 14560,
    [int]$CrsfPort  = 14561,
    [int]$Rate      = 50,
    [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"
$pkg = "com.edgetx.droidui"
$confName = "netlink.conf"
$devConf = "/sdcard/Android/data/$pkg/files/$confName"

function Step($text) { Write-Host ""; Write-Host "== $text" -ForegroundColor Cyan }

# ---- adb ------------------------------------------------------------------
if (-not (Test-Path $Adb)) {
    $found = Get-Command adb -ErrorAction SilentlyContinue
    if ($found) { $Adb = $found.Source } else { throw "adb not found: $Adb (pass -Adb <path>)" }
}
Write-Host "adb : $Adb"

Step "looking for the radio"
$devices = & $Adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\S' }
$devices | ForEach-Object { Write-Host "  $_" }
$ready = @($devices | Where-Object { $_ -match '\sdevice$' })
if ($ready.Count -eq 0) {
    throw @"
no authorised device. Check, on the radio:
  * USB debugging is on (设置 -> 开发者选项 -> USB 调试)
  * the "allow USB debugging?" dialog is accepted
  * the data cable is a data cable (not charge-only)
If it says "unauthorized", unlock the radio and accept the dialog, then re-run.
"@
}
if ($ready.Count -gt 1) { throw "more than one device attached; unplug the others" }

# ---- the APK --------------------------------------------------------------
if (-not $SkipInstall) {
    if (-not (Test-Path $Apk)) {
        throw "APK not found: $Apk`nUnzip the artifact first:  Expand-Archive `"$env:USERPROFILE\Desktop\edgetx-apk.zip`" -DestinationPath `"$env:USERPROFILE\Desktop\edgetx-apk`" -Force"
    }
    Write-Host "apk : $Apk ($([math]::Round((Get-Item $Apk).Length/1MB,1)) MB)"

    Step "installing"
    & $Adb install -r $Apk
    if ($LASTEXITCODE -ne 0) {
        Write-Host "retrying after uninstalling the old build (different signature?)" -ForegroundColor Yellow
        & $Adb uninstall $pkg | Out-Null
        & $Adb install -r $Apk
        if ($LASTEXITCODE -ne 0) { throw "adb install failed" }
    }
}

# ---- this PC's LAN address ------------------------------------------------
if (-not $HostIP) {
    Step "finding this PC's LAN address"
    $candidates = Get-NetIPAddress -AddressFamily IPv4 |
        Where-Object { $_.IPAddress -notmatch '^(127\.|169\.254\.)' -and $_.PrefixOrigin -ne 'WellKnown' } |
        Sort-Object @{ Expression = { if ($_.InterfaceAlias -match 'Wi-?Fi|WLAN|Wireless') { 0 } else { 1 } } }, InterfaceIndex
    if (-not $candidates) { throw "no LAN address found; pass -HostIP explicitly" }
    $candidates | ForEach-Object { Write-Host ("  {0,-16} {1}" -f $_.IPAddress, $_.InterfaceAlias) }
    $HostIP = ($candidates | Select-Object -First 1).IPAddress
    Write-Host "using: $HostIP   (wrong one? re-run with -HostIP <address>)" -ForegroundColor Yellow
}

# ---- netlink.conf ---------------------------------------------------------
Step "writing $confName"
$confLines = @(
    "# the radio's raw controls + module bytes go to $HostIP",
    "# see NETLINK.md for every key",
    "enabled   = true",
    "host      = $HostIP",
    "port      = $Port",
    "crsf_port = $CrsfPort",
    "mode      = both",
    "rate      = $Rate"
)
$confPath = Join-Path $env:USERPROFILE "Desktop\$confName"
[IO.File]::WriteAllText($confPath, ($confLines -join "`n"))
Get-Content $confPath | ForEach-Object { Write-Host "  $_" }

Step "uploading it to the radio"
& $Adb shell mkdir -p "/sdcard/Android/data/$pkg/files"
& $Adb push $confPath $devConf
& $Adb shell "cat $devConf"

Step "restarting the app"
& $Adb shell am force-stop $pkg
& $Adb shell pm grant $pkg android.permission.READ_LOGS 2>$null
& $Adb shell am start -n "$pkg/.EdgeTxActivity"

Step "done - now do these on this PC"
Write-Host @"
1. let UDP through the firewall (once, in an ADMIN PowerShell):
     New-NetFirewallRule -DisplayName "netdump UDP $Port-$CrsfPort" -Direction Inbound -Action Allow -Protocol UDP -LocalPort $Port,$CrsfPort -Profile Any

2. install Python once if you have not (then reopen PowerShell):
     winget install -e --id Python.Python.3.12

3. receive what the radio sends:
     cd "$PSScriptRoot\.."
     python tools\netdump.py

   you should see one snapshot line every $([math]::Round(1000.0/$Rate)) ms, and
   "event:" lines when you flip a switch or press a button.

4. watch the app's own log (a second PowerShell):
     & "$Adb" logcat -s EdgeTXUI EdgeTXNet
"@ -ForegroundColor Gray
