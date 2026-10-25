# 04 — Engine update ownership + Claude credentials fallback (upstream #970, `f725384bd`)

**What to build:** Port `f725384bd`: (1) a superseded engine stops instead
of relaunching itself into an update while the app runs — the app's
reconnect starts the new version as its child (`engine/src/managed.rs`,
`crates/update/src/lib.rs`); (2) Claude Code login writes fall back to
`~/.claude/.credentials.json` when the Keychain write fails
(`engine/src/agent_accounts.rs`).

**Port note:** the orphan-successor bug applies to roboco's app-spawned
engine; keep the macOS Keychain rationale out of comments, keep the logic.

**Excluded upstream hunks:** none (all three files are ours).

**Blocked by:** none.

**Status:** ready-for-agent

**Verification budget:** `cargo check -p roboco-engine -p roboco-update` +
restart_resume/managed tests. Source: `.scratch/upstream-drift/2026-10-25.md` § A4.

- [ ] Stop-don't-orphan + credentials fallback ported — **partial: see Comments; the
  orphan-successor half has no Roboco surface to port**
- [x] Commit records upstream SHA `f725384bd` (#970)

## Comments

- **2026-10-25 — ported on `wave9/engine` (the portable parts).**
  - **Credentials fallback (ported in full):** `write_claude_credentials` now
    falls back to `~/.claude/.credentials.json` (owner-only) when the Keychain
    write fails and no item is visible — Claude Code's own fallback — and
    errors with the `claude` → /login recovery when an existing item can't be
    updated (it would shadow the file). Keychain test seam
    (`SECURITY_OVERRIDE` / `security_bin`) and the macOS test
    `claude_login_falls_back_to_the_file_when_the_keychain_refuses` ported.
    **Untested surface:** the whole Keychain path is `#[cfg(target_os =
    "macos")]` and Roboco CI has no macOS runners — the port is
    compile-unverified here (Linux) and must be reviewed/run on macOS by a
    human.
  - **Update-lib seam (ported):** `set_managed_restart_ready` + the
    `MANAGED_RESTART_READY` gate composed into `SupersededRestart::poll`
    (checked every pass, default open) — upstream's generic readiness gate
    for a superseded service restart.
  - **Stop-don't-orphan (impossible as written):** Roboco has no
    `engine/src/managed.rs` and no engine self-relaunch — the desktop app
    EMBEDS its engine in-process (`EngineHandle::bootstrap` →
    connect-or-embed), and the headless daemon's restart goes through
    `restart_service()` (systemd `--user restart` / `launchctl kickstart`),
    so the successor is always the service manager's child, never an
    orphan the old engine spawned. Upstream's `app_starts_engine`/
    `app_running` proc_listallpids bundle scan and the stop-instead-of-
    relaunch closure would be untestable dead code on all three Roboco
    platforms. The seam above is carried so a future restart owner can hold
    a restart back; today nothing registers it (behavior identical to
    upstream's unset default).
- **Verification:** `cargo test -p roboco-update` — 19 passed (incl. the new
  `managed_restart_gate_defaults_open_and_can_be_registered` and the
  unchanged `superseded_service_restarts_once_when_quiet`);
  `cargo check -p roboco-engine -p roboco-update` clean (11 pre-existing
  warnings, none in touched files); `cargo test -p roboco-engine --test
  restart_resume` — 13 passed, 1 ignored. The macOS-gated Keychain test
  cannot run on this Linux host (no macOS CI either) — left for human
  verification.
