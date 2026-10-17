<#
.SYNOPSIS
    Echoes the app's latency probes back, so the radio can measure the round trip.

.DESCRIPTION
    The app sends, on its control port, a small datagram

        PROBE <seq> <microseconds-since-boot>\n

    and this script sends it straight back. The difference between the two
    timestamps is the round trip the radio otherwise cannot see: the app's own
    figures stop at sendto() and say nothing about the air, the switch and this
    machine.

    Only datagrams that start with PROBE are echoed. Everything else the app
    sends - the JSON control snapshots - is counted and dropped, which keeps a
    50 Hz control stream from being thrown back at the radio for no reason.

    Turn it on in the app first: settings page (the gear button on the firmware's
    screen) -> 回声测距. Without that the app never sends a probe and this script
    has nothing to do.

.PARAMETER Port
    The port to listen on. Must be the app's 控制端口 (JSON), NOT the module port:
    default 14560, and the app's own default.

.PARAMETER Seconds
    Stop after this many seconds. 0 (the default) runs until Ctrl-C.

.EXAMPLE
    .\tools\echo-udp.ps1
    .\tools\echo-udp.ps1 -Port 14560 -Seconds 60

.NOTES
    Windows Firewall asks the first time. Allow it for private networks, or the
    packets from the radio are dropped before they reach this process - the
    symptom is an app that says "往返: 没有回包" while the control stream is
    visibly arriving in tools\netdump.ps1.
#>
param(
    [int]$Port = 14560,
    [int]$Seconds = 0,
    [string]$Bind = "0.0.0.0"
)

$ErrorActionPreference = "Stop"

$endpoint = [System.Net.IPEndPoint]::new([System.Net.IPAddress]::Parse($Bind), $Port)
$socket = [System.Net.Sockets.UdpClient]::new($endpoint)

Write-Host "echo-udp: listening on $Bind`:$Port (UDP) - Ctrl-C to stop"
Write-Host "echo-udp: the app must have 回声测距 on, and the same port as 控制端口"

$received = 0
$probes = 0
$bytes = 0
$lastReport = [DateTime]::UtcNow
$stopAt = if ($Seconds -gt 0) { [DateTime]::UtcNow.AddSeconds($Seconds) } else { [DateTime]::MaxValue }

try {
    while ([DateTime]::UtcNow -lt $stopAt) {
        # A receive timeout rather than a blocking read, so the deadline and the
        # periodic report are honoured without a second thread.
        $socket.Client.ReceiveTimeout = 500
        try {
            $remote = [System.Net.IPEndPoint]::new([System.Net.IPAddress]::Any, 0)
            $data = $socket.Receive([ref]$remote)
        } catch [System.Net.Sockets.SocketException] {
            # TimedOut: nothing arrived in this window. Everything else is a real
            # error and worth seeing.
            if ($_.Exception.SocketErrorCode -ne [System.Net.Sockets.SocketError]::TimedOut) {
                throw
            }
            $data = $null
        }

        if ($null -ne $data -and $data.Length -gt 0) {
            $received++
            $bytes += $data.Length

            # "PROBE " is six bytes; compare them without turning the payload into
            # a string, because the JSON snapshots are not text-safe to decode on
            # every packet.
            $isProbe = $data.Length -gt 6 -and
                $data[0] -eq 0x50 -and $data[1] -eq 0x52 -and $data[2] -eq 0x4F -and
                $data[3] -eq 0x42 -and $data[4] -eq 0x45 -and $data[5] -eq 0x20

            if ($isProbe) {
                try {
                    $null = $socket.Send($data, $data.Length, $remote)
                    $probes++
                } catch {
                    Write-Host ("echo-udp: could not reply to {0}: {1}" -f $remote, $_.Exception.Message)
                }
            }
        }

        $now = [DateTime]::UtcNow
        if (($now - $lastReport).TotalSeconds -ge 1) {
            Write-Host ("echo-udp: {0} 包 / {1} KB, 回弹 {2} 个探针" -f `
                $received, [math]::Round($bytes / 1024, 1), $probes)
            $lastReport = $now
        }
    }
} finally {
    $socket.Close()
    Write-Host ("echo-udp: stopped. {0} 包 / {1} KB, 回弹 {2} 个探针" -f `
        $received, [math]::Round($bytes / 1024, 1), $probes)
}
