<#
.SYNOPSIS
  Repo-root PowerShell wrapper for WSL tests.

.DESCRIPTION
  Delegates to `external/imagedecoder-houri/tests/wsl/run.ps1` - the
  single source of truth - so the repo root has a discoverable entry
  point without duplicating logic.

  Usage from repo root (PowerShell):
    powershell -ExecutionPolicy Bypass -File scripts\wsl-tests\run.ps1
    pwsh -File scripts/wsl-tests/run.ps1
    .\scripts\wsl-tests\run.ps1 -Suite test_meson_helper -Verbose

.PARAMETER Suite
  Forwarded to the inner run.ps1 (single suite name).

.PARAMETER WhatIf
  Forwarded to the inner run.ps1.
#>
param(
  [string]$Suite,
  [switch]$WhatIf
)
# Fallback for powershell -File with hyphenated script path where named params become positional
$needsFallback = $false
if ($Suite -like "-*") { $needsFallback = $true }
elseif (-not $WhatIf -and $args -contains "-WhatIf") { $needsFallback = $true }
elseif ($Suite -and $args.Count -gt 0 -and $args[0] -like "-*") { $needsFallback = $true }
if ($needsFallback -or (-not $Suite -and -not $WhatIf -and $args.Count -gt 0)) {
  $wasSuite = $Suite
  $Suite = $null; $WhatIf = $false
  if ($wasSuite -eq "-WhatIf") { $WhatIf = $true }
  elseif ($wasSuite -and $wasSuite -notlike "-*") { $Suite = $wasSuite }
  for ($i=0; $i -lt $args.Count; $i++) {
    if ($args[$i] -eq "-Suite" -and $i+1 -lt $args.Count) { $Suite = $args[$i+1]; $i++ }
    elseif ($args[$i] -eq "-WhatIf") { $WhatIf = $true }
    elseif ($args[$i] -notlike "-*" -and -not $Suite) { $Suite = $args[$i] }
  }
  if ($Suite -like "-*") { $Suite = $null }
  if ($wasSuite -eq "-WhatIf") { $WhatIf = $true }
}

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$here = $PSScriptRoot
$inner = Join-Path $here "..\..\external\imagedecoder-houri\tests\wsl\run.ps1"
$inner = Resolve-Path $inner -ErrorAction Stop

$argsList = @()
if ($Suite) { $argsList += @("-Suite", $Suite) }
if ($WhatIf) { $argsList += "-WhatIf" }
if ($VerbosePreference -eq "Continue") { $argsList += "-Verbose" }

& $inner.Path @argsList
exit $LASTEXITCODE
