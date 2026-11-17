//go:build windows

// Package winhelper installs and runs the OpenVPN Desktop helper as a Windows
// service. The service runs as LocalSystem and starts OpenVPN for the GUI.
package winhelper

import (
	"context"
	"fmt"
	"time"

	"golang.org/x/sys/windows/svc"
	"golang.org/x/sys/windows/svc/mgr"

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

// Install creates the service, configured for automatic start, and starts it.
func Install(exePath, configPath string) error {
	m, err := mgr.Connect()
	if err != nil {
		return fmt.Errorf("connect to the service manager: %w", err)
	}
	defer m.Disconnect()
	if s, err := m.OpenService(Name); err == nil {
		s.Close()
		return fmt.Errorf("%s is already installed", Name)
	}
	s, err := m.CreateService(Name, exePath, mgr.Config{
		DisplayName:  "OpenVPN Desktop Helper",
		Description:  "Privileged OpenVPN helper for OpenVPN Desktop",
		StartType:    mgr.StartAutomatic,
		ErrorControl: mgr.ErrorNormal,
	}, "service", "--config", configPath)
	if err != nil {
		return fmt.Errorf("create service: %w", err)
	}
	defer s.Close()
	if err := s.Start(); err != nil {
		return fmt.Errorf("start service: %w", err)
	}
	return nil
}

// Uninstall stops and deletes the service.
func Uninstall() error {
	m, err := mgr.Connect()
	if err != nil {
		return err
	}
	defer m.Disconnect()
	s, err := m.OpenService(Name)
	if err != nil {
		return err
	}
	defer s.Close()
	_, _ = s.Control(svc.Stop)
	for i := 0; i < 20; i++ {
		status, err := s.Query()
		if err != nil || status.State == svc.Stopped {
			break
		}
		time.Sleep(250 * time.Millisecond)
	}
	return s.Delete()
}
