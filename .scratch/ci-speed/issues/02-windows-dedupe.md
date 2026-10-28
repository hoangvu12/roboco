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

**Status:** implemented on branch `ci/caches-windows` (2026-10-28); timings are measured after merge

**Parent:** `.scratch/ci-speed/spec.md`

Baseline (research §2.4): `tests` p50 75.0 min (63.5–82.4). Packaging 8.3, Build application 9.1 warm / ≈ 20 cold, Mobile core crates 11.7, host bindings 9.2, duplicate steps ≈ 1.6.

- [ ] Packaging step's cargo build finishes in < 1 min on a run where the tests already built `roboco` (log shows no `Compiling roboco-engine` in packaging), and no later step recompiles `roboco-engine` — confirm on the first CI run; locally (run A below) packaging compiled nothing and the next test build recompiled only `roboco` and `roboco-update` themselves (their test units)
- [x] `package-windows.ps1` without the new switch still builds the adapter itself (release.yml path unchanged); dry-run evidence from a `release.yml` `workflow_dispatch` or a local run — local run B below (no `release.yml` dispatch was run)
- [x] Coverage list before/after: every removed test invocation is named as a duplicate or as having no Windows reason (table under Comments)
- [ ] Measured before/after over ≥ 5 green runs: `tests` p50 (target ≤ 52 min warm) — measure after merge (baseline p50 75.0 min, research §2.4)

## Comments

**2026-10-28 (implementation, branch `ci/caches-windows`):**

