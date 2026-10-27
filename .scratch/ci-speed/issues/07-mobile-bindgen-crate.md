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

**Status:** ready-for-agent

**Parent:** `.scratch/ci-speed/spec.md`

Baseline (research §2.6, emulator job 122458315870): `uniffi-bindgen` build 6.6 min + host `.so` 0.5 min inside "Run the flow suite", before the x86_64 NDK build (4.0).

- [ ] `cargo build -p roboco-mobile-bindgen` compiles only `uniffi` + its deps (`cargo tree -p roboco-mobile-bindgen` attached)
- [ ] Generated Kotlin is byte-identical before/after for both profiles (`--check` passes; diff of `target/android-core/<profile>/kotlin` empty)
- [ ] `:app:assembleDebug :app:testDebugUnitTest` green on Windows and Linux hosts; release.yml `assembleRelease` path unchanged (dispatch dry run)
- [ ] Measured before/after over ≥ 5 emulator runs: time from "Calculating task graph" to `connectedDebugAndroidTest` start (expect −5…−6 min) and android-emulator job p50

## Comments

**2026-10-28 (web research round 2):** a warm-cache reference point for the acceptance numbers. In `android (ubuntu)` job 122458316176 the `uniffi-bindgen` build took **2 m 08 s** (host `.so` 24 s), against 6 m 35 s cold in the emulator job. Quote both in the before/after. Ticket 16 (build before boot) measures after this one.
