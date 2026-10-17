# RCB1 injector: sends rcbridge-style packets to the radio so the whole inbound
# mapping (UDP -> rc_bridge.cpp -> joystick:: -> EdgeTX mixer) can be verified
# from this PC, without touching the radio.
#
# Packet layout (48 bytes, little endian) - must match rc_bridge.cpp's Packet:
#   0  uint32 magic 0x31424352 "RCB1"
#   4  uint32 seq
#   8  uint32 tMillis
#  12  uint8  src       0=evdev 1=MSDK 2=SrcEdgeTX   (the radio accepts 0,1,2)
#  13  uint8  flags     bit0 axes, bit1 buttons
#  14  uint16 reserved0
#  16  int16  sticks[4] LH LV RH RV, -1000..1000
#  24  int16  dials[2]  left right (-660..660 nominal, same range accepted)
#  28  int16  wheel
#  30  uint8  five      bit0 up bit1 down bit2 left bit3 right bit4 centre
#  31  int8   fmode     -1 / 0 / +1  -> switch SB
#  32  uint64 buttons
#  40  uint32 sourceMask
#  44  uint32 reserved5
param(
  [string]$Target = "192.168.1.22",
  [int]$Port = 14571,
  [int]$Seconds = 24,
  [int]$Rate = 100,
  [int]$LocalPort = 14561,
  [switch]$Listen
)
$ErrorActionPreference = "Continue"

$udp = New-Object System.Net.Sockets.UdpClient
if ($Listen) {
  $udp.Client.SetSocketOption([System.Net.Sockets.SocketOptionLevel]::Socket,
    [System.Net.Sockets.SocketOptionName]::ReuseAddress, $true)
  try {
    $udp.Client.Bind((New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, $LocalPort)))
    $udp.Client.Blocking = $false
  }
  catch { "cannot bind local port $LocalPort : $($_.Exception.Message)" }
}
$ep = New-Object System.Net.IPEndPoint ([System.Net.IPAddress]::Parse($Target)), $Port

function New-Packet([int]$lh, [int]$lv, [int]$rh, [int]$rv, [int]$d0, [int]$d1,
  [int]$five, [int]$fmode, [uint64]$buttons, [uint32]$seq, [uint32]$ms) {
  $b = New-Object byte[] 48
  [BitConverter]::GetBytes([uint32]0x31424352).CopyTo($b, 0)
  [BitConverter]::GetBytes([uint32]$seq).CopyTo($b, 4)
  [BitConverter]::GetBytes([uint32]$ms).CopyTo($b, 8)
  $b[12] = 2      # src = SrcEdgeTX (what rcbridge sends)
  $b[13] = 3      # axes + buttons
  [BitConverter]::GetBytes([int16]$lh).CopyTo($b, 16)
  [BitConverter]::GetBytes([int16]$lv).CopyTo($b, 18)
  [BitConverter]::GetBytes([int16]$rh).CopyTo($b, 20)
  [BitConverter]::GetBytes([int16]$rv).CopyTo($b, 22)
  [BitConverter]::GetBytes([int16]$d0).CopyTo($b, 24)
  [BitConverter]::GetBytes([int16]$d1).CopyTo($b, 26)
  [BitConverter]::GetBytes([int16]0).CopyTo($b, 28)
  $b[30] = [byte]$five
  $b[31] = [byte](([int]$fmode) -band 0xFF)
  [BitConverter]::GetBytes([uint64]$buttons).CopyTo($b, 32)
  [BitConverter]::GetBytes([uint32]0).CopyTo($b, 40)
  [BitConverter]::GetBytes([uint32]0).CopyTo($b, 44)
  return , $b
}

"target $Target`:$Port   seconds=$Seconds rate=${Rate}Hz  listen=$Listen (local $LocalPort)"
$seq = 0; $sent = 0; $acks = 0; $firstAckMs = 0
$sw = [Diagnostics.Stopwatch]::StartNew()
while ($sw.Elapsed.TotalSeconds -lt $Seconds) {
  $t = $sw.Elapsed.TotalSeconds
  $ms = [uint32]$sw.ElapsedMilliseconds
  if ($t -lt ($Seconds * 0.55)) {
    # phase 1: everything sweeping slowly, so a moving value is obvious in a capture
    $a = 2 * [Math]::PI * $t / 5.0
    $lh = [int](800 * [Math]::Sin($a)); $lv = [int](800 * [Math]::Cos($a))
    $rh = [int](800 * [Math]::Sin(2 * $a)); $rv = [int](-800 * [Math]::Cos(2 * $a))
    $d0 = [int](600 * [Math]::Sin($a / 2)); $d1 = [int](600 * [Math]::Cos($a / 2))
    $five = 0; $fmode = 0; $buttons = [uint64]0
  }
  else {
    # phase 2: hold one recognisable pose (easy to read in netdump output)
    $lh = 800; $lv = -800; $rh = 400; $rv = 400
    $d0 = -600; $d1 = 600
    $five = 0; $fmode = -1; $buttons = [uint64](0x100 -bor 0x200)   # L1 | L2
  }
  $pkt = New-Packet $lh $lv $rh $rv $d0 $d1 $five $fmode $buttons $seq $ms
  try { [void]$udp.Send($pkt, 48, $ep); $sent++ } catch { }
  $seq++

  if ($Listen) {
    try {
      while ($udp.Available -gt 0) {
        $r = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, 0)
        $d = $udp.Receive([ref]$r)
        if ($d.Length -ge 8 -and $d[0] -eq 0x41 -and $d[1] -eq 0x43 -and $d[2] -eq 0x4B -and $d[3] -eq 0x31) {
          if ($acks -eq 0) { $firstAckMs = $sw.ElapsedMilliseconds; "first ACK after ${firstAckMs}ms from $($r.Address):$($r.Port)" }
          $acks++
        }
      }
    }
    catch { }
  }
  Start-Sleep -Milliseconds ([Math]::Max(1, [int](1000 / $Rate)))
}
$udp.Close()
"sent=$sent  acks=$acks"
if ($Listen -and $acks -eq 0) { "NO ACK - the radio is not listening on UDP $Port (rcbridge.conf missing/disabled) or the packets are rejected" }
