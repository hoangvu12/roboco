# 03 — Engine preview relay route: session-authenticated mux over WebSocket

**What to build:** The remote engine's half of the session-relay data plane. A new WebSocket upgrade route on the engine's existing listener (beside the tailcat-relay routing, `crates/engine/src/listener.rs:167-180` — exact path decided in-ticket, e.g. `/preview-relay/{chatId}/{serviceId}` or a query-param shape), authenticated by the existing session gate exactly like the RPC socket (`Bearer` header / first-frame `Auth`, `listener.rs:197-234`). The upgrade hands the socket to `Mux::start(transport, connector, initiator: false)` (`crates/preview/src/mux.rs:148-183`) where `transport` is a WS-backed `mux::Transport` implementation (new, small: `send`/`receive` over the tungstenite socket — binary frames, `ProgressIo` wrapping for lease/liveness like the relay does) and `connector` is the preview service's `LocalConnector` (`crates/preview/src/service.rs:285-313`) scoped so that an incoming OPEN resolves only service ids that are (a) in the live catalog, (b) attributed to the relay request's chat (ticket 01's `chatId`), and (c) still process-identity-verified before dial. Reuse the `PeerScoped` connector pattern (`mux.rs:51-82`) for the scoping rather than new machinery. No RPC envelope changes, no base64 — opaque binary pump, the `serve_relay_websocket` shape (`tailcat_relay.rs:469-561`). Expose what the route needs from `PreviewService` (a handle to `LocalConnector`/`local_route`) — the service API grows one method.

**Why now:** this is the entire engine-side remote story; clients (tickets 04/05) dial it with sessions they already hold.

**Blocked by:** 01 — origin stamping and chat attribution (the relay's authorization scoping depends on `chatId`).

**Status:** ready-for-agent
**Parent:** `.scratch/agent-previews/spec.md`

**Verification budget:** `cargo test -p roboco-engine --test preview_relay` (new) + existing preview tests; `cargo test -p roboco-preview`.

- [ ] Unauthenticated dials rejected on the route; session-authenticated dials accepted on both bind flavors (loopback policy unchanged, remote bind requires the credential)
- [ ] An authenticated client completes a real HTTP round-trip through the mux (request → `LocalConnector` → loopback dev server → response), including large bodies, slow readers, half-close and cancellation — the `transport.rs` idioms
- [ ] A service id outside the request's chat is refused; a dead/stale service id is refused (process re-verification)
- [ ] Revoking the session (or disabling remote access) tears down an open relay socket
- [ ] No RPC method added; no base64 on the preview byte path; mux framing unchanged
- [ ] `docs/preview-networking.md` gains the relay section (invariant: remote metadata never authorizes an arbitrary TCP address)

## Comments
