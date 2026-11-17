package appui

import (
	"fmt"
	"runtime"
	"strings"
	"time"

	"github.com/egoist/mygo/ui"

	"github.com/smhasnanmonir/openvpn/desktop/internal/app"
	"github.com/smhasnanmonir/openvpn/desktop/internal/autostart"
	"github.com/smhasnanmonir/openvpn/desktop/internal/model"
	"github.com/smhasnanmonir/openvpn/desktop/internal/profile"
	"github.com/smhasnanmonir/openvpn/desktop/internal/settings"
)

// View is the MyGo native view. It runs on the UI thread; all slow work lives
// in the service and is read through thread-safe snapshots.
func (a *App) View(c *ui.Context) {
	if a.svc != nil {
		switch a.svc.Settings().Theme {
		case settings.ThemeDark:
			c.SetTheme(ui.DarkTheme())
		case settings.ThemeLight:
			c.SetTheme(ui.LightTheme())
		}
	}
	if a.svc == nil {
		a.viewUnlock(c)
		c.After(500 * time.Millisecond)
		return
	}
	if a.takePendingAutoConnect() {
		a.connect()
	}
	a.viewMain(c)
	c.After(750 * time.Millisecond)
}

func (a *App) viewUnlock(c *ui.Context) {
	t := c.Theme()
	action := "Unlock vault"
	if a.creating {
		action = "Create vault"
	}
	desc := "Unlock your profiles, credentials and settings."
	if a.creating {
		desc = "Choose a master password. It encrypts every profile and credential on this computer."
	}
	ui.Column(c).Fill().Center().Padding(28).Children(func() {
		ui.Column(c).Width(460).Padding(26).Gap(12).Background(t.Surface).Border(1, t.Border).Radius(16).Children(func() {
			ui.Text(c, "OpenVPN Desktop").FontSize(24).Bold()
			ui.Text(c, desc).TextColor(t.TextMuted).FontSize(13).Wrap()
			first := ui.TextInput(c, &a.password).Password().Label("Master password")
			submit := first.Submitted()
			if a.creating {
				second := ui.TextInput(c, &a.confirm).Password().Label("Confirm master password")
				submit = submit || second.Submitted()
			}
			if a.unlockError != "" {
				ui.Text(c, a.unlockError).TextColor(t.Danger).FontSize(12).Wrap()
			}
			if ui.PrimaryButton(c, action).Width(180).Clicked() || submit {
				a.submitUnlock()
			}
			ui.Text(c, "AES-256-GCM encryption with an Argon2id-derived key. There is no password recovery: "+
				"if you forget it, the vault cannot be opened.").FontSize(11).TextColor(t.TextMuted).Wrap()
			if !a.creating {
				ui.Text(c, "Vault: "+a.vaultPath).FontSize(10).TextColor(t.TextMuted).Wrap()
			}
		})
	})
}

func (a *App) viewMain(c *ui.Context) {
	t := c.Theme()
	snap := a.svc.Snapshot()
	ui.Column(c).Fill().Children(func() {
		// Header
		ui.Row(c).WidthPercent(100).Padding(14, 22).Gap(12).AlignItems(ui.Center).Children(func() {
			ui.Text(c, "OpenVPN Desktop").FontSize(18).Bold()
			status := a.stateLabelColor(t, snap.State)
			ui.Box(c).Padding(4, 11).Radius(999).Background(status.Alpha(0.14)).Children(func() {
				ui.Text(c, snap.State.Label()).FontSize(12).Bold().TextColor(status)
			})
			if snap.ProfileName != "" {
				ui.Text(c, snap.ProfileName).FontSize(12).TextColor(t.TextMuted).SingleLine()
			}
			ui.Box(c).Grow(1)
			if snap.State.CanDisconnect() {
				if ui.Button(c, "Disconnect").Clicked() {
					a.disconnect()
				}
			} else {
				if ui.PrimaryButton(c, "Connect").Disabled(snap.IsBusy()).Clicked() {
					a.connect()
				}
			}
		})
		ui.Row(c).WidthPercent(100).Padding(0, 22, 8, 22).Children(func() {
			ui.Tabs(c, &a.tab, "Home", "Profiles", "Logs", "Settings")
		})
		ui.Divider(c)

		switch a.tab {
		case 0:
			ui.Scroll(c).Grow(1).Children(func() { a.home(c) })
		case 1:
			ui.Scroll(c).Grow(1).Children(func() { a.profiles(c) })
		case 2:
			a.logs(c)
		case 3:
			ui.Scroll(c).Grow(1).Children(func() { a.settings(c) })
		}

		if msg := a.activeToast(); msg != "" {
			ui.Row(c).WidthPercent(100).Padding(10, 22).Background(t.Inverse).Children(func() {
				ui.Text(c, msg).FontSize(12).TextColor(t.InverseText).Wrap()
			})
		}
	})
}

