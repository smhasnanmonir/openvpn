package appui

import (
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"sync/atomic"
	"time"

	"github.com/egoist/mygo"
	"github.com/egoist/mygo/ui"

	"github.com/smhasnanmonir/openvpn/desktop/internal/app"
	"github.com/smhasnanmonir/openvpn/desktop/internal/autostart"
	"github.com/smhasnanmonir/openvpn/desktop/internal/captiveportal"
	"github.com/smhasnanmonir/openvpn/desktop/internal/model"
	"github.com/smhasnanmonir/openvpn/desktop/internal/ovpn"
	"github.com/smhasnanmonir/openvpn/desktop/internal/perapp"
	"github.com/smhasnanmonir/openvpn/desktop/internal/settings"
	"github.com/smhasnanmonir/openvpn/desktop/internal/vault"
)

type perAppEntry struct {
	Path     string
	Selected bool
}

func (e perAppEntry) Name() string { return filepath.Base(e.Path) }

// App is the state of the single window.
type App struct {
	win          *mygo.Window
	vaultPath    string
	dataDir      string
	helper       ovpn.HelperInfo
	selfDir      string
	resourcesDir string
	helperError  string

	svc      *app.Service
	unlocked bool
	creating bool

	password    string
	confirm     string
	unlockError string

	tab        int
	toast      string
	toastUntil time.Time

	// Profiles
	editProfile    string
	credUsername   string
	credPassword   string
	credKeyPass    string
	credResponse   string
	importOutcomes []model.ImportOutcome

	confirmDeleteOpen bool
	confirmDeleteID   string
	confirmDeleteName string

	// Logs
	logList   ui.ListState
	logFilter string

	// Settings form
	settingsLoaded     bool
	requireDNS         bool
	fallbackDNS        string
	publicIPURL        string
	mtuMode            string
	compression        string
	cipher             string
	themeMode          string
	splitMode          string
	splitCIDRs         string
	autoIP             bool
	killSwitch         bool
	lanBypass          bool
	minimizeTray       bool
	autoConnect        bool
	autoPortalDetect   bool
	portalProbeURL     string
	perAppMode         string
	perAppExecutables  []string
	perAppEntries      []perAppEntry
	perAppFilter       string
	perAppManual       string
	perAppList         ui.ListState
	perAppError        string
	settingsError      string
	autostartInstalled bool
	autostartError     string

	// Startup/tray
	autoConnectFlag    bool
	pendingAutoConnect bool
	trayAvailable      bool
	forceQuit          atomic.Bool
}

// New builds the app state. If OPENVPN_DESKTOP_PASSPHRASE is set and a vault
// exists, it is unlocked automatically (useful for tests and headless runs).
func New(vaultPath, dataDir string, helper ovpn.HelperInfo, autoConnect ...bool) *App {
	a := &App{vaultPath: vaultPath, dataDir: dataDir, helper: helper}
	if len(autoConnect) > 0 {
		a.autoConnectFlag = autoConnect[0]
	}
	if !vault.Exists(vaultPath) {
		a.creating = true
	} else if pass := os.Getenv("OPENVPN_DESKTOP_PASSPHRASE"); pass != "" {
		if v, err := vault.Open(vaultPath, pass); err == nil {
			a.attach(v)
		} else {
			a.unlockError = err.Error()
		}
	}
	return a
}

// SetWindow stores the window used for dialogs and background updates.
func (a *App) SetWindow(w *mygo.Window) { a.win = w }

// SetPaths records where the executable and packaged resources live, so the
// helper can be reinstalled or rediscovered without restarting the app.
func (a *App) SetPaths(selfDir, resourcesDir string) {
	a.selfDir = selfDir
	a.resourcesDir = resourcesDir
}

