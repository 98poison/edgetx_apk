# One-shot functional test of the EdgeTX-on-Android app, run from this PC.
#
#   powershell -ExecutionPolicy Bypass -File tools\verify-all.ps1
#       [-Radio 192.168.1.22] [-Seconds 8] [-InjectSeconds 8] [-RateHz 100]
#       [-NoInject] [-Restart] [-Settle 10]
#
# Steps
#   1  install            adb reachable, package present, version
#   2  app alive          process, foreground activity
#   3  DJI MSDK           registration + sticks reaching the firmware
#   4  input readers      Shizuku (shoulder buttons), raw USB HID, MSDK vs raw agreement
#   5  RF module          port open at 400000 baud, TX flowing, no new drops
#   6  network OUT        JSON schema, CRSF frames + crc8, both rates
#   7  inbound injection  rcbridge packets -> appear in the forwarded streams
#   8  release            forwarded sticks return to the real pose
#
# -Restart clears logcat and restarts the app first, so steps 3/4 read evidence
# from this run instead of whatever happens to be left in the buffer.
#
# Read-only except step 7, which sends rcbridge-format packets to the radio's
# inbound port; tools\rcb1-send.ps1 sends exactly the same packets. No model,
# setting or file on the radio is modified.

param(
    [string]$Radio = "192.168.1.22",
    [string]$Adb = "$env:USERPROFILE\Desktop\platform-tools\adb.exe",
    [int]$JsonPort = 14560,
    [int]$CrsfPort = 14561,
    [int]$BridgePort = 14571,
    [int]$Seconds = 8,
    [int]$InjectSeconds = 8,
    [int]$RateHz = 100,
    [int]$Settle = 10,
    [switch]$NoInject,
    [switch]$Restart
)

$ErrorActionPreference = "Continue"
$script:pass = 0
$script:fail = 0
$script:warn = 0

function Head([string]$t) { Write-Host ""; Write-Host ("===== " + $t + " =====") -ForegroundColor Cyan }
function Ok([string]$name, [bool]$good, [string]$detail = "") {
    if ($good) { $script:pass++ } else { $script:fail++ }
    $tag = if ($good) { "[PASS]" } else { "[FAIL]" }
    $col = if ($good) { "Green" } else { "Red" }
    if ($detail -eq "") { Write-Host ("  " + $tag + " " + $name) -ForegroundColor $col }
    else { Write-Host ("  " + $tag + " " + $name + "   " + $detail) -ForegroundColor $col }
}
function Warn([string]$name, [string]$detail = "") {
    $script:warn++
    if ($detail -eq "") { Write-Host ("  [WARN] " + $name) -ForegroundColor Yellow }
    else { Write-Host ("  [WARN] " + $name + "   " + $detail) -ForegroundColor Yellow }
}
function Info([string]$t) { Write-Host ("         " + $t) -ForegroundColor DarkGray }
function Sh([string]$cmd) { return ((& $Adb shell $cmd 2>&1) -join "`n") }
function Logcat { return ((& $Adb logcat -d -v brief -s EdgeTXUI:V 2>&1) -join "`n") }
function Has([string]$text, [string]$needle) { return ($text.IndexOf($needle, [StringComparison]::Ordinal) -ge 0) }
function LastMatch([string]$text, [string]$pattern) {
    $m = @(($text -split "`n") | Where-Object { $_ -match $pattern })
    if ($m.Count -gt 0) { return $m[$m.Count - 1].Trim() } else { return "" }
}
# the text after a marker - logcat lines start with a pid, so never scan from column 0
function After([string]$line, [string]$marker) {
    $i = $line.IndexOf($marker, [StringComparison]::Ordinal)
    if ($i -lt 0) { return "" }
    return $line.Substring($i + $marker.Length)
}
function Num([string]$text, [int]$n) {
    $m = [regex]::Matches($text, '-?\d+')
    if ($m.Count -gt $n) { return [int]$m[$n].Value }
    return -99999
}
function JInt([object]$v, [int]$fallback) {
    try { return [int]$v } catch { return $fallback }
}

