# 05 — Docs + ADR 0011 amendment + agent guide

**What to build:** The decision record and user/agent documentation.

**ADR** (`docs/adr/0011-rooms.md`) gains a dated amendment (2026-10-27):
fleet rooms retain the secret at rest in the pairing DB domain (0600) —
a fleet secret is a group pairing token; introduction becomes symmetric
(`GetFleetInvite` after pairing) and hub-driven (`RoomJoin` push from
the desktop app); runtime reachability-only, direct dials, verifier
presentations, and rotate-then-revoke are unchanged; plain rooms keep
the full hostless semantics.

**User guide** (`docs/reference/remote-access.md`): §Rooms is rewritten
as **Fleet sharing** — the 5-line personal-fleet quickstart (pair
servers → scan the pairing QR on the phone → done), the symmetric
server-intro one-liner (`roboco engine pairing create --qr`), the PC 2
paste path, dedupe/revocation semantics (revoke sessions per engine,
`room leave`), and what "not in yet" means (no liveness probing by
design).

**Agent guide:** a short "fleet for agents" section — `pairing create
--qr` + phone auto-fetch, `room create` / `room invite`, `room list
--json` for state, stable `--json` shapes, and the exit-code contract.

Sweep the repo for user-facing "Rooms" language that survived the UI
deletion (docs/, help text, UI strings); update the glossary; verify the
AGENTS.md ADR references still read true.

**Blocked by:** 02, 03, 04

**Status:** ready-for-agent

**Parent:** `.scratch/fleet-follow/spec.md`

- [x] ADR 0011 amendment records the invariant change, its justification, and what is explicitly unchanged
- [x] `remote-access.md` §Rooms → Fleet sharing: quickstart, symmetric intro, paste path, revocation, agents section
- [ ] No stale user-facing Rooms references (grep sweep: docs/, help text, UI strings) — *docs/, CLI help and desktop UI are clean; the Android Pair page's room strings (ported verbatim by 04) still say "room" and need a Kotlin + test change — see the comment*
- [x] AGENTS.md / ADR cross-references accurate

## Comments

### 2026-10-27 — evidence (Windows 11 host, worktree `roboco-ff-05`, branch `ff-05-docs`)

Part 1 (`5ce556fa`): ADR 0011 restored from v0.13.0 with the dated fleet
amendment; CONTEXT.md Fleet + Room; AGENTS.md / ARCHITECTURE.md references.

Part 2 (this commit) documents what 01–04 actually built (their evidence
comments), not the original ticket text.

**`docs/reference/remote-access.md`** gains **Fleet sharing** after Pairing
administration, where v0.13.0 had §Rooms. It covers: the quickstart, the
Tailcat prerequisite (including that the desktop's own engine is in the book
only when it serves Tailcat), pairing a phone through a server, the Fleet card
(the real captions from `FleetState::caption`), joining from another computer
(03 decision 5 solo/shared switch, decision 6 plain-invite refusal text),
starting a fleet on a server (`room create` / `room invite`; 02 deviations 3
and 5; a CLI `room join` does not add the joiner's own address), duplicates
(03 known limit / 04 deviation 3 leftover session), what "Not in yet" means
(03 decision 4 triggers; no liveness probing), leaving and evicting (03
decision 7 forward-only opt-out, decision 8 no Leave button), **Fleet for
agents**, plain rooms, and under the hood. "Pairing administration" now
mentions `--qr`; "Using several engines" notes that fleet engines appear there
too; the Android gate catalogue gains the `rooms-v1` and `rooms-v2` rows.

*Agent guide location:* in `remote-access.md` (§Fleet sharing → Fleet for
agents). `docs/agents/` only configures agent skills (issue tracker, labels,
domain), and `docs/mcp.md` covers MCP tools, not the CLI. The `--json` shapes
and the exit codes are copied verbatim from `apps/roboco/src/pairing_cli.rs`
(`ROOM_EXIT_CODES` and the `RoomCommand` doc comments). The refusal texts come
from `RoomRefusal` and `FleetConflict` (`crates/engine/src/rooms.rs:79`). The
binary was not built.

**Deviation from the requested wording:** the "server-intro one-liner"
`roboco engine pairing create --qr` does not run as written. `--base-url` is
required (`PairingCommand::Create`), and a fleet member serves Tailcat, whose
listener is loopback-only. The doc gives `roboco engine tailcat invite --qr` for
Tailcat engines and `roboco engine pairing create --base-url URL --qr` for
LAN/tunnel engines. The phone follows the fleet after either
(`PairPageModel.followFleet` runs after any persisted pairing).

**Cross-reference fixes (true-to-build):** the desktop never calls
`GetFleetInvite`; only the Android app does (`grep -rln GetFleetInvite` →
engine, proto, rpc, mobile-client, tests only). Pairing a *second desktop* to a
fleet engine does not introduce it: it mints its own fleet, and the push is
refused as "In a different fleet". So:
- AGENTS.md: "Pairing a client" → "Pairing a phone".
- ARCHITECTURE.md: "a client fetches" → "the Android app fetches"; adds the
  desktop paste path.
- CONTEXT.md Fleet: "pair a client" → "pair a phone", plus the desktop
  push/paste clause. CONTEXT.md Room: "Plain rooms (CLI only)" → "(no desktop
  UI; engines join through the CLI)", because the phone's Pair page still
  accepts plain invites.
