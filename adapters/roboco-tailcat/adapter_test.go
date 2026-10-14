package tailcatnative

import (
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"

	"github.com/tailscale/tailcat"
	"tailscale.com/envknob"
	"tailscale.com/net/netcheck"
	"tailscale.com/syncs"
	"tailscale.com/tailcfg"
	"tailscale.com/tstest/integration"
	"tailscale.com/types/key"
	"tailscale.com/types/logger"
)

func TestMain(m *testing.M) {
	envknob.Setenv("IN_TS_TEST", "true")
	netcheck.HookStartCaptivePortalDetection.SetForTest(func(context.Context, *netcheck.Client, *tailcfg.DERPMap, tailcfg.DERPRegionID, func(bool)) (<-chan struct{}, func()) {
		return syncs.ClosedChan(), func() {}
	})
	os.Exit(m.Run())
}

func TestLocalDERPTwoPeerProxy(t *testing.T) {
	dm := integration.RunDERPAndSTUN(t, logger.Discard, "127.0.0.1")
	region := dm.Regions[1]
	if region == nil {
		t.Fatal("local DERP has no region 1")
	}

	backend, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer backend.Close()
	backendDone := make(chan struct{})
	go func() {
		defer close(backendDone)
		conn, err := backend.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		payload, _ := io.ReadAll(conn)
		_, _ = conn.Write([]byte("reply:" + string(payload)))
	}()

	root := t.TempDir()
	serverState := filepath.Join(root, "server.key")
	server, err := startServer(backend.Addr().String(), serverState, "", 0, region)
	if err != nil {
		t.Fatalf("start server: %v", err)
	}
	defer server.Close()
	clientState := filepath.Join(root, "client.key")
	client, err := startClient(server.Address(), "127.0.0.1:0", clientState, "")
	if err != nil {
		t.Fatalf("start client: %v", err)
	}
	defer client.Close()

	localAddress := strings.TrimPrefix(client.URL(), "http://")
	conn, err := net.DialTimeout("tcp4", localAddress, 10*time.Second)
	if err != nil {
		t.Fatalf("dial client loopback: %v", err)
	}
	if _, err := conn.Write([]byte("tailcat bytes")); err != nil {
		t.Fatal(err)
	}
	if tcp, ok := conn.(*net.TCPConn); ok {
		_ = tcp.CloseWrite()
	}
	got, err := io.ReadAll(conn)
	_ = conn.Close()
	if err != nil {
		t.Fatal(err)
	}
	if want := "reply:tailcat bytes"; string(got) != want {
		t.Fatalf("proxy response = %q, want %q", got, want)
	}
	<-backendDone

	assertPrivateModeOnPOSIX(t, serverState)
	assertPrivateModeOnPOSIX(t, clientState)
	serverIdentity, err := os.ReadFile(serverState)
	if err != nil {
		t.Fatal(err)
	}
	clientIdentity, err := os.ReadFile(clientState)
	if err != nil {
		t.Fatal(err)
	}
	if string(serverIdentity) == string(clientIdentity) {
		t.Fatal("client and server identities are not separate")
	}

	client.Close()
	if conn, err := net.DialTimeout("tcp4", localAddress, 200*time.Millisecond); err == nil {
		conn.Close()
		t.Fatal("client listener still accepts after Close")
	}

	firstAddress := server.Address()
	server.Close()
	restarted, err := startServer(backend.Addr().String(), serverState, "", 0, nil)
	if err != nil {
		t.Fatalf("restart server from persisted identity: %v", err)
	}
	defer restarted.Close()
	if restarted.Address() != firstAddress {
		t.Fatal("persisted server address changed across restart")
	}

}

func TestLoopbackRestriction(t *testing.T) {
	for _, address := range []string{"0.0.0.0:1234", "[::1]:1234", "localhost:1234", "127.0.0.2:1234"} {
		if err := validateLoopback(address, false); err == nil {
			t.Errorf("validateLoopback(%q) succeeded", address)
		}
	}
	if err := validateLoopback("127.0.0.1:7332", false); err != nil {
		t.Fatalf("valid loopback rejected: %v", err)
	}
	if err := validateLoopback("127.0.0.1:0", true); err != nil {
		t.Fatalf("ephemeral loopback rejected: %v", err)
	}
}

