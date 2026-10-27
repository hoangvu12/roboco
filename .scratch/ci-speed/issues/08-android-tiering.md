# 08 — Android tiering: PR fast tier, full tier on main/nightly, `ci:full` opt-in

**What to build:** Split `.github/workflows/android.yml` into a fast PR tier and a full tier.

- **`changes` job** (u24, ≈ 10 s): outputs `android_relevant`, true when the PR touches any of:
  - `apps/android/**`
  - `crates/{mobile,mobile-client,text,markdown,engine-client,proto,rpc,xtask,android-assets}/**`
  - `adapters/roboco-tailcat/**`
  - `crates/ui/assets/{fonts,icons,file-icons}/**`
  - `Cargo.lock`, `rust-toolchain.toml`, `.cargo/**`
  - `.github/workflows/android.yml`

  It also outputs `gradle_host`, true for `apps/android/{gradle/**,buildSrc/**,*.gradle.kts,gradlew*}`, `crates/xtask/**` and `android.yml`. Use a SHA-pinned paths-filter action or a `git diff --name-only` script; no third-party action unpinned.
- **Full tier** = `push` to main, `schedule`, `workflow_dispatch`, or a PR labelled `ci:full` (`contains(github.event.pull_request.labels.*.name, 'ci:full')`).
- Jobs:
  - `android` (ubuntu): every run, as today.
  - `android` (windows-2022), the bash-free proof: full tier, or a PR with `gradle_host`. Split it out of the matrix into its own job so it can carry its own `if`.
  - `android-emulator`: full tier, or a PR with `android_relevant`.
  - `android-emulator-tablet`: `schedule` (nightly) and `workflow_dispatch` only, until ticket 11 makes it green. Then decide whether it returns to main pushes.
  - The R8 smoke stays on main pushes.
- Workflow `paths` trimmed: drop `crates/harness/**` and `crates/engine/**` from the *PR* trigger. They still matter for the fixture engine, but are covered by the full tier on main. Keep them on `push`. `crates/syntax/**` stays (the mobile core links it).
- Add a `schedule` trigger (nightly) for the full tier.
- Optional, measure first: build the debug APK + androidTest APK before launching the emulator (with `ROBOCO_ANDROID_ABIS=x86_64`), so `flow-tests.sh` only installs and runs. This only helps if it shortens wall time.

**Blocked by:** 01 (cache keys)

**Status:** ready-for-agent

**Parent:** `.scratch/ci-speed/spec.md`

Baseline (research §2.3): android wall p50 39.0. `android` ubuntu 19.2, windows 30.1, emulator 34.0; tablet 0/14 green on main (416 runner-min in 5 days). ≈ 83 runner-min per PR push.

- [ ] A PR touching only `crates/engine/**` runs `android` ubuntu only (no emulator, no Windows leg); the same PR with `ci:full` runs the full tier after re-run
- [ ] A PR touching `apps/android/buildSrc/**` runs the Windows leg
- [ ] Main pushes run the full tier; nightly runs tablet + tours
- [ ] Measured before/after over ≥ 10 PR runs: android runner-min per PR push and wall p50, split by Android-relevant / not
- [ ] AGENTS.md "android.yml" bullet updated to the new tiers

## Comments

**2026-10-28 (web research round 2, `research-web-2026-10-28.md` §4):**

- The "optional, measure first: build the APKs before launching the emulator" bullet is now ticket **16**. The emulator log already shows the overlap: the emulator booted at 20:53, tests started at 21:11, and an idle 2-vCPU guest shared the runner with the build for 18 min. Ticket 16 also covers `cores`, an ATD image A/B and sharding.
- The AVD snapshot cache (2.75 GB, ≈ 4 hits in ≈ 45 runs) goes in ticket **13**. Don't touch it here.
- Configuration-cache reuse, cargo-ndk via binstall, and the NDK-28-not-on-image download are ticket **17**.
- Runner-minutes only: on PRs where the emulator also runs, the ubuntu `android` job could build only `arm64-v8a`, since the emulator job builds x86_64. That saves ≈ 1–4 runner-min per PR and no wall time. Optional; measure if it is cheap to wire.
