# 02 — Engine relay path: `/pairing/room-redeem` for web clients

**What to build:** The browser join path of ADR 0011, extending the existing
Tailcat relay machinery (`crates/engine/src/tailcat_relay.rs`, routes at
`listener.rs:139-168`). A paired client POSTs
`/pairing/room-redeem {invite, target, label}` to the engine serving it; that
engine dials the target member from its own room book, relays the
presentation (forwards the client's `/room/present` call over the tunnel),
returns the target's session grant with `baseUrl` rewritten to
`…/tailcat-relay/{id}`, and byte-pumps frames exactly as
`serve_relay_websocket` does today. The origin engine relays the secret in
transit but never persists it — the relay keeps persisting only tunnel state
(`tailcat-relays/{id}/`), preserving the verifier-only invariant. Multiple
redeems against the same member reuse or mint relays per the existing
registry semantics; relay-directory growth is a known separate hygiene item
(out of scope here).

**Blocked by:** 01 (room store, book, presentation endpoint).

**Status:** resolved

**Parent:** `.scratch/rooms/spec.md`

- [x] `/pairing/room-redeem` on the paired listener dials the target from the engine's book and relays the presentation round-trip
- [x] Returned grant carries a `/tailcat-relay/{id}` base; the existing byte pump carries the client's traffic unchanged
- [x] No room secret is persisted by the origin engine (test: disk state after N redeems contains no secret material)
- [x] Target-unreachable degrades cleanly (caller gets a per-member failure, not a torn listener)
- [x] Integration test: a fake paired client redeems against a fixture member engine end-to-end

Research: `.scratch/research/2026-10-25-multi-engine-chats-and-taildrop.md`
(the existing relay analysis), catches file C2.

## Implementation evidence — 2026-10-25

- Branch `rooms-02` (commit `861432f89`), merged into `rooms` as
  `3cfb45c1c` (+ CI wiring `f67b151ac`). Diff: 4 engine files, +982/−3.
- `room_redeem` in `crates/engine/src/tailcat_relay.rs`: `POST
  /pairing/room-redeem {invite, target, label}` — the invite's
  `members[]` is the book (the origin engine needs NO membership; it
  persists neither invite nor secret — only tunnel state), target
  resolved by member label, dial via `TailcatClient::start_with_adapter`
  (tunnel state under `tailcat-relays/{id}/`, `connect.json` 0600),
  presentation forwarded to the member's `/room/present` over the
  tunnel, grant returned with `baseUrl` rewritten to
  `…/tailcat-relay/{id}`; the existing `serve_relay_websocket` byte
  pump carries the caller's traffic unchanged.
- Error surface: 400 shape / 404 unknown-label / 502 dial-or-transport /
  401 member-refused / 429 member-throttled — label-only texts; dial
  failures log shape-only via `tracing::warn!(member = label)`.
- Test seam: `PairedListenerContext.adapter: Option<PathBuf>` (None in
  production via `serve_engine_remote`), also retrofitted onto
  `tailcat_redeem` with unchanged production behavior.
- Tests: `crates/engine/tests/room_relay.rs` — 4 stable integration
  tests (×3 reruns): end-to-end redeem + browser first-frame Auth +
  EngineInfo + GET_REMOTE_ACCESS through the byte pump; repeat redeem
  (fresh relay, same session row); `connect.json` 0600 layout; the
  no-secret-on-disk walk over BOTH data dirs after two redeems; origin
  room store stays empty; dead-target and dial-failure degradation
  (listener not torn, follow-up redeem succeeds); shape/CORS/preflight/
  local-bind-404. Plus 3 `resolve_target` unit tests.
- Orchestrator gates (main checkout, post-merge): engine lib 478 (the
  one failure is the documented baseline flake
  `a_self_hosted_ghes_login_switches_away_and_back_without_the_network`,
  passes in isolation); integration suites green — rooms 7, room_relay
  4, pairing 3, web_client 6, remote_listener 2, remote_settings 7,
  engine_info 1; proto 115; rpc 27.
- CI: `ui-tests.yml` wired to run `--test rooms --test room_relay`
  (engine-local-regressions) and `--test room_cli` (ui job) — the
  workflow's explicit target lists would otherwise have skipped them.
- Deviations (accepted): invite-as-book (stronger verifier-only posture
  than "the engine's own book"; ticket 05 must list members from the
  invite it holds); `target` is a member label with first-match on
  duplicates (unit-pinned); a failed redeem leaves an empty
  `tailcat-relays/{id}/` shell — the known relay-directory hygiene item,
  pre-existing behavior, out of scope.