func (a *App) home(c *ui.Context) {
	snap := a.svc.Snapshot()
	ui.Column(c).WidthPercent(100).Padding(20, 22).Gap(16).MaxWidth(980).Children(func() {
		if snap.Error != "" {
			t := c.Theme()
			ui.Column(c).WidthPercent(100).Padding(14).Gap(4).Background(t.Danger.Alpha(0.10)).Border(1, t.Danger.Alpha(0.4)).Radius(12).Children(func() {
				ui.Text(c, "Connection problem").Bold().TextColor(t.Danger)
				ui.Text(c, snap.Error).FontSize(12).Wrap()
			})
		}
		if snap.PausedForPortal {
			t := c.Theme()
			ui.Column(c).WidthPercent(100).Padding(14).Gap(4).Background(t.Warning.Alpha(0.12)).Border(1, t.Warning.Alpha(0.45)).Radius(12).Children(func() {
				ui.Text(c, "VPN paused for captive-portal sign-in").Bold().TextColor(t.Warning)
				ui.Text(c, "The tunnel is down while you sign in. Traffic is not protected. Finish signing in, then reconnect.").
					FontSize(12).Wrap()
			})
		}
		a.statusCard(c, snap)
		a.portalCard(c)
		a.warningsCard(c)
		a.publicIPCard(c)
	})
}

func (a *App) statusCard(c *ui.Context, snap model.Snapshot) {
	t := c.Theme()
	a.card(c, "Connection", func() {
		ui.Row(c).Gap(14).AlignItems(ui.Center).Children(func() {
			ui.Text(c, snap.State.Label()).FontSize(26).Bold().TextColor(a.stateLabelColor(t, snap.State))
			if snap.State == model.StateConnected {
				ui.Text(c, formatDuration(snap.Duration(time.Now()))).FontSize(13).TextColor(t.TextMuted)
			}
			if snap.KillSwitch {
				ui.Text(c, "kill switch").FontSize(11).Bold().TextColor(t.Warning)
			}
		})
		a.kv(c, "Profile", orDash(snap.ProfileName))
		a.kv(c, "Server", orDash(snap.Details.ServerLabel()))
		a.kv(c, "Protocol", orDash(snap.Details.Protocol))
		a.kv(c, "Tunnel IP", orDash(firstNonEmpty(snap.Details.VPNIP4, snap.Details.VPNIP6)))
		a.kv(c, "TUN device", orDash(snap.Details.TunName))
		dns := orDash(snap.Details.DNSServers)
		if snap.DNSFallbackUsed {
			dns += " (fallback)"
		}
		a.kv(c, "DNS", dns)
		a.kv(c, "MTU", orDash(snap.Details.MTU))
		a.kv(c, "Download", formatBytes(snap.Stats.BytesIn))
		a.kv(c, "Upload", formatBytes(snap.Stats.BytesOut))
		ui.Text(c, "Counters come from OpenVPN's management interface and reset when the tunnel reconnects.").
			FontSize(11).TextColor(t.TextMuted).Wrap()
	})
}

