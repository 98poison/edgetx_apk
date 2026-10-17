# Trigger the "Build APK" workflow without the Actions web page, then follow it
# and pull the APK down.
#
# Why: github.com is not reliably reachable here, but api.github.com is. The
# workflow accepts a repository_dispatch event (see its `on:` block), and that
# endpoint only needs a token with Contents: write - the same token that
# uploaded the files. No Actions permission, no browser.
#
# Usage:
#   $t = Get-Content "$env:USERPROFILE\Desktop\gh_token.txt" -Raw
#   powershell -ExecutionPolicy Bypass -File tools\dispatch-build.ps1 -Token $t
#
# Options:
#   -Mode source           build EdgeTX from the fork instead of the committed .so
#   -DjiMsdk true -DjiAppKey <key>
#                          package the DJI Mobile SDK, as the installed APK has it
#   -NoWait                only fire the event and print the run URL
#   -TimeoutMinutes 180    give up watching after this long
#
# A successful run leaves edgetx-apk.zip (containing app-release.apk) on the
# Desktop.

param(
    [Parameter(Mandatory = $true)][string]$Token,
    [string]$Owner        = "98poison",
    [string]$Repo         = "edgetx_apk",
    [string]$EventType    = "build-apk",
    [string]$Mode         = "prebuilt",
    # The DJI Mobile SDK is what the RC Plus 2's 5-way switch, its scroll wheel and
    # its record button come from, and the App Key is bound to the package name and
    # the signing certificate. Without these two the dispatch builds an APK with no
    # SDK in it at all (the workflow defaults both to empty), which is not the build
    # this radio runs; pass them to reproduce the installed one.
    [string]$DjiMsdk      = "",
    [string]$DjiAppKey    = "",
    [string]$OutDir       = "$env:USERPROFILE\Desktop",
    [int]$TimeoutMinutes  = 180,
    [switch]$NoWait
)

$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$api = "https://api.github.com/repos/$Owner/$Repo"
$headers = @{
    Authorization          = "Bearer $Token"
    Accept                 = "application/vnd.github+json"
    "X-GitHub-Api-Version" = "2022-11-28"
    "User-Agent"           = "edgetx-apk-dispatch"
}

function Invoke-Api($method, $url, $body) {
    $p = @{ Method = $method; Uri = $url; Headers = $headers }
    if ($null -ne $body) {
        $p.Body        = ($body | ConvertTo-Json -Depth 6 -Compress)
        $p.ContentType = "application/json; charset=utf-8"
    }
    try {
        return @{ ok = $true; status = 200; json = (Invoke-RestMethod @p); text = "" }
    } catch {
        $resp = $_.Exception.Response
        $code = 0
        if ($resp) { $code = [int]$resp.StatusCode }
        $text = $_.ErrorDetails.Message
        if (-not $text -and $resp) {
            try { $text = (New-Object IO.StreamReader($resp.GetResponseStream())).ReadToEnd() } catch {}
        }
        return @{ ok = $false; status = $code; json = $null; text = $text }
    }
}

function Hhmmss([TimeSpan]$t) { "{0:hh\:mm\:ss}" -f $t }

$started = Get-Date
$deadline = $started.AddMinutes($TimeoutMinutes)

# ---- 1. fire the event ---------------------------------------------------
$payload = @{ edgetx_mode = $Mode }
if ($DjiMsdk -ne "")   { $payload["dji_msdk"]    = $DjiMsdk }
if ($DjiAppKey -ne "") { $payload["dji_app_key"] = $DjiAppKey }

Write-Host "dispatching '$EventType' (edgetx_mode=$Mode) to $Owner/$Repo ..."
$r = Invoke-Api POST "$api/dispatches" @{
    event_type     = $EventType
    client_payload = $payload
}
if (-not $r.ok) {
    Write-Host ""
    Write-Host "dispatch failed: HTTP $($r.status)" -ForegroundColor Red
    Write-Host $r.text
    if ($r.status -eq 403) {
        Write-Host ""
        Write-Host "403 here is usually a token permission, not a network problem." -ForegroundColor Yellow
    }
    exit 1
}
Write-Host "accepted (HTTP 204). GitHub queues it within a few seconds."
if ($NoWait) { exit 0 }

