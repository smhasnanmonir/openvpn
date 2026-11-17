//go:build linux

package perapp

import (
	"os"
	"testing"
)

func TestMatch(t *testing.T) {
	sel := []string{"/usr/bin/firefox", "/opt/app/bin/app"}
	if !Match(sel, "/usr/bin/firefox") {
		t.Fatal("exact path should match")
	}
	if !Match(sel, "/usr/bin/firefox (deleted)") {
		t.Fatal("deleted suffix should be normalized")
	}
	if Match(sel, "/usr/bin/firefox-esr") {
		t.Fatal("a different path must not match")
	}
	if Match(sel, "") {
		t.Fatal("empty path must not match")
	}
}

func TestScanFindsTestBinary(t *testing.T) {
	exe, err := os.Executable()
	if err != nil {
		t.Skip(err)
	}
	exe = NormalizeExe(exe)
	found := false
	for _, app := range Scan() {
		if app.Exe == exe {
			found = true
			if len(app.PIDs) == 0 {
				t.Fatal("test binary has no PIDs")
			}
			break
		}
	}
	if !found {
		t.Fatalf("Scan did not find the test binary %q", exe)
	}
}
