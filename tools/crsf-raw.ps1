# Raw look at the module frames: are they really CRSF 0x16 with 16x11-bit
# channels, and are the channel values sensible?
#
#   powershell -ExecutionPolicy Bypass -File tools\crsf-raw.ps1 -Seconds 25
#
# It dumps the first frames as hex, checks the CRSF CRC8 (poly 0xD5), and then
# tries every bit alignment / bit order for the channel payload, reporting for
# each which one looks like a real model (channels near 992, one of them wide).

param(
    [int]$Seconds = 25,
    [int]$CrsfPort = 14561,
    [int]$JsonPort = 14560
)

$ErrorActionPreference = "Continue"

function NowMs { [DateTime]::UtcNow.Ticks / 10000.0 }

# CRC8 with polynomial 0xD5 (CRSF uses the DVB-S2 CRC).
$g_crcTable = New-Object 'int[]' 256
for ($i = 0; $i -lt 256; $i++) {
    $c = $i
    for ($b = 0; $b -lt 8; $b++) {
        if ($c -band 0x80) { $c = (($c -shl 1) -bxor 0xD5) -band 0xFF } else { $c = ($c -shl 1) -band 0xFF }
    }
    $g_crcTable[$i] = $c
}
function Crc8([byte[]]$data, [int]$from, [int]$to) {
    $crc = 0
    for ($i = $from; $i -le $to; $i++) { $crc = $g_crcTable[($crc -bxor $data[$i]) -band 0xFF] }
    return $crc
}

# Little-endian bit field out of a byte array.
function GetBits([byte[]]$b, [int]$bitStart, [int]$count) {
    $byteIdx = [int]($bitStart / 8)
    $bitOff = $bitStart % 8
    $nbytes = [int][Math]::Ceiling(($bitOff + $count) / 8.0)
    $acc = 0
    for ($i = 0; $i -lt $nbytes; $i++) {
        if (($byteIdx + $i) -lt $b.Length) { $acc = $acc -bor ([int]$b[$byteIdx + $i] -shl (8 * $i)) }
    }
    return (($acc -shr $bitOff) -band ((1 -shl $count) - 1))
}
function Rev11([int]$v) {
    $r = 0
    for ($i = 0; $i -lt 11; $i++) { if ($v -band (1 -shl $i)) { $r = $r -bor (1 -shl (10 - $i)) } }
    return $r
}

$json = New-Object System.Net.Sockets.UdpClient($JsonPort); $json.Client.ReceiveTimeout = 2
$crsf = New-Object System.Net.Sockets.UdpClient($CrsfPort); $crsf.Client.ReceiveTimeout = 2
Write-Host "listening: controls on $JsonPort, module frames on $CrsfPort" -ForegroundColor Cyan

$frames = New-Object 'System.Collections.Generic.List[byte[]]'
$lengths = @{}
$axisMin = New-Object 'int[]' 8
$axisMax = New-Object 'int[]' 8
$axisCount = New-Object 'int[]' 8
for ($a = 0; $a -lt 8; $a++) { $axisMin[$a] = 99999; $axisMax[$a] = -99999 }
$jCount = 0

$start = NowMs
$deadline = $start + $Seconds * 1000
while ((NowMs) -lt $deadline) {
    for ($d = 0; $d -lt 200; $d++) {
        $ep = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, 0)
        try { $data = $crsf.Receive([ref]$ep) } catch { break }
        if ($data.Length -gt 0) {
            if ($frames.Count -lt 20000) { [void]$frames.Add($data) }
            $k = [string]$data.Length
            if ($lengths.ContainsKey($k)) { $lengths[$k]++ } else { $lengths[$k] = 1 }
        }
    }
    for ($d = 0; $d -lt 200; $d++) {
        $ep = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, 0)
        try { $data = $json.Receive([ref]$ep) } catch { break }
        $text = [Text.Encoding]::UTF8.GetString($data)
        foreach ($line in ($text -split "`n")) {
            $line = $line.Trim()
            if (-not $line) { continue }
            try { $j = $line | ConvertFrom-Json } catch { continue }
            if ($null -eq $j.st) { continue }
            $v = @($j.st)
            if ($v.Count -lt 8) { continue }
            $jCount++
            for ($a = 0; $a -lt 8; $a++) {
                $x = [int]$v[$a]
                if ($x -lt $axisMin[$a]) { $axisMin[$a] = $x }
                if ($x -gt $axisMax[$a]) { $axisMax[$a] = $x }
                $axisCount[$a]++
            }
        }
    }
    Start-Sleep -Milliseconds 1
}
$json.Close(); $crsf.Close()

