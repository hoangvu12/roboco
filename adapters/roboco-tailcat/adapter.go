// Package tailcatnative exposes Roboco's narrow native Tailcat transport.
//
// It intentionally provides only one fixed Tailcat application port backed by
// one exact IPv4 loopback target. It does not install a VPN, execute
// commands, or expose a general-purpose forwarder. The desktop drives it as
// the roboco-tailcat subprocess; Android loads it in-process through the C
// ABI in ./capi (libroboco_tailcat.so).
package tailcatnative

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/netip"
	"os"
	"path/filepath"

	"runtime"
	"sync"
	"sync/atomic"
	"time"

	"github.com/tailscale/tailcat"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
	"tailscale.com/types/logger"
)

// ApplicationPort is the only remote TCP port exposed by this adapter.
const ApplicationPort uint16 = 7332

// testLogEnv opts a TEST run into tailcat's diagnostics on stderr. Nothing
// ships with it set: the desktop, the CLI and Android always discard them
// (an Android c-shared library even starts with an empty Go environment).
// The lines can name relay endpoints and node keys, so never set it in
// production.
const testLogEnv = "ROBOCO_TAILCAT_TEST_LOG"

// debugLogf is tailcat's log sink: logger.Discard unless testLogEnv=1.
var debugLogf logger.Logf = func() logger.Logf {
	if os.Getenv(testLogEnv) == "1" {
		return log.New(os.Stderr, "tailcat["+fmt.Sprint(os.Getpid())+"]: ", log.LstdFlags|log.Lmicroseconds).Printf
	}
	return logger.Discard
}()

const stateVersion = 1

type stateFile struct {
	Version      int                  `json:"version"`
	Role         string               `json:"role"`
	PrivateKey   key.NodePrivate      `json:"private_key"`
	PresharedKey tailcat.PresharedKey `json:"preshared_key,omitempty"`
	Region       *tailcfg.DERPRegion  `json:"region,omitempty"`
}

// Client exposes a loopback HTTP-compatible endpoint connected to a Tailcat
// server's fixed application port.
type Client struct {
	proxy *proxy
	url   string
	once  sync.Once

	// cur is the live tunnel. Dials read it without a lock; only a reset
	// (under resetMu) replaces it.
	cur   atomic.Pointer[tunnel]
	newTC func() *tailcat.Client

	// resetMu serializes reset decisions, so simultaneous failed dials share
	// one health probe and at most one of them replaces the tunnel.
	resetMu   sync.Mutex
	lastReset time.Time

	// path pushes for a direct path and keeps the last one measured
	// (fleet-redesign 16); stopPath ends its pings.
	path     *pathProber
	stopPath context.CancelFunc
}

// tunnel is one tailcat.Client generation. A reset replaces the whole
// generation, which kills every socket it carries.
type tunnel struct {
	tc      *tailcat.Client
	created time.Time
	// handshaken is set once the server has acknowledged this client: the
	// startup ping, a successful dial or a successful health probe. Only a
	// handshaken client has a network stack and tunnel sockets to drain.
	handshaken atomic.Bool

	// The last health probe, guarded by Client.resetMu.
	probedAt time.Time
	probeErr error
}

func newTunnel(tc *tailcat.Client) *tunnel {
	return &tunnel{tc: tc, created: time.Now()}
}

// URL returns the local loopback URL. It remains stable until Close.
func (c *Client) URL() string { return c.url }

// Path is how this client last reached its server: directly or through a
// relay region, with the measured round trip. Never an address or a key.
func (c *Client) Path() PathStatus { return c.path.Status() }

// OnPath sets a sink for every new path measurement (the `connect`
// subprocess prints them as status lines).
func (c *Client) OnPath(sink func(PathStatus)) { c.path.OnStatus(sink) }

// SetBackground pauses the path pings while the app is in the background
// (a phone, ADR 0010) and restarts the burst when it comes back.
func (c *Client) SetBackground(background bool) { c.path.SetBackground(background) }

// closeDrainTimeout bounds how long Close waits for the in-tunnel FINs to be
// acknowledged. Without the drain, tearing down the in-process TCP stack
// loses them and the engine only notices the dead sockets when its silence
// lease runs out.
const closeDrainTimeout = 1500 * time.Millisecond

