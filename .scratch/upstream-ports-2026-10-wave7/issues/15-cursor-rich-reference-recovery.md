# 15 — Cursor rich-reference recovery and portable rich invocation delivery (upstream da2adfe8 + 8f34f3c9 + 216558ed)

**What to build:** Port the Cursor rich-reference recovery line:
`da2adfe8` (fix rich-reference recovery for Cursor —
`crates/engine/src/sessions.rs` +48/−3), `8f34f3c9` (validate portable
rich invocation delivery — engine + `crates/proto/src`), and `216558ed`
(their tests — `crates/engine/tests`). Diff-check whether the
#748-cursor substrate port (perf ticket 08, `4bd1cea9`/`b1ecfc40`)
already carries any of this; port the remainder by intent.

**Why now:** three older genuinely-missed commits (2026-09-19) in the
engine/proto surfaces roboco actively holds; the cursor adapter is a
roboco-first-class surface.

**Steps:**
1. `git show da2adfe8 8f34f3c9 216558ed`.
2. Check overlap with the landed #748 cursor slices; port the delta.
3. Adapt proto deltas; run the wiregen gate if wire types moved.

**Excluded upstream hunks:** `b3ac8b63` (screenshots/review-notes
refresh — review-only, skip).

**Blocked by:** None.

**Status:** ready-for-agent

**Verification budget:** `cargo check -p roboco-engine -p roboco-proto`
+ the ported engine tests. Source:
`.scratch/upstream-drift/2026-10-11.md` § C.

- [ ] Rich-reference recovery fix ported (overlap-checked vs #748 slices)
- [ ] Portable rich invocation validation ported
- [ ] Tests ported; wiregen green
- [ ] Commit records upstream SHAs `da2adfe8` + `8f34f3c9` + `216558ed`
