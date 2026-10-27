# 65 — Sidebar and session-card polish: "● N" running pill, card snaps on switch, branch-over-base totals, icon-button tooltips (#647, #1028, #950, #1048)

**Lane:** 4b Features: desktop UI. **Effort:** M (four S/S–M pieces).

**What to build:**
1. **#647: the "● N" running-subagents pill on sidebar chat rows.** The
   wire (`Session.running_subagents`) and the titlebar badge exist
   (`189c3d5a3`). Add `running_pill()` on rows (upstream `shell.rs:8776`;
   Roboco's `running_pill.rs` has only `mark_titlebar_button`).
2. **#1028: the session card snaps on a chat switch** instead of fading
   over the next chat. Add the `session_info_snap` field, set it in the
   chat-select path, change `session_info_frame`, and add a test
   (`shell/session_info.rs:290-306`).
3. **#950: the branch row counts the branch over its base,** not the
   working tree, and a click opens that diff. Rework `SessionDiffs`
   (`shell/session_info.rs`) and add `Changes::for_branch` and
   `Shell::add_branch_diff_surface`. Deps exist (`changes.rs:937`
   `default_base_ref`, `DiffScope::Branch`). Route `LIST_BRANCHES` and the
   diff through the chat's `EngineTarget` (fleet).
4. **#1048: tooltips and accessible names on icon-only buttons:**
   comments "+" (`comment_ui.rs:34`), model-picker tabs/star/"Back · Esc"
   (`pickers/compact.rs`), settings swatches/close/zoom, the actions icon
   picker tiles (`actions_ui.rs` ~580-603), the voice-error dismiss, the
   crumb, Linux caption buttons (+ Windows captions after ticket 04), and
   the sidebar section dialog Close.

**Upstream:** `0c4835d2b` (#647, row pill), `4443e6b58` (#1028),
`bc892abd0` (#950), `daf047b7f` (#1048).

**Roboco files:** `crates/ui/src/shell/{running_pill,spaces,session_info,actions_ui,sidebar_sections}.rs`,
`crates/ui/src/{shell,changes,comment_ui}.rs`, `crates/ui/src/pickers/compact.rs`,
`crates/ui/src/settings/*`.

**Excluded:** #1048's PR/billing/cloud buttons; #647's dead files-panel
Finished dropdown (deleted upstream by #895).

**Proto change:** no.

**Blocked by:** 01. Better after 64 (same sidebar rows).

**Status:** ready-for-agent

**Source:** `2026-10-28.md` §B; `part-06.md` `0c4835d2b`; `part-07.md`.

- [ ] Rows with running subagents show "● N" (UI test)
- [ ] The card snaps on a switch (ported test)
- [ ] Branch totals = branch vs base; a click opens the Branch diff, remote engines included (test)
- [ ] Every listed icon button has a tooltip + aria label (UI test sweep)
- [ ] Commit records the four SHAs

## Comments
