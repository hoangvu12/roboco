package tailcatnative

import (
	"context"
	"encoding/json"
	"slices"
	"strings"
	"sync"
	"time"

	"tailscale.com/ipn/ipnstate"
	"tailscale.com/net/netmon"
)

// pushForDirectPath switches the direct-path push (fleet-redesign 16) on or
// off, in this one place. On, a client sends disco pings at 1, 3, 10 and
// 30 s after it connects, then every minute while its pongs come back over
// a relay, like `tailcat ping --until-direct`: each disco ping nudges NAT
// traversal along. Off, it pings only once per (re)connect and then every
// few minutes, just enough to keep the path label current.
const pushForDirectPath = true

// pathCadence is when a client probes its path. Tests shrink it.
type pathCadence struct {
	// burst is the probe schedule after a (re)arm, as offsets from it.
	burst []time.Duration
	// relayed is the interval while pongs come back over a relay (or not
	// at all), after the burst.
	relayed time.Duration
	// direct is the interval once a pong came back over a direct path:
	// only enough to notice the path falling back to a relay.
	direct time.Duration
	// netCheck is how often the local addresses are compared, to re-arm
	// on a network change.
	netCheck time.Duration
	// probeTimeout bounds one disco ping.
	probeTimeout time.Duration
}

func defaultPathCadence() pathCadence {
	if !pushForDirectPath {
		return pathCadence{
			burst:        []time.Duration{time.Second},
			relayed:      5 * time.Minute,
			direct:       5 * time.Minute,
			netCheck:     15 * time.Second,
			probeTimeout: 5 * time.Second,
		}
	}
	return pathCadence{
		burst:        []time.Duration{time.Second, 3 * time.Second, 10 * time.Second, 30 * time.Second},
		relayed:      time.Minute,
		direct:       5 * time.Minute,
		netCheck:     15 * time.Second,
		probeTimeout: 5 * time.Second,
	}
}

// PathStatus is how this client last reached its server: directly, or
// through a relay region, and the round trip the pong measured. It never
// carries an address or a key (ADR 0008): only the path kind, the relay's
// short region code ("tok") and a latency.
type PathStatus struct {
	// Known is false until a disco ping has been answered.
	Known  bool
	Direct bool
	// Region is the relay's region code when relayed ("" when direct, or
	// when the relay did not say).
	Region string
	RTT    time.Duration
}

// pathLine is the status line `connect` prints with ROBOCO_TAILCAT_STATUS=1,
// and the JSON roboco_tailcat_path returns.
type pathLine struct {
	Path   string `json:"path"`
	Region string `json:"region,omitempty"`
	RTTMs  int64  `json:"rttMs"`
}

// JSON encodes a known status as a status line; "" while unknown.
func (s PathStatus) JSON() string {
	if !s.Known {
		return ""
	}
	line := pathLine{Path: "relay", Region: s.Region, RTTMs: s.RTT.Milliseconds()}
	if s.Direct {
		line = pathLine{Path: "direct", RTTMs: s.RTT.Milliseconds()}
	}
	encoded, err := json.Marshal(line)
	if err != nil {
		return ""
	}
	return string(encoded)
}

// statusOf reads a disco pong: an endpoint means a direct path. Only the
// path kind and the region code are kept; the endpoint itself is dropped.
func statusOf(result *ipnstate.PingResult) PathStatus {
	status := PathStatus{Known: true, RTT: time.Duration(result.LatencySeconds * float64(time.Second))}
	if result.Endpoint != "" {
		status.Direct = true
		return status
	}
	status.Region = sanitizeRegion(result.DERPRegionCode)
	return status
}

// sanitizeRegion keeps a region code to a short lowercase token, so nothing
// else ever rides along in a status line.
func sanitizeRegion(code string) string {
	code = strings.ToLower(strings.TrimSpace(code))
	if len(code) > 16 {
		return ""
	}
	for _, r := range code {
		if (r < 'a' || r > 'z') && (r < '0' || r > '9') && r != '-' {
			return ""
		}
	}
	return code
}

// pathProber owns one client's direct-path pings and its last path status.
type pathProber struct {
	cadence pathCadence
	// ping sends one disco ping over the client's current tunnel.
	ping func(context.Context) (*ipnstate.PingResult, error)

	rearm      chan struct{}
	background chan bool

	mu       sync.Mutex
	status   PathStatus
	onStatus func(PathStatus)
	// probes are the times of the pings this prober sent (tests).
	probes []time.Time
}

