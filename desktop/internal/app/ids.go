package app

import (
	"crypto/rand"
	"encoding/hex"
	"time"
)

func timeNow() time.Time { return time.Now() }

func newID() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return hex.EncodeToString([]byte(time.Now().Format(time.RFC3339Nano)))
	}
	return hex.EncodeToString(b)
}
