# 04 — Remove browser-only engine surfaces and the browser-helper CLI

**What to build:** Every engine path that exists only because a browser cannot set headers or dial Tailcat goes away. The paired bind then refuses browser-shaped requests outright.

**Listener (`crates/engine/src/listener.rs`)**
- Remove the CORS preflight (`OPTIONS`) handling and `cors_open` on the redeem routes.
- Remove the `/pairing/tailcat-redeem`, `/pairing/room-redeem` and `/tailcat-relay/{id}` routes.
- Remove `authenticate_first_frame`, `refuse` (4401) and the `first_frame_auth` path. A WebSocket upgrade on `/` without `Authorization: Bearer` gets 401 before the upgrade.
- **New:** reject any request carrying an `Origin` header on the paired bind with 403, mirroring the Local-bind rule (lines ~136-145, which stay exactly as they are). Put the check where it covers every route, including redeem and `/room/present`.

**Engine wiring**
- Delete `crates/engine/src/tailcat_relay.rs` and its wiring in `lib.rs` (`PairedListenerContext`, `restore_from_disk`, the `tailcat-relays/` restore). Simplify `serve_listener_with_policy`'s signature.
- Do not delete existing `tailcat-relays/` dirs on disk (spec: no migration).
- `crates/engine/src/preview_relay.rs`: remove the `first_frame_auth` parameter and branch. `/preview-relay` is Bearer-only, and a missing Bearer gets 401.

**CLI (`apps/roboco`)**
- Delete `src/tailcat_browser_helper.rs`, `TailcatCommand::BrowserHelper` and `mod tailcat_browser_helper`. Port 7333 is no longer claimed.
- `roboco engine tailcat connect`: keep the behavior and reword the help/output to call the URL a "loopback pairing link for a Roboco app". The `/pair#token=` grammar is unchanged.

**Tests and examples**
- Delete `crates/engine/tests/web_client.rs` (whatever 03 left), `tests/room_relay.rs`, `examples/web_conformance.rs` and `examples/web_smoke.rs`. In the same commit, drop `--test room_relay` from `.github/workflows/ui-tests.yml`'s engine test line, so the head never names a missing test.
- Turn the browser-path cases into refusals: `tests/pairing.rs` (~188 CORS → `OPTIONS` is no longer special and `Origin` gets 403), `tests/preview_relay.rs` (~360-420 and ~452-570 first-frame → a Bearer-less upgrade gets 401), `tests/remote_listener.rs` (~141) and `tests/rooms.rs` (~558, browser room redeem → 404). Line numbers are from the 2026-10-28 research on fleet-follow and will have drifted.
- New: a test that the desktop's Rust client (`crates/rpc` Bearer dial), the preview-relay dialer (`crates/preview-relay/src/client.rs`) and the mobile client's redeem (`crates/mobile-client` pairing) all succeed against the paired bind with the Origin rule on. Native stacks send no `Origin`. If one does, record it here before changing the rule.

**Blocked by:** 03

**Status:** done

**Parent:** `.scratch/web-removal/spec.md`

- [x] `tailcat_relay.rs`, `tailcat_browser_helper.rs`, `web_client.rs`, `room_relay.rs` and the two web examples are gone. `ui-tests.yml` no longer names `room_relay`.
- [x] Paired bind: `OPTIONS` gets no CORS headers. Any `Origin` → 403. `/pairing/tailcat-redeem`, `/pairing/room-redeem` and `/tailcat-relay/x` → 404. A Bearer-less WS on `/` or `/preview-relay` → 401.
- [x] Local bind Origin rejection is unchanged (its existing test passes as written)
- [x] Native paths are green: `cargo test -p roboco-engine --test pairing --test remote_listener --test preview_relay --test rooms`, `-p roboco-engine-client`, `-p roboco-mobile-client`, `-p roboco-preview-relay`
- [x] `roboco engine tailcat --help` lists no `browser-helper`. The `connect` help says "loopback pairing link". `cargo test -p roboco --test pairing_cli --test tailcat_invite_cli --test room_cli` passes.
- [x] `git grep -n "first_frame_auth\|tailcat-redeem\|room-redeem\|tailcat-relay/\|browser-helper\|BrowserHelper" -- crates apps` is empty

