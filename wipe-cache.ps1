<#
.SYNOPSIS
  Convenience wrapper — runs the nuclear wipe with confirmation.

.DESCRIPTION
  From repo root:  powershell -ExecutionPolicy Bypass -File .\wipe-cache.ps1
  This just calls scripts\cache\wipe-all-caches.ps1 with defaults.
  For dry-run:     powershell -File .\wipe-cache.ps1 -DryRun
#>
[CmdletBinding()]
param([switch]$DryRun, [switch]$NoDaemonStop)
& "$PSScriptRoot\scripts\cache\wipe-all-caches.ps1" -DryRun:$DryRun -NoDaemonStop:$NoDaemonStop
