# 09 — Delete rooms and the fleet code

**What to build:** remove the room machinery, now that tickets 06 and 08 no longer use it:

- **Engine:**
  - `crates/engine/src/rooms.rs`;
  - `POST /room/present`, `/pairing/room-redeem`;
  - `GetRoomBook`, `UpdateRoomBook`, `RoomJoin`, `GetFleetInvite`;
  - the `rooms-v1`/`rooms-v2` capabilities.
  - On startup, rows in `fleet_rooms` and the room tables are **securely erased**, and the tables dropped.
- **Proto:** `crates/proto/src/room.rs` (`roboco-room:` invites). A pasted `roboco-room:` gets the fixed text "Fleet invites are gone. Use a connect code from Settings → Devices."
- **CLI:** `roboco engine room *`, and `apps/roboco/tests/room_cli.rs`.
- **Desktop:** `crates/ui/src/rooms.rs` (+ tests), `settings/remote_access/fleet.rs`, and `fleet-v1.json` handling (keep only the erase from ticket 06).
- **Mobile:** `crates/mobile-client/src/rooms.rs`, `crates/mobile/src/client_ffi/rooms.rs`, and the Android room UI and storage.
- Any web room code still present after the web-removal PR.
- Clients probing old engines: an engine that still serves rooms is simply treated as a one-way paired engine.

**Blocked by:** 06, 08

**Status:** ready-for-agent

**Parent:** `.scratch/fleet-redesign/spec.md`

- [ ] `rg -i 'room|fleet_rooms|GetFleetInvite|RoomJoin'` over `crates/`, `apps/` shows only the legacy-paste message, the erase migration and unrelated words (`room_gen`, "breathing room")
- [ ] Erase test: a v0.14 `pairing.sqlite3` with fleet rows opens, and afterwards the secret bytes are absent from the file and its WAL
- [ ] Workspace builds and tests pass on Windows and Linux; Android builds

## Comments
