// roboco-tailcat-testderp serves a local DERP relay, a STUN server and their
// DERP map on 127.0.0.1, for hermetic Tailcat integration tests
// (android-next#02: the mobile core's host test pairs and reconnects through
// it). Test-only: never shipped.
//
// It prints one JSON line, {"derpMap":"http://127.0.0.1:<port>/derpmap.json"},
// then serves until stdin reaches EOF, so the parent test owns its lifetime.
// The pieces mirror tstest/integration.RunDERPAndSTUN, which needs a
// testing.TB and so cannot run as a program.
package main

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/http/httptest"
	"os"

	"tailscale.com/derp/derpserver"
	"tailscale.com/net/stunserver"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
	"tailscale.com/types/logger"
)

const host = "127.0.0.1"

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(2)
	}
}

func run() error {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	stun := stunserver.New(ctx)
	if err := stun.Listen(net.JoinHostPort(host, "0")); err != nil {
		return fmt.Errorf("stun listen: %w", err)
	}
	go func() { _ = stun.Serve() }()

	derp := derpserver.New(key.NewNode(), logger.Discard)
	defer derp.Close()
	derpListener, err := net.Listen("tcp", net.JoinHostPort(host, "0"))
	if err != nil {
		return fmt.Errorf("derp listen: %w", err)
	}
	relay := httptest.NewUnstartedServer(derpserver.AddWebSocketSupport(derp, derpserver.Handler(derp)))
	relay.Listener.Close()
	relay.Listener = derpListener
	relay.Config.ErrorLog = log.New(io.Discard, "", 0)
	relay.Config.TLSNextProto = make(map[string]func(*http.Server, *tls.Conn, http.Handler))
	relay.StartTLS()
	defer relay.Close()

	derpMap := &tailcfg.DERPMap{
		Regions: map[tailcfg.DERPRegionID]*tailcfg.DERPRegion{
			1: {
				RegionID:   1,
				RegionCode: "test",
				Nodes: []*tailcfg.DERPNode{{
					Name:             "t1",
					RegionID:         1,
					HostName:         host,
					IPv4:             host,
					IPv6:             "none",
					STUNPort:         stun.LocalAddr().(*net.UDPAddr).Port,
					DERPPort:         derpListener.Addr().(*net.TCPAddr).Port,
					InsecureForTests: true,
					STUNTestIP:       host,
				}},
			},
		},
	}
	body, err := json.Marshal(derpMap)
	if err != nil {
		return fmt.Errorf("encode derp map: %w", err)
	}
	mapListener, err := net.Listen("tcp", net.JoinHostPort(host, "0"))
	if err != nil {
		return fmt.Errorf("map listen: %w", err)
	}
	go func() {
		_ = http.Serve(mapListener, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write(body)
		}))
	}()

	ready, err := json.Marshal(map[string]string{
		"derpMap": "http://" + mapListener.Addr().String() + "/derpmap.json",
	})
	if err != nil {
		return err
	}
	fmt.Println(string(ready))
	_, _ = io.Copy(io.Discard, os.Stdin)
	return nil
}
