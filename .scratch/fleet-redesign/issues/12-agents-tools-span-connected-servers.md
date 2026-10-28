# 12 — Agents: Roboco tools reach every connected server

**What to build:** in `crates/roboco-mcp` and `crates/engine`, an agent on any server can see, create and drive chats on every connected server. This is rooms story 15 ("agent tooling spans the fleet"), which was never built. (Moved from the deleted `fleet-agents` ticket 02.)

- **Today:** the MCP server only talks to its own engine.
  - `resolve_target` rejects other devices (`crates/roboco-mcp/src/roboco.rs:413-440`, `tools.rs:670`, `:693`, `:836`);
  - `list_devices` reports one row.
- **Engine-forwarded** (upstream's shape, see `../research-agents-2026-10-28.md`):
  - The MCP adds `targetDeviceId`.
  - The engine's fail-closed guard (`crates/engine/src/rpc.rs:1452-1457`) becomes `forward()` over the peer session from tickets 02–03, for **connected servers only**.
  - Port by intent upstream's `forward()`, `forwardable()`, `is_stream_method()` (re-subscribe and pipe), `forward_deadline()` (30 s default, longer for model lists) and link invalidation (`zeron/main:crates/engine/src/rpc.rs:1559-1640, 1975, 2085-2253`).
  - Credentials never leave the engine, and the receiving engine sees its own id, so forwards can't loop.
- **Forward allowlist** (wider than upstream's, because we have no sync):
  - chat ops in `Mutate`;
  - `QueueCommand`, `QueueMessage`, `ForkSideChat` (route by the source chat's host), `AckSideChat`;
  - `WatchChats`, `WatchSessions`, `WatchSpaces`, `WatchDocMessages`, `WatchPreviews`;
  - `ListHarnesses`, `ListModels`, `ListAgentAccounts`.

  Admin, pairing and peer RPCs never forward.
- **Tools:**
  - `list_devices` returns `{id, name, platform, local, online}` for this server plus every connected server (online from ticket 03's link status).
  - Device-addressed tools route to that server: `list_projects`, `list_harnesses`, `list_models`, `create_chat(s)`.
  - Chat-addressed tools route to the chat's server: `read_chat`, `get_chat`, `list_chats` (all, or by `device`), `send_message(s)`, `interrupt_chat`, `wait_for_turn`, `respond_to_input`, `fork_chat`, `archive_chat`.
  - The local engine stays the default when `device` is omitted.
- **Ids:**
  - Outputs give the raw chat UUID plus `deviceId`/`deviceName`.
  - Chat-addressed tools accept an optional `device` or `<chatId>@<device>`.
  - Resolution is a concurrent fan-out over servers' `WatchChats` (2–3 s budget each; offline servers are skipped with a note), with a chat→device cache.
  - An ambiguity across servers is an error that names the devices.
- **Port upstream PR #706 by intent** (`01832f2ca`):
  - host-first resolution, with ambiguity rejected before any write;
  - validation against the host's catalog;
  - MCP-minted chat ids, with createChat idempotent on them;
  - mixed batches with per-request errors and no rollback.
- **Retries reuse the same chat/command ids.** A send never depends on a transcript read (upstream #1076 lesson).
- **Return address:** `QueueCommand`/`QueueMessage` carry `(originDeviceId, originChatId)`. A remote createChat carries `parentDeviceId` next to `parentChatId`, plus `notify`.
- **Offline server:** fail fast with "Linux SG is offline", never a hang.
- Update the MCP server instructions text (`crates/roboco-mcp/src/jsonrpc.rs:32`, the "engine is device-local" paragraph) and the tool descriptions (`tools.rs:51`).

**Prerequisite:** the IPC token (`.scratch/upstream-ports-2026-10-wave10/issues/03-ipc-token-and-sign-in-url-guard.md`). Without it, any local process could drive every connected server.

**Blocked by:** 03; wave-10 03 (IPC token)

**Status:** ready-for-agent

**Parent:** `.scratch/fleet-redesign/spec.md`

- [ ] Loopback MCP tests: `list_devices` shows connected servers; `create_chats` with a remote `device` creates the chat there; read/send/interrupt/wait route correctly
- [ ] Ids never collide; a cross-server title ambiguity names the devices
- [ ] An offline server fails fast (bounded time) with plain text; in a mixed batch the others succeed
- [ ] Single-server behaviour unchanged (existing MCP tests green)
- [ ] A local process without the IPC token is refused

## Comments
