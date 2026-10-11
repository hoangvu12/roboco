# 17 — Verify #528 (Windows terminal cwd) and #535 (update check) apply to Roboco, port if they do

**Type:** research + task.

**What to build:** Determine whether roboco main carries the two bugs
upstream fixed in parallel with our open zeron PRs, and port the fixes
if so:

- `88a07863` (#528): Windows terminals started in `C:\Windows` instead
  of the project checkout — `crates/engine/src/terminals/windows.rs`
  +86/−1. Our fork holds the open zeron PR
  `fix/windows-terminal-cwd-verbatim` (`099f1274`, "Harden Windows
  terminal cwd normalization") — **not merged into roboco main**.
- `94304283` (#535): app updates checked without workspace sync —
  `crates/engine/src/lib.rs`, `crates/update/src/lib.rs` (+247),
  `crates/ui/src/shell.rs`. Our fork holds the open zeron PR
  `fix/unconditional-update-check` (`e78ecdb1`) — **not merged into
  roboco main**.

**Why now:** the 2026-10-11 drift review found roboco main records
neither fix while holding both PR branches. #527/#529/#536 from the
same batch are already fixed locally (`bd20d842`, `web-bugs/16`
`3904a3d3`, `2bf73c60`); these two are the stragglers. Roboco is
Windows-first, so #528 especially matters.

**Steps:**
1. Trace roboco's terminal spawn cwd resolution
   (`crates/engine/src/terminals/…`, `open(&self, cwd, …)` at
   `terminals.rs:166`) on Windows: does a verbatim/oddly-cased checkout
   path resolve to `C:\Windows`? Compare against upstream `88a07863`'s
   normalization and our PR `099f1274`.
2. Trace roboco's update check path (`crates/update`, engine `lib.rs`,
   shell call-site): does it gate on workspace/sync state upstream
   removed in `94304283`?
3. Port whichever applies: prefer upstream's landed shape (`88a07863`,
   `94304283`) adapted to roboco, recording both our PR SHA and the
   upstream SHA; if neither applies, record the evidence here and close.

**Excluded upstream hunks:** whatever the trace shows inapplicable —
record it.

**Blocked by:** None.

**Status:** ready-for-agent

**Verification budget:** `cargo check -p roboco-engine -p roboco-update`
+ terminal/update tests. Source:
`.scratch/upstream-drift/2026-10-11.md` § E.

- [ ] #528 trace recorded (applies / not, with file:line evidence)
- [ ] #535 trace recorded (applies / not, with file:line evidence)
- [ ] Fixes ported where they apply; commit records upstream + PR SHAs
