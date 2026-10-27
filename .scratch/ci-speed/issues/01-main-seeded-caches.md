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

**Status:** implemented on branch `ci/caches-windows` (2026-10-28); the acceptance items below are measured after merge

**Parent:** `.scratch/ci-speed/spec.md`

Baseline (research §2.5, 2026-10-28): 10.90 GB in 14–16 entries. Rust-job restore misses: Windows 8/28, engine-local 22/33, mobile-core 14/33, android ubuntu 16/30, android windows 19/28, emulator 23/30, preview 28/30. Miss penalty: +9 to +13 min per job. Second snapshot just before the change (`gh api …/cache/usage`, 2026-10-28): 15.91 GB / 38 entries; Rust entries `v0-rust-android-android-Linux` 2.07, `…-android-emulator-Linux` 1.66 (the `key: android` jobs did **not** share an entry: without `shared-key` rust-cache appends the job id), `…-android-Windows_NT` 1.02, `…-ui-tests-Linux` 1.12, plus 3 tag entries (3.34 GB) and a tag-scoped Go entry (0.25).

- [ ] No `refs/pull/*` or `refs/tags/*` Rust cache entries are created by runs after the merge (`gh api repos/hoangvu12/roboco/actions/caches`) — measure after merge
- [ ] `cache/usage` < 9.5 GB one week after merge — measure after merge (see the budget estimate under Comments: it needs 13 and the web removal)
- [ ] First push to main after merge saves each shared key once; the next PR restores it (log shows "Restored from cache key …" / restore step > 0.15 min) — measure after merge
- [ ] `windows.yml` runs on a push to main; a main run is not cancelled by a following merge — measure after merge (config is in place: `push: main` + per-run concurrency group for non-PR events)
- [ ] Measured before/after with `cache.py` over ≥ 10 runs per workflow: miss rate per job (target ≤ 10 % excluding `Cargo.lock` bumps) and p50 job minutes — measure after merge
- [x] AGENTS.md CI section still accurate for `windows.yml` (PR + push): it already said "PR + push"; that is now true

## Comments

**2026-10-28 (web research round 2, `research-web-2026-10-28.md` §1):**

- **The budget is incomplete.** `cache/usage` is now 15.91 GB in 38 entries: Rust 9.2, **AVD snapshot 2.75** (the largest single entry), Gradle ≈ 2.8 from 4 writers, Go 1.0. With main-only Rust saves the total is still ≈ 14.5 GB, so LRU keeps evicting main's own keys. Ticket **13** removes the AVD cache, cuts Gradle to one writer and asks the user about a paid limit (≈ $0.07/GB-month; 20 GB ≈ $0.70/month). The "< 9.5 GB" acceptance check only holds with 13.
- **Keys also move with the compiler.** `channel = "stable"` follows each runner image. In one run (2026-10-27 20:43) `ui-tests` built with 1.99.0 and `android-emulator` with 1.99.1, and rust-cache hashes the version into the key. Ticket **12** pins it; land it with or right after this ticket so main seeds once.
- **`cache-workspace-crates: true` buys nothing anywhere.** Cargo's mtime freshness rebuilds every workspace crate after checkout, which is why rust-cache's default is off. Set it to `false` on the android.yml jobs too (`android`, `android-emulator`, `-tablet`, `-benchmark-apk`), not only on Windows. The `windows.yml` comment about "near-instant" reruns is wrong.
- `preview-tests.yml` runs on `ubuntu-latest`, which moves to Ubuntu 26.04 in November 2026 (runner-images announcement). One more reason to replace it with `ubuntu-24.04` or fold it into 06 soon.

**2026-10-28 (implementation, branch `ci/caches-windows`):**

What changed:

