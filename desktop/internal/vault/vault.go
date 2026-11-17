// Package vault persists profiles, credentials and settings in a single
// password-encrypted file. The Android client uses the hardware-backed
// Keystore; a desktop has no equivalent non-exportable key, so the vault uses
// Argon2id to derive an AES-256-GCM key from a master password. Nothing is
// written in plaintext: profiles, credentials and settings all live inside the
// ciphertext, and the file is mode 0600 in a mode 0700 directory.
package vault

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"sync"

	"golang.org/x/crypto/argon2"

	"github.com/smhasnanmonir/openvpn/desktop/internal/model"
	"github.com/smhasnanmonir/openvpn/desktop/internal/settings"
)

const (
	magic       = "OVPNDESK"
	formatByte  = 1
	saltLen     = 16
	keyLen      = 32
	argonTime   = 3
	argonMemory = 64 * 1024
	argonThread = 4
	minPassword = 8
)

var (
	ErrNotInitialized = errors.New("vault does not exist yet")
	ErrWrongPassword  = errors.New("wrong master password")
	ErrLocked         = errors.New("vault is locked")
	ErrExists         = errors.New("vault already exists")
)

// Data is the plaintext content of the vault.
type Data struct {
	Version         int                            `json:"version"`
	Profiles        map[string]model.StoredProfile `json:"profiles"`
	Credentials     map[string]model.Credentials   `json:"credentials"`
	Settings        settings.Settings              `json:"settings"`
	SelectedProfile string                         `json:"selectedProfile,omitempty"`
}

func newData() *Data {
	return &Data{
		Version:     1,
		Profiles:    map[string]model.StoredProfile{},
		Credentials: map[string]model.Credentials{},
		Settings:    settings.Default(),
	}
}

// Vault is safe for concurrent use.
type Vault struct {
	mu   sync.RWMutex
	path string
	key  []byte
	salt []byte
	data *Data
}

// DefaultPath returns the per-user vault file. OPENVPN_DESKTOP_DATA_DIR
// overrides it for portable installs and tests.
func DefaultPath() (string, error) {
	if dir := os.Getenv("OPENVPN_DESKTOP_DATA_DIR"); dir != "" {
		return filepath.Join(dir, "vault.ovpnx"), nil
	}
	base, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(base, "openvpn-desktop", "vault.ovpnx"), nil
}

// Exists reports whether a vault file is present.
func Exists(path string) bool {
	st, err := os.Stat(path)
	return err == nil && !st.IsDir()
}

// Create initializes a new vault. It fails if the file already exists.
func Create(path, password string) (*Vault, error) {
	if len(password) < minPassword {
		return nil, fmt.Errorf("the master password must be at least %d characters", minPassword)
	}
	if Exists(path) {
		return nil, ErrExists
	}
	salt := make([]byte, saltLen)
	if _, err := rand.Read(salt); err != nil {
		return nil, err
	}
	key := deriveKey(password, salt)
	v := &Vault{path: path, key: key, salt: salt, data: newData()}
	if err := v.saveLocked(); err != nil {
		return nil, err
	}
	return v, nil
}

// Open decrypts an existing vault.
func Open(path, password string) (*Vault, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil, ErrNotInitialized
		}
		return nil, err
	}
	if len(raw) < len(magic)+1+saltLen+12+16 {
		return nil, fmt.Errorf("vault file is truncated or not an OpenVPN Desktop vault")
	}
	if string(raw[:len(magic)]) != magic {
		return nil, fmt.Errorf("not an OpenVPN Desktop vault")
	}
	if raw[len(magic)] != formatByte {
		return nil, fmt.Errorf("unsupported vault format version %d", raw[len(magic)])
	}
	off := len(magic) + 1
	salt := append([]byte(nil), raw[off:off+saltLen]...)
	off += saltLen
	nonce := raw[off : off+12]
	off += 12
	ciphertext := raw[off:]

	key := deriveKey(password, salt)
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	plain, err := gcm.Open(nil, nonce, ciphertext, aad())
	if err != nil {
		return nil, ErrWrongPassword
	}
	var data Data
	if err := json.Unmarshal(plain, &data); err != nil {
		return nil, fmt.Errorf("vault contents are corrupt: %w", err)
	}
	if data.Profiles == nil {
		data.Profiles = map[string]model.StoredProfile{}
	}
	if data.Credentials == nil {
		data.Credentials = map[string]model.Credentials{}
	}
	data.Settings = data.Settings.Normalize()
	return &Vault{path: path, key: key, salt: salt, data: &data}, nil
}

