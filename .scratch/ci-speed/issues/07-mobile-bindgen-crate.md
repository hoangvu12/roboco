# 07 — `roboco-mobile-bindgen` crate; skip the host core when only NDK ABIs are needed

**What to build:** Carry upstream `684becf0f`'s `crates/mobile-bindgen` (as `crates/mobile-bindgen`, package `roboco-mobile-bindgen`, bin `uniffi-bindgen`) by hand, and use it in `crates/xtask/src/main.rs:129-186`.

- New crate:
  - one dependency, `uniffi = { version = <same as roboco-mobile>, features = ["cli"] }`
  - `fn main() { uniffi::uniffi_bindgen_main() }`
  - a workspace member
  - The version must match roboco-mobile's `uniffi`, because the generator reads its metadata. Add an xtask test or check that fails if they drift.
- xtask step 1 builds `-p roboco-mobile-bindgen` instead of `-p roboco-mobile --bin uniffi-bindgen --features bindgen,…`. Remove the `bindgen` feature/bin from `roboco-mobile` if nothing else uses it.
- When the host library is not needed, the host core build is skipped. Today `--skip-host` is opt-in; the emulator path (`connectedDebugAndroidTest`, `ROBOCO_ANDROID_ABIS=x86_64`) still builds the host core because bindgen reads from it. Decide where the switch lives:
  - Gradle's `AndroidCoreBuild` passes `--skip-host` for tasks that do not run Robolectric, or
  - the xtask generates bindings from the first NDK `.so` when `--skip-host` is set (it already supports that).

  Do not change what JVM unit tests load.
- Update `docs/reference/android-development.md` if it names the bindgen command.

**Blocked by:** none

**Status:** in review (PR `ci/mobile-bindgen`); measurement after merge

**Parent:** `.scratch/ci-speed/spec.md`

Baseline (research §2.6, emulator job 122458315870): `uniffi-bindgen` build 6.6 min + host `.so` 0.5 min inside "Run the flow suite", before the x86_64 NDK build (4.0).

- [x] `cargo build -p roboco-mobile-bindgen` compiles only `uniffi` + its deps (`cargo tree -p roboco-mobile-bindgen` attached)
- [x] Generated Kotlin is byte-identical before/after for both profiles (`--check` passes; diff of `target/android-core/<profile>/kotlin` empty). Host path, locally. The NDK-`.so` path is checked by CI (see the comment).
- [ ] `:app:assembleDebug :app:testDebugUnitTest` green on Windows and Linux hosts; release.yml `assembleRelease` path unchanged (dispatch dry run). **CI only** (no Android SDK locally). The release path *does* change: it now skips the host core (see below), so the dispatch dry run is the proof.
- [ ] Measured before/after over ≥ 5 emulator runs: time from "Calculating task graph" to `connectedDebugAndroidTest` start (expect −5…−6 min) and android-emulator job p50. **Measure after merge.** Baseline: `uniffi-bindgen` build 6.6 min cold (6 m 35 s, emulator job 122458315870) / 2 m 08 s warm (android ubuntu job 122458316176), host `.so` 0.5 min / 24 s, then the x86_64 NDK build 4.0 min.

## Comments

**2026-10-28 (web research round 2):** a warm-cache reference point for the acceptance numbers. In `android (ubuntu)` job 122458316176 the `uniffi-bindgen` build took **2 m 08 s** (host `.so` 24 s), against 6 m 35 s cold in the emulator job. Quote both in the before/after. Ticket 16 (build before boot) measures after this one.

**2026-10-28 (implementation, branch `ci/mobile-bindgen` from `6126c05d6`):** carries upstream `684becf0f`'s `crates/mobile-bindgen` by hand.

*What landed:*
- `crates/mobile-bindgen` (package `roboco-mobile-bindgen`, bin `uniffi-bindgen`, `uniffi = { version = "0.32", features = ["cli"] }`, `fn main() { uniffi::uniffi_bindgen_main() }`) is a workspace member. `Cargo.lock` gains only that package.
- xtask step 1 builds `-p roboco-mobile-bindgen`, always at the `mobile` profile. The generator's own optimisation doesn't change its output, and it shares no artifacts with the core anymore. Removed: `roboco-mobile`'s `bindgen` feature, its `[[bin]] uniffi-bindgen` and `src/bin/uniffi-bindgen.rs`. Nothing else used them (grep across workflows, scripts, docs, Gradle).
- Drift check: `crates/xtask/src/uniffi_lock.rs` reads `Cargo.lock` and fails when `roboco-mobile` and `roboco-mobile-bindgen` resolve different `uniffi` entries. The lock writes `uniffi` with one version and `uniffi <ver>` with several, so equal entries mean the same version. It runs at the start of every `android-core` and as tests: the workspace lock passes, a synthetic drift fails, and a missing package or dependency fails.
- `--skip-host` now reads the bindings from the **first requested ABI's** unstripped cargo output (`target/<triple>/<profile>/libroboco_mobile.so`, triple via the new `ndk::target_triple`, shared with `tailcat.rs`). Before, the arm64 path was hard-coded, which would break `ROBOCO_ANDROID_ABIS=x86_64`.
- New `--check` guard: when a run builds both the host library and the NDK libraries, the xtask generates the bindings a second time from the first ABI's `.so` and fails if any byte differs. Every `android (ubuntu|windows)` Gradle job (`assembleDebug` + `testDebugUnitTest`) therefore proves that the skip-host builds ship the bindings the JVM tests load.