# ---------------------------------------------------------------- CRSF helpers ---
# What the firmware actually writes out:  ee 19 16 <22 ch bytes> <1 byte> <crc8>
#   27 bytes, len byte = 25 (= 1 type + 23 payload + 1 crc), crc8/DVB-S2 over
#   bytes 2..25 covers everything except address, length and crc itself.
function Crc8([byte[]]$b, [int]$from, [int]$len) {
    $crc = 0
    for ($i = $from; $i -lt ($from + $len); $i++) {
        $crc = $crc -bxor $b[$i]
        for ($k = 0; $k -lt 8; $k++) {
            if ($crc -band 0x80) { $crc = (($crc -shl 1) -bxor 0xD5) -band 0xFF }
            else { $crc = ($crc -shl 1) -band 0xFF }
        }
    }
    return $crc
}
function IsCrsf16([byte[]]$f, [int]$at) {
    if (($at + 27) -gt $f.Length) { return $false }
    if ($f[$at] -ne 0xEE -or $f[$at + 1] -ne 0x19 -or $f[$at + 2] -ne 0x16) { return $false }
    if ((Crc8 $f ($at + 2) 24) -ne $f[$at + 26]) { return $false }
    return $true
}
function DecodeCrsf16([byte[]]$f, [int]$at) {
    $chans = @()
    $acc = 0; $bits = 0
    for ($i = ($at + 3); $i -lt ($at + 25); $i++) {
        $acc = $acc -bor ($f[$i] -shl $bits); $bits += 8
        while ($bits -ge 11) {
            $chans += (988 + [int](($acc -band 0x7FF) * 5 / 8))
            $acc = $acc -shr 11; $bits -= 11
        }
    }
    return $chans
}

# rcbridge's 48-byte packet (see app\src\main\cpp\rc_bridge.cpp, tools\rcb1-send.ps1)
function NewRcbPacket([int]$lh, [int]$lv, [int]$rh, [int]$rv, [int]$d0, [int]$d1,
                      [int]$five, [int]$fmode, [uint64]$buttons, [uint32]$seq, [uint32]$ms) {
    $b = New-Object byte[] 48
    [BitConverter]::GetBytes([uint32]0x31424352).CopyTo($b, 0)
    [BitConverter]::GetBytes([uint32]$seq).CopyTo($b, 4)
    [BitConverter]::GetBytes([uint32]$ms).CopyTo($b, 8)
    $b[12] = 2
    $b[13] = 3
    [BitConverter]::GetBytes([int16]$lh).CopyTo($b, 16)
    [BitConverter]::GetBytes([int16]$lv).CopyTo($b, 18)
    [BitConverter]::GetBytes([int16]$rh).CopyTo($b, 20)
    [BitConverter]::GetBytes([int16]$rv).CopyTo($b, 22)
    [BitConverter]::GetBytes([int16]$d0).CopyTo($b, 24)
    [BitConverter]::GetBytes([int16]$d1).CopyTo($b, 26)
    [BitConverter]::GetBytes([int16]0).CopyTo($b, 28)
    $b[30] = [byte]$five
    $b[31] = [byte]($fmode -band 0xFF)
    [BitConverter]::GetBytes([uint64]$buttons).CopyTo($b, 32)
    [BitConverter]::GetBytes([uint32]0).CopyTo($b, 40)
    [BitConverter]::GetBytes([uint32]0).CopyTo($b, 44)
    return , $b
}