func (a *App) portalCard(c *ui.Context) {
	t := c.Theme()
	portal := a.svc.Portal()
	snap := a.svc.Snapshot()
	a.card(c, "Network / captive portal", func() {
		switch portal.Status {
		case model.PortalChecking:
			ui.Row(c).Gap(8).AlignItems(ui.Center).Children(func() {
				ui.Spinner(c)
				ui.Text(c, "Checking the network…").TextColor(t.TextMuted)
			})
		case model.PortalInternet:
			ui.Text(c, "Internet is reachable; no sign-in page is intercepting traffic.").FontSize(12)
		case model.PortalDetected:
			ui.Text(c, "A captive portal is intercepting this network.").FontSize(12).TextColor(t.Warning)
			if portal.URL != "" {
				ui.Text(c, portal.URL).FontSize(11).TextColor(t.TextMuted).Wrap()
			}
		case model.PortalOffline:
			ui.Text(c, "Network check failed: "+orDash(portal.Error)).FontSize(12).TextColor(t.Warning).Wrap()
		default:
			ui.Text(c, "Check whether this network needs a browser sign-in page before the VPN can connect.").
				FontSize(12).TextColor(t.TextMuted).Wrap()
		}
		ui.Row(c).Gap(8).Wrap().Children(func() {
			if ui.Button(c, "Check network").Clicked() {
				a.checkPortal()
			}
			if portal.Status == model.PortalDetected && portal.URL != "" {
				if ui.Button(c, "Open sign-in page").Clicked() {
					a.openPortal(portal.URL)
				}
			}
			if !snap.PausedForPortal && snap.State.IsActive() && portal.Status == model.PortalDetected {
				if ui.PrimaryButton(c, "Pause VPN and sign in").Clicked() {
					a.pauseForPortal()
				}
			}
			if snap.PausedForPortal {
				if portal.URL != "" {
					if ui.Button(c, "Open sign-in page").Clicked() {
						a.openPortal(portal.URL)
					}
				}
				if ui.PrimaryButton(c, "I signed in — reconnect").Clicked() {
					a.resumeForPortal()
				}
			}
		})
	})
}

func (a *App) warningsCard(c *ui.Context) {
	t := c.Theme()
	warnings := profile.RuntimeWarnings(a.svc.Settings())
	if !a.helper.Available {
		warnings = append([]string{"The privileged helper is not ready. " + a.helper.Detail}, warnings...)
	}
	if len(warnings) == 0 {
		return
	}
	a.card(c, "Security notices", func() {
		for _, w := range warnings {
			ui.Row(c).Gap(8).Children(func() {
				ui.Text(c, "!").Bold().TextColor(t.Warning).Width(12)
				ui.Text(c, w).FontSize(12).TextColor(t.Warning).Wrap()
			})
		}
	})
}

func (a *App) publicIPCard(c *ui.Context) {
	t := c.Theme()
	state := a.svc.PublicIP()
	a.card(c, "Public IP address", func() {
		switch {
		case state.Loading:
			ui.Row(c).Gap(8).AlignItems(ui.Center).Children(func() {
				ui.Spinner(c)
				ui.Text(c, "Checking…").TextColor(t.TextMuted)
			})
		case state.Error != "":
			ui.Text(c, state.Error).FontSize(12).TextColor(t.Danger).Wrap()
		case state.IP != "":
			ui.Text(c, state.IP).FontSize(20).Bold()
			via := "direct connection"
			if state.ThroughTunnel {
				via = "through the tunnel"
			}
			ui.Text(c, fmt.Sprintf("Checked %s %s.", state.CheckedAt.Format("15:04:05"), via)).
				FontSize(11).TextColor(t.TextMuted)
		default:
			ui.Text(c, "Not checked yet. The app contacts the configured public-IP service only when you ask.").
				FontSize(12).TextColor(t.TextMuted).Wrap()
		}
		if ui.Button(c, "Check now").Clicked() {
			a.checkIP()
		}
	})
}

