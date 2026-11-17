package helper

import "fmt"

// Version is the helper build version. Release builds set it with -ldflags
// from mygo.json; it is written next to the installed helper so the GUI can
// detect a stale service after an app update.
var Version = "dev"

func versionCommand() int {
	fmt.Println(Version)
	return 0
}
