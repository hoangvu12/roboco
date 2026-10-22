# Roboco Tailcat adapter

This managed `serve`/`connect` subprocess is derived from `connectivity/tailcat`
in [wasimysaid/Kratos at 9c31b48221d42bcecd3f3fb8cb19fcd89d8a07d9](https://github.com/wasimysaid/Kratos/tree/9c31b48221d42bcecd3f3fb8cb19fcd89d8a07d9/connectivity/tailcat).
The original adapter is MIT-licensed (`LICENSE.kratos`). Changes here rename
the module and binary for Roboco; upstream Tailcat and Tailscale modules are
pinned in `go.mod` and `go.sum`. Redistribution notices and license texts for
the original Go dependency closure are in `licenses/bundle`.

The engine starts `roboco-tailcat serve --state <server.key> --target 127.0.0.1:<port>`
or `roboco-tailcat connect --config <private-connect.json> --state <client.key>
--listen 127.0.0.1:0`. The only stdout line is readiness JSON (`address` or
`url`); the secret address stays out of argv. The engine currently sets
`ROBOCO_TAILCAT_PARENT_PIPE=1` to terminate the subprocess when its stdin
closes; both the Go adapter and Rust launcher use this Roboco-owned flag.

The client keeps one `tailcat.Client` per engine for every socket. A failed
tunnel dial resets it (with the same key) only when a ~3 s disco-ping health
probe also fails; a client still on its first handshake gets 20 s first.
`Client.Ping` cannot be the probe: after the first handshake it returns
without waiting for an ack. The loopback proxy dials the tunnel only once the
caller sends bytes, so a bare connect-and-close liveness check never does.
`Close` drains in-tunnel TCP (`DrainTCP`, 1.5 s) before tearing the client
down, so the engine receives the FINs instead of waiting out its lease.

Build from this directory with Go 1.27.1 or `GOTOOLCHAIN=auto`:

```sh
CGO_ENABLED=0 go test -mod=readonly ./...
```

For a source build of the desktop, `cargo xtask tailcat-adapter [--release]`
builds it beside `target/<debug|release>/roboco[.exe]`, where the engine looks.

The release scripts cross-build the same pinned source for native Linux and
Windows architectures. Linux tarballs install it beside the Roboco executable.
Windows builds embed it in `roboco.exe` (`ROBOCO_TAILCAT_EMBED`, see
`crates/engine/build.rs`), because in-app updates replace only that file and
the portable `.exe` is that file alone; the engine unpacks it to the managed
adapters directory on first use.