func (a *App) profiles(c *ui.Context) {
	t := c.Theme()
	profiles := a.svc.Profiles()
	snap := a.svc.Snapshot()
	ui.Column(c).WidthPercent(100).Padding(20, 22).Gap(14).MaxWidth(980).Children(func() {
		ui.Row(c).AlignItems(ui.Center).Gap(10).Children(func() {
			ui.Text(c, "Profiles").FontSize(18).Bold()
			ui.Text(c, fmt.Sprintf("%d stored", len(profiles))).FontSize(12).TextColor(t.TextMuted)
			ui.Box(c).Grow(1)
			if ui.PrimaryButton(c, "Import .ovpn").Clicked() {
				a.openImportDialog()
			}
		})
		if len(profiles) == 0 {
			a.card(c, "", func() {
				ui.Text(c, "No profiles yet.").Bold()
				ui.Text(c, "Import a .ovpn file. If it references ca/cert/key files, select them in the same dialog; "+
					"the app inlines them and stores the result encrypted.").FontSize(12).TextColor(t.TextMuted).Wrap()
			})
		}
		for _, p := range profiles {
			a.profileCard(c, p, p.ID == snap.ProfileID)
		}
		if a.editProfile != "" {
			a.credentialsCard(c)
		}
		if len(a.importOutcomes) > 0 {
			a.importCard(c)
		}
	})

	if a.confirmDeleteOpen {
		open := true
		res := ui.AlertDialog(c, &open, "Delete profile?",
			fmt.Sprintf("Delete “%s” and its saved credentials? This cannot be undone.", a.confirmDeleteName),
			"Cancel", "Delete")
		if res == 1 {
			a.deleteProfile(a.confirmDeleteID, a.confirmDeleteName)
			a.confirmDeleteOpen = false
		} else if res != -1 {
			a.confirmDeleteOpen = false
		}
	}
}

func (a *App) profileCard(c *ui.Context, p model.ProfileSummary, selected bool) {
	t := c.Theme()
	border := t.Border
	if selected {
		border = t.Accent
	}
	ui.Column(c).WidthPercent(100).Padding(14).Gap(8).Background(t.Surface).Border(1, border).Radius(12).Children(func() {
		ui.Row(c).Gap(10).AlignItems(ui.Center).Children(func() {
			ui.Text(c, p.Emoji).FontSize(22)
			ui.Column(c).Grow(1).MinWidth(0).Children(func() {
				ui.Text(c, p.Name).Bold()
				ui.Text(c, strings.Join(nonEmpty(p.Metadata.DisplayServer(), p.Metadata.ProtocolLabel()), " · ")).
					FontSize(12).TextColor(t.TextMuted).SingleLine()
			})
			if selected {
				ui.Text(c, "Selected").FontSize(11).Bold().TextColor(t.Accent)
			}
		})
		ui.Row(c).Gap(8).Wrap().Children(func() {
			a.badge(c, "CA "+yesNo(p.Metadata.HasCA))
			a.badge(c, "Client certificate "+yesNo(p.Metadata.HasClientCert || p.Metadata.HasClientKey))
			if p.Metadata.NeedsPassword {
				a.badge(c, "username/password")
			}
			if p.Metadata.StaticChallenge != "" {
				a.badge(c, "static challenge")
			}
			if p.Metadata.ExternalPKI {
				a.badge(c, "certificate-less")
			}
		})
		if p.Warning != "" {
			ui.Text(c, p.Warning).FontSize(11).TextColor(t.Warning).Wrap()
		}
		ui.Row(c).Gap(8).Children(func() {
			if !selected {
				if ui.Button(c, "Use").Clicked() {
					if err := a.svc.SelectProfile(p.ID); err == nil {
						a.editProfile = p.ID
						a.loadCredentials(p.ID)
					}
				}
			}
			if ui.Button(c, "Credentials").Clicked() {
				a.editProfile = p.ID
				a.loadCredentials(p.ID)
			}
			if ui.Button(c, "Delete").TextColor(t.Danger).Clicked() {
				a.confirmDeleteOpen = true
				a.confirmDeleteID = p.ID
				a.confirmDeleteName = p.Name
			}
		})
	})
}

func (a *App) credentialsCard(c *ui.Context) {
	t := c.Theme()
	p, ok := a.svc.Profile(a.editProfile)
	if !ok {
		return
	}
	md := p.Summary.Metadata
	a.card(c, "Credentials — "+p.Summary.Name, func() {
		ui.Text(c, "Saved in the encrypted vault. They are written to a mode-0600 temporary file only while connecting, "+
			"and the file is overwritten and deleted when the session ends.").FontSize(11).TextColor(t.TextMuted).Wrap()
		ui.Row(c).Gap(10).Wrap().Children(func() {
			ui.TextInput(c, &a.credUsername).Label("Username").Width(240)
			ui.TextInput(c, &a.credPassword).Password().Label("Password").Width(240)
			ui.TextInput(c, &a.credKeyPass).Password().Label("Private-key password (optional)").Width(240)
		})
		if md.StaticChallenge != "" {
			ui.Text(c, "Static challenge: "+md.StaticChallenge).FontSize(12).TextColor(t.Warning).Wrap()
			ui.TextInput(c, &a.credResponse).Password().Label("Challenge response (used once, not saved)").Width(320)
		}
		ui.Row(c).Gap(8).Children(func() {
			if ui.PrimaryButton(c, "Save credentials").Clicked() {
				a.saveCredentials()
			}
			if ui.Button(c, "Connect with this profile").Clicked() {
				if err := a.svc.SelectProfile(p.Summary.ID); err != nil {
					a.setToast(err.Error())
				} else {
					a.connect()
				}
			}
		})
	})
}

