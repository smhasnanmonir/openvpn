package helper

// splitSpec configures Linux per-app split tunneling for one session.
type splitSpec struct {
	Mode        string
	Executables []string
	OpenVPNPID  int
}

// splitController stops the per-app rules and monitoring.
type splitController interface {
	Stop()
}

// vpnProcessSetter attaches the already-started OpenVPN pid to the include
// mode's exempt cgroup. Setup happens before OpenVPN starts so the physical
// route is captured before the VPN changes the main table.
type vpnProcessSetter interface {
	SetVPNProcess(pid int) error
}
