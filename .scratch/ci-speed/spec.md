# CI speed: a fast PR tier and a full main tier

Status: ready-for-agent
Spec date: 2026-10-28
Research: `.scratch/ci-speed/research-2026-10-28.md` (all numbers below come from it; raw data in `data/`, scripts `analyze.py`, `cache.py`, `waste.py`, `fails.py`)

## Problem Statement

CI is too slow, and much of the time goes on work that tells nobody anything. Measured over the last 30 runs of each workflow (2026-10-17 → 10-27):

- **A PR waits ≈ 76 min** (p50, 63–82 warm, ≈ 90 cold) for the serial Windows job. Without it the critical path would still be ≈ 39 min (Android emulator) and ≈ 24 min (Linux UI tests). The timeouts were already raised 90→120 (Windows) and 30→45 (ui-tests) to stop green runs from being cancelled.
- **The Actions cache is over its limit**: 10.9 GB against GitHub's 10 GB, spread over ~16 per-job entries, so entries are evicted within hours. About 60 % of Rust jobs restore nothing. A cache miss costs +9 to +13 min per job and causes the "Build application 9 vs 20 min" variance. `windows.yml` never runs on `main`, so a new PR's first Windows run is always cold. PRs and tags save caches that no other ref can read.
- **The Windows job repeats work**: about 10 separate cargo invocations with different package and feature sets. `package-windows.ps1` sets `ROBOCO_TAILCAT_EMBED`, which `crates/engine/build.rs` watches, so the engine and app are rebuilt (≈ 8 min) and rebuilt again by the next test step. Two harness steps re-run earlier steps. About 21 min goes on Android-only crates and a host-bindings build that `android.yml`'s Windows leg already covers.
- **Linux duplicates builds**: `ui-tests/android-core` (15 min, no cache) repeats the Gradle core build in `android.yml`. The UI tests compile twice in thin-LTO release. A 5-min informational bench runs on every PR. An Android-only PR runs ≈ 68 runner-min of desktop jobs, because `apps/**` matches `apps/android/**`.
- **Android runs everything on every PR**: the 30-min Windows Gradle "bash-free" proof, and the 34-min emulator suite even for engine-only changes. The emulator job also spends ≈ 7 min building a host library and full-graph bindgen it does not need.
- **Rapid merges cancel `main` runs** (`cancel-in-progress: true` for every ref). A cancelled main run never seeds a cache.
- **Red suites**: 56 % of Android runner-minutes and 55 % of Windows runner-minutes went to failed or cancelled runs. The emulator flow, the tablet suite (never green in 14 main pushes) and the engine-local storage tests fail often.

Per PR push touching `crates/**`: **≈ 245 runner-min, 76 min wall.**

## Solution

Two tiers with explicit triggers, built from one set of fixes:

1. **Main-seeded caches.** Only `main` writes Rust caches (`save-if`). There is one `shared-key` per dependency graph. `windows.yml` also runs on `push: main`. `release.yml` stops caching. Main runs are never cancelled. The ≈ 9 GB budget is laid out in research §8.
2. **Windows: dedupe, then restructure.** First the cheap removals: the env-triggered rebuild, the duplicate steps, the Android-only crates. Then one nextest build on a CI release profile (LTO off, workspace crates at opt-level 0), split into parallel `tests` and `app` jobs, with LLD and a Dev Drive.
3. **Linux: one build per graph.** `core-tests` (one nextest build that absorbs `preview-tests.yml`), `ui-tests` (nextest on the dev profile, one build including the CLI tests), `mobile-core` (nextest, bench on main only) and `tailcat-adapter`. Delete `android-core`. A nightly `--release` run backstops the profile switch.
4. **Android tiering.** On PRs: debug APK + JVM always; the emulator only when Android-relevant paths change. On main, nightly and dispatch: the Windows Gradle leg, R8 smoke and tablet/tour. A `roboco-mobile-bindgen` crate lets NDK-only builds skip the host core.
5. **Opt-in full tier on a PR**: the `ci:full` label runs the full tier.
6. **Correctness and upkeep**: wire `codex_login_resolution`, `test-windows-startup.ps1` and the cursor-shim 30 s first frame. Pin actions to SHAs, add Dependabot, and bump the `@v4` actions.