func (a *App) importCard(c *ui.Context) {
	t := c.Theme()
	a.card(c, "Last import", func() {
		for _, o := range a.importOutcomes {
			switch {
			case o.Error != "":
				ui.Text(c, "✕ "+o.FileName+": "+o.Error).FontSize(12).TextColor(t.Danger).Wrap()
			case o.AlreadyImported:
				ui.Text(c, "• "+o.FileName+" was already imported.").FontSize(12).TextColor(t.TextMuted).Wrap()
			default:
				ui.Text(c, "✓ "+o.FileName).FontSize(12).TextColor(t.Success)
			}
			if o.Warning != "" {
				ui.Text(c, o.Warning).FontSize(11).TextColor(t.Warning).Wrap()
			}
		}
	})
}

func (a *App) logs(c *ui.Context) {
	t := c.Theme()
	logs := a.filteredLogs()
	ui.Column(c).Grow(1).Padding(14, 22).Gap(10).Children(func() {
		ui.Row(c).Gap(8).AlignItems(ui.Center).Children(func() {
			ui.SearchField(c, &a.logFilter).Label("Filter logs").Width(300)
			ui.Box(c).Grow(1)
			if ui.Button(c, "Copy all").Clicked() {
				var b strings.Builder
				for _, e := range logs {
					b.WriteString(e.Time.Format("15:04:05") + " [" + string(e.Level) + "] " + e.Message + "\n")
				}
				c.WriteClipboard(b.String())
				a.setToast("Copied " + fmt.Sprint(len(logs)) + " log lines.")
			}
			if ui.Button(c, "Clear").Clicked() {
				a.svc.ClearLogs()
			}
		})
		a.logList.FollowEnd = strings.TrimSpace(a.logFilter) == ""
		ui.List(c, &a.logList, len(logs), func(i int) {
			e := logs[i]
			ui.Row(c).Gap(8).PaddingY(2).AlignItems(ui.Center).Children(func() {
				ui.Text(c, e.Time.Format("15:04:05")).FontSize(11).TextColor(t.TextMuted).Width(64)
				ui.Text(c, levelName(e.Level)).FontSize(11).Bold().TextColor(levelColor(t, e.Level)).Width(44)
				ui.Text(c, e.Message).FontSize(12).Wrap()
			})
		}).Grow(1).Label("Logs")
	})
}