func newPathProber(cadence pathCadence, ping func(context.Context) (*ipnstate.PingResult, error)) *pathProber {
	return &pathProber{
		cadence:    cadence,
		ping:       ping,
		rearm:      make(chan struct{}, 1),
		background: make(chan bool, 1),
	}
}

// Status is the last path this client measured.
func (p *pathProber) Status() PathStatus {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.status
}

// OnStatus sets a sink called with every new measurement (outside any lock).
func (p *pathProber) OnStatus(sink func(PathStatus)) {
	p.mu.Lock()
	p.onStatus = sink
	p.mu.Unlock()
}

// Rearm restarts the burst: a reconnect, a tunnel reset, a failed dial, a
// network change or a return to the foreground.
func (p *pathProber) Rearm() {
	select {
	case p.rearm <- struct{}{}:
	default:
	}
}

// SetBackground pauses every probe while the app is in the background (a
// phone, ADR 0010), and re-arms on the way back.
func (p *pathProber) SetBackground(background bool) {
	for {
		select {
		case p.background <- background:
			return
		default:
		}
		// Replace a pending value nobody has read yet: the latest wins.
		select {
		case <-p.background:
		default:
		}
	}
}

// record keeps a probe's outcome. A failed probe keeps the last known path.
func (p *pathProber) record(result *ipnstate.PingResult, err error) {
	p.mu.Lock()
	p.probes = append(p.probes, time.Now())
	if err != nil || result == nil {
		p.mu.Unlock()
		return
	}
	p.status = statusOf(result)
	status, sink := p.status, p.onStatus
	p.mu.Unlock()
	if sink != nil {
		sink(status)
	}
}

// observe takes a pong somebody else measured (the dial health probe).
func (p *pathProber) observe(result *ipnstate.PingResult) {
	if result == nil {
		return
	}
	p.mu.Lock()
	p.status = statusOf(result)
	status, sink := p.status, p.onStatus
	p.mu.Unlock()
	if sink != nil {
		sink(status)
	}
}

func (p *pathProber) probeTimes() []time.Time {
	p.mu.Lock()
	defer p.mu.Unlock()
	return slices.Clone(p.probes)
}

// run probes on the cadence until ctx ends. The client starts armed.
func (p *pathProber) run(ctx context.Context) {
	timer := time.NewTimer(0)
	stopTimer := func() {
		if !timer.Stop() {
			select {
			case <-timer.C:
			default:
			}
		}
	}
	stopTimer()
	netCheck := time.NewTicker(p.cadence.netCheck)
	defer netCheck.Stop()
	defer stopTimer()

	var armedAt time.Time
	step := 0
	background := false
	addresses := localAddresses()
	arm := func() {
		armedAt, step = time.Now(), 0
		stopTimer()
		if !background {
			timer.Reset(p.cadence.burst[0])
		}
	}
	arm()
	for {
		select {
		case <-ctx.Done():
			return
		case <-p.rearm:
			arm()
		case next := <-p.background:
			if next == background {
				continue
			}
			background = next
			if background {
				stopTimer()
			} else {
				addresses = localAddresses()
				arm()
			}
		case <-netCheck.C:
			if background {
				continue
			}
			if now := localAddresses(); now != addresses {
				addresses = now
				arm()
			}
		case <-timer.C:
			if background {
				continue
			}
			probe, cancel := context.WithTimeout(ctx, p.cadence.probeTimeout)
			result, err := p.ping(probe)
			cancel()
			if ctx.Err() != nil {
				return
			}
			p.record(result, err)
			direct := err == nil && result != nil && result.Endpoint != ""
			step++
			switch {
			case direct:
				// The push is over: only watch for a fall back to a relay.
				step = len(p.cadence.burst)
				timer.Reset(p.cadence.direct)
			case step < len(p.cadence.burst):
				timer.Reset(time.Until(armedAt.Add(p.cadence.burst[step])))
			default:
				timer.Reset(p.cadence.relayed)
			}
		}
	}
}

// localAddresses fingerprints this machine's addresses: a change means the
// network changed and a new direct path may exist. Never logged.
func localAddresses() string {
	regular, _, err := netmon.LocalAddresses()
	if err != nil {
		return ""
	}
	parts := make([]string, 0, len(regular))
	for _, address := range regular {
		parts = append(parts, address.String())
	}
	slices.Sort(parts)
	return strings.Join(parts, ",")
}
