# Receive the radio's network output and print it - the same job as
# tools\netdump.py, with nothing to install (no Python on this PC).
#
#   powershell -ExecutionPolicy Bypass -File tools\netdump.ps1
#       both streams, snapshot lines + events + one stats line per second
#
#   ... -Quiet        events only (no snapshot spam)
#   ... -Raw          dump the module frames as hex too
#   ... -NoCrsf       controls only      -NoJson   module frames only
#   ... -Seconds 30   stop by itself
#
# Wire format: see NETLINK.md. 14560 = JSON control snapshots + events,
# 14561 = the firmware's own module bytes, mirrored verbatim.

param(
    [int]$JsonPort = 14560,
    [int]$CrsfPort = 14561,
    [switch]$NoJson,
    [switch]$NoCrsf,
    [switch]$Raw,
    [switch]$Quiet,
    [int]$Seconds = 0
)

$ErrorActionPreference = "Stop"

function LanAddresses {
    Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress -notmatch '^(127\.|169\.254\.)' } |
        Sort-Object @{ Expression = { if ($_.InterfaceAlias -match 'Wi-?Fi|WLAN|Wireless') { 0 } else { 1 } } }, InterfaceIndex |
        ForEach-Object { "{0}  ({1})" -f $_.IPAddress, $_.InterfaceAlias }
}

function SwitchText([int]$v) { if ($v -lt 0) { "up" } elseif ($v -eq 0) { "mid" } else { "dn" } }

# CRSF 0x16 = 16 channels x 11 bits, 0..2047 -> 988..2012 us.
function DecodeCrsf([byte[]]$f) {
    if ($f.Length -lt 5) { return $null }
    $len = $f[1]
    if ($f[2] -ne 0x16) { return $null }
    if (2 + $len -gt $f.Length) { return $null }
    $chans = New-Object System.Collections.Generic.List[int]
    $acc = 0; $bits = 0
    for ($i = 3; $i -lt (2 + $len); $i++) {
        # The [int] cast matters: PowerShell's -shl keeps the operand's type, so a
        # [byte] shifted by 8+ truncates to zero and the channels turn to nonsense.
        $acc = $acc -bor ([int]$f[$i] -shl $bits)
        $bits += 8
        while ($bits -ge 11) {
            [void]$chans.Add(988 + (($acc -band 0x7FF) * 5 / 8))
            $acc = $acc -shr 11
            $bits -= 11
        }
    }
    return $chans
}

Write-Host "local addresses:" -ForegroundColor DarkGray
LanAddresses | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }
Write-Host ""

$socks = @()
if (-not $NoJson) {
    try {
        $u = New-Object System.Net.Sockets.UdpClient($JsonPort)
        $u.Client.ReceiveTimeout = 10
        $socks += @{ kind = "json"; client = $u; port = $JsonPort }
        Write-Host "listening for controls      on 0.0.0.0:$JsonPort" -ForegroundColor Cyan
    } catch { Write-Host "cannot listen on $JsonPort : $($_.Exception.Message)" -ForegroundColor Red }
}
if (-not $NoCrsf) {
    try {
        $u = New-Object System.Net.Sockets.UdpClient($CrsfPort)
        $u.Client.ReceiveTimeout = 10
        $socks += @{ kind = "crsf"; client = $u; port = $CrsfPort }
        Write-Host "listening for module frames on 0.0.0.0:$CrsfPort" -ForegroundColor Cyan
    } catch { Write-Host "cannot listen on $CrsfPort : $($_.Exception.Message)" -ForegroundColor Red }
}
if ($socks.Count -eq 0) { throw "nothing to listen for (use neither -NoJson nor -NoCrsf)" }
Write-Host ""

$started     = Get-Date
$deadline    = if ($Seconds -gt 0) { $started.AddSeconds($Seconds) } else { [datetime]::MaxValue }
$statsAt     = $started
$jsonCount   = 0; $jsonBytes = 0; $jsonEvents = 0
$crsfCount   = 0; $crsfBytes = 0
$shown       = [datetime]::MinValue
$chanShown   = [datetime]::MinValue
$gotAnything = $false
$warned      = $false
$rawLeft     = 12

