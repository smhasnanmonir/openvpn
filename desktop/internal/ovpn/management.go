package ovpn

import (
	"bufio"
	"context"
	"fmt"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/smhasnanmonir/openvpn/desktop/internal/model"
	"github.com/smhasnanmonir/openvpn/desktop/internal/security"
)

// ManagementEvent is one parsed line from OpenVPN's management interface.
type ManagementEvent struct {
	Kind        string // "state", "bytecount", "log"
	State       string
	Description string
	LocalIP     string
	RemoteIP    string
	RemotePort  string
	LocalPort   string
	TunName     string
	BytesIn     int64
	BytesOut    int64
	LogLevel    model.LogLevel
	Message     string
}

// ManagementClient speaks the OpenVPN 2.x management protocol over TCP.
type ManagementClient struct {
	conn      net.Conn
	password  string
	events    chan ManagementEvent
	done      chan struct{}
	gotState  chan struct{}
	stateOnce sync.Once
	closeOnce sync.Once
	writeMu   sync.Mutex
}

// DialManagement connects to a management endpoint, authenticates, enables
// state and bytecount notifications, and returns a client whose Events channel
// receives parsed lines.
func DialManagement(ctx context.Context, addr, password string, timeout time.Duration) (*ManagementClient, error) {
	if timeout <= 0 {
		timeout = 10 * time.Second
	}
	deadline := time.Now().Add(timeout)
	var lastErr error
	for {
		d := net.Dialer{Timeout: 2 * time.Second}
		conn, err := d.DialContext(ctx, "tcp", addr)
		if err == nil {
			c, err := newManagementClient(conn, password)
			if err == nil {
				return c, nil
			}
			conn.Close()
			lastErr = err
		} else {
			lastErr = err
		}
		if time.Now().After(deadline) {
			return nil, fmt.Errorf("could not connect to the OpenVPN management interface at %s: %w", addr, lastErr)
		}
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(200 * time.Millisecond):
		}
	}
}

func newManagementClient(conn net.Conn, password string) (*ManagementClient, error) {
	c := &ManagementClient{
		conn:     conn,
		password: password,
		events:   make(chan ManagementEvent, 256),
		done:     make(chan struct{}),
		gotState: make(chan struct{}),
	}
	reader := bufio.NewReader(conn)
	if password != "" {
		// The server prompts with "ENTER PASSWORD:" (no newline). Read until the
		// prompt or a banner, then send the password.
		_ = conn.SetReadDeadline(time.Now().Add(3 * time.Second))
		for {
			line, err := reader.ReadString('\n')
			if err != nil && line == "" {
				break
			}
			if strings.Contains(line, "ENTER PASSWORD:") {
				break
			}
			if strings.HasPrefix(line, ">") {
				break
			}
		}
		_ = conn.SetReadDeadline(time.Time{})
		if err := c.send(password); err != nil {
			return nil, err
		}
	}
	if err := c.send("state on"); err != nil {
		return nil, err
	}
	if err := c.send("bytecount 1"); err != nil {
		return nil, err
	}
	// `state on` only reports future transitions. Query the current state now,
	// and poll it, so a management connection that opens after CONNECTED still
	// learns the real state.
	if err := c.send("state"); err != nil {
		return nil, err
	}
	go c.readLoop(reader)
	go c.pollState()
	return c, nil
}

func (c *ManagementClient) pollState() {
	// The initial `state` normally answers immediately; keep polling only if
	// that response is lost or arrives while OpenVPN is still transitioning.
	// Once any state is parsed, `state on` reports future transitions, so the
	// poll stops and the log is not filled with MANAGEMENT: CMD 'state' lines.
	t := time.NewTicker(2 * time.Second)
	defer t.Stop()
	for {
		select {
		case <-c.done:
			return
		case <-c.gotState:
			return
		case <-t.C:
			if err := c.send("state"); err != nil {
				return
			}
		}
	}
}

// Events is the stream of parsed management lines.
func (c *ManagementClient) Events() <-chan ManagementEvent { return c.events }

func (c *ManagementClient) send(line string) error {
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	_ = c.conn.SetWriteDeadline(time.Now().Add(2 * time.Second))
	_, err := fmt.Fprintf(c.conn, "%s\n", line)
	return err
}

// Signal asks OpenVPN to handle a signal (SIGTERM to disconnect, SIGHUP to
// restart).
func (c *ManagementClient) Signal(signal string) error {
	return c.send("signal " + signal)
}

// Close closes the management connection. OpenVPN itself keeps running; use
// Signal first when the tunnel should stop.
func (c *ManagementClient) Close() {
	c.closeOnce.Do(func() {
		close(c.done)
		_ = c.conn.Close()
	})
}

