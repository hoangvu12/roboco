# 01 — Port upstream #1023: origin stamping, chat attribution, Ports card (upstream `a9e6c48b7`)

**What to build:** Port `a9e6c48b7` (PR #1023, "Previews: only discover ports Zeron started, and list them beside the chat") across the rebrand per `docs/reference/upstream-ports.md`. The engine stamps `ROBOCO_ORIGIN` (`chat:<id>` for agent runs, `terminal:<id>` for terminals) into the environment of everything it launches (`engine/src/sessions.rs`, `engine/src/terminals.rs`, proto `ORIGIN_ENV`/`chat_origin`/`terminal_origin` — `crates/proto/src/preview.rs`); discovery replaces the `mark_descendants` ancestry walk with marker-based attribution (`resolve`/`attribute`/`origin_marker` in `crates/preview/src/discovery.rs`, Linux `/proc` env read once per process identity, macOS `procargs`); `PreviewService` gains `chat_id` (wire change — **wiregen gate**); the harness gains the agent-CLI control-port reservation (`crates/harness/src/agent_ports.rs`, excludes e.g. `opencode serve`); terminals remember their chat after close; the session card gains the Ports section (`crates/ui/src/shell/session_info.rs` — `SessionPreviews`, `PORTS_INITIAL: 3`, `PORTS_PAGE: 10`, rows per server this chat can preview, Open opens a browser tab; macOS `register_preview` + `add_browser_surface` flow) and `browser/mod.rs` gains `register_preview`. Roboco's existing auth-command exclusion (wave5 ticket 05 port) is preserved.

**Excluded upstream hunks:** `edge/src/preview-room.ts` +2 and `edge/src/preview-room.test.ts` +3 (cloud territory). Refresh `docs/preview-networking.md`'s stale validation section (remove `ROBOCO_PREVIEW_TEST_EDGE` / `--test coordinator` references — removed with signaling) and adapt `docs/PARITY.md`/`ARCHITECTURE.md` hunks to the rebrand.

**Why now:** unlocks honest attribution (user-started servers stop listing), gives previews chat identity (required by ticket 03's authorization scoping), and delivers the visible feature (Ports card).

**Blocked by:** none.

**Status:** ready-for-agent
**Parent:** `.scratch/agent-previews/spec.md`

**Verification budget:** `cargo test -p roboco-preview --test discovery`, `cargo test -p roboco-engine --test previews`, `cargo test -p roboco-ui`, wiregen (`pnpm -r build` gate), clippy.

- [ ] Only listeners carrying a `ROBOCO_ORIGIN` owned by a hosted chat list; user-started servers in a project cwd do not
- [ ] Marker survives `nohup`/`setsid`/double-fork and engine restart; `env -i` launcher attributed via marked ancestor
- [ ] Unreadable process table keeps the previous list; marker read once per (pid, start-time)
- [ ] Agent CLI control ports (e.g. `opencode serve`) excluded via the reservation registry
- [ ] `chat_id` on the wire, TS types regenerated, wiregen gate green
- [ ] Ports card renders beside the chat (3 + paging 10), absent while nothing runs, opens a browser tab
- [ ] Edge hunks verifiably absent; commit records upstream SHA `a9e6c48b7` (#1023)

## Comments