- **Embed once.** New step "Build the Tailcat adapter (embedded by every cargo build below)" right after nextest is installed, before the first cargo command. It runs the script's own adapter build (`CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -mod=readonly -trimpath -o target\tailcat\x86_64\roboco-tailcat.exe ./cmd/roboco-tailcat`) and writes `ROBOCO_TAILCAT_EMBED=<absolute path>` to `$GITHUB_ENV`. That variable is not `CARGO_*`/`RUST*`, so it does not enter the rust-cache key.
- **Script switch.** `scripts/package-windows.ps1` gains `-AdapterPath`, which defaults to `$env:ROBOCO_TAILCAT_EMBED`. If it is set, the script checks that it is a fully qualified path to an existing file, uses it verbatim (another spelling of the same path would be a different env value to `build.rs`), takes the architecture from the adapter's PE header, so the existing "roboco.exe is X but the adapter is Y" guard still compares two real binaries, and skips the Go build. If it is unset (`release.yml`, local use), nothing changes: rustc host → Go build into `target/tailcat/<arch>/`. The script now restores the caller's `ROBOCO_TAILCAT_EMBED` after the build instead of always removing it. In a reused pwsh session the old `Remove-Item` silently dropped a caller's value. The CI `run:` line is unchanged (the env default covers it), which keeps the web-removal branch's `-SkipWebBuild` edit on that line conflict-free.
- **Deleted steps:** "Build application" (packaging's `cargo build --release -p roboco` is the build), "OpenCode wire integration tests", "Native harness integration tests", "Mobile core host build and bindings".
- **"Mobile core crates" → "Desktop-linked mobile core crates":** `-p roboco-markdown -p roboco-engine-client` only. `cargo tree --locked -p roboco -e normal,build -i <crate> --target x86_64-pc-windows-msvc` (2026-10-28): `roboco-markdown` and `roboco-engine-client` are in the desktop graph; `roboco-text`, `roboco-mobile`, `roboco-mobile-client` and `roboco-android-assets` "did not match any packages".
- **`paths`** (PR and push): `!crates/text/**`, `!crates/mobile/**`, `!crates/mobile-client/**`, `!crates/android-assets/**` after `crates/**`.
- Lint: `actionlint` 1.7.12 is clean on `windows.yml`.

Coverage before → after (`tests` job, every cargo invocation in order):

| # | before | after | why |
|---|---|---|---|
| 1 | harness `--features native-fixture --lib --test codex_availability --test windows_native` | same | — |
| 2 | harness `--features native-fixture --test opencode --test cursor_shim --test pi_rpc` | same | — |
| 3 | `cargo build --release -p roboco` ("Build application") | **removed** | duplicate: packaging (4) runs the same build, now with the same env |
| 4 | `package-windows.ps1` (adapter + `cargo build --release -p roboco` with the embed) | same script, adapter reused from the early step | — |
| 5 | installer install/uninstall | same | — |
| 6 | `gpui_windows --lib layout` | same | — |
| 7 | `roboco` + `roboco-update` (all targets) | same | — |
| 8 | `roboco-proto --lib device_paths`; engine `--test worktree_on_run worktree_location_` | same | — |
| 9 | engine + rpc + harness `--lib` | same | — |
| 10 | preview `--lib --test discovery` | same | — |
| 11 | text + markdown + mobile + engine-client + mobile-client | markdown + engine-client | text/mobile/mobile-client: no Windows reason (not linked by the desktop); Linux `mobile-core` and android.yml run them |
| 12 | `cargo xtask android-core --skip-ndk --check` | **removed** | duplicate of android.yml's `android (windows-2022)` leg, which runs the same xtask host path (.dll naming, .exe bindgen, Kotlin generation) through Gradle |
| 13 | harness `--test opencode` | **removed** | exact duplicate of (2): `native-fixture` only adds the fixture binary and gates `windows_native.rs`, not `opencode.rs` or lib code |
| 14 | harness `--features native-fixture --test codex_availability --test windows_native` | **removed** | exact re-run of (1) |
| 15 | engine `--test codex_catalog` | same | — |
| 16 | engine `--test engine_local --test local_first --test local_profiles --test project_actions_rpc` | same | — |
| 17–18 | ui `--lib` native font geometry; ui `--lib` headless `-j 1` | same | — |
| 19 | CLI startup without HOME | same | — |

**The embed now reaches every test step, and no Windows-run test depends on it.** The engine is compiled with `cfg(roboco_tailcat_embedded)` in every step, as the shipped Windows binary is, so `tailcat::adapter_path()` would unpack the embedded adapter instead of reporting "not found". The only tests that call it are `tailcat::tests` (`#[cfg(all(test, unix))]`, including `real_adapter_carries_bytes_over_tailcat`) and `apps/roboco/tests/tailcat_invite_cli.rs` (`#![cfg(unix)]`), so neither is compiled on Windows. Checked: `cargo test --release -p roboco-engine --lib real_adapter_carries_bytes_over_tailcat` with the embed on this Windows host selects 0 of 452 tests.

Local verification (Windows dev host, PowerShell 7.6.9 portable, rustc 1.99.1 from the ticket-12 pin, `CARGO_PROFILE_RELEASE_LTO=false` + `CARGO_INCREMENTAL=0` + placeholder `ROBOCO_WEB_DIST` as in windows.yml; other agents' builds shared the host, so wall times are inflated):

- **Run A (CI path):** the adapter was built with the workflow step's commands and `ROBOCO_TAILCAT_EMBED` was set to it. A1 `cargo build --release --locked -p roboco` (standing in for the earlier steps) took 21m44s cold on 1.99.1. A2 ran `package-windows.ps1 -SkipWebBuild` with no `-AdapterPath`, so the env default applied: its cargo build printed **no `Compiling` line** ("Finished … in 1m 06s", fingerprint checks and replayed warnings on a loaded host). It produced `roboco-0.14.0-windows-x86_64.exe` and `.zip`, and afterwards the caller's `ROBOCO_TAILCAT_EMBED` was still set. A3 `cargo test --release --no-run -p roboco -p roboco-update -v` compiled only `roboco-update` and `roboco` (their test units; 49 s) with no `Dirty` line, so **`roboco-engine` was not rebuilt**.
- **Run B (release.yml path):** with no env and no `-AdapterPath`, the script built the adapter itself (its mtime moved), embedded it (engine/ui/roboco recompiled for the new adapter file, 2m45s) and left `ROBOCO_TAILCAT_EMBED` unset afterwards. A relative `-AdapterPath` is refused before any build ("must be the absolute path of a built roboco-tailcat.exe").
- Both runs stopped at the installer step, `Inno Setup 6 (ISCC.exe) is required` (it is not installed on this host). That code is unchanged and runs in CI, where the image ships ISCC.
