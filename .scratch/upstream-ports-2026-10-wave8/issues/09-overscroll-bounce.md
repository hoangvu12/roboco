# 09 — Overscroll bounce for transcript, sidebar and file tree (upstream #821, `2d40f8df6`)

**What to build:** Port `2d40f8df6` (#821): a bounce effect at the ends
of the transcript, sidebar and file-tree scroll regions — new
`crates/ui/src/overscroll.rs` (+721) with the scroll-listener/offset
machinery, wired into `shell.rs` (sidebar), `transcript.rs` and
`files/tree.rs`, exported from `lib.rs`.

**Why now:** roboco holds all three surfaces; no `Cargo.toml` change
upstream, so no pin or dependency involvement. Pure UI polish with
visible payoff.

**Steps:**
1. `git show 2d40f8df6` — overscroll.rs, lib.rs +1, files/tree.rs +12,
   shell.rs +6, transcript.rs +10.
2. Port `overscroll.rs` verbatim (no zeron identifiers), then wire the
   three consumers in the same order upstream did.
3. Check the effect interacts with roboco's motion settings (the
   animation should respect any reduced-motion gates roboco carries).

**Excluded upstream hunks:** none.

**Blocked by:** None.

**Status:** ready-for-agent

**Verification budget:** `cargo check -p roboco-ui` + any overscroll
tests upstream ships; a manual smoke of the three regions if a headed
run is convenient. Source: `.scratch/upstream-drift/2026-10-22.md` §
medium tickets.

- [ ] `overscroll.rs` ported + three consumers wired
- [ ] Commit records upstream SHA `2d40f8df6` (#821)

## Comments
