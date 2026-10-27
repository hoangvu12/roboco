# 09 — Document the tiers, decide required checks, final measurement

**What to build:**

- `docs/reference/ci.md` (new):
  - the two tiers and what triggers each
  - the `ci:full` label
  - the cache layout: keys, the single saving job per key, main-only saves, the 10 GB budget
  - the nightly jobs
  - how to measure, using the research scripts. Move `analyze.py`/`cache.py`/`waste.py` to `scripts/ci/` only if the user wants them tracked; otherwise reference `.scratch/ci-speed/`.
- AGENTS.md CI section: update every workflow bullet for the new layout. `preview-tests.yml` is gone; `windows.yml` has `tests` + `app` on PR + push; `ui-tests.yml` has `core-tests`/`ui-tests`/`mobile-core`/`tailcat-adapter` + nightly backstop; `android.yml` tiers. Merge with whatever the web-removal PR already changed there.
- **Required checks** (spec open question 1): record the user's decision. If they want required checks on `main`, add an aggregate `CI result` job per workflow (or one aggregator), so a path-skipped workflow does not block merging.
- Final measurement: rerun the research data pull over the last 30 runs per workflow, and add a "Before / After" table to `research-2026-10-28.md` (or a dated follow-up file): PR wall p50/p90, runner-min per push, cache miss rate, `cache/usage`.

**Blocked by:** 04, 05, 06, 07, 08

**Status:** ready-for-human (needs the required-checks decision)

**Parent:** `.scratch/ci-speed/spec.md`

- [ ] `docs/reference/ci.md` exists and matches the workflows
- [ ] AGENTS.md CI section matches the workflows
- [ ] Required-checks decision recorded (here, under Comments) and implemented
- [ ] Before/after table: PR wall p50 ≤ 25 min warm (from 76), runner-min per crates/** push ≤ 120 (from ≈ 245), Rust cache miss rate ≤ 10 % (from ≈ 60 %), `cache/usage` < 9.5 GB

## Comments
