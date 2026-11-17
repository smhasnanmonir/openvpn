package main

import (
	"bytes"
	_ "embed"
	"flag"
	"image"
	"image/png"
	"io"
	"log"
	"os"
	"path/filepath"

	"github.com/egoist/mygo"
	"github.com/egoist/mygo/ui"
	xdraw "golang.org/x/image/draw"

	"github.com/smhasnanmonir/openvpn/desktop/internal/app"
	"github.com/smhasnanmonir/openvpn/desktop/internal/appui"
	"github.com/smhasnanmonir/openvpn/desktop/internal/ovpn"
)

//go:embed resources/icon.png
var appIconPNG []byte

func main() {
	// ContinueOnError keeps a GUI launch working when the desktop passes an
	// argument this app does not know (for example macOS's old -psn_… token).
	flags := flag.NewFlagSet("openvpn-desktop", flag.ContinueOnError)
	flags.SetOutput(io.Discard)
	autoConnectFlag := flags.Bool("autoconnect", false, "connect the selected profile after the vault is unlocked")
	_ = flags.Parse(os.Args[1:])
	autoConnect := *autoConnectFlag || os.Getenv("OPENVPN_DESKTOP_AUTOCONNECT") == "1"

	dataDir := resolveDataDir()
	if err := app.EnsureDataDir(dataDir); err != nil {
		log.Fatalf("cannot create data directory %s: %v", dataDir, err)
	}
	helper := ovpn.FindHelper(executableDir())
	vaultPath := filepath.Join(dataDir, "vault.ovpnx")
	a := appui.New(vaultPath, dataDir, helper, autoConnect)

	mygo.App.OnBeforeQuit(func(*mygo.QuitEvent) { a.ForceQuit() })

	mygo.App.WhenReady(func() {
		mygo.App.SetMenu(mygo.NewMenu([]*mygo.MenuItem{
			{Role: mygo.RoleAppMenu},
			{Label: "File", Submenu: []*mygo.MenuItem{
				{Label: "Import Profile…", Accelerator: "CmdOrCtrl+O", Click: func(*mygo.MenuItem, *mygo.Window) {
					a.OpenImportDialog()
				}},
				{Label: "Lock Vault", Accelerator: "CmdOrCtrl+L", Click: func(*mygo.MenuItem, *mygo.Window) {
					a.LockVault()
				}},
				mygo.Separator(),
				{Label: "Quit", Accelerator: "CmdOrCtrl+Q", Click: func(*mygo.MenuItem, *mygo.Window) {
					a.Quit()
				}},
			}},
			{Label: "Connection", Submenu: []*mygo.MenuItem{
				{Label: "Connect", Accelerator: "CmdOrCtrl+Shift+C", Click: func(*mygo.MenuItem, *mygo.Window) {
					a.ConnectFromMenu()
				}},
				{Label: "Disconnect", Accelerator: "CmdOrCtrl+Shift+D", Click: func(*mygo.MenuItem, *mygo.Window) {
					a.DisconnectFromMenu()
				}},
				mygo.Separator(),
				{Label: "Check Public IP", Click: func(*mygo.MenuItem, *mygo.Window) {
					a.CheckPublicIPFromMenu()
				}},
			}},
			{Role: mygo.RoleEditMenu},
			{Role: mygo.RoleWindowMenu},
		}))

		w := mygo.NewWindow(mygo.WindowOptions{
			Title:     "OpenVPN Desktop",
			Width:     1120,
			Height:    800,
			MinWidth:  940,
			MinHeight: 640,
			StateKey:  "main",
			Content:   ui.View(a.View),
		})
		a.SetWindow(w)
		w.OnClose(func(e *mygo.CloseEvent) {
			if a.ShouldHideOnClose() {
				e.PreventDefault()
				a.HideWindow()
			}
		})

		tray, err := mygo.NewTray(mygo.TrayOptions{
			ID:      "openvpn-desktop",
			Icon:    trayIcon(appIconPNG),
			ToolTip: "OpenVPN Desktop",
			Menu: mygo.NewMenu([]*mygo.MenuItem{
				{Label: "Show Window", Click: func(*mygo.MenuItem, *mygo.Window) { a.ShowWindow() }},
				{Label: "Connect", Click: func(*mygo.MenuItem, *mygo.Window) { a.ConnectFromMenu() }},
				{Label: "Disconnect", Click: func(*mygo.MenuItem, *mygo.Window) { a.DisconnectFromMenu() }},
				mygo.Separator(),
				{Label: "Check Public IP", Click: func(*mygo.MenuItem, *mygo.Window) { a.CheckPublicIPFromMenu() }},
				mygo.Separator(),
				{Label: "Quit", Click: func(*mygo.MenuItem, *mygo.Window) { a.Quit() }},
			}),
		})
		if err != nil {
			log.Println("tray icon unavailable:", err)
		} else {
			a.SetTrayAvailable(true)
			_ = tray
		}
	})
	if err := mygo.App.Run(); err != nil {
		log.Fatal(err)
	}
}

func resolveDataDir() string {
	if dir := os.Getenv("OPENVPN_DESKTOP_DATA_DIR"); dir != "" {
		return dir
	}
	base, err := os.UserConfigDir()
	if err != nil {
		return "."
	}
	return filepath.Join(base, "openvpn-desktop")
}

func executableDir() string {
	exe, err := os.Executable()
	if err != nil {
		return "."
	}
	return filepath.Dir(exe)
}

// trayIcon scales the embedded 1024 px app icon to the 32 px the tray expects.
func trayIcon(src []byte) []byte {
	img, err := png.Decode(bytes.NewReader(src))
	if err != nil {
		return src
	}
	dst := image.NewNRGBA(image.Rect(0, 0, 32, 32))
	xdraw.CatmullRom.Scale(dst, dst.Rect, img, img.Bounds(), xdraw.Over, nil)
	var buf bytes.Buffer
	if err := png.Encode(&buf, dst); err != nil {
		return src
	}
	return buf.Bytes()
}