- Every `rust-cache` step: `shared-key`, `add-job-id-key: false`, `save-if: main`, `cache-on-failure: true`; `cache-workspace-crates: false` where it was `true` (`windows.yml`, all four android.yml jobs). Keys and their single writer:

  | shared-key | OS | writer (main only) | restore-only |
  |---|---|---|---|
  | `windows-tests` | Windows | windows `tests` | — |
  | `linux-engine` | Linux | ui `engine-local-regressions` | — |
  | `linux-mobile` | Linux | ui `mobile-core` | — |
  | `linux-ui` | Linux | ui `ui-tests` | — |
  | `android` | Linux | `android-emulator` | `android (ubuntu)`, `android-emulator-tablet` |
  | `android` | Windows | `android (windows-2022)` | — |
  | `android-benchmark` | Linux | `android-benchmark-apk` (dispatch on main only) | — |
  | `preview` | Linux | preview `networking` | — |
  | `theme` | Linux | theme `export-check` | — |
  | `web-codegen` | Linux | `freshness` | — |
  | `web-engine` | Linux | `engine-client` | `web-smoke` |

  The OS is part of every rust-cache key, so `android` is two entries with one writer each. **Linux `android` writer = the emulator job, not `android (ubuntu)`:** the emulator builds the fixture engine (host, dev), the bindgen and the x86_64 core; `android (ubuntu)` adds only the arm64 NDK core (≈ 1–4 min, research-web §4.3) and, on main, the mobile-dist R8 smoke, which PRs never use. The reverse would cost the emulator (the Android critical path) the ≈ 5–6 min fixture-engine build on every PR. `web-smoke` shares `engine-client`'s entry (both are dev builds of `roboco-engine` examples) to keep the doomed web caches to one entry.
- `windows.yml`: `push: branches: [main]` with the PR's `paths`.
- Concurrency in every workflow (release.yml has none): `cancel-in-progress: ${{ github.event_name == 'pull_request' }}` **and** `group: <name>-${{ github.event_name == 'pull_request' && github.ref || github.run_id }}`. Deviation from the literal spec line: with a per-ref group and `cancel-in-progress: false`, GitHub still cancels a *pending* run when a newer one queues behind it ("any existing pending job … will be canceled"), so with three quick merges the middle main run would be cancelled. A per-run group for non-PR events means no main run is ever cancelled (user story 4); concurrent main runs that compute the same key just skip the second save.
- `release.yml`: the three `rust-cache` steps are gone; `setup-go` has `cache: false` (×3); the linux matrix has `timeout-minutes: 60`; the arm64 Windows timeout comment no longer mentions rust-cache. cargo-ndk in the release android job is now compiled from source each tag (≈ 0.5 min; it lived in rust-cache's `~/.cargo/bin`).
- `preview-tests.yml`: `dtolnay/rust-toolchain@stable` → `rustup show active-toolchain || rustup toolchain install`; `ubuntu-latest` → `ubuntu-24.04`; `rust-toolchain.toml` added to its `paths` (the toolchain is now part of its key).
- Lint: `actionlint` 1.7.12 is clean on every workflow except four pre-existing `release.yml` findings (`if-no-files-found` is not a `download-artifact@v4` input; untouched here). All files parse with PyYAML.

Budget estimate for the Rust entries after the first main seed (sizes from the snapshots above, workspace crates no longer cached): windows-tests ≈ 1.5–2.0, android Linux ≈ 1.6, android Windows ≈ 1.0, linux-mobile 1.4, linux-ui 1.1, linux-engine 0.9, preview 0.5, theme 0.3, web-codegen 0.65, web-engine 0.66 → **≈ 9.6–10.1 GB Rust**, + Gradle ≈ 1.0 (after 13) + Go 0.46 ≈ 11–11.5 GB. That is still over 10 GB until the web removal (−1.3 GB) and ticket 06 (linux-engine + preview → linux-core); LRU then evicts the least-used entries (theme/preview/web, which run on few paths), not the hot ones. If the one-week check is over 9.5 GB, the first levers are `save-if: false` on the web workflows and dropping Go caching.
