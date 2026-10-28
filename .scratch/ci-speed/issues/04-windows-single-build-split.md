# 04 — Windows: one nextest build on a CI profile, split into `tests` and `app` jobs

**What to build:** Restructure `windows.yml`. Carries the intent of upstream `f4abe69b8` and `c168ba5b4`, plus the "one nextest build" part of zeronsh/zeron#1077.

- **Composite action `.github/actions/windows-ci-setup`**:
  1. Turn off Defender real-time scanning (best effort).
  2. `rustup show active-toolchain || rustup toolchain install`.
  3. Locate fxc.exe.
  4. **CI release profile**: write `[profile.release] lto = false` plus `opt-level = 0` for every workspace member (from `cargo metadata --no-deps`) into `$CARGO_HOME/config.toml`. `Cargo.toml` stays untouched; `release.yml` keeps the shipped profile.
  5. `rust-cache` with the key from input, main-only save, `cache-workspace-crates: false`.
  6. Install nextest (pinned version).
- **`tests` job** (windows-2022, key `windows-tests`): **one** `cargo nextest run --release --locked -P ci` over the union of today's Windows suites:
  - harness `--lib` and the Windows suites `codex_availability`, `windows_native`, `opencode`, `cursor_shim`, `pi_rpc`, with `--features roboco-harness/native-fixture` for the whole build
  - `gpui_windows` layout, `roboco` + `roboco-update`
  - `roboco-proto` device_paths
  - `roboco-engine` lib + `worktree_on_run` + `codex_catalog` + `codex_login_resolution` + `engine_local`/`local_first`/`local_profiles`/`project_actions_rpc`
  - `roboco-rpc` lib, `roboco-preview` lib + `discovery`, `roboco-engine-client`, `roboco-markdown`
  - `roboco-ui` lib

  Filters keep today's selections; use nextest `-E` filtersets where a step ran a name filter. `roboco-ui` and `roboco-engine-client` go in a nextest **test group with `max-threads = 1`** (`.config/nextest.toml`), replacing `-j 1` and the separate native-font step. The native-font test stays mandatory and is reported by name. Optional: `nextest archive` is not needed while both jobs build different graphs.
- **`app` job** (windows-2022, key `windows-app`): Go adapter → `package-windows.ps1` (the single build, with the embed from 02) → `test-windows-installer.ps1` → `test-windows-startup.ps1` (03) → "CLI startup without HOME" → optional GUI fixture/upload for `native-gui` (now `needs: app`).
- `timeout-minutes`: 45 per job (from 120).
- Before/after coverage list: `cargo nextest list` output from the old steps vs the new invocation. Every test that ran before still runs.

**Blocked by:** 01, 02, 03

**Status:** implemented on branch `ci/windows-split` (2026-10-29, PR with 03 and 05); the p50 and cache-size items are measured after merge

**Parent:** `.scratch/ci-speed/spec.md`

Baseline: `tests` 75 min p50 warm (≈ 50 expected after 02). Upstream measured CI-release (workspace opt0) 23.7 min vs dev profile 31.5 min cold for the Windows test build, so do **not** switch Windows to the dev profile.

- [ ] One cargo build per job (log: a single `Finished` line for the test build in `tests`, one for `roboco` in `app`)
- [x] Coverage list attached: test count before (sum of old steps, deduped) ≤ after
- [x] `native_diff_font_geometry` still runs as its own mandatory test and is visible in the summary
- [ ] Measured over ≥ 5 green PR runs + ≥ 3 main runs: `tests` p50 (target ≤ 25 min warm), `app` p50 (target ≤ 15), workflow wall p50 (target ≤ 25)
- [ ] Cache entries `windows-tests` + `windows-app` together ≤ 3.5 GB

## Comments

**2026-10-28 (web research round 2, `research-web-2026-10-28.md` §2.4, §3.4, §5.3):**