# ------------------------------------------------------------------- capture ---
function Capture([int]$secs, [bool]$inject, [string]$pose = "hold") {
    $res = @{
        json = 0; jsonBad = 0; events = 0; crsf = 0; crsfBad = 0
        injected = 0; samples = (New-Object System.Collections.ArrayList)
        keys = (New-Object System.Collections.ArrayList)
        chanMin = (@(0) * 16); chanMax = (@(0) * 16); elapsed = 1
    }
    for ($i = 0; $i -lt 16; $i++) { $res.chanMin[$i] = 99999; $res.chanMax[$i] = -99999 }

    $js = New-Object System.Net.Sockets.UdpClient($JsonPort)
    $js.Client.Blocking = $false
    $cs = New-Object System.Net.Sockets.UdpClient($CrsfPort)
    $cs.Client.Blocking = $false
    $inj = $null; $target = $null
    if ($inject) {
        $inj = New-Object System.Net.Sockets.UdpClient
        $target = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Parse($Radio), $BridgePort)
    }

    $sw = [Diagnostics.Stopwatch]::StartNew()
    $nextInject = 0.0
    $seq = 0
    while ($sw.Elapsed.TotalSeconds -lt $secs) {
        if ($inject -and $sw.Elapsed.TotalMilliseconds -ge $nextInject) {
            if ($pose -eq "neutral") {
                # all centred/off - used at the end so the radio is not left holding a fake pose
                $pkt = NewRcbPacket 0 0 0 0 0 0 0 0 ([uint64]0) ([uint32]$seq) ([uint32]$sw.ElapsedMilliseconds)
            } else {
                # hold pose: LH 800 LV -800 RH 400 RV 400, dials -600/+600, SB up, L1+L2 held
                $pkt = NewRcbPacket 800 -800 400 400 -600 600 0 -1 ([uint64]0x300) ([uint32]$seq) ([uint32]$sw.ElapsedMilliseconds)
            }
            try { [void]$inj.Send($pkt, 48, $target); $res.injected++ } catch { }
            $seq++
            $nextInject = $sw.Elapsed.TotalMilliseconds + (1000.0 / $RateHz)
        }

        $got = $false
        for ($i = 0; $i -lt 400; $i++) {
            $from = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, 0)
            try { $d = $js.Receive([ref]$from) } catch { break }
            if ($d -eq $null) { break }
            $got = $true
            $res.json++
            $text = [Text.Encoding]::UTF8.GetString($d)
            foreach ($line in ($text -split "`n")) {
                $line = $line.Trim()
                if ($line -eq "") { continue }
                try { $j = $line | ConvertFrom-Json } catch { $res.jsonBad++; continue }
                $names = $j.PSObject.Properties.Name
                if ($names -contains "ev") {
                    $res.events++
                    if ($j.ev -eq "switch" -or $j.ev -eq "key" -or $j.ev -eq "rotary") { [void]$res.keys.Add($line) }
                    continue
                }
                if (-not ($names -contains "t") -or -not ($names -contains "st") -or
                    -not ($names -contains "sw") -or -not ($names -contains "k") -or
                    -not ($names -contains "rot")) { $res.jsonBad++; continue }
                $st = @($j.st); $sw2 = @($j.sw)
                if ($st.Count -ne 8 -or $sw2.Count -ne 10) { $res.jsonBad++; continue }
                $bad = $false
                foreach ($v in $st) { $iv = JInt $v -32768; if ($iv -lt 0 -or $iv -gt 4096) { $bad = $true } }
                foreach ($v in $sw2) { $iv = JInt $v -32768; if ($iv -lt -1 -or $iv -gt 3) { $bad = $true } }
                if ($bad) { $res.jsonBad++; continue }
                [void]$res.samples.Add($j)
            }
        }
        for ($i = 0; $i -lt 400; $i++) {
            $from = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, 0)
            try { $d = $cs.Receive([ref]$from) } catch { break }
            if ($d -eq $null) { break }
            $got = $true
            $at = 0
            while (($at + 27) -le $d.Length) {
                $res.crsf++
                if (-not (IsCrsf16 $d $at)) { $res.crsfBad++ } else {
                    $ch = DecodeCrsf16 $d $at
                    for ($c = 0; $c -lt 16 -and $c -lt $ch.Count; $c++) {
                        if ($ch[$c] -lt $res.chanMin[$c]) { $res.chanMin[$c] = $ch[$c] }
                        if ($ch[$c] -gt $res.chanMax[$c]) { $res.chanMax[$c] = $ch[$c] }
                    }
                }
                $at += 27
            }
            if ($d.Length -eq 0) { $res.crsfBad++ }
        }
        if (-not $got) { Start-Sleep -Milliseconds 3 }
    }
    $sw.Stop()
    $js.Close(); $cs.Close()
    if ($inj -ne $null) { $inj.Close() }
    $res.elapsed = [math]::Max(1, $sw.Elapsed.TotalSeconds)
    return $res
}
function Rate([int]$count, [double]$secs) { if ($secs -le 0) { return 0 } else { return [math]::Round($count / $secs, 1) } }
function Moved([hashtable]$c) {
    $n = 0
    for ($i = 0; $i -lt 16; $i++) { if (($c.chanMax[$i] - $c.chanMin[$i]) -gt 20) { $n++ } }
    return $n
}

