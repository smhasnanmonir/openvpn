package helper

import (
	"fmt"
	"os"

	"github.com/smhasnanmonir/openvpn/desktop/internal/serviceinstall"
)

// installCommand implements `install`, `install-service`, `uninstall` and
// `uninstall-service`. The GUI runs the install form through one elevation
// prompt; the scripts use the same code path.
func installCommand(args []string, uninstall bool) int {
	opts := serviceinstall.Options{Version: Version}
	for i := 0; i < len(args); i++ {
		value := func() string {
			if i+1 >= len(args) {
				return ""
			}
			i++
			return args[i]
		}
		switch args[i] {
		case "--helper":
			opts.HelperPath = value()
		case "--user-config":
			opts.UserConfig = value()
		case "--user":
			opts.UserName = value()
		case "--user-sid":
			opts.UserSID = value()
		case "--listen":
			opts.ListenAddr = value()
		case "--openvpn":
			opts.OpenVPNPath = value()
		case "--purge":
			opts.Purge = true
		default:
			fmt.Fprintf(os.Stderr, "openvpn-desktop-helper: unknown install option %q\n", args[i])
			return 2
		}
	}
	var err error
	if uninstall {
		err = serviceinstall.Uninstall(opts)
	} else {
		err = serviceinstall.Install(opts)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "openvpn-desktop-helper:", err)
		return 1
	}
	if uninstall {
		fmt.Println("removed the OpenVPN Desktop privileged helper")
	} else {
		fmt.Println("installed the OpenVPN Desktop privileged helper")
	}
	return 0
}