func (c *ManagementClient) readLoop(reader *bufio.Reader) {
	defer close(c.events)
	for {
		line, err := reader.ReadString('\n')
		if err != nil {
			return
		}
		line = strings.TrimRight(line, "\r\n")
		if line == "" {
			continue
		}
		ev, ok := ParseManagementLine(line)
		if ok && ev.Kind == "state" {
			c.stateOnce.Do(func() { close(c.gotState) })
		}
		if !ok {
			// Surface unknown state-looking lines so a protocol change is
			// diagnosable instead of silently leaving the UI in "connecting".
			upper := strings.ToUpper(line)
			if strings.Contains(upper, "STATE") && !strings.HasPrefix(upper, "SUCCESS") {
				select {
				case c.events <- ManagementEvent{Kind: "raw", Message: line}:
				case <-c.done:
					return
				}
			}
			continue
		}
		select {
		case c.events <- ev:
		case <-c.done:
			return
		}
	}
}

// ParseManagementLine parses a ">STATE:", ">BYTECOUNT:" or ">LOG:" line.
//
// The `state` command's one-shot response is a bare CSV line rather than a
// ">STATE:" notification, for example:
//
//	1794865860,CONNECTED,SUCCESS,10.8.0.2,192.168.123.1,1194,,
//	END
//
// It is parsed here too; otherwise a management connection opened after the
// tunnel came up would never leave "connecting".
func ParseManagementLine(line string) (ManagementEvent, bool) {
	switch {
	case strings.HasPrefix(line, ">STATE:"):
		return parseState(strings.TrimPrefix(line, ">STATE:")), true
	case strings.HasPrefix(line, ">BYTECOUNT:"):
		return parseBytecount(strings.TrimPrefix(line, ">BYTECOUNT:")), true
	case strings.HasPrefix(line, ">LOG:"):
		return parseLog(strings.TrimPrefix(line, ">LOG:")), true
	}
	if !strings.HasPrefix(line, ">") && !strings.EqualFold(strings.TrimSpace(line), "END") {
		fields := strings.Split(line, ",")
		if len(fields) >= 3 {
			if _, err := strconv.ParseInt(strings.TrimSpace(fields[0]), 10, 64); err == nil {
				if _, ok := stateFromManagement(strings.TrimSpace(fields[1]), strings.TrimSpace(fields[2])); ok {
					return parseState(line), true
				}
			}
		}
	}
	return ManagementEvent{}, false
}

func parseState(payload string) ManagementEvent {
	fields := strings.Split(payload, ",")
	ev := ManagementEvent{Kind: "state"}
	if len(fields) > 1 {
		ev.State = strings.TrimSpace(fields[1])
	}
	if len(fields) > 2 {
		ev.Description = strings.TrimSpace(fields[2])
	}
	if len(fields) > 3 {
		ev.LocalIP = strings.TrimSpace(fields[3])
	}
	if len(fields) > 4 {
		ev.RemoteIP = strings.TrimSpace(fields[4])
	}
	if len(fields) > 5 {
		ev.RemotePort = strings.TrimSpace(fields[5])
	}
	if len(fields) > 6 {
		ev.LocalPort = strings.TrimSpace(fields[6])
	}
	if len(fields) > 7 {
		// Newer OpenVPN versions append the TUN device name; older ones leave
		// the field empty or duplicate a port number.
		last := strings.TrimSpace(fields[len(fields)-1])
		if last != "" {
			if _, err := strconv.Atoi(last); err != nil {
				ev.TunName = last
			}
		}
	}
	return ev
}

func parseBytecount(payload string) ManagementEvent {
	ev := ManagementEvent{Kind: "bytecount"}
	fields := strings.Split(payload, ",")
	if len(fields) > 0 {
		ev.BytesIn, _ = strconv.ParseInt(strings.TrimSpace(fields[0]), 10, 64)
	}
	if len(fields) > 1 {
		ev.BytesOut, _ = strconv.ParseInt(strings.TrimSpace(fields[1]), 10, 64)
	}
	return ev
}

func parseLog(payload string) ManagementEvent {
	ev := ManagementEvent{Kind: "log", LogLevel: model.LogInfo}
	parts := strings.SplitN(payload, ",", 3)
	if len(parts) == 3 {
		flags := parts[1]
		switch {
		case strings.Contains(flags, "E"):
			ev.LogLevel = model.LogError
		case strings.Contains(flags, "W"):
			ev.LogLevel = model.LogWarn
		}
		ev.Message = security.Sanitize(parts[2])
	} else {
		ev.Message = security.Sanitize(payload)
	}
	return ev
}
