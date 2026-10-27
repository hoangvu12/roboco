# 45 — Background engine: restart into the new version once idle after an update (A41, update slice)

**Lane:** 4a Features: engine + agents. **Effort:** M.

**What to build:** Once ticket 44 makes the engine outlive the window,
an app update can't simply swap the binary and relaunch everything.
Port upstream's handoff:
- The installed version is recorded in a version file (`VERSION_FILE`,
  `installed_version`).
- A running background engine notices it's older than the installed
  app and restarts into the new image **once it's idle** (no working
  runs, and nothing the user is mid-way through). Upstream calls this
  `set_managed_restart`.
- The app, on launch, attaches to the old engine until that handoff.
  It shows "updating engine…" state, not an error.
- Windows: the swap must not fight a running `roboco-engine.exe`
  (rename-then-replace, as the updater already does for the app).

**Upstream:** `cc7da5c2a` (#966): `crates/update/src/{lib,windows}.rs`
(`VERSION_FILE`, `installed_version`, `set_managed_restart`), the
managed-engine idle check.

**Roboco files:** `crates/update/src/{lib,windows}.rs`,
`crates/engine/src/managed.rs`, `crates/ui/src/app_update.rs`.

**Excluded:** cloud release channels.

**Proto change:** no.

**Blocked by:** 44.

**Status:** ready-for-agent

**Source:** `pass2-features-desktop.md` N1 (updater row).

- [ ] After an update, a busy engine keeps running until idle, then restarts into the new version (test with a fake version file)
- [ ] The app attaches across the handoff without an error state
- [ ] Windows swap test (rename-in-use) passes
- [ ] Commit records the `cc7da5c2a` updater slice

## Comments
