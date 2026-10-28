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

**Status:** implemented on `ci/linux-android-tiers` (with 06 and 23, one PR); gate dry-run below; the ≥ 10-run measurement comes after merge

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

**2026-10-29 (implementation, branch `ci/linux-android-tiers`):**

What landed:

- **`changes` job** (u24, sparse checkout of `scripts/ci`, `pull-requests: read`) runs `scripts/ci/android-changes.sh`, a `gh api` script (no third-party action). Outputs `full`, `android_relevant`, `gradle_host`:
  - Off-PR (`push`, `schedule`, `workflow_dispatch`): all true.
  - PR: labels are read **live** (`issues/<n>/labels`), not from the event payload, because a re-run reuses the original payload; so "add `ci:full`, then re-run" works. `ci:full` → all true.
  - Otherwise the PR's files (`pulls/<n>/files`, paginated; a rename counts under both names) are classified with `case` globs.
  - Fails open: an API error or a listing shorter than `changed_files` (the 3000-file cap) sets both path outputs true (`full` stays false).
- **Jobs:**
  - `android (ubuntu-24.04)`: every run (`needs: changes`, `if: !cancelled()`, so a failed `changes` cannot skip it). The R8 smoke, the unsigned release APK and the `android-core-sizes` upload run when `full` (main pushes, nightly, dispatch, `ci:full`).
  - `android (windows-2022)`: its own job, `if: gradle_host`.
  - `android-emulator`: `if: android_relevant`.
  - `android-emulator-tablet`: `schedule` or `workflow_dispatch` only.
  - The two Gradle jobs share `apps/android/scripts/apk-report.ps1` (`-Libraries`, `-Sizes`), so the split does not duplicate the 45 lines of APK checks. The ubuntu job's cargo-ndk/SDK/Gradle steps moved from pwsh to bash; the Windows job keeps pwsh + `gradlew.bat`, which is what it proves.
- **Triggers:** PR `paths` lose `crates/engine/**` and `crates/harness/**` (push keeps them). Both gain the crates the APK links that were missing from the list: `crates/engine-client/**`, `crates/preview-relay/**`, `crates/mobile-bindgen/**` (ticket 07's generator). They also gain `scripts/ci/android-changes.sh`, and `.cargo/config.toml` widens to `.cargo/**`. New `schedule` at 02:47 UTC = the full tier + tablet.
- **Cache writers are main *pushes* only:** emulator (`android` Linux), Windows leg (`android` Windows), and the ubuntu Gradle cache (`cache-read-only` unless a main push). Nightly and dispatch runs on main no longer write. `android (ubuntu)` was already restore-only for Rust; it is now `save-if: 'false'`, stated as such.

Deviations:

1. **The gate's path list is the spec's plus `crates/{doc,syntax,preview-relay,mobile-bindgen}/**`, `scripts/ci/android-changes.sh` and (`gradle_host`) `apps/android/gradle.properties` / `apps/android/**/*.gradle.kts`.**
   - `cargo tree --locked -p roboco-mobile -e normal,build --target aarch64-linux-android` lists the workspace crates the APK links: doc, engine-client, markdown, mobile, mobile-client, preview-relay, proto, rpc, syntax, text. The spec's list misses doc, syntax and preview-relay. The generator crate is newer than the spec.
   - A `*.gradle.kts` anywhere under `apps/android` (`app/build.gradle.kts` included) counts for the Windows leg, not only the root ones.
2. **Acceptance line 1 reads differently after the path trim.** A PR touching only `crates/engine/**` no longer triggers android.yml at all, so neither `android (ubuntu)` nor `ci:full` applies to it. For the full Android tier on such a branch: `gh workflow run android.yml --ref <branch>`. A PR touching only root `Cargo.toml` (still in `paths`, not in the gate) runs `android (ubuntu)` only.
3. The optional "arm64 only on the ubuntu job when the emulator runs" is not wired (runner-minutes only, research-web §4.3).

**Gate verification (dry runs of `android-changes.sh`, 2026-10-29):**

- `--classify` on synthetic lists:
  - engine-only, harness + `crates/ui/src`, desktop app + ui-tests.yml + `scripts/ci/test-core.sh`, root `Cargo.toml` only, `crates/ui/assets/images`, docs only → `android_relevant=false gradle_host=false`.
  - Kotlin, proto, `Cargo.lock`, UI fonts, mobile-bindgen, doc, Tailcat Go → relevant only.
  - `app/build.gradle.kts`, buildSrc, `gradlew.bat`, xtask, android.yml → both.
- Live API path, `GITHUB_EVENT_NAME=pull_request` against real PRs:
  - #38 (118 files, Android) → relevant, not gradle_host.
  - #35 (CI batch 1, touches android.yml) → both.
  - #40 (remote speed, touches `crates/proto`) → relevant.
- Sweep of the last 30 PRs: the emulator would have been skipped on 5 (#15, #25, #27, #37, #41) and the Windows leg would have run on 6 (#19, #23, #31, #35, #36, #39). Most recent PRs are large feature PRs that touch the APK's crates.
- `push`/`schedule` → all true. A claimed 5000-file PR (listing short) and an unreadable PR (#999999) → fail open (both true).
- `ci:full` with a stub `gh` returning the label → `full=true`, everything on. The same engine-only PR without the label → all false.
- Job conditions with those outputs: an Android-path PR whose files classify false runs `changes` + `android (ubuntu)` only (emulator `if` false, Windows `if` false, R8 `if` false, tablet `if` false on `pull_request`).
- This PR touches android.yml, so the gate puts it in both path tiers (emulator + Windows leg run, R8 does not).