func TestStateCorruptionAndPermissionsFailClosed(t *testing.T) {
	dir := t.TempDir()
	corrupt := filepath.Join(dir, "corrupt.key")
	if err := os.WriteFile(corrupt, []byte("not json"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := loadOrCreateState(corrupt, "client"); err == nil {
		t.Fatal("corrupt state was silently replaced")
	}
	got, _ := os.ReadFile(corrupt)
	if string(got) != "not json" {
		t.Fatal("corrupt state was modified")
	}

	if runtime.GOOS != "windows" {
		wide := filepath.Join(dir, "wide.key")
		if err := os.WriteFile(wide, []byte("{}"), 0644); err != nil {
			t.Fatal(err)
		}
		if _, err := loadOrCreateState(wide, "client"); err == nil {
			t.Fatal("over-permissive state was accepted")
		}
	}

	serverPath := filepath.Join(dir, "server.key")
	if _, err := loadOrCreateState(serverPath, "server"); err != nil {
		t.Fatal(err)
	}
	if _, err := loadOrCreateState(serverPath, "client"); err == nil {
		t.Fatal("server identity was accepted as client identity")
	}
}

func assertPrivateModeOnPOSIX(t *testing.T, path string) {
	t.Helper()
	if runtime.GOOS == "windows" {
		return // Windows file privacy is represented by ACLs, not FileMode.Perm.
	}
	st, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if got := st.Mode().Perm(); got != 0600 {
		t.Fatalf("%s mode = %o, want 600", path, got)
	}
}

// The Android path: the identity lives with the caller, never on disk.
func TestClientStateDecodeFailsClosed(t *testing.T) {
	created, err := decodeClientState("")
	if err != nil || created.Role != "client" || created.PrivateKey.IsZero() {
		t.Fatalf("empty state did not create a client identity: %v", err)
	}
	valid, err := json.Marshal(created)
	if err != nil {
		t.Fatal(err)
	}
	again, err := decodeClientState(string(valid))
	if err != nil || again.PrivateKey != created.PrivateKey {
		t.Fatalf("valid state did not round-trip: %v", err)
	}
	server, err := json.Marshal(&stateFile{Version: stateVersion, Role: "server", PrivateKey: key.NewNode(), PresharedKey: tailcat.NewPresharedKey()})
	if err != nil {
		t.Fatal(err)
	}
	for name, input := range map[string]string{
		"corrupt":     `not json SECRET`,
		"server role": string(server),
		"zero key":    `{"version":1,"role":"client"}`,
		"old version": strings.Replace(string(valid), `"version":1`, `"version":9`, 1),
	} {
		if _, err := decodeClientState(input); err == nil {
			t.Errorf("%s state was accepted", name)
		} else if strings.Contains(err.Error(), "SECRET") || strings.Contains(err.Error(), "private") {
			t.Errorf("%s error echoes its input: %v", name, err)
		}
	}
	if _, _, err := StartClientState("tcSECRET", string(valid), ""); err == nil || strings.Contains(err.Error(), "SECRET") {
		t.Fatalf("bad address: %v", err)
	}
}

func TestLocalDERPClientStateRoundTrip(t *testing.T) {
	dm := integration.RunDERPAndSTUN(t, logger.Discard, "127.0.0.1")
	region := dm.Regions[1]
	if region == nil {
		t.Fatal("local DERP has no region 1")
	}
	// HTTP, like the engine: Tailcat's Ping sends HEAD / through the proxy.
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		_, _ = w.Write([]byte("reply:" + string(body)))
	}))
	defer backend.Close()
	server, err := startServer(backend.Listener.Addr().String(), filepath.Join(t.TempDir(), "server.key"), "", 0, region)
	if err != nil {
		t.Fatalf("start server: %v", err)
	}
	defer server.Close()

	httpClient := &http.Client{Transport: &http.Transport{DisableKeepAlives: true}, Timeout: 15 * time.Second}
	roundTrip := func(client *Client) {
		t.Helper()
		response, err := httpClient.Post(client.URL(), "text/plain", strings.NewReader("bytes"))
		if err != nil {
			t.Fatalf("request through the client: %v", err)
		}
		defer response.Body.Close()
		got, err := io.ReadAll(response.Body)
		if err != nil || string(got) != "reply:bytes" {
			t.Fatalf("proxy response = %q, %v", got, err)
		}
	}

	first, state, err := StartClientState(server.Address(), "", "")
	if err != nil {
		t.Fatalf("start client with a new identity: %v", err)
	}
	roundTrip(first)
	first.Close()
	if state == "" || strings.Contains(state, server.Address()) {
		t.Fatal("returned state is empty or embeds the address")
	}

	// Reconnecting with the stored identity keeps it: the server sees the
	// same peer, not a new one per launch.
	second, again, err := StartClientState(server.Address(), state, "")
	if err != nil {
		t.Fatalf("start client with the stored identity: %v", err)
	}
	defer second.Close()
	if again != state {
		t.Fatal("stored identity changed on reuse")
	}
	roundTrip(second)
}
