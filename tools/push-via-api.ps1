# Upload the files the GitHub web UI cannot take (dot-prefixed paths) straight
# through the REST API. Uses the Git Data API, so the 10.5 MB simulator library
# goes up as a single blob and everything lands in one commit.
#
# Why: the browser drag-and-drop uploader silently skips hidden entries, so
# .github/workflows/build-apk.yml and a .edgetx/ library can never be uploaded
# that way. This endpoint does not care what the path starts with.
#
# You need a token with permission to write contents of the repository:
#   github.com -> Settings -> Developer settings -> Personal access tokens ->
#   Fine-grained tokens -> Generate new token
#     Repository access : Only select repositories -> <your repo>
#     Permissions       : Contents  = Read and write
#                         Workflows = Read and write    <- REQUIRED for the
#                         .github/workflows/ file, GitHub refuses without it.
#     Expiration        : 7 days (revoke it as soon as this has run)
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File tools\push-via-api.ps1 -Token ghp_xxx
#   powershell -ExecutionPolicy Bypass -File tools\push-via-api.ps1 -Token ghp_xxx `
#       -Owner 98poison -Repo edgetx_apk -Branch main -Root ..\upload2
#
# Blobs are content addressed, so a second run re-uses whatever is already on
# the server instead of re-uploading 11 MB (the script prints "reuse" for those).
# It prints the resulting commit and then re-reads the tree to prove the files
# are there.

param(
    [Parameter(Mandatory = $true)][string]$Token,
    [string]$Owner  = "98poison",
    [string]$Repo   = "edgetx_apk",
    [string]$Branch = "main",
    # Directory holding the staged files (build-apk.yml + edgetx-lib\...).
    [string]$Root   = "..\upload2"
)

$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$api     = "https://api.github.com/repos/$Owner/$Repo"
$headers = @{
    Authorization          = "Bearer $Token"
    Accept                 = "application/vnd.github+json"
    "X-GitHub-Api-Version" = "2022-11-28"
    "User-Agent"           = "edgetx-apk-uploader"
}

