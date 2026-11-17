# Windows signing injection for the SignPath two-stage build.
#
# MyGo calls windows.signCommand for every PE file it would otherwise sign.
# This script does not sign anything itself: it puts the files returned by
# SignPath in place. The final installer is signed by SignPath after MyGo has
# packaged the signed app, helper and uninstaller.
param(
  [Parameter(Mandatory = $true)]
  [string]$File
)

$ErrorActionPreference = "Stop"
$name = Split-Path -Leaf $File

function Copy-Signed([string]$source, [string]$destination) {
  if (-not $source) {
    throw "SignPath returned no file for $destination"
  }
  if (-not (Test-Path -LiteralPath $source)) {
    throw "signed file not found: $source"
  }
  Copy-Item -LiteralPath $source -Destination $destination -Force
}

switch -Wildcard ($name) {
  "OpenVPN Desktop.exe" {
    Copy-Signed $env:SIGNPATH_SIGNED_APP $File
  }
  "openvpn-desktop-helper.exe" {
    Copy-Signed $env:SIGNPATH_SIGNED_HELPER $File
  }
  "Uninstall.exe" {
    Copy-Signed $env:SIGNPATH_SIGNED_UNINSTALLER $File
  }
  "openvpn-desktop-setup-*.exe" {
    # Signed by SignPath after the complete installer exists.
  }
  "*.dll" {
    # Resources such as DLLs are not part of the SignPath request.
  }
  default {
    # NSIS passes the generated uninstaller as a temporary file whose name is
    # not necessarily Uninstall.exe (makensis uses a random temporary name).
    # It is the only remaining PE file MyGo asks the signer to handle.
    if ($env:SIGNPATH_SIGNED_UNINSTALLER) {
      Copy-Signed $env:SIGNPATH_SIGNED_UNINSTALLER $File
    }
  }
}