// Close stops accepting connections and closes active proxy and Tailcat
// connections. It is safe to call more than once.
func (c *Client) Close() {
	c.once.Do(func() {
		c.stopPath()
		// proxy.close cancels and joins all dial/copy workers first, so no
		// reset can race this and every tunnel socket is already closed.
		c.proxy.close()
		t := c.cur.Load()
		if t.handshaken.Load() {
			ctx, cancel := context.WithTimeout(context.Background(), closeDrainTimeout)
			err := t.tc.DrainTCP(ctx)
			cancel()
			debugLogf("adapter: close drained tailcat client %p: %v", t.tc, err)
		}
		_ = t.tc.Close()
	})
}

// Server exposes exactly one local IPv4 loopback target through Tailcat's
// application port.
type Server struct {
	proxy   *proxy
	tc      *tailcat.Server
	address string
	once    sync.Once
}

// Address returns the secret Tailcat address clients use to connect.
func (s *Server) Address() string { return s.address }

// Close stops accepting connections and closes active proxy and Tailcat
// connections. It is safe to call more than once.
func (s *Server) Close() {
	s.once.Do(func() {
		s.proxy.close()
		_ = s.tc.Close()
	})
}

// StartClient starts a loopback listener on an OS-selected port and connects
// it to application port 7332 at address. stateDir stores client.key; it is
// deliberately distinct from the server identity. derpMap may be empty.
func StartClient(address, stateDir, derpMap string) (*Client, error) {
	return startClient(address, "127.0.0.1:0", filepath.Join(stateDir, "client.key"), derpMap)
}

// StartServer exposes target through application port 7332. target must be an
// exact 127.0.0.1 TCP endpoint. stateDir stores server.key; derpMap may be
// empty. Region selection is automatic.
func StartServer(target, stateDir, derpMap string) (*Server, error) {
	return startServer(target, filepath.Join(stateDir, "server.key"), derpMap, 0, nil)
}

// StartClientCLI is the managed subprocess variant of StartClient. It accepts
// an explicit state file and loopback listener but preserves the same network
// restrictions.
func StartClientCLI(address, listenAddress, statePath, derpMap string) (*Client, error) {
	return startClient(address, listenAddress, statePath, derpMap)
}

// StartServerCLI is the managed subprocess variant of StartServer. A non-zero
// regionID pins relay selection to that DERP map region.
func StartServerCLI(target, statePath, derpMap string, regionID int) (*Server, error) {
	return startServer(target, statePath, derpMap, regionID, nil)
}

// StartClientState is StartClient with the identity held by the caller
// instead of a file: the Android app keeps it Keystore-encrypted. An empty
// stateJSON generates a new client key. The returned state is the identity
// to store (unchanged when one was passed in); it is a secret like the
// address. The listener is always 127.0.0.1 on an OS-selected port.
func StartClientState(address, stateJSON, derpMap string) (*Client, string, error) {
	if _, err := tailcat.ParseAddr(tailcat.Addr(address)); err != nil {
		return nil, "", errors.New("invalid Tailcat address")
	}
	state, err := decodeClientState(stateJSON)
	if err != nil {
		return nil, "", fmt.Errorf("client identity: %w", err)
	}
	encoded, err := json.Marshal(state)
	if err != nil {
		return nil, "", errors.New("client identity: could not encode state")
	}
	c, err := connectClient(address, "127.0.0.1:0", state, derpMap)
	if err != nil {
		return nil, "", err
	}
	return c, string(encoded), nil
}

// decodeClientState parses a caller-held client identity, or creates one
// when stateJSON is empty. Errors never echo the input.
func decodeClientState(stateJSON string) (*stateFile, error) {
	if stateJSON == "" {
		return &stateFile{Version: stateVersion, Role: "client", PrivateKey: key.NewNode()}, nil
	}
	var state stateFile
	if err := json.Unmarshal([]byte(stateJSON), &state); err != nil {
		return nil, errors.New("state is corrupt")
	}
	if err := validateState(&state, "client"); err != nil {
		return nil, err
	}
	return &state, nil
}

func startClient(address, listenAddress, statePath, derpMap string) (*Client, error) {
	if _, err := tailcat.ParseAddr(tailcat.Addr(address)); err != nil {
		return nil, errors.New("invalid Tailcat address")
	}
	if err := validateLoopback(listenAddress, true); err != nil {
		return nil, fmt.Errorf("invalid listen endpoint: %w", err)
	}
	state, err := loadOrCreateState(statePath, "client")
	if err != nil {
		return nil, fmt.Errorf("client identity: %w", err)
	}
	return connectClient(address, listenAddress, state, derpMap)
}

