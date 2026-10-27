# 06 — Linux: nextest, dev profile, one build per graph; drop android-core

**What to build:** Restructure `ui-tests.yml`. Carries the intent of upstream `878307a3d`, `9cb1de50f` and `5d5d93599` by hand.

- **Composite action `.github/actions/linux-ci`**:
  - toolchain
  - apt packages with bounded retries and a cached `.deb` dir that only main saves (upstream `scripts/ci/apt-install.sh`)
  - `CARGO_PROFILE_DEV_DEBUG=0` exported for the job
  - rust-cache `shared-key` input, main-only save
  - pinned nextest, installed *after* the cache restore
- **`core-tests`** (key `linux-core`): one script, `scripts/ci/test-core.sh`, running one `cargo nextest run` over:
  - `roboco-harness`: all test binaries, `native-fixture` on for the whole build
  - `roboco-engine` lib + the named integration tests that `engine-local-regressions` runs today: `pi_resume`, `pi_steering`, `pi_forks`, `account_failover` … `room_relay`. **Listed, not globbed**, to avoid compiling the unrun live-agent binaries.
  - `roboco-sync` lib, `roboco-update` lib, `roboco-mcp`
  - `roboco-preview`: every test, which absorbs `preview-tests.yml` (delete that workflow)
  - `roboco-doc`, `roboco-android-assets`, `roboco-xtask` (moved here from `android.yml`)

  Coverage list before/after is mandatory.
- **`ui-tests`** (key `linux-ui`): one nextest invocation on the **dev profile**: `-p roboco-ui --lib -p roboco --test pairing_cli --test tailcat_invite_cli --test room_cli`. `roboco-ui` goes in a `max-threads = 1` test group, replacing `--test-threads=1`. This replaces the two thin-LTO `--release` builds. If any UI test needs release, say which one and why.
- **`mobile-core`** (key `linux-mobile`): nextest for text/markdown/mobile/mobile-client (Tailcat e2e keeps the Go pieces and the verbose re-run on failure) and engine-client (`max-threads = 1` group). The `syntax` mobile subset stays a separate cargo command (different features on purpose). Gate "benches compile" and "Mobile layout bench (informational)" to `push`/`schedule`/`workflow_dispatch`.
- **Delete the `android-core` job.** `android.yml`'s Gradle core build already runs `xtask android-core --check` (16 KB PT_LOAD, Kotlin contract) for both ABIs. The mobile-dist profile is built on main by the R8 smoke and on tags by `release.yml`. If the `sizes.json` artifact is wanted, upload it from the R8 smoke.
- **Nightly backstop**: a `schedule` trigger (once a day) plus a `release-backstop` job that runs `test-core.sh --release` and the UI nextest with `--release` (`CARGO_PROFILE_RELEASE_LTO=false`), no cache save. It is the safety net for the dev-profile switch (upstream `5d5d93599`).
- `paths`:
  - add `!apps/android/**`
  - drop `scripts/test-linux-browser.sh`
  - add `.github/actions/linux-ci/**` and `scripts/ci/**`
  - absorb `preview-tests.yml`'s paths (already covered by `crates/**`)
- Keep a nextest `ci` profile with retries for the known timing-sensitive tests; overrides are named, as in upstream's `scripts/ci/nextest.toml`.

**Web-removal interplay:** this ticket does not touch `ROBOCO_WEB_DIST` or the webkit apt packages. In the dev profile, `crates/engine/build.rs` does not need `ROBOCO_WEB_DIST` (the release-only panic), so if the web-removal PR has not landed, keep the env line as is. When it lands, those lines go with it.

**Blocked by:** 01

**Status:** ready-for-agent

**Parent:** `.scratch/ci-speed/spec.md`

Baseline (research §2.3): ui-tests wall p50 23.6. Jobs: `ui-tests` 21.8, `android-core` 15.3, `engine-local-regressions` 14.8, `mobile-core` 12.8, `tailcat-adapter` 3.3; `preview-tests` 3.3. ≈ 71 runner-min per push.

- [ ] Coverage list attached: every test binary/filter run before runs after (nextest list), except the intentional `android-core` removal
- [ ] `preview-tests.yml` deleted; preview tests visible in `core-tests`
- [ ] Nightly `release-backstop` ran green at least twice
- [ ] Measured over ≥ 10 runs: ui-tests wall p50 (target ≤ 15 warm), runner-min per push (target ≤ 35), cache entries `linux-core`/`linux-ui`/`linux-mobile` sizes
- [ ] Android-only PR (touching only `apps/android/**`) does not trigger `ui-tests.yml`

## Comments

**2026-10-28 (web research round 2, `research-web-2026-10-28.md` §2.4, §2.5, §3.1):**

- **No doctests exist** in `crates/` or `apps/roboco` (every `///`/`//!` fence is a `text` or non-Rust block). Moving to nextest, which skips doctests, loses no coverage, and the per-crate `Doc-tests` rustdoc pass goes away. No `cargo test --doc` step is needed.
- Consider a named CI profile in `Cargo.toml` instead of `CARGO_PROFILE_DEV_DEBUG=0` env. uv's `[profile.fast-build]` is `inherits = "dev"`, `opt-level = 1`, `lto = "off"`, `debug = 0`, `strip = "debuginfo"`, run with `--cargo-profile`. A profile doesn't change the rust-cache key, and every job sharing a key gets it without having to repeat env lines. `opt-level = 1` for workspace code may also make the GPUI-heavy UI tests run faster. A/B it.
- rust-cache already exports `CARGO_INCREMENTAL=0`, so the dev profile's default incremental mode costs nothing in CI.
- Ticket **14** then moves these jobs to `ubuntu-24.04-arm`. This repo's release data shows arm64 Linux builds 23–25 % faster.