while ((Get-Date) -lt $deadline) {
    foreach ($s in $socks) {
        for ($drain = 0; $drain -lt 400; $drain++) {
            $ep = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, 0)
            try {
                $data = $s.client.Receive([ref]$ep)
            } catch {
                break     # nothing left (the socket's own timeout fired)
            }
            $gotAnything = $true

            if ($s.kind -eq "crsf") {
                $crsfCount++; $crsfBytes += $data.Length
                $chans = DecodeCrsf $data
                if ($chans) {
                    # the frames arrive ~195/s - printing every one of them buries
                    # everything else, so show the decoded channels once a second
                    if (((Get-Date) - $chanShown).TotalMilliseconds -lt 1000) { continue }
                    $chanShown = Get-Date
                    Write-Host ("  channels (us): " + (($chans | Select-Object -First 16) -join " ")) -ForegroundColor Green
                } elseif ($Raw -and $rawLeft -gt 0) {
                    $rawLeft--
                    $hex = ($data | ForEach-Object { $_.ToString("x2") }) -join " "
                    Write-Host ("  module: {0} bytes  {1}" -f $data.Length, $hex) -ForegroundColor DarkGray
                }
                continue
            }

            $jsonCount++; $jsonBytes += $data.Length
            $text = [Text.Encoding]::UTF8.GetString($data)
            foreach ($line in ($text -split "`n")) {
                $line = $line.Trim()
                if (-not $line) { continue }
                try { $j = $line | ConvertFrom-Json } catch { Write-Host "  ? $line" -ForegroundColor DarkGray; continue }

                if ($j.PSObject.Properties.Name -contains "ev") {
                    $jsonEvents++
                    switch ($j.ev) {
                        "key"    { Write-Host ("  event: key {0} {1}" -f $j.k, $(if ($j.d -eq 1) { "down" } else { "up" })) -ForegroundColor Yellow }
                        "switch" { Write-Host ("  event: switch {0} -> {1} ({2})" -f $j.i, $j.v, (SwitchText ([int]$j.v))) -ForegroundColor Yellow }
                        "rotary" { Write-Host ("  event: rotary {0}" -f $j.v) -ForegroundColor Yellow }
                        default  { Write-Host ("  event: {0}" -f $j.ev) -ForegroundColor Yellow }
                    }
                    continue
                }

                if ($Quiet) { continue }
                if (((Get-Date) - $shown).TotalMilliseconds -lt 500) { continue }   # don't print faster than the eye
                $shown = Get-Date
                $st = @($j.st)
                $sw = @($j.sw)
                $swText = @(); for ($i = 0; $i -lt $sw.Count; $i++) { $swText += ("S{0}{1}" -f $i, (SwitchText ([int]$sw[$i]))) }
                Write-Host ("  t={0,6}ms  LH={1}  LV={2}  RV={3}  RH={4}  P1={5}  P2={6}  SL1={7}  SL2={8}  | {9} | keys=0x{10:x2} rot={11}" -f `
                    $j.t, $st[0], $st[1], $st[2], $st[3], $st[4], $st[5], $st[6], $st[7], ($swText -join " "), $j.k, $j.rot)
            }
        }
    }

    $now = Get-Date
    if (($now - $statsAt).TotalSeconds -ge 1) {
        $dt = ($now - $statsAt).TotalSeconds
        Write-Host ("  -- {0:N1}/s controls ({1:N0} B/s, {2} event(s)), {3:N1}/s module frames ({4:N0} B/s)" -f `
            ($jsonCount / $dt), ($jsonBytes / $dt), $jsonEvents, ($crsfCount / $dt), ($crsfBytes / $dt)) -ForegroundColor DarkCyan
        $statsAt = $now; $jsonCount = 0; $jsonBytes = 0; $jsonEvents = 0; $crsfCount = 0; $crsfBytes = 0
    }

    if (-not $gotAnything -and -not $warned -and ($now - $started).TotalSeconds -ge 10) {
        $warned = $true
        Write-Host ""
        Write-Host "nothing has arrived in 10 s. Check, on the radio side:" -ForegroundColor Yellow
        Write-Host "  * netlink.conf's host = one of the addresses printed above (not the radio's own)" -ForegroundColor Yellow
        Write-Host "  * the app was restarted after the file was written, and its log says 'net: sending ...'" -ForegroundColor Yellow
        Write-Host "  adb shell logcat -s EdgeTXNet EdgeTXUI" -ForegroundColor Gray
        Write-Host "and on this PC (admin PowerShell, once):" -ForegroundColor Yellow
        Write-Host "  New-NetFirewallRule -DisplayName ""netdump UDP $JsonPort-$CrsfPort"" -Direction Inbound -Action Allow -Protocol UDP -LocalPort $JsonPort,$CrsfPort -Profile Any" -ForegroundColor Gray
        Write-Host ""
    }
}

foreach ($s in $socks) { $s.client.Close() }
Write-Host ""
Write-Host "stopped after $([math]::Round(((Get-Date) - $started).TotalSeconds)) s"
