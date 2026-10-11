# 08 — Panels: content columns share the width, continuous transitions (upstream #677)

**What to build:** Port `dace1a8c` (#677): content columns (files /
transcript / queue panes) share the available width instead of each
clamping independently, with continuous drag transitions. Touches
`panel_layout.rs`, `motion.rs`, `composer_dock*`, `side_chats.rs`,
`spaces.rs`, `tabs.rs`, `shell.rs` (12 files, +1844/−346).

**Why now:** part of the same UI-parity run as 06/07; roboco holds all
the files from earlier panel ports. Sequenced after the sidebar series
to avoid double-conflicting the same `shell.rs`/`tabs.rs` hunks.

**Steps:**
1. `git show dace1a8c` — port the shared-width layout model into
   `panel_layout.rs` and the continuous transitions into `motion.rs`.
2. Carry the per-surface adjustments (`composer_dock`, `side_chats`,
   `spaces`, `tabs`, `files_panel`) by intent.
3. Port the panel interaction tests
   (`shell/panel_interaction_tests.rs`, `panel_layout.rs` tests).

**Excluded upstream hunks:** none known; adapt identifiers per AGENTS.md.

**Blocked by:** 07 (same-file sequencing).

**Status:** ready-for-agent

**Verification budget:** `cargo check -p roboco-ui` + panel tests.
Source: `.scratch/upstream-drift/2026-10-11.md` § `dace1a8c`.

- [ ] Shared-width columns + continuous transitions ported
- [ ] Per-surface adjustments carried by intent
- [ ] Panel tests ported and passing
- [ ] Commit records upstream SHA `dace1a8c` (#677)