func aad() []byte {
	return []byte(fmt.Sprintf("%s:%d", magic, formatByte))
}

func deriveKey(password string, salt []byte) []byte {
	return argon2.IDKey([]byte(password), salt, argonTime, argonMemory, argonThread, keyLen)
}

func (v *Vault) saveLocked() error {
	if v.key == nil {
		return ErrLocked
	}
	plain, err := json.Marshal(v.data)
	if err != nil {
		return err
	}
	block, err := aes.NewCipher(v.key)
	if err != nil {
		return err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return err
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return err
	}
	ciphertext := gcm.Seal(nil, nonce, plain, aad())

	var buf bytes.Buffer
	buf.WriteString(magic)
	buf.WriteByte(formatByte)
	buf.Write(v.salt)
	buf.Write(nonce)
	buf.Write(ciphertext)

	dir := filepath.Dir(v.path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(dir, ".vault-*")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	defer os.Remove(tmpName)
	if err := tmp.Chmod(0o600); err != nil {
		tmp.Close()
		return err
	}
	if _, err := tmp.Write(buf.Bytes()); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	if err := os.Rename(tmpName, v.path); err != nil {
		return err
	}
	if d, err := os.Open(dir); err == nil {
		_ = d.Sync()
		d.Close()
	}
	return nil
}

// Save writes changes atomically.
func (v *Vault) Save() error {
	v.mu.Lock()
	defer v.mu.Unlock()
	return v.saveLocked()
}

// Lock forgets the key. Further reads return ErrLocked.
func (v *Vault) Lock() {
	v.mu.Lock()
	defer v.mu.Unlock()
	for i := range v.key {
		v.key[i] = 0
	}
	v.key = nil
	v.data = nil
}

func (v *Vault) locked() bool { return v.key == nil || v.data == nil }

// Path returns the vault file location.
func (v *Vault) Path() string { return v.path }

// Profiles returns profile summaries sorted by name.
func (v *Vault) Profiles() ([]model.ProfileSummary, error) {
	v.mu.RLock()
	defer v.mu.RUnlock()
	if v.locked() {
		return nil, ErrLocked
	}
	out := make([]model.ProfileSummary, 0, len(v.data.Profiles))
	for _, p := range v.data.Profiles {
		out = append(out, p.Summary)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Name == out[j].Name {
			return out[i].ID < out[j].ID
		}
		return out[i].Name < out[j].Name
	})
	return out, nil
}

// Profile returns a stored profile.
func (v *Vault) Profile(id string) (model.StoredProfile, bool) {
	v.mu.RLock()
	defer v.mu.RUnlock()
	if v.locked() {
		return model.StoredProfile{}, false
	}
	p, ok := v.data.Profiles[id]
	return p, ok
}

// FindByHash returns the id of a profile with the given content hash.
func (v *Vault) FindByHash(hash string) (string, bool) {
	v.mu.RLock()
	defer v.mu.RUnlock()
	if v.locked() {
		return "", false
	}
	for id, p := range v.data.Profiles {
		if p.Summary.ContentHash == hash {
			return id, true
		}
	}
	return "", false
}

// SameNamed returns profiles with the given display name.
func (v *Vault) SameNamed(name string) []model.ProfileSummary {
	v.mu.RLock()
	defer v.mu.RUnlock()
	if v.locked() {
		return nil
	}
	var out []model.ProfileSummary
	for _, p := range v.data.Profiles {
		if p.Summary.Name == name {
			out = append(out, p.Summary)
		}
	}
	return out
}