// InstallHelper runs the one-time elevation flow and refreshes discovery.
func (a *App) InstallHelper() {
	a.helperError = ""
	if err := ovpn.InstallHelper(a.helper); err != nil {
		a.helperError = err.Error()
		return
	}
	a.recheckHelper()
	if !a.helper.Available {
		a.helperError = "The installer finished but the helper is still not ready: " + a.helper.Detail
		return
	}
	a.setToast("Privileged helper installed.")
}

func (a *App) recheckHelper() {
	a.helper = ovpn.FindHelper(a.selfDir, a.resourcesDir)
	if a.svc != nil {
		a.svc.SetHelper(a.helper)
	}
}

// OpenImportDialog is the menu entry point.
func (a *App) OpenImportDialog() { a.openImportDialog() }

// LockVault is the menu entry point.
func (a *App) LockVault() { a.lock() }

// ConnectFromMenu is the menu entry point.
func (a *App) ConnectFromMenu() { a.connect() }

// DisconnectFromMenu is the menu entry point.
func (a *App) DisconnectFromMenu() { a.disconnect() }

// CheckPublicIPFromMenu is the menu entry point.
func (a *App) CheckPublicIPFromMenu() { a.checkIP() }

// Notify implements app.Notifier.
func (a *App) Notify(title, body string) {
	if !mygo.NotificationsSupported() {
		return
	}
	go func() {
		_ = mygo.NewNotification(mygo.NotificationOptions{Title: title, Body: body}).Show()
	}()
}

func (a *App) attach(v *vault.Vault) {
	a.svc = app.New(v, a.dataDir, a.helper, a)
	a.unlocked = true
	a.creating = false
	a.password = ""
	a.confirm = ""
	a.unlockError = ""
	a.settingsLoaded = false
	a.loadSettings()
	a.autostartInstalled = autostart.Supported() && autostart.Installed()
	a.editProfile = v.Selected()
	a.credResponse = ""
	if a.editProfile != "" {
		a.loadCredentials(a.editProfile)
	}
	if a.autoConnectFlag || a.svc.Settings().AutoConnect {
		a.pendingAutoConnect = true
	}
}

func (a *App) submitUnlock() {
	a.unlockError = ""
	if a.creating {
		if len(a.password) < 8 {
			a.unlockError = "Use at least 8 characters for the master password."
			return
		}
		if a.password != a.confirm {
			a.unlockError = "The two passwords do not match."
			return
		}
		v, err := vault.Create(a.vaultPath, a.password)
		if err != nil {
			a.unlockError = err.Error()
			return
		}
		a.attach(v)
		a.setToast("Vault created. Import a .ovpn profile to begin.")
		a.tab = 1
		return
	}
	v, err := vault.Open(a.vaultPath, a.password)
	if err != nil {
		a.unlockError = err.Error()
		return
	}
	a.attach(v)
}

func (a *App) lock() {
	if a.svc != nil {
		a.svc.Close()
		a.svc = nil
	}
	a.unlocked = false
	a.creating = false
	a.password = ""
	a.confirm = ""
	a.unlockError = ""
	a.settingsLoaded = false
	a.tab = 0
}

func (a *App) setToast(msg string) {
	a.toast = msg
	a.toastUntil = time.Now().Add(6 * time.Second)
}

func (a *App) activeToast() string {
	if time.Now().After(a.toastUntil) {
		return ""
	}
	return a.toast
}

