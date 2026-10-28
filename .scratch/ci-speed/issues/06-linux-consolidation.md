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

**Status:** in review, PR #44 (with 08 and 23); PR-run timings below; the ≥ 10-run measurement and the two green nightly backstops come after merge

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

**2026-10-29 (implementation, branch `ci/linux-android-tiers`):**

What landed:

- **`.github/actions/linux-ci`** (composite): toolchain; optional apt packages through `scripts/ci/apt-install.sh` (bounded `--download-only` retries, `--no-install-recommends`, no docs/man/locales, a `~/apt-debs` cache keyed by image + package list + script hash, saved by main pushes only); `CARGO_PROFILE_DEV_DEBUG=0` into `$GITHUB_ENV`; rust-cache (`shared-key` input, `add-job-id-key: false`, `cache-workspace-crates: false`, `cache-on-failure`, `save-if` = input `save-rust-cache` **and** `push` to main, so nightly/dispatch runs never write); then `scripts/ci/install-nextest.sh` (cargo-nextest **0.9.144**, the version windows.yml pins, SHA-256 `8a4f7262…1e1e` checked against the release's `.sha256` asset) after the cache restore.
- **`core-tests`** (key `linux-core`, the writer): `scripts/ci/test-core.sh`, one `cargo nextest run -P ci --no-fail-fast` (table below). Replaces `engine-local-regressions` (`linux-engine` key) and `preview-tests.yml` (`preview` key, workflow deleted), and takes android.yml's "Test the asset generator and the xtask (Rust)" step.
- **`ui-tests`** (key `linux-ui`): one dev-profile nextest run `-p roboco-ui --lib -p roboco --test pairing_cli --test tailcat_invite_cli --test room_cli`. `.config/nextest.toml` puts `roboco-ui` in a `max-threads = 1` group (`serial-ui`) instead of `--test-threads=1`; the CLI tests run beside it. The two thin-LTO `--release` builds are gone.
- **`mobile-core`** (key `linux-mobile`): one nextest run over text, markdown, mobile, mobile-client and engine-client (`serial-engine-client` group); the Tailcat Go pieces and the verbose re-run on failure stay; the `syntax` mobile subset stays its own invocation (nextest, `--no-default-features --features mobile-languages --lib`). "benches compile" and the layout bench run only off-PR or with `ci:full`.
- **`android-core` deleted.** `android.yml`'s ubuntu Gradle build runs `xtask android-core --check` for both ABIs; the R8 smoke (main) builds `mobile-dist`, and now uploads `target/android-core/mobile-dist/sizes.json` as the same `android-core-sizes` artifact (x86_64 only: the smoke builds one ABI; the old job recorded both).
- **Nightly `release-backstop`** (`schedule` 03:23 UTC, or dispatch with `release_backstop: true`): `test-core.sh --release` then the UI/CLI nextest with `--release`, `CARGO_PROFILE_RELEASE_LTO=false`, key `linux-release-backstop` never saved. On `schedule` the other four jobs skip.
- `paths` (PR and push): `+ !apps/android/**`, `+ .github/actions/linux-ci/**`, `+ scripts/ci/**`, `+ .config/nextest.toml`; `- scripts/test-linux-browser.sh` (no step runs it; the web-removal branch drops the same line). preview-tests' paths are inside `crates/**`.
- Test groups are `platform = 'cfg(target_os = "linux")'` on purpose: windows.yml (ticket 04's branch) keeps its own `-j 1` / parallel steps unchanged by this PR. `[profile.ci] retries = 2` already existed (ticket 02) and covers the timing-sensitive tests by name and by class (completion_notices, ticket 24; the upstream overrides' bootstrap/MCP-injection tests); a pass on retry shows as FLAKY in the final summary, `--retries 0` reproduces the strict run. So no per-test overrides were added.
- `ROBOCO_WEB_DIST` (workflow env) and the webkit apt packages are untouched (web-removal owns them); the dev profile does not need `ROBOCO_WEB_DIST`, the backstop's `--release` does.
- Left alone: windows.yml:162's comment still names `preview-tests.yml` (ticket 04's branch owns windows.yml).
- Lint: actionlint 1.7.12 clean on `ui-tests.yml`, `android.yml` and the composite; `bash -n` on `scripts/ci/*.sh`.

