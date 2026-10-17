# Stick -> RF module latency.
#
#   powershell -ExecutionPolicy Bypass -File tools\stick-latency.ps1 -Seconds 40
#
# What it measures
#   STIMULUS  a change of the analog axis that moves most, as seen in the app's
#             control snapshots on 14560 (~100/s, each carrying the app's own
#             timestamp "t").
#   RESPONSE  the first module frame on 14561 (~195/s) in which CH<Channel>
#             carries a new value.
#   LATENCY   response - stimulus. The stimulus side is taken from the app's own
#             clock (its "t") so the network's jitter on that side drops out; the
#             offset between the app's clock and this PC's is the median of
#             (pc - t) over the run.
#
# The number is what the firmware + module path costs once the stick value has
# changed. The joystick node samples the hardware every ~16 ms, so the physical
# stick -> module path is this number plus 0..16 ms (mean ~8 ms). The SDK path
# samples at 8-14/s, which is where the 70-125 ms of the old route came from.

param(
    [int]$Seconds = 40,
    [int]$JsonPort = 14560,
    [int]$CrsfPort = 14561,
    [int]$Channel = 7
)

$ErrorActionPreference = "Continue"

function NowMs { [DateTime]::UtcNow.Ticks / 10000.0 }

function Stats($values, $name, $unit) {
    if ($values.Count -eq 0) { Write-Host ("  {0,-24} (no samples)" -f $name) -ForegroundColor DarkGray; return }
    $sorted = @($values | Sort-Object)
    $n = $sorted.Count
    $mean = ($values | Measure-Object -Average).Average
    $p = { param($q) $sorted[[int][Math]::Min($n - 1, [Math]::Floor($q * $n))] }
    Write-Host ("  {0,-24} n={1,-5} mean {2,7:N2}{4}  p50 {5,7:N2}  p95 {6,7:N2}  max {7,7:N2}{4}" -f `
        $name, $n, $mean, $unit, $unit, (& $p 0.5), (& $p 0.95), $sorted[$n - 1])
}

# CRSF 0x16 = 16 channels x 11 bits, 0..2047 -> 988..2012 us.
#
# Note the [int] casts: PowerShell's -shl keeps the left operand's type, so a
# [byte] shifted by 8 or more silently truncates to zero and every channel after
# the first decodes to nonsense.
function DecodeCrsf([byte[]]$f) {
    if ($f.Length -lt 5) { return $null }
    $len = $f[1]
    if ($f[2] -ne 0x16) { return $null }
    if (2 + $len -gt $f.Length) { return $null }
    $chans = New-Object 'System.Collections.Generic.List[int]'
    $acc = 0; $bits = 0
    for ($i = 3; $i -lt (2 + $len); $i++) {
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

$json = New-Object System.Net.Sockets.UdpClient($JsonPort); $json.Client.ReceiveTimeout = 2
$crsf = New-Object System.Net.Sockets.UdpClient($CrsfPort); $crsf.Client.ReceiveTimeout = 2
Write-Host "listening: controls on $JsonPort, module frames on $CrsfPort" -ForegroundColor Cyan

$sticks = New-Object 'System.Collections.Generic.List[object]'
$frames = New-Object 'System.Collections.Generic.List[object]'
$jCount = 0; $cCount = 0
$start = NowMs
$deadline = $start + $Seconds * 1000

while ((NowMs) -lt $deadline) {
    for ($d = 0; $d -lt 400; $d++) {
        $ep = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, 0)
        try { $data = $crsf.Receive([ref]$ep) } catch { break }
        $now = NowMs
        $ch = DecodeCrsf $data
        if ($ch) { $cCount++; [void]$frames.Add([pscustomobject]@{ pc = $now; ch = $ch }) }
    }
    for ($d = 0; $d -lt 400; $d++) {
        $ep = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, 0)
        try { $data = $json.Receive([ref]$ep) } catch { break }
        $now = NowMs
        $text = [Text.Encoding]::UTF8.GetString($data)
        foreach ($line in ($text -split "`n")) {
            $line = $line.Trim()
            if (-not $line) { continue }
            try { $j = $line | ConvertFrom-Json } catch { continue }
            if ($j.PSObject.Properties.Name -contains "ev") { continue }
            if ($null -eq $j.st) { continue }
            $v = @($j.st)
            if ($v.Count -lt 8) { continue }
            $jCount++
            [void]$sticks.Add([pscustomobject]@{ pc = $now; t = [double]$j.t; v = $v })
        }
    }
    Start-Sleep -Milliseconds 1
}
$json.Close()
$crsf.Close()

$elapsed = ((NowMs) - $start) / 1000
Write-Host ("received {0} control snapshots, {1} module frames over {2:N1} s" -f $jCount, $cCount, $elapsed)
if ($jCount -lt 10 -or $cCount -lt 10) { Write-Host "not enough data" -ForegroundColor Red; return }

$offsets = @($sticks | ForEach-Object { $_.pc - $_.t }) | Sort-Object
$offset = $offsets[[int]($offsets.Count / 2)]
Write-Host ("clock offset (pc - app) = {0:N1} ms" -f $offset) -ForegroundColor DarkGray

