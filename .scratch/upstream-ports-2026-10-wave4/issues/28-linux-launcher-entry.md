# 28 — Linux: harden the launcher entry and icon install

**What to build:** The tarball's `install.sh` copied the shipped
`.desktop` entry verbatim, but `Exec=roboco` and `TryExec=roboco` need
`~/.local/bin` on the desktop session's PATH (often missing on bare
Wayland/fuzzel/rofi setups), and TryExec then hides the entry entirely.
Render the entry with absolute paths through `~/.roboco/app/current`
(stable across in-app updates), reference the icon by path (the only
artwork is 1024x1024, not a size the hicolor theme indexes), honor
`XDG_DATA_HOME`, quote Exec per the Desktop Entry spec, write atomically,
add StartupNotify to the template, keep `StartupWMClass=roboco` matching
the app_id, and refresh the desktop/icon caches only when those tools
exist — only refresh an EXISTING icon cache (a created user-level cache
goes stale and hides other apps' icons). Use a dedicated staging variable
in both installer copies (`sh` has no `local`, so reusing `tmp` overwrote
the download dir the EXIT trap removes). An offline test script
(`scripts/test-linux-desktop-entry.sh`) runs the installer against a fake
release under a throwaway HOME, gives each run its own TMPDIR, requires it
empty, fails if an icon cache appears, and checks the two copies of the
entry-rendering function are identical.

**Blocked by:** None.

**Status:** ready-for-agent

**Upstream SHAs:** `11c91089` (#627) — 8 files. Port: `scripts/package-
linux.sh` (the embedded install.sh is the single source here — upstream's
`edge/src/install.sh` curl-installer copy does not exist in Roboco; carry
its hardening into the one copy and keep the "identical copies" test as a
no-op note or drop that assertion), `dist/roboco.desktop` (StartupNotify),
`dist/README.md`, `scripts/test-linux-desktop-entry.sh` (new). Skip:
`.github/workflows/linux-installer.yml` (CI stays our 7 workflows),
`README.md`/`README.zh-CN.md` hunks (carry the install doc lines into our
README only if the installer section exists). Renames:
`~/.zeron/app/current` → `~/.roboco/app/current`.

**Verification budget:** `bash scripts/test-linux-desktop-entry.sh` (runs
offline under a fake HOME); `shellcheck` if available; release workflow
unaffected (tarball layout unchanged).

- [ ] Entry rendered with absolute paths through ~/.roboco/app/current;
      icon by path; XDG_DATA_HOME honored; atomic write; StartupNotify
- [ ] No icon-cache creation; staging variable fix
- [ ] Offline test script green
- [ ] Port commit records upstream SHA + the edge-copy exclusion

## Comments
