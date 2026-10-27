# 01 — Main-seeded Rust caches, Windows on main, no cancelled main runs

**What to build:** Make the Actions cache fit under 10 GB and stay warm. Only `main` writes caches, and every PR restores them. Carries upstream `710b06ff8` and zeronsh/zeron#864 by hand.

- Every `Swatinem/rust-cache@v2` step:
  - `save-if: ${{ github.ref == 'refs/heads/main' }}` and `cache-on-failure: true`.
  - A `shared-key` per dependency graph with `add-job-id-key: false`. Exactly one saving job per key. Interim keys until 04/06 restructure the jobs:
    - `windows-tests`
    - `linux-engine` (engine-local-regressions)
    - `linux-mobile` (mobile-core)
    - `linux-ui` (ui-tests)
    - `android` (already shared by the three android.yml jobs)
    - `preview`
    - `theme`
  - `cache-workspace-crates: false` on Windows, which keeps the entry smaller; workspace crates rebuild on every change anyway.
- `windows.yml`: add `push: branches: [main]` with the same `paths` as `pull_request`. This also fixes the drift-report gap and makes AGENTS.md's "PR + push" true.
- Every workflow: `cancel-in-progress: ${{ github.event_name == 'pull_request' }}`.
- `release.yml`: remove all three `rust-cache` steps. Tag-scoped entries are readable only by that tag: 19/23 linux and 15/19 windows release runs restored nothing. Set `cache: false` on its `setup-go` steps. Add `timeout-minutes` to the linux matrix (it has none; one apt stall took 24 min).
- `preview-tests.yml`: replace `dtolnay/rust-toolchain@stable` with `rustup show active-toolchain || rustup toolchain install`, as the other workflows do. A different toolchain never shares a key (28/30 misses). Ticket 06 later folds this workflow into `core-tests`.
- Job-level `CARGO_*` env must be identical across jobs that share a key, because rust-cache hashes `CARGO_*` into the key.
- Optional and measured: if `cache/usage` after a week is still > 9 GB, drop `setup-go` caching on main too (the Go builds take ≈ 1 min).

**Blocked by:** none

**Status:** ready-for-agent

**Parent:** `.scratch/ci-speed/spec.md`

Baseline (research §2.5, 2026-10-28): 10.90 GB in 14–16 entries. Rust-job restore misses: Windows 8/28, engine-local 22/33, mobile-core 14/33, android ubuntu 16/30, android windows 19/28, emulator 23/30, preview 28/30. Miss penalty: +9 to +13 min per job.

- [ ] No `refs/pull/*` or `refs/tags/*` Rust cache entries are created by runs after the merge (`gh api repos/hoangvu12/roboco/actions/caches`)
- [ ] `cache/usage` < 9.5 GB one week after merge
- [ ] First push to main after merge saves each shared key once; the next PR restores it (log shows "Restored from cache key …" / restore step > 0.15 min)
- [ ] `windows.yml` runs on a push to main; a main run is not cancelled by a following merge
- [ ] Measured before/after with `cache.py` over ≥ 10 runs per workflow: miss rate per job (target ≤ 10 % excluding `Cargo.lock` bumps) and p50 job minutes
- [ ] AGENTS.md CI section still accurate for `windows.yml` (PR + push)

## Comments

**2026-10-28 (web research round 2, `research-web-2026-10-28.md` §1):**

- **The budget is incomplete.** `cache/usage` is now 15.91 GB in 38 entries: Rust 9.2, **AVD snapshot 2.75** (the largest single entry), Gradle ≈ 2.8 from 4 writers, Go 1.0. With main-only Rust saves the total is still ≈ 14.5 GB, so LRU keeps evicting main's own keys. Ticket **13** removes the AVD cache, cuts Gradle to one writer and asks the user about a paid limit (≈ $0.07/GB-month; 20 GB ≈ $0.70/month). The "< 9.5 GB" acceptance check only holds with 13.
- **Keys also move with the compiler.** `channel = "stable"` follows each runner image. In one run (2026-10-27 20:43) `ui-tests` built with 1.99.0 and `android-emulator` with 1.99.1, and rust-cache hashes the version into the key. Ticket **12** pins it; land it with or right after this ticket so main seeds once.
- **`cache-workspace-crates: true` buys nothing anywhere.** Cargo's mtime freshness rebuilds every workspace crate after checkout, which is why rust-cache's default is off. Set it to `false` on the android.yml jobs too (`android`, `android-emulator`, `-tablet`, `-benchmark-apk`), not only on Windows. The `windows.yml` comment about "near-instant" reruns is wrong.
- `preview-tests.yml` runs on `ubuntu-latest`, which moves to Ubuntu 26.04 in November 2026 (runner-images announcement). One more reason to replace it with `ubuntu-24.04` or fold it into 06 soon.
