# Parallel-segment download of a GitHub Actions artifact (single-connection is often throttled to ~30 KB/s)
param(
  [Parameter(Mandatory = $true)][string]$RunId,
  [string]$OutZip = "$env:USERPROFILE\Desktop\edgetx-apk\fastfetch.zip",
  [int]$Connections = 8
)
$ErrorActionPreference = "Stop"
$tok = (Get-Content "$env:USERPROFILE\Desktop\gh_token.txt" -Raw).Trim()
$h = @{
  Authorization          = "Bearer $tok"
  Accept                 = "application/vnd.github+json"
  "X-GitHub-Api-Version" = "2022-11-28"
  "User-Agent"           = "edgetx"
}
$api = "https://api.github.com/repos/98poison/edgetx_apk"

Get-Process curl -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 2

$arts = Invoke-RestMethod -Uri "$api/actions/runs/$RunId/artifacts" -Headers $h
$art = $arts.artifacts | Where-Object { $_.name -eq 'edgetx-apk' } | Select-Object -First 1
if (-not $art) { throw "artifact edgetx-apk not found for run $RunId" }
$total = [int64]$art.size_in_bytes
Write-Host ("target size: {0:N2} MB" -f ($total / 1MB))

$signed = (& curl.exe -s -L -r 0-0 -o NUL -w "%{url_effective}" -H "Authorization: Bearer $tok" -H "User-Agent: edgetx" $art.archive_download_url | Out-String).Trim()
if ($signed -notmatch '^https://') { throw "could not get signed url: $signed" }
Write-Host ("signed url ok: {0}..." -f $signed.Substring(0, [Math]::Min(80, $signed.Length)))

$dir = Split-Path $OutZip -Parent
New-Item -ItemType Directory -Force -Path $dir | Out-Null
Remove-Item $OutZip -Force -ErrorAction SilentlyContinue

$chunk = [int64][Math]::Ceiling($total / $Connections)
$parts = @()
$procs = @()
for ($i = 0; $i -lt $Connections; $i++) {
  $st = [int64]($i * $chunk)
  $en = [int64][Math]::Min($total - 1, ($i + 1) * $chunk - 1)
  if ($st -gt $en) { continue }
  $f = "$OutZip.part$i"
  Remove-Item $f -Force -ErrorAction SilentlyContinue
  $parts += [pscustomobject]@{ Start = $st; End = $en; File = $f; Want = [int64]($en - $st + 1) }
  $procs += Start-Process -FilePath "curl.exe" -PassThru -NoNewWindow -ArgumentList @(
    '-s', '-L', '--retry', '8', '--retry-delay', '3', '--retry-all-errors', '--connect-timeout', '20',
    '-r', "$st-$en", '-o', $f, $signed)
}
Write-Host ("started {0} parallel connections for {1:N2} MB ..." -f $procs.Count, ($total / 1MB))
$sw = [Diagnostics.Stopwatch]::StartNew()
$procs | Wait-Process
$sw.Stop()

foreach ($p in $parts) {
  if (-not (Test-Path $p.File)) { throw "missing part: $($p.File)" }
  $got = (Get-Item $p.File).Length
  if ($got -ne $p.Want) { throw ("part size mismatch: {0} got {1} want {2}" -f $p.File, $got, $p.Want) }
}
$kbs = ($total / 1KB) / [Math]::Max(0.1, $sw.Elapsed.TotalSeconds)
Write-Host ("all parts done in {0:N1}s -> avg {1:N0} KB/s" -f $sw.Elapsed.TotalSeconds, $kbs)

$fs = [IO.File]::Create($OutZip)
try {
  foreach ($p in ($parts | Sort-Object Start)) {
    $in = [IO.File]::OpenRead($p.File)
    try { $in.CopyTo($fs) } finally { $in.Dispose() }
  }
} finally { $fs.Dispose() }

$finalSize = (Get-Item $OutZip).Length
Write-Host ("merged: {0:N2} MB (expected {1:N2} MB)" -f ($finalSize / 1MB), ($total / 1MB))
if ($finalSize -ne $total) { throw "merged size mismatch" }
$parts | ForEach-Object { Remove-Item $_.File -Force -ErrorAction SilentlyContinue }
Write-Host "DOWNLOAD OK -> $OutZip"
