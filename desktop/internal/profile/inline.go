package profile

import (
	"fmt"
	"path/filepath"
	"strings"
)

// MaxCompanionBytes bounds a referenced certificate/key file.
const MaxCompanionBytes = 2 << 20

// InlineExternalFiles replaces `ca ca.crt`, `cert client.crt`, `key
// client.key`, `tls-auth ta.key` and similar references with their inline
// blocks, using the companion files the user selected. References that cannot
// be resolved fail the import instead of leaving a profile that would read an
// arbitrary path when the privileged helper runs it.
func InlineExternalFiles(config string, files map[string][]byte) (string, []string, error) {
	lines, trailing := splitConfig(config)
	out := make([]string, 0, len(lines))
	var warnings []string
	block := ""

	for _, raw := range lines {
		t := strings.TrimSpace(raw)
		lt := strings.ToLower(t)
		if block != "" {
			out = append(out, raw)
			if lt == "</"+block+">" {
				block = ""
			}
			continue
		}
		if strings.HasPrefix(lt, "<") && strings.HasSuffix(lt, ">") && !strings.HasPrefix(lt, "</") {
			name := strings.TrimSuffix(strings.TrimPrefix(lt, "<"), ">")
			if inlineTags[name] {
				block = name
			}
			out = append(out, raw)
			continue
		}
		name, value, ok := directive(raw)
		if !ok || value == "" {
			out = append(out, raw)
			continue
		}

		switch name {
		case "ca", "cert", "key", "tls-auth", "tls-crypt", "tls-crypt-v2":
			base, err := referencedBase(value)
			if err != nil {
				return "", nil, fmt.Errorf("%s: %w", name, err)
			}
			data, found := files[base]
			if !found {
				return "", nil, fmt.Errorf("this profile references %q (%s), which was not selected. "+
					"Select the profile and its certificate/key files together, or use a profile "+
					"with inline <ca>/<cert>/<key> blocks", value, name)
			}
			if len(data) > MaxCompanionBytes {
				return "", nil, fmt.Errorf("referenced file %q is larger than %d bytes", base, MaxCompanionBytes)
			}
			body := strings.TrimSpace(string(data))
			if body == "" {
				return "", nil, fmt.Errorf("referenced file %q is empty", base)
			}
			out = append(out, "<"+name+">", body, "</"+name+">")
			warnings = append(warnings, fmt.Sprintf("inlined the referenced %s file %q", name, base))
		case "auth-user-pass":
			// The client supplies credentials from its encrypted vault.
			warnings = append(warnings, "removed the profile's auth-user-pass file reference; credentials are stored encrypted by the client")
		case "askpass":
			warnings = append(warnings, "removed the profile's askpass reference; the private-key password is stored encrypted by the client")
		default:
			out = append(out, raw)
		}
	}
	return joinConfig(out, trailing), warnings, nil
}

func referencedBase(value string) (string, error) {
	v := strings.TrimSpace(value)
	if strings.HasPrefix(v, `"`) && strings.HasSuffix(v, `"`) && len(v) >= 2 {
		v = v[1 : len(v)-1]
	}
	v = strings.TrimSpace(v)
	if v == "" {
		return "", fmt.Errorf("empty file reference")
	}
	// Never follow a directory or an absolute path from a profile: match the
	// file the user selected by its base name only.
	if strings.ContainsAny(v, `/\`) {
		v = filepath.Base(v)
	}
	if v == "." || v == ".." || v == "" {
		return "", fmt.Errorf("unsupported file reference %q", value)
	}
	return v, nil
}

// ValidateNoExternalFiles fails when a profile still points at a file outside
// itself after import.
func ValidateNoExternalFiles(config string) error {
	block := ""
	for _, raw := range strings.Split(config, "\n") {
		t := strings.TrimSpace(raw)
		lt := strings.ToLower(t)
		if block != "" {
			if lt == "</"+block+">" {
				block = ""
			}
			continue
		}
		if strings.HasPrefix(lt, "<") && strings.HasSuffix(lt, ">") && !strings.HasPrefix(lt, "</") {
			name := strings.TrimSuffix(strings.TrimPrefix(lt, "<"), ">")
			if inlineTags[name] {
				block = name
			}
			continue
		}
		name, value, ok := directive(raw)
		if !ok || value == "" {
			continue
		}
		switch name {
		case "ca", "cert", "key", "tls-auth", "tls-crypt", "tls-crypt-v2", "askpass":
			return fmt.Errorf("the profile still references the external file %q (%s); import the profile with its companion files so its material is inline", value, name)
		case "config":
			return fmt.Errorf("the profile includes another file (config %s), which is not allowed", value)
		}
	}
	return nil
}