- ADR 0011 amendment: "a client calls `GetFleetInvite`" → "the phone", plus
  the desktop paste/switch sentence. The push goes only to engines serving
  their Tailcat route. The opt-out is now described as **forward-only** (no
  remote leave; `roboco engine room leave` on the engine). The eviction
  parenthetical notes that the rotate checklist is gone and there is no Leave
  button.

**Sweep (commands and results):**
- `grep -rn -i '\brooms\?\b' docs --include=*.md | grep -v '^docs/research/' | grep -v '^docs/adr/0011'`
  → hits only in `adr/0003`, `adr/0009`, `chat2-sync.md`, `memory-plan.md`,
  `PARITY.md`, `preview-networking.md`, `registry-sync.md`,
  `markdown-preview.md` ("no room for another raster"). All of them are zeron's
  removed sync rooms or ordinary English, not ADR 0011. Left alone.
- `grep -rn -I 'Settings → Rooms\|SettingsSection::Rooms\|RoomsPage' .`
  (excluding target, .scratch, research, node_modules) → none.
- `grep -rn -i '"[^"]*\broom' crates/ui/src` (minus ids, wire names, tests) →
  `PLAIN_ROOM_INVITE` (intentional: it refuses plain-room invites and points at
  the CLI) and one internal error, "the engine room store could not be
  reached" (left as is). The Fleet card itself has no "room" except the
  `roboco-room:…` placeholder, a wire prefix.
- CLI help (`pairing_cli.rs`): `room` subcommand help says "rooms and this
  engine's fleet" / "Join a room". This is accurate, since `room` handles both
  kinds, and the wire/CLI name stays. No change. `apps/roboco/src` has no other
  room strings.
- `README.md`, `README.zh-CN.md`, `web/`: no room wording.

**Android `strings.xml` findings (not changed: values only were allowed, and
no value change is both correct and test-safe):**
1. `pair_hint` — "…or a roboco-room: room invite from any engine…".
   `PairPageTest` pins the substring "Paste a pairing link, a roboco-tailcat:
   invite, or a roboco-room: room invite", so changing it fails JVM CI.
2. `pair_room_submit` — "Join room". `RoomFlowTest` (instrumented) waits on
   `onAllNodesWithText("Join room")`.
3. `pair_room_target` — "Room “%1$s” · %2$d engines". Scanning the desktop's
   **Show fleet invite** QR reads "Room “Fleet” · N engines".
4. `pair_room_submitting` — "Joining room…".
5. `room_error_refused` — "That room invite was refused — its secret may have
   rotated, or a member left the room."
6. `room_error_throttled` — "That engine is throttling room joins…".
7. `pair_placeholder` (`roboco-room:…`) is a wire prefix and is fine.

The Pair page's join arm serves plain *and* fleet invites, and Kotlin cannot
pick a string by kind without a code change. The follow-up is a Kotlin ticket:
expose the kind on the parsed invite (`RoomInviteTarget`, via the core's
parse), add fleet variants
("Join fleet", "Fleet “%1$s” · %2$d engines"), and update `PairPageTest` /
`RoomFlowTest`. It needs an Android SDK host or CI.

**Observation, not fixed (03 behaviour):** a second desktop that already
paired an engine of fleet F lazily mints its own fleet G, whose book then
includes that engine's address, even though the `RoomJoin` push to it is
refused. Pasting F's invite therefore asks "Leave it and join" rather than
switching silently. This is harmless (one confirm), and the doc describes the
rule ("a fleet shared with other engines"), so it stays true.

**Not done:** nothing else. No code changed; docs only. Verification is the
reading of source cited above; no build or test was needed for a docs-only
change.
