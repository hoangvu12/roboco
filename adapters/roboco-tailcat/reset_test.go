package tailcatnative

import (
	"context"
	"errors"
	"io"
	"net"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"tailscale.com/tstest/integration"
	"tailscale.com/types/logger"
)

func TestDecideReset(t *testing.T) {
	errDown := errors.New("probe failed")
	healthy := dialFailure{sinceReset: time.Hour, age: time.Hour, handshaken: true}
	cases := []struct {
		name      string
		failure   dialFailure
		probe     error
		want      resetVerdict
		wantProbe bool
	}{
		{"a healthy tunnel survives one failed dial", healthy, nil, keepHealthy, true},
		{"an unhealthy tunnel is reset", healthy, errDown, resetTunnel, true},
		{"a newer tunnel already took over",
			dialFailure{replaced: true, sinceReset: time.Hour, age: time.Hour, handshaken: true}, errDown, keepReplaced, false},
		{"resets are rate-limited",
			dialFailure{sinceReset: minResetInterval - time.Millisecond, age: time.Hour, handshaken: true}, errDown, keepRateLimited, false},
		{"a client still on its first handshake gets its grace",
			dialFailure{sinceReset: time.Hour, age: handshakeGrace - time.Millisecond}, errDown, keepHandshaking, false},
		{"a client that never handshook is reset after its grace",
			dialFailure{sinceReset: time.Hour, age: handshakeGrace}, errDown, resetTunnel, true},
		{"a late first handshake keeps the client",
			dialFailure{sinceReset: time.Hour, age: handshakeGrace}, nil, keepHealthy, true},
		{"a handshaken young client is probed, not graced",
			dialFailure{sinceReset: time.Hour, age: time.Second, handshaken: true}, errDown, resetTunnel, true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			probed := false
			got := decideReset(tc.failure, func() error {
				probed = true
				return tc.probe
			})
			if got != tc.want {
				t.Errorf("verdict = %v, want %v", got, tc.want)
			}
			if probed != tc.wantProbe {
				t.Errorf("probed = %v, want %v", probed, tc.wantProbe)
			}
		})
	}
}

// A failed dial on a healthy tunnel must not reset it: the health probe
// passes, the client is kept and its live sockets keep working.
func TestFailedDialKeepsAHealthyTunnel(t *testing.T) {
	dm := integration.RunDERPAndSTUN(t, logger.Discard, "127.0.0.1")
	backend, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer backend.Close()
	go func() {
		for {
			conn, err := backend.Accept()
			if err != nil {
				return
			}
			go func() {
				defer conn.Close()
				_, _ = io.Copy(conn, conn)
			}()
		}
	}()
	root := t.TempDir()
	server, err := startServer(backend.Addr().String(), filepath.Join(root, "server.key"), "", 0, dm.Regions[1])
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	client, err := startClient(server.Address(), "127.0.0.1:0", filepath.Join(root, "client.key"), "")
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	conn, err := net.DialTimeout("tcp4", strings.TrimPrefix(client.URL(), "http://"), 5*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	echo := func(payload string) {
		t.Helper()
		if _, err := conn.Write([]byte(payload)); err != nil {
			t.Fatal(err)
		}
		got := make([]byte, len(payload))
		_ = conn.SetReadDeadline(time.Now().Add(15 * time.Second))
		if _, err := io.ReadFull(conn, got); err != nil || string(got) != payload {
			t.Fatalf("echo %q: got %q, %v", payload, got, err)
		}
	}
	echo("before")

	live := client.cur.Load()
	client.afterFailedDial(context.Background(), live)
	if client.cur.Load() != live {
		t.Fatal("a failed dial reset a healthy tunnel")
	}
	if live.probeErr != nil {
		t.Fatalf("health probe of a healthy tunnel failed: %v", live.probeErr)
	}
	echo("after")
}

// Closing the client must deliver the in-tunnel FINs, so the engine sees its
// sockets end instead of waiting out a silence lease.
func TestCloseDeliversFINsToTheServer(t *testing.T) {
	dm := integration.RunDERPAndSTUN(t, logger.Discard, "127.0.0.1")
	backend, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer backend.Close()
	const sockets = 3
	ended := make(chan error, sockets)
	go func() {
		for {
			conn, err := backend.Accept()
			if err != nil {
				return
			}
			go func() {
				defer conn.Close()
				buf := make([]byte, 64)
				_, _ = conn.Read(buf) // the caller's request
				_, _ = conn.Write([]byte("ok"))
				_ = conn.SetReadDeadline(time.Now().Add(20 * time.Second))
				_, err := io.Copy(io.Discard, conn)
				ended <- err
			}()
		}
	}()
	root := t.TempDir()
	server, err := startServer(backend.Addr().String(), filepath.Join(root, "server.key"), "", 0, dm.Regions[1])
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	client, err := startClient(server.Address(), "127.0.0.1:0", filepath.Join(root, "client.key"), "")
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	for i := 0; i < sockets; i++ {
		conn, err := net.DialTimeout("tcp4", strings.TrimPrefix(client.URL(), "http://"), 5*time.Second)
		if err != nil {
			t.Fatal(err)
		}
		defer conn.Close()
		if _, err := conn.Write([]byte("hello")); err != nil {
			t.Fatal(err)
		}
		reply := make([]byte, 2)
		_ = conn.SetReadDeadline(time.Now().Add(15 * time.Second))
		if _, err := io.ReadFull(conn, reply); err != nil {
			t.Fatalf("socket %d: %v", i, err)
		}
	}
	started := time.Now()
	client.Close()
	for i := 0; i < sockets; i++ {
		if err := <-ended; err != nil {
			t.Fatalf("engine socket %d ended with %v after %v, want a clean EOF", i, err, time.Since(started))
		}
	}
}

// The mobile core checks the loopback is alive with a bare connect-and-close.
// That must not dial the tunnel, so it can never count as a failed dial; a
// real caller's first bytes still reach the upstream intact.
func TestProxyDialsOnlyOnceTheCallerSpeaks(t *testing.T) {
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	var dials atomic.Int32
	upstream := make(chan net.Conn, 1)
	p := newProxy(ln, func(context.Context) (net.Conn, error) {
		dials.Add(1)
		near, far := net.Pipe()
		upstream <- far
		return near, nil
	})
	p.awaitFirstBytes = true
	p.start()
	defer p.close()

	probe, err := net.Dial("tcp4", ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	_ = probe.Close()

	caller, err := net.Dial("tcp4", ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer caller.Close()
	if _, err := caller.Write([]byte("GET / HTTP/1.1\r\n\r\n")); err != nil {
		t.Fatal(err)
	}
	var far net.Conn
	select {
	case far = <-upstream:
	case <-time.After(5 * time.Second):
		t.Fatal("the proxy never dialed for a caller that spoke")
	}
	defer far.Close()
	got := make([]byte, len("GET / HTTP/1.1\r\n\r\n"))
	if _, err := io.ReadFull(far, got); err != nil {
		t.Fatal(err)
	}
	if string(got) != "GET / HTTP/1.1\r\n\r\n" {
		t.Fatalf("upstream got %q", got)
	}
	if _, err := far.Write([]byte("ok")); err != nil {
		t.Fatal(err)
	}
	reply := make([]byte, 2)
	if _, err := io.ReadFull(caller, reply); err != nil || string(reply) != "ok" {
		t.Fatalf("caller got %q, %v", reply, err)
	}
	if n := dials.Load(); n != 1 {
		t.Fatalf("dialed %d times, want 1 (the bare connect must not dial)", n)
	}
}
