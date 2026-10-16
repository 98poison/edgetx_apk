# Extract libedgetx-<name>-simulator.so from an already-built APK and verify it.
#
# The old APK already contains the patched EdgeTX library the app needs; with it,
# the new APK can be built without rebuilding EdgeTX (-Pedgetx.skipBuild=true).
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File tools\extract-sim-lib.ps1 -Apk C:\path\old.apk
#   powershell -ExecutionPolicy Bypass -File tools\extract-sim-lib.ps1 -Apk old.apk -Out .edgetx
#
# It prints every ABI in the APK, extracts the arm64-v8a library into -Out
# (default .edgetx/, i.e. the path the CI workflow looks for) and checks the ELF
# header and the host symbols the app resolves at load time.

param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [string]$Out = ".edgetx"
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression.FileSystem

if (-not (Test-Path -LiteralPath $Apk)) { throw "APK not found: $Apk" }
$Apk = (Resolve-Path -LiteralPath $Apk).Path
Write-Host "APK : $Apk ($([math]::Round((Get-Item $Apk).Length/1MB,1)) MB)"

$zip = [System.IO.Compression.ZipFile]::OpenRead($Apk)
try {
    $libs = $zip.Entries | Where-Object { $_.FullName -match '^lib/[^/]+/libedgetx-.*-simulator\.so$' }
    if (-not $libs) { throw "No libedgetx-*-simulator.so inside the APK. Is this the EdgeTX-on-Android APK?" }

    Write-Host "`nLibraries found:"
    $libs | ForEach-Object {
        Write-Host ("  {0,-12} {1,10:N0} bytes  {2}" -f ($_.FullName -split '/')[1], $_.Length, $_.FullName)
    }

    $want = $libs | Where-Object { $_.FullName -like 'lib/arm64-v8a/*' } | Select-Object -First 1
    if (-not $want) { throw "No arm64-v8a build inside the APK (the RC Plus 2 is 64-bit ARM)." }

    New-Item -ItemType Directory -Force -Path $Out | Out-Null
    $dst = Join-Path $Out "libedgetx-st16mk3-simulator.so"
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($want, $dst, $true)
    Write-Host "`nExtracted -> $dst"

    # ---- ELF header -------------------------------------------------------
    $bytes = [System.IO.File]::ReadAllBytes($dst)
    if ($bytes.Length -lt 20 -or $bytes[0] -ne 0x7F -or $bytes[1] -ne 0x45 -or $bytes[2] -ne 0x4C -or $bytes[3] -ne 0x46) {
        throw "Not an ELF file - extraction went wrong."
    }
    $class = if ($bytes[4] -eq 2) { "64-bit" } else { "32-bit" }
    $machine = $bytes[18] -bor ($bytes[19] -shl 8)
    $machineName = switch ($machine) { 0xB7 { "AArch64 (arm64-v8a)" } 0x28 { "ARM (armeabi-v7a)" } 0x3E { "x86-64" } default { "0x{0:X}" -f $machine } }
    Write-Host ("ELF : {0}, machine {1}" -f $class, $machineName)

    # ---- host symbols the app resolves at load time -----------------------
    # (C++ linkage, so the mangled names contain these plain substrings.)
    Write-Host "`nHost symbols:"
    $ascii = [System.Text.Encoding]::ASCII.GetString($bytes)
    $required = @(
        "simuSetSplashStartup", "simuTouchCancel", "simuTouchUp",
        "edgetxAndroidTakeAudio", "edgetxAndroidAudioSampleRate",
        "edgetxAndroidAudioWrittenBytes", "edgetxAndroidAudioDroppedBytes",
        "edgetxAndroidSetAnalog", "edgetxAndroidSetAnalogExternal"
    )
    $optional = @(
        "edgetxAndroidSetAuxSerialSink",   # RF module serial bridge
        "edgetxAndroidSetBattery", "edgetxAndroidSetGps", "edgetxAndroidSetSwitch",
        "edgetxAndroidExternalModuleType", "lcdRequestFullRefresh"
    )
    $missing = @()
    foreach ($s in $required) {
        $ok = $ascii.Contains($s)
        if (-not $ok) { $missing += $s }
        Write-Host ("  [{0}] {1}" -f $(if ($ok) { "ok " } else { "MISS" }), $s)
    }
    Write-Host "  -- optional --"
    foreach ($s in $optional) {
        Write-Host ("  [{0}] {1}" -f $(if ($ascii.Contains($s)) { "ok " } else { "-- " }), $s)
    }

    Write-Host ""
    if ($missing.Count -eq 0) {
        Write-Host "VERDICT: this library satisfies the app. Use it with edgetx_mode=prebuilt."
    } else {
        Write-Host ("VERDICT: MISSING required symbols ({0}). This APK predates the app; use edgetx_mode=source instead." -f ($missing -join ", "))
    }
    if (-not $ascii.Contains("edgetxAndroidSetAuxSerialSink")) {
        Write-Host "NOTE: no aux-serial bridge -> the RF module output would stay silent with this library."
    }
}
finally {
    $zip.Dispose()
}
