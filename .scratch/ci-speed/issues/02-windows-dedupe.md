# 02 — Windows job: remove the hidden rebuild, the duplicate steps and Android-only crates

**What to build:** Cheap removals in `.github/workflows/windows.yml` (`tests` job), before restructuring it in 04.

- **One build with the embed.** Today `package-windows.ps1` sets `ROBOCO_TAILCAT_EMBED`. `crates/engine/build.rs:74` declares `rerun-if-env-changed=ROBOCO_TAILCAT_EMBED`, so packaging rebuilds the engine and app (≈ 8 min), and "Application and updater tests" rebuilds them again without the variable. Fix:
  - Build the Go adapter in an early step (same command as the script) and export `ROBOCO_TAILCAT_EMBED` to `$GITHUB_ENV` before the first cargo command, so every cargo invocation sees the same value.
  - Let the script reuse it: a small `-AdapterPath`/"skip adapter build if `ROBOCO_TAILCAT_EMBED` is set" switch in `scripts/package-windows.ps1`, which must keep its release behaviour.
  - Delete the separate "Build application" step; packaging's `cargo build --release -p roboco` is the build.
- **Delete duplicate steps**:
  - "OpenCode wire integration tests": `--test opencode` already runs in "Portable harness integration tests".
  - "Native harness integration tests": an exact re-run of step 1.
- **Android-only crates leave Windows**: "Mobile core crates" keeps `-p roboco-engine-client -p roboco-markdown`, which the desktop links (`cargo tree -p roboco -i`). Drop `roboco-text`, `roboco-mobile` and `roboco-mobile-client`; Linux `mobile-core` and the Android jobs cover them.
- **Delete "Mobile core host build and bindings"** (`xtask android-core --skip-ndk --check`, 9.2 min). `android.yml`'s `android (windows-2022)` leg runs the same xtask host path (.dll naming, .exe bindgen, Kotlin generation) through Gradle. Ticket 08 keeps that leg on main and on xtask/Gradle PRs.
- `paths`: add `!crates/text/**`, `!crates/mobile/**`, `!crates/mobile-client/**`, `!crates/android-assets/**` after `crates/**`.

**Blocked by:** none (land after or alongside 01; same file)

**Status:** ready-for-agent

**Parent:** `.scratch/ci-speed/spec.md`

Baseline (research §2.4): `tests` p50 75.0 min (63.5–82.4). Packaging 8.3, Build application 9.1 warm / ≈ 20 cold, Mobile core crates 11.7, host bindings 9.2, duplicate steps ≈ 1.6.

- [ ] Packaging step's cargo build finishes in < 1 min on a run where the tests already built `roboco` (log shows no `Compiling roboco-engine` in packaging), and no later step recompiles `roboco-engine`
- [ ] `package-windows.ps1` without the new switch still builds the adapter itself (release.yml path unchanged); dry-run evidence from a `release.yml` `workflow_dispatch` or a local run
- [ ] Coverage list before/after: every removed test invocation is named as a duplicate or as having no Windows reason
- [ ] Measured before/after over ≥ 5 green runs: `tests` p50 (target ≤ 52 min warm)

## Comments
