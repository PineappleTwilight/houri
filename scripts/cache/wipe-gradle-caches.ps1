<#
.SYNOPSIS
  Wipes ALL Gradle caches that can hold a stale CMake/ExternalProject hash.

.DESCRIPTION
  Use when wipe-imagedecoder-cache.ps1 is not enough (e.g. you changed
  gradle libs.versions.toml, AGP, or Kotlin and still get stale artifacts).

  Removes:
    - Project .gradle + .kotlin + .gradle-home
    - Module .gradle (external/*)
    - User home ~/.gradle/caches/* (build-cache, transforms, modules, jars)
    - User home ~/.gradle/configuration-cache
    - Daemon registry

  Does NOT touch .cxx or build/ — use wipe-all-caches.ps1 for that.

.NOTES
  Run from repo root: powershell -ExecutionPolicy Bypass -File scripts\cache\wipe-gradle-caches.ps1 [-DryRun]
#>
[CmdletBinding()]
param([switch]$DryRun, [switch]$NoDaemonStop)

$ErrorActionPreference = "SilentlyContinue"
$repoRoot = (Resolve-Path "$PSScriptRoot\..\..").Path
Write-Host "Repo: $repoRoot" -ForegroundColor Cyan

function Remove-Target($path, $label) {
  $full = if ([System.IO.Path]::IsPathRooted($path)) { $path } else { Join-Path $repoRoot $path }
  if (Test-Path $full) {
    Write-Host "  [REMOVE] $label -> $path" -ForegroundColor Yellow
    if (-not $DryRun) { Remove-Item -Recurse -Force -LiteralPath $full }
  } else { Write-Host "  [SKIP] $label" -ForegroundColor DarkGray }
}
function Remove-HomeCache($pattern, $label) {
  $base = "$env:USERPROFILE\.gradle\caches"
  if (-not (Test-Path $base)) { return }
  Get-ChildItem -Path $base -Filter $pattern -Directory -ErrorAction SilentlyContinue | ForEach-Object {
    Write-Host "  [REMOVE] $label -> $($_.FullName)" -ForegroundColor Yellow
    if (-not $DryRun) { Remove-Item -Recurse -Force -LiteralPath $_.FullName }
  }
}

if (-not $NoDaemonStop) {
  Write-Host "`nStopping Gradle daemon..." -ForegroundColor Cyan
  if (-not $DryRun) { try { & "$repoRoot\gradlew.bat" --stop 2>$null | Out-Null } catch {} }
  else { Write-Host "  [DryRun] gradlew.bat --stop" -ForegroundColor DarkGray }
}

Write-Host "`nWiping project Gradle caches..." -ForegroundColor Cyan
@(".gradle", ".kotlin", ".gradle-home", "build", "external\imagedecoder-houri\.gradle", "external\imagedecoder-houri\build", "external\yakuyomi-engine\.gradle", "external\webgpuviewer-houri\.gradle") | ForEach-Object { Remove-Target $_ $_ }

Write-Host "`nWiping user home Gradle caches..." -ForegroundColor Cyan
Remove-Target "$env:USERPROFILE\.gradle\caches\build-cache-1" "build-cache-1"
Remove-Target "$env:USERPROFILE\.gradle\caches\configuration-cache" "configuration-cache (global)"
Remove-Target "$env:USERPROFILE\.gradle\daemon" "daemon registry"
Remove-HomeCache "transforms-*" "transforms"
Remove-HomeCache "8.*" "Gradle 8.x version cache"
Remove-HomeCache "9.*" "Gradle 9.x version cache"
Remove-HomeCache "modules-*" "modules"
Remove-HomeCache "jars-*" "jars"
Remove-HomeCache "build-cache-*" "build-cache"

Write-Host "`nDone. DryRun=$DryRun" -ForegroundColor Green
