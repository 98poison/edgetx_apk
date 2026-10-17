# Live monitor: prints every change the radio reports over UDP 14560, as it happens.
# Use it to confirm that a button, switch, dial or stick actually reaches the app and
# comes out of the network side.
#
#   powershell -ExecutionPolicy Bypass -File tools\live-monitor.ps1
#       [-Seconds 120] [-JsonPort 14560] [-Raw] [-Quiet]
#
# -Raw    also print every snapshot (10/s), not just the changes
# -Quiet  only the changes, no stick lines at all
#
# What you should see while you press things:
#   sticks / dials   a "stick" line with the value that moved
#   switches         "switch  i -> up|mid|down"  (i = 0..9 = SA..SJ)
#   keys (buttons)   "key     k down/up"         (k = EdgeTX key number)
#   rotary           "rotary  +1 / -1"
#
# If nothing appears at all, the app is not sending: check that it is in the
# foreground (window attached) and that its log says "net: sending ...".

param(
    [int]$JsonPort = 14560,
    [int]$Seconds = 120,
    [switch]$Raw,
    [switch]$Quiet
)

$ErrorActionPreference = "Continue"

function SwitchText([int]$v) { if ($v -lt 0) { "up" } elseif ($v -eq 0) { "mid" } else { "down" } }
function Stamp { return (Get-Date).ToString("HH:mm:ss.fff") }

$u = New-Object System.Net.Sockets.UdpClient($JsonPort)
$u.Client.ReceiveTimeout = 500
$u.Client.Blocking = $false

Write-Host ("listening on 0.0.0.0:" + $JsonPort + " for " + $Seconds + " s  (Ctrl+C to stop)") -ForegroundColor Cyan
if (-not $Quiet) {
    Write-Host "press the sticks, dials, switches, shoulder buttons and C1..C4 - every change is listed" -ForegroundColor DarkGray
}
Write-Host ""

$labels = @("LH", "LV", "RV", "RH", "P1", "P2", "SL1", "SL2")
$prevSt = $null
$prevSw = $null
$prevK = $null
$prevRot = $null
$started = Get-Date
$stats = 0
$statsAt = $started
$any = $false

while (((Get-Date) - $started).TotalSeconds -lt $Seconds) {
    for ($i = 0; $i -lt 400; $i++) {
        $from = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, 0)
        try { $d = $u.Receive([ref]$from) } catch { break }
        if ($d -eq $null) { break }
        $any = $true
        $stats++
        $text = [Text.Encoding]::UTF8.GetString($d)
        foreach ($line in ($text -split "`n")) {
            $line = $line.Trim()
            if ($line -eq "") { continue }
            try { $j = $line | ConvertFrom-Json } catch { continue }

            if ($j.PSObject.Properties.Name -contains "ev") {
                switch ($j.ev) {
                    "key" { Write-Host ((Stamp) + "  key     " + $j.k + "  " + $(if ($j.d -eq 1) { "down" } else { "up" })) -ForegroundColor Yellow }
                    "switch" { Write-Host ((Stamp) + "  switch  " + $j.i + " -> " + (SwitchText ([int]$j.v))) -ForegroundColor Green }
                    "rotary" { Write-Host ((Stamp) + "  rotary  " + $j.v) -ForegroundColor Magenta }
                    default { Write-Host ((Stamp) + "  event   " + $j.ev) -ForegroundColor Yellow }
                }
                continue
            }

            $st = @($j.st)
            $sw = @($j.sw)
            $k = [int]$j.k
            $rot = [int]$j.rot

            if ($null -eq $prevSt) { $prevSt = $st; $prevSw = $sw; $prevK = $k; $prevRot = $rot }
            if ($null -eq $prevSt) { continue }

            for ($c = 0; $c -lt 8 -and $c -lt $st.Count; $c++) {
                if ([int]$st[$c] -ne [int]$prevSt[$c]) {
                    if (-not $Quiet) {
                        Write-Host ((Stamp) + "  stick   " + $labels[$c] + " = " + $st[$c] + "   (" + $prevSt[$c] + " -> " + $st[$c] + ")") -ForegroundColor White
                    }
                }
            }
            for ($c = 0; $c -lt 10 -and $c -lt $sw.Count; $c++) {
                if ([int]$sw[$c] -ne [int]$prevSw[$c]) {
                    Write-Host ((Stamp) + "  switch  " + $c + " = " + (SwitchText ([int]$sw[$c])) + "   (S" + [char](65 + $c) + ")") -ForegroundColor Green
                }
            }
            if ($k -ne $prevK) { Write-Host ((Stamp) + "  keys    0x" + ("{0:x}" -f $k)) -ForegroundColor Yellow }
            if ($rot -ne $prevRot) { Write-Host ((Stamp) + "  rotary  total " + $rot) -ForegroundColor Magenta }
            if ($Raw -and -not $Quiet) {
                Write-Host ((Stamp) + "  snapshot " + $line) -ForegroundColor DarkGray
            }
            $prevSt = $st; $prevSw = $sw; $prevK = $k; $prevRot = $rot
        }
    }
    $now = Get-Date
    if (($now - $statsAt).TotalSeconds -ge 5) {
        if (-not $any) { Write-Host ((Stamp) + "  nothing is arriving - is the app in the foreground?") -ForegroundColor Red }
        $statsAt = $now
    }
    Start-Sleep -Milliseconds 2
}
$u.Close()
Write-Host ""
if ($any) { Write-Host ("done - received " + $stats + " datagrams") -ForegroundColor Cyan }
else { Write-Host "nothing arrived on UDP $JsonPort" -ForegroundColor Red }
