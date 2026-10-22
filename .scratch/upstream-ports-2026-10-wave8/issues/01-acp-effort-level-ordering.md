# 01 — ACP: order advertised effort levels lowest to highest (upstream #953, `911ec56e4`)

**What to build:** Port `911ec56e4` (#953): the harness ACP adapter
advertises its effort levels ordered from lowest to highest instead of
the ad-hoc order it had.

**Why now:** one file (`crates/harness/src/acp/mod.rs`, +65 including
tests), no wire/UI/pin involvement, roboco holds the adapter from the
#748 perf port. Smallest candidate of the window.

**Steps:**
1. `git show 911ec56e4` — single file.
2. Port the ordering logic and its tests into roboco's
   `crates/harness/src/acp/mod.rs`.

**Excluded upstream hunks:** none — single file, wholly ours.

**Blocked by:** None.

**Status:** ready-for-agent

**Verification budget:** `cargo check -p roboco-harness` + the ACP
tests. Source: `.scratch/upstream-drift/2026-10-22.md` § quick ports.

- [ ] Effort-level ordering + tests ported
- [ ] Commit records upstream SHA `911ec56e4` (#953)

## Comments
