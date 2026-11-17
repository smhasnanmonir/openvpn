package app

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"unicode/utf8"

	"github.com/smhasnanmonir/openvpn/desktop/internal/model"
	"github.com/smhasnanmonir/openvpn/desktop/internal/profile"
)

const maxProfileBytes = 2 << 20

var profileEmojis = []string{"🔒", "🌍", "🛡️", "🚀", "⚡", "🌐", "🔐", "🧭"}

// ImportFiles imports one or more .ovpn files and their companion certificate
// files. Every profile is validated, hardened and encrypted before it is
// stored. Nothing from the file is logged.
func (s *Service) ImportFiles(paths []string) []model.ImportOutcome {
	if len(paths) == 0 {
		return []model.ImportOutcome{{Error: "Select at least one .ovpn profile."}}
	}
	companions := map[string][]byte{}
	var profiles []string
	seenCompanion := map[string]string{}
	for _, p := range paths {
		base := filepath.Base(p)
		data, err := readBounded(p, maxProfileBytes)
		if err != nil {
			return []model.ImportOutcome{{FileName: base, Error: err.Error()}}
		}
		if strings.EqualFold(filepath.Ext(base), ".ovpn") {
			profiles = append(profiles, p)
			continue
		}
		if prev, ok := seenCompanion[base]; ok && prev != p {
			return []model.ImportOutcome{{FileName: base, Error: "Two selected files have the same name: " + base}}
		}
		seenCompanion[base] = p
		companions[base] = data
	}
	if len(profiles) == 0 {
		return []model.ImportOutcome{{FileName: filepath.Base(paths[0]),
			Error: "No .ovpn file was selected. Pick a profile ending in .ovpn (and its certificate files if it references them)."}}
	}
	sort.Strings(profiles)

	out := make([]model.ImportOutcome, 0, len(profiles))
	existingCount := len(s.Profiles())
	for i, p := range profiles {
		out = append(out, s.importOne(p, companions, existingCount+i))
	}
	return out
}