// connectClient is the shared body of the file-backed and caller-held
// client paths: address and listener are already validated.
func connectClient(address, listenAddress string, state *stateFile, derpMap string) (*Client, error) {
	return connectClientWith(address, listenAddress, state, derpMap, defaultPathCadence())
}

// connectClientWith is connectClient with the path probe cadence chosen
// (tests shrink it).
func connectClientWith(address, listenAddress string, state *stateFile, derpMap string, cadence pathCadence) (*Client, error) {
	newTC := func() *tailcat.Client {
		return &tailcat.Client{
			Server:     tailcat.Addr(address),
			Key:        state.PrivateKey,
			DERPMapURL: derpMap,
			Logf:       debugLogf,
		}
	}
	cl := newTC()
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if _, err := cl.Ping(ctx); err != nil {
		_ = cl.Close()
		return nil, redactError("Tailcat client startup", err)
	}
	ln, err := net.Listen("tcp4", listenAddress)
	if err != nil {
		_ = cl.Close()
		return nil, errors.New("could not open loopback listener")
	}
	c := &Client{newTC: newTC, url: "http://" + ln.Addr().String()}
	t := newTunnel(cl)
	t.handshaken.Store(true) // the startup ping succeeded
	c.cur.Store(t)
	c.proxy = newProxy(ln, c.dial)
	// Callers speak first (HTTP/WebSocket). Waiting for their first bytes
	// keeps a bare connect-and-close liveness check of the loopback (the
	// mobile core's "is the Go client still there?" probe) from dialing the
	// tunnel, and so from ever counting as a failed dial.
	c.proxy.awaitFirstBytes = true
	c.proxy.start()
	c.path = newPathProber(cadence, c.discoPing)
	pathCtx, stopPath := context.WithCancel(context.Background())
	c.stopPath = stopPath
	go c.path.run(pathCtx)
	return c, nil
}

// discoPing pings the server over the current tunnel. A pong proves the
// tunnel handshaken, like a successful dial.
func (c *Client) discoPing(ctx context.Context) (*ipnstate.PingResult, error) {
	t := c.cur.Load()
	result, err := t.tc.DiscoPing(ctx)
	if err == nil {
		t.handshaken.Store(true)
	}
	return result, err
}

// Bound each tunnel dial: the proxy's lifetime context alone can leave a
// gVisor SYN pending long after the HTTP caller has given up.
const tunnelDialTimeout = 5 * time.Second

// The pinned Tailcat client caches its initial registration (upDone), so a
// restarted server that has forgotten the peer stays unreachable until the
// client is recreated, with the SAME persisted key; the loopback listener and
// pairing stay untouched. A reset kills every socket the client carries, so
// one failed dial is not enough: the tunnel must also fail a health probe.
const (
	// healthProbeTimeout bounds the probe a failed dial triggers.
	healthProbeTimeout = 3 * time.Second
	// probeReuse lets the other dials that failed in the same burst reuse a
	// fresh probe result instead of each paying for their own.
	probeReuse = 2 * time.Second
	// handshakeGrace is how long a client that has never completed its
	// first handshake is left alone: right after a reset or a thaw its
	// relay connection may still be coming up.
	handshakeGrace = 20 * time.Second
	// minResetInterval rate-limits resets while the server is offline.
	minResetInterval = tunnelDialTimeout
)

// resetVerdict is the outcome of a failed tunnel dial.
type resetVerdict int

const (
	keepReplaced    resetVerdict = iota // a newer tunnel already took over
	keepRateLimited                     // reset too recently
	keepHandshaking                     // first handshake still within its grace
	keepHealthy                         // the health probe succeeded
	resetTunnel                         // replace the tailcat client
)

func (v resetVerdict) String() string {
	switch v {
	case keepReplaced:
		return "replaced"
	case keepRateLimited:
		return "rate-limited"
	case keepHandshaking:
		return "handshaking"
	case keepHealthy:
		return "healthy"
	case resetTunnel:
		return "reset"
	}
	return fmt.Sprintf("resetVerdict(%d)", int(v))
}

// dialFailure is what the reset decision knows about a failed dial.
type dialFailure struct {
	replaced   bool          // the dial used a tunnel that is no longer current
	sinceReset time.Duration // since the last reset of this Client
	age        time.Duration // of the tunnel the dial used
	handshaken bool          // that tunnel ever completed its handshake
}

