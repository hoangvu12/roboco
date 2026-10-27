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

**Status:** ready-for-agent

**Parent:** `.scratch/ci-speed/spec.md`

Baseline: `tests` 75 min p50 warm (≈ 50 expected after 02). Upstream measured CI-release (workspace opt0) 23.7 min vs dev profile 31.5 min cold for the Windows test build, so do **not** switch Windows to the dev profile.

- [ ] One cargo build per job (log: a single `Finished` line for the test build in `tests`, one for `roboco` in `app`)
- [ ] Coverage list attached: test count before (sum of old steps, deduped) ≤ after
- [ ] `native_diff_font_geometry` still runs as its own mandatory test and is visible in the summary
- [ ] Measured over ≥ 5 green PR runs + ≥ 3 main runs: `tests` p50 (target ≤ 25 min warm), `app` p50 (target ≤ 15), workflow wall p50 (target ≤ 25)
- [ ] Cache entries `windows-tests` + `windows-app` together ≤ 3.5 GB

## Comments

**2026-10-28 (web research round 2, `research-web-2026-10-28.md` §2.4, §3.4, §5.3):**

- **Defender is already off on GitHub-hosted Windows images.** `actions/runner-images` `Configure-WindowsDefender.ps1` sets `DisableRealtimeMonitoring = $true` and `ExclusionPath = C:\, D:\`, and switches to passive mode. Drop setup step 1, or keep it as a documented no-op, and credit no savings to it.
- Alternative to writing `[profile.release]` overrides into `$CARGO_HOME/config.toml`: a named profile in `Cargo.toml`, as uv does (`[profile.fast-build]`, run with `cargo nextest run --cargo-profile …`). For Roboco that would be `[profile.ci] inherits = "release"` + `lto = false` + per-member `opt-level = 0`. It is tracked and reproducible locally, doesn't touch the rust-cache key (no `CARGO_*` env), and builds in `target/ci`, so it never fights the packaging build. Trade-off: the `app` job's packaging build would not see it. That's fine: `app` keeps building the real release profile (closer to what ships), at the cost of LTO-off savings there. Pick one approach and record why.
- Confirmed not needed: `nextest archive`/`--partition`. uv partitions its Windows tests three ways only because its run time is large. Revisit if `tests` shows > 8 min of test execution after this ticket.
- Ticket **15** (arm64 `tests`) and ticket **19** (fewer test binaries) build on this split.
