# 12 — Pin the Rust toolchain so cache keys stop following runner images

**What to build:** Replace `channel = "stable"` in `rust-toolchain.toml` with an exact version, and keep it current through Dependabot.

Why: rust-cache hashes the rustc version into every key. With `stable`, each job compiles with whatever stable its runner image ships, and images roll out over days. One run on 2026-10-27 used 1.99.0 in `ui-tests` (job 122458314912) and 1.99.1 in `android-emulator` (job 122458315870). Windows switched on 10-23/24 while Linux was still mixed on 10-27. Every flip makes keys miss (+9…13 min per job), and keys alternate while a rollout is in progress. Zed (`1.98.1`), uv (`1.99.0`) and ruff (`1.99.0`) all pin. Research: `research-web-2026-10-28.md` §1.1.

- `rust-toolchain.toml`: `channel = "<the version the current ubuntu-24.04 and windows-2022 images ship>"` (1.99.1 at the time of writing; check `rustc -V` in a fresh job log first), keeping `components = ["rustfmt", "clippy"]`. Add `profile = "minimal"` so a mismatch installs only what's needed.
- Every workflow keeps `rustup show active-toolchain || rustup toolchain install`. That installs the pinned version when an image lags (≈ 0.3–1 min once per job until the image catches up).
- `.github/dependabot.yml`: a `rust-toolchain` ecosystem entry ([supported since 2025-08-19](https://github.blog/changelog/2025-08-19-dependabot-now-supports-rust-toolchain-updates/)), monthly. If ticket 10 has not landed yet, create the file with only this entry, and let 10 add `github-actions`.
- This changes the toolchain for local development too (tracked file). Note it in `docs/reference/windows-development.md` / `android-development.md` if they say "stable".
- `release.yml` and `android.yml` are covered automatically: both use the toolchain file.

**Blocked by:** none (land alongside or right after 01, so the first main seed uses the pinned version)

**Status:** ready-for-human (confirms that pinning local dev to an exact version is acceptable; then ready-for-agent)

**Parent:** `.scratch/ci-speed/spec.md`

Baseline: rustc versions per job from rust-cache's "Environment considered" log block (research §1.1 table). Cache entries with different env hashes for the same job key (`gh api repos/hoangvu12/roboco/actions/caches`).

- [ ] Every Rust job in one run logs the same `Rust Versions:` line (check 1 PR run + 1 main run, all workflows)
- [ ] Over 2 weeks after merge: no cache key for the same job/shared-key exists with two different env hashes, except across a deliberate Dependabot bump
- [ ] Dependabot opens a toolchain PR when a new stable ships (or the config validates in Insights → Dependency graph → Dependabot)
- [ ] Measured: toolchain step p50 per job before/after (`python -I overhead.py data`); expect ≤ 0.05 min when the image matches, ≤ 1 min when it lags
- [ ] Measured with `cache.py` over ≥ 10 runs: miss rate excluding `Cargo.lock`/toolchain-bump runs, compared with ticket 01's after-numbers

## Comments
