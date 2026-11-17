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

function Remove-ServiceIfPresent {
  sc.exe stop $ServiceName 2>$null | Out-Null
  Start-Sleep -Milliseconds 500
  if (Test-Path $InstalledHelper) {
    & $InstalledHelper uninstall-service 2>$null | Out-Null
  }
}

if ($Uninstall) {
  Remove-ServiceIfPresent
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

$token = ([guid]::NewGuid().ToString("N")) + ([guid]::NewGuid().ToString("N"))
$config = @{ addr = $Listen; token = $token } | ConvertTo-Json -Compress
Set-Content -Path $SystemConfig -Value $config -Encoding ASCII
icacls $SystemConfig /inheritance:r /grant:r "SYSTEM:(F)" "Administrators:(F)" | Out-Null
Set-Content -Path $UserConfig -Value $config -Encoding ASCII
$user = "$env:USERDOMAIN\$env:USERNAME"
icacls $UserConfig /inheritance:r /grant:r "${user}:(F)" "SYSTEM:(F)" | Out-Null

Remove-ServiceIfPresent
& $InstalledHelper install-service $SystemConfig
if ($LASTEXITCODE -ne 0) {
  throw "the helper failed to install the service (exit $LASTEXITCODE)"
}

Write-Host ""
Write-Host "Installed OpenVPN Desktop helper service."
Write-Host "  service: $ServiceName (LocalSystem, automatic)"
Write-Host "  helper:  $InstalledHelper"
Write-Host "  config:  $SystemConfig (SYSTEM/Administrators only)"
Write-Host "  token:   $UserConfig (current user only)"
Write-Host ""
Write-Host "OpenVPN is required for the tunnel. Restart OpenVPN Desktop, then connect."
Write-Host "To remove: powershell -ExecutionPolicy Bypass -File $($MyInvocation.MyCommand.Path) -Uninstall"
