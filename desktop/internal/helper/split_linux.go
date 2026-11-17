//go:build linux

package helper

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/smhasnanmonir/openvpn/desktop/internal/perapp"
)

const (
	splitTable        = 51820
	splitMark         = "0x1"
	splitCgroupPrefix = "openvpn-desktop-"
	resolverGroup     = "vpn"
)

type physicalRoute struct {
	v6      bool
	gateway string
	dev     string
}

type linuxSplit struct {
	mode         string
	execs        []string
	rootDir      string
	appDir       string
	vpnDir       string
	appID        uint64
	vpnID        uint64
	openvpnPID   int
	rulePriority int

	mu     sync.Mutex
	routes []physicalRoute

	resolverMu      sync.Mutex
	resolverOrigins map[int]string

	stop chan struct{}
	wg   sync.WaitGroup
}

func startSplit(spec splitSpec) (splitController, error) {
	mode := strings.ToLower(strings.TrimSpace(spec.Mode))
	if mode == "" || mode == "off" {
		return nil, nil
	}
	if mode != "include" && mode != "exclude" {
		return nil, fmt.Errorf("unknown per-app mode %q", spec.Mode)
	}
	if len(spec.Executables) == 0 {
		return nil, errors.New("per-app split tunneling needs at least one executable")
	}
	for _, exe := range spec.Executables {
		if !filepath.IsAbs(exe) || strings.ContainsAny(exe, "\r\n\x00") {
			return nil, fmt.Errorf("per-app executable must be an absolute path: %q", exe)
		}
	}
	cgRoot := cgroupRoot()
	if _, err := os.Stat(filepath.Join(cgRoot, "cgroup.controllers")); err != nil {
		return nil, fmt.Errorf("per-app split tunneling needs cgroup v2 at %s", cgRoot)
	}

	root := filepath.Join(cgRoot, splitCgroupPrefix+strconv.Itoa(os.Getpid()))
	appDir := filepath.Join(root, "apps")
	vpnDir := filepath.Join(root, resolverGroup)
	if err := os.MkdirAll(appDir, 0o755); err != nil {
		return nil, fmt.Errorf("create app cgroup: %w", err)
	}
	if err := os.MkdirAll(vpnDir, 0o755); err != nil {
		_ = os.Remove(appDir)
		return nil, fmt.Errorf("create vpn cgroup: %w", err)
	}
	appID, err := cgroupID(appDir)
	if err != nil {
		_ = os.Remove(appDir)
		_ = os.Remove(vpnDir)
		return nil, err
	}
	vpnID, err := cgroupID(vpnDir)
	if err != nil {
		_ = os.Remove(appDir)
		_ = os.Remove(vpnDir)
		return nil, err
	}

	canonical := make([]string, 0, len(spec.Executables))
	for _, exe := range spec.Executables {
		canonical = append(canonical, perapp.Canonical(exe))
	}
	s := &linuxSplit{
		mode:         mode,
		execs:        canonical,
		rootDir:      root,
		appDir:       appDir,
		vpnDir:       vpnDir,
		appID:        appID,
		vpnID:        vpnID,
		openvpnPID:   spec.OpenVPNPID,
		rulePriority: 20000 + os.Getpid()%10000,
		stop:         make(chan struct{}),
	}
	if spec.OpenVPNPID > 0 {
		if err := moveAndVerify(vpnDir, spec.OpenVPNPID); err != nil {
			s.cleanupDirs()
			return nil, fmt.Errorf("move the VPN process into its cgroup: %w", err)
		}
	}
	routes := physicalRoutes()
	if len(routes) == 0 {
		s.cleanupDirs()
		return nil, errors.New("per-app split tunneling needs a non-VPN default route; none was found")
	}
	s.routes = routes

	has4, has6 := hasFamily(routes, false), hasFamily(routes, true)
	if err := installSplitNFT(mode, appID, vpnID, has4, has6); err != nil {
		s.cleanupDirs()
		return nil, err
	}
	if err := installPolicyRouting(routes, s.rulePriority); err != nil {
		_ = removeSplitNFT()
		s.cleanupDirs()
		return nil, err
	}
	s.scan()
	s.wg.Add(1)
	go s.loop()
	fmt.Fprintf(os.Stderr, "[openvpn-desktop] per-app split: %s, %d app(s), families v4=%v v6=%v\n",
		mode, len(spec.Executables), has4, has6)
	return s, nil
}