func (a *App) settings(c *ui.Context) {
	t := c.Theme()
	ui.Column(c).WidthPercent(100).Padding(20, 22).Gap(16).MaxWidth(980).Children(func() {
		a.card(c, "DNS leak protection", func() {
			ui.Row(c).Gap(10).AlignItems(ui.Center).Children(func() {
				ui.Switch(c, &a.requireDNS)
				ui.Column(c).Grow(1).Children(func() {
					ui.Text(c, "Require tunnel DNS").Bold()
					ui.Text(c, "When the server pushes no usable resolver, stop the connection instead of using the "+
						"underlying network's DNS. This is the fail-closed default.").FontSize(11).TextColor(t.TextMuted).Wrap()
				})
			})
			ui.TextInput(c, &a.fallbackDNS).Label("Fallback DNS servers (comma separated, used through the tunnel)").Width(420)
			ui.Text(c, "Example: 1.1.1.1, 9.9.9.9, 2606:4700:4700::1111. IPv6 entries are dropped when the tunnel has no IPv6 address.").
				FontSize(11).TextColor(t.TextMuted).Wrap()
		})

		a.card(c, "Tunnel", func() {
			ui.Row(c).Gap(12).Wrap().Children(func() {
				ui.Column(c).Gap(4).Children(func() {
					ui.Text(c, "MTU override").FontSize(12).TextColor(t.TextMuted)
					ui.Select(c, &a.mtuMode, []string{"Automatic", "1500", "1400", "1280"}).Width(160)
				})
				ui.Column(c).Gap(4).Children(func() {
					ui.Text(c, "Compression").FontSize(12).TextColor(t.TextMuted)
					ui.Select(c, &a.compression, []string{"Compatibility", "Strict"}).Width(180)
				})
				ui.Column(c).Gap(4).Children(func() {
					ui.Text(c, "Data ciphers").FontSize(12).TextColor(t.TextMuted)
					ui.Select(c, &a.cipher, []string{"Profile default", "AEAD only"}).Width(180)
				})
			})
			ui.Text(c, profile.CompressionDescription(settings.CompressionCompatibility)).FontSize(11).TextColor(t.TextMuted).Wrap()
			ui.Text(c, profile.CipherDescription(settings.CipherAEADOnly)).FontSize(11).TextColor(t.TextMuted).Wrap()
		})

		a.card(c, "Split tunneling (desktop)", func() {
			ui.Text(c, "Android selects apps; a desktop routes by network. Off routes everything through the tunnel.").
				FontSize(11).TextColor(t.TextMuted).Wrap()
			ui.Select(c, &a.splitMode, []string{"All traffic", "Only these networks", "All except these networks"}).Width(260)
			ui.TextInput(c, &a.splitCIDRs).Label("Networks (CIDR, comma separated)").Width(460)
			ui.Text(c, "Example: 192.168.50.0/24, 10.20.0.0/16. “Only these” removes the default route; "+
				"“All except these” routes the listed networks outside the tunnel.").FontSize(11).TextColor(t.TextMuted).Wrap()
			ui.Row(c).Gap(10).AlignItems(ui.Center).Children(func() {
				ui.Switch(c, &a.lanBypass)
				ui.Text(c, "Bypass the VPN for private LAN ranges").FontSize(12)
			})
		})

		a.card(c, "Per-app split tunneling (Linux)", func() {
			if !a.perAppSupported() {
				ui.Text(c, "Per-app split tunneling is implemented for Linux (cgroup v2 + nftables).").
					FontSize(12).TextColor(t.TextMuted).Wrap()
				return
			}
			ui.Text(c, "Route-based split tunneling above is independent; both policies can be active. "+
				"Per-app rules need the privileged helper, cgroup v2, nftables and a non-VPN default route.").
				FontSize(11).TextColor(t.TextMuted).Wrap()
			ui.Select(c, &a.perAppMode, []string{"Off", "Only selected apps use the VPN", "Selected apps bypass the VPN"}).Width(320)
			ui.Row(c).Gap(8).AlignItems(ui.Center).Children(func() {
				ui.SearchField(c, &a.perAppFilter).Label("Filter applications").Width(260)
				if ui.Button(c, "Refresh running apps").Clicked() {
					a.refreshPerApps()
				}
				ui.Text(c, fmt.Sprintf("%d selected", len(a.selectedPerApps()))).FontSize(11).TextColor(t.TextMuted)
			})
			entries := a.filteredPerApps()
			if len(entries) == 0 {
				ui.Text(c, "No running applications match. Add an absolute executable path below.").
					FontSize(11).TextColor(t.TextMuted)
			} else {
				a.perAppList.FollowEnd = false
				ui.List(c, &a.perAppList, len(entries), func(i int) {
					entry := entries[i]
					ui.Row(c).Gap(8).AlignItems(ui.Center).Children(func() {
						ui.Checkbox(c, &entry.Selected, entry.Name())
						ui.Text(c, entry.Path).FontSize(11).TextColor(t.TextMuted).SingleLine()
					})
				}).Height(230).Label("Applications")
			}
			ui.Row(c).Gap(8).AlignItems(ui.Center).Children(func() {
				ui.TextInput(c, &a.perAppManual).Placeholder("/usr/bin/firefox").Label("Add an executable path").Width(320)
				if ui.Button(c, "Add").Clicked() {
					a.addPerAppPath()
				}
			})
			if a.perAppError != "" {
				ui.Text(c, a.perAppError).FontSize(11).TextColor(t.Danger).Wrap()
			}
			ui.Text(c, "DNS: the system resolver is kept on the VPN path so selected apps' system DNS does "+
				"not leak to the ISP; apps using their own DoH/DoT follow the per-app rule. A newly started "+
				"app is moved into its cgroup by a one-second monitor, so there is a short window. Scripts "+
				"match their interpreter, not the script path.").FontSize(11).TextColor(t.TextMuted).Wrap()
		})

		a.card(c, "Kill switch and public IP", func() {
			if runtime.GOOS == "linux" {
				ui.Row(c).Gap(10).AlignItems(ui.Center).Children(func() {
					ui.Switch(c, &a.killSwitch)
					ui.Column(c).Grow(1).Children(func() {
						ui.Text(c, "Kill switch (nftables)").Bold()
						ui.Text(c, "Drop all traffic outside the tunnel except loopback, DHCP, IPv6 neighbour discovery and the "+
							"VPN server. Off by default. If the app is force-killed while it is on, run "+
							"`openvpn-desktop-helper cleanup` to restore networking.").FontSize(11).TextColor(t.TextMuted).Wrap()
					})
				})
			} else {
				ui.Text(c, "The nftables kill switch is implemented for Linux only.").
					FontSize(11).TextColor(t.TextMuted).Wrap()
			}
			ui.Row(c).Gap(10).AlignItems(ui.Center).Children(func() {
				ui.Switch(c, &a.autoIP)
				ui.Text(c, "Check the public IP automatically after connecting").FontSize(12)
			})
			ui.TextInput(c, &a.publicIPURL).Label("Public-IP service URL").Width(420)
		})

		a.card(c, "Appearance", func() {
			ui.Select(c, &a.themeMode, []string{"System", "Light", "Dark"}).Width(180)
		})

		a.card(c, "Startup and system tray", func() {
			ui.Row(c).Gap(10).AlignItems(ui.Center).Children(func() {
				ui.Switch(c, &a.minimizeTray)
				ui.Column(c).Grow(1).Children(func() {
					ui.Text(c, "Keep running in the system tray when the window is closed").FontSize(12)
					ui.Text(c, "The tray icon offers Show Window, Connect, Disconnect and Quit.").
						FontSize(11).TextColor(t.TextMuted).Wrap()
				})
			})
			ui.Row(c).Gap(10).AlignItems(ui.Center).Children(func() {
				ui.Switch(c, &a.autoConnect)
				ui.Column(c).Grow(1).Children(func() {
					ui.Text(c, "Connect automatically after the vault is unlocked").FontSize(12)
					ui.Text(c, "The master password is never stored. Start at login opens the app locked; "+
						"the tunnel connects only after you unlock it.").FontSize(11).TextColor(t.TextMuted).Wrap()
				})
			})
			status := "not installed"
			if a.autostartInstalled {
				status = "installed for this user"
			}
			if !autostart.Supported() {
				ui.Text(c, "Start at login is implemented for Linux (XDG autostart).").
					FontSize(12).TextColor(t.TextMuted).Wrap()
			} else {
				ui.Text(c, "Start at login: "+status).FontSize(12).TextColor(t.TextMuted)
				if a.autostartInstalled {
					if ui.Button(c, "Remove start at login").Clicked() {
						a.removeAutostart()
					}
				} else {
					if ui.PrimaryButton(c, "Install start at login").Clicked() {
						a.installAutostart()
					}
				}
			}
			if a.autostartError != "" {
				ui.Text(c, a.autostartError).FontSize(11).TextColor(t.Danger).Wrap()
			}
		})

		a.card(c, "Captive portal", func() {
			ui.Row(c).Gap(10).AlignItems(ui.Center).Children(func() {
				ui.Switch(c, &a.autoPortalDetect)
				ui.Column(c).Grow(1).Children(func() {
					ui.Text(c, "Detect a captive portal automatically after a failed connection").FontSize(12)
					ui.Text(c, "A plain-HTTP connectivity check is sent to the probe URL. A 204 means internet, "+
						"a redirect or 200 means a portal; an offline result is shown without blocking the app.").
						FontSize(11).TextColor(t.TextMuted).Wrap()
				})
			})
			ui.TextInput(c, &a.portalProbeURL).Label("Connectivity probe URL (http or https)").Width(460)
		})

		if a.settingsError != "" {
			ui.Text(c, a.settingsError).FontSize(12).TextColor(t.Danger).Wrap()
		}
		if ui.PrimaryButton(c, "Save settings").Clicked() {
			a.saveSettings()
		}

		a.card(c, "Privileged helper", func() {
			ui.Text(c, app.HelperHint(a.helper)).FontSize(12).Wrap()
			if ui.Button(c, "Copy setup command").Clicked() {
				c.WriteClipboard("cd desktop && ./scripts/install-helper.sh")
				a.setToast("Setup command copied.")
			}
		})

		a.card(c, "Vault and data", func() {
			ui.Text(c, "Vault: "+a.vaultPath).FontSize(12).Wrap()
			if a.svc != nil {
				ui.Text(c, "Salt fingerprint: "+a.svc.Vault().Fingerprint()).FontSize(11).TextColor(t.TextMuted)
			}
			ui.Text(c, "Profiles, credentials and settings are encrypted together. Logs stay in memory and are never written to disk. "+
				"There is no analytics, crash reporter or backend.").FontSize(11).TextColor(t.TextMuted).Wrap()
			if ui.Button(c, "Lock vault").TextColor(t.Danger).Clicked() {
				a.lock()
			}
		})
	})
}

