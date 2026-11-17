//go:build windows

// Package winhelper runs the OpenVPN Desktop helper as a Windows service. The
// service runs as LocalSystem and starts OpenVPN for the GUI; installation is
// in internal/serviceinstall.
package winhelper

import (
	"context"
	"fmt"

	"golang.org/x/sys/windows/svc"

	"github.com/smhasnanmonir/openvpn/desktop/internal/helper"
)

const Name = "OpenVPNDesktopHelper"

type serviceHandler struct {
	configPath string
}

func (h *serviceHandler) Execute(args []string, requests <-chan svc.ChangeRequest, changes chan<- svc.Status) (bool, uint32) {
	changes <- svc.Status{State: svc.StartPending}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	errCh := make(chan error, 1)
	go func() { errCh <- helper.ServeContext(ctx, h.configPath) }()
	changes <- svc.Status{State: svc.Running, Accepts: svc.AcceptStop | svc.AcceptShutdown}
	for {
		select {
		case req := <-requests:
			switch req.Cmd {
			case svc.Interrogate:
				changes <- req.CurrentStatus
			case svc.Stop, svc.Shutdown:
				changes <- svc.Status{State: svc.StopPending}
				cancel()
				return false, 0
			}
		case err := <-errCh:
			if err != nil {
				return false, 1
			}
			return false, 0
		}
	}
}

// RunService runs the helper as the Windows service dispatcher.
func RunService(configPath string) error {
	if configPath == "" {
		return fmt.Errorf("a service config path is required")
	}
	return svc.Run(Name, &serviceHandler{configPath: configPath})
}
