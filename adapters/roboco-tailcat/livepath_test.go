package tailcatnative

import (
	"bufio"
	"context"
	"net"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"tailscale.com/tstest/integration"
	"tailscale.com/types/logger"
)

// A live connection through the tunnel keeps carrying bytes across disco
// pings: the direct-path push must never cost the user an open socket.
func TestDiscoPingsKeepLiveConnections(t *testing.T) {
	dm := integration.RunDERPAndSTUN(t, logger.Discard, "127.0.0.1")
	region := dm.Regions[1]
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
				lines := bufio.NewScanner(conn)
				for lines.Scan() {
					_, _ = conn.Write([]byte("echo:" + lines.Text() + "\n"))
				}
			}()
		}
	}()
	server, err := startServer(backend.Addr().String(), filepath.Join(t.TempDir(), "server.key"), "", 0, region)
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	state, err := decodeClientState("")
	if err != nil {
		t.Fatal(err)
	}
	// No automatic pings: this test sends them by hand.
	quiet := testCadence()
	quiet.burst = []time.Duration{time.Hour}
	client, err := connectClientWith(server.Address(), "127.0.0.1:0", state, "", quiet)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	conn, err := net.DialTimeout("tcp4", strings.TrimPrefix(client.URL(), "http://"), 5*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	reader := bufio.NewReader(conn)
	roundTrip := func(word string) {
		t.Helper()
		_ = conn.SetDeadline(time.Now().Add(10 * time.Second))
		if _, err := conn.Write([]byte(word + "\n")); err != nil {
			t.Fatalf("write %s: %v", word, err)
		}
		line, err := reader.ReadString('\n')
		if err != nil || line != "echo:"+word+"\n" {
			t.Fatalf("round trip %s = %q, %v", word, line, err)
		}
	}
	roundTrip("before")
	for i := 0; i < 3; i++ {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		result, err := client.discoPing(ctx)
		cancel()
		t.Logf("ping %d: %+v %v", i, result, err)
		roundTrip("after-" + string(rune('a'+i)))
	}
}
