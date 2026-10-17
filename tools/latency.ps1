# Latency measurement for the RF-module path and the network path.
#
#   powershell -ExecutionPolicy Bypass -File tools\latency.ps1                 # cadence only
#   powershell -ExecutionPolicy Bypass -File tools\latency.ps1 -WatchSticks    # + stick input rate
#   powershell -ExecutionPolicy Bypass -File tools\latency.ps1 -Inject         # + RCB1 round trip
#   powershell -ExecutionPolicy Bypass -File tools\latency.ps1 -Seconds 20
#
# What each part measures:
#
#   MODULE   the firmware's own module frames on 14561 (CRSF 27 B, ~195/s). The
#            interval between frames is what the RF module is fed with, so its
#            mean is the worst-case wait for a value that is ready to go out, and
#            the max shows the hiccups.
#   NETWORK  the JSON snapshots on 14560 (~98/s): the same control state, 10 ms apart.
#   STICKS   how often each analog input really changes value, read out of the JSON
#            stream while the sticks are moved. This is the DJI SDK's own report rate
#            and the single biggest term in how direct the radio feels.
#   INJECT   one RCB1 packet with the flight-mode switch flipped (SB) is sent to the
#            radio's inbound bridge and the tool reports how long it takes to appear
#            in the JSON event stream and in the module frames. Needs
#            rcbridge.conf enabled=true and a mix that uses SB as a source for the
#            module frames to move at all (without one the frame column says so).
#
# Nothing here needs the radio's screen: the only radio-side prerequisite for
# -Inject is the inbound bridge.

param(
    [int]$JsonPort = 14560,
    [int]$CrsfPort = 14561,
    [string]$Radio = "192.168.1.22",
    [int]$RcbPort = 14571,
    [int]$Seconds = 15,
    [switch]$WatchSticks,
    [switch]$Inject,
    [switch]$NoCrsf
)

$ErrorActionPreference = "Continue"

function Now-Ms { [DateTime]::UtcNow.Ticks / 10000.0 }

