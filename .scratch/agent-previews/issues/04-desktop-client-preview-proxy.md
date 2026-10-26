# 04 — Desktop client-side preview proxy on 7332 + remote browser open

**What to build:** The desktop client's half of the session relay. A GPUI-free client-proxy module (in or beside `crates/engine-client`, so ticket 05 can share it): binds `127.0.0.1:7332` (unclaimed — engine owns 7331, browser-helper 7333) only while a remote preview is viewed (lazy start, idle shutdown, inline error if the port is busy — mirror the 7331 failure mode, `crates/preview/src/service.rs:196-201`); routes by Host header against the remote engine's preview hostnames, sourced from the `WatchPreviews` snapshot the browser pane already receives (`crates/ui/src/browser/mod.rs:261-320`) — drive the dormant `catalog.set_remote` ingestion (`crates/preview/src/catalog.rs:187/236`) with it; and dials the remote engine's `/preview-relay` (ticket 03) over the transport the client already holds: the engine registry's dial (`connect_ws_authenticated`, `crates/rpc/src/client.rs:472-475`) for LAN engines, the Tailcat tunnel's loopback baseUrl for tunneled engines. Each accepted HTTP connection opens a mux stream (`Mux::start(transport, connector, initiator: true)` — the client never serves OPENs) and relays HTTP with the same header hygiene the engine proxy applies (hop-by-hop stripping, Host/Origin preserved, localhost redirect rewrites — factor from `crates/preview/src/proxy.rs` rather than duplicating). Then rewire the desktop: for non-local engines, `service.url()` renders against 7332 (`crates/ui/src/browser/view.rs:128`, macOS `register_preview` `mod.rs:286-289`) and the "Open this engine preview through a tunnel URL" degradation (`mod.rs:279-281`) becomes a real open; macOS extends the per-domain WebKit CONNECT registration to 7332. Linux: add a WebKitGTK proxy configuration for preview hostnames pointing at the client proxy (the macOS pattern's sibling — verify the WebKit API in-ticket; `webkit_network_proxy_settings_*`) with external-browser fallback, since stock resolvers do not synthesize `*.localhost` and the WebKitGTK helper has no `.localhost` handling today (`crates/ui/src/browser/linux/`).

**Why now:** the visible payoff — a chat on another engine gets first-class previews in the browser pane.

**Blocked by:** 03 — engine preview relay route.

**Status:** ready-for-agent
**Parent:** `.scratch/agent-previews/spec.md`

**Verification budget:** `cargo test -p roboco-engine-client` (client proxy tests: hostname routing from a snapshot, mux round-trip against a test relay, bind-conflict error), `cargo test -p roboco-ui` (browser pane tests with a fake remote engine), the `preview-fixture` example extended to a two-engine variant if cheap.

- [ ] A remote chat's preview opens from the browser pane and renders the remote dev server, HMR/WebSocket intact
- [ ] Hostnames route only to services present in the live remote snapshot; stale hostnames 502 with the existing "not running" shape
- [ ] 7332 binds only while a remote preview is viewed; a busy port surfaces an inline error, never a URL change
- [ ] Revoking the remote engine's session kills the open preview stream and the pane degrades to the reconnect state
- [ ] macOS CONNECT registration covers 7332; Linux loads previews via the WebKit proxy configuration (or documented external fallback)
- [ ] No engine-side changes (ticket 03 owns those); no changes to 7331 behavior

## Comments
