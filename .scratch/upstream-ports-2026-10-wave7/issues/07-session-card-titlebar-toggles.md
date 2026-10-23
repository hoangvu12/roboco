# 07 — Session card superset: project, device, side chats and actions behind one toggle (upstream ac4acbc2 + 6774a37a + c8c717862 + 591824881; folds ticket 12)

**2026-10-22 re-scope:** upstream built on this card twice since the
2026-10-11 note — port the superset, not the stale base. Add
`c8c717862` (#867: the card shows beside a roomy transcript, floats
over a tight one — `session_info.rs` +240/−140, `sidebar_sections.rs`,
`tabs.rs`) and `591824881` (#895: side chats and subagents sections in
the card; deletes `files/sections.rs` (roboco holds it, from the
`731697b6` side-chats explorer port) and `files/subagents.rs` (roboco
never took it) as the card absorbs the explorer panels; also touches
`popover.rs` +80, `harness/mock.rs` +178, adds
`shell/panel_interaction_tests.rs` +133). **Ticket 12 folds in here**
(see its file): `591824881` consumes `Session.running_subagents`, so
port `0c4835d2`'s wire entity (engine publishes the count; optional on
the wire, stale rows count zero) with this ticket — wire change, run
the wiregen gate. `running_pill.rs` is absent in roboco: port as much
of #647's pill shape as #895's +44 hunks require, or adapt around the
absence; do NOT port #647's files-panel subagents surface (dead
upstream — #895 deleted it).

**What to build:** Port the visible half of the screenshot gap. `ac4acbc2`
collapses 564 lines of sprawl out of `shell/actions_ui.rs` into a new
`shell/session_info.rs` (443 lines): the session card gathers project,
device, side chats and actions behind one toggle, `shell.rs` and
`shell/tabs.rs` re-frame the layout (−147 from tabs), `transcript.rs`
rides along (+28). `6774a37a` follows on top: the titlebar gains a
frame-style session-card toggle and a folder-tree explorer toggle
(new `folder-tree.svg` icon, `list-rows.svg` retired; `icons.rs` +43).

**Why now (verified 2026-10-11):** no `crates/ui/src/shell/session_info.rs`
exists; roboco's `actions_ui.rs` still carries all 1290 lines; neither
`folder-tree.svg` nor `list-rows.svg` is in `crates/ui/assets/icons/`.
This is the commit pair that makes the sidebar "show on hover" the
Zeron creator described.

**Steps:**
1. `git show ac4acbc2` (10 files, +603/−681), `git show 6774a37a`
   (4 files, +45/−7), `git show c8c717862` (#867, 6 files), `git show
   591824881` (#895, 10 files), `git show 0c4835d2` (#647 wire entity
   only: `Session.running_subagents` + engine publication).
2. Port `session_info.rs` and slim `actions_ui.rs` accordingly; adapt
   engine-local surfaces (project/device rows, side-chats list) — exclude
   any cloud/account hunks by intent.
3. Port the titlebar toggles + icons; remove the retired icon.
4. Port the tests that moved with the card.

**Excluded upstream hunks:** account/usage or cloud surfaces inside the
card; anything referencing sync rooms.

**Blocked by:** 06 (the titlebar option slot this builds on lands there;
sequence after it to avoid double-conflicting `shell.rs`/`tabs.rs`).

**Status:** ready-for-agent

**Verification budget:** `cargo check -p roboco-ui -p roboco-engine` +
shell tests; **wiregen gate must run** for the `running_subagents`
wire addition. Source: `.scratch/upstream-drift/2026-10-11.md` and
`.scratch/upstream-drift/2026-10-22.md` § sidebar series.

- [ ] `session_info.rs` ported as the superset (incl. #867 + #895);
      `actions_ui.rs` slimmed by the same delta
- [ ] Explorer panels absorbed (`files/sections.rs` deleted); ticket
      12's `Session.running_subagents` wire entity folded in
- [ ] Titlebar session-card + folder-tree explorer toggles with icons
- [ ] Tests ported (incl. `panel_interaction_tests.rs`); no cloud
      surfaces restored
- [ ] Commit records upstream SHAs `ac4acbc2` + `6774a37a` +
      `c8c717862` + `591824881` + the `0c4835d2` wire slice