func (s *linuxSplit) loop() {
	defer s.wg.Done()
	scanTick := time.NewTicker(time.Second)
	defer scanTick.Stop()
	routeTick := time.NewTicker(5 * time.Second)
	defer routeTick.Stop()
	for {
		select {
		case <-s.stop:
			return
		case <-scanTick.C:
			s.scan()
		case <-routeTick.C:
			s.refreshRoutes()
		}
	}
}

func (s *linuxSplit) SetVPNProcess(pid int) error {
	s.mu.Lock()
	s.openvpnPID = pid
	s.mu.Unlock()
	if s.mode != "include" || pid <= 0 {
		return nil
	}
	return moveAndVerify(s.vpnDir, pid)
}

func (s *linuxSplit) scan() {
	s.mu.Lock()
	vpnPID := s.openvpnPID
	s.mu.Unlock()
	if s.mode == "include" && vpnPID > 0 {
		_ = movePID(s.vpnDir, vpnPID)
	}
	apps := perapp.Scan()
	for _, app := range apps {
		if perapp.Match(s.execs, app.Exe) {
			for _, pid := range app.PIDs {
				_ = movePID(s.appDir, pid)
			}
			continue
		}
		// In include mode every unselected process is sent to the physical
		// path, including the system resolver. Keep the resolver on the VPN
		// path so selected applications' system DNS does not leak to the ISP.
		if s.mode == "include" && isResolver(app.Exe) {
			s.moveResolverToVPN(app.PIDs)
		}
	}
}

func isResolver(exe string) bool {
	switch filepath.Base(perapp.NormalizeExe(exe)) {
	case "systemd-resolve", "systemd-resolved", "dnsmasq", "unbound", "named":
		return true
	default:
		return false
	}
}

func (s *linuxSplit) moveResolverToVPN(pids []int) {
	s.resolverMu.Lock()
	defer s.resolverMu.Unlock()
	if s.resolverOrigins == nil {
		s.resolverOrigins = map[int]string{}
	}
	for _, pid := range pids {
		if _, ok := s.resolverOrigins[pid]; !ok {
			if path := procCgroup(pid); path != "" {
				s.resolverOrigins[pid] = path
			}
		}
		_ = movePID(s.vpnDir, pid)
	}
}

func procCgroup(pid int) string {
	data, err := os.ReadFile(fmt.Sprintf("/proc/%d/cgroup", pid))
	if err != nil {
		return ""
	}
	for _, line := range strings.Split(string(data), "\n") {
		if strings.HasPrefix(line, "0::") {
			return strings.TrimPrefix(line, "0::")
		}
	}
	return ""
}

func (s *linuxSplit) restoreResolvers() {
	s.resolverMu.Lock()
	defer s.resolverMu.Unlock()
	base := cgroupRoot()
	for pid, path := range s.resolverOrigins {
		target := filepath.Join(base, strings.TrimPrefix(path, "/"))
		if err := movePID(target, pid); err != nil {
			_ = movePID(base, pid)
		}
	}
	s.resolverOrigins = nil
}

func (s *linuxSplit) refreshRoutes() {
	routes := physicalRoutes()
	if len(routes) == 0 {
		return
	}
	s.mu.Lock()
	changed := !sameRoutes(s.routes, routes)
	s.routes = routes
	s.mu.Unlock()
	if changed {
		if err := installPolicyRouting(routes, s.rulePriority); err != nil {
			fmt.Fprintln(os.Stderr, "[openvpn-desktop] per-app split: update routes:", err)
		}
	}
}

func (s *linuxSplit) Stop() {
	select {
	case <-s.stop:
	default:
		close(s.stop)
	}
	s.wg.Wait()
	_ = removeSplitNFT()
	removePolicyRouting(s.rulePriority)
	s.restoreResolvers()
	s.evacuate()
	s.cleanupDirs()
}

func (s *linuxSplit) evacuate() {
	for _, dir := range []string{s.appDir, s.vpnDir} {
		pids := cgroupPIDs(dir)
		for _, pid := range pids {
			_ = movePID(cgroupRoot(), pid)
		}
	}
}

func cgroupRoot() string {
	if root := strings.TrimSpace(os.Getenv("OPENVPN_DESKTOP_CGROUP_ROOT")); root != "" && isCgroup2Mount(root) {
		return root
	}
	return "/sys/fs/cgroup"
}

