package ovpn

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"time"

	"github.com/smhasnanmonir/openvpn/desktop/internal/model"
	"github.com/smhasnanmonir/openvpn/desktop/internal/security"
	"github.com/smhasnanmonir/openvpn/desktop/internal/serviceproto"
)

// startService starts a session through the installed privileged helper
// service (Windows/macOS). The management interface remains TCP loopback.
func startService(ctx context.Context, spec Spec) (*Session, error) {
	if spec.Helper.ServiceAddr == "" || spec.Helper.ServiceToken == "" {
		return nil, fmt.Errorf("the helper service endpoint or token is missing")
	}
	if spec.Verb <= 0 {
		spec.Verb = 3
	}
	password := randomHex(24)
	port, err := freePort()
	if err != nil {
		return nil, err
	}
	mgmtAddr := fmt.Sprintf("127.0.0.1:%d", port)

	conn, err := (&net.Dialer{Timeout: 10 * time.Second}).DialContext(ctx, "tcp", spec.Helper.ServiceAddr)
	if err != nil {
		return nil, fmt.Errorf("connect to the helper service: %w", err)
	}
	s := &Session{
		spec:        spec,
		events:      make(chan Event, 512),
		stopped:     make(chan struct{}),
		serviceConn: conn,
		serviceEnc:  json.NewEncoder(conn),
	}
	job := serviceproto.Job{
		Token:              spec.Helper.ServiceToken,
		Config:             spec.Config,
		Username:           spec.Credentials.Username,
		Password:           spec.Credentials.Password,
		PrivateKeyPassword: spec.Credentials.PrivateKeyPassword,
		Response:           spec.Credentials.Response,
		ManagementAddr:     mgmtAddr,
		ManagementPassword: password,
		RequireDNS:         spec.Settings.RequireTunnelDNS,
		FallbackDNS:        spec.FallbackDNS,
		KillSwitch:         spec.Settings.KillSwitch,
		PerAppMode:         spec.PerAppMode,
		PerAppExecutables:  spec.PerAppExecutables,
		Verb:               spec.Verb,
	}
	if err := s.serviceEnc.Encode(job); err != nil {
		conn.Close()
		return nil, fmt.Errorf("send the helper service job: %w", err)
	}
	go s.readServiceEvents(conn)

	mgmtCtx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	mgmt, err := DialManagement(mgmtCtx, mgmtAddr, password, 20*time.Second)
	if err != nil {
		s.sendServiceStop()
		conn.Close()
		s.finishService(err.Error())
		return nil, err
	}
	s.mgmt = mgmt
	go s.readManagement(mgmt)
	return s, nil
}

func (s *Session) readServiceEvents(conn net.Conn) {
	dec := json.NewDecoder(conn)
	for {
		var msg serviceproto.Message
		if err := dec.Decode(&msg); err != nil {
			s.finishService("helper service disconnected")
			return
		}
		switch msg.Type {
		case "log":
			if details, ok := parseHelperDetails(msg.Line); ok {
				s.emit(Event{Kind: EventDetails, Details: details})
				continue
			}
			clean := security.Sanitize(msg.Line)
			s.logMu.Lock()
			s.recent = append(s.recent, clean)
			if len(s.recent) > 40 {
				s.recent = s.recent[len(s.recent)-40:]
			}
			s.logMu.Unlock()
			s.emit(Event{Kind: EventLog, Log: model.LogEntry{Time: time.Now(), Level: classifyLog(msg.Line), Message: clean}})
		case "error":
			s.emit(Event{Kind: EventLog, Log: model.LogEntry{Time: time.Now(), Level: model.LogError, Message: msg.Error}})
			s.finishService(msg.Error)
			return
		case "exit":
			s.finishService(msg.Error)
			return
		}
	}
}

func (s *Session) sendServiceStop() {
	if s.serviceConn == nil || s.serviceEnc == nil {
		return
	}
	s.serviceMu.Lock()
	defer s.serviceMu.Unlock()
	_ = s.serviceEnc.Encode(serviceproto.Message{Type: "stop"})
}

func (s *Session) finishService(errText string) {
	s.finish.Do(func() {
		s.mu.Lock()
		s.exited = true
		s.mu.Unlock()
		if s.mgmt != nil {
			s.mgmt.Close()
		}
		if s.serviceConn != nil {
			_ = s.serviceConn.Close()
		}
		s.emit(Event{Kind: EventExited, Error: errText})
		close(s.events)
		close(s.done)
	})
}
