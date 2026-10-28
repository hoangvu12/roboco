# 15 — Windows `tests` job on `windows-11-arm` (PR critical path −20…−25 %)

**What to build:** After ticket 04 splits Windows into `tests` and `app`, run **`tests`** on `windows-11-arm`. The **`app`** job stays on x64 and keeps building, packaging, installing and smoke-testing the shipped x64 binary on every PR.

Evidence from `release.yml` (research-web §3.1). The Windows package step takes 21.9 min on x64 vs 16.4 on arm64 on a cache miss (n=13/12), and 13.9 vs 11.2 on a hit (n=4/6). `windows-11-arm` is free for public repos. Ticket 04's target `tests` ≈ 20–22 min is the PR critical path; this would make it ≈ 16–17.

- `tests` job: `runs-on: windows-11-arm`. Add release.yml's "Ensure clang for aarch64 ring builds" step. Locate fxc with the x64-or-arm64 pattern release.yml already uses. Make sure node (cursor_shim suite) and Go (if the job builds Tailcat pieces) are present; `actions/setup-node` and `setup-go` support arm64.
- LLD (ticket 05) applies to `aarch64-pc-windows-msvc` too. Set it per target, for example `CARGO_TARGET_AARCH64_PC_WINDOWS_MSVC_LINKER=rust-lld`, identically in every job that shares the key.
- **Coverage decision (user):** x64-specific behaviour of the test suites, such as ConPTY or path handling, is architecture-independent in Rust, but not provably so. Options:
  - (a) main pushes also run `tests` on x64. That needs a second cache key, ≈ +2 GB, which ticket 13's paid limit could absorb.
  - (b) a **nightly** x64 `tests` run with no cache save (cold ≈ 35–40 min, no one waits on it).
  - (c) arm64 only.

  Recommendation: (b).
  **User decision (2026-10-29): (b)**, a nightly x64 `tests` run with no cache save.
- The label moved to the VS 2026 image in September 2026. Watch for image churn in the first runs.
- Record the decision and measurements here. If arm64 queue time or flakiness is worse, revert by changing one line.

**Blocked by:** 04 (the split), 05 (linker settings are shared), 13 (if option (a) needs the paid cache headroom)

**Status:** implemented on branch `ci/windows-tests-arm64` (2026-10-29, coverage option (b)); the p50 items and the scheduled nightly runs are measured after merge

**Parent:** `.scratch/ci-speed/spec.md`

Baseline: ticket 04/05 after-numbers for `tests` (target ≤ 22 min warm).

- [ ] One A/B pair on the same commit via `workflow_dispatch`: `tests` on windows-2022 vs windows-11-arm, both warm; attach timings and the nextest summary (same test count)
- [ ] Tests that cannot run on arm64 are named, with the reason, and covered by the chosen x64 option
- [ ] Measured over ≥ 5 green PR runs + 3 main runs: `tests` p50 (target ≤ 17 warm), workflow wall p50, queue time p50 (< 1 min)
- [ ] The chosen x64 coverage option runs green at least twice

## Comments

**2026-10-29 (implementation, branch `ci/windows-tests-arm64`):**

What changed:

- **`tests` runs on `windows-11-arm`** for PRs and pushes. Its `runs-on` is `schedule` → `windows-2022`, dispatch → the `tests_runner` input, else `windows-11-arm`. Reverting to x64 means changing that last literal.
- **Nightly x64 (option (b))**: `schedule: '11 4 * * *'` (UTC, apart from android's 02:47 and ui-tests' 03:23). On schedule, only `tests` runs, on `windows-2022`, with `rust-cache: false`. It neither saves nor restores a Rust cache, so it owns no entry and keeps no stale x64 entry alive. `app` gets `if: github.event_name != 'schedule'`, because every PR and push already runs it on x64, and `native-gui` is dispatch-only as before.
- **Dispatch inputs**: `runner` is split into `tests_runner` (`windows-11-arm` default, `windows-2022`, `windows-2025`) and `app_runner` (`windows-2022`, `windows-2025`; it stays x64 because it packages the shipped x86_64 build). New `rust_cache` (default on): off reproduces the nightly's cold build. A dispatch with `tests_runner=windows-2022, rust_cache=false` is the nightly's `tests` job, plus an `app` run.
- **`windows-ci-setup`**:
  - The "Ensure clang for aarch64 ring builds" step comes from release.yml (`runner.arch == 'ARM64'`; choco fallback).
  - The fxc lookup accepts the SDK's `x64` or `arm64` build, as release.yml does.
  - New `rust-cache` input.
  - **`save-if` is now `push` to main only** (was any run on `refs/heads/main`). Without this, the nightly or a dispatch A/B on main would write an entry.
- **Cache key per architecture**: rust-cache already puts `runner.os`-`runner.arch` in the key (`v0-rust-windows-tests-Windows_NT-x64-…` today), so `shared-key: windows-tests` gives arm64 its own `…-Windows_NT-arm64-…` entry. It replaces the x64 one, which stops being saved and ages out. It does not add a key.
- **LLD on aarch64**: no new setting. Ticket 05 writes `[target.<host triple>] linker = "rust-lld.exe"` into `$CARGO_HOME/config.toml`, so the arm64 job gets `[target.aarch64-pc-windows-msvc]`. That is per target, and it is the same switch in every job that shares a key (there is one job per key). The new "Architecture and linker of the test build" step checks the `roboco.exe` the CLI tests ran: PE machine (`aarch64`) and no Rich header. The `app` job's inline check moved into the same script, `scripts/test-windows-linker.ps1`, which is covered by the `scripts/test-windows-*.ps1` path filter. Checked locally under PowerShell 5.1 against `notepad.exe`: `-FastLinker false` passes ("x86_64, linked by link.exe"), and `-FastLinker true` throws.
- **node**: `actions/setup-node@v4` with node 24 in `tests` on both architectures (`cursor_shim` spawns `node` and fails without it). **Go**: not needed. Since ticket 04, `tests` builds no Tailcat adapter, and no selected suite runs `go`.
- Docs: the AGENTS.md CI line and `docs/reference/windows-development.md` "Verification" now describe the arm64 `tests` job and the nightly x64 run.
- Lint: `actionlint` clean on `windows.yml`, which also validates the composite action.
