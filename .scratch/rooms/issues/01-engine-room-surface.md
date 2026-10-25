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

**Status:** resolved

**Parent:** `.scratch/rooms/spec.md`

- [x] Room store tables live in the pairing data domain, IF-NOT-EXISTS style, with 0600-address persistence
- [x] Invite parsing accepts `roboco-room:` grammar; the secret is hashed (domain-tagged like `pairing.rs` `verifier()`) and never persisted or logged; errors describe only the invite's shape
- [x] `POST /room/present` mints an ordinary session (label param), is repeatable (re-present is idempotent for the same label), and failed presentations are throttled with shape-only logging
- [x] `GetRoomBook` requires session auth + secret presentation; `UpdateRoomBook` union-merges and persists
- [x] `rooms-v1` advertised in capabilities; `roboco engine room join/list/leave` CLI works against a running engine
- [x] Room code never flips remote-access settings (regression test asserting settings are untouched by join/present)
- [x] Unit tests: verifier round-trip, re-present idempotency, throttle, book union, CLI against a fixture engine

Research: `.scratch/research/2026-10-25-room-model-catches.md` (C2 storage
conventions, C4 security invariants), spec Implementation Decisions.

## Implementation evidence — 2026-10-25

- Branch `rooms-01` (commits `091caa4e8`, `39f807a3f`, `7c66bed8c`, fixup
  `7d5c6e4b5`), merged into `rooms` as `725d5f4ff` + `e163094ef`. Diff:
  24 files, +2393/−17 (fmt-noise from an early `cargo fmt` run was reverted
  before committing; the final diff touches only the room surface).
- Room store (`crates/engine/src/rooms.rs`): `rooms` + `room_presentations`
  tables **inside `pairing.sqlite3`** (pairing data domain, IF-NOT-EXISTS, no
  migrations, WAL-shared with the CLI); book persisted at
  `rooms/{id}/book.json` via tailcat's `write_private` (0600, 0700 dirs);
  room id derived from the secret (`roboco_proto::room::room_id`,
  domain-tagged SHA-256) so every holder addresses the same hostless room;
  43-char base64url id guard against path traversal; verifier =
  `SHA-256("roboco-room-v1\0verifier\0" + secret)` — the pairing
  construction in the room's own domain. No usable secret is ever at rest.
- Presentations: `POST /room/present {secret, label}` next to
  `/pairing/redeem` in the listener (pre-session, CORS-open like redeem,
  preflight handled, `RESERVED_API_PATHS` entry), verified against the
  stored verifier BEFORE the throttle check (a real member is never locked
  out by strangers' failures); failures throttle 5-per-60 s (sliding
  window) and log shape only (`secret_chars`, `label_chars` — never
  content). Repeat presentations reuse the room's session row (fresh
  credential via `reissue_session`; a revoked row re-mints — eviction stays
  rotate-then-revoke per ADR 0011).
- Book RPCs: `GetRoomBook {roomId, secret}` (session-authed; the secret is
  verified per call — unknown-room and wrong-secret are indistinguishable)
  and `UpdateRoomBook {roomId, members}` (union-merge, sorted, 0600 persist)
  in `crates/engine/src/rpc.rs` + method constants in `crates/rpc/src/lib.rs`.
- CLI: `roboco engine room join/list/leave` (`apps/roboco/src/pairing_cli.rs`
  + `tests/room_cli.rs`) on the pairing-CLI boundary; stdout never carries
  the secret. `rooms-v1` advertised (`crates/proto/src/workspace.rs`);
  wiregen regenerated (`RoomMember`/`RoomBook` TS + capability constant).
- Regression test: `presentation_mints_an_ordinary_session_and_never_touches_settings`
  asserts `remote-access.json` is byte-identical before/after join+present
  and that nothing on disk carries the secret.
- Post-merge defect found by the gate and fixed (`7d5c6e4b5`): the grammar
  test asserted serde_json Map key ORDER, which flips under feature
  unification (GPUI enables `serde_json/preserve_order`, so multi-crate
  builds turn the Map into an IndexMap). Fixed order-insensitively (sort
  before compare), matching the `pairing_cli.rs` precedent.
- Tests (implementer, worktree): proto 114 (7 room); engine 10 room-store
  unit + 7 rooms integration + pairing/web_client/engine_info/remote_listener
  suites; CLI 15 (room_cli 2); rpc 27; engine-client 13; the exact 8-crate
  gate command re-run green (76 binaries) with only the environmental set
  failing (pi_*/previews/completion_notices — verified on base
  `d13360e8f`). Orchestrator gates on the merged tree: workspace build,
  8-crate tests, wiregen, web build+vitest (all as ticket 06 above — same
  tree).
- Deviations (accepted): the present reply is a bare pairing `SessionGrant`
  (no `roomId` — clients derive the id from the secret); `GetRoomBook`'s
  "secret presentation" is the per-call `{roomId, secret}` params (the
  browser holds the secret and passes it through — ticket 02's relayed path
  assumes exactly this); CONTEXT.md's Room glossary entry belongs to ticket
  07.