# ============================================================================ run ===
Write-Host ""
Write-Host "EdgeTX on Android - full functional test" -ForegroundColor White
Write-Host ("radio " + $Radio + "  json=" + $JsonPort + " crsf=" + $CrsfPort + " bridge=" + $BridgePort +
             "  capture " + $Seconds + "s" + $(if ($NoInject) { "" } else { " + inject " + $InjectSeconds + "s" }) +
             $(if ($Restart) { "  [restart first]" } else { "" })) -ForegroundColor White

if ($Restart) {
    Head "0  restart (fresh logcat, clean startup evidence)"
    & $Adb logcat -c 2>&1 | Out-Null
    Sh "am force-stop com.edgetx.droidui" | Out-Null
    Start-Sleep -Seconds 2
    Sh "am start -n com.edgetx.droidui/.EdgeTxActivity" | Out-Null
    $fresh = $false
    for ($i = 0; $i -lt 12; $i++) {
        Start-Sleep -Seconds 5
        $l = Logcat
        if (Has $l "dji: registered") { $fresh = $true; break }
    }
    Ok "the app came back up by itself" ($fresh -and ((Sh "pidof com.edgetx.droidui").Trim() -ne "")) $(if ($fresh) { "MSDK registered again" } else { "waited 60 s" })
}

# ------------------------------------------------------- 1. the install itself ---
Head "1  install"
$state = ((& $Adb get-state 2>&1) -join " ").Trim()
Ok "adb sees the radio" ($state -eq "device") ("get-state=" + $state)
$pkgs = Sh "pm list packages | grep edgetx"
Ok "com.edgetx.droidui installed" (Has $pkgs "package:com.edgetx.droidui")
Info (LastMatch (Sh "dumpsys package com.edgetx.droidui | grep versionName") "versionName")
if (Has $pkgs "package:com.edgetx.droidui.bridge") {
    Info "com.edgetx.droidui.bridge is also installed (a separate app, not this one)"
}

# ------------------------------------------------------------ 2. app is alive ---
Head "2  app process / foreground"
$appPid = (Sh "pidof com.edgetx.droidui").Trim()
Ok "app process running" ($appPid -ne "") ("pid=" + $appPid)
$resumed = (Sh "dumpsys activity activities | grep mResumedActivity").Trim()
$inFront = Has $resumed "com.edgetx.droidui/"
Ok "app is the foreground activity" $inFront
if (-not $inFront) { Warn "keep it in front for the RF module: adb shell am start -n com.edgetx.droidui/.EdgeTxActivity" }

$log = Logcat

# ---------------------------------------------------------------- 3. DJI MSDK ---
Head "3  DJI MSDK (the remote's own sticks/keys)"
Ok "SDK initialised + app registered" ((Has $log "dji: register success") -and (Has $log "dji: sdk initialised"))
Info (LastMatch $log "dji: registered, product category")
Ok "sticks reported to the firmware" (Has $log "dji: sticks reporting")
$stick = LastMatch $log "dji: sticks"
if ($stick -ne "") { Info $stick }

# --------------------------------------------------- 4. input readers (Shizuku) ---
Head "4  input readers (Shizuku shoulder buttons + raw USB HID)"
$shizukuProc = Sh "ps -A | grep -i shizuku"
Ok "Shizuku server running on the radio" ($shizukuProc.Trim() -ne "")
$l = Logcat
if (Has $l "shizuku: no server") {
    Warn "the app could not reach Shizuku in its last start" "shoulder buttons then come from Android only - see the hand-over"
} else {
    Ok "Shizuku picked up by the app" ((Has $l "shizuku: server appeared") -and (Has $l "reading the shoulder buttons from /dev/input/event3"))
}
Info (LastMatch $l "shizuku: server version")
Ok "raw USB HID joystick claimed" (Has $l "claimed /dev/bus/usb")
$raw = LastMatch $l "raw joystick: sdk"
if ($raw -ne "") {
    Info $raw
    $sdkPart = After $raw "sdk"
    $rawPart = After $raw "| raw"
    $sdkLh = Num $sdkPart 0
    $rawLh = Num $rawPart 0
    Ok "MSDK reader and raw HID reader agree (left stick)" ([math]::Abs($sdkLh - $rawLh) -le 8) ("sdk=" + $sdkLh + " raw=" + $rawLh)
} else { Warn "no 'raw joystick: sdk' line in the logcat buffer" "use -Restart for fresh evidence" }
$swl = LastMatch $l "switches: firmware sees"
if ($swl -ne "") { Info $swl }

