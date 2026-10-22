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

**Status:** done (ported in 106f36a38)

**Verification budget:** `cargo check -p roboco-ui` + any overscroll
tests upstream ships; a manual smoke of the three regions if a headed
run is convenient. Source: `.scratch/upstream-drift/2026-10-22.md` §
medium tickets.

- [x] `overscroll.rs` ported + three consumers wired
- [x] Commit records upstream SHA `2d40f8df6` (#821)

## Comments

- Ported in commit 106f36a38. `overscroll.rs` ported verbatim from
  upstream (0 zeron identifiers, 721 lines, all 10 tests including the
  div-stretch fixture). Consumers wired in upstream's order:
  transcript (List source), sessions sidebar (Handle source), file
  tree (List source); `pub mod overscroll` exported from lib.rs.
- Motion gate verified: the wrapper's prepaint already consults
  roboco's `crate::motion::reduced_motion` (the OS-following global,
  upstream #642 parity) and resets the band when set, so no extra
  wiring was needed; it is also off while a drag is active.
- Verified: cargo check -p roboco-ui clean; overscroll 10/10,
  files::tree 5/5, transcript 135/135, shell 216/216. No manual headed
  smoke available in this environment (Linux); the unit suite covers
  the band mechanics and ENABLED is macOS/test-only as upstream.
