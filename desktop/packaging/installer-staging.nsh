; Staging installer for SignPath.
;
; MyGo writes Uninstall.exe at install time, so it cannot be signed after the
; installer is built. This staging installer uses the same uninstaller code as
; the release installer, but omits customInstall (so installing it on the
; runner does not start the helper service). The workflow installs it once,
; extracts Uninstall.exe, signs that file through SignPath, and injects the
; signed file into the release installer through windows.signCommand.
;
; Keep customUnInstall identical to packaging/installer.nsh.

!macro customUnInstall
  ; Run from %TEMP% so removing $INSTDIR does not delete the helper while it
  ; is stopping the service.
  CopyFiles /SILENT "$INSTDIR\openvpn-desktop-helper.exe" "$TEMP\openvpn-desktop-helper.exe"
  ExecShell "runas" "$TEMP\openvpn-desktop-helper.exe" "uninstall-service --purge --user-config $\"$APPDATA\openvpn-desktop\service.json$\"" SW_SHOWNORMAL
!macroend