- **Defender is already off on GitHub-hosted Windows images.** `actions/runner-images` `Configure-WindowsDefender.ps1` sets `DisableRealtimeMonitoring = $true` and `ExclusionPath = C:\, D:\`, and switches to passive mode. Drop setup step 1, or keep it as a documented no-op, and credit no savings to it.
- Alternative to writing `[profile.release]` overrides into `$CARGO_HOME/config.toml`: a named profile in `Cargo.toml`, as uv does (`[profile.fast-build]`, run with `cargo nextest run --cargo-profile …`). For Roboco that would be `[profile.ci] inherits = "release"` + `lto = false` + per-member `opt-level = 0`. It is tracked and reproducible locally, doesn't touch the rust-cache key (no `CARGO_*` env), and builds in `target/ci`, so it never fights the packaging build. Trade-off: the `app` job's packaging build would not see it. That's fine: `app` keeps building the real release profile (closer to what ships), at the cost of LTO-off savings there. Pick one approach and record why.
- Confirmed not needed: `nextest archive`/`--partition`. uv partitions its Windows tests three ways only because its run time is large. Revisit if `tests` shows > 8 min of test execution after this ticket.
- Ticket **15** (arm64 `tests`) and ticket **19** (fewer test binaries) build on this split.

**2026-10-29 (implementation, branch `ci/windows-split`):**

What changed:

- **`.github/actions/windows-ci-setup`** (composite, input `cache-key`): toolchain → fxc → CI release profile → `rust-cache` (`shared-key` from the input, `add-job-id-key: false`, main-only save, `cache-on-failure`, `cache-workspace-crates: false`). Two of the ticket's setup steps are left out:
  - The Defender step: it is a no-op on hosted images (comment above). The action says so in a comment.
  - The nextest install: only `tests` needs it, so it stays a `tests` step (`taiki-e/install-action`, `nextest@0.9.144` as before).
- **Profile: `$CARGO_HOME/config.toml`, not a named `[profile.ci]`.** The action appends `[profile.release] opt-level = 0` + `[profile.release.package."*"] opt-level = 3`. In a profile override `"*"` means every non-workspace package, so new workspace crates need no edit, and there is no `cargo metadata` loop as upstream has. The workflow's existing `CARGO_PROFILE_RELEASE_LTO=false` keeps LTO off. It is unchanged, so the rust-cache env hash is unchanged too. Why config over a named profile:
  - The `app` job's packaging build (`package-windows.ps1` → `cargo build --release -p roboco`) gets the cheap profile with no script change. Every PR rebuilds the whole workspace there, and that build is the app job's critical path. With a named profile, `app` would build workspace crates at opt-level 3.
  - Every script keeps using `target/release` (installer, startup, CLI smoke, native-gui upload).
  - `release.yml` never runs the action, so shipped binaries keep the committed profile.
  - The trade-off: the profile is not reproducible locally from `Cargo.toml`. The action's step prints the config it wrote.
- **`tests` job** (key `windows-tests`, 45 min): "Select the Windows test suites" writes one argument list to `WINDOWS_TESTS`. "Build tests" runs `cargo nextest run --release --locked -P ci --no-run <list>`, and "Windows tests" runs the same list with `--no-fail-fast -E <filter>`:
  - The list is 11 packages, `--features roboco-harness/native-fixture` for the whole build, `--lib --bin roboco`, and the named suites. Every test target of `roboco`, `roboco-update`, `roboco-markdown` and `roboco-engine-client` (the old "all targets" steps) is read from `cargo metadata`, so a new test file there is picked up as it was before.
  - The filter is `all()` minus the three suites that used to run by name: `package(roboco-proto) - test(device_paths)`, `binary_id(roboco-engine::worktree_on_run) - test(worktree_location_)`, and `(not package(roboco*)) - test(layout)`. The last one is gpui_windows: a dependency, which `package()` cannot name, the same trick as upstream's filter. `-p gpui_windows` resolves only when a selected workspace package depends on it (`roboco-ui` here). Checked locally: `-p roboco-proto -p gpui_windows` alone fails with "did not match any packages".
  - "UI native font geometry test ran" reads `target/nextest/ci/junit.xml` (new `[profile.ci.junit]`) and fails unless `native_diff_font_geometry` ran and passed. The JUnit report is uploaded as `windows-tests-junit` (14 days), which gives the per-test record for coverage comparisons.
  - The tests job no longer builds the Go adapter. Ticket 02 showed no Windows-run test depends on the embed (`tailcat::tests` and `tailcat_invite_cli` are unix-only). The engine under test is now the non-embedded build that Linux tests too, and `app` tests the embedded one.
- **`.config/nextest.toml`**: test groups `serial-ui` (`package(roboco-ui)`) and `serial-engine-client` (`package(roboco-engine-client)`), each `max-threads = 1`. They are set in `profile.default`, so local nextest runs are serial too. They are two groups, so the two serial suites overlap each other. They replace `-j 1` and the separate native-font step. Engine-client was not serial on Windows before (it ran in parallel with retries); the spec asks for the group, and its 38 tests are small.
- **`app` job** (key `windows-app`, 45 min): adapter → `package-windows.ps1` (the single `roboco` build, embed exported first) → installer → `test-windows-startup.ps1` (03) → CLI startup without HOME → optional rendering fixture + upload. `native-gui` now `needs: app`.
- **Separate cache keys, as the ticket says** (upstream shares one `windows` key, saved by `tests`). `cargo build -p roboco` resolves dependency features without the test build's dev-dependencies and `native-fixture`, so restoring the test graph would still rebuild every crate whose features differ, and everything above it. Cost: the first PR runs restore no `windows-app` entry until main seeds it.
- `paths` (PR and push) gain `.github/actions/windows-ci-setup/**` and `.config/nextest.toml`.
- `scripts/package-windows.ps1`: the 02 nit. `[IO.Path]::IsPathFullyQualified` does not exist in Windows PowerShell 5.1, so `-AdapterPath`/`ROBOCO_TAILCAT_EMBED` threw there. It is replaced by a lexical check (drive + separator, or UNC). Checked under 5.1.26100: the script parses with no errors. `C:\a\b.exe`, `D:/x.exe`, `\srv\share\a.exe` and `\?\C:\a.exe` are accepted; `a\b.exe`, `\a.exe` and `C:a.exe` are refused.
- Docs: `docs/reference/windows-development.md` "Verification" describes the two jobs and the CI profile. Its "Linux and macOS" is now "Linux" (Roboco has no macOS jobs). The AGENTS.md CI line names the two jobs.
- Lint: `actionlint` clean on `windows.yml`; the action parses. The selection step was run locally (PowerShell 5.1). `cargo test --release --no-run <the same list>` resolved every target and started compiling (stopped after 45 s).

**Coverage before** (main run 40725884653, `3395065c0`, the post-02 layout, from the log's nextest PASS lines): 12 nextest steps, 3194 test executions, **2802 unique tests**. The 392 `roboco-harness` lib tests ran twice: with `native-fixture` in the first step and without it in "Engine, RPC, and harness unit tests".

| binary | tests | | binary | tests |
|---|---|---|---|---|
| roboco-ui (lib) | 1656 (1655 headless + native font) | | roboco-harness::windows_native | 16 |
| roboco-engine (lib) | 453 | | roboco::bin/roboco | 8 |
| roboco-harness (lib) | 392 | | roboco-preview::discovery | 7 |
| roboco-markdown | 52 | | roboco-harness::pi_rpc | 7 |
| roboco-engine-client | 38 | | roboco::room_cli | 5 |
| roboco-harness::opencode | 31 | | roboco-proto (device_paths) | 4 |
| roboco-harness::cursor_shim | 31 | | gpui_windows (layout) | 4 |
| roboco-rpc | 28 | | harness::codex_availability, engine::worktree_on_run (worktree_location_), roboco::pairing_cli | 3 each |
| roboco-update | 27 | | engine::project_actions_rpc, local_profiles, codex_catalog | 2 each |
| roboco-preview (lib) | 24 | | engine::local_first, engine_local, roboco::remote_startup, engine-client::preview_proxy | 1 each |

`roboco::tailcat_invite_cli` builds but has 0 tests on Windows (`#![cfg(unix)]`). Ignored tests (6 in ui, 1 in the engine lib) stay ignored.

**Coverage after** (expected): the same 27 binaries and filters, plus `roboco-engine::codex_login_resolution` (03). The harness lib runs once, with `native-fixture`. That feature only adds the two fixture bins and gates `windows_native.rs` (ticket 02), so no lib test is lost. The observed list from the PR run follows below.

**2026-10-29 (PR #43, first run 40739069881 on `176e789b0`; cold: no Windows cache was restored, see 05):**

- **Coverage after (observed):** `Starting 2806 tests across 29 binaries (146 tests skipped)` → `2806 passed`. Diffed against the baseline's PASS lines: **all 2802 baseline tests ran, 0 missing**, and 4 are new (`roboco-engine::codex_login_resolution`). 29 binaries = the 27 above + `codex_login_resolution` + `tailcat_invite_cli` (0 tests on Windows). The 146 skipped are the filter's trims (proto outside `device_paths`, gpui_windows outside `layout`, `worktree_on_run` outside `worktree_location_`) plus the 7 `#[ignore]`d. 0 flaky.
- `native_diff_font_geometry`: `PASS: roboco-ui changes::tests::native_diff_font_geometry` from the JUnit check step, and its PASS line is in the nextest output.
- **Timings (cold, no cache restored):**

  | job | before (post-02, warm, 5 runs 10-28) | this PR, run 1 (cold) |
  |---|---|---|
  | `tests` | 36.4 / 41.3 / 43.2 / 51.0 / 53.0 (p50 43.2) | **24.9** (test build `Finished … in 16m 26s`, test run 4.9 min `Summary [295 s]`) |
  | `app` | (inside `tests`: packaging 7.0–10.3) | **21.8** (packaging build `Finished … in 18m 16s`; installer 0.15, startup + CLI smoke < 0.1) |
  | workflow wall | = `tests` | ≈ 25 (the two jobs run in parallel) |

  Warm numbers come after main seeds `windows-tests`/`windows-app`, measured after merge.
- **Fix found by run 1: one cargo invocation, not two.** The "Build tests" (`--no-run`) → "Windows tests" split made the second step recompile `ort-sys`, `roboco-voice`, `roboco-ui` and `roboco` (`Finished … in 1m 28s`; 1m 57s in the fallback run). `ort-sys`'s build script downloads the ONNX runtime while it runs and emits `rerun-if-changed` on the result, so the file is always newer than the script's start time and the next cargo command reruns it. The fix merges build and run into one "Windows tests" step, which gives a single `Finished` line, as the acceptance item asks. Build and run times still come from the log (`Finished` and nextest's `Summary`). Applied in the follow-up commit; the one-`Finished` check is on that commit's run.

