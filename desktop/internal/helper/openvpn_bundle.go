package helper

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"os"
	"path/filepath"
)

// Windows ships without an OpenVPN engine, so the app bundles the official
// installer next to the executable and installs it once, elevated. That keeps
// setup to a single download and a single UAC prompt.
//
// bundledOpenVPNMSI is the file MyGo copies from resources/windows-<arch>/.
const bundledOpenVPNMSI = "openvpn-installer.msi"

// bundledOpenVPNVersion is the OpenVPN release inside that installer. It is
// shown in errors and warnings so the user knows what was attempted.
const bundledOpenVPNVersion = "2.7.1-I001"

// bundledOpenVPNSHA256 returns the expected digest of the bundled installer.
// The installer sits in a directory the signed-in user can write, and it is
// about to run with administrator rights, so the helper hashes it before
// handing it to msiexec and refuses anything that does not match. The values
// are pinned by scripts/fetch-openvpn-msi.sh; keep both in sync.
func bundledOpenVPNSHA256(goarch string) (string, bool) {
	switch goarch {
	case "amd64":
		return "821415899fda40063b2740fa239c557435965c5c9df5a6260cc6f684f02e65b8", true
	case "arm64":
		return "039191156e49c964ed9250c0a191177a15ef963f2d5553bb94038ca711771ae7", true
	}
	return "", false
}

// bundledMSIPath is where the installer sits once MyGo has copied the platform
// resources next to the app executable.
func bundledMSIPath(exeDir string) string {
	return filepath.Join(exeDir, bundledOpenVPNMSI)
}

// fileSHA256 hashes a file without loading it into memory.
func fileSHA256(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

// copyRegularFile stages src at dst. The copy is what gets hashed and
// installed, so a file swapped in the app directory after this point cannot
// change what msiexec runs.
func copyRegularFile(src, dst string, mode os.FileMode) error {
	in, err := os.Open(src)
	if err != nil {
		return fmt.Errorf("open %s: %w", src, err)
	}
	defer in.Close()
	out, err := os.OpenFile(dst, os.O_WRONLY|os.O_CREATE|os.O_EXCL, mode)
	if err != nil {
		return fmt.Errorf("create %s: %w", dst, err)
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		return fmt.Errorf("copy %s: %w", dst, err)
	}
	if err := out.Sync(); err != nil {
		out.Close()
		return fmt.Errorf("sync %s: %w", dst, err)
	}
	return out.Close()
}
