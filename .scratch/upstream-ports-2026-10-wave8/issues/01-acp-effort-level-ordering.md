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

**Status:** done (ported in 32ecde8fb)

**Verification budget:** `cargo check -p roboco-harness` + the ACP
tests. Source: `.scratch/upstream-drift/2026-10-22.md` § quick ports.

- [x] Effort-level ordering + tests ported
- [x] Commit records upstream SHA `911ec56e4` (#953)

## Comments

- 2026-10-24: Ported in 32ecde8fb. `models_from_session` gained
  `ladder.sort_unstable(); ladder.dedup();` (renamed binding to `mut`),
  plus the `grok_thought_levels_order_from_lowest_to_highest` test
  carried verbatim — roboco's `grok_spec` matches upstream's at the
  port point (single grok-4.5 catalog row), so the catalog-match vs
  wire-ladder assertions hold unchanged. `cargo check -p roboco-harness`
  clean; the new test + neighbours pass. Pre-existing, unrelated:
  `acp::tests::antigravity_named_home_settings_preserve_business_auth`
  fails on pristine main in this sandbox (no user-database home for
  GEMINI_HOME resolution) — environment, not the port. No exclusions.
