#Requires -Version 5.1
# Exercise packaging/signpath-inject.ps1 on a real Windows runner.
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$inject = Join-Path $repo "packaging/signpath-inject.ps1"
$root = Join-Path $env:TEMP ("signpath-inject-test-" + [guid]::NewGuid().ToString("N"))
$signed = Join-Path $root "signed"
$targets = Join-Path $root "targets"
New-Item -ItemType Directory -Force $signed, $targets | Out-Null

function New-Bytes([string]$path, [string]$text) {
  [IO.File]::WriteAllText($path, $text)
}

$app = Join-Path $signed "OpenVPN Desktop.exe"
$helper = Join-Path $signed "openvpn-desktop-helper.exe"
$uninstaller = Join-Path $signed "Uninstall.exe"
New-Bytes $app "signed-app"
New-Bytes $helper "signed-helper"
New-Bytes $uninstaller "signed-uninstaller"

$env:SIGNPATH_SIGNED_APP = $app
$env:SIGNPATH_SIGNED_HELPER = $helper
$env:SIGNPATH_SIGNED_UNINSTALLER = $uninstaller

$files = @{
  "OpenVPN Desktop.exe" = "signed-app"
  "openvpn-desktop-helper.exe" = "signed-helper"
  "Uninstall.exe" = "signed-uninstaller"
  "makensis.tmp" = "signed-uninstaller"
}
foreach ($name in $files.Keys) {
  $path = Join-Path $targets $name
  New-Bytes $path "unsigned"
  & $inject $path
  $got = [IO.File]::ReadAllText($path)
  if ($got -ne $files[$name]) { throw "$name was not replaced: $got" }
}

$setup = Join-Path $targets "openvpn-desktop-setup-1.0.0-amd64.exe"
New-Bytes $setup "unsigned"
& $inject $setup
if ([IO.File]::ReadAllText($setup) -ne "unsigned") { throw "the installer was modified before SignPath" }

$dll = Join-Path $targets "plugin.dll"
New-Bytes $dll "unsigned"
& $inject $dll
if ([IO.File]::ReadAllText($dll) -ne "unsigned") { throw "a DLL was replaced" }

Remove-Item -Recurse -Force $root
Write-Host "SignPath injection test passed."
