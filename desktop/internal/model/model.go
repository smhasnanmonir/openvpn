// Package model holds the domain types shared by the desktop client's
// services and views. It has no dependencies on the UI toolkit.
package model

import "time"

// VPNState is the client-visible connection state. The zero value is
// disconnected so a fresh snapshot is always safe.
type VPNState string

const (
	StateDisconnected  VPNState = "disconnected"
	StatePreparing     VPNState = "preparing"
	StateConnecting    VPNState = "connecting"
	StateConnected     VPNState = "connected"
	StateReconnecting  VPNState = "reconnecting"
	StateDisconnecting VPNState = "disconnecting"
	StateAuthFailed    VPNState = "auth-failed"
	StateError         VPNState = "error"
)

// IsActive reports whether the session is trying to be up or is up.
func (s VPNState) IsActive() bool {
	switch s {
	case StatePreparing, StateConnecting, StateConnected, StateReconnecting:
		return true
	default:
		return false
	}
}

// IsConnected is true only while traffic is actually protected.
func (s VPNState) IsConnected() bool { return s == StateConnected }

// CanDisconnect reports whether a disconnect action makes sense.
func (s VPNState) CanDisconnect() bool {
	return s.IsActive() || s == StateAuthFailed || s == StateError
}

func (s VPNState) Label() string {
	switch s {
	case StateDisconnected:
		return "Disconnected"
	case StatePreparing:
		return "Preparing"
	case StateConnecting:
		return "Connecting"
	case StateConnected:
		return "Protected"
	case StateReconnecting:
		return "Reconnecting"
	case StateDisconnecting:
		return "Disconnecting"
	case StateAuthFailed:
		return "Authentication failed"
	case StateError:
		return "Error"
	default:
		return string(s)
	}
}

// TrafficStats is the tunnel's byte/packet counters. BytesIn is received from
// the VPN server (download); BytesOut is sent to it (upload).
type TrafficStats struct {
	BytesIn    int64 `json:"bytesIn"`
	BytesOut   int64 `json:"bytesOut"`
	PacketsIn  int64 `json:"packetsIn"`
	PacketsOut int64 `json:"packetsOut"`
}

func (t TrafficStats) HasData() bool {
	return t.BytesIn > 0 || t.BytesOut > 0 || t.PacketsIn > 0 || t.PacketsOut > 0
}

// ConnectionDetails mirrors what the management interface reports.
type ConnectionDetails struct {
	ServerHost  string `json:"serverHost,omitempty"`
	ServerPort  string `json:"serverPort,omitempty"`
	Protocol    string `json:"protocol,omitempty"`
	ServerIP    string `json:"serverIp,omitempty"`
	VPNIP4      string `json:"vpnIp4,omitempty"`
	VPNIP6      string `json:"vpnIp6,omitempty"`
	MTU         string `json:"mtu,omitempty"`
	TunName     string `json:"tunName,omitempty"`
	DNSServers  string `json:"dnsServers,omitempty"`
	DNSFallback bool   `json:"dnsFallback,omitempty"`
}

// ServerLabel renders host:port when known.
func (d ConnectionDetails) ServerLabel() string {
	if d.ServerHost == "" {
		return ""
	}
	if d.ServerPort == "" {
		return d.ServerHost
	}
	return d.ServerHost + ":" + d.ServerPort
}

// Snapshot is the single source of truth the UI renders.
type Snapshot struct {
	State           VPNState          `json:"state"`
	ProfileID       string            `json:"profileId,omitempty"`
	ProfileName     string            `json:"profileName,omitempty"`
	ProfileEmoji    string            `json:"profileEmoji,omitempty"`
	ConnectedAt     time.Time         `json:"connectedAt,omitempty"`
	Error           string            `json:"error,omitempty"`
	Details         ConnectionDetails `json:"details"`
	Stats           TrafficStats      `json:"stats"`
	PausedForPortal bool              `json:"pausedForPortal,omitempty"`
	DNSFallbackUsed bool              `json:"dnsFallbackUsed,omitempty"`
	KillSwitch      bool              `json:"killSwitch,omitempty"`
}

func (s Snapshot) IsBusy() bool {
	return s.State == StatePreparing || s.State == StateConnecting || s.State == StateDisconnecting
}

// Duration is how long the current session has been connected, or zero.
func (s Snapshot) Duration(now time.Time) time.Duration {
	if s.ConnectedAt.IsZero() {
		return 0
	}
	return now.Sub(s.ConnectedAt)
}

// LogLevel classifies a log line for the UI.
type LogLevel string

const (
	LogInfo  LogLevel = "info"
	LogWarn  LogLevel = "warn"
	LogError LogLevel = "error"
)