func (a *App) connect() {
	if a.svc == nil {
		return
	}
	snap := a.svc.Snapshot()
	profiles := a.svc.Profiles()
	if len(profiles) == 0 {
		a.setToast("Import a .ovpn profile first.")
		a.tab = 1
		return
	}
	id := snap.ProfileID
	if id == "" {
		id = profiles[0].ID
		_ = a.svc.SelectProfile(id)
	}
	stored, ok := a.svc.Profile(id)
	if !ok {
		a.setToast("Select a profile first.")
		a.tab = 1
		return
	}
	creds := a.svc.Credentials(id)
	if a.editProfile == id {
		if a.credUsername != "" || a.credPassword != "" || a.credKeyPass != "" || a.credResponse != "" {
			creds = model.Credentials{
				Username:           a.credUsername,
				Password:           a.credPassword,
				PrivateKeyPassword: a.credKeyPass,
				Response:           a.credResponse,
			}
		}
	}
	if stored.Summary.Metadata.NeedsPassword && creds.Username == "" {
		a.editProfile = id
		a.loadCredentials(id)
		a.tab = 1
		a.setToast("This profile needs a username and password. Enter them under Profiles, then connect.")
		return
	}
	if stored.Summary.Metadata.StaticChallenge != "" && creds.Response == "" {
		a.editProfile = id
		a.loadCredentials(id)
		a.tab = 1
		a.setToast("This profile uses a static challenge. Enter the response under Profiles before connecting.")
		return
	}
	if err := a.svc.Connect(id, creds); err != nil {
		a.setToast(err.Error())
		a.editProfile = id
		a.loadCredentials(id)
		a.tab = 1
	}
}

func (a *App) disconnect() {
	if a.svc == nil {
		return
	}
	a.svc.Disconnect()
}

func (a *App) openImportDialog() {
	if a.svc == nil {
		return
	}
	go func() {
		paths, err := mygo.Dialog.Open(mygo.OpenDialogOptions{
			Parent:  a.win,
			Title:   "Import an OpenVPN profile",
			Message: "Select the .ovpn profile and, if it references them, its certificate/key files.",
			Filters: []mygo.FileFilter{
				{Name: "OpenVPN profiles and keys", Extensions: []string{"ovpn", "crt", "pem", "key", "txt"}},
				{Name: "All files", Extensions: []string{"*"}},
			},
			Multiple: true,
		})
		if err != nil || len(paths) == 0 {
			return
		}
		outcomes := a.svc.ImportFiles(paths)
		if a.win != nil {
			a.win.Update(func() { a.applyImport(outcomes) })
		}
	}()
}

func (a *App) applyImport(outcomes []model.ImportOutcome) {
	a.importOutcomes = outcomes
	a.tab = 1
	for _, o := range outcomes {
		if o.OK() {
			a.editProfile = o.ProfileID
			a.loadCredentials(o.ProfileID)
			a.setToast("Imported " + o.FileName)
			return
		}
	}
	if len(outcomes) > 0 && outcomes[0].Error != "" {
		a.setToast("Import failed: " + outcomes[0].Error)
	}
}

func (a *App) loadCredentials(id string) {
	if a.svc == nil {
		return
	}
	c := a.svc.Credentials(id)
	a.credUsername = c.Username
	a.credPassword = c.Password
	a.credKeyPass = c.PrivateKeyPassword
	a.credResponse = ""
}

func (a *App) saveCredentials() {
	if a.svc == nil || a.editProfile == "" {
		return
	}
	err := a.svc.SetCredentials(a.editProfile, model.Credentials{
		Username:           strings.TrimSpace(a.credUsername),
		Password:           a.credPassword,
		PrivateKeyPassword: a.credKeyPass,
	})
	if err != nil {
		a.setToast(err.Error())
		return
	}
	a.setToast("Credentials saved in the encrypted vault.")
}

func (a *App) deleteProfile(id, name string) {
	if a.svc == nil {
		return
	}
	if err := a.svc.DeleteProfile(id); err != nil {
		a.setToast(err.Error())
		return
	}
	if a.editProfile == id {
		a.editProfile = ""
		a.credUsername, a.credPassword, a.credKeyPass, a.credResponse = "", "", "", ""
	}
	a.setToast("Deleted " + name)
}

