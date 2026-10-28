# 02 — MCP tools address every fleet member

**What to build:** In `crates/roboco-mcp`, lift the engine-local restriction (`roboco.rs:413-440`, `tools.rs:670`, `:693`, `:836`) for fleet members.

- `list_devices` lists this engine plus every fleet member (name, id, online/offline).
- Device-addressed tools route to the owning engine: `list_projects`, `list_harnesses`, `list_models`, `create_chat`/`create_chats` (`device`, or a project on that device).
- Chat-addressed tools route by the chat's engine: `read_chat`, `get_chat`, `list_chats` (all members, or filtered by `device`), `send_message(s)`, `interrupt_chat`, `wait_for_turn`, `respond_to_input`, `fork_chat`, `archive_chat`.
- Chat ids from other engines are scoped, so references never collide (the registry's `ScopedId` pattern). Exact titles and unique prefixes still resolve, and an ambiguity across engines is an error that names the devices.
- Transport: decide whether the MCP server asks its own engine to forward over the fleet session (preferred: credentials stay in the engine), or reads credentials over a local-only RPC. Record the choice here.
- Offline member: fail fast with "<device> is offline".
- Update the MCP server instructions text (`crates/roboco-mcp/src/jsonrpc.rs:32`, the "engine is device-local" paragraph) and the tool descriptions (`tools.rs:51`).

**From the research (`research-2026-10-28.md`; supersedes the transport and id bullets above):**
- **Engine-forwarded (decided).** The MCP adds `targetDeviceId`. In the engine, replace the fail-closed guard (`crates/engine/src/rpc.rs:1452-1457`) with `forward()` over the fleet session (01), for fleet members only. Port upstream's shape by intent: `forward()`, `forwardable()`, `is_stream_method()` (re-subscribe and pipe), `forward_deadline()` (30 s default, longer for model lists) and link invalidation (`zeron/main:crates/engine/src/rpc.rs:1559-1640, 1975, 2085-2253`). The receiving engine sees its own id, so forwards can't loop.
- **Allowlist** (wider than upstream's):
  - chat ops in `Mutate`;
  - `QueueCommand`, `QueueMessage`, `ForkSideChat` (route by the *source* chat's host), `AckSideChat`;
  - `WatchChats`, `WatchSessions`, `WatchSpaces`, `WatchDocMessages`, `WatchPreviews`;
  - `ListHarnesses`, `ListModels`, `ListAgentAccounts`.

  Admin, pairing and room RPCs never forward.
- **Ids:** outputs give the raw chat UUID plus `deviceId`/`deviceName`, not opaque `ScopedId`. Chat-addressed tools accept an optional `device` or `<chatId>@<device>`. Resolution is a concurrent fan-out over members' `WatchChats` (2–3 s budget per member; offline members skipped with a note), with a chat→device cache.
- **`list_devices`** returns `{id, name, platform, local, online}`. Upstream has no online flag; ours comes from 01's status.
- **Port PR #706 by intent** (`01832f2ca`):
  - host-first resolution, with ambiguity rejected before any write;
  - validation against the host's catalog;
  - MCP-minted chat ids, with createChat idempotent on them;
  - mixed batches with per-request errors and no rollback (`zeron/main:crates/mcp/src/tools.rs:786-814, 978-1253`; `zeron.rs:446-515`).
- **Retries reuse the same chat/command ids.** A send never depends on a transcript read: a slow remote read must not fail `send_message` (upstream #1076 lesson).
- **Return address:** `QueueCommand`/`QueueMessage` carry `(originDeviceId, originChatId)`; a remote createChat carries `parentDeviceId` next to `parentChatId` and `notify`.

**Blocked by:** 01

**Status:** ready-for-agent

**Parent:** `.scratch/fleet-agents/spec.md`

- [ ] Loopback MCP tests: `list_devices` shows members; `create_chats` with a remote `device` creates the chat on that engine; read/send/interrupt/wait route correctly
- [ ] Scoped ids don't collide; cross-engine title ambiguity is an error naming the devices
- [ ] An offline member fails fast (bounded time) with plain text
- [ ] Local-only behaviour is unchanged when there's no fleet (existing MCP tests green)

## Comments
