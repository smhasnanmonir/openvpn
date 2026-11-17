//go:build windows

package vault

import (
	"os"
	"unsafe"

	"golang.org/x/sys/windows"
)

// RememberSupported reports whether the master password can be stored. Windows
// uses DPAPI: the blob is encrypted with the logged-in user's profile key, so
// another account (or a copied disk) cannot read it.
func RememberSupported() bool { return true }

// SaveRemembered stores the master password for this user.
func SaveRemembered(vaultPath, password string) error {
	in := []byte(password)
	if len(in) == 0 {
		return removeRemembered(vaultPath)
	}
	inBlob := windows.DataBlob{Size: uint32(len(in)), Data: &in[0]}
	var out windows.DataBlob
	if err := windows.CryptProtectData(&inBlob, nil, nil, 0, nil, 0, &out); err != nil {
		return err
	}
	defer windows.LocalFree(windows.Handle(unsafe.Pointer(out.Data)))
	blob := unsafe.Slice(out.Data, out.Size)
	return os.WriteFile(RememberPath(vaultPath), blob, 0o600)
}

// LoadRemembered returns the stored password, or "" when there is none or it
// cannot be decrypted by this user.
func LoadRemembered(vaultPath string) string {
	blob, err := os.ReadFile(RememberPath(vaultPath))
	if err != nil || len(blob) == 0 {
		return ""
	}
	inBlob := windows.DataBlob{Size: uint32(len(blob)), Data: &blob[0]}
	var out windows.DataBlob
	if err := windows.CryptUnprotectData(&inBlob, nil, nil, 0, nil, 0, &out); err != nil {
		return ""
	}
	defer windows.LocalFree(windows.Handle(unsafe.Pointer(out.Data)))
	return string(unsafe.Slice(out.Data, out.Size))
}

// ClearRemembered deletes the stored password.
func ClearRemembered(vaultPath string) error { return removeRemembered(vaultPath) }
