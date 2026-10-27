# 03 — Desktop: create, join, and manage rooms

**What to build:** The desktop side of ADR 0011, in Settings (no sidebar
changes in v1). Create room: mint the secret (OS CSPRNG), seed the book from
the local engine plus selected paired engines (`EngineRegistry`,
`crates/engine-client/src/registry.rs`), serialize the
`roboco-room:` invite, and show it as a QR + copyable string — the invite is
secret-bearing and follows ADR 0008 display rules (never logged, clipboard
warnings). Join room: paste an invite, dial each member directly through the
existing paired-engine machinery, present per member, store the room secret
in the desktop client config beside `paired-engines-v1.json`. The desktop
engine itself joins via the ticket-01 surface (Settings action + the CLI for
headless boxes). Rotate room secret: mint a new secret, show a guided
checklist of members (engines and clients) that have re-presented, keep the
old verifier rejecting until manually dropped. Leave room: forget + best-effort
own-session revokes per member. Room UI is hidden when a member engine lacks
`rooms-v1` (capability check, not error toasts).

**Blocked by:** 01 (engine surface). The rotate flow also depends on 04/05
existing for the checklist to be meaningful, but ships against engine
verifiers alone.

**Status:** resolved

**Parent:** `.scratch/rooms/spec.md`

- [x] Settings → Rooms: create (pick member engines), join (paste), leave, rotate — full flow
- [x] Invite shown as QR + string; never written to logs; engine join consumes it and stores only the verifier
- [x] Joining a room surfaces member engines in the existing Devices/sidebar flow with no new grouping layer
- [x] Composer/space palette device picker lists room members like any paired engine (no routing changes — per-engine, `ScopedId` untouched)
- [x] Rotate flow tracks re-presented members and old-secret rejection
- [x] Room UI hidden when `rooms-v1` is absent on the local engine
- [x] Manual test on Windows: room of desktop + fixture headless engine; phone-less dry run of join/rotate/leave

Research: `.scratch/research/2026-10-25-room-model-catches.md` (C5 unknown-method
UX, C6 ScopedId seams), spec Implementation Decisions.

## Implementation evidence — 2026-10-25

- Branch `rooms-03` (commits `514c8095f` + the review fix `ac713514c`),
  merged into `rooms`. Diff: engine-client + ui (+Cargo.lock), ~35
  files, +2.6k/−0.3k.
- `crates/engine-client` rooms module: `RoomSecretFile`
  (`rooms-v1.json` beside `paired-engines-v1.json`, additive, atomic,
  **0600**, `Debug` redacts secret and member addresses);
  `present_room_member` (`POST /room/present`, fixed-text refusals);
  `EngineRegistry::join_room_member` (adopt extracted from `pair()` —
  joined engines are ordinary paired engines; ScopedId untouched);
  `join_room_members` (dial → present → adopt per member, dead
  forwarders retired + re-dialed, then the client-driven book union:
  `GetRoomBook` in, `UpdateRoomBook` back); `revoke_room_sessions`;
  `mint_room_secret` (32 bytes OS CSPRNG → base64url).
- `crates/ui`: `rooms.rs` (RoomOps create/join/sync/leave/rotate;
  TailcatRoomDialer; local engine joins through its own RoomStore;
  the rotate checklist state machine) + `settings/rooms.rs` (the page:
  paste-join, create with member picks, invite cards with QR + string
  + the ADR 0008 secrecy caption, room cards, member rows — labels
  only) + shell/state wiring. Capability gating:
  `SettingsSection::Rooms` filtered from the nav when the local engine
  lacks `rooms-v1` — hiding, never toasts.
- SECURITY REVIEW FINDING (orchestrator) and fix (`ac713514c`): the
  create flow originally harvested member addresses via
  `CreateTailcatInvite`, which force-enables and persists Tailcat on
  engines where it is off — the C4a escalation primitive, forbidden by
  the invariants. The fix guards with `GET_REMOTE_ACCESS` (Serves =
  enabled + loopback listener): only engines already serving Tailcat
  get the 1-second mint (token never redeemed); Off engines are
  skipped into `unavailable` with a label-only enable-first note.
  Tests prove zero `CreateTailcatInvite` RPCs against off engines and
  that `remote-access.json` never exists after the flow. Known
  documented edge: a network-transport engine deliberately bound to
  loopback reads as tailcat-shaped (unreachable by any remote client;
  acceptable, engine-side transport field is the full fix — parking
  lot).
- Tests (implementer): ui 1692 (14 rooms tests incl. the two new
  recording-rig guard tests), engine-client 22 (9 rooms tests), builds
  green, rustfmt clean. Orchestrator gates (main checkout, post-merge,
  including the fix): ui 1692/1692, engine-client 22/22, `cargo build
  -p roboco` green.
- Deviations (accepted): the Windows manual-test checkbox was
  substituted with headless flows against real `EngineCore`s over
  loopback with a fake dialer (the production `TailcatClient::start`
  dialer path remains untested locally — user does the Windows pass);
  create does NOT present to members (hostless semantics — the invite
  is handed over; Reconnect performs introductions); page async flows
  are tested at RoomOps level (gpui::test can't run cross-thread
  wakes); leave also drops the local engine's room and forgets only
  room-recorded registry entries.