func (a *App) loadSettings() {
	if a.svc == nil {
		return
	}
	s := a.svc.Settings()
	a.requireDNS = s.RequireTunnelDNS
	a.fallbackDNS = s.FallbackDNS
	a.publicIPURL = s.PublicIPURL
	a.autoIP = s.AutoCheckIP
	a.killSwitch = s.KillSwitch && runtime.GOOS == "linux"
	a.lanBypass = s.LanBypass
	a.minimizeTray = s.MinimizeToTray
	a.autoConnect = s.AutoConnect
	a.autoPortalDetect = s.AutoPortalDetect
	a.portalProbeURL = s.PortalProbeURL
	a.perAppMode = map[settings.PerAppMode]string{
		settings.PerAppOff:     "Off",
		settings.PerAppInclude: "Only selected apps use the VPN",
		settings.PerAppExclude: "Selected apps bypass the VPN",
	}[s.PerAppMode]
	if a.perAppMode == "" {
		a.perAppMode = "Off"
	}
	a.perAppExecutables = append([]string(nil), s.PerAppExecutables...)
	a.perAppEntries = nil
	a.perAppFilter = ""
	a.perAppManual = ""
	a.refreshPerApps()
	a.settingsError = ""
	a.mtuMode = map[settings.MtuMode]string{
		settings.MtuAuto: "Automatic", settings.Mtu1500: "1500", settings.Mtu1400: "1400", settings.Mtu1280: "1280",
	}[s.MtuMode]
	if a.mtuMode == "" {
		a.mtuMode = "Automatic"
	}
	a.compression = "Compatibility"
	if s.Compression == settings.CompressionStrict {
		a.compression = "Strict"
	}
	a.cipher = "Profile default"
	if s.Cipher == settings.CipherAEADOnly {
		a.cipher = "AEAD only"
	}
	a.themeMode = map[settings.ThemeMode]string{
		settings.ThemeSystem: "System", settings.ThemeLight: "Light", settings.ThemeDark: "Dark",
	}[s.Theme]
	if a.themeMode == "" {
		a.themeMode = "System"
	}
	a.splitMode = map[settings.SplitTunnelMode]string{
		settings.SplitOff: "All traffic", settings.SplitInclude: "Only these networks", settings.SplitExclude: "All except these networks",
	}[s.SplitTunnel]
	if a.splitMode == "" {
		a.splitMode = "All traffic"
	}
	a.splitCIDRs = s.SplitTunnelCIDRs
	a.settingsLoaded = true
}

func (a *App) saveSettings() {
	if a.svc == nil {
		return
	}
	next := settings.Settings{
		RequireTunnelDNS:  a.requireDNS,
		FallbackDNS:       strings.TrimSpace(a.fallbackDNS),
		MtuMode:           settings.MtuAuto,
		Compression:       settings.CompressionCompatibility,
		Cipher:            settings.CipherProfile,
		AutoCheckIP:       a.autoIP,
		PublicIPURL:       strings.TrimSpace(a.publicIPURL),
		Theme:             settings.ThemeSystem,
		KillSwitch:        a.killSwitch,
		SplitTunnel:       settings.SplitOff,
		SplitTunnelCIDRs:  strings.TrimSpace(a.splitCIDRs),
		LanBypass:         a.lanBypass,
		MinimizeToTray:    a.minimizeTray,
		AutoConnect:       a.autoConnect,
		AutoPortalDetect:  a.autoPortalDetect,
		PortalProbeURL:    strings.TrimSpace(a.portalProbeURL),
		PerAppMode:        perAppModeFromLabel(a.perAppMode),
		PerAppExecutables: a.selectedPerApps(),
	}
	switch a.mtuMode {
	case "1500":
		next.MtuMode = settings.Mtu1500
	case "1400":
		next.MtuMode = settings.Mtu1400
	case "1280":
		next.MtuMode = settings.Mtu1280
	}
	if a.compression == "Strict" {
		next.Compression = settings.CompressionStrict
	}
	if a.cipher == "AEAD only" {
		next.Cipher = settings.CipherAEADOnly
	}
	switch a.themeMode {
	case "Light":
		next.Theme = settings.ThemeLight
	case "Dark":
		next.Theme = settings.ThemeDark
	}
	switch a.splitMode {
	case "Only these networks":
		next.SplitTunnel = settings.SplitInclude
	case "All except these networks":
		next.SplitTunnel = settings.SplitExclude
	}
	err := a.svc.UpdateSettings(func(s *settings.Settings) error {
		*s = next
		return nil
	})
	if err != nil {
		a.settingsError = err.Error()
		return
	}
	a.settingsError = ""
	a.setToast("Settings saved.")
}

