//go:build windows

package main

import (
	"fmt"
	"os"

	"github.com/smhasnanmonir/openvpn/desktop/internal/helper"
	"github.com/smhasnanmonir/openvpn/desktop/internal/winhelper"
)

func usage() {
	fmt.Fprintln(os.Stderr, `usage:
  openvpn-desktop-helper service --config <service.json>
  openvpn-desktop-helper install-service <service.json>
  openvpn-desktop-helper uninstall-service
  openvpn-desktop-helper run [options]`)
}

func main() {
	if len(os.Args) > 1 {
		switch os.Args[1] {
		case "install-service":
			if len(os.Args) < 3 {
				usage()
				os.Exit(1)
			}
			exe, err := os.Executable()
			if err != nil {
				fmt.Fprintln(os.Stderr, "install-service:", err)
				os.Exit(1)
			}
			if err := winhelper.Install(exe, os.Args[2]); err != nil {
				fmt.Fprintln(os.Stderr, "install-service:", err)
				os.Exit(1)
			}
			fmt.Println("installed OpenVPNDesktopHelper")
			return
		case "uninstall-service":
			if err := winhelper.Uninstall(); err != nil {
				fmt.Fprintln(os.Stderr, "uninstall-service:", err)
				os.Exit(1)
			}
			fmt.Println("removed OpenVPNDesktopHelper")
			return
		case "service":
			config := ""
			for i := 2; i < len(os.Args); i++ {
				if os.Args[i] == "--config" && i+1 < len(os.Args) {
					config = os.Args[i+1]
					i++
				}
			}
			if config == "" {
				usage()
				os.Exit(1)
			}
			if err := winhelper.RunService(config); err != nil {
				fmt.Fprintln(os.Stderr, "service:", err)
				os.Exit(1)
			}
			return
		}
	}
	os.Exit(helper.Main(os.Args[1:], os.Environ()))
}
