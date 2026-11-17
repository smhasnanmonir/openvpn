#Requires -RunAsAdministrator
<#
.SYNOPSIS
  Install the OpenVPN Desktop privileged helper service on Windows.

.DESCRIPTION
  Installs openvpn-desktop-helper.exe as the OpenVPNDesktopHelper service
  (LocalSystem), creates a random loopback token, writes the root-owned service
  config and the current user's token file, and starts the service.

  OpenVPN 2.7+ (with the TAP-Windows6 driver) must be installed. The helper
  finds C:\Program Files\OpenVPN\bin\openvpn.exe.

.PARAMETER HelperPath
  Path to the built openvpn-desktop-helper.exe. Defaults to ..\bin\openvpn-desktop-helper.exe.

.PARAMETER Uninstall
  Stop and remove the service and configuration.
#>
param(
  [string]$HelperPath = "",
  [switch]$Uninstall
)

$ErrorActionPreference = "Stop"
$ServiceName = "OpenVPNDesktopHelper"
$Listen = "127.0.0.1:51940"
$InstallDir = Join-Path $env:ProgramFiles "OpenVPN Desktop"
$InstalledHelper = Join-Path $InstallDir "openvpn-desktop-helper.exe"
$SystemDir = Join-Path $env:ProgramData "OpenVPN Desktop"
$SystemConfig = Join-Path $SystemDir "service.json"
$UserDir = Join-Path $env:APPDATA "openvpn-desktop"
$UserConfig = Join-Path $UserDir "service.json"
# Well-known SIDs: SYSTEM and the local Administrators group. Names are
# localized on many Windows installs, SIDs are not.
$SystemSid = "*S-1-5-18"
$AdminSid = "*S-1-5-32-544"

function Remove-ServiceIfPresent {
  sc.exe stop $ServiceName 2>$null | Out-Null
  Start-Sleep -Milliseconds 500
  if (Test-Path $InstalledHelper) {
    & $InstalledHelper uninstall-service 2>$null | Out-Null
  }
}

if ($Uninstall) {
  Remove-ServiceIfPresent
  if (Test-Path $InstalledHelper) {
    & $InstalledHelper uninstall-service --purge --user-config $UserConfig
  }
  Remove-Item -Force -ErrorAction SilentlyContinue $SystemConfig, $UserConfig
  Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $InstallDir, $SystemDir, $UserDir
  Write-Host "OpenVPN Desktop helper service removed."
  exit 0
}

if (-not $HelperPath) {
  $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
  $HelperPath = Join-Path (Split-Path -Parent $scriptDir) "bin\openvpn-desktop-helper.exe"
}
if (-not (Test-Path $HelperPath)) {
  throw "helper not found at $HelperPath; build it with: `$env:GOOS='windows'; go build -o bin/openvpn-desktop-helper.exe ./cmd/openvpn-desktop-helper"
}

$openvpnCandidates = @()
if ($env:ProgramFiles) { $openvpnCandidates += (Join-Path $env:ProgramFiles "OpenVPN\bin\openvpn.exe") }
if (${env:ProgramFiles(x86)}) { $openvpnCandidates += (Join-Path ${env:ProgramFiles(x86)} "OpenVPN\bin\openvpn.exe") }
$openvpn = $openvpnCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $openvpn) {
  Write-Warning "OpenVPN 2.7+ was not found under Program Files. Install it with the TAP-Windows6 driver before connecting."
} else {
  Write-Host "Using OpenVPN: $openvpn"
}

New-Item -ItemType Directory -Force -Path $InstallDir, $SystemDir, $UserDir | Out-Null
Copy-Item -Force $HelperPath $InstalledHelper

# The helper writes the token files, copies itself into Program Files and
# creates/upgrades the service. Running it directly keeps one install path for
# the script, CI and the GUI's Install privileged helper button.
& $InstalledHelper install-service --helper $InstalledHelper --user-config $UserConfig
if ($LASTEXITCODE -ne 0) {
  throw "the helper failed to install the service (exit $LASTEXITCODE)"
}
if (-not (Test-Path $SystemConfig) -or -not (Test-Path $UserConfig)) {
  throw "the helper did not write the service token files"
}
# Protect the token files and their directories, using SIDs so the ACLs work
# on every Windows display language.
icacls $SystemDir /inheritance:r /grant:r "${SystemSid}:(OI)(CI)(F)" "${AdminSid}:(OI)(CI)(F)" | Out-Null
icacls $SystemConfig /inheritance:r /grant:r "${SystemSid}:(F)" "${AdminSid}:(F)" | Out-Null
$user = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
icacls $UserDir /inheritance:r /grant:r "${user}:(OI)(CI)(F)" "${SystemSid}:(OI)(CI)(F)" | Out-Null
icacls $UserConfig /inheritance:r /grant:r "${user}:(F)" "${SystemSid}:(F)" | Out-Null

Write-Host ""
Write-Host "Installed OpenVPN Desktop helper service."
Write-Host "  service: $ServiceName (LocalSystem, automatic)"
Write-Host "  helper:  $InstalledHelper"
Write-Host "  config:  $SystemConfig (SYSTEM/Administrators only)"
Write-Host "  token:   $UserConfig (current user only)"
Write-Host ""
Write-Host "OpenVPN is required for the tunnel. Restart OpenVPN Desktop, then connect."
Write-Host "To remove: powershell -ExecutionPolicy Bypass -File $($MyInvocation.MyCommand.Path) -Uninstall"