// isCgroup2Mount keeps the env override from pointing the privileged helper at
// an arbitrary directory; only a cgroup2 mount (or a bind mount of one) is
// accepted.
func isCgroup2Mount(path string) bool {
	data, err := os.ReadFile("/proc/self/mountinfo")
	if err != nil {
		return false
	}
	path = filepath.Clean(path)
	for _, line := range strings.Split(string(data), "\n") {
		fields := strings.Fields(line)
		if len(fields) < 5 {
			continue
		}
		mountPoint := fields[4]
		if path != mountPoint && !strings.HasPrefix(path, mountPoint+"/") {
			continue
		}
		if strings.Contains(line, " - cgroup2 ") {
			return true
		}
	}
	return false
}

func (s *linuxSplit) cleanupDirs() {
	for _, dir := range []string{s.appDir, s.vpnDir, s.rootDir} {
		if dir != "" {
			_ = os.Remove(dir)
		}
	}
}

func cgroupID(dir string) (uint64, error) {
	st, err := os.Stat(dir)
	if err != nil {
		return 0, err
	}
	sys, ok := st.Sys().(*syscall.Stat_t)
	if !ok || sys.Ino == 0 {
		return 0, fmt.Errorf("cannot read the cgroup inode of %s", dir)
	}
	return sys.Ino, nil
}

func cgroupPIDs(dir string) []int {
	data, err := os.ReadFile(filepath.Join(dir, "cgroup.procs"))
	if err != nil {
		return nil
	}
	var pids []int
	for _, line := range strings.Split(string(data), "\n") {
		if pid, err := strconv.Atoi(strings.TrimSpace(line)); err == nil && pid > 0 {
			pids = append(pids, pid)
		}
	}
	return pids
}

func moveAndVerify(dir string, pid int) error {
	var lastErr error
	for i := 0; i < 50; i++ {
		if err := movePID(dir, pid); err != nil {
			lastErr = err
		}
		if cgroupHasPID(dir, pid) {
			return nil
		}
		time.Sleep(10 * time.Millisecond)
	}
	if lastErr != nil {
		return lastErr
	}
	return fmt.Errorf("pid %d did not appear in %s", pid, dir)
}

func cgroupHasPID(dir string, pid int) bool {
	for _, p := range cgroupPIDs(dir) {
		if p == pid {
			return true
		}
	}
	return false
}

func movePID(dir string, pid int) error {
	if pid <= 0 {
		return nil
	}
	f, err := os.OpenFile(filepath.Join(dir, "cgroup.procs"), os.O_WRONLY|os.O_APPEND, 0)
	if err != nil {
		return err
	}
	_, werr := fmt.Fprintf(f, "%d\n", pid)
	cerr := f.Close()
	if werr != nil {
		return werr
	}
	return cerr
}

func installSplitNFT(mode string, appID, vpnID uint64, v4, v6 bool) error {
	nft, err := findTrusted("nft")
	if err != nil {
		return err
	}
	script := nftSplitScript(mode, appID, vpnID, v4, v6)
	_ = removeSplitNFT()
	if err := runTrustedStdin(script, nft, "-f", "-"); err != nil {
		return fmt.Errorf("install per-app nftables rules: %w", err)
	}
	return nil
}

func removeSplitNFT() error {
	nft, err := findTrusted("nft")
	if err != nil {
		return err
	}
	return runTrusted(nft, "delete", "table", "inet", "openvpn_desktop_split")
}

func nftSplitScript(mode string, appID, vpnID uint64, v4, v6 bool) string {
	var b strings.Builder
	b.WriteString("table inet openvpn_desktop_split {\n")
	b.WriteString("  chain output {\n")
	b.WriteString("    type route hook output priority mangle; policy accept;\n")
	if mode == "include" {
		b.WriteString("    oif \"lo\" accept\n")
		fmt.Fprintf(&b, "    meta cgroup %d accept\n", vpnID)
		fmt.Fprintf(&b, "    meta cgroup %d accept\n", appID)
		if v4 {
			b.WriteString("    meta nfproto ipv4 meta mark set " + splitMark + "\n")
		}
		if v6 {
			b.WriteString("    meta nfproto ipv6 meta mark set " + splitMark + "\n")
		}
	} else {
		if v4 {
			fmt.Fprintf(&b, "    meta nfproto ipv4 meta cgroup %d meta mark set %s\n", appID, splitMark)
		}
		if v6 {
			fmt.Fprintf(&b, "    meta nfproto ipv6 meta cgroup %d meta mark set %s\n", appID, splitMark)
		}
	}
	b.WriteString("  }\n}\n")
	return b.String()
}