# One-way delay of the control stream cannot be separated from the clock offset,
# but its spread around the median (which is by definition this offset) can: that
# is the queueing + network jitter on the JSON path.
$spread = @($offsets | ForEach-Object { $_ - $offset })
Stats $spread "control stream jitter" " ms"

Write-Host "analog axes, changes seen in the control snapshots:" -ForegroundColor DarkGray
$names = @("LH", "LV", "RV", "RH", "P1", "P2", "SL1", "SL2")
$bestAxis = -1; $bestCount = 0
for ($a = 0; $a -lt 8; $a++) {
    $prev = $null; $n = 0
    foreach ($s in $sticks) {
        if ($null -ne $prev -and $s.v[$a] -ne $prev) { $n++ }
        $prev = $s.v[$a]
    }
    Write-Host ("  {0,-4} {1,5} changes = {2,7:N1}/s" -f $names[$a], $n, ($n / $elapsed)) -ForegroundColor DarkGray
    if ($n -gt $bestCount) { $bestCount = $n; $bestAxis = $a }
}
if ($bestAxis -lt 0 -or $bestCount -lt 5) {
    Write-Host "no analog input moved during the run - nothing to correlate" -ForegroundColor Yellow
    return
}
Write-Host ("correlating axis {0} ({1}) with CH{2}" -f $bestAxis, $names[$bestAxis], $Channel) -ForegroundColor Cyan

$stim = New-Object 'System.Collections.Generic.List[double]'
$prev = $null
foreach ($s in $sticks) {
    if ($null -ne $prev -and $s.v[$bestAxis] -ne $prev) { [void]$stim.Add($s.t) }
    $prev = $s.v[$bestAxis]
}

$intervals = New-Object 'System.Collections.Generic.List[double]'
for ($i = 1; $i -lt $frames.Count; $i++) { [void]$intervals.Add($frames[$i].pc - $frames[$i - 1].pc) }

$ci = $Channel - 1
$chChanges = New-Object 'System.Collections.Generic.List[object]'
$lastCh = $null
$chMin = $null; $chMax = $null
foreach ($f in $frames) {
    $v = $f.ch[$ci]
    if ($null -eq $chMin -or $v -lt $chMin) { $chMin = $v }
    if ($null -eq $chMax -or $v -gt $chMax) { $chMax = $v }
    if ($null -ne $lastCh -and $v -ne $lastCh) { [void]$chChanges.Add([pscustomobject]@{ pc = $f.pc; v = $v }) }
    $lastCh = $v
}

Write-Host ""
Write-Host ("CH{0}: {1} changes in {2:N1} s = {3:N1}/s, range {4}..{5} us" -f `
    $Channel, $chChanges.Count, $elapsed, ($chChanges.Count / $elapsed), $chMin, $chMax) -ForegroundColor Green
if ($chChanges.Count -lt 3) {
    Write-Host "that channel is not following the stick: no live mix on it" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "all module channels:" -ForegroundColor DarkGray
for ($c = 0; $c -lt 16; $c++) {
    $n = 0; $last = $null; $mn = $null; $mx = $null
    foreach ($f in $frames) {
        if ($c -ge $f.ch.Count) { continue }
        $v = $f.ch[$c]
        if ($null -eq $mn -or $v -lt $mn) { $mn = $v }
        if ($null -eq $mx -or $v -gt $mx) { $mx = $v }
        if ($null -ne $last -and $v -ne $last) { $n++ }
        $last = $v
    }
    $mark = " "
    if ($c -eq $ci) { $mark = "*" }
    Write-Host ("  {0}CH{1,-3} {2,5} chg  {3,5}..{4,5} us  span {5,5}" -f $mark, ($c + 1), $n, $mn, $mx, ($mx - $mn)) -ForegroundColor DarkGray
}

$lats = New-Object 'System.Collections.Generic.List[double]'
$unmatched = 0
$cursor = 0
$bestT = $null
foreach ($c in $chChanges) {
    $appT = $c.pc - $offset
    while ($cursor -lt $stim.Count -and $stim[$cursor] -le $appT) { $bestT = $stim[$cursor]; $cursor++ }
    if ($null -eq $bestT -or ($appT - $bestT) -gt 500) { $unmatched++; continue }
    [void]$lats.Add($appT - $bestT)
}

Write-Host ""
Write-Host "=== results ===" -ForegroundColor White
Stats $intervals "module frame interval" " ms"
Stats $lats ("stick change -> CH" + $Channel + " frame") " ms"
Write-Host ("  paired {0} of {1} channel changes ({2} had no recent stick change)" -f `
    $lats.Count, $chChanges.Count, $unmatched) -ForegroundColor DarkGray
Write-Host ""
Write-Host "  the joystick node samples the hardware every ~16 ms (median), so the" -ForegroundColor DarkGray
Write-Host "  physical stick -> module path is the number above plus 0..16 ms." -ForegroundColor DarkGray
Write-Host "done." -ForegroundColor Green
