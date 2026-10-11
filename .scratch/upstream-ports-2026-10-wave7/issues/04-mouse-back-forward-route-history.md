# 04 — Shell: mouse back/forward buttons walk the route history (upstream #872)

**What to build:** Port `488c0358` (#872): auxiliary mouse buttons 8/4
(back/forward) walk the shell's route history in `crates/ui/src/shell.rs`,
and the browser view forwards its own navigation history into the same
walk (`crates/ui/src/browser/view.rs`).

**Why now:** 2 files, +16/−2 — the smallest UI candidate of the window,
and a Windows-first product ships mice with these buttons everywhere.
Roboco holds both surfaces.

**Steps:**
1. `git show 488c0358` — two files.
2. Port the button handling into roboco's `shell.rs` route history and
   the browser-view forwarding.

**Excluded upstream hunks:** none.

**Blocked by:** None.

**Status:** ready-for-agent

**Verification budget:** `cargo check -p roboco-ui`. Source:
`.scratch/upstream-drift/2026-10-11.md` § `488c0358`.

- [ ] Back/forward buttons walk route history; browser view forwards
- [ ] Commit records upstream SHA `488c0358` (#872)
