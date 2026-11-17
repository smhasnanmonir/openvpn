//go:build !windows

package main

import (
	"os"

	"github.com/smhasnanmonir/openvpn/desktop/internal/helper"
)

func main() {
	os.Exit(helper.Main(os.Args[1:], os.Environ()))
}
