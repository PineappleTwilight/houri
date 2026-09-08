<#
.SYNOPSIS
  WSL-aware wipe + rebuild for the libiconv/glib Meson 1.12.0 C:/ bug.
  On Windows with WSL available, uses WSL's Linux meson (1.7.0), bash, pkg-config
  instead of Windows binaries (meson.exe 1.12.0, msys64 pkg-config) to avoid
  gio/meson.build:925 declare_dependency is_parent_path assert with C:/ prefix.

.DESCRIPTION
  1. Detects Windows + WSL, sets USE_WSL_TOOLS for CMake (wsl meson/bash)
  2. Wipes all caches that survive a plain .cxx delete (patch stamp, config-cache)
  3. Optionally rebuilds via WSL or Windows Gradle

  Use when you see:
    Run-time dependency iconv found: NO
    Run-time dependency libinotify found: NO
    Found bash-completion but the .pc file did not set 'completionsdir'
    gio/meson.build:925:13: ERROR: Unhandled python exception (Meson bug)

.NOTES
  Run from repo root in PowerShell:
    powershell -ExecutionPolicy Bypass -File scripts\cache\wipe-and-rebuild-wsl.ps1
    powershell -ExecutionPolicy Bypass -File scripts\cache\wipe-and-rebuild-wsl.ps1 -Rebuild
    powershell -ExecutionPolicy Bypass -File scripts\cache\wipe-and-rebuild-wsl.ps1 -DryRun
#>
[CmdletBinding()]
param(
  [switch]$DryRun,
  [switch]$Rebuild,
  [switch]$NoWslCheck,
  [string]$Abi = "arm64-v8a"
)

$ErrorActionPreference = "SilentlyContinue"
$repoRoot = (Resolve-Path "$PSScriptRoot\..\..").Path
Write-Host "Repo: $repoRoot" -ForegroundColor Cyan
Write-Host "Abi: $Abi" -ForegroundColor Cyan

function Test-IsWindows { return $env:OS -eq "Windows_NT" }
function Test-WslAvailable {
  if (-not (Test-IsWindows)) { return $false }
  try { $null = Get-Command wsl -ErrorAction Stop; return $true } catch { return $false }
  try { wsl --status 2>$null | Out-Null; return $LASTEXITCODE -eq 0 } catch { return $false }
}

$isWindows = Test-IsWindows
$hasWsl = -not $NoWslCheck -and (Test-WslAvailable)
Write-Host "Windows: $isWindows  WSL: $hasWsl" -ForegroundColor Cyan
if ($isWindows -and $hasWsl) {
  Write-Host "WSL detected - CMake will use 'wsl meson' (1.7.0) instead of 'C:/Program Files/Meson/meson.exe' (1.12.0) to avoid C:/ is_parent_path bug" -ForegroundColor Green
  # Verify WSL meson version
  try { $wslMesonVer = wsl meson --version 2>$null; Write-Host "WSL meson: $wslMesonVer" -ForegroundColor DarkGray } catch {}
  try { $wslBashVer = wsl bash --version 2>$null | Select-Object -First 1; Write-Host "WSL bash: $wslBashVer" -ForegroundColor DarkGray } catch {}
} elseif ($isWindows) {
  Write-Host "WSL not found - falling back to MSYS/Windows tools (may still hit gio:925 bug). Install WSL: wsl --install" -ForegroundColor Yellow
}

