# 01 — Engine fleet core: wire the re-landed surface, RoomJoin + GetFleetInvite

**Port reality (2026-10-27):** the foundation shim on `feature/fleet-follow`
already re-lands `crates/engine/src/rooms.rs` (the store: verifier, book,
present, `PairingStore::mint_session`/`reissue_session` restored) and the
proto/constant/capability layer. What remains for this ticket is the WIRING
and the FLEET EXTENSIONS — treat the store file as landed, extend it, do not
re-port it. Your worktree should merge the latest `feature/fleet-follow`
before starting.

**What to build:** The engine-side foundation of fleet follow.

(a) **Proto** (`crates/proto/src/room.rs`): `RoomInvite` gains an optional
`kind` (`"fleet"`; absent = plain room) — grammar, validation, wiregen
regenerated. `mint_room_secret()` moves here from
`crates/engine-client/src/rooms.rs` (update callers; a re-export is fine).

(b) **Store** (`crates/engine/src/rooms.rs`): a `fleet_rooms (room_id PK,
secret)` table in `pairing.sqlite3` (IF-NOT-EXISTS, no migration
machinery) written only for fleet-kind joins. `join` retains the secret iff
`kind=fleet` (plain rooms keep verifier-only semantics byte-for-byte);
`leave` drops the fleet row; `fleet_room()` returns the data dir's one
fleet room if any; `fleet_invite(id)` serializes a fresh `RoomInvite`
(secret from the table, book from disk, name from the label, kind=fleet);
`create(name, members)` is the mint path for CLI bootstrapping (id on
return, secret never logged) and refuses when a fleet room already exists
(one per data dir).

(c) **RPCs** (`crates/engine/src/rpc.rs`, new arm beside
`GET_ROOM_BOOK | UPDATE_ROOM_BOOK` around line 1482; constants in
`crates/rpc/src/lib.rs`): `RoomJoin {invite}` — session-authed, idempotent
join (union book, never a second row), returns `{roomId, members}`; and
`GetFleetInvite {}` — session-authed, returns the fleet invite or null.
Params structs that carry secrets get no `Debug` (the `GetRoomBook`
precedent at rpc.rs:119).

(d) **Capability**: advertise `rooms-v2` in
`crates/proto/src/workspace.rs` `capabilities::CURRENT`.

Critical constraints: C4a — neither RPC mutates remote-access settings;
ADR 0008 — no secret or invite content in logs, Debug output, or errors
(shape-only, like the existing room surface).

**Blocked by:** None — can start immediately.

**Status:** ready-for-human

**Parent:** `.scratch/fleet-follow/spec.md`

- [x] `kind` round-trips through the invite grammar; absent kind = plain room; malformed kinds are shape errors
- [x] `mint_room_secret` lives in proto; all callers updated; no behavior change
- [x] Fleet join retains the secret in `fleet_rooms`; plain join stores no secret (disk-walk assertion after engine drop, rooms-wave test style)
- [x] `join` stays idempotent: re-join refreshes the label and unions the book, never a second row
- [x] `fleet_room()` / `fleet_invite()` round-trip (encode → decode → same room id, members, kind); absent fleet answers null cleanly
- [x] `create` mints one fleet per data dir and refuses a second (fixed-text error)
- [x] `RoomJoin` RPC: session-authed (no session → refused), idempotent, unions the book; `GetFleetInvite` returns the invite or null, session-authed
- [x] `rooms-v2` advertised; engines without the room store attached answer cleanly
- [x] C4a regression: `remote-access.json` byte-identical across RoomJoin/GetFleetInvite
- [ ] ADR 0008: no secret/invite content in logs, Debug, or errors; `pairing.sqlite3` stays 0600 — *secret/log/Debug/error half demonstrated; the 0600 half is a `#[cfg(unix)]` test (`the_pairing_database_is_owner_only`) that cannot run on this Windows host — Linux CI*
- [x] Unit tests for each criterion above run green on this host (except the unix-only 0600 test above)

## Comments

### 2026-10-27 — implementation evidence (Windows 11 host, solo integrator)

