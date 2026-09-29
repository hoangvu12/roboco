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

## Comments

**Branch:** `wave3/20-inno-installer` → merged into main `Merge wave3/20`. Commit `994b2ef5` (11 files, +357/−18): roboco.iss (per-user LocalAppData, HKCU roboco:// handler, uninstall entry; fresh GUID {22BA167B-E6D5-45C5-BA8B-7569B44F6065}), windows.rs version sidecar + UNINSTALL_KEY + refresh_installer_version (between swap_into_place and relaunch), package-windows.ps1 ISCC step → setup.exe (manifest.json gains setup sha entries; install.ps1 stays un-hashed), test-windows-installer.ps1 (CI-gated), release.yml + windows.yml wiring (ARM64 Inno winget fallback), dist + windows-development docs. Post-merge re-verified: update 17/17, both workflow YAMLs parse.

**CI-gated (explicit):** Inno compile, silent install/uninstall smoke, registry refresh on real updates, ARM64 winget fallback, windows-gated AppId↔UNINSTALL_KEY test. Release surface: two new assets + manifest entries + guard — sign-off list in the commit body.
