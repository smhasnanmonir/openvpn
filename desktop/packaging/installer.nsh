; OpenVPN Desktop NSIS hooks.
;
; MyGo installs the GUI per user. The privileged helper is a LocalSystem
; service, so the installer starts it with a UAC prompt. The GUI also offers
; "Install privileged helper" if this prompt is declined or the service is
; later removed.
!macro customInstall
  ExecShell "runas" "$INSTDIR\openvpn-desktop-helper.exe" \
    "install-service --helper $\"$INSTDIR\openvpn-desktop-helper.exe$\" --user-config $\"$APPDATA\openvpn-desktop\service.json$\"" \
    SW_SHOWNORMAL
!macroend

!macro customUnInstall
  ; Run from %TEMP% so removing $INSTDIR does not delete the helper while it
  ; is stopping the service.
  CopyFiles /SILENT "$INSTDIR\openvpn-desktop-helper.exe" "$TEMP\openvpn-desktop-helper.exe"
  ExecShell "runas" "$TEMP\openvpn-desktop-helper.exe" "uninstall-service --purge --user-config $\"$APPDATA\openvpn-desktop\service.json$\"" SW_SHOWNORMAL
!macroend