Write-Host ("received {0} module frames, {1} control snapshots" -f $frames.Count, $jCount) -ForegroundColor Cyan
if ($frames.Count -eq 0) { Write-Host "no module frames arrived" -ForegroundColor Red; return }

Write-Host ""
Write-Host "datagram lengths:" -ForegroundColor DarkGray
foreach ($k in ($lengths.Keys | Sort-Object { [int]$_ })) { Write-Host ("  {0,4} bytes x {1}" -f [int]$k, $lengths[$k]) -ForegroundColor DarkGray }

Write-Host ""
Write-Host "first frames:" -ForegroundColor White
for ($i = 0; $i -lt [Math]::Min(3, $frames.Count); $i++) {
    $f = $frames[$i]
    $hex = ($f | ForEach-Object { $_.ToString("X2") }) -join ' '
    Write-Host ("  [{0}] len={1} type=0x{2:X2} crc=0x{3:X2} {4}" -f $i, $f[1], $f[2], $f[$f.Length - 1], $hex)
}

# CRC check on the frames that look like CRSF channel frames.
$okCrc = 0; $badCrc = 0
foreach ($f in $frames) {
    if ($f.Length -lt 4) { continue }
    if ($f[2] -ne 0x16) { continue }
    $len = $f[1]
    if ((2 + $len) -gt $f.Length) { continue }
    $want = Crc8 $f 2 (2 + $len - 2)
    if ($want -eq $f[2 + $len - 1]) { $okCrc++ } else { $badCrc++ }
}
Write-Host ""
Write-Host ("CRSF 0x16 frames: CRC ok {0}, bad {1}" -f $okCrc, $badCrc) -ForegroundColor White

# Channel-payload candidates: bit offset 0..7, LSB-first and reversed (MSB-first).
Write-Host ""
Write-Host "channel payload candidates (best first):" -ForegroundColor White
Write-Host ("  {0,-6} {1,-5} {2,10} {3,10} {4,10} {5,10} {6,10}" -f "order", "off", "mean|v-992|", "valid", "ch7 span", "ch7 min", "ch7 max") -ForegroundColor DarkGray

$results = New-Object 'System.Collections.Generic.List[object]'
foreach ($rev in @($false, $true)) {
    foreach ($off in 0..7) {
        $sum = 0.0; $n = 0; $valid = 0
        $cMin = 99999; $cMax = -99999
        $first = $null
        foreach ($f in $frames) {
            if ($f.Length -lt 4 -or $f[2] -ne 0x16) { continue }
            $len = $f[1]
            if ((2 + $len) -gt $f.Length) { continue }
            if ($null -eq $first) { $first = $f }
            for ($c = 0; $c -lt 16; $c++) {
                $raw = GetBits $f (24 + $off + 11 * $c) 11
                if ($rev) { $raw = Rev11 $raw }
                if ($c -eq 6) {
                    if ($raw -lt $cMin) { $cMin = $raw }
                    if ($raw -gt $cMax) { $cMax = $raw }
                }
                $sum += [Math]::Abs($raw - 992); $n++
                if ($raw -ge 172 -and $raw -le 1811) { $valid++ }
            }
        }
        if ($n -eq 0) { continue }
        [void]$results.Add([pscustomobject]@{
            rev = $rev; off = $off; score = $sum / $n; valid = $valid / $n
            min = $cMin; max = $cMax; span = $cMax - $cMin; frames = $n / 16
        })
    }
}
foreach ($r in ($results | Sort-Object score)) {
    Write-Host ("  {0,-6} {1,-5} {2,10:N1} {3,10:P0} {4,10} {5,10} {6,10}" -f `
        $(if ($r.rev) { "msb" } else { "lsb" }), $r.off, $r.score, $r.valid, $r.span, $r.min, $r.max) -ForegroundColor Gray
}

Write-Host ""
Write-Host "analog axes seen on the control stream (raw, 0..4096, centre 2048):" -ForegroundColor DarkGray
$names = @("LH", "LV", "RV", "RH", "P1", "P2", "SL1", "SL2")
for ($a = 0; $a -lt 8; $a++) {
    if ($axisMin[$a] -gt $axisMax[$a]) { continue }
    Write-Host ("  {0,-4} min {1,5}  max {2,5}  span {3,5}" -f $names[$a], $axisMin[$a], $axisMax[$a], ($axisMax[$a] - $axisMin[$a])) -ForegroundColor Gray
}
Write-Host "done." -ForegroundColor Green
