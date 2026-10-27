# 23 — Forks: copied subagent chips settle; a fleet fork opens at once with progress, error and Retry (A7, A46)

**Lane:** 1d Fixes: sidebar + chat. **Effort:** M.

**What to build:**
1. **A7 (#1045): a fork copies running subagent chips that spin
   forever.** The copy loop at `crates/engine/src/rpc.rs:1959-1965` only
   resolves `Input` parts. Add the arm that turns
   `MessagePart::Tool { subagent_status: Running }` into `Failed` (or
   upstream's settled state) in the copy. Check Roboco's other fork paths
   for the same copy: orchestration-v2 `fork_chat`, fork-from-reply, and
   the Codex thread fork (`41ff18d03`). Tests go in
   `crates/engine/tests/side_chats.rs` (already in `ui-tests.yml`).
2. **A46: forking on another fleet engine.** Today nothing opens until
   that engine answers. Meanwhile every other fork or new side chat
   anywhere is silently ignored: one global `side_chat_creating` flag
   (`shell.rs:1833`, `fork_dialog.rs:137`). A failed fork only shows a
   passing notice with no Retry. Port the local shape of #1038:
   - `PendingFork { params, host, error, running }` per side-chat tab,
     with `run_fork` / `retry_fork` / `finish_fork` / `draft_child_row`
     in `shell/side_chats.rs`. The tab's empty state reads "Forking on
     {host}…" or "Couldn't fork on {host}", with the engine's reason and
     a ghost Retry. Retry replays the same client-minted `chatId`
     (already `uuid::Uuid::new_v4()` at `side_chats.rs:83`), so it's
     idempotent. Scope the draft row with `engine_registry::scope_chat`.
   - Replace the global creating flag with per-request state.
   - `TranscriptLoading::Opening` plus a delayed loader for a chat that
     isn't hosted by the local engine and hasn't replayed yet. Map
     upstream's edge states onto Roboco's per-engine `EngineConnectionState`
     (`settings/devices.rs:41`).

**Upstream:** `ae1ec6ad8` (#1045); `00cc2e059` (#1038) local slice.

**Roboco files:** `crates/engine/src/rpc.rs` (+ other fork paths),
`crates/engine/tests/side_chats.rs`,
`crates/ui/src/shell/{side_chats,fork_dialog,session_lanes}.rs`,
`crates/ui/src/shell.rs`, `crates/ui/src/state.rs`,
`crates/ui/src/transcript.rs`.

**Excluded:** #1038's edge connectivity (`Syncing`/`Stalled`/`Offline`
from edge `ConnectivityState`) and remote-link composer notices. This
is fork feedback on desktop, not the side-chat surface redesign
declined in wave-9 23.

**Proto change:** no (`FORK_SIDE_CHAT` unchanged).

**Blocked by:** 01.

**Status:** ready-for-agent

**Source:** `2026-10-28.md` A7, A46; `part-07.md` `ae1ec6ad8`;
`pass3-skips-2.md` G1.

- [ ] A fork taken mid-subagent shows settled chips and lanes rows (side_chats test), on every fork path
- [ ] A fleet fork opens a tab at once that reads "Forking on {host}…"; failure shows the reason + Retry; Retry is idempotent (UI tests with a fake slow engine)
- [ ] Two forks in flight on different engines both complete (test)
- [ ] A slow remote chat shows the delayed "Opening…" loader
- [ ] Commit records `ae1ec6ad8` and `00cc2e059` (local slice)

## Comments
