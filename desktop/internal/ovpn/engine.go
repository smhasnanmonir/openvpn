package ovpn

import "runtime"

// bundledEngine is the OpenVPN release the Windows package installs. Keep it in
// sync with scripts/fetch-openvpn-msi.sh; scripts/check-openvpn-bundle.py fails
// when they disagree.
const bundledEngine = "2.7.1-I001"

// EngineLabel says which OpenVPN engine this build will use, so the UI can show
// it instead of leaving users to guess.
func EngineLabel() string {
	if runtime.GOOS == "windows" {
		return "OpenVPN " + bundledEngine + " (bundled, installed with the helper)"
	}
	return "OpenVPN from this system"
}
