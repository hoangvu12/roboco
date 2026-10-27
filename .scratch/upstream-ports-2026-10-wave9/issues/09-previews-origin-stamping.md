# 09 — Previews: only ports the engine started (upstream #1023, `a9e6c48b7`) — WIRE

**What to build:** Port `a9e6c48b7`: the engine stamps an origin
(`ZERON_ORIGIN` → **`ROBOCO_ORIGIN`**) into everything it launches
(`chat:<id>`, `terminal:<id>`, project actions); preview discovery lists a
listener only when its process (or nearest ancestor) carries a hosted-chat
origin — inherited through nohup/setsid/double-fork, read once per process
identity, scan failures keep the previous list, `opencode serve` excluded.
The session card gains a **Ports card** (ui `shell/session_info.rs` +245).
New `harness/src/agent_ports.rs`; `crates/preview/src/discovery.rs` rework
(+519) + tests; `proto/preview.rs` +19.

**Excluded upstream hunks:** the two `edge/preview-room*` hunks.

**Blocked by:** none.

**Status:** ready-for-human

**Verification budget:** preview discovery/coordinator tests, engine
previews tests, wiregen. Source: `.scratch/upstream-drift/2026-10-25.md` § A8.

- [x] Origin stamping + discovery + Ports card ported
- [x] wiregen fresh
- [x] Commit records upstream SHA `a9e6c48b7` (#1023)

## Comments

- **2026-10-25 — ported on `wave9/engine`.**
  - proto: `ORIGIN_ENV = "ROBOCO_ORIGIN"` + `chat_origin`/`terminal_origin`
    helpers + `PreviewService.chat_id` (additive, serde-defaulted; wire
    regenerated — `PreviewService.ts`, `wiregen -- --check` fresh).
  - harness: `agent_ports.rs` (new — the `opencode serve` reservation so
    discovery never lists the agent's own server) + `RunControls.origin` +
    `apply_origin()`. **Adaptation:** upstream stamps the origin through its
    per-run env map on `RunRequest` (roboco's has none), so the origin rides
    `RunControls` instead — stamped by the engine at dispatch, applied by
    every adapter at spawn (claude/codex/pi/cursor directly; acp's
    `spawn_agent` and opencode's `Server::spawn` thread an `origin` param).
    `opencode serve` reserves its port.
  - preview: `discovery.rs` rework (upstream post-image, rebrand-clean
    pre-state): origins read once per process identity (pid + start time),
    markers inherited through nohup/setsid/double-fork, scan failures keep
    the previous list, `catalog.rs`/`service.rs` — `Scope {chats, terminals,
    reserved_ports}` replaces the bare roots list; a listener counts only
    when an owned origin maps to a chat this engine hosts; a project's
    node_modules copy and unattributed listeners are gone. Roboco's service
    keeps its 1-arg `start` (upstream's signaling param is an edge path
    roboco removed).
  - engine: the projects closure builds `Scope` from the workspace chats +
    remembered terminal chats + reserved ports; terminals stamp
    `terminal:<id>` into every PTY's environment (id minted before the shell
    starts) and remember each terminal's chat past its close
    (`REMEMBERED_TERMINAL_CHATS`); `OpenTerminal` associates the chat (real
    chats only — canvas keys map to nothing); the WATCH_PREVIEWS snapshot
    filter keeps a chat's own servers (roboco-owned + its chat_id or its
    checkout). Project actions ride their terminal's origin (upstream's
    design — verified by the ported test).
  - ui: the Ports card (`session_info.rs` +150: WatchPreviews per chat,
    first 3 ports then "Show N more", scroll + floating rail, click opens a
    browser tab through `register_preview`), `BrowserContext::register_preview`
    (macOS-gated), `preview-fixture` example updated to Scope + origins.
- **Verification:** `cargo test -p roboco-preview` — 30 passed (discovery
  23, leak 4, proxy 1, transport 1; the coordinator test is upstream's edge
  pairing path, removed in roboco's history — excluded); harness `--lib`
  401/402 (the 1 failure is the pre-existing container passwd lookup);
  engine `--test project_action_commands` 2 passed (incl. the ported
  `actions_mark_what_they_start_with_their_terminal`);
  `cargo check -p roboco-ui` clean + `--features browser-fixture --example
  preview-fixture` clean; `wiregen -- --check` fresh. **Pre-existing
  failure, not from this port:** engine `--test previews`
  (`preview_watch_follows_the_session_checkout_and_owning_device`) fails
  identically on the clean tree — its remote-peer `targetDeviceId` watch
  needs a connected peer under roboco's engine-local RPC.
- **Deliberately excluded:** the two edge `preview-room*` hunks (no edge,
  ADR 0003/0004).
- **2026-10-27 — merged with main's agent-previews (PR #33).** Main ported
  the same upstream commit independently (agent-previews ticket 01,
  `b6b7c8aa`), restoring upstream's `RunRequest.env` (serde-skipped both
  ways) + `apply_run_env` instead of this port's `RunControls.origin` +
  `apply_origin`. The merge keeps main's mechanism (upstream's shape, and the
  one the agent-previews tickets build on) and drops `RunControls.origin`, so
  the origin is stamped once, in `drive_run`. The preview crate and
  `proto/preview.rs` take main's files (identical port plus Windows discovery
  and the session relay); the WATCH_PREVIEWS routing of the Ports card through
  the chat's owning engine (`a69ec35c`) is kept.
