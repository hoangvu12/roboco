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

Build from this directory with Go 1.27.1 or `GOTOOLCHAIN=auto`:

```sh
CGO_ENABLED=0 go build -mod=readonly -trimpath -o ../../target/roboco-tailcat ./cmd/roboco-tailcat
CGO_ENABLED=0 go test -mod=readonly ./...
```

The release scripts cross-build the same pinned source for native Linux and
Windows architectures and install it beside the Roboco executable. Windows
in-app updates currently replace `roboco.exe` only, not this companion;
installing a new ZIP or installer upgrades the adapter too.

The Expo Android client binds the same Go package into
`target/tailcat/RobocoTailcat.aar`; see `apps/mobile/Native/build-aar.sh`.
`netmon_android.go` is carried from the recovered Kratos mobile experiment and
uses bionic interface discovery where Android refuses netlink route queries.
It is compiled only for Android with cgo; native desktop builds are unchanged.
