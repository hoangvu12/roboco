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

**Status:** claimed

**Parent:** `.scratch/rooms/spec.md`

- [ ] `/pairing/room-redeem` on the paired listener dials the target from the engine's book and relays the presentation round-trip
- [ ] Returned grant carries a `/tailcat-relay/{id}` base; the existing byte pump carries the client's traffic unchanged
- [ ] No room secret is persisted by the origin engine (test: disk state after N redeems contains no secret material)
- [ ] Target-unreachable degrades cleanly (caller gets a per-member failure, not a torn listener)
- [ ] Integration test: a fake paired client redeems against a fixture member engine end-to-end

Research: `.scratch/research/2026-10-25-multi-engine-chats-and-taildrop.md`
(the existing relay analysis), catches file C2.
