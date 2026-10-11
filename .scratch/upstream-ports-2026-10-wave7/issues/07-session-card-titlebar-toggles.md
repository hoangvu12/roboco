# 07 — Session card: project, device, side chats and actions behind one toggle (upstream ac4acbc2 + 6774a37a)

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
   (4 files, +45/−7).
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

**Verification budget:** `cargo check -p roboco-ui` + shell tests
(`cargo nextest run -p roboco-ui --lib`). Source:
`.scratch/upstream-drift/2026-10-11.md` § sidebar series.

- [ ] `session_info.rs` ported; `actions_ui.rs` slimmed by the same delta
- [ ] Titlebar session-card + folder-tree explorer toggles with icons
- [ ] Tests ported; no cloud surfaces restored
- [ ] Commit records upstream SHAs `ac4acbc2` + `6774a37a`
