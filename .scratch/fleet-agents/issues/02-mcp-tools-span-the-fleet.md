# 02 — MCP tools address every fleet member

**What to build:** In `crates/roboco-mcp`, lift the engine-local restriction (`roboco.rs:413-440`, `tools.rs:670`, `:693`, `:836`) for fleet members.

- `list_devices` lists this engine plus every fleet member (name, id, online/offline).
- Device-addressed tools route to the owning engine: `list_projects`, `list_harnesses`, `list_models`, `create_chat`/`create_chats` (`device`, or a project on that device).
- Chat-addressed tools route by the chat's engine: `read_chat`, `get_chat`, `list_chats` (all members, or filtered by `device`), `send_message(s)`, `interrupt_chat`, `wait_for_turn`, `respond_to_input`, `fork_chat`, `archive_chat`.
- Chat ids from other engines are scoped, so references never collide (the registry's `ScopedId` pattern). Exact titles and unique prefixes still resolve, and an ambiguity across engines is an error that names the devices.
- Transport: decide whether the MCP server asks its own engine to forward over the fleet session (preferred: credentials stay in the engine), or reads credentials over a local-only RPC. Record the choice here.
- Offline member: fail fast with "<device> is offline".
- Update the MCP server instructions text (`crates/roboco-mcp/src/jsonrpc.rs:32`, the "engine is device-local" paragraph) and the tool descriptions (`tools.rs:51`).

**Blocked by:** 01

**Status:** ready-for-agent

**Parent:** `.scratch/fleet-agents/spec.md`

- [ ] Loopback MCP tests: `list_devices` shows members; `create_chats` with a remote `device` creates the chat on that engine; read/send/interrupt/wait route correctly
- [ ] Scoped ids don't collide; cross-engine title ambiguity is an error naming the devices
- [ ] An offline member fails fast (bounded time) with plain text
- [ ] Local-only behaviour is unchanged when there's no fleet (existing MCP tests green)

## Comments
