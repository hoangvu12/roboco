// Command capi builds libroboco_tailcat, the in-process Tailcat client the
// Android app's Rust core loads with dlopen (android-next#02). Build it with
// -buildmode=c-shared; `cargo xtask android-core` does so per ABI.
//
// The exported C ABI lives in exports.go (cgo). This file is the pure-Go
// handle table behind it, so its logic also runs with CGO_ENABLED=0. No Go
// pointer crosses the boundary: C callers hold int64 handles only.
package main

import (
	"sync"

	tailcatnative "github.com/hoangvu12/roboco/adapters/roboco-tailcat"
)

// abiVersion is what roboco_tailcat_abi_version returns. The Rust loader
// refuses any other value, so bump it on every breaking change to exports.go.
const abiVersion = 1

func main() {}

var (
	mu      sync.Mutex
	nextID  int64
	clients = map[int64]*tailcatnative.Client{}
)

// startClient opens a client and registers it. It returns a handle > 0, or
// 0 and a redacted message: adapter errors never echo the address, and a
// recovered panic reports only that one happened.
func startClient(address, stateJSON, derpMap string) (handle int64, url, newState, errMessage string) {
	defer func() {
		if recovered := recover(); recovered != nil {
			handle, url, newState, errMessage = 0, "", "", "Tailcat client startup panicked"
		}
	}()
	client, state, err := tailcatnative.StartClientState(address, stateJSON, derpMap)
	if err != nil {
		return 0, "", "", err.Error()
	}
	mu.Lock()
	nextID++
	handle = nextID
	clients[handle] = client
	mu.Unlock()
	return handle, client.URL(), state, ""
}

// closeClient closes and forgets a handle. Unknown, zero and already
// closed handles are no-ops.
func closeClient(handle int64) {
	mu.Lock()
	client := clients[handle]
	delete(clients, handle)
	mu.Unlock()
	if client == nil {
		return
	}
	defer func() { _ = recover() }()
	client.Close()
}

// openHandles is the number of live clients (tests).
func openHandles() int {
	mu.Lock()
	defer mu.Unlock()
	return len(clients)
}
