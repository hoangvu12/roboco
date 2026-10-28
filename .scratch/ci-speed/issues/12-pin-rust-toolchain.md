# 12 — Pin the Rust toolchain so cache keys stop following runner images

**What to build:** Replace `channel = "stable"` in `rust-toolchain.toml` with an exact version, and keep it current through Dependabot.

Why: rust-cache hashes the rustc version into every key. With `stable`, each job compiles with whatever stable its runner image ships, and images roll out over days. One run on 2026-10-27 used 1.99.0 in `ui-tests` (job 122458314912) and 1.99.1 in `android-emulator` (job 122458315870). Windows switched on 10-23/24 while Linux was still mixed on 10-27. Every flip makes keys miss (+9…13 min per job), and keys alternate while a rollout is in progress. Zed (`1.98.1`), uv (`1.99.0`) and ruff (`1.99.0`) all pin. Research: `research-web-2026-10-28.md` §1.1.

- `rust-toolchain.toml`: `channel = "<the version the current ubuntu-24.04 and windows-2022 images ship>"` (1.99.1 at the time of writing; check `rustc -V` in a fresh job log first), keeping `components = ["rustfmt", "clippy"]`. Add `profile = "minimal"` so a mismatch installs only what's needed.
- Every workflow keeps `rustup show active-toolchain || rustup toolchain install`. That installs the pinned version when an image lags (≈ 0.3–1 min once per job until the image catches up).
- `.github/dependabot.yml`: a `rust-toolchain` ecosystem entry ([supported since 2025-08-19](https://github.blog/changelog/2025-08-19-dependabot-now-supports-rust-toolchain-updates/)), monthly. If ticket 10 has not landed yet, create the file with only this entry, and let 10 add `github-actions`.
- This changes the toolchain for local development too (tracked file). Note it in `docs/reference/windows-development.md` / `android-development.md` if they say "stable".
- `release.yml` and `android.yml` are covered automatically: both use the toolchain file.

**Blocked by:** none (land alongside or right after 01, so the first main seed uses the pinned version)

**Status:** implemented on branch `ci/caches-windows` (2026-10-28; user approved pinning local dev the same day); the acceptance items are measured after merge

**Parent:** `.scratch/ci-speed/spec.md`

Baseline: rustc versions per job from rust-cache's "Environment considered" log block (research §1.1 table). Cache entries with different env hashes for the same job key (`gh api repos/hoangvu12/roboco/actions/caches`).

- [ ] Every Rust job in one run logs the same `Rust Versions:` line (check 1 PR run + 1 main run, all workflows) — measure after merge
- [ ] Over 2 weeks after merge: no cache key for the same job/shared-key exists with two different env hashes, except across a deliberate Dependabot bump — measure after merge
- [ ] Dependabot opens a toolchain PR when a new stable ships (or the config validates in Insights → Dependency graph → Dependabot) — check after merge (the config only takes effect on the default branch)
- [ ] Measured: toolchain step p50 per job before/after (`python -I overhead.py data`); expect ≤ 0.05 min when the image matches, ≤ 1 min when it lags — measure after merge; **the ≤ 0.05 expectation is wrong**, see Comments
- [ ] Measured with `cache.py` over ≥ 10 runs: miss rate excluding `Cargo.lock`/toolchain-bump runs, compared with ticket 01's after-numbers — measure after merge

## Comments

**2026-10-28 (implementation, branch `ci/caches-windows`):**

- Version: **1.99.1**. Fresh logs from the newest runs (`gh run view --log`, rust-cache "Rust Versions"): windows `tests` 40580510376 and `android (windows-2022)` 40589753504 log `1.99.1 x86_64-pc-windows-msvc 58cf8f9c…`; `android-emulator-tablet` (same run) logs `1.99.1 x86_64-unknown-linux-gnu 58cf8f9c…`, while `ui-tests`, `engine-local-regressions`, `mobile-core`, `android (ubuntu)` and `android-emulator` of main run 40589753394/40589753504 (2026-10-27 21:47) still log `1.99.0 … b940084d…`. So ubuntu-24.04 is mid-rollout to 1.99.1 and windows-2022 is done; 1.99.1 is the newest stable on any image.
- `rust-toolchain.toml`: `channel = "1.99.1"`, `profile = "minimal"`, components unchanged, plus a 3-line comment on why.
- `.github/dependabot.yml`: created with only the `rust-toolchain` entry (`package-ecosystem: rust-toolchain`, verified in the Dependabot options reference), `directory: /`, monthly. Ticket 10 adds `github-actions`.
- Docs: `docs/reference/android-development.md` toolchain row ("stable" → the exact version in `rust-toolchain.toml`) and `docs/reference/windows-development.md` "Build and run" (rustup installs the pinned toolchain on the first `cargo`; no `+<version>` override). No tracked doc mentioned `cargo +1.99.0`.
- Local check on the Windows dev host: in the worktree `rustup show active-toolchain` → `1.99.1-x86_64-pc-windows-msvc (overridden by …rust-toolchain.toml)`; `cargo -V` 1.99.1, `rustc 1.99.1 (58cf8f9c3 2026-10-13)`, rustfmt and clippy present.
- **Install cost is not zero on a matching image.** Runner images install their compiler as the toolchain named `stable-<host>`; a toolchain file naming `1.99.1` resolves to the toolchain named `1.99.1-<host>`, which no image has, so `rustup toolchain install` downloads it in every job even when the image's stable *is* 1.99.1 (rustup refuses to `toolchain link` a custom toolchain under an official name, so it cannot be aliased). Expect ≈ 0.3–1 min per Rust job every run, not ≤ 0.05; zed/uv/ruff pay the same. Still a clear win against one 9–13 min miss per toolchain flip, but the "≤ 0.05 min" acceptance line should be read as "≤ 1 min".
