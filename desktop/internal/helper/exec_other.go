//go:build !unix

package helper

import "os"

// The privileged helper is a Linux/Unix component. On other platforms the
// package still compiles so the Go client can be built, but no trusted binary
// is accepted (fail closed).
func ownershipOK(os.FileInfo) bool { return false }