function Invoke-GitHub($method, $url, $body) {
    $p = @{ Method = $method; Uri = $url; Headers = $headers }
    if ($null -ne $body) {
        $p.Body        = ($body | ConvertTo-Json -Depth 12 -Compress)
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

function Must($result, $what) {
    if (-not $result.ok) {
        throw ("{0} failed: HTTP {1}`n{2}" -f $what, $result.status, $result.text)
    }
    return $result.json
}

# Git object names are content addressed: sha1("blob <len>\0" + bytes). If the
# server already has that object we can skip the upload entirely.
function Get-GitBlobSha([string]$path) {
    $bytes  = [IO.File]::ReadAllBytes($path)
    $header = [Text.Encoding]::ASCII.GetBytes("blob $($bytes.Length)" + [char]0)
    $sha    = [Security.Cryptography.SHA1]::Create()
    $sha.TransformBlock($header, 0, $header.Length, $header, 0) | Out-Null
    $null = $sha.TransformFinalBlock($bytes, 0, $bytes.Length)
    return (($sha.Hash | ForEach-Object { $_.ToString("x2") }) -join "")
}

# ---- what to upload ------------------------------------------------------
# Everything staged under $Root goes up, at the same relative path, so the way
# to change one file is to copy it into $Root with that path and re-run. Only
# files whose blob sha changed are actually uploaded (see step 2), so a re-run
# costs nothing.
$rootAbs = (Resolve-Path $Root).Path.TrimEnd('\', '/')
$files = @(
    Get-ChildItem $rootAbs -Recurse -File |
        Sort-Object FullName |
        ForEach-Object {
            @{ path  = ($_.FullName.Substring($rootAbs.Length).TrimStart('\', '/') -replace '\\', '/')
               local = $_.FullName }
        }
)
if (-not $files) { throw "nothing to upload: no files found under $Root" }

Write-Host "to $Owner/$Repo@$Branch"
foreach ($f in $files) {
    Write-Host ("  {0,10:N0} bytes  {1}" -f (Get-Item $f.local).Length, $f.path)
}

# ---- 1. current commit of the branch -------------------------------------
$ref      = Must (Invoke-GitHub GET "$api/git/ref/heads/$Branch" $null) "reading branch $Branch"
$parent   = $ref.object.sha
$commit   = Must (Invoke-GitHub GET "$api/git/commits/$parent" $null)     "reading commit $parent"
$baseTree = $commit.tree.sha
Write-Host "`nparent commit : $parent"

# ---- 2. one blob per file (re-using objects that already exist) ----------
# Read the commit's tree once: it lists every path with its blob sha, so we can
# tell whether a file is already up there without downloading anything.
$treeNow = Must (Invoke-GitHub GET "$api/git/trees/$($baseTree)?recursive=1" $null) "reading the current tree"
$present = @{}
foreach ($e in $treeNow.tree) { if ($e.type -eq "blob") { $present[$e.path] = $e.sha } }
Write-Host "current tree  : $($present.Count) file(s)"

$entries = @()
foreach ($f in $files) {
    $sha = Get-GitBlobSha $f.local
    if ($present.ContainsKey($f.path) -and $present[$f.path] -eq $sha) {
        Write-Host ("blob {0}  reuse  {1}" -f $sha.Substring(0, 12), $f.path)
    } else {
        $blob = Must (Invoke-GitHub POST "$api/git/blobs" @{ content = [Convert]::ToBase64String([IO.File]::ReadAllBytes($f.local)); encoding = "base64" }) "uploading $($f.path)"
        $sha  = $blob.sha
        Write-Host ("blob {0}  sent   {1}" -f $sha.Substring(0, 12), $f.path)
    }
    $entries += @{ path = $f.path; mode = "100644"; type = "blob"; sha = $sha }
}

# ---- 3. tree -> commit -> move the branch --------------------------------
# GitHub refuses a tree that touches .github/workflows/ unless the token also
# carries the "Workflows" permission, so fall back to the rest of the files.
$dropped = @()
$tree    = Invoke-GitHub POST "$api/git/trees" @{ base_tree = $baseTree; tree = $entries }
if (-not $tree.ok) {
    Write-Host "`n[!] creating the tree failed: HTTP $($tree.status)"
    Write-Host $tree.text
    $wf   = @($entries | Where-Object { $_.path -like ".github/workflows/*" })
    $rest = @($entries | Where-Object { $_.path -notlike ".github/workflows/*" })
    if ($wf.Count -gt 0 -and $rest.Count -gt 0) {
        Write-Host "`n[!] retrying without the workflow file ..."
        $retry = Invoke-GitHub POST "$api/git/trees" @{ base_tree = $baseTree; tree = $rest }
        if ($retry.ok) { $tree = $retry; $dropped = $wf }
    }
}
$newTree = Must $tree "creating the tree"
if ($dropped.Count -gt 0) {
    Write-Host ""
    Write-Host "!! the workflow file was left out: $($dropped.path -join ', ')" -ForegroundColor Yellow
    Write-Host "!! the token needs the Workflows permission, see the header of this script." -ForegroundColor Yellow
}

$list = $files | ForEach-Object { $_.path }
$newCommit = Must (Invoke-GitHub POST "$api/git/commits" @{
    message = "Upload via the API: " + (($list -join ", ") -replace '^(.{180}).*', '$1 ...')
    tree    = $newTree.sha
    parents = @($parent)
}) "creating the commit"
Must (Invoke-GitHub PATCH "$api/git/refs/heads/$Branch" @{ sha = $newCommit.sha }) "moving $Branch" | Out-Null
Write-Host "`nnew commit    : $($newCommit.sha)"

# ---- 4. prove it ---------------------------------------------------------
$after = Must (Invoke-GitHub GET "$api/git/trees/$($newCommit.sha)?recursive=1" $null) "reading the new tree"
$blobs = @($after.tree | Where-Object { $_.type -eq "blob" })
Write-Host "`nfiles in the repository now: $($blobs.Count)"
foreach ($f in $files) {
    $hit = $blobs | Where-Object { $_.path -eq $f.path } | Select-Object -First 1
    Write-Host ("  [{0}] {1}" -f $(if ($hit) { "ok " } else { "MISS" }), $f.path)
}
if ($dropped.Count -gt 0) { exit 1 }
