package tailcatnative

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"tailscale.com/envknob"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tstest/integration"
	"tailscale.com/types/logger"
)

// testCadence is the production schedule shrunk to test time.
func testCadence() pathCadence {
	return pathCadence{
		burst:        []time.Duration{100 * time.Millisecond, 300 * time.Millisecond, time.Second, 3 * time.Second},
		relayed:      time.Second,
		direct:       10 * time.Second,
		netCheck:     time.Hour,
		probeTimeout: 3 * time.Second,
	}
}

// pathRecorder collects every status a client reports.
type pathRecorder struct {
	mu       sync.Mutex
	statuses []PathStatus
}

func (r *pathRecorder) add(status PathStatus) {
	r.mu.Lock()
	r.statuses = append(r.statuses, status)
	r.mu.Unlock()
}

func (r *pathRecorder) all() []PathStatus {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]PathStatus(nil), r.statuses...)
}

// startPathPeers runs a test DERP, a server and a client on loopback, the
// client probing on cadence.
func startPathPeers(t *testing.T, cadence pathCadence) (*Client, string, *pathRecorder) {
	t.Helper()
	dm := integration.RunDERPAndSTUN(t, logger.Discard, "127.0.0.1")
	region := dm.Regions[1]
	if region == nil {
		t.Fatal("local DERP has no region 1")
	}
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {}))
	t.Cleanup(backend.Close)
	server, err := startServer(backend.Listener.Addr().String(), filepath.Join(t.TempDir(), "server.key"), "", 0, region)
	if err != nil {
		t.Fatalf("start server: %v", err)
	}
	t.Cleanup(server.Close)
	state, err := decodeClientState("")
	if err != nil {
		t.Fatal(err)
	}
	client, err := connectClientWith(server.Address(), "127.0.0.1:0", state, "", cadence)
	if err != nil {
		t.Fatalf("start client: %v", err)
	}
	t.Cleanup(client.Close)
	recorder := &pathRecorder{}
	client.OnPath(recorder.add)
	return client, region.RegionCode, recorder
}

func waitFor(t *testing.T, what string, within time.Duration, done func() bool) {
	t.Helper()
	deadline := time.Now().Add(within)
	for !done() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(20 * time.Millisecond)
	}
}

// Two loopback peers start on the relay (direct UDP blocked while they
// come up); once UDP is open the pings alone upgrade them to a direct path
// (no tunnel traffic flows meanwhile), the status reports it, and the push
// stops.
func TestPathPushUpgradesRelayToDirect(t *testing.T) {
	envknob.Setenv("TS_DEBUG_NEVER_DIRECT_UDP", "true")
	t.Cleanup(func() { envknob.Setenv("TS_DEBUG_NEVER_DIRECT_UDP", "") })
	client, _, recorder := startPathPeers(t, testCadence())
	waitFor(t, "a relayed pong", 15*time.Second, func() bool { return client.Path().Known })
	if status := client.Path(); status.Direct || status.Region == "" {
		t.Fatalf("the peers should start on the relay: %+v", status)
	}
	envknob.Setenv("TS_DEBUG_NEVER_DIRECT_UDP", "")
	client.path.Rearm()
	waitFor(t, "a direct path", 30*time.Second, func() bool { return client.Path().Direct })
	t.Logf("path statuses: %+v", recorder.all())
	status := client.Path()
	if !status.Known || status.Region != "" {
		t.Fatalf("direct status = %+v", status)
	}
	line := status.JSON()
	if !strings.HasPrefix(line, `{"path":"direct","rttMs":`) || strings.Contains(line, "127.0.0.1") {
		t.Fatalf("status line = %q", line)
	}
	// Direct: the push is over; the next check is a whole direct interval
	// away.
	probes := len(client.path.probeTimes())
	time.Sleep(2 * time.Second)
	if after := len(client.path.probeTimes()); after != probes {
		t.Fatalf("still pinging once direct: %d -> %d probes", probes, after)
	}
}

