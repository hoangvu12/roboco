# 01 — Engine fleet core: kind-aware store, RoomJoin + GetFleetInvite RPCs

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

**Status:** ready-for-agent

**Parent:** `.scratch/fleet-follow/spec.md`

- [ ] `kind` round-trips through the invite grammar; absent kind = plain room; malformed kinds are shape errors
- [ ] `mint_room_secret` lives in proto; all callers updated; no behavior change
- [ ] Fleet join retains the secret in `fleet_rooms`; plain join stores no secret (disk-walk assertion after engine drop, rooms-wave test style)
- [ ] `join` stays idempotent: re-join refreshes the label and unions the book, never a second row
- [ ] `fleet_room()` / `fleet_invite()` round-trip (encode → decode → same room id, members, kind); absent fleet answers null cleanly
- [ ] `create` mints one fleet per data dir and refuses a second (fixed-text error)
- [ ] `RoomJoin` RPC: session-authed (no session → refused), idempotent, unions the book; `GetFleetInvite` returns the invite or null, session-authed
- [ ] `rooms-v2` advertised; engines without the room store attached answer cleanly
- [ ] C4a regression: `remote-access.json` byte-identical across RoomJoin/GetFleetInvite
- [ ] ADR 0008: no secret/invite content in logs, Debug, or errors; `pairing.sqlite3` stays 0600
- [ ] Unit tests for each criterion above run green on this host

## Comments