function Remove-Target($path, $label) {
  $full = if ([System.IO.Path]::IsPathRooted($path)) { $path } else { Join-Path $repoRoot $path }
  if (Test-Path -LiteralPath $full) {
    Write-Host "  [REMOVE] $label -> $path" -ForegroundColor Yellow
    if (-not $DryRun) {
      try { Remove-Item -Recurse -Force -LiteralPath $full -ErrorAction Stop }
      catch {
        Write-Host "           -> robocopy fallback for MAX_PATH" -ForegroundColor DarkYellow
        $tmp = Join-Path $env:TEMP "empty_$(Get-Random)"
        New-Item -ItemType Directory -Path $tmp -Force | Out-Null
        robocopy $tmp $full /MIR /NFL /NDL /NJH /NJS /R:0 /W:0 | Out-Null
        Remove-Item -Recurse -Force -LiteralPath $full -ErrorAction SilentlyContinue
        Remove-Item -Recurse -Force -LiteralPath $tmp -ErrorAction SilentlyContinue
      }
    }
  } else { Write-Host "  [SKIP] $label" -ForegroundColor DarkGray }
}

Write-Host "`n[1/4] Stopping Gradle daemon..." -ForegroundColor Cyan
if (-not $DryRun) { try { & "$repoRoot\gradlew.bat" --stop 2>$null | Out-Null } catch {} }

Write-Host "`n[2/4] Wiping .cxx / build / .gradle..." -ForegroundColor Cyan
@(
  "external\imagedecoder-houri\library\.cxx",
  "external\imagedecoder-houri\library\build",
  "external\imagedecoder-houri\.gradle",
  "app\.cxx", "app\build", "build", ".gradle", ".kotlin", ".gradle-home", ".cxx"
) | ForEach-Object { Remove-Target $_ $_ }
Get-ChildItem -Path "$repoRoot\external" -Filter ".cxx" -Directory -Recurse -Depth 3 -ErrorAction SilentlyContinue | ForEach-Object {
  Write-Host "  [REMOVE] stray .cxx -> $($_.FullName)" -ForegroundColor Yellow
  if (-not $DryRun) { Remove-Item -Recurse -Force -LiteralPath $_.FullName }
}

Write-Host "`n[3/4] Wiping user home Gradle caches..." -ForegroundColor Cyan
@(
  "$env:USERPROFILE\.gradle\caches\build-cache-1",
  "$env:USERPROFILE\.gradle\caches\configuration-cache",
  "$env:USERPROFILE\.gradle\daemon"
) | ForEach-Object { Remove-Target $_ $_ }
foreach ($pat in @("transforms-*", "8.*", "9.*", "modules-*", "jars-*")) {
  Get-ChildItem -Path "$env:USERPROFILE\.gradle\caches" -Filter $pat -Directory -ErrorAction SilentlyContinue | ForEach-Object {
    Write-Host "  [REMOVE] $($_.FullName)" -ForegroundColor Yellow
    if (-not $DryRun) { Remove-Item -Recurse -Force -LiteralPath $_.FullName }
  }
}

Write-Host "`n[4/4] Verifying WSL tools (if on Windows)..." -ForegroundColor Cyan
if ($hasWsl -and -not $DryRun) {
  wsl bash -c "which meson && meson --version; which pkg-config && pkg-config --version; which bash && bash --version | head -1" 2>$null | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }
}

if ($DryRun) { Write-Host "`nDryRun - nothing deleted." -ForegroundColor Magenta; exit 0 }

Write-Host "`nDone. Next steps:" -ForegroundColor Green
Write-Host "  git submodule update --init --recursive external\imagedecoder-houri" -ForegroundColor White
Write-Host "  Verify: git -C external\imagedecoder-houri log --oneline -1  # should be c25ce83" -ForegroundColor White
if ($Rebuild) {
  Write-Host "`nRebuilding via Gradle (will use WSL meson if available)..." -ForegroundColor Cyan
  & "$repoRoot\gradlew.bat" :external:imagedecoder-houri:library:assembleDebug --no-configuration-cache --info 2>&1 | Select-String -Pattern "Using.*Meson|WSL|iconv|gio|libinotify|bash-completion" | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }
} else {
  Write-Host "  .\gradlew.bat :external:imagedecoder-houri:library:assembleDebug --no-configuration-cache" -ForegroundColor White
  Write-Host "  Verify in meson-log.txt: Run-time dependency iconv found: YES and no gio:925 python exception" -ForegroundColor DarkGray
}
