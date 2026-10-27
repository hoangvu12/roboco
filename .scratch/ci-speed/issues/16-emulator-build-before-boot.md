# 16 — Emulator job: build before boot, then tune the emulator

**What to build:** Today `flow-tests.sh` runs `./gradlew :app:connectedDebugAndroidTest` inside `reactivecircus/android-emulator-runner`, so the whole Rust + Kotlin build happens while the emulator is up. In job 122458315870 the emulator booted at 20:53:22 and tests started at 21:11:37: a 2-vCPU, software-rendered guest (`cores: 2` default, `swiftshader_indirect`) shared the 4-vCPU runner with rustc and kotlinc for 18 min. The instrumented phase then took 12.6 min. Research: `research-web-2026-10-28.md` §4.1. This promotes ticket 08's "optional, measure first" bullet.

1. **Build first, boot second.**
   - Before the emulator step, run `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest` with `ROBOCO_ANDROID_ABIS=x86_64`, using the same Gradle properties `flow-tests.sh` passes that affect task inputs.
   - Also build the fixture engine before the emulator step (already a separate step).
   - Then `connectedDebugAndroidTest` inside the emulator step should find compile/package tasks up to date (check `--console=plain` task outcomes).
   - Note that instrumentation runner arguments (`-Pandroid.testInstrumentationRunnerArguments.*`) must not invalidate the APK tasks. Verify, or pass them so they only reach the test task.
2. **Emulator CPU**: once the build no longer overlaps, A/B `cores: 4` vs 2 for the test phase. The three fixture engines and adb share the host.
3. **ATD image experiment**: A/B `target: google_atd` (or `aosp_atd`), API 36, x86_64, vs `google_apis`. Check `sdkmanager --list` for the exact package. ATD images drop SystemUI and Settings and use less CPU and memory. Adopt only if the flow suite is green and stable over ≥ 5 runs. Flows that touch permission dialogs or system bars may differ; name any that need `google_apis`.
4. **Sharding, only if** the instrumented phase is still > 10 min after 1–3:
   - Split the job into `build` (uploads app + androidTest APKs, fixture binary and Tailcat pieces) and `flow-shard` ×2 (`needs: build`; download, boot, `-Pandroid.testInstrumentationRunnerArguments.numShards=2 -Pandroid.testInstrumentationRunnerArguments.shardIndex=N`).
   - Check that the orchestrator (`ANDROIDX_TEST_ORCHESTRATOR`) and `RobocoTestRunner`'s filters honour `numShards`/`shardIndex`.
   - Each shard pays checkout + boot (≈ 2 min), so it is worth it only above ≈ 10 min.
5. Applies to `android-emulator-tablet` as well (nightly per 08), without sharding.

**Blocked by:** 07 (removes the host bindgen build from this path; measure after it), 08 (job gating), 13 (AVD cache removal changes boot)

**Status:** ready-for-agent

**Parent:** `.scratch/ci-speed/spec.md`

Baseline (R1 §2.3/§2.6): `android-emulator` p50 34.0 min (warm target after 07/08 ≈ 20–21); instrumented phase 12.6 min; boot 0.9 min.

- [ ] Log shows the APK/compile tasks UP-TO-DATE inside the emulator step, and the emulator launch timestamp after the build finished
- [ ] A/B results attached for `cores` 2 vs 4 and google_apis vs ATD (same commit, ≥ 2 runs each); decision recorded
- [ ] Coverage: same instrumented test count before/after (Gradle test report); for sharding, the union of shards equals the unsharded set
- [ ] Measured over ≥ 10 runs: emulator job p50 warm (target ≤ 16 min, from 08's ≈ 20–21), instrumented-phase p50, failure rate not worse than ticket 11's baseline

## Comments
