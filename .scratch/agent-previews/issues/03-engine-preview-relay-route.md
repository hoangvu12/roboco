# 03 — Engine preview relay route: session-authenticated mux over WebSocket

**What to build:** The remote engine's half of the session-relay data plane. A new WebSocket upgrade route on the engine's existing listener (beside the tailcat-relay routing, `crates/engine/src/listener.rs:167-180` — exact path decided in-ticket, e.g. `/preview-relay/{chatId}/{serviceId}` or a query-param shape), authenticated by the existing session gate exactly like the RPC socket (`Bearer` header / first-frame `Auth`, `listener.rs:197-234`). The upgrade hands the socket to `Mux::start(transport, connector, initiator: false)` (`crates/preview/src/mux.rs:148-183`) where `transport` is a WS-backed `mux::Transport` implementation (new, small: `send`/`receive` over the tungstenite socket — binary frames, `ProgressIo` wrapping for lease/liveness like the relay does) and `connector` is the preview service's `LocalConnector` (`crates/preview/src/service.rs:285-313`) scoped so that an incoming OPEN resolves only service ids that are (a) in the live catalog, (b) attributed to the relay request's chat (ticket 01's `chatId`), and (c) still process-identity-verified before dial. Reuse the `PeerScoped` connector pattern (`mux.rs:51-82`) for the scoping rather than new machinery. No RPC envelope changes, no base64 — opaque binary pump, the `serve_relay_websocket` shape (`tailcat_relay.rs:469-561`). Expose what the route needs from `PreviewService` (a handle to `LocalConnector`/`local_route`) — the service API grows one method.

**Why now:** this is the entire engine-side remote story; clients (tickets 04/05) dial it with sessions they already hold.

**Blocked by:** 01 — origin stamping and chat attribution (the relay's authorization scoping depends on `chatId`).

**Status:** implemented
**Parent:** `.scratch/agent-previews/spec.md`

**Verification budget:** `cargo test -p roboco-engine --test preview_relay` (new) + existing preview tests; `cargo test -p roboco-preview`.

- [x] Unauthenticated dials rejected on the route; session-authenticated dials accepted on both bind flavors (loopback policy unchanged, remote bind requires the credential)
- [x] An authenticated client completes a real HTTP round-trip through the mux (request → `LocalConnector` → loopback dev server → response), including large bodies, slow readers, half-close and cancellation — the `transport.rs` idioms
- [x] A service id outside the request's chat is refused; a dead/stale service id is refused (process re-verification)
- [x] Revoking the session (or disabling remote access) tears down an open relay socket
- [x] No RPC method added; no base64 on the preview byte path; mux framing unchanged
- [x] `docs/preview-networking.md` gains the relay section (invariant: remote metadata never authorizes an arbitrary TCP address)

## Implementation record (2026-10-26)

**Route path chosen: `GET /preview-relay/{chatId}/{serviceId}`** — both ids as path segments, mirroring `/tailcat-relay/{id}`. Rationale: the listener rejects query parameters on *every* route (so neither scope nor credentials can ever appear in a URL or log — a query-param shape was not available without weakening that global rule), and carrying both ids in the path scopes one relay socket to exactly one (chat, service) pair: an OPEN over that socket may name only that service id — not another chat's, not even another service of the same chat. Segment charset: `[A-Za-z0-9_-]{1,128}` (UUIDs in practice); malformed/foreign `/preview-relay*` paths are reserved (401/404 from the gate, never the web SPA shell).

**What landed where:**

- `crates/engine/src/preview_relay.rs` (new) — `scope_from_path`/`is_relay_path`, `serve_relay_websocket` (the upgrade + `Mux::start(transport, connector, initiator: false)` + first-frame `Auth` reuse + close on mux end), and `WsTransport`, the WS-backed `mux::Transport` (one mux frame per binary message; pings answered; text/close/EOF end the transport; independent read/write locks; the tailcat relay's `ProgressIo` wrapping).
- `crates/engine/src/listener.rs` — the route rides the *existing* session gate exactly like the RPC socket: Bearer checked before the upgrade, or `None if upgrade_key.is_some() || relay_upgrade => first_frame_auth` (browser-shaped dials). Served on both bind flavors: the paired bind requires the credential; the local bind keeps its native-only, credential-free, origin-rejecting boundary (`serve_listener_with_previews`, new). `/preview-relay*` excluded from the SPA fallback.
- `crates/preview/src/service.rs` — the one new service method: `PreviewService::relay_connector(chat_id, service_id) -> Arc<dyn Connector>`, a `ChatScoped` connector following the PeerScoped pattern (scope wrapper, not new machinery): per OPEN it checks the service id equals the path's, resolves `local_route` in the live catalog, and requires the entry's `chat_id` to match the path's chat; the dial itself is the extracted `LocalConnector::connect_route` (single route lookup — no TOCTOU — with the existing process re-verification and `TcpStream::connect(catalog address)`).
- `crates/engine/src/lib.rs` — `serve_engine_remote_with_previews` (the existing `serve_engine_remote` delegates with `None`, so all 20+ existing call sites are unchanged); `remote_access.rs` carries the preview handle through `initialize` so production remote binds serve the route (`remote_settings.rs` test call site updated).
- `docs/preview-networking.md` — new "Session relay" section (route, gate, both binds, revocation semantics, the per-OPEN scoping, and the invariant **remote metadata never authorizes an arbitrary TCP address** — the client names a service id; only the engine's own live catalog turns that into an address), the transport section's remote sentence updated, and the Validation section lists the new suite.

**Commands and results** (worktree root, `RUSTC_WRAPPER=/home/ubuntu/tools/bin/sccache`):

- `cargo test --locked -p roboco-engine --test preview_relay` → **5 passed, 0 failed** (`unauthenticated_dials_are_rejected_on_the_route`, `authenticated_round_trips_through_the_mux`, `service_and_chat_scoping_is_enforced`, `stopping_the_listener_cuts_an_open_relay`, `the_local_bind_serves_the_relay_with_its_native_policy`; the round-trip/scoping/local-bind trio is `#[cfg(any(linux, macos))]` — see residuals).
- `cargo test --locked -p roboco-preview` → **all green**: lib 23, discovery 4, proxy 1, transport 1, leak 1, doc 0.
- `cargo test --locked -p roboco-engine --test previews` → **1 passed, 0 failed** (no regression).
- Listener-touching regression: `--test pairing` 3/3, `--test web_client` 2/2, `--test remote_settings` 7/7, `--test remote_listener` 6/6 — all green.
- `cargo clippy --locked -p roboco-preview -p roboco-engine --tests` → **no new warnings/errors in any touched file** (preview_relay.rs, listener.rs, lib.rs, remote_access.rs, remote_settings.rs, service.rs all clean); pre-existing clippy errors exist at the base commit in `completion_notices.rs` ("this loop never actually loops") and `rpc.rs` ("read amount is not handled") plus `lib.rs`'s "items after a test module" — verified identical by stashing the work and re-running clippy on the base (same errors, only line numbers shift).
- Mux framing untouched: `git diff HEAD~1 -- crates/preview/src/mux.rs` is empty; no RPC method constant added; no base64 anywhere on the byte path (the only base64 in the listener is the pre-existing WebSocket handshake key check).

**Test evidence for each criterion:**

- *Unauthenticated rejected / both bind flavors*: wrong Bearer → HTTP 401 at the handshake; no-Bearer dial with a binary first frame → cut before any OPEN resolves; no-Bearer dial with a wrong first-frame `Auth` → cut; `?token=x` → 400 (query strings stay rejected); authenticated non-upgrade GETs on relay paths → 404, never the SPA shell. Remote bind with a valid Bearer → round-trips work; local bind (`serve_listener_with_previews`) with no credential → round-trips work (policy unchanged) and origin-carrying requests still 403.
- *Real HTTP round-trips, the transport.rs idioms*: small body byte-exact; 2 MiB body byte-exact through the mux windows; a stalled stream (opened `/large`, never read) blocks nothing while 8 parallel round-trips complete, then drains fully; a slow reader (8 KiB reads with sleeps) still completes; every request half-closes its write side (mux END → engine shuts the dev socket's write half); dropping an unfinished `/infinite` stream drops the dev-server connection (CANCEL both ways, active-connection count → 0). Plus a browser-shaped valid first-frame `Auth` dial doing a full round-trip.
- *Scoping*: over a working `(chat-1, serviceA)` relay, OPENs for chat-2's service, a stale-process service, and an unknown id are all refused (the engine cancels the slot → the client's `open` fails at once); a relay scoped `(chat-1, serviceB)` refuses chat-2's service; `(chat-2, serviceA)` refuses chat-1's; the stale-process service (in the catalog, correctly attributed, `started_at` mismatch) is refused by the connector's re-verification.
- *Revocation*: revoking the session (the Settings action, `PairingStore::revoke`) refuses its next dial with 401; stopping the remote bind (`EngineListener::stop` — exactly what disabling remote access runs) cuts an open relay socket, and a fresh dial after the cut finds no listener.
- *No RPC/base64/mux change*: by construction — asserted via the empty mux.rs diff and the raw-WebSocket test dials (the test never uses the RPC client for the data plane).

**Residuals / known gaps:**

1. **Windows round-trip coverage**: the dial-dependent tests are `#[cfg(any(linux, macos))]` because `discovery::same_process` is unconditionally `false` on Windows today (pre-ticket-02), so the real-dial path cannot succeed there yet. The gate, revocation and path-reservation tests are ungated and run everywhere. Windows round-trip coverage should land with ticket 02's Windows process identity.
2. **Production local IPC bind not wired**: the route is served on the local bind at the listener level (`serve_listener_with_previews`, covered by tests), but `serve_engine_ipc` does not pass a preview handle — local previews already have the engine's own 7331 proxy, and no local client needs the relay. Ticket 04 can wire it if the client proxy wants a uniform dial for local engines.
3. **Mid-connection session revocation** does not cut *live* relay sockets: `RevokePairingSession` removes the credential, so the next dial is refused (asserted), but an already-open socket is not re-authenticated — identical to the RPC socket's inherited behavior (live sockets die with listener teardown). Per the ticket ("Revocation semantics are inherited"), no new re-auth machinery was added.
4. **Hyper dev servers and half-close**: the mux's END half-close reaches the dev server as a TCP write-half shutdown, and hyper 1's *default* server config treats a mid-request client EOF as `IncompleteMessage` (aborts without responding). Node-style dev servers (Vite) accept half-closed clients; the test dev server enables `http1::Builder::half_close(true)` accordingly. This property is pre-existing on the local-proxy path too (unchanged by this ticket) — ticket 04's client proxy tests should keep it in mind when hand-rolling requests.
5. First-frame `Auth` dials leave the `Auth` text frame *before* the mux frames on the wire — a native client never needs it, and the engine consumes it exactly like `roboco_rpc::serve_websocket` does.

**Deviations from the ticket (each with a reason):**

1. The scoping connector is a new `ChatScoped` in `service.rs` rather than literally reusing `mux::PeerScoped` — `PeerScoped`'s scope means *peer device* (it feeds the login-callback routing in `LocalConnector::connect_from`); overloading it with chat ids would corrupt that meaning. `ChatScoped` follows the pattern (a scope wrapper implementing `Connector`) but checks (service id == path's) + catalog chat attribution per OPEN, and dials via the extracted `LocalConnector::connect_route` so the route lookup happens once (no TOCTOU window between the chat check and the process re-verification).
2. `serve_listener_with_policy` gained a `previews: Option<PreviewService>` parameter (the local bind has no `PairedListenerContext` to carry it) — `serve_listener` keeps its signature for the existing call sites.
3. `RemoteAccessController::initialize` gained the `previews` parameter (2 call sites updated: `lib.rs`, `remote_settings.rs`) so production remote binds serve the route; `serve_engine_remote`'s signature is unchanged (new `serve_engine_remote_with_previews` beside it).

**Final commit:** `0b61654dd` ("feat(engine): session-authenticated /preview-relay route — the remote preview data plane (ticket 03)") on `ap/03-preview-relay-route`.

## Comments