func (a *App) perAppSupported() bool { return runtime.GOOS == "linux" }

func perAppModeFromLabel(label string) settings.PerAppMode {
	switch label {
	case "Only selected apps use the VPN":
		return settings.PerAppInclude
	case "Selected apps bypass the VPN":
		return settings.PerAppExclude
	default:
		return settings.PerAppOff
	}
}

func (a *App) refreshPerApps() {
	selected := map[string]bool{}
	for _, exe := range a.perAppExecutables {
		selected[perapp.Canonical(exe)] = true
	}
	entries := make([]perAppEntry, 0)
	seen := map[string]bool{}
	for _, app := range perapp.Scan() {
		exe := perapp.Canonical(app.Exe)
		if seen[exe] {
			continue
		}
		seen[exe] = true
		entries = append(entries, perAppEntry{Path: exe, Selected: selected[exe]})
	}
	for exe := range selected {
		if !seen[exe] {
			entries = append(entries, perAppEntry{Path: exe, Selected: true})
		}
	}
	sort.Slice(entries, func(i, j int) bool { return entries[i].Path < entries[j].Path })
	a.perAppEntries = entries
	a.perAppList = ui.ListState{}
}

func (a *App) filteredPerApps() []*perAppEntry {
	filter := strings.ToLower(strings.TrimSpace(a.perAppFilter))
	out := make([]*perAppEntry, 0, len(a.perAppEntries))
	for i := range a.perAppEntries {
		if filter == "" || strings.Contains(strings.ToLower(a.perAppEntries[i].Path), filter) {
			out = append(out, &a.perAppEntries[i])
		}
	}
	return out
}

func (a *App) selectedPerApps() []string {
	set := map[string]bool{}
	for _, exe := range a.perAppExecutables {
		set[perapp.Canonical(exe)] = true
	}
	for i := range a.perAppEntries {
		if a.perAppEntries[i].Selected {
			set[a.perAppEntries[i].Path] = true
		} else {
			delete(set, a.perAppEntries[i].Path)
		}
	}
	out := make([]string, 0, len(set))
	for exe := range set {
		out = append(out, exe)
	}
	sort.Strings(out)
	return out
}

func (a *App) addPerAppPath() {
	raw := strings.TrimSpace(a.perAppManual)
	if raw == "" {
		return
	}
	if !filepath.IsAbs(raw) || strings.ContainsAny(raw, "\r\n\x00") {
		a.perAppError = "Enter an absolute executable path, for example /usr/bin/firefox."
		return
	}
	exe := perapp.Canonical(raw)
	if st, err := os.Stat(exe); err != nil || st.IsDir() {
		a.perAppError = "The executable does not exist: " + exe
		return
	}
	for _, existing := range a.perAppExecutables {
		if perapp.Canonical(existing) == exe {
			a.perAppError = ""
			a.perAppManual = ""
			return
		}
	}
	a.perAppExecutables = append(a.perAppExecutables, exe)
	a.perAppManual = ""
	a.perAppError = ""
	a.refreshPerApps()
}

func (a *App) checkIP() {
	if a.svc != nil {
		a.svc.CheckPublicIP()
	}
}

func (a *App) checkPortal() {
	if a.svc != nil {
		a.svc.CheckPortal()
	}
}