## Comments

- 2026-10-28 (implementer): Listener: CORS preflight, `cors_open`, `/pairing/tailcat-redeem`, `/pairing/room-redeem`, `/tailcat-relay/{id}`, `authenticate_first_frame`, `refuse` (4401), `FIRST_FRAME_AUTH_TIMEOUT` and the `first_frame_auth` path are gone. The paired bind refuses any `Origin` with 403 as the first check after the Local-bind rule, which is unchanged. It covers every route, redeem and `/room/present` included. A Bearer-less upgrade on `/` or `/preview-relay/…` is a 401 before the upgrade. `tailcat_relay.rs` and its `lib.rs` wiring (`PairedListenerContext`, the restore task) are gone; `serve_listener_with_policy` lost its `paired` parameter; `preview_relay::serve_relay_websocket` lost `first_frame_auth`/`pairing`; `preview_relay::is_relay_path` (SPA-fallback only) is gone. On-disk `tailcat-relays/` dirs are left alone.
- CLI: `tailcat_browser_helper.rs`, `TailcatCommand::BrowserHelper` and the `mod` line are gone. `roboco engine tailcat --help` lists `address`, `invite`, `connect` (no `browser-helper`). `connect`'s help reads "Dial this engine's route and print a loopback pairing link for a Roboco app", and its stdout adds "Paste this loopback pairing link into a Roboco app." before the forwarding line. The `/pair#token=` output is unchanged.
- Tests: `web_client.rs`, `room_relay.rs`, `examples/web_conformance.rs` and `examples/web_smoke.rs` are deleted; `ui-tests.yml` no longer names `--test room_relay`. (`web-engine-client.yml` still builds the two deleted examples until ticket 06 deletes the workflow. That is allowed between commits, and the PR head has no such reference.) `tests/pairing.rs` needed no edit: its Origin case is on the Local bind and passes as written. `tests/preview_relay.rs`: the first-frame dials are now a missing-Bearer 401, plus an Origin+valid-Bearer 403; the valid first-frame round trip is gone. `tests/remote_listener.rs`: the credential-less dial is now a 401 at the upgrade. New `paired_bind_refuses_browser_shaped_requests` covers: Origin 403 on redeem (without spending the code), `/room/present`, `/health` and the upgrade; OPTIONS 403 with an Origin and 404 without, never with CORS headers; the removed routes 404; Bearer-less `/` and `/preview-relay` upgrades 401. `tests/rooms.rs`: `unauthenticated_call_fails` now requires the upgrade itself to fail.
- New `crates/mobile-client/tests/origin_rule_engine.rs::native_clients_pass_the_paired_bind_origin_rule` runs against a real engine with the rule live (a browser-shaped `/health` gets 403). The mobile client's redeem plus Bearer RPC reach `Connected`; `roboco_rpc::connect_ws_authenticated` (the desktop's Rust RPC dial) serves `EngineInfo`; and `roboco_preview_relay::client::dial_relay` gets its 101. No native stack sends `Origin`. The desktop registry's pairing is exercised by `roboco-engine-client`'s suites (green).
- Green: `cargo test -p roboco-engine --test pairing --test remote_listener --test preview_relay --test rooms` (3/4/2/9). `cargo test --no-fail-fast -p roboco-mobile-client -p roboco-engine-client -p roboco-preview-relay` is all green. One earlier run failed `session_engine::a_frozen_transcript_connection_is_replaced` (a stall-proxy liveness timing test, under host load); it passed on the rerun and is unrelated. `cargo +1.99.0 test -p roboco --test pairing_cli --test tailcat_invite_cli --test room_cli` is green (`room_cli` runs 0 tests on Windows).
- `git grep -n "first_frame_auth\|tailcat-redeem\|room-redeem\|tailcat-relay/\|browser-helper\|BrowserHelper" -- crates apps`: the only hits are the three negative assertions in `remote_listener.rs` that prove those routes now 404. They are deliberate.
- `cargo +1.99.0 check --workspace --all-targets`: no errors, no new warnings (the deleted files' warnings are gone).