type LogEntry struct {
	Time    time.Time `json:"time"`
	Level   LogLevel  `json:"level"`
	Message string    `json:"message"`
}

// PublicIPState is the UI state of the optional public-IP check.
type PublicIPState struct {
	Loading       bool      `json:"loading"`
	IP            string    `json:"ip,omitempty"`
	Error         string    `json:"error,omitempty"`
	CheckedAt     time.Time `json:"checkedAt,omitempty"`
	ThroughTunnel bool      `json:"throughTunnel,omitempty"`
}

// PortalStatus is the result of the captive-portal probe.
type PortalStatus string

const (
	PortalUnknown  PortalStatus = ""
	PortalChecking PortalStatus = "checking"
	PortalInternet PortalStatus = "internet"
	PortalDetected PortalStatus = "portal"
	PortalOffline  PortalStatus = "offline"
)

// PortalState is the UI state of the captive-portal check.
type PortalState struct {
	Status    PortalStatus `json:"status"`
	URL       string       `json:"url,omitempty"`
	Error     string       `json:"error,omitempty"`
	ProbeURL  string       `json:"probeUrl,omitempty"`
	CheckedAt time.Time    `json:"checkedAt,omitempty"`
}

// ProfileMetadata is the non-secret information extracted from a profile.
type ProfileMetadata struct {
	ServerHost      string `json:"serverHost,omitempty"`
	ServerPort      string `json:"serverPort,omitempty"`
	Protocol        string `json:"protocol,omitempty"`
	Autologin       bool   `json:"autologin,omitempty"`
	NeedsPassword   bool   `json:"needsPassword,omitempty"`
	StaticChallenge string `json:"staticChallenge,omitempty"`
	PrivateKeyNeed  bool   `json:"privateKeyPasswordRequired,omitempty"`
	ExternalPKI     bool   `json:"externalPki,omitempty"`
	ClientCertOff   bool   `json:"clientCertNotRequired,omitempty"`
	ProfileName     string `json:"profileName,omitempty"`
	FriendlyName    string `json:"friendlyName,omitempty"`
	HasCA           bool   `json:"hasCa,omitempty"`
	HasClientCert   bool   `json:"hasClientCert,omitempty"`
	HasClientKey    bool   `json:"hasClientKey,omitempty"`
	HasTLSKey       bool   `json:"hasTlsKey,omitempty"`
}

func (m ProfileMetadata) DisplayServer() string {
	if m.ServerHost == "" {
		return ""
	}
	if m.ServerPort == "" {
		return m.ServerHost
	}
	return m.ServerHost + ":" + m.ServerPort
}

func (m ProfileMetadata) ProtocolLabel() string {
	if m.Protocol == "" {
		return ""
	}
	// Upper-case for display without importing strings in every caller.
	b := []byte(m.Protocol)
	for i, c := range b {
		if c >= 'a' && c <= 'z' {
			b[i] = c - 32
		}
	}
	return string(b)
}

// ProfileSummary is the stored, non-secret half of a profile.
type ProfileSummary struct {
	ID          string          `json:"id"`
	Name        string          `json:"name"`
	Emoji       string          `json:"emoji"`
	Metadata    ProfileMetadata `json:"metadata"`
	CreatedAt   time.Time       `json:"createdAt"`
	UpdatedAt   time.Time       `json:"updatedAt"`
	ContentHash string          `json:"contentHash,omitempty"`
	Warning     string          `json:"warning,omitempty"`
}

// StoredProfile is a profile as persisted: summary plus the (encrypted at
// rest) config body.
type StoredProfile struct {
	Summary ProfileSummary `json:"summary"`
	Config  string         `json:"config"`
}

// Credentials are the per-profile secrets. They are encrypted in the vault and
// only written to a mode-0600 temp file for the duration of a connection.
type Credentials struct {
	Username           string `json:"username,omitempty"`
	Password           string `json:"password,omitempty"`
	PrivateKeyPassword string `json:"privateKeyPassword,omitempty"`
	// Response is a static-challenge response. The app never persists it in
	// the vault: it is supplied for one connection only.
	Response string `json:"-"`
}

func (c Credentials) IsEmpty() bool {
	return c.Username == "" && c.Password == "" && c.PrivateKeyPassword == "" && c.Response == ""
}

// ImportOutcome is the result of importing one file.
type ImportOutcome struct {
	FileName        string `json:"fileName"`
	ProfileID       string `json:"profileId,omitempty"`
	AlreadyImported bool   `json:"alreadyImported,omitempty"`
	Warning         string `json:"warning,omitempty"`
	Error           string `json:"error,omitempty"`
}

func (o ImportOutcome) OK() bool { return o.Error == "" }