// With direct UDP blocked between the peers the status stays on the relay
// (with its region code) and, after the burst, the pings drop to the relay
// interval.
func TestPathPushStaysRelayedWhenUDPIsBlocked(t *testing.T) {
	envknob.Setenv("TS_DEBUG_NEVER_DIRECT_UDP", "true")
	t.Cleanup(func() { envknob.Setenv("TS_DEBUG_NEVER_DIRECT_UDP", "") })
	cadence := testCadence()
	client, _, _ := startPathPeers(t, cadence)
	// The burst (to 3 s) and two relay intervals.
	waitFor(t, "two pings past the burst", 15*time.Second, func() bool {
		return len(client.path.probeTimes()) >= len(cadence.burst)+2
	})
	status := client.Path()
	// The relay names its region by the code the server's address
	// carries (the test DERP's region "1").
	if !status.Known || status.Direct || status.Region == "" {
		t.Fatalf("status = %+v, want relayed with a region code", status)
	}
	if line := status.JSON(); line != `{"path":"relay","region":"`+status.Region+`","rttMs":`+strconv.FormatInt(status.RTT.Milliseconds(), 10)+`}` {
		t.Fatalf("status line = %q", line)
	}
	probes := client.path.probeTimes()
	for i := len(cadence.burst); i < len(probes); i++ {
		gap := probes[i].Sub(probes[i-1])
		if gap < cadence.relayed*8/10 {
			t.Fatalf("relayed ping %d came %v after the one before, want ~%v: %v", i, gap, cadence.relayed, probes)
		}
	}
	if production := defaultPathCadence(); pushForDirectPath && production.relayed != time.Minute {
		t.Fatalf("production relayed cadence = %v, want 1m", production.relayed)
	}
}

// The scheduler alone, with a scripted ping: the burst, the stop on a
// direct pong, the background pause and the re-arm on return.
func TestPathProberCadence(t *testing.T) {
	var mu sync.Mutex
	direct := false
	ping := func(context.Context) (*ipnstate.PingResult, error) {
		mu.Lock()
		defer mu.Unlock()
		if direct {
			return &ipnstate.PingResult{Endpoint: "192.0.2.1:41641", LatencySeconds: 0.035}, nil
		}
		return &ipnstate.PingResult{DERPRegionID: 7, DERPRegionCode: "tok", LatencySeconds: 0.16}, nil
	}
	cadence := pathCadence{
		burst:        []time.Duration{20 * time.Millisecond, 60 * time.Millisecond, 120 * time.Millisecond},
		relayed:      200 * time.Millisecond,
		direct:       time.Hour,
		netCheck:     time.Hour,
		probeTimeout: time.Second,
	}
	prober := newPathProber(cadence, ping)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go prober.run(ctx)

	waitFor(t, "the burst and a relayed ping", 5*time.Second, func() bool { return len(prober.probeTimes()) >= 4 })
	if status := prober.Status(); status.Direct || status.Region != "tok" || status.RTT != 160*time.Millisecond {
		t.Fatalf("relayed status = %+v", status)
	}

	// Background: no pings at all.
	prober.SetBackground(true)
	time.Sleep(50 * time.Millisecond)
	paused := len(prober.probeTimes())
	time.Sleep(500 * time.Millisecond)
	if now := len(prober.probeTimes()); now != paused {
		t.Fatalf("pinged in the background: %d -> %d", paused, now)
	}

	// Back in the foreground: a fresh burst, which finds the direct path
	// and stops.
	mu.Lock()
	direct = true
	mu.Unlock()
	prober.SetBackground(false)
	waitFor(t, "a direct path", 5*time.Second, func() bool { return prober.Status().Direct })
	stopped := len(prober.probeTimes())
	time.Sleep(400 * time.Millisecond)
	if now := len(prober.probeTimes()); now != stopped {
		t.Fatalf("still pinging once direct: %d -> %d", stopped, now)
	}
	if line := prober.Status().JSON(); strings.Contains(line, "192.0.2.1") || line != `{"path":"direct","rttMs":35}` {
		t.Fatalf("direct status line = %q", line)
	}

	// A re-arm (a failed dial, a network change) pushes again.
	mu.Lock()
	direct = false
	mu.Unlock()
	prober.Rearm()
	waitFor(t, "the re-armed burst", 5*time.Second, func() bool { return !prober.Status().Direct })
}

// A failed ping keeps the last known path; region codes are reduced to a
// short token so nothing else rides along.
func TestPathStatusHygiene(t *testing.T) {
	prober := newPathProber(testCadence(), nil)
	prober.record(&ipnstate.PingResult{DERPRegionCode: "TOK", LatencySeconds: 0.1}, nil)
	prober.record(nil, errors.New("timeout"))
	if status := prober.Status(); !status.Known || status.Region != "tok" {
		t.Fatalf("status after a failed ping = %+v", status)
	}
	for _, code := range []string{"tok 1.2.3.4", "a:b", strings.Repeat("x", 40)} {
		if got := sanitizeRegion(code); got != "" {
			t.Errorf("sanitizeRegion(%q) = %q", code, got)
		}
	}
	if (PathStatus{}).JSON() != "" {
		t.Fatal("an unknown path must have no status line")
	}
}
