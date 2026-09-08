<#
.SYNOPSIS
  Nuclear wipe — everything that can hold a stale CMake/ExternalProject/Gradle hash.

.DESCRIPTION
  Combines wipe-imagedecoder-cache.ps1 + wipe-gradle-caches.ps1 + additional
  .cxx/build/.kotlin that survive a normal Clean Project.

  Safe to run repeatedly (idempotent, -ErrorAction SilentlyContinue).
  Handles MAX_PATH (260) by using literal paths and robocopy fallback.

  Order matters: daemon first, then .cxx, then .gradle, then home caches.

.NOTES
  Run from repo root: powershell -ExecutionPolicy Bypass -File scripts\cache\wipe-all-caches.ps1
  Preview:           powershell -File scripts\cache\wipe-all-caches.ps1 -DryRun
  Keep daemon:       -NoDaemonStop
#>
[CmdletBinding()]
param([switch]$DryRun, [switch]$NoDaemonStop, [switch]$NoConfirm)

$ErrorActionPreference = "SilentlyContinue"
$repoRoot = (Resolve-Path "$PSScriptRoot\..\..").Path
Write-Host "==============================================" -ForegroundColor Cyan
Write-Host "  Wipe ALL caches (nuclear) - DryRun=$DryRun" -ForegroundColor Cyan
Write-Host "  Repo: $repoRoot" -ForegroundColor Cyan
Write-Host "==============================================" -ForegroundColor Cyan

if (-not $NoConfirm -and -not $DryRun) {
  Write-Host "`nThis will delete .cxx, build, .gradle, and home Gradle caches." -ForegroundColor Red
  $a = Read-Host "Type YES to continue"
  if ($a -ne "YES") { Write-Host "Aborted." -ForegroundColor Yellow; exit 1 }
}

function Remove-Target($path, $label) {
  $full = if ([System.IO.Path]::IsPathRooted($path)) { $path } else { Join-Path $repoRoot $path }
  $exists = Test-Path -LiteralPath $full
  if ($exists) {
    Write-Host "  [REMOVE] $label" -ForegroundColor Yellow
    Write-Host "           $full" -ForegroundColor DarkGray
    if (-not $DryRun) {
      # Try Remove-Item, fallback to robocopy empty dir for MAX_PATH
      try { Remove-Item -Recurse -Force -LiteralPath $full -ErrorAction Stop }
      catch {
        Write-Host "           -> fallback to robocopy purge..." -ForegroundColor DarkYellow
        $tmp = Join-Path $env:TEMP "empty_$(Get-Random)"
        New-Item -ItemType Directory -Path $tmp -Force | Out-Null
        robocopy $tmp $full /MIR /NFL /NDL /NJH /NJS /R:0 /W:0 | Out-Null
        Remove-Item -Recurse -Force -LiteralPath $full -ErrorAction SilentlyContinue
        Remove-Item -Recurse -Force -LiteralPath $tmp -ErrorAction SilentlyContinue
      }
    }
  } else {
    Write-Host "  [SKIP] $label (not found)" -ForegroundColor DarkGray
  }
}

if (-not $NoDaemonStop) {
  Write-Host "`n[1/4] Stopping Gradle daemon..." -ForegroundColor Cyan
  if (-not $DryRun) {
    try { & "$repoRoot\gradlew.bat" --stop 2>$null | Out-Null; Write-Host "        gradlew --stop done" -ForegroundColor Green } catch { Write-Host "        no daemon or gradlew failed" -ForegroundColor DarkGray }
  } else { Write-Host "  [DryRun] gradlew.bat --stop" -ForegroundColor DarkGray }
}

Write-Host "`n[2/4] Wiping project .cxx / build / .gradle..." -ForegroundColor Cyan
@(
  "external\imagedecoder-houri\library\.cxx",
  "external\imagedecoder-houri\library\build",
  "external\imagedecoder-houri\.cxx",
  "external\imagedecoder-houri\build",
  "external\yakuyomi-engine\.cxx",
  "external\webgpuviewer-houri\.cxx",
  "app\.cxx",
  "app\build",
  "build",
  ".gradle",
  ".kotlin",
  ".gradle-home",
  ".cxx",
  "yakuyomi-engine\.cxx"
) | ForEach-Object { Remove-Target $_ $_ }

# Also wipe any stray .cxx under external (catches future modules)
Get-ChildItem -Path "$repoRoot\external" -Filter ".cxx" -Directory -Recurse -Depth 3 -ErrorAction SilentlyContinue | ForEach-Object {
  Write-Host "  [REMOVE] stray .cxx -> $($_.FullName)" -ForegroundColor Yellow
  if (-not $DryRun) { Remove-Item -Recurse -Force -LiteralPath $_.FullName }
}
Get-ChildItem -Path "$repoRoot" -Filter ".cxx" -Directory -Recurse -Depth 2 -ErrorAction SilentlyContinue | Where-Object { $_.FullName -notlike "*\.git*" } | ForEach-Object {
  if ($_.FullName -like "*\external\imagedecoder*") { return } # already handled
  Write-Host "  [REMOVE] stray .cxx -> $($_.FullName)" -ForegroundColor Yellow
  if (-not $DryRun) { Remove-Item -Recurse -Force -LiteralPath $_.FullName }
}

Write-Host "`n[3/4] Wiping module .gradle..." -ForegroundColor Cyan
Get-ChildItem -Path "$repoRoot" -Filter ".gradle" -Directory -Recurse -Depth 3 -ErrorAction SilentlyContinue | Where-Object { $_.FullName -notlike "*\.git*" } | ForEach-Object {
  Write-Host "  [REMOVE] $($_.FullName)" -ForegroundColor Yellow
  if (-not $DryRun) { Remove-Item -Recurse -Force -LiteralPath $_.FullName }
}

Write-Host "`n[4/4] Wiping user home Gradle caches..." -ForegroundColor Cyan
$homeRoot = "$env:USERPROFILE\.gradle"
Remove-Target "$homeRoot\daemon" "daemon registry"
Remove-Target "$homeRoot\caches\build-cache-1" "build-cache-1"
Remove-Target "$homeRoot\caches\configuration-cache" "configuration-cache"
# Versioned caches (8.x, 9.x) — wildcard delete
foreach ($pat in @("transforms-*", "8.*", "9.*", "modules-*", "jars-*", "build-cache-*", "journal-*")) {
  $base = "$homeRoot\caches"
  if (Test-Path $base) {
    Get-ChildItem -Path $base -Filter $pat -Directory -ErrorAction SilentlyContinue | ForEach-Object {
      Write-Host "  [REMOVE] $($_.FullName)" -ForegroundColor Yellow
      if (-not $DryRun) { Remove-Item -Recurse -Force -LiteralPath $_.FullName }
    }
  }
}

Write-Host "`n==============================================" -ForegroundColor Cyan
if ($DryRun) { Write-Host "DryRun complete — nothing deleted. Re-run without -DryRun to wipe." -ForegroundColor Magenta }
else { Write-Host "Done. Next: Sync in Android Studio or .\gradlew.bat assembleDebug --no-configuration-cache" -ForegroundColor Green; Write-Host "Verify: .cxx/.../fk/src/ep_libiconv/srclib/fcntl.c starts with #ifndef F_SETFD and Makefile has '# cd src &&'" -ForegroundColor DarkGray }