// AddProfile stores a profile and selects it.
func (v *Vault) AddProfile(p model.StoredProfile) error {
	v.mu.Lock()
	defer v.mu.Unlock()
	if v.locked() {
		return ErrLocked
	}
	v.data.Profiles[p.Summary.ID] = p
	v.data.SelectedProfile = p.Summary.ID
	return v.saveLocked()
}

// DeleteProfile removes a profile and its credentials.
func (v *Vault) DeleteProfile(id string) error {
	v.mu.Lock()
	defer v.mu.Unlock()
	if v.locked() {
		return ErrLocked
	}
	delete(v.data.Profiles, id)
	delete(v.data.Credentials, id)
	if v.data.SelectedProfile == id {
		v.data.SelectedProfile = ""
	}
	return v.saveLocked()
}

// Selected returns the selected profile id.
func (v *Vault) Selected() string {
	v.mu.RLock()
	defer v.mu.RUnlock()
	if v.locked() {
		return ""
	}
	return v.data.SelectedProfile
}

// Select sets the selected profile.
func (v *Vault) Select(id string) error {
	v.mu.Lock()
	defer v.mu.Unlock()
	if v.locked() {
		return ErrLocked
	}
	if _, ok := v.data.Profiles[id]; !ok {
		return fmt.Errorf("profile %s does not exist", id)
	}
	v.data.SelectedProfile = id
	return v.saveLocked()
}

// Credentials returns the stored credentials for a profile.
func (v *Vault) Credentials(id string) model.Credentials {
	v.mu.RLock()
	defer v.mu.RUnlock()
	if v.locked() {
		return model.Credentials{}
	}
	return v.data.Credentials[id]
}

// SetCredentials stores credentials for a profile.
func (v *Vault) SetCredentials(id string, c model.Credentials) error {
	v.mu.Lock()
	defer v.mu.Unlock()
	if v.locked() {
		return ErrLocked
	}
	if _, ok := v.data.Profiles[id]; !ok {
		return fmt.Errorf("profile %s does not exist", id)
	}
	if c.IsEmpty() {
		delete(v.data.Credentials, id)
	} else {
		v.data.Credentials[id] = c
	}
	return v.saveLocked()
}

// Settings returns a copy of the settings.
func (v *Vault) Settings() settings.Settings {
	v.mu.RLock()
	defer v.mu.RUnlock()
	if v.locked() {
		return settings.Default()
	}
	return v.data.Settings
}

// UpdateSettings applies fn and validates the result before saving.
func (v *Vault) UpdateSettings(fn func(*settings.Settings) error) error {
	v.mu.Lock()
	defer v.mu.Unlock()
	if v.locked() {
		return ErrLocked
	}
	next := v.data.Settings
	if err := fn(&next); err != nil {
		return err
	}
	if err := next.Validate(); err != nil {
		return err
	}
	v.data.Settings = next
	return v.saveLocked()
}

// ChangePassword re-encrypts the vault with a new master password.
func (v *Vault) ChangePassword(oldPassword, newPassword string) error {
	if len(newPassword) < minPassword {
		return fmt.Errorf("the new master password must be at least %d characters", minPassword)
	}
	if _, err := Open(v.path, oldPassword); err != nil {
		return err
	}
	v.mu.Lock()
	defer v.mu.Unlock()
	if v.locked() {
		return ErrLocked
	}
	salt := make([]byte, saltLen)
	if _, err := rand.Read(salt); err != nil {
		return err
	}
	v.key = deriveKey(newPassword, salt)
	v.salt = salt
	return v.saveLocked()
}

// Fingerprint returns a short non-secret identifier for the vault file, useful
// in diagnostics without revealing the key.
func (v *Vault) Fingerprint() string {
	v.mu.RLock()
	defer v.mu.RUnlock()
	if v.locked() {
		return ""
	}
	sum := sha256.Sum256(v.salt)
	return hex.EncodeToString(sum[:6])
}
