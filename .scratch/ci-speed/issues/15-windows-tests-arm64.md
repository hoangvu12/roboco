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
- The label moved to the VS 2026 image in September 2026. Watch for image churn in the first runs.
- Record the decision and measurements here. If arm64 queue time or flakiness is worse, revert by changing one line.

**Blocked by:** 04 (the split), 05 (linker settings are shared), 13 (if option (a) needs the paid cache headroom)

**Status:** ready-for-human (coverage option a/b/c), then ready-for-agent

**Parent:** `.scratch/ci-speed/spec.md`

Baseline: ticket 04/05 after-numbers for `tests` (target ≤ 22 min warm).

- [ ] One A/B pair on the same commit via `workflow_dispatch`: `tests` on windows-2022 vs windows-11-arm, both warm; attach timings and the nextest summary (same test count)
- [ ] Tests that cannot run on arm64 are named, with the reason, and covered by the chosen x64 option
- [ ] Measured over ≥ 5 green PR runs + 3 main runs: `tests` p50 (target ≤ 17 warm), workflow wall p50, queue time p50 (< 1 min)
- [ ] The chosen x64 coverage option runs green at least twice

## Comments