// decideReset decides whether a failed dial resets the tunnel. probe runs
// only when every cheap check allows a reset; nil means the tunnel is healthy.
func decideReset(f dialFailure, probe func() error) resetVerdict {
	switch {
	case f.replaced:
		return keepReplaced
	case f.sinceReset < minResetInterval:
		return keepRateLimited
	case !f.handshaken && f.age < handshakeGrace:
		return keepHandshaking
	}
	if probe() == nil {
		return keepHealthy
	}
	return resetTunnel
}

func (c *Client) dial(ctx context.Context) (net.Conn, error) {
	t := c.cur.Load()
	conn, err := t.dial(ctx)
	if err == nil || ctx.Err() != nil {
		return conn, err
	}
	c.afterFailedDial(ctx, t)
	// A failed dial may mean the path changed: push for a direct one again.
	c.path.Rearm()
	if ctx.Err() != nil {
		return nil, ctx.Err()
	}
	// No application bytes have been copied yet: retrying the TCP dial cannot
	// replay a command or attachment write. The upper layer owns such retries.
	return c.cur.Load().dial(ctx)
}

// afterFailedDial resets the tunnel if decideReset says so. Simultaneous
// room reconnects coalesce on resetMu: they share one probe, and none tears
// down a newer tunnel because a dial from its predecessor finally timed out.
func (c *Client) afterFailedDial(ctx context.Context, failed *tunnel) {
	c.resetMu.Lock()
	defer c.resetMu.Unlock()
	now := time.Now()
	verdict := decideReset(dialFailure{
		replaced:   c.cur.Load() != failed,
		sinceReset: now.Sub(c.lastReset),
		age:        now.Sub(failed.created),
		handshaken: failed.handshaken.Load(),
	}, func() error { return failed.probe(ctx, c.path) })
	if verdict != resetTunnel || ctx.Err() != nil {
		debugLogf("adapter: no reset of %p (%v)", failed.tc, verdict)
		return
	}
	_ = failed.tc.Close()
	next := newTunnel(c.newTC())
	c.cur.Store(next)
	c.lastReset = time.Now()
	debugLogf("adapter: reset tailcat client %p -> %p", failed.tc, next.tc)
}

func (t *tunnel) dial(ctx context.Context) (net.Conn, error) {
	attempt, cancel := context.WithTimeout(ctx, tunnelDialTimeout)
	defer cancel()
	started := time.Now()
	conn, err := t.tc.DialTCPPort(attempt, ApplicationPort)
	if err != nil {
		debugLogf("adapter: tunnel dial %p failed after %v: %v", t.tc, time.Since(started), err)
		return nil, err
	}
	t.handshaken.Store(true)
	debugLogf("adapter: tunnel dial %p ok after %v", t.tc, time.Since(started))
	return conn, nil
}

// probe checks the tunnel end to end within healthProbeTimeout. The caller
// holds Client.resetMu. A pong also tells path which way it came.
//
// tailcat.Client.Ping is no health check here: once the first meow
// handshake completed it returns at once without waiting for an ack. A
// disco ping needs a pong from the server, via the relay or a direct path,
// and a restarted server that has forgotten this peer ignores it.
func (t *tunnel) probe(ctx context.Context, path *pathProber) error {
	if !t.probedAt.IsZero() && time.Since(t.probedAt) < probeReuse {
		return t.probeErr
	}
	attempt, cancel := context.WithTimeout(ctx, healthProbeTimeout)
	defer cancel()
	started := time.Now()
	result, err := t.tc.DiscoPing(attempt)
	if err == nil {
		path.observe(result)
	}
	if ctx.Err() != nil {
		// The caller gave up mid-probe: that says nothing about the tunnel.
		// Caching it would let another dial of the same burst reset a
		// healthy tunnel on this caller's cancellation.
		debugLogf("adapter: health probe of %p abandoned by its caller: %v", t.tc, ctx.Err())
		return err
	}
	t.probedAt, t.probeErr = time.Now(), err
	if err == nil {
		t.handshaken.Store(true)
	}
	debugLogf("adapter: health probe of %p after %v: %v", t.tc, time.Since(started), err)
	return err
}

