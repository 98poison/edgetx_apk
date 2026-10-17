# Download the APK a finished GitHub Actions run produced and unpack it next to
# the Desktop, ready for tools\setup-radio.ps1 (which installs it).
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File tools\fetch-artifact.ps1 -Token ghp_xxx
#   ... -RunId 38908338829        (a specific run instead of the newest success)
#   ... -OutDir D:\somewhere
#
# Any token that can read the repository will do (Contents: read is enough); the
# artifact download URL is not reachable anonymously.

param(
    [Parameter(Mandatory = $true)][string]$Token,
    [string]$Owner = "98poison",
    [string]$Repo  = "edgetx_apk",
    [long]$RunId   = 0,
    [string]$OutDir = "$env:USERPROFILE\Desktop\edgetx-apk",
    [string]$Artifact = "edgetx-apk"
)

$ErrorActionPreference = "Stop"
$api = "https://api.github.com/repos/$Owner/$Repo"
$hdr = @{
    Authorization          = "Bearer $Token"
    Accept                 = "application/vnd.github+json"
    "X-GitHub-Api-Version" = "2022-11-28"
    "User-Agent"           = "edgetx-artifact-fetcher"
}

# ---- which run ------------------------------------------------------------
if ($RunId -eq 0) {
    Write-Host "looking for the newest successful run ..."
    $runs = Invoke-RestMethod "$api/actions/runs?per_page=20" -Headers $hdr
    $run  = $runs.workflow_runs |
        Where-Object { $_.status -eq "completed" -and $_.conclusion -eq "success" } |
        Sort-Object created_at -Descending | Select-Object -First 1
    if (-not $run) {
        Write-Host "no successful run yet. The newest ones:"
        $runs.workflow_runs | Select-Object -First 5 | ForEach-Object {
            Write-Host ("  #{0}  {1}/{2}  {3}  {4}" -f $_.run_number, $_.status, $_.conclusion, $_.created_at, $_.head_sha.Substring(0, 8))
        }
        throw "nothing to download"
    }
} else {
    $run = Invoke-RestMethod "$api/actions/runs/$RunId" -Headers $hdr
}
Write-Host ("run #{0}  {1}/{2}  {3}  head={4}" -f $run.run_number, $run.status, $run.conclusion, $run.created_at, $run.head_sha.Substring(0, 8))
if ($run.conclusion -ne "success") { throw "run #$($run.run_number) is $($run.conclusion), not success" }

# ---- which artifact -------------------------------------------------------
$arts = Invoke-RestMethod "$api/actions/runs/$($run.id)/artifacts" -Headers $hdr
$art  = $arts.artifacts | Where-Object { $_.name -eq $Artifact } | Select-Object -First 1
if (-not $art) {
    Write-Host "artifacts in that run: $(($arts.artifacts | ForEach-Object { $_.name }) -join ', ')"
    throw "no artifact named '$Artifact' in run #$($run.run_number)"
}
Write-Host ("artifact '{0}'  {1:N1} MB  (expires {2:yyyy-MM-dd})" -f $art.name, ($art.size_in_bytes / 1MB), [datetime]$art.expires_at)

# ---- download -------------------------------------------------------------
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$zip = Join-Path $env:TEMP "edgetx-apk-$($run.id).zip"
if (Test-Path $zip) { Remove-Item $zip -Force }

# curl.exe follows the redirect to the signed blob URL; the Authorization
# header is what makes the API hand out that URL in the first place.
$code = (& curl.exe -s -L -w "%{http_code}" -H "Authorization: Bearer $Token" `
            -H "Accept: application/vnd.github+json" -H "User-Agent: edgetx-artifact-fetcher" `
            -o $zip "$api/actions/artifacts/$($art.id)/zip").Trim()
if ($code -ne "200") { throw "artifact download failed: HTTP $code" }

Write-Host ("downloaded {0:N1} MB -> {1}" -f ((Get-Item $zip).Length / 1MB), $zip)

# ---- unpack ---------------------------------------------------------------
Expand-Archive -Path $zip -DestinationPath $OutDir -Force
$apk = Get-ChildItem $OutDir -Filter *.apk -File | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $apk) { throw "no .apk inside the artifact zip" }

Write-Host ""
Write-Host ("APK : {0}" -f $apk.FullName) -ForegroundColor Green
Write-Host ("size: {0:N1} MB   built {1:yyyy-MM-dd HH:mm}" -f ($apk.Length / 1MB), $apk.LastWriteTime)
Write-Host ""
Write-Host "install it on the radio and point the network output at this PC:"
Write-Host "  powershell -ExecutionPolicy Bypass -File tools\setup-radio.ps1" -ForegroundColor Gray
