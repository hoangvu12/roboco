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

**Status:** ready-for-agent

**Parent:** `.scratch/web-removal/spec.md`

- [ ] `tailcat_relay.rs`, `tailcat_browser_helper.rs`, `web_client.rs`, `room_relay.rs` and the two web examples are gone. `ui-tests.yml` no longer names `room_relay`.
- [ ] Paired bind: `OPTIONS` gets no CORS headers. Any `Origin` → 403. `/pairing/tailcat-redeem`, `/pairing/room-redeem` and `/tailcat-relay/x` → 404. A Bearer-less WS on `/` or `/preview-relay` → 401.
- [ ] Local bind Origin rejection is unchanged (its existing test passes as written)
- [ ] Native paths are green: `cargo test -p roboco-engine --test pairing --test remote_listener --test preview_relay --test rooms`, `-p roboco-engine-client`, `-p roboco-mobile-client`, `-p roboco-preview-relay`
- [ ] `roboco engine tailcat --help` lists no `browser-helper`. The `connect` help says "loopback pairing link". `cargo test -p roboco --test pairing_cli --test tailcat_invite_cli --test room_cli` passes.
- [ ] `git grep -n "first_frame_auth\|tailcat-redeem\|room-redeem\|tailcat-relay/\|browser-helper\|BrowserHelper" -- crates apps` is empty

## Comments
