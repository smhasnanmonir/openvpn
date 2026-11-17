#Requires -RunAsAdministrator
<#
.SYNOPSIS
  Smoke-test the OpenVPN Desktop helper service install/upgrade/uninstall.

.DESCRIPTION
  Runs the real Windows service path without connecting a VPN. Use this on a
  test machine before a release; it creates and removes the service and writes
  a temporary per-user token file.

  OpenVPN does not need to be installed for this test, but a real tunnel test
  still needs OpenVPN 2.7+ and TAP-Windows6.
#>
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$helper = Join-Path $root "build\windows-amd64\openvpn-desktop-helper.exe"
if (-not (Test-Path $helper)) {
  $helper = Join-Path $root "bin\openvpn-desktop-helper.exe"
}
if (-not (Test-Path $helper)) {
  throw "helper not found; run scripts\build-helpers.sh and go tool mygo build first"
}
$userConfig = Join-Path $env:TEMP "openvpn-desktop-test-$([guid]::NewGuid().ToString('N'))\service.json"

Write-Host "==> install"
& $helper install-service --helper $helper --user-config $userConfig
if ($LASTEXITCODE -ne 0) { throw "install-service failed" }
$svc = Get-Service OpenVPNDesktopHelper
if ($svc.Status -ne "Running") { throw "service is $($svc.Status), not Running" }
if (-not (Test-Path $userConfig)) { throw "per-user token was not written" }

Write-Host "==> service is running; token: $userConfig"
Write-Host "==> uninstall"
& $helper uninstall-service --purge --user-config $userConfig
if ($LASTEXITCODE -ne 0) { throw "uninstall-service failed" }
Start-Sleep -Milliseconds 500
if (Get-Service OpenVPNDesktopHelper -ErrorAction SilentlyContinue) {
  throw "service still exists after uninstall"
}
if (Test-Path $userConfig) { throw "per-user token still exists after purge" }
Write-Host "Windows helper service smoke test passed."
