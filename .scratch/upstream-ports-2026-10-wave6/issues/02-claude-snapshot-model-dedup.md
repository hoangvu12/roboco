# 02 — Fold dated Claude snapshot IDs into curated model rows (upstream #835)

**What to build:** Port `970f41fa` — the Claude CLI lists some models by
dated snapshot (`claude-haiku-4-5-20251001`) while the curated catalog
uses the undated id (`claude-haiku-4-5`), so the discovered-models
overlay adds a duplicate row to the model picker. A `-YYYYMMDD` snapshot
of a curated ID folds into that curated row; snapshots with no curated
base still surface as-is.

**Why now (verified 2026-10-07):** our `with_discovered_models`
(`crates/harness/src/claude/catalog.rs:224`) dedups only by exact id
(`catalog.rs:260`), so the same duplication happens in Roboco the
moment the CLI reports dated snapshot IDs. The upstream fix is 24 lines
including a regression test — near-verbatim, single file, zero
cross-surface fallout.

**Steps:**
1. `git show 970f41fa` — one file: `crates/harness/src/claude/catalog.rs`
   (+24).
2. Port the fold into the discovery loop: after the alias/default
   handling and before the exact-id dedup, map `id` through
   `snapshot_base(id).filter(|base| models.iter().any(|m| m.id ==
   *base)).unwrap_or(id)`.
3. Port `snapshot_base`: split on the last `-`; the tail must be 8
   ASCII digits; return the head ("claude-haiku-4-5-20251001" →
   "claude-haiku-4-5").
4. Port the regression test `dated_snapshots_fold_into_curated_rows`
   beside the existing catalog tests (Haiku 4.5 folds; a snapshot with
   no curated base — "claude-haiku-9-9-20990101" — still surfaces).

**Excluded upstream hunks:** none — single file, wholly ours.

**Blocked by:** None.

**Status:** ready-for-agent

**Verification budget:** deferred to the end-of-pass batched pass:
`cargo nextest run -p roboco-harness --lib` (catalog suite). Source:
`.scratch/upstream-drift/2026-10-07.md` § `970f41fa` (#835).

- [ ] Fold + `snapshot_base` ported into `catalog.rs`
- [ ] Regression test ported and passing
- [ ] Commit records upstream SHA `970f41fa` (#835)