func startServer(target, statePath, derpMap string, regionID int, region *tailcfg.DERPRegion) (*Server, error) {
	if err := validateLoopback(target, false); err != nil {
		return nil, fmt.Errorf("invalid target endpoint: %w", err)
	}
	state, err := loadOrCreateState(statePath, "server")
	if err != nil {
		return nil, fmt.Errorf("server identity: %w", err)
	}
	srv := &tailcat.Server{
		Key:          state.PrivateKey,
		PresharedKey: state.PresharedKey,
		DERPMapURL:   derpMap,
		Logf:         debugLogf,
	}
	switch {
	case region != nil:
		srv.Region = region
	case regionID != 0:
		srv.RegionID = tailcfg.DERPRegionID(regionID)
	case state.Region != nil:
		srv.Region = state.Region
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	ln, err := srv.Listen(ctx, "tcp", fmt.Sprintf(":%d", ApplicationPort))
	if err != nil {
		_ = srv.Close()
		return nil, redactError("Tailcat server startup", err)
	}

	// Persist the selected relay region so the complete secret address remains
	// stable across restarts, not merely the WireGuard identity.
	ci, err := tailcat.ParseAddr(srv.TailcatAddr())
	if err != nil || len(ci.Region) != 1 {
		_ = ln.Close()
		_ = srv.Close()
		return nil, errors.New("Tailcat server produced invalid connection metadata")
	}
	if state.Region == nil || regionID != 0 || region != nil {
		state.Region = ci.Region[0]
		if err := writeState(statePath, state); err != nil {
			_ = ln.Close()
			_ = srv.Close()
			return nil, fmt.Errorf("server identity: %w", err)
		}
	}

	p := newProxy(ln, func(ctx context.Context) (net.Conn, error) {
		var d net.Dialer
		conn, err := d.DialContext(ctx, "tcp4", target)
		if err != nil {
			debugLogf("adapter: server target dial failed: %v", err)
		} else {
			debugLogf("adapter: server accepted a tunnel connection")
		}
		return conn, err
	})
	p.start()
	return &Server{proxy: p, tc: srv, address: string(srv.TailcatAddr())}, nil
}

func validateLoopback(address string, allowZero bool) error {
	ap, err := netip.ParseAddrPort(address)
	if err != nil {
		return errors.New("must be a numeric IP:port")
	}
	if ap.Addr() != netip.MustParseAddr("127.0.0.1") {
		return errors.New("must use 127.0.0.1")
	}
	if ap.Port() == 0 && !allowZero {
		return errors.New("port must not be zero")
	}
	return nil
}

func loadOrCreateState(path, role string) (*stateFile, error) {
	if path == "" {
		return nil, errors.New("state path is empty")
	}
	st, err := os.Lstat(path)
	if err == nil {
		if !st.Mode().IsRegular() || (runtime.GOOS != "windows" && st.Mode().Perm() != 0600) {
			return nil, errors.New("state file must be regular and private")
		}
		data, err := os.ReadFile(path)
		if err != nil {
			return nil, errors.New("could not read state file")
		}
		var state stateFile
		if err := json.Unmarshal(data, &state); err != nil {
			return nil, errors.New("state file is corrupt")
		}
		if err := validateState(&state, role); err != nil {
			return nil, err
		}
		return &state, nil
	}
	if !errors.Is(err, os.ErrNotExist) {
		return nil, errors.New("could not inspect state file")
	}
	state := &stateFile{Version: stateVersion, Role: role, PrivateKey: key.NewNode()}
	if role == "server" {
		state.PresharedKey = tailcat.NewPresharedKey()
	}
	if err := writeState(path, state); err != nil {
		return nil, err
	}
	return state, nil
}

func validateState(state *stateFile, role string) error {
	if state.Version != stateVersion || state.Role != role || state.PrivateKey.IsZero() {
		return errors.New("state file is corrupt or belongs to the other endpoint role")
	}
	if role == "server" && state.PresharedKey.IsZero() {
		return errors.New("server state file is missing its pre-shared key")
	}
	return nil
}

func writeState(path string, state *stateFile) error {
	data, err := json.Marshal(state)
	if err != nil {
		return errors.New("could not encode state file")
	}
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0700); err != nil {
		return errors.New("could not create state directory")
	}
	if err := os.Chmod(dir, 0700); err != nil {
		return errors.New("could not secure state directory")
	}
	tmp, err := os.CreateTemp(dir, ".tailcat-state-*")
	if err != nil {
		return errors.New("could not create state file")
	}
	tmpName := tmp.Name()
	ok := false
	defer func() {
		_ = tmp.Close()
		if !ok {
			_ = os.Remove(tmpName)
		}
	}()
	if err := tmp.Chmod(0600); err != nil {
		return errors.New("could not secure state file")
	}
	if _, err := tmp.Write(data); err != nil {
		return errors.New("could not write state file")
	}
	if err := tmp.Sync(); err != nil {
		return errors.New("could not sync state file")
	}
	if err := tmp.Close(); err != nil {
		return errors.New("could not close state file")
	}
	if err := os.Rename(tmpName, path); err != nil {
		return errors.New("could not install state file")
	}
	ok = true
	if d, err := os.Open(dir); err == nil {
		_ = d.Sync()
		_ = d.Close()
	}
	return nil
}