func (a *App) pauseForPortal() {
	if a.svc == nil {
		return
	}
	if err := a.svc.PauseForPortal(); err != nil {
		a.setToast(err.Error())
		return
	}
	portal := a.svc.Portal()
	if portal.URL != "" {
		a.openPortal(portal.URL)
	} else {
		a.setToast("VPN paused. Sign in to the network in your browser, then press Reconnect.")
	}
}

func (a *App) resumeForPortal() {
	if a.svc == nil {
		return
	}
	if err := a.svc.ResumeFromPortal(); err != nil {
		a.setToast(err.Error())
		return
	}
	a.setToast("Checking the network and reconnecting…")
}

func (a *App) openPortal(raw string) {
	safe, ok := captiveportal.ValidatePortalURL(raw)
	if !ok {
		a.setToast("The captive portal did not provide a safe http(s) URL.")
		return
	}
	if err := mygo.Shell.OpenExternal(safe); err != nil {
		a.setToast("Could not open the sign-in page: " + err.Error())
	}
}

func (a *App) installAutostart() {
	exe, err := os.Executable()
	if err != nil {
		a.autostartError = err.Error()
		return
	}
	path, err := autostart.Install(exe)
	if err != nil {
		a.autostartError = err.Error()
		return
	}
	a.autostartError = ""
	a.autostartInstalled = true
	a.setToast("Start at login installed: " + path)
}

func (a *App) removeAutostart() {
	if err := autostart.Remove(); err != nil {
		a.autostartError = err.Error()
		return
	}
	a.autostartError = ""
	a.autostartInstalled = false
	a.setToast("Start at login removed.")
}

// SetTrayAvailable records that the tray icon was created. Without it, closing
// the window must quit rather than hide and strand the user.
func (a *App) SetTrayAvailable(v bool) { a.trayAvailable = v }

// ShouldHideOnClose decides whether closing the window keeps the app in the
// tray. A real quit (menu/tray/OS session end) always closes.
func (a *App) ShouldHideOnClose() bool {
	if a.forceQuit.Load() || !a.trayAvailable || a.svc == nil {
		return false
	}
	return a.svc.Settings().MinimizeToTray
}

// ShowWindow brings the hidden window back.
func (a *App) ShowWindow() {
	if a.win != nil {
		a.win.Show()
		a.win.Focus()
	}
}

// HideWindow hides the window while the tray keeps the app alive.
func (a *App) HideWindow() {
	if a.win != nil {
		a.win.Hide()
	}
}

// ForceQuit allows the close handler to proceed during a real quit.
func (a *App) ForceQuit() { a.forceQuit.Store(true) }

// Quit exits immediately without trapping the user in the tray.
func (a *App) Quit() {
	a.forceQuit.Store(true)
	mygo.App.Exit(0)
}

func (a *App) takePendingAutoConnect() bool {
	if !a.pendingAutoConnect {
		return false
	}
	a.pendingAutoConnect = false
	return true
}

func (a *App) stateLabelColor(t *ui.Theme, s model.VPNState) ui.Color {
	switch s {
	case model.StateConnected:
		return t.Success
	case model.StateError, model.StateAuthFailed:
		return t.Danger
	case model.StateConnecting, model.StateReconnecting, model.StatePreparing:
		return t.Warning
	default:
		return t.TextMuted
	}
}

func (a *App) filteredLogs() []model.LogEntry {
	if a.svc == nil {
		return nil
	}
	logs := a.svc.Logs()
	filter := strings.ToLower(strings.TrimSpace(a.logFilter))
	if filter == "" {
		return logs
	}
	out := make([]model.LogEntry, 0, len(logs))
	for _, e := range logs {
		if strings.Contains(strings.ToLower(e.Message), filter) {
			out = append(out, e)
		}
	}
	return out
}

// summary is used by tests and the header.
func (a *App) summary() string {
	if a.svc == nil {
		return "locked"
	}
	s := a.svc.Snapshot()
	return fmt.Sprintf("%s %s", s.State.Label(), s.ProfileName)
}
