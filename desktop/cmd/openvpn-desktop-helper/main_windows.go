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
  openvpn-desktop-helper install-service [--helper <path>] [--user-config <path>]
  openvpn-desktop-helper uninstall-service [--purge]
  openvpn-desktop-helper run [options]`)
}

func main() {
	if len(os.Args) > 1 && os.Args[1] == "service" {
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
	os.Exit(helper.Main(os.Args[1:], os.Environ()))
}
