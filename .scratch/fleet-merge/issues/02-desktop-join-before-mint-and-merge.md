# 02 — Desktop: join before mint, merge on meet, pending moves

**What to build:** In `crates/ui/src/rooms.rs` (`RoomOps`, `FleetSync`) and the Fleet card (`crates/ui/src/settings/remote_access/fleet.rs`), make two fleets become one with no prompt.

- **Join before mint** (`rooms.rs:575-584`): before minting, ask every paired, connected `rooms-v3` engine for its fleet (`GetFleetInvite`). If any engine has one, adopt the one with the biggest member count instead of minting: the local engine joins it through `RoomStore` and the client record is written. Mint only when no reachable engine holds a fleet.
- **Merge on meet**: when this desktop's fleet F and an engine's fleet G differ:
  - the winner is the bigger book; on a tie, F wins;
  - join the loser's members through the loser's invite (`join_room_members`; the existing registry dedupe links engines that are already paired);
  - push the winner with `replace: true` to every reachable member of both fleets, including the local engine (store, `replace`);
  - union the books (the existing heal).

  No prompt.
- **Pending moves**: loser members that can't be reached right now go into a pending list in `fleet-v1.json` (atomic, 0600), with the loser invite held in a type that never prints. Retry it on every sync (app open, first connect, Retry, opt-out change). Drop the loser secret when the list empties, or after 30 days with a one-line card caption.
- **Opt-out** is respected: an opted-out engine is never pushed or moved, and its address is never contributed.
- **Card states**: In / Not in yet (offline · Tailcat off · unconfirmed, with Retry) / "Update that engine to bring it in" (rooms-v2 only) / Left out. "In a different fleet" no longer shows as a resting state.
- **No solo leftovers**: after a sync, the desktop never holds a fleet with only itself in it while a reachable engine holds another fleet.

**Blocked by:** 01

**Status:** ready-for-agent

**Parent:** `.scratch/fleet-merge/spec.md`

- [ ] Fake-dialer tests (`crates/ui/src/rooms/tests.rs`): join-before-mint; bigger wins; tie → own; the user's three fleets (PC solo, Laptop solo, SG+US) converge to one fleet of 4
- [ ] An offline loser member moves on a later sync; the pending list survives a restart (file round-trip)
- [ ] Opted-out engine never moved; a rooms-v2-only engine shows "Update that engine to bring it in"
- [ ] No solo fleet after any sync where another fleet is reachable
- [ ] Secrets absent from `Debug`/logs; `fleet-v1.json` still loads files written by v0.14.0 (serde default)
- [ ] GPUI page test: card captions and Retry for the new states

## Comments