func (s *Service) importOne(path string, companions map[string][]byte, index int) model.ImportOutcome {
	fileName := filepath.Base(path)
	fail := func(msg string) model.ImportOutcome {
		s.addLog(model.LogError, "Import failed for "+fileName+": "+msg)
		return model.ImportOutcome{FileName: fileName, Error: msg}
	}

	raw, err := readBounded(path, maxProfileBytes)
	if err != nil {
		return fail(err.Error())
	}
	text := string(raw)

	config, warnings, err := profile.InlineExternalFiles(text, companions)
	if err != nil {
		return fail(err.Error())
	}

	parsed, err := profile.Parse(config, fileName)
	if err != nil {
		return fail(err.Error())
	}
	warnings = append(warnings, parsed.Warnings...)
	md := parsed.Metadata
	if md.ExternalPKI {
		if !profile.DeclaresUserPasswordAuth(config) {
			return fail("This profile has no client certificate or key and no sign-in credentials. " +
				"Import a profile that contains its certificate and key, or a username/password sign-in.")
		}
		config = profile.WithoutClientCertificate(config)
		md.ClientCertOff = true
	}
	if profile.DeclaresExternalPki(config) {
		return fail("This profile requires an external PKI certificate and key provider, which this app does not have. " +
			"Import a profile that contains its certificate and key.")
	}

	negotiable := profile.NegotiableTLSCiphers(config)
	if negotiable != config {
		warnings = append(warnings, "Removed a legacy-only tls-cipher pin so the server can negotiate TLS 1.3.")
		config = negotiable
	}

	beforeServerUsage := config
	withUsage := profile.WithServerCertificateUsage(config)
	if withUsage != config {
		warnings = append(warnings, "Added remote-cert-tls server to require a server certificate.")
		config = withUsage
	}

	trust := profile.ValidateTrust(config)
	if !trust.Allowed {
		return fail(trust.Message)
	}

	clean, removed := profile.SanitizeConfig(config)
	if len(removed) > 0 {
		warnings = append(warnings, "Removed profile directives that could run code or write files: "+strings.Join(removed, ", ")+".")
	}
	if err := profile.ValidateNoExternalFiles(clean); err != nil {
		return fail(err.Error())
	}
	clean = profile.WithServerCertificateUsage(clean)
	if !profile.ValidateTrust(clean).Allowed {
		return fail("This profile does not retain a usable CA or tls-remote trust anchor after sanitization.")
	}

	hash := hashText(clean)
	if id, ok := s.vault.FindByHash(hash); ok {
		_ = s.vault.Select(id)
		return model.ImportOutcome{FileName: fileName, ProfileID: id, AlreadyImported: true}
	}
	if withUsage != beforeServerUsage {
		if id, ok := s.vault.FindByHash(hashText(beforeServerUsage)); ok {
			_ = s.vault.Select(id)
			return model.ImportOutcome{FileName: fileName, ProfileID: id, AlreadyImported: true}
		}
	}

	name := displayName(fileName)
	var sameNamed []model.ProfileSummary
	for _, existing := range s.vault.SameNamed(name) {
		sameNamed = append(sameNamed, existing)
	}
	newCA := profile.CAFingerprint(clean)
	for _, existing := range sameNamed {
		stored, ok := s.vault.Profile(existing.ID)
		if !ok {
			continue
		}
		if oldCA := profile.CAFingerprint(stored.Config); newCA != "" && oldCA != "" && oldCA != newCA {
			warnings = append(warnings, "A profile with this name was imported earlier with a different CA certificate. "+
				"Verify the new CA before trusting this profile.")
			break
		}
	}
	legacy := profile.LegacyCipherFindings(clean)
	if len(legacy) > 0 {
		values := make([]string, 0, len(legacy))
		seen := map[string]bool{}
		for _, f := range legacy {
			v := strings.ToUpper(f.Value)
			if !seen[v] {
				seen[v] = true
				values = append(values, v)
			}
		}
		warnings = append(warnings, "This profile allows non-AEAD data ciphers ("+strings.Join(values, ", ")+
			"). Switch the cipher policy to AEAD-only to refuse them.")
	}
	if profile.HasIgnoredPeerFingerprint(clean) {
		warnings = append(warnings, "This profile contains a peer-fingerprint pin. Server identity is verified through the profile's CA.")
	}

	now := timeNow()
	summary := model.ProfileSummary{
		ID:          newID(),
		Name:        name,
		Emoji:       profileEmojis[index%len(profileEmojis)],
		Metadata:    md,
		CreatedAt:   now,
		UpdatedAt:   now,
		ContentHash: hash,
		Warning:     strings.Join(unique(warnings), "\n"),
	}
	summary.Metadata.HasCA = profile.ScanTrust(clean).HasCA
	if err := s.vault.AddProfile(model.StoredProfile{Summary: summary, Config: clean}); err != nil {
		return fail("Could not save the profile securely: " + err.Error())
	}
	_ = s.SelectProfile(summary.ID)
	s.addLog(model.LogInfo, fmt.Sprintf("Imported profile %q (%s).", name, md.DisplayServer()))
	return model.ImportOutcome{
		FileName:  fileName,
		ProfileID: summary.ID,
		Warning:   summary.Warning,
	}
}

func readBounded(path string, max int64) ([]byte, error) {
	st, err := os.Stat(path)
	if err != nil {
		return nil, err
	}
	if !st.Mode().IsRegular() {
		return nil, errors.New(filepath.Base(path) + " is not a regular file")
	}
	if st.Size() > max {
		return nil, fmt.Errorf("%s is larger than %d bytes", filepath.Base(path), max)
	}
	return os.ReadFile(path)
}

func displayName(fileName string) string {
	base := strings.TrimSuffix(fileName, filepath.Ext(fileName))
	base = strings.Map(func(r rune) rune {
		if r < 32 || r == 127 {
			return -1
		}
		return r
	}, base)
	base = strings.TrimSpace(base)
	if base == "" {
		base = "Imported profile"
	}
	if utf8.RuneCountInString(base) > 60 {
		base = string([]rune(base)[:60])
	}
	return base
}

func hashText(s string) string {
	sum := sha256.Sum256([]byte(s))
	return hex.EncodeToString(sum[:])
}

func unique(list []string) []string {
	seen := map[string]bool{}
	out := make([]string, 0, len(list))
	for _, v := range list {
		v = strings.TrimSpace(v)
		if v == "" || seen[v] {
			continue
		}
		seen[v] = true
		out = append(out, v)
	}
	return out
}