**Gate first.** `cargo test -p roboco-proto room` 6/6 green. `cargo test -p
roboco-engine rooms` initially did not COMPILE on Windows: `roboco-harness`
calls the unix-only `process::dies_with_parent` from nine ungated sites.
v0.13.0 had a `#[cfg(windows)]` no-op for exactly this (PR #31); the wave-9
merge dropped it with rooms. Restored in `effa8bd3` (separate commit). After
that: 10/10 green.

**Deviations (recorded before building on them):**

1. *Contract vs ticket text:* this ticket says `RoomJoin` returns
   `{roomId, members}`; the frozen contract says `{"roomId":"…"}` only,
   never echo members. Built against the frozen contract.
2. *One fleet per data dir is enforced at `join` too*, not only `create`: a
   fleet-kind join while a DIFFERENT fleet row exists is refused with the
   same fixed text as `create` (`FleetConflict`: "this engine already
   belongs to a fleet; leave it first"). Re-joining the SAME fleet is
   idempotent. Desktop (03) handles the PC-2 case by leaving its own
   lazily-minted fleet before joining a pasted one.
3. *`pairing.sqlite3` was never chmodded* on the trunk (it held verifiers
   only). Now that it can hold a usable fleet secret, `RoomStore::open`
   sets the db and its `-wal`/`-shm` sidecars to 0600 on unix (SQLite
   creates later sidecars with the db's mode). No-op on Windows.
4. *Leaving a fleet erases the secret from disk*: `secure_delete` for the
   delete plus a best-effort `wal_checkpoint(TRUNCATE)` (a concurrently
   running engine can keep old WAL frames until its own next checkpoint).
5. *`mint_room_secret`* was already moved to proto by the shim; there are no
   remaining callers on this branch (engine-client's copy is not re-landed —
   ticket 03 must import it from `roboco_proto::room`).
6. *Engine-side rooms wiring had to be re-landed too* (not just "extended"):
   the shim left the store unwired, so `/room/present`, `GetRoomBook`/
   `UpdateRoomBook`, `EngineCore.rooms`, and the listener plumbing were
   re-applied from `39f807a3` (3-way, clean), and `crates/engine/tests/rooms.rs`
   restored from the tag. NOT re-landed: `/pairing/room-redeem` (web relay,
   `861432f8`) — spec lists the web room flow out of scope.
7. *Windows test fix:* the tag's secret disk-walks `unwrap()`ed every read;
   on Windows the engine's `*.lock` files hold byte-range locks while it
   runs (os error 33). The walk now skips exactly a locked `*.lock` file on
   Windows and fails on every other read error.
8. *wiregen was stale at HEAD* (CURRENT gained rooms-v1/v2, constants.ts
   didn't): `RoomMember`/`RoomBook` re-registered, `ROOMS_V1`/`ROOMS_V2`
   constants emitted, regenerated; `wiregen --check` → fresh.

**What landed:** proto `RoomInvite::fleet`/`is_fleet`, `kind` in the
redacting `Debug`; store `fleet_rooms` table, kind-aware `join`/`list`/
`record`/`leave`, `fleet_room`, `fleet_invite`, `create`, `FleetConflict`,
`RoomRecord.kind`; RPC arms `RoomJoin` (hand-parsed `invite` so no serde
message can quote it; store errors collapse to fixed text; shape-only
`info!` with id/kind/member count) and `GetFleetInvite` (`{"invite":…|null}`).

**Verification (this host):**

- `cargo test -p roboco-proto room` → 8 passed (new:
  `the_fleet_kind_round_trips_and_plain_stays_absent`,
  `minted_secrets_are_fresh_and_valid`)
- `cargo test -p roboco-engine --lib room` → 15 passed (new store tests:
  `a_fleet_join_retains_the_secret_and_a_plain_join_does_not`,
  `fleet_invites_round_trip_and_absent_fleets_answer_none`,
  `one_fleet_per_data_dir_and_rejoining_it_is_idempotent`,
  `leaving_a_fleet_erases_the_retained_secret_from_disk`; RPC:
  `room_rpcs_answer_cleanly_without_a_room_store`)
- `cargo test -p roboco-engine --test rooms` → 9 passed (new:
  `room_join_and_fleet_invite_are_session_authed_idempotent_and_settings_free`
  — no-session refusal, bad credential refusal, rooms-v2 advertised, null
  answer, `{roomId}`-only reply, re-push = one row + union, plain room
  alongside, fleet conflict, junk invites never quoted, local IPC parity,
  fleet secret presents, `remote-access.json` byte-identical before/after
  shutdown; `a_plain_room_join_over_rpc_leaves_no_secret_at_rest` — walk
  after shutdown: plain secret nowhere, fleet secret only in
  `pairing.sqlite3*`)
- `cargo test -p roboco-engine --test pairing --test web_client` → 3 + 6
  passed; `cargo test -p roboco-engine-client --lib cache` → 1 passed
- `cargo check -p roboco-engine --all-targets` clean;
  `cargo run -p wiregen -- --check` fresh
- CI-only: `rooms::tests::the_pairing_database_is_owner_only` (unix)