function Stats($values, $name, $unit) {
    if ($values.Count -eq 0) { Write-Host ("  {0,-26} (no samples)" -f $name) -ForegroundColor DarkGray; return }
    $sorted = $values | Sort-Object
    $n = $sorted.Count
    $mean = ($values | Measure-Object -Average).Average
    $p = { param($q) $sorted[[int][Math]::Min($n - 1, [Math]::Floor($q * $n))] }
    Write-Host ("  {0,-26} n={1,-5} mean {2,7:N2}{4}  p50 {5,7:N2}  p95 {6,7:N2}  max {7,7:N2}{4}" -f `
        $name, $n, $mean, $unit, $unit, (& $p 0.5), (& $p 0.95), $sorted[$n - 1])
}

# CRSF 0x16 = 16 channels x 11 bits, 0..2047 -> 988..2012 us.
function DecodeCrsf([byte[]]$f) {
    if ($f.Length -lt 5) { return $null }
    $len = $f[1]
    if ($f[2] -ne 0x16) { return $null }
    if (2 + $len -gt $f.Length) { return $null }
    $chans = New-Object 'System.Collections.Generic.List[int]'
    $acc = 0; $bits = 0
    for ($i = 3; $i -lt (2 + $len); $i++) {
        $acc = $acc -bor ($f[$i] -shl $bits)
        $bits += 8
        while ($bits -ge 11) {
            [void]$chans.Add(988 + (($acc -band 0x7FF) * 5 / 8))
            $acc = $acc -shr 11
            $bits -= 11
        }
    }
    return $chans
}

$json = $null; $crsf = $null
try {
    $json = New-Object System.Net.Sockets.UdpClient($JsonPort)
    $json.Client.ReceiveTimeout = 5
    Write-Host "listening for controls      on 0.0.0.0:$JsonPort" -ForegroundColor Cyan
} catch { Write-Host "cannot listen on $JsonPort : $($_.Exception.Message)" -ForegroundColor Red }
if (-not $NoCrsf) {
    try {
        $crsf = New-Object System.Net.Sockets.UdpClient($CrsfPort)
        $crsf.Client.ReceiveTimeout = 5
        Write-Host "listening for module frames on 0.0.0.0:$CrsfPort" -ForegroundColor Cyan
    } catch { Write-Host "cannot listen on $CrsfPort : $($_.Exception.Message)" -ForegroundColor Red }
}
if (-not $json -and -not $crsf) { throw "nothing to listen for" }
Write-Host ""

$injector = $null
if ($Inject) {
    $injector = New-Object System.Net.Sockets.UdpClient
    Write-Host "will inject RCB1 (SB flip) to ${Radio}:$RcbPort" -ForegroundColor Magenta
    Write-Host ""
}

$crsfAt = New-Object 'System.Collections.Generic.List[double]'
$jsonAt = New-Object 'System.Collections.Generic.List[double]'
$lastCrsf = 0.0; $lastJson = 0.0
$lastChans = $null
$movedChannels = @{}            # channel index -> count of changes, to spot a live mix
$analogAfter = @{}              # axis -> timestamps of value changes (WatchSticks)
$analogLast = @{}
$jsonSw = $null

# injection bookkeeping
$injectAt = 0.0; $injectPending = $false
$injectJsonMs = -1.0; $injectCrsfMs = -1.0; $injectCrsfChan = -1
$injectFmode = 1
$injectSent = 0

$started = Now-Ms
$deadline = $started + ($Seconds * 1000)
$nextInject = $started + 2000.0
$lastReport = $started
$crsfFrames = 0; $jsonPackets = 0

while ((Now-Ms) -lt $deadline) {
    $now = Now-Ms

    if ($crsf) {
        for ($d = 0; $d -lt 500; $d++) {
            $ep = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, 0)
            try { $data = $crsf.Receive([ref]$ep) } catch { break }
            $now = Now-Ms
            if ($lastCrsf -gt 0) { [void]$crsfAt.Add($now - $lastCrsf) }
            $lastCrsf = $now
            $crsfFrames++
            $ch = DecodeCrsf $data
            if ($ch) {
                if ($lastChans) {
                    for ($i = 0; $i -lt [Math]::Min(16, $ch.Count); $i++) {
                        if ($ch[$i] -ne $lastChans[$i]) {
                            $movedChannels[$i] = 1 + [int]$movedChannels[$i]
                            if ($injectPending -and $injectCrsfMs -lt 0 -and $injectCrsfChan -lt 0) {
                                $injectCrsfMs = $now - $injectAt
                                $injectCrsfChan = $i
                            }
                        }
                    }
                }
                $lastChans = $ch
            }
        }
    }

    if ($json) {
        for ($d = 0; $d -lt 500; $d++) {
            $ep = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, 0)
            try { $data = $json.Receive([ref]$ep) } catch { break }
            $now = Now-Ms
            if ($lastJson -gt 0) { [void]$jsonAt.Add($now - $lastJson) }
            $lastJson = $now
            $jsonPackets++
            $text = [Text.Encoding]::UTF8.GetString($data)
            foreach ($line in ($text -split "`n")) {
                $line = $line.Trim()
                if (-not $line) { continue }
                try { $j = $line | ConvertFrom-Json } catch { continue }
                if ($j.PSObject.Properties.Name -contains "ev") {
                    if ($injectPending -and $injectJsonMs -lt 0) {
                        if ($j.ev -eq "switch" -and [int]$j.i -eq 1) {
                            $injectJsonMs = $now - $injectAt
                        }
                    }
                    continue
                }
                $st = @($j.st)
                if ($st.Count -ge 8) {
                    for ($a = 0; $a -lt 8; $a++) {
                        $v = [int]$st[$a]
                        if ($analogLast.ContainsKey($a)) {
                            if ($analogLast[$a] -ne $v) {
                                $analogLast[$a] = $v
                                if (-not $analogAfter.ContainsKey($a)) { $analogAfter[$a] = New-Object 'System.Collections.Generic.List[double]' }
                                [void]$analogAfter[$a].Add($now)
                            }
                        } else { $analogLast[$a] = $v }
                    }
                }
                $jsonSw = @($j.sw)
            }
        }
    }

    if ($Inject -and -not $injectPending -and (Now-Ms) -ge $nextInject) {
        # RCB1: 48 bytes little endian (see rc_bridge.cpp / rcb1-send.ps1).
        $b = New-Object byte[] 48
        [BitConverter]::GetBytes([uint32]0x31424352).CopyTo($b, 0)
        [BitConverter]::GetBytes([uint32]$injectSent).CopyTo($b, 4)
        [BitConverter]::GetBytes([uint32]([int]((Now-Ms) - $started))).CopyTo($b, 8)
        $b[12] = 2; $b[13] = 3                    # src SrcEdgeTX, axes+buttons
        [BitConverter]::GetBytes([int16]0).CopyTo($b, 16)
        [BitConverter]::GetBytes([int16]0).CopyTo($b, 18)
        [BitConverter]::GetBytes([int16]0).CopyTo($b, 20)
        [BitConverter]::GetBytes([int16]0).CopyTo($b, 22)
        [BitConverter]::GetBytes([int16]0).CopyTo($b, 24)
        [BitConverter]::GetBytes([int16]0).CopyTo($b, 26)
        [BitConverter]::GetBytes([int16]0).CopyTo($b, 28)
        $b[30] = 0
        $b[31] = [byte]([int]$injectFmode -band 0xFF)
        [BitConverter]::GetBytes([uint64]0).CopyTo($b, 32)
        [BitConverter]::GetBytes([uint32]0).CopyTo($b, 40)
        [BitConverter]::GetBytes([uint32]0).CopyTo($b, 44)
        $ep = New-Object System.Net.IPEndPoint ([System.Net.IPAddress]::Parse($Radio)), $RcbPort
        try {
            [void]$injector.Send($b, 48, $ep)
            $injectAt = Now-Ms
            $injectPending = $true
            $injectSent++
            Write-Host ("  inject #{0}: SB -> {1} at t+{2:N0} ms" -f $injectSent, $injectFmode, ($injectAt - $started)) -ForegroundColor Magenta
        } catch { Write-Host "  inject failed: $($_.Exception.Message)" -ForegroundColor Red }
        $nextInject = (Now-Ms) + 4000.0
    }
    if ($injectPending -and (Now-Ms) - $injectAt -gt 3000.0) {
        Write-Host ("    -> nothing came back for that flip (JSON {0}, module {1})" -f `
            $(if ($injectJsonMs -lt 0) { "no" } else { "yes" }), $(if ($injectCrsfMs -lt 0) { "no" } else { "yes" })) -ForegroundColor DarkYellow
        $injectPending = $false
        $injectFmode = -$injectFmode
    }
    if ($injectPending -and $injectJsonMs -ge 0 -and ((Now-Ms) - $injectAt -gt 1500.0 -or $injectCrsfMs -ge 0)) {
        Write-Host ("    -> JSON switch event after {0:N1} ms; module frame after {1}`n" -f $injectJsonMs, `
            $(if ($injectCrsfMs -ge 0) { "{0:N1} ms (CH{1})" -f $injectCrsfMs, ($injectCrsfChan + 1) } else { "no channel moved" })) -ForegroundColor Green
        $injectPending = $false
        $injectFmode = -$injectFmode
    }

    Start-Sleep -Milliseconds 2
    if (((Now-Ms) - $lastReport) -ge 5000) {
        $lastReport = Now-Ms
        Write-Host ("  ... {0:N1}/s module frames, {1:N1}/s controls" -f ($crsfFrames / $Seconds * ($Seconds / ((Now-Ms) - $started) * 1000)), `
            ($jsonPackets / $Seconds * ($Seconds / ((Now-Ms) - $started) * 1000))) -ForegroundColor DarkGray
    }
}

$elapsed = (Now-Ms) - $started
Write-Host ""
Write-Host ("=== measured over {0:N1} s ===" -f ($elapsed / 1000)) -ForegroundColor White
Write-Host ""
Write-Host "MODULE (firmware -> RF module, what the module is fed)" -ForegroundColor Cyan
Stats $crsfAt "frame interval" " ms"
Write-Host ("  frames received {0:N0} = {1:N1}/s   (CRSF 27 B = {2:N0} B/s at 400000 baud)" -f `
    $crsfFrames, ($crsfFrames / ($elapsed / 1000)), ($crsfFrames * 27 / ($elapsed / 1000)))
if ($lastChans) { Write-Host ("  last channels (us): " + (($lastChans | Select-Object -First 16) -join " ")) -ForegroundColor Green }
if ($movedChannels.Count -gt 0) {
    $live = ($movedChannels.GetEnumerator() | Sort-Object Name | ForEach-Object { "CH$([int]$_.Name + 1)=$($_.Value)" }) -join " "
    Write-Host ("  channels that MOVED during this run: " + $live) -ForegroundColor Green
} else {
    Write-Host "  no channel moved: the model has no mix feeding an input into a channel," -ForegroundColor Yellow
    Write-Host "  so nothing the sticks/buttons do can appear in the module frames yet." -ForegroundColor Yellow
}
Write-Host ""
Write-Host "NETWORK (controls JSON over UDP)" -ForegroundColor Cyan
Stats $jsonAt "snapshot interval" " ms"
Write-Host ("  packets received {0:N0} = {1:N1}/s" -f $jsonPackets, ($jsonPackets / ($elapsed / 1000)))
Write-Host ""

if ($WatchSticks) {
    Write-Host "STICKS (how often the DJI SDK hands over a new analog value)" -ForegroundColor Cyan
    $names = @("LH", "LV", "RV", "RH", "P1", "P2", "SL1", "SL2")
    $any = $false
    for ($a = 0; $a -lt 8; $a++) {
        if (-not $analogAfter.ContainsKey($a)) { continue }
        $ts = $analogAfter[$a]
        if ($ts.Count -lt 2) { continue }
        $any = $true
        $gaps = New-Object 'System.Collections.Generic.List[double]'
        for ($i = 1; $i -lt $ts.Count; $i++) { [void]$gaps.Add($ts[$i] - $ts[$i - 1]) }
        Stats $gaps ("  " + $names[$a] + " change gap") " ms"
        Write-Host ("      {0} changed {1} times in {2:N1} s = {3:N1}/s" -f $names[$a], $ts.Count, ($elapsed / 1000), ($ts.Count / ($elapsed / 1000)))
    }
    if (-not $any) { Write-Host "  no analog input changed - move the sticks while this runs" -ForegroundColor Yellow }
    Write-Host ""
    Write-Host "  the SDK's own line in logcat says the same thing, once a second while a stick moves:" -ForegroundColor DarkGray
    Write-Host "    adb shell logcat -s EdgeTXUI | Select-String 'dji: stick'" -ForegroundColor DarkGray
    Write-Host ""
}

if ($Inject) {
    Write-Host "INJECT (RCB1 packet -> radio, i.e. input -> app -> firmware)" -ForegroundColor Cyan
    if ($injectSent -eq 0) { Write-Host "  nothing was injected" -ForegroundColor Yellow }
    else { Write-Host ("  {0} packet(s) sent, {1} of them seen as a JSON switch event" -f $injectSent, $injectJsonMs) }
    Write-Host "  (needs rcbridge.conf enabled=true, and a mix for the module column)"
    Write-Host ""
}

if ($injector) { $injector.Close() }
if ($json) { $json.Close() }
if ($crsf) { $crsf.Close() }
Write-Host "done." -ForegroundColor Green