Coverage before → after (every cargo invocation; "all" = cargo's default target selection):

| before (job) | after (job) | change |
|---|---|---|
| harness `--lib opencode::` (engine-local) | harness `--lib` (core) | **wider**: every harness unit test, not only `opencode::` |
| harness `--test opencode`, `--test codex`, `--test claude`, `--test pi` (engine-local) | harness every `tests/*.rs` (core), `native-fixture` on | **wider**: + acp, acp_hardening, acp_quiet, acp_stall, claude_availability, claude_models, claude_refresh_lock, cursor, cursor_shim, managed_install, managed_install_failure, model_catalog, model_refresh, pi_rpc, shell_env_resolution, real_acp_lifecycle / real_quiet_survey (their live tests stay `#[ignore]`); codex_availability and windows_native compile empty (`cfg(windows)`) |
| — | harness `--bins` (the two `native-fixture` fixture binaries) | built before only implicitly; now their unit tests (if any) run |
| engine `--test pi_resume --test pi_steering --test pi_forks` (engine-local) | same (core) | — |
| engine `--lib` + account_failover, account_sign_in_state, agent_origin, codex_subagents, completion_notices, continue_on_harness, engine_local, harness_switch, limited_chats, local_profiles, project_action_commands, project_actions_remote, project_actions_rpc, remote_settings, restart_resume, resume_at_reset, side_chats, rooms, room_relay (engine-local) | same 19 + lib, listed in `test-core.sh` (core) | — (`room_relay` leaves with the web-removal PR, as before) |
| sync `--lib`, update `--lib`, mcp all (= lib) (engine-local) | same (core) | — |
| preview all: lib, bin `preview-child-server`, tests discovery/leak/proxy/transport (preview-tests.yml) | same (core) | — |
| — | doc `--lib --test attachments_roundtrip` (core) | **new** (no workflow ran roboco-doc) |
| android-assets all (lib, bin, pipeline/raster/vector_drawable) + xtask all (bin) (android.yml ubuntu) | same (core) | moved; any change to either crate matches `crates/**`, so ui-tests still runs |
| text, markdown, mobile, mobile-client all; engine-client all `--test-threads=1` (mobile-core, 2 invocations) | same, one nextest run, engine-client in a 1-thread group (mobile-core) | engine-client now built with `test-support` unified in (mobile-client's dev-dep enables it) |
| syntax `--no-default-features --features mobile-languages --lib` | same (nextest) | — |
| `cargo bench -p roboco-text --no-run`; layout bench `--release --ignored` (every run) | same, off-PR / `ci:full` only | intentional (spec): informational, 5.4 min on every PR |
| `xtask android-core --profile mobile-dist --check` both ABIs (android-core) | — | **removed**: duplicate of android.yml's Gradle `--check` (both ABIs, `mobile` profile) + R8 smoke (`mobile-dist`, main) + release.yml (tags) |
| ui `--lib` `--release` `--test-threads=1`; roboco `--test pairing_cli --test tailcat_invite_cli --test room_cli` `--release` (ui-tests, 2 builds) | same targets, dev profile, one build (ui-tests); `--release` nightly (release-backstop) | profile switch; backstopped |
| doctests (implicit in `cargo test`) | — | none exist (research-web §2.5) |

Per-binary nextest counts from the PR run are below.

**2026-10-29 (PR #44 runs: PR 40739293148, dispatch with `release_backstop` 40739307223):**

Every Rust job was **cold**: the new keys (`linux-core`, and `CARGO_PROFILE_DEV_DEBUG=0` in the key of `linux-ui`/`linux-mobile`) have no main entry yet ("No cache found"). The apt cache was empty too (37 s to download and install). Queue times were inflated by four runs at once and are left out.

| job | PR run (cold) | baseline (research §2.3, warm p50) |
|---|---|---|
| `ui-tests` | **13.8** (build 11.0, 1733 tests in 96 s) | 21.8 |
| `core-tests` | **8.2** (build 4.8, 1719 tests in 138 s) | `engine-local-regressions` 14.8 + `preview-tests` 3.3 |
| `mobile-core` | **10.5** (PR: no benches); 19.5 on dispatch with benches + layout bench | 12.8 |
| `tailcat-adapter` | 1.6 | 3.3 |
| `android-core` | — (deleted) | 15.3 |
| **runner-min** | **34.1** | ≈ 71 |
| `release-backstop` (dispatch) | 31.3 (core `--release` 12.4, UI/CLI `--release` 17.8) | — |

nextest per binary (dev profile; the release run lists the same 1719 + 1733):

- **core-tests** (66 binaries; 13 skipped = `#[ignore]`):
  - harness lib 424, acp 67, acp_hardening 10, acp_quiet 9, acp_stall 1, claude 34, claude_availability 3, claude_models 2, claude_refresh_lock 1, codex 42, cursor 22, cursor_shim 31, managed_install_failure 1, model_catalog 3, model_refresh 1, opencode 31, pi 60, pi_rpc 7, shell_env_resolution 1;
  - engine lib 507, account_failover 7, account_sign_in_state 2, agent_origin 8, codex_subagents 2, completion_notices 25, continue_on_harness 3, engine_local 1, harness_switch 6, limited_chats 6, local_profiles 2, pi_forks 2, pi_resume 1, pi_steering 3, project_action_commands 2, project_actions_remote 2, project_actions_rpc 2, remote_settings 7, restart_resume 13, resume_at_reset 14, room_relay 4, rooms 9, side_chats 25;
  - sync 13, update 19, mcp 45;
  - preview lib 24, discovery 4, leak 1, proxy 1, transport 1;
  - doc lib 155, attachments_roundtrip 1;
  - android-assets pipeline 3, raster 2, vector_drawable 2; xtask bin 44.
- **ui-tests** (4 binaries): roboco-ui lib 1721, pairing_cli 3, room_cli 5, tailcat_invite_cli 4.
- **mobile-core** (23 binaries, 5 skipped):
  - text: fidelity 15, layout 28, measure 15, props 2, lib 15;
  - markdown 52; mobile lib 63, layout_attach 2;
  - mobile-client lib 213, accounts 4, fleet 5, images 1, notify 9, real 19, remote_open 1, rooms 8, session 23, side_chats 5, tailcat 2, workspace 7;
  - engine-client lib 38, preview_proxy 3;
  - plus the syntax subset, 21 in its own run.

Findings:

- **The wider harness coverage found a broken test.** `model_catalog::each_spec_hashes_its_auth_file_contents` failed 3/3 in the dev and release runs. wave2/21 (`dedb7df05`) took pi out of `harnesses()` but left `.pi/agent/auth.json` in the zipped auth list, so antigravity and devin were checked against the wrong files. Fixed in this PR (one line; no workflow had ever run that binary).
- **Retries worked as intended.** completion_notices (ticket 24) and `resume_at_reset::the_setting_on_arms_the_continuation_automatically` failed once (30 s waits) and passed on retry, reported FLAKY. nextest also flags 3 LEAKs (harness jsonrpc ×2, preview discovery ×1): informational, not failures.
- **No UI test needs `--release`.** The whole dev-profile UI/CLI suite passed (1733/1733), and so did the release one in the backstop.
- The composite's nested post steps run ("Post Run ./.github/actions/linux-ci"), so rust-cache will save from inside the action on main.

**2026-10-29 (merge of main after #43, ci-speed 04/05):** ticket 04 landed the same `serial-ui`/`serial-engine-client` groups for every platform, plus `[profile.ci.junit]`. The merge keeps main's unfiltered groups, so the `platform = linux` filter above is gone and windows.yml and ui-tests.yml share one definition. AGENTS.md takes main's `windows.yml` bullet and this branch's `ui-tests.yml` bullet (`preview-tests.yml` stays deleted). windows.yml:127 still names `preview-tests.yml` in a comment; that file is not this branch's.