*Where the skip-host switch lives: in Gradle (option 1), using the xtask's existing skip-host input (option 2).* Only Gradle knows whether an invocation will run a JVM test, so the decision can't sit in the xtask. `buildSrc` `HostCore.needed(taskNames)` decides from `gradle.startParameter.taskNames` (configuration-cache safe, the same idiom as `unitTestOnly`). It skips the host core only when **every** requested task is a known APK/device task: `assemble*` (not `*UnitTest`), `bundle*`, `install*`/`uninstall*`, `connected*`, `clean`. Everything else keeps it: an empty request, `test`, `check`, `build`, abbreviations, `--tests`, unknown names. `build.gradle.kts` sets `buildHost = (buildType == "debug" && HostCore.needed(...))`. Release and benchmark never build a host core: their unit tests are disabled, and `Test` tasks depend only on `buildDebugCore` and load `debugHostDir`. `AndroidCoreBuild.buildHost` is an `@Input`, so a test run after an APK-only build reruns the task and gets the host library. `-ProbocoSkipCore`'s completeness check requires the host library only when one is built. **What JVM unit tests load is unchanged:** any invocation that may run them still builds the same debug host core into the same `host/` directory. Effect per CI invocation:
  - `android-emulator` / `-tablet` (`flow-tests.sh` → `:app:connectedDebugAndroidTest`, x86_64): no host core, bindings from the x86_64 `.so`. This is the ticket's −5…−6 min.
  - `android` job (`assembleDebug testDebugUnitTest :buildSrc:test`): host core as before. It gains the cheap generator crate (no feature-unified rebuild of the core) and the identity guard.
  - R8 smoke, `release.yml` `assembleRelease`, `assembleBenchmark`: no host core. The bindings come from the first ABI's `mobile-dist` `.so`, which keeps its symbol table (`strip = "debuginfo"`), and the generator is built at `mobile`, not thin-LTO.
  - `windows.yml` `cargo xtask android-core --skip-ndk --check`: unchanged (host path).
  - `crates/mobile-bindgen` is added to `AndroidCoreBuild`'s `sources`.

*Excluded from 684becf0f:* everything else in that commit: the Android app itself (Roboco's `apps/android` is its own line), `scripts/android/build-core.sh` (Roboco uses the xtask; no bash on the build path), the R2/zeron.sh publishing workflow, landing-page downloads, icon scripts, and `uniffi.toml` renames (already present or diverged). Upstream also kept `crates/mobile`'s own bindgen bin for iOS; Roboco ships no iOS, so it goes.

*Local evidence (Windows, `cargo +1.99.0`, shared 20-thread box, so the timings are contended and not a measurement):*
- `cargo build -p roboco-mobile-bindgen`: OK. `cargo tree -p roboco-mobile-bindgen -e normal --depth 2`:
  ```
  roboco-mobile-bindgen v0.14.0
  └── uniffi v0.32.2
      ├── anyhow, camino, cargo_metadata, clap
      ├── uniffi_bindgen v0.32.2, uniffi_core v0.32.2
      ├── uniffi_macros v0.32.2 (proc-macro), uniffi_pipeline v0.32.2
  ```
  72 unique crates in total, and the only workspace crate in the graph is `roboco-mobile-bindgen` itself (no `roboco-*` core crates).
- Byte identity: `cargo xtask android-core --skip-ndk --check --profile <p> --out target/{base,head}-kotlin/<p>`, run on base `6126c05d6` and on head; `diff -r` of the `kotlin/` trees was empty for **both** `mobile` and `mobile-dist`. The `roboco_core.kt` sha256 was `96cac7a9bab4724696ddcc8cd755294e58a111791e5589869c486a63c105eae1` in all four runs, and `--check` passed.
- Base `mobile-dist` illustrates the removed cost: the old feature-gated `uniffi-bindgen` bin linked the whole core at thin LTO and cgu=1 (**23 m 56 s**), and only then was the host lib built (4 m 34 s). On head the generator builds at `mobile` and was reused (2.8 s), and the host lib took 8 m 45 s. Base `mobile`: bin 9 m 13 s + lib 1 m 47 s. Head `mobile`: generator 10 m 01 s (cold for this crate's feature set, contended) + lib 2 m 07 s.
- `cargo test -p roboco-xtask`: 44/44 (4 new in `uniffi_lock`). `rustfmt --check` is clean on the new files and touched hunks. clippy reports nothing in new code; the pre-existing xtask fmt/clippy debt in `android_licenses.rs`, `elf.rs`, `kotlin.rs`, `sizes.rs`, `tailcat.rs` and `main.rs` is left alone.
- `gradlew -p buildSrc test`: green, including the new `HostCoreTest` (3 cases: the CI invocations, unknown/empty requests, APK/device tasks).

*CI only:* configuring `app/build.gradle.kts` (needs the SDK), `android (ubuntu|windows)` with the identity guard, the emulator flow suite on bindings from the x86_64 `.so`, the main-push R8 smoke, and a `release.yml` dispatch dry run. Measurement after merge, ≥ 5 emulator runs, against the baseline above.
