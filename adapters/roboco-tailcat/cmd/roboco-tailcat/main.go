// roboco-tailcat is the managed native transport subprocess used by Roboco.
package main

import (
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"os/signal"

	"runtime"
	"strconv"
	"sync"
	"syscall"

	native "github.com/hoangvu12/roboco/adapters/roboco-tailcat"
)

func main() {
	if err := run(os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(2)
	}
}

func run(args []string) error {
	if os.Getenv("ROBOCO_TAILCAT_PARENT_PIPE") == "1" {
		// Managed mode: stdin is a pipe held ONLY by the owning engine. EOF
		// also covers abrupt process exit, where kill_on_drop never runs.
		// Exit immediately even during startup or a stuck network shutdown;
		// the OS closes all listeners and no pairing state is removed.
		go func() {
			_, _ = io.Copy(io.Discard, os.Stdin)
			os.Exit(0)
		}()
	}
	if len(args) == 0 {
		return usageError()
	}
	switch args[0] {
	case "serve":
		return runServe(args[1:])
	case "connect":
		return runConnect(args[1:])
	default:
		return usageError()
	}
}

func runServe(args []string) error {
	fs := flag.NewFlagSet("serve", flag.ContinueOnError)
	fs.SetOutput(os.Stderr)
	state := fs.String("state", "", "server identity file")
	target := fs.String("target", "", "exact 127.0.0.1 application endpoint")
	derpMap := fs.String("derp-map", "", "alternate DERP map URL")
	region := fs.Int("region", 0, "DERP region ID")
	if err := fs.Parse(args); err != nil || fs.NArg() != 0 || *state == "" || *target == "" {
		return usageError()
	}
	if *region < 0 || *region > 999 {
		return errors.New("--region must be between 1 and 999")
	}
	srv, err := native.StartServerCLI(*target, *state, *derpMap, *region)
	if err != nil {
		return err
	}
	defer srv.Close()
	if err := json.NewEncoder(os.Stdout).Encode(map[string]string{"address": srv.Address()}); err != nil {
		return errors.New("could not emit readiness")
	}
	return waitSignal()
}

func runConnect(args []string) error {
	fs := flag.NewFlagSet("connect", flag.ContinueOnError)
	fs.SetOutput(os.Stderr)
	configPath := fs.String("config", "", "0600 JSON connection config")
	listen := fs.String("listen", "127.0.0.1:0", "local 127.0.0.1 listener")
	state := fs.String("state", "", "client identity file")
	derpMap := fs.String("derp-map", "", "alternate DERP map URL")
	if err := fs.Parse(args); err != nil || fs.NArg() != 0 || *configPath == "" || *state == "" {
		return usageError()
	}
	config, err := loadConnectConfig(*configPath)
	if err != nil {
		return err
	}
	cl, err := native.StartClientCLI(config.Address, *listen, *state, *derpMap)
	if err != nil {
		return err
	}
	defer cl.Close()
	if err := json.NewEncoder(os.Stdout).Encode(map[string]string{"url": cl.URL()}); err != nil {
		return errors.New("could not emit readiness")
	}
	if os.Getenv(statusEnv) == "1" {
		emitPathStatus(cl, os.Stdout)
	}
	return waitSignal()
}

// statusEnv asks `connect` for path status lines after its readiness line
// (fleet-redesign 16). An environment variable, not a flag: an older
// adapter ignores it instead of refusing to start.
const statusEnv = "ROBOCO_TAILCAT_STATUS"

// emitPathStatus prints one JSON line per path measurement:
// {"path":"direct","rttMs":35} or {"path":"relay","region":"tok","rttMs":160}.
// No address or key ever appears in them. A write error (the parent stopped
// reading) ends the lines; the client keeps running.
func emitPathStatus(cl *native.Client, out io.Writer) {
	var mu sync.Mutex
	broken := false
	emit := func(status native.PathStatus) {
		line := status.JSON()
		if line == "" {
			return
		}
		mu.Lock()
		defer mu.Unlock()
		if broken {
			return
		}
		if _, err := io.WriteString(out, line+"\n"); err != nil {
			broken = true
		}
	}
	cl.OnPath(emit)
	emit(cl.Path())
}

type connectConfig struct {
	Address string `json:"address"`
}

const maxConnectConfigSize = 1 << 20

func loadConnectConfig(path string) (*connectConfig, error) {
	info, err := os.Lstat(path)
	if err != nil || !info.Mode().IsRegular() || (runtime.GOOS != "windows" && info.Mode().Perm() != 0600) {
		return nil, errors.New("connection config must be regular and private")
	}
	if info.Size() > maxConnectConfigSize {
		return nil, errors.New("connection config is too large")
	}
	file, err := os.Open(path)
	if err != nil {
		return nil, errors.New("could not read connection config")
	}
	defer file.Close()
	decoder := json.NewDecoder(io.LimitReader(file, maxConnectConfigSize+1))
	decoder.DisallowUnknownFields()
	var config connectConfig
	if err := decoder.Decode(&config); err != nil {
		return nil, errors.New("connection config is malformed")
	}
	var extra any
	if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) {
		return nil, errors.New("connection config is malformed")
	}
	if config.Address == "" {
		return nil, errors.New("connection config has no address")
	}
	return &config, nil
}

func waitSignal() error {
	ch := make(chan os.Signal, 1)
	signal.Notify(ch, os.Interrupt, syscall.SIGTERM)
	defer signal.Stop(ch)
	<-ch
	return nil
}

func usageError() error {
	return fmt.Errorf("usage: %s {serve --state FILE --target 127.0.0.1:PORT [--derp-map URL] [--region ID] | connect --config 0600_JSON_FILE --listen 127.0.0.1:0 --state FILE [--derp-map URL]}", strconv.Quote(os.Args[0]))
}