### Target layout

**Fast PR tier** (`pull_request`, path-filtered, superseded runs cancelled). Target ≤ 25 min wall warm:

| workflow / job | content | est. warm |
|---|---|---|
| `ui-tests` / `core-tests` | one nextest build: harness (all, `native-fixture`), engine lib + named tests, sync, update, mcp, preview, doc, android-assets, xtask | 10–12 |
| `ui-tests` / `ui-tests` | nextest, dev profile `debug=0`: `roboco-ui --lib` (serial group) + `roboco` CLI tests | 12–13 |
| `ui-tests` / `mobile-core` | nextest: text, markdown, mobile, mobile-client, engine-client (serial group); syntax mobile subset | 6–8 |
| `ui-tests` / `tailcat-adapter` | Go | 3 |
| `theme-artifact` / `export-check` | unchanged | 2 |
| `windows` / `tests` | one nextest build (CI release profile, LLD, Dev Drive), every Windows-relevant suite | 20–22 |
| `windows` / `app` | adapter → package (one build) → installer → startup script → CLI smoke | 12–15 |
| `android` / `android` (ubuntu) | debug APK + JVM + buildSrc tests | ≈ 16 |
| `android` / `android-emulator` | only on Android-relevant paths | 20–21 |

**Full tier** (push to `main`, tags, `workflow_dispatch`, `ci:full`): the PR tier unconditionally for its paths, plus `android (windows-2022)`, the R8 smoke and the layout bench. Nightly/dispatch: `android-emulator-tablet` + tours, and a `--release` backstop of the Linux core/UI suites. Main runs are never cancelled and are the only cache writers.

Expected after tickets 01–09: **≈ 22–25 min PR wall, ≈ 70 runner-min (≈ 110 with Android)**, down from 76 / 245. Cold ≈ 30–35.

### What the web/browser removal (separate spec) adds

`.scratch/web-removal/` deletes `web-codegen.yml`, `web-engine-client.yml`, the theme `typecheck` job, Node/pnpm in `release.yml`, `ROBOCO_WEB_DIST`, and the in-app browser along with its webkit apt packages. This spec assumes those land. They save ≈ 15 runner-min per qualifying push and ≈ 2 GB of cache, but **0 min of PR wall time**, because none of those jobs is on the critical path. This spec has no tickets for those deletions. Tickets that touch the same lines say they wait for that PR.

## User Stories

1. As a contributor, I see every PR check finish in about 25 minutes (warm), so I can iterate within a working session.
2. As a contributor pushing a follow-up commit, I lose at most ~25 min of superseded CI, not an hour.
3. As a contributor on an Android-only PR, I don't wait for desktop/engine jobs. On an engine-only PR, I don't wait for the emulator, unless I add `ci:full`.
4. As the maintainer, I get the full matrix on every merge to `main`, and those runs are never cancelled by the next merge.
5. As the maintainer, I can see cache health (hit rates, total size under 10 GB) and per-job timings with the scripts in `.scratch/ci-speed/`.
6. As the maintainer, Windows regressions show up on `main` too, not only on PRs.
7. As the maintainer, the actions that run next to the signing secrets are pinned and updated by Dependabot.

## Implementation Decisions