// ---- small builders ----

func (a *App) card(c *ui.Context, title string, body func()) {
	t := c.Theme()
	ui.Column(c).WidthPercent(100).Padding(16).Gap(10).Background(t.Surface).Border(1, t.Border).Radius(12).Children(func() {
		if title != "" {
			ui.Text(c, title).FontSize(15).Bold()
		}
		body()
	})
}

func (a *App) kv(c *ui.Context, label, value string) {
	t := c.Theme()
	ui.Row(c).Gap(10).Children(func() {
		ui.Text(c, label).FontSize(12).TextColor(t.TextMuted).Width(110)
		ui.Text(c, value).FontSize(12).Wrap()
	})
}

func (a *App) badge(c *ui.Context, text string) {
	t := c.Theme()
	ui.Box(c).Padding(2, 8).Radius(999).Background(t.Border.Alpha(0.5)).Children(func() {
		ui.Text(c, text).FontSize(10).TextColor(t.TextMuted)
	})
}

// ---- formatting ----

func formatBytes(n int64) string {
	if n <= 0 {
		return "0 B"
	}
	const unit = 1024
	if n < unit {
		return fmt.Sprintf("%d B", n)
	}
	div, exp := int64(unit), 0
	for v := n / unit; v >= unit; v /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.2f %ciB", float64(n)/float64(div), "KMGTPE"[exp])
}