# The firmware only runs while the app's window is attached - and coming back to the
# front does not always restart it (seen on the radio: 'firmware stopped ... port closed,
# window attached' with 0 module frames/s). Make sure it is really up before checking it.
if (-not (Has (Logcat) "module: tx")) {
    Warn "the firmware/module is not up" "restarting the app - being in front is not always enough"
    Sh "am force-stop com.edgetx.droidui" | Out-Null
    Start-Sleep -Seconds 2
    Sh "am start -n com.edgetx.droidui/.EdgeTxActivity" | Out-Null
    for ($i = 0; $i -lt 10 -and -not (Has (Logcat) "module: tx"); $i++) { Start-Sleep -Seconds 4 }
    $log = Logcat
    Ok "the module came up after restarting the app" (Has $log "module: tx")
}

# --------------------------------------------------------------- 5. RF module ---
Head "5  RF module (UART to the module bay)"
$mod1 = LastMatch $log "module: tx"
if ($mod1 -eq "") {
    Ok "module port open at 400000 baud" $false "no 'module: tx' yet - app in front? screen on?"
} else {
    $body1 = After $mod1 "module: tx"
    $tx1 = Num $body1 0
    $drop1 = Num $body1 2
    Ok "module port open at 400000 baud" ((Has $mod1 "port open") -and (Has $mod1 "400000"))
    Info $mod1
    Info ("settling " + $Settle + " s before the second sample ...")
    Start-Sleep -Seconds $Settle
    $log2 = Logcat
    $mod2 = LastMatch $log2 "module: tx"
    if ($mod2 -eq "") {
        Ok "module keeps transmitting" $false "the app stopped logging module counters"
    } else {
        $body2 = After $mod2 "module: tx"
        $tx2 = Num $body2 0
        $drop2 = Num $body2 2
        Ok "module keeps transmitting" ($tx2 -gt $tx1) ("tx " + $tx1 + " -> " + $tx2 + " B")
        Ok "no new module drops in the settling window" ($drop2 -le $drop1) ("dropped " + $drop1 + " -> " + $drop2 + " B")
    }
}

# ------------------------------------------------- 6. network forwarding (out) ---
Head ("6  network forwarding OUT -> " + $Radio + "   (json " + $JsonPort + " / crsf " + $CrsfPort + ")")
Info "listening ..."
$c1 = Capture $Seconds $false
$jsonRate = Rate $c1.json $c1.elapsed
$crsfRate = Rate $c1.crsf $c1.elapsed
Ok "control stream (JSON) arriving" ($jsonRate -ge 20) ([string]$jsonRate + "/s, " + $c1.events + " event(s)")
Ok "every JSON datagram well formed (t, st[8], sw[10], k, rot, ranges)" ($c1.samples.Count -gt 0 -and $c1.jsonBad -eq 0) ($c1.jsonBad.ToString() + " malformed of " + $c1.json.ToString())
if ($c1.samples.Count -gt 0) {
    $s = $c1.samples[$c1.samples.Count - 1]
    Info ("last: LH=" + $s.st[0] + " LV=" + $s.st[1] + " RV=" + $s.st[2] + " RH=" + $s.st[3] +
          " P1=" + $s.st[4] + " P2=" + $s.st[5] + " SL1=" + $s.st[6] + " SL2=" + $s.st[7] +
          " sw=[" + (@($s.sw) -join ",") + "] keys=0x" + ("{0:x}" -f (JInt $s.k 0)) + " rot=" + $s.rot)
}
Ok "module frame stream (CRSF) arriving" ($crsfRate -ge 50) ([string]$crsfRate + "/s")
Ok "every module frame valid (ee 19 16 + 16 channels + crc8)" ($c1.crsf -gt 0 -and $c1.crsfBad -eq 0) ($c1.crsf.ToString() + " frames, " + $c1.crsfBad.ToString() + " malformed")
$chans = @(); for ($i = 0; $i -lt 16; $i++) { $chans += $c1.chanMin[$i] }
Info ("channels (us): " + ($chans -join " "))
Info ("channels that moved in this capture: " + (Moved $c1) + " / 16")