- **Main-only cache writers**: `save-if: ${{ github.ref == 'refs/heads/main' }}` on every `rust-cache`. Each `shared-key` names a dependency graph (`linux-core`, `linux-ui`, `linux-mobile`, `android`, `windows-tests`, `windows-app`), with exactly one saving job per key. Use `add-job-id-key: false` and `cache-workspace-crates: false`. Rust caches in `release.yml` go entirely; `setup-go` caching is off where it only creates tag- or PR-scoped entries.
- **Concurrency**: `cancel-in-progress: ${{ github.event_name == 'pull_request' }}` in every workflow.
- **`CARGO_*` variables are part of the rust-cache key**, so every job that shares a key sets the same ones at job level (upstream #864 lesson).
- **Windows CI profile**: keep `--release` (GPUI release shaders, upstream measured dev 31.5 min vs CI release 23.7). Write `lto=false` and `opt-level=0` for workspace members into `$CARGO_HOME/config.toml` in a composite setup action, so `Cargo.toml` stays untouched and `package-windows.ps1` picks it up. `release.yml` keeps the committed profile.
- **One nextest invocation per graph** (`-P ci`, `.config/nextest.toml`). Serial suites (`roboco-ui`, `roboco-engine-client`) become nextest test groups with `max-threads = 1` instead of separate invocations.
- **The adapter embed is built once per Windows `app` job**, with `ROBOCO_TAILCAT_EMBED` exported at job level before any cargo command runs.
- **Platform reason rule**: a suite runs on Windows only if it has Windows-specific code paths or the desktop links it. `roboco-text`/`roboco-mobile`/`roboco-mobile-client` are Linux + Android only.
- **Android-relevant paths for the PR emulator gate**: `apps/android/**`, `crates/{mobile,mobile-client,text,markdown,engine-client,proto,rpc,xtask,android-assets}/**`, `adapters/roboco-tailcat/**`, the UI font/icon asset dirs, `Cargo.lock`, `rust-toolchain.toml`, `.cargo/**`, `android.yml`. Implemented with a small `changes` job (pinned paths-filter action or a `git diff` script) whose output gates the job.
- **`ci:full` label**: a PR with it runs the full tier (`contains(github.event.pull_request.labels.*.name, 'ci:full')`). Re-run after adding it.
- **Nightly** (`schedule`): `android-emulator-tablet` + tours, and the Linux `--release` backstop. No cache save.
- Upstream intent is carried by hand ([upstream port workflow](../../docs/reference/upstream-ports.md)); upstream's admission/reuse machinery from #1077 is **not** ported (Roboco has 0 queue time).

## Testing Decisions

- Every ticket records a **measured before/after** using the research scripts (`analyze.py`, `cache.py`, `waste.py`) over ≥ 10 runs after merge, compared against the research §2 baseline. A ticket is not done on estimates.
- **Coverage must not shrink silently.** Each restructuring ticket lists the test binaries/filters that ran before and after (nextest `--list` output, or the `cargo test` invocations), with any intentional drop named and justified (dup or no platform reason).
- Workflow changes are exercised on a PR branch **and** on the first main push after merge (cache save on main, restore on the next PR).
- The dev-profile switch on Linux is backstopped by the nightly `--release` job for at least two weeks before anyone proposes removing the backstop.
- Cache budget check after tickets 01/04/06: `gh api repos/hoangvu12/roboco/actions/cache/usage` stays < 9.5 GB with no `refs/pull/*` or `refs/tags/*` Rust entries.

## Out of Scope

- Deleting web/browser code, workflows and steps (owned by `.scratch/web-removal/`).
- Fixing the failing tests themselves (emulator flows, tablet, engine-local storage). Ticket 11 triages and decides on quarantine; the fixes become their own tickets.
- Upstream's admission scheduler, input-fingerprint reuse and merge queue (#1077 beyond the tier idea).
- macOS/iOS jobs (Roboco has none), Play Store or other deploy workflows.
- Larger paid runners. Listed as an open question; revisit after 01–09 land.
- sccache in CI, cargo-hakari (evaluated in research §6, not recommended).
- `release.yml` restructuring beyond dropping caches, adding timeouts and the web removal.

## Tickets

| # | ticket | blocked by | est. effect | waits for web-removal? |
|---|---|---|---|---|
| 01 | `issues/01-main-seeded-caches.md` | — | miss rate ~60 % → ≲ 10 %; −11…−15 min on a cold Windows run | no |
| 02 | `issues/02-windows-dedupe.md` | — | Windows 75 → ≈ 50 | no |
| 03 | `issues/03-windows-coverage-gaps.md` | 02 | correctness (+≤ 2 min) | no |
| 04 | `issues/04-windows-single-build-split.md` | 01, 02, 03 | Windows wall ≈ 50 → ≈ 22 | no |
| 05 | `issues/05-windows-lld-devdrive.md` | 04 | −2…−3 per Windows job | no |
| 06 | `issues/06-linux-consolidation.md` | 01 | ui-tests 24 → ≈ 13; −40 runner-min | no (leaves `ROBOCO_WEB_DIST`/webkit lines to it) |
| 07 | `issues/07-mobile-bindgen-crate.md` | — | emulator −5…−6 | no |
| 08 | `issues/08-android-tiering.md` | 01 | Android off the critical path for non-Android PRs; −30…−64 runner-min | no |
| 09 | `issues/09-tier-docs-final-measurement.md` | 04–08 | docs, required checks, final numbers | merges with its AGENTS.md edits |
| 10 | `issues/10-pin-actions-dependabot.md` | 04, 06, 08 | security/upkeep | **yes** |
| 11 | `issues/11-red-suite-triage.md` | — | fewer red reruns | no |
| 12 | `issues/12-pin-rust-toolchain.md` | — (with 01) | stops toolchain-driven cache misses (1.99.0/1.99.1 in one run) | no |
| 13 | `issues/13-cache-budget-non-rust.md` | — (with 01) | cache 15.9 → < 9.5 GB: AVD 2.75 GB dropped, one Gradle writer; optional paid limit | no |
| 14 | `issues/14-linux-tests-arm64.md` | 06, 01 | Linux test jobs −20…−25 % (free arm64) | no |
| 15 | `issues/15-windows-tests-arm64.md` | 04, 05, (13) | Windows `tests` 22 → ≈ 17 (PR critical path) | no |
| 16 | `issues/16-emulator-build-before-boot.md` | 07, 08, 13 | emulator job ≈ 20 → ≤ 16 | no |
| 17 | `issues/17-gradle-config-cache-android-setup.md` | — (user: secret) | −2…−4 per Gradle job | no |
| 18 | `issues/18-cargo-timings-profile.md` | — | measurement (per-crate/link time) | no |
| 19 | `issues/19-consolidate-integration-test-binaries.md` | 04, 06, 18 | −2…−5 Windows, −1…−3 Linux test build (estimate) | no |
| 20 | `issues/20-affected-package-selection.md` | 04, 06, 08, 19 | PRs touching leaf crates only (needs-triage) | no |
| 21 | `issues/21-linux-release-glibc-floor.md` | — | correctness (wave-10 A24): tarball loads on Ubuntu 22.04; +1.5–2.5 min per release Linux job | coordinate apt lines |

Tickets 12–21 come from `research-web-2026-10-28.md` (web/GitHub research round 2, 2026-10-28).

Suggested order: 01 + 02 + 07 + 11 in parallel → 03 → 04 → 05; 06 and 08 after 01; then 09; 10 last (after the web-removal PR).

## Open questions

1. **Required checks / branch protection**: should `main` require the PR-tier checks (they are path-filtered, so a skipped workflow must not block)? This needs either an aggregate "CI result" job or no required checks. Owner: user.
2. **Emulator on PRs**: is the path gate acceptable, or should every PR run the emulator (+20 min of runner time per PR, no wall cost when Windows is ≥ 20 min)?
3. **Tablet + tour suite**: nightly until green, or delete the job until the suite is fixed (ticket 11)?
4. **Bigger runners**: worth paying for after 01–09? An 8-vCPU Windows runner would roughly halve the 20-min Windows tests job.
5. **Nightly `--release` backstop**: run it nightly, or on every main push (+≈ 22 runner-min per merge, no PR cost)?
6. **Windows Gradle leg**: main-only plus PRs that touch Gradle/xtask paths, or nightly only?

## User decisions (2026-10-28)

- **No paid anything:** no paid cache limit and no paid or larger runners. Stay within the free 10 GB cache and free runners (arm64 included).
- **Pin the toolchain (ticket 12):** yes, for CI **and** local development. `rust-toolchain.toml` names an exact version (e.g. 1.99.1), so plain `cargo` works locally (no more `cargo +1.99.0`). Dependabot bumps it.
- **Windows on arm64 (ticket 15):** yes for PRs. x64 Windows tests run on every push to `main` and nightly.
- **Required checks:** Windows tests, the UI tests, and Android when Android paths change.
- **Android tablet suite** (never green on main): nightly, not per push. Triage in ticket 11.
- **Release-mode safety build:** nightly.
- **Gradle:** the repository secret `GRADLE_ENCRYPTION_KEY` exists (created 2026-10-28); the move to NDK 30 is approved (ticket 17).
- **A24** (Linux release on Ubuntu 22.04) belongs to this spec (ticket 21).
- Implementation starts only on the user's explicit go.