func formatDuration(d time.Duration) string {
	if d < 0 {
		d = 0
	}
	d = d.Round(time.Second)
	h := int(d.Hours())
	m := int(d.Minutes()) % 60
	s := int(d.Seconds()) % 60
	if h > 0 {
		return fmt.Sprintf("%dh %02dm %02ds", h, m, s)
	}
	return fmt.Sprintf("%02dm %02ds", m, s)
}

func orDash(s string) string {
	if strings.TrimSpace(s) == "" {
		return "—"
	}
	return s
}

func yesNo(v bool) string {
	if v {
		return "yes"
	}
	return "no"
}

func nonEmpty(values ...string) []string {
	out := make([]string, 0, len(values))
	for _, v := range values {
		if strings.TrimSpace(v) != "" {
			out = append(out, v)
		}
	}
	return out
}

func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if strings.TrimSpace(v) != "" {
			return v
		}
	}
	return ""
}

func levelName(l model.LogLevel) string {
	switch l {
	case model.LogWarn:
		return "WARN"
	case model.LogError:
		return "ERROR"
	default:
		return "INFO"
	}
}

func levelColor(t *ui.Theme, l model.LogLevel) ui.Color {
	switch l {
	case model.LogWarn:
		return t.Warning
	case model.LogError:
		return t.Danger
	default:
		return t.Text
	}
}
