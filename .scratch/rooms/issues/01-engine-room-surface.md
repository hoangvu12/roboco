# 01 — Engine room surface: store, join, present, book RPCs

**What to build:** The engine-side room surface of ADR 0011. A room store
following the `pairing.sqlite3` precedent (same data domain — who is allowed
in; IF-NOT-EXISTS tables, no migration machinery) holding `{id, label,
SHA-256 verifier, book}` per room; member addresses persisted with the same
0600 discipline as relay `connect.json` entries (`crates/engine/src/tailcat.rs`
`write_private`). Engine joins a room by parsing an invite
(`roboco-room:<base64url(json{v,name,secret,members[]})>`), hashing the secret,
and discarding it. Presentations: `POST /room/present {secret, label}` on the
listener — redeem-style, pre-session, next to `/pairing/redeem`
(`crates/engine/src/listener.rs`) — verified against the verifier, minting an
ordinary session (labeled, listed, revocable like any other). Session-authed
RPCs `GetRoomBook` (additionally requires a secret presentation, since member
addresses are transport secrets) and `UpdateRoomBook` (union merge, persisted).
Advertise the `rooms-v1` capability in `EngineInfo.capabilities`
(`crates/proto/src/workspace.rs`). CLI: `roboco engine room join <invite>`
plus `roboco engine room list/leave`. Critical constraint from the catches
research: presentations must **never mutate remote-access settings** — unlike
`CreateTailcatInvite`, which force-enables and persists Tailcat
(`crates/engine/src/remote_access.rs:390-421`), a room join only works on
engines that are already reachable, so no settings escalation exists.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

**Parent:** `.scratch/rooms/spec.md`

- [ ] Room store tables live in the pairing data domain, IF-NOT-EXISTS style, with 0600-address persistence
- [ ] Invite parsing accepts `roboco-room:` grammar; the secret is hashed (domain-tagged like `pairing.rs` `verifier()`) and never persisted or logged; errors describe only the invite's shape
- [ ] `POST /room/present` mints an ordinary session (label param), is repeatable (re-present is idempotent for the same label), and failed presentations are throttled with shape-only logging
- [ ] `GetRoomBook` requires session auth + secret presentation; `UpdateRoomBook` union-merges and persists
- [ ] `rooms-v1` advertised in capabilities; `roboco engine room join/list/leave` CLI works against a running engine
- [ ] Room code never flips remote-access settings (regression test asserting settings are untouched by join/present)
- [ ] Unit tests: verifier round-trip, re-present idempotency, throttle, book union, CLI against a fixture engine

Research: `.scratch/research/2026-10-25-room-model-catches.md` (C2 storage
conventions, C4 security invariants), spec Implementation Decisions.