# ---- 2. wait for the run to appear --------------------------------------
# A dispatched run's event is "repository_dispatch", not the dispatch type we
# sent ("build-apk"), so filtering the list by $EventType finds nothing at all
# and the wait below would time out on a build that is running perfectly well.
$runEvent = "repository_dispatch"
$run = $null
Write-Host "`nlooking for the run "
while (-not $run -and (Get-Date) -lt $started.AddMinutes(5)) {
    Start-Sleep -Seconds 5
    $list = Invoke-Api GET "$api/actions/runs?event=$runEvent&per_page=10" $null
    if (-not $list.ok) { Write-Host "  (query failed: HTTP $($list.status))"; continue }
    $run = $list.json.workflow_runs |
        Where-Object { [datetime]$_.created_at -ge $started.AddSeconds(-30) } |
        Sort-Object created_at -Descending | Select-Object -First 1
    Write-Host ("  {0}  elapsed {1}" -f $(if ($run) { "found" } else { "waiting" }), (Hhmmss ((Get-Date) - $started)))
}
if (-not $run) {
    Write-Host "no run showed up - check https://github.com/$Owner/$Repo/actions" -ForegroundColor Yellow
    exit 1
}

Write-Host ""
Write-Host "run #$($run.run_number)  id $($run.id)"
Write-Host "page : $($run.html_url)"
Write-Host ""

# ---- 3. follow it --------------------------------------------------------
$last = ""
while ($true) {
    $cur = Invoke-Api GET "$api/actions/runs/$($run.id)" $null
    if ($cur.ok) {
        $state = "$($cur.json.status) / $($cur.json.conclusion)"
        if ($state -ne $last) {
            Write-Host ("[{0}] {1}" -f (Hhmmss ((Get-Date) - $started)), $state)
            $last = $state
        }
        if ($cur.json.status -eq "completed") { $final = $cur.json; break }
    }
    if ((Get-Date) -gt $deadline) {
        Write-Host "`ngiving up watching after $TimeoutMinutes minute(s); it is still running:" -ForegroundColor Yellow
        Write-Host "  $($run.html_url)"
        exit 1
    }
    Start-Sleep -Seconds 20
}

$conclusion = $final.conclusion
Write-Host "`nfinished: $conclusion  (took $(Hhmmss ((Get-Date) - $started)))"
Write-Host $final.html_url

# ---- 4. the goods, or the reason ----------------------------------------
if ($conclusion -ne "success") {
    Write-Host "`n---- failed job logs ----" -ForegroundColor Red
    $jobs = Invoke-Api GET "$api/actions/runs/$($run.id)/jobs" $null
    if ($jobs.ok) {
        foreach ($job in $jobs.json.jobs) {
            Write-Host ("  {0}  {1}  {2}" -f $job.name, $job.status, $job.conclusion)
            $logZip = Join-Path $env:TEMP ("edgetx-log-{0}.zip" -f $job.id)
            $code = & curl.exe -s -L -o $logZip -w "%{http_code}" -H "Authorization: Bearer $Token" `
                "$api/actions/jobs/$($job.id)/logs"
            if ($code -eq "200" -and (Test-Path $logZip)) {
                Write-Host "    log -> $logZip   (Expand-Archive $logZip $env:TEMP\edgetx-log)"
            } else {
                Write-Host "    (could not fetch the log: HTTP $code)"
            }
        }
    } else {
        Write-Host "  (could not list the jobs: HTTP $($jobs.status))"
    }
    Write-Host "`nOpen the page above, or unzip the log and paste the first error to me." -ForegroundColor Yellow
    exit 1
}

$arts = Invoke-Api GET "$api/actions/runs/$($run.id)/artifacts" $null
if (-not $arts.ok -or $arts.json.total_count -eq 0) {
    Write-Host "`nno artifact found - download it from $($run.html_url)" -ForegroundColor Yellow
    exit 1
}

foreach ($art in $arts.json.artifacts) {
    $dest = Join-Path $OutDir "$($art.name).zip"
    Write-Host "`ndownloading $($art.name) ($([math]::Round($art.size_in_bytes/1MB,1)) MB) -> $dest"
    $code = & curl.exe -s -L -o $dest -w "%{http_code}" `
        -H "Authorization: Bearer $Token" "$api/actions/artifacts/$($art.id)/zip"
    if ($code -ne "200") {
        $code = & curl.exe -s -L -o $dest -w "%{http_code}" "$api/actions/artifacts/$($art.id)/zip"
    }
    if ($code -eq "200" -and (Test-Path $dest)) {
        Write-Host "  ok  $((Get-Item $dest).Length) bytes"
    } else {
        Write-Host "  download failed (HTTP $code) - grab it from $($run.html_url)" -ForegroundColor Yellow
        exit 1
    }
}

Write-Host "`ndone. Unzip it to get app-release.apk:" -ForegroundColor Green
Write-Host "  Expand-Archive '$OutDir\edgetx-apk.zip' -DestinationPath '$OutDir\edgetx-apk' -Force"
Write-Host "  dir '$OutDir\edgetx-apk'"
