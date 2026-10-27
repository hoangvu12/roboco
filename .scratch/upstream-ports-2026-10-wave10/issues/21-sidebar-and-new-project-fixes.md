# 21 — Sidebar and New project: `+` on git and "No project" groups, scratch paths, current device, folder dialog, Archived paging (A1, 81adbdabf, A19, #690, #663, A44)

**Lane:** 1d Fixes: sidebar + chat. **Effort:** M (six S pieces).

**What to build:**
1. **A1: the per-project `+` never shows on git projects.**
   By-project group keys are `repo:<repositoryId>`, but
   `crates/ui/src/shell/spaces.rs:5555-5557` looks them up with
   `state.space_row(&key)` as a space id. Port upstream's lookup: find
   the space whose `project_key == key` and open on
   `project_members(space).first()` (this device's checkout first). Add a
   regression test. Non-git folders keep working. This is a regression
   from the wave-5 #799 port against #737.
2. **`81adbdabf`: `+` on "No project" groups.** Add
   `NewSessionTarget::{Project, NoProject(device)}` in `shell/tabs.rs`
   and a header `+` for `home:<device>` groups (`spaces.rs:2538`), shown
   only for devices the composer's device picker offers. With the fleet,
   there's one such group per engine.
3. **A19: projectless chats show raw `~/.roboco/scratch/<date>-<id>`.**
   #876's UI slice: projectless labels (`spaces.rs:2538,4999`,
   `command_palette.rs:300`, `project_icon.rs:295,373`), the files header
   (`files/mod.rs:403`), and the docked composer offset
   (`shell.rs:10041,10202` `.left(-session_reserve/2)` → `shift_x`).
   MCP `create_chat` names the scratch cwd in its reply. Hold
   `workspace_search.rs` hunks for ticket 69.
4. **#690: New project opens on the device list,** not the device in
   context (`spaces.rs:6045` always starts at `ProjectStep::Devices`).
5. **#663: no native folder dialog in New project.** Add a "Browse in
   File Explorer…" (Windows) / "Browse…" row for the local device only,
   using `prompt_for_paths` (already used in composer/appearance).
6. **A44 (#449 slice): Settings → Archived draws every row at once.**
   Page 40 at a time with Previous/Next ("Previous archived sessions" /
   "Next archived sessions"). Clamp the page after an unarchive or
   delete. Port the test (upstream `settings/archived.rs:23-27,475,638`).

**Upstream:** `dbb639be3` (#799) `+` slice, `81adbdabf`, `1bc74d5da`
(#876) UI slice, `b782d0437` (#449) archive pager; open PRs #690, #663.

**Roboco files:** `crates/ui/src/shell/{spaces,tabs,project_icon,command_palette}.rs`,
`crates/ui/src/shell.rs`, `crates/ui/src/files/mod.rs`,
`crates/ui/src/settings/archived.rs`, `crates/roboco-mcp/src/tools.rs`.

**Excluded:** the rest of #799's deferred half (tickets 73/74); #449's
compact Accounts redesign (Roboco keeps its grouped page by choice).

**Proto change:** no.

**Blocked by:** 01. Ticket 05 also edits `spaces.rs` (paths). Land 05
first if both are open.

**Status:** ready-for-agent

**Source:** `2026-10-28.md` A1, A19, A44, §B (`81adbdabf`), §A smaller
(Pickers); `part-05.md` `dbb639be3`; `part-06.md` `1bc74d5da`;
`pass2-features-desktop.md` N7.

- [ ] `+` appears on git project groups and opens on this device's checkout (regression test)
- [ ] `+` on a "No project" group starts a projectless chat on that device (test)
- [ ] No scratch path appears in the sidebar, palette, files header or composer (tests or fixture screenshot)
- [ ] New project opens on the device in context; a Browse row opens the OS dialog (local device)
- [ ] Archived pages 40 rows at a time, and the page clamps after unarchive/delete (ported test)
- [ ] Commit records `dbb639be3` (slice), `81adbdabf`, `1bc74d5da` (UI slice), `b782d0437` (pager), PRs #690/#663

## Comments
