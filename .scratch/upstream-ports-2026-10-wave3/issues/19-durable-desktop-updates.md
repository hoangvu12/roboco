# 19 — Durable desktop updates (roboco-adapted #595)

**What to build:** Carry #595's hardening onto Roboco's diverged
`crates/update` (keep our manifest+sha256 feed, WindowsPortable kind, and
engine-owned `UpdateStatus`/`ApplyUpdate` RPC — the web strip reads it):
hourly wall-clock schedule with 1m→30m failure backoff (replacing the 6h
monotonic sleep; keep the quiescent/idle auto-apply path); re-check on
window activation; app-level update controller (`crates/ui/src/app_update.rs`)
that checks against THIS binary (report-only role when the app attaches to
another engine); background download feeding the existing `UpdateFlow::Ready`
strip; install-on-quit for staged updates; "Check for updates" in the
account/user menu with a live result; `--version` gate for staged
headless/managed payloads (parity with our Windows gate); unwritable-install
up-front probe; X11 window-close panic fix in the geometry save; Linux
tarball `install.sh` installs into the self-updating `~/.roboco/app/<ver>`
+ `current` symlink layout the updater already expects.

**Blocked by:** None formally; run LAST (touches shell.rs/state.rs heavily —
after 18 minimizes conflicts). Independent of the side-chat line.

**Status:** ready-for-agent

**Upstream SHAs:** `109b39f3` (#595) — read in the mirror worktree;
relevant: `crates/update/src/{lib,windows}.rs`, `crates/ui/src/app_update.rs`
(new), `app_menus.rs`, `shell.rs`, `state.rs`, `lib.rs`,
`apps/zeron/src/{main.rs,update_cli.rs}`, `scripts/package-linux.sh`,
`docs/reference/windows-development.md`. SKIP: macOS-only bits (App
Translocation, disk image, macOS app-menu placement, `package-macos`),
the Inno installer set (ticket 20), upstream README/dist-README wholesale,
upstream's workflow files (adapt notes only). UpdateStatus stays additive
(wiregen + web typecheck). Coordinate engine-side and app-side cadences so
an embedded engine doesn't double-check.

**Verification budget:** `cargo check -p roboco-update -p roboco-engine
-p roboco-ui -j 3`; update-targeted nextest (engine update tests, staged
swaps); ui shell update tests; `wiregen --check` + `pnpm -r build`; scripts
review (linux packaging can run locally; Windows scripts are review-only on
this box).

- [ ] Hourly wall-clock + backoff loop; activation re-check
- [ ] App-level controller + background download + install-on-quit
- [ ] Check-for-updates menu + live result
- [ ] Headless staged `--version` gate; up-front writability probe
- [ ] X11 close panic fix
- [ ] Linux self-updating layout (install.sh)
- [ ] Engine RPC/wire kept additive; wiregen fresh
- [ ] Tests green; port commit records upstream SHA + exclusions