func installPolicyRouting(routes []physicalRoute, priority int) error {
	ip, err := findTrusted("ip")
	if err != nil {
		return err
	}
	for _, route := range routes {
		family := []string{}
		if route.v6 {
			family = append(family, "-6")
		}
		rule := append(append([]string{}, family...), "rule", "add", "fwmark", splitMark+"/0x1",
			"table", strconv.Itoa(splitTable), "priority", strconv.Itoa(priority))
		if err := runTrusted(ip, rule...); err != nil && !isFileExists(err) {
			return fmt.Errorf("add per-app policy rule: %w", err)
		}
		routeArgs := append(append([]string{}, family...), "route", "replace", "default")
		if route.gateway != "" {
			routeArgs = append(routeArgs, "via", route.gateway)
		}
		routeArgs = append(routeArgs, "dev", route.dev, "table", strconv.Itoa(splitTable))
		if err := runTrusted(ip, routeArgs...); err != nil {
			return fmt.Errorf("add per-app policy route: %w", err)
		}
	}
	return nil
}

func removePolicyRouting(priority int) {
	ip, err := findTrusted("ip")
	if err != nil {
		return
	}
	for _, family := range [][]string{{}, {"-6"}} {
		args := append(append([]string{}, family...), "rule", "del", "priority", strconv.Itoa(priority))
		_ = runTrusted(ip, args...)
		flush := append(append([]string{}, family...), "route", "flush", "table", strconv.Itoa(splitTable))
		_ = runTrusted(ip, flush...)
	}
}

func physicalRoutes() []physicalRoute {
	ip, err := findTrusted("ip")
	if err != nil {
		return nil
	}
	var out []physicalRoute
	for _, v6 := range []bool{false, true} {
		args := []string{"route", "show", "default"}
		if v6 {
			args = append([]string{"-6"}, args...)
		}
		text, err := runTrustedOutput(ip, args...)
		if err != nil {
			continue
		}
		for _, line := range strings.Split(text, "\n") {
			route, ok := parseDefaultRoute(line, v6)
			if !ok {
				continue
			}
			out = append(out, route)
			break
		}
	}
	return out
}

func parseDefaultRoute(line string, v6 bool) (physicalRoute, bool) {
	fields := strings.Fields(line)
	if len(fields) == 0 || fields[0] != "default" {
		return physicalRoute{}, false
	}
	var route physicalRoute
	route.v6 = v6
	for i := 0; i < len(fields)-1; i++ {
		switch fields[i] {
		case "via":
			route.gateway = fields[i+1]
		case "dev":
			route.dev = fields[i+1]
		}
	}
	if route.dev == "" || strings.HasPrefix(route.dev, "tun") || strings.HasPrefix(route.dev, "tap") {
		return physicalRoute{}, false
	}
	return route, true
}

func hasFamily(routes []physicalRoute, v6 bool) bool {
	for _, r := range routes {
		if r.v6 == v6 {
			return true
		}
	}
	return false
}

func sameRoutes(a, b []physicalRoute) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func isFileExists(err error) bool {
	return err != nil && (strings.Contains(err.Error(), "File exists") || strings.Contains(err.Error(), "EEXIST"))
}

// cleanupStaleSplits removes cgroups left by a killed helper. It does not touch
// a live session's classes unless it is called explicitly (cleanup command).
func cleanupStaleSplits() {
	base := cgroupRoot()
	entries, err := os.ReadDir(base)
	if err != nil {
		return
	}
	for _, e := range entries {
		if !e.IsDir() || !strings.HasPrefix(e.Name(), splitCgroupPrefix) {
			continue
		}
		root := filepath.Join(base, e.Name())
		for _, sub := range []string{"apps", "vpn"} {
			dir := filepath.Join(root, sub)
			for _, pid := range cgroupPIDs(dir) {
				_ = movePID(base, pid)
			}
			_ = os.Remove(dir)
		}
		_ = os.Remove(root)
	}
}
