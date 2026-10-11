# 14 — Restore device names in the sidebar project selector (upstream #400)

**What to build:** Port `7904e8a7` (#400): device names show again in
the sidebar project selector. Small UI fix (`crates/ui/src` + an example
and screenshots).

**Why now:** an older genuinely-missed fix (2026-09-15); roboco's
sidebar has device rows in the project selector (device tags ride the
repository-identity substrate ported in wave-5/6), so the regression
surface is shared.

**Steps:**
1. `git show 7904e8a7` — port the name-resolution fix into the project
   selector rows.
2. Adapt to engine-local device identity (no cloud device catalog) — if
   upstream's fix resolves names through a cloud surface, carry the
   intent (stable, human-readable names) through roboco's local engine
   registry instead and record the adaptation.

**Excluded upstream hunks:** screenshots and example churn (take the
code only); any cloud device-catalog resolution.

**Blocked by:** None.

**Status:** ready-for-agent

**Verification budget:** `cargo check -p roboco-ui` + selector tests if
any. Source: `.scratch/upstream-drift/2026-10-11.md` § C.

- [ ] Device names restored in the project selector (engine-local)
- [ ] Commit records upstream SHA `7904e8a7` (#400) + adaptation notes
