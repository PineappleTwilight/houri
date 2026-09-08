<#
.SYNOPSIS
  Targeted wipe for the libiconv / imagedecoder-houri caching issue.
  Removes only the ExternalProject + Gradle configuration-cache that survives
  a normal `rm -rf .cxx` (patch stamp + host mis-detection).

.DESCRIPTION
  Use when you hit:
    FAILED: fk/src/ep_libiconv-stamp/ep_libiconv-build
    host=x86_64 instead of aarch64, duplicate case F_SETFD, or rpl_* link errors
  after a supposedly-fixed commit.

  This is the minimal wipe that forces ExternalProject to re-run:
    PATCH_COMMAND (patch_iconv.sh) + CONFIGURE (configure_iconv.sh) with correct
    /c/ vs /mnt/c/ handling and URL_HASH invalidation.

.NOTES
  Run from repo root:  powershell -ExecutionPolicy Bypass -File scripts\cache\wipe-imagedecoder-cache.ps1
  Or with -DryRun to preview.
#>
[CmdletBinding()]
param(
  [switch]$DryRun,
  [switch]$NoDaemonStop
)

$ErrorActionPreference = "SilentlyContinue"
$repoRoot = (Resolve-Path "$PSScriptRoot\..\..").Path
Write-Host "Repo: $repoRoot" -ForegroundColor Cyan

function Remove-Target($path, $label) {
  $full = Join-Path $repoRoot $path
  if (Test-Path $full) {
    Write-Host "  [REMOVE] $label -> $path" -ForegroundColor Yellow
    if (-not $DryRun) { Remove-Item -Recurse -Force -LiteralPath $full }
  } else {
    Write-Host "  [SKIP]   $label (not found)" -ForegroundColor DarkGray
  }
}

if (-not $NoDaemonStop) {
  Write-Host "`nStopping Gradle daemon (holds config-cache)..." -ForegroundColor Cyan
  if (-not $DryRun) {
    try { & "$repoRoot\gradlew.bat" --stop 2>$null | Out-Null } catch {}
    # Fallback: kill any leftover daemons
    Get-Process -Name "java" -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -like "*GradleDaemon*" } | Stop-Process -Force -ErrorAction SilentlyContinue
  } else { Write-Host "  [DryRun] would run: gradlew.bat --stop" -ForegroundColor DarkGray }
}

Write-Host "`nWiping imagedecoder caches..." -ForegroundColor Cyan
Remove-Target "external\imagedecoder-houri\library\.cxx" "ExternalProject .cxx (ep_libiconv src + stamps)"
Remove-Target "external\imagedecoder-houri\library\build" "library build/"
Remove-Target ".gradle\configuration-cache" "Gradle configuration-cache (holds CMakeLists hash)"
Remove-Target "external\imagedecoder-houri\.gradle" "Module .gradle"
Remove-Target ".cxx" "Root .cxx (if any)"

Write-Host "`nWiping user home Gradle build-cache (versioned)..." -ForegroundColor Cyan
$homeCaches = @(
  "$env:USERPROFILE\.gradle\caches\build-cache-1",
  "$env:USERPROFILE\.gradle\caches\transforms-1",
  "$env:USERPROFILE\.gradle\caches\transforms-4",
  "$env:USERPROFILE\.gradle\caches\8.7",
  "$env:USERPROFILE\.gradle\caches\8.14.3",
  "$env:USERPROFILE\.gradle\caches\9.3.1",
  "$env:USERPROFILE\.gradle\caches\9.5.1",
  "$env:USERPROFILE\.gradle\caches\9.7.1"
)
foreach ($c in $homeCaches) {
  if (Test-Path $c) {
    Write-Host "  [REMOVE] $c" -ForegroundColor Yellow
    if (-not $DryRun) { Remove-Item -Recurse -Force -LiteralPath $c }
  }
}

Write-Host "`nDone. Next: Android Studio -> Sync, or powershell: .\gradlew.bat :external:imagedecoder-houri:library:assembleDebug --no-configuration-cache" -ForegroundColor Green
if ($DryRun) { Write-Host "DryRun: nothing was deleted." -ForegroundColor Magenta }