// redactError deliberately excludes upstream text, which may contain the
// secret Tailcat address or raw relay endpoints.
func redactError(operation string, _ error) error { return errors.New(operation + " failed") }

// firstBytesTimeout bounds how long an awaitFirstBytes proxy waits for an
// accepted connection to speak before dropping it.
const firstBytesTimeout = 30 * time.Second

type proxy struct {
	listener net.Listener
	dial     func(context.Context) (net.Conn, error)
	// awaitFirstBytes defers the upstream dial until the accepted
	// connection sends something; one that hangs up first never dials.
	// Only for protocols whose caller speaks first.
	awaitFirstBytes bool
	ctx             context.Context
	cancel          context.CancelFunc
	mu              sync.Mutex
	closed          bool
	active          map[net.Conn]struct{}
	wg              sync.WaitGroup
}

func newProxy(listener net.Listener, dial func(context.Context) (net.Conn, error)) *proxy {
	ctx, cancel := context.WithCancel(context.Background())
	return &proxy{listener: listener, dial: dial, ctx: ctx, cancel: cancel, active: make(map[net.Conn]struct{})}
}

func (p *proxy) start() {
	p.wg.Add(1)
	go func() {
		defer p.wg.Done()
		for {
			local, err := p.listener.Accept()
			if err != nil {
				return
			}
			p.wg.Add(1)
			go p.serve(local)
		}
	}()
}

func (p *proxy) serve(local net.Conn) {
	defer p.wg.Done()
	if !p.track(local) {
		_ = local.Close()
		return
	}
	defer p.untrack(local)
	var first []byte
	if p.awaitFirstBytes {
		buf := make([]byte, 4096)
		// A caller that connects and never speaks must not hold this
		// goroutine (and its socket) until Close.
		_ = local.SetReadDeadline(time.Now().Add(firstBytesTimeout))
		n, _ := local.Read(buf)
		_ = local.SetReadDeadline(time.Time{})
		if n == 0 {
			// Hung up (or failed) before sending anything: a liveness
			// check of the listener, not a caller. Don't dial for it.
			return
		}
		first = buf[:n]
	}
	remote, err := p.dial(p.ctx)
	if err != nil {
		_ = local.Close()
		return
	}
	if !p.track(remote) {
		_ = remote.Close()
		_ = local.Close()
		return
	}
	defer p.untrack(remote)
	if len(first) > 0 {
		if _, err := remote.Write(first); err != nil {
			return
		}
	}
	proxyBoth(local, remote)
}

func (p *proxy) track(conn net.Conn) bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.closed {
		return false
	}
	p.active[conn] = struct{}{}
	return true
}

func (p *proxy) untrack(conn net.Conn) {
	p.mu.Lock()
	delete(p.active, conn)
	p.mu.Unlock()
	_ = conn.Close()
}

func (p *proxy) close() {
	p.cancel()
	_ = p.listener.Close()
	p.mu.Lock()
	p.closed = true
	for conn := range p.active {
		_ = conn.Close()
	}
	p.mu.Unlock()
	p.wg.Wait()
}

func proxyBoth(a, b net.Conn) {
	var wg sync.WaitGroup
	wg.Add(2)
	copyOne := func(dst, src net.Conn) {
		defer wg.Done()
		_, _ = io.Copy(dst, src)
		if cw, ok := dst.(interface{ CloseWrite() error }); ok {
			_ = cw.CloseWrite()
		} else {
			_ = dst.Close()
		}
		if cr, ok := src.(interface{ CloseRead() error }); ok {
			_ = cr.CloseRead()
		}
	}
	go copyOne(a, b)
	go copyOne(b, a)
	wg.Wait()
	_ = a.Close()
	_ = b.Close()
}