# --------------------------------------------------------- 7. inbound injection ---
$c2 = $null
if (-not $NoInject) {
    Head "7  inbound injection (rcbridge packets -> the forwarded streams)"
    $before = LastMatch $log2 "bridge: rcbridge port"
    if ($before -ne "") { Info $before }
    $accBefore = Num (After $before "rcbridge port") 2
    Info ("injecting the hold pose at " + $RateHz + " Hz for " + $InjectSeconds + " s ...")
    $c2 = Capture $InjectSeconds $true
    Ok "packets sent to the radio's inbound port" ($c2.injected -gt 0) ($c2.injected.ToString() + " sent")

    $after = LastMatch (Logcat) "bridge: rcbridge port"
    $accAfter = Num (After $after "rcbridge port") 2
    $rejAfter = Num (After $after "rcbridge port") 3
    for ($try = 0; $try -lt 4 -and $accAfter -le $accBefore; $try++) {
        Start-Sleep -Seconds 4
        $after = LastMatch (Logcat) "bridge: rcbridge port"
        $accAfter = Num (After $after "rcbridge port") 2
        $rejAfter = Num (After $after "rcbridge port") 3
    }
    if ($after -ne "") { Info $after }
    Ok "the app accepted them, none rejected" ($accAfter -gt $accBefore -and $rejAfter -eq 0) ("accepted " + $accBefore + " -> " + $accAfter + ", rejected " + $rejAfter)

    # the hold pose as it must appear in the forwarded JSON:
    #   LH 2048+800*2.048 = 3686   LV 2048-800*2.048 = 410
    #   RV/RH 2048+400*2.048 = 2867   P1 2048-600*2.048 = 819   P2 2048+600*2.048 = 3276
    $match = 0; $keyHits = 0; $sbHits = 0
    foreach ($s in $c2.samples) {
        $lh = JInt $s.st[0] -9999
        $lv = JInt $s.st[1] -9999
        $rv = JInt $s.st[2] -9999
        $rh = JInt $s.st[3] -9999
        $p1 = JInt $s.st[4] -9999
        $p2 = JInt $s.st[5] -9999
        $ok = ([math]::Abs($lh - 3686) -le 6) -and ([math]::Abs($lv - 410) -le 6) -and
              ([math]::Abs($rv - 2867) -le 6) -and ([math]::Abs($rh - 2867) -le 6) -and
              ([math]::Abs($p1 - 819) -le 6) -and ([math]::Abs($p2 - 3276) -le 6)
        if ($ok) { $match++ }
        if ((JInt $s.k -1) -eq 768) { $keyHits++ }
        if ((JInt (@($s.sw)[1]) -9) -eq -1) { $sbHits++ }
    }
    $pct = 0
    if ($c2.samples.Count -gt 0) { $pct = [math]::Round(100.0 * $match / $c2.samples.Count, 1) }
    Ok "injected sticks and dials show up in the forwarded JSON" ($pct -ge 20) ($match.ToString() + " of " + $c2.samples.Count + " snapshots = " + $pct + "% match the injected pose")
    Info ("keys 0x300 seen in " + $keyHits + " snapshots, SB=up in " + $sbHits + " (switch positions also arrive as events below)")
    foreach ($e in (@($c2.keys) | Select-Object -First 4)) { Info ("event: " + $e) }

    $cmoved = Moved $c2
    if ($cmoved -gt 0) {
        Ok "module frames (CRSF) follow the sticks" $true ($cmoved.ToString() + " channels moved")
    } else {
        Warn "module frames (CRSF) do not follow the sticks" "the model has no mixer lines yet - see the mixing part of the hand-over"
    }

    Head "8  restore neutral + release check"
    Info "EdgeTX keeps the last received value when the injector stops, so put it back to centre"
    $c4 = Capture 3 $true "neutral"
    Start-Sleep -Seconds 2
    $c5 = Capture 4 $false
    $centre = 0
    foreach ($s in $c5.samples) { if ([math]::Abs((JInt $s.st[0] 0) - 2048) -lt 200) { $centre++ } }
    $pct5 = 0
    if ($c5.samples.Count -gt 0) { $pct5 = [math]::Round(100.0 * $centre / $c5.samples.Count, 1) }
    Ok "sticks are neutral again after the restore" ($pct5 -ge 50) ($centre.ToString() + " of " + $c5.samples.Count + " = " + $pct5 + "% near 2048")
}

Write-Host ""
Write-Host ("===== " + $script:pass + " passed, " + $script:fail + " failed, " + $script:warn + " warning(s) =====") -ForegroundColor $(if ($script:fail -eq 0) { "Green" } else { "Red" })
Write-Host ""
