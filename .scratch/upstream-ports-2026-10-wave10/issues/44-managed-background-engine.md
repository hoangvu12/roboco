# 44 — Background engine: agents keep running when the window closes (A41, core)

**Lane:** 4a Features: engine + agents. **Effort:** M–L. If it runs long,
split it into (a) the engine/CLI launch protocol and (b) the app attach,
loss reporting and packaging, as two commits.

**What to build:** Today the headed app embeds the engine in-process
(`apps/roboco/src/main.rs:287-298`), so quitting kills every running
turn. On Windows, the main platform, there's no other way to keep runs
alive: `roboco daemon` is systemd/launchd only (`apps/roboco/src/daemon.rs:1-4`).
The only detached engine is the Tailcat CLI helper
(`apps/roboco/src/tailcat_setup.rs:110-118`, `ROBOCO_BACKGROUND_ENGINE`).
Port upstream's managed engine:
- The app starts a **detached engine process**, or attaches to a
  running one, through a hidden subcommand. `roboco engine` is taken by
  pairing. Proposal: a hidden `roboco background-engine` plus
  `ROBOCO_MANAGED_ENGINE` and `ROBOCO_APP`, and on Windows a separate
  `roboco-engine.exe` (no console window, own job object, not
  killed with the GUI). Spec question 1 asks for the final name.
- Closing or quitting the window leaves running agents going. The next
  launch re-attaches through the IPC token from ticket 03.
- `managed::launch` / `reap`, a 60 s handoff, `record_engine_loss` with
  the notice "Roboco's engine keeps stopping unexpectedly" when it
  crash-loops, and `ROBOCO_EMBED_ENGINE` to force the old in-process
  mode (tests, debugging).
- Everything that lives in the engine moves with it unchanged: the
  remote-access listener, Tailcat, the fleet/rooms state, previews, and
  the mcp injection. Single-instance rules: one managed engine per data
  dir. Coexist with an existing `roboco daemon` or a headless engine on
  the same data dir: attach, don't spawn a second.
- **Behaviour change:** whether quitting keeps agents running by default
  (with a tray/"engine is running" affordance, or a quit dialog that
  offers "Stop agents too") is spec question 1. Build the mechanism.
  Ship the default the user picks.

**Upstream:** `cc7da5c2a` (#966) slice: `crates/engine/src/managed.rs:1-60`
(`engine_command`, `MANAGED_ENV`, `APP_ENV`, handoff),
`apps/zeron/src/background.rs`, `apps/zeron/src/bin/zeron-engine.rs`,
`apps/zeron/src/main.rs:34-39`, `crates/ui/src/state.rs:421-432,3739`.

**Roboco files:** `apps/roboco/src/{main,daemon,tailcat_setup}.rs`,
new `apps/roboco/src/background.rs` (+ a bin target if a separate exe),
`crates/engine/src/managed.rs` (new), `crates/ui/src/state.rs`,
packaging scripts (`scripts/package-*`, Windows installer) for the
extra binary.

**Excluded:** the update-restart half (ticket 45), engine-posted
notifications (46), and cloud runner enrolment.

**Proto change:** no.

**Blocked by:** 03 (IPC token).

**Status:** ready-for-human (architecture + a default-behaviour
decision; spec question 1)

**Source:** `2026-10-28.md` A41; `pass2-features-desktop.md` N1.

- [ ] Start a long turn, quit the window: the turn finishes; relaunching re-attaches and shows it (Windows + Linux e2e)
- [ ] No console window flashes on Windows; the engine survives the GUI's job object
- [ ] One engine per data dir; attaches to a running daemon/headless engine
- [ ] Remote access, Tailcat, fleet and previews work against the background engine
- [ ] The crash-loop notice appears after repeated losses (test)
- [ ] `ROBOCO_EMBED_ENGINE=1` keeps the in-process mode
- [ ] Commit records the `cc7da5c2a` managed-engine slice

## Comments
