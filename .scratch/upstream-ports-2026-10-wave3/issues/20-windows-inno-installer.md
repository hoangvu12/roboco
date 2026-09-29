# 20 — Windows per-user Inno Setup installer

**What to build:** A per-user Inno Setup installer for Windows (LocalAppData
install, Start-menu shortcut, `roboco://` deep-link registration, Add/Remove
uninstall entry kept current after in-app updates), built in release CI,
with a silent install/uninstall smoke test script. **This changes the
release process (new setup artifact) — release-surface sign-off expected at
review.** Alternative recorded: extend `install-windows.ps1` with uninstall
entry + deep-link registration instead (less faithful).

**Blocked by:** 19.

**Status:** ready-for-agent

**Upstream SHAs:** `109b39f3` (#595) — `dist/windows/zeron.iss` (→
`dist/windows/roboco.iss`), `scripts/package-windows.ps1` (setup build
step), `scripts/test-windows-installer.ps1` (silent smoke), workflow
release/windows jobs (adapt into Roboco's `release.yml`/`windows.yml`
shapes), `dist/README.md` notes.

**Verification budget:** this box is linux — Windows installer behavior is
CI-gated: `cargo check` where Rust is touched (none expected), review
scripts + Inno file against Roboco's packaging (`scripts/package-windows.ps1`,
`release.yml` manifest/version-guard conventions), and ensure
`windows.yml`/`release.yml` YAML is valid (actionlint if available or
careful review). No local Inno run.

- [ ] roboco.iss (per-user, deep link, uninstall entry)
- [ ] package-windows.ps1 builds setup artifact; release.yml publishes it
- [ ] Silent smoke test script + CI wiring
- [ ] Windows-only verification delegated to CI (explicit in PR notes)
- [ ] Port commit records upstream SHA + release-process note

---

**Handoff from ticket 19 (landed on `wave3/19-durable-updates`):** the update
machinery the installer rides on is already in place and must not be
re-derived:

- `roboco_update::windows::apply(staged, directory, relaunch)` takes the
  relaunch flag (the app controller passes `true` for "Restart to update",
  `false` for install-on-quit); `cleanup_previous_image()` runs at every
  normal `roboco`/`roboco headless` launch (apps/roboco/src/main.rs).
- All staged payloads now pass a `--version` gate, and
  `InstallKind::desktop_update_blocker()` probes install-dir writability
  up front — the installer dir must simply stay writable by the user.
- Upstream windows.rs pieces deliberately NOT ported in 19 (they are this
  ticket's): the `version` sidecar written by `windows::stage` and read by
  `apply` (only consumer is the uninstall-entry refresh), `UNINSTALL_KEY` +
  `refresh_installer_version` (needs `Win32_System_Registry` added to the
  windows-sys features in `crates/update/Cargo.toml`; upstream inserts it in
  `apply` between `swap_into_place` and the relaunch), plus
  `dist/windows/zeron.iss`→`roboco.iss`, the Inno step in
  `scripts/package-windows.ps1` (`Find-InnoSetupCompiler`), the silent smoke
  script, CI wiring, and the dist/README + windows-development.md prose.
- Upstream's uninstall key GUID `{AD5DEC34-E254-467B-8F24-8127EBAF4DA6}`
  belongs to zeron — generate a fresh GUID for Roboco's `roboco.iss`
  AppId; do not carry it over.
- `scripts/install-windows.ps1` (the curl installer) already exists here;
  the README's Windows section already points at it.
