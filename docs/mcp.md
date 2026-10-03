# Roboco MCP server

`roboco mcp` serves the Model Context Protocol on stdin/stdout and proxies every
tool into the running engine's localhost IPC (`ws://127.0.0.1:$ROBOCO_IPC_PORT`,
default 27654) — the same `roboco_rpc` surface the headed app and the web client
dial. It is a subcommand of the one `roboco` binary: no Node runtime, no extra
install, a few MB resident.

Crate: `crates/roboco-mcp` (`roboco-mcp`). The protocol layer is hand-rolled
(`initialize`, `ping`, `tools/list`, `tools/call`; newline-delimited JSON-RPC
2.0) — the repo already owns JSON-RPC framing for the Codex and ACP drivers and
the stdio tool-server subset is tiny, so no SDK dependency was taken.

## Identity

The engine can inject this server into a harness's MCP config with the
originating chat in the environment:

| Variable          | Meaning                                                       |
| ----------------- | ------------------------------------------------------------- |
| `ROBOCO_IPC_PORT` | Engine to proxy (default 27654).                              |
| `ROBOCO_CHAT_ID`  | The chat whose agent spawned this server.                     |
| `ROBOCO_DEVICE_ID` | That chat's host device.                                     |

When `ROBOCO_CHAT_ID` is set, every `send_message` is prefixed with a
`[Message from Roboco chat <title> (<id8>) …]` line so the receiving agent and the
human reading that transcript can tell an agent-to-agent message from a typed
one, and the server refuses to message its own chat. The transcript renders
this routing header as "Message from **chat name**", keeping the full routing
instructions in the stored prompt for agents.

### Parent links

`create_chat` and each request in `create_chats` accept `kind`:

| Input | Placement |
| --- | --- |
| `kind: "chat"` | Standalone session, visible in the left **Sessions** sidebar; no `parentChatId` is written. |
| `kind: "side"` | Child of the explicit `parent` or origin chat. Requires one of those. |
| Kind omitted, origin or parent supplied | Child, preserving the previous default. |
| Kind omitted, no origin or parent | Standalone, preserving terminal usage. |

`kind: "chat"` with a nonempty `parent`, `kind: "side"` without an origin/parent,
and unknown kinds fail before any writes. Empty `parent` uses the default.
The creation result includes the effective `kind`, `chatId`, `deviceId`,
`project` and `parentChatId`. Kind is derived from the parent; it is not persisted.

Children record `Chat.parent_chat_id` (proto) ⇄ `parentChatId` on the registry
row (`Mutate createChat { parentChatId? }` → `WorkspaceHost::create_chat_with_parent`).
Chats with a parent cannot create chats through MCP, including standalone and
batch creation or an explicit parent override. A side chat cannot be selected
as a parent; only one level is supported.

For children, `parent` (id, prefix, or title) overrides the origin (`ROBOCO_CHAT_ID`).
`list_chats { parent }` returns children, and chat summaries carry `parentChatId`.
Rows from older engines read as parentless; a dangling parent id is tolerated.

Chats with a parent stay out of the main sidebar (the left sidebar, project
tabs and jump slots require `parent_chat_id == None`); children remain
addressable by id, deep link, and every MCP tool, and `list_chats { parent }`
is how an orchestrator finds them.

### Injection

The host engine stamps this server onto every run it drives
(`RunRequest.mcp`, additive): the same `roboco` binary with `args: ["mcp"]`
and the three variables above, pointed at the port the engine itself serves
(never a port it lost the bind race for). Each driver spells it in its own
dialect and leaves the user's configured servers alone:

| Harness | Where |
| ------- | ----- |
| Claude  | `--mcp-config <inline json>` (no `--strict-mcp-config`)             |
| ACP (Devin, Grok, Hermes, Antigravity) | `session/new` and `session/load` → `mcpServers: [{name, command, args, env}]` |
| OpenCode | Child-only `OPENCODE_CONFIG_CONTENT`: `mcp.roboco` on 1.x, `mcp.servers.roboco` on 2.x |
| Codex   | `thread/start` config overrides `mcp_servers.roboco.{command,args,env}` |
| Cursor  | SDK `Agent.create` / `Agent.resume` → inline `mcpServers.roboco` |
| Pi      | `--extension <bridge>` + the server spec in `ROBOCO_MCP_SERVER` |

OpenCode preserves inherited inline configuration and other servers. Its config
shape follows the installed binary's major version. Cursor uses the SDK's
[inline MCP configuration](https://cursor.com/docs/sdk/typescript); OpenCode's
[1.x config layer](https://opencode.ai/docs/config/) and
[2.x MCP format](https://opencode.ai/v2/docs/mcp-servers) differ.

Pi is the odd one out: its first-party JSONL RPC has no MCP surface — no
`--mcp-config`-style flag, no `mcpServers` settings key, and no runtime
command that attaches a server. The one spawn-time door pi opens is
`--extension`, so the driver writes a small bridge (a plain-JS extension
embedded in the harness crate, `crates/harness/src/pi/mcp_extension.js`)
into a per-run scratch dir and hands pi its path plus the whole server
spec in `ROBOCO_MCP_SERVER`. The bridge spawns `roboco mcp` on stdio,
speaks the five-method MCP subset, and re-exposes every tool through
`pi.registerTool` as `mcp__roboco__<tool>` — the same name shape Claude
uses, so the transcript renders Roboco tool calls as MCP chips for pi
chats too. Registration happens at pi's `session_start` (never in the
factory, and pi startup never blocks on the engine: `roboco mcp` answers
`initialize`/`tools/list` before dialing), tool calls run in pi's
parallel execution mode (`wait_for_turn` may block for minutes), and the
bridge degrades silently — no Roboco tools, run unaffected — if the
scratch file cannot be written or the server never handshakes. Upstream
zeron bridges through a pi-acp `--extension` shim because its Pi harness
rides the community pi-acp adapter; Roboco kept the shim idea but rebased
it onto the native RPC driver that replaced that adapter.

Title runs never carry it. A run with no served port (embedded engine that
lost the bind) gets no Roboco tools rather than a dead server.

### Forks

`ForkSideChat { chatId, sourceChatId, parentChatId? }` copies a chat's
history through its latest completed response into a new chat and appends
a `fork` part (a system entry: `sourceChatId`, `sourceTitle`) as the seam;
the transcript draws it as "This chat was forked from <title>". `parentChatId`
defaults to the source; a side chat's own fork button passes its parent so
the copy lists as a sibling.

## Tools

Chats are referenced by full id, a unique id prefix, or an exact title.
Projects by id, path, display name, or unique path suffix. Devices by id or
name (default: the local engine's device — the engine is device-local, so a
`device` argument must resolve to it).

| Tool               | Engine calls                                              |
| ------------------ | --------------------------------------------------------- |
| `whoami`           | `LocalDevice`, `EngineInfo`, origin chat summary          |
| `list_devices`     | `WatchDevices` snapshot                                   |
| `list_projects`    | `WatchSpaces` snapshot (device filter resolves locally)   |
| `list_harnesses`   | `ListHarnesses` (device arg validates against this engine) |
| `list_models`      | `ListModels {harness}` (device arg validates locally)    |
| `list_chats`       | `WatchChats` + `WatchSessions` snapshots (status merged)  |
| `get_chat`         | above + `WatchDocMessages` opening frame (pending input)  |
| `create_chat`      | `Mutate createChat` (+ `renameChat`; optional first send) |
| `create_chats`     | Concurrent `create_chat` requests with per-request results |
| `send_messages`    | Concurrent `send_message` requests with per-request results |
| `read_chat`        | `WatchDocMessages` opening `reset` frame, rendered        |
| `send_message`     | `QueueCommand` Run / Steer, or `QueueMessage`             |
| `wait_for_turn`    | `WatchSessions` until the chat settles                    |
| `interrupt_chat`   | `QueueCommand` Interrupt                                  |
| `respond_to_input` | `QueueCommand` RespondInput                               |
| `archive_chat`     | `Mutate setChatArchived`                                  |

Watch streams are the engine's only read surface (there is no one-shot "get
transcript" RPC); a snapshot is "subscribe, take the first item, drop" — drop
cancels server-side, exactly what the sidebar does on attach.

`send_message` mode `auto` starts idle chats and steers working chats through
their live mailbox without interrupting tools or child processes. Providers
consume it at their next supported input boundary. Only explicit `queue` mode
creates a held queue row. `awaitingInput` refuses and points at
`respond_to_input`. Quiet, long-running turns still receive steering; the host
falls back to starting a turn if the live runtime has already exited.

`wait_for_turn` after a send is edge-triggered on the `Session` row captured
before the send: it returns on a new `last_completed_turn`, an
`awaitingInput`/`errored` stamp newer than the baseline, or a working→idle
edge. A brand-new chat has no session row until the host picks the run up, so
the wait keeps waiting in that case rather than reporting the unstarted run as
done (this was the one bug the first live run found).

## Selecting a project and device

`list_projects {device?}` filters by device id or exact name; without arguments
it lists all projects. `list_harnesses {device?}` and
`list_models {harness, device?}` validate the device argument — engine-local,
there is exactly one device, and every catalog is this engine's.

For creation, project alone determines the chat's device. Device alone creates
a session without a project on that device. With both, the project is resolved
**within** the selected device, and a project id belonging to another device is
rejected. Neither argument means a projectless session on the local engine.
Repeated names or paths are errors with candidate ids and devices; use ids from
discovery.

Before writing, creation checks the harness catalog, chooses the available
default (Claude Code when available, otherwise the first available non-mock
harness), and validates any explicit model against the catalog. An explicit
harness must be offered, installed and enabled. Catalog failures, including
model lookup failures, are returned with the device id; there is no catalog
fallback.

Discover and launch a standalone session:

```text
list_devices {}
list_projects { device: "<device-id>" }
list_harnesses { device: "<device-id>" }
list_models { device: "<device-id>", harness: "codex" }
create_chat {
  kind: "chat",
  device: "<device-id>",
  project: "<project-id>",
  harness: "codex",
  model: "<model-id from the catalog>",
  title: "Implement feature",
  prompt: "Implement the feature...",
  wait: false
}
```

Without a project (the engine uses the home directory unless `cwd` is given):

```json
{"kind":"chat", "device":"<device-id>", "harness":"codex", "prompt":"Reply with pong", "wait":true}
```

A mixed `create_chats` batch, with an origin chat or explicit top-level parent:

```json
{
  "requests": [
    {"kind":"chat", "device":"<device-id>", "project":"<project-id>", "title":"Feature", "prompt":"Implement the feature"},
    {"kind":"side", "parent":"<parent-chat-id>", "project":"<project-id>", "prompt":"Review the API"}
  ]
}
```

The first prompt and later sends use durable chat commands drained by the host.
Sends remember the session row and existing message ids on the MCP connection
before sending, so a subsequent `wait_for_turn` after `wait:false` waits for
that send, even before a session row appears. A separate MCP server has no send
baseline and reports the chat's current posture. If completion arrives before
the transcript, the wait allows the new assistant response to arrive within the
**same timeout**. Replies are matched by message id, not by `createdAt` (the
host's clock can run behind the caller's), so a previous response is never
substituted for a missing response to a new send. An unfinished transcript or
missing response at the deadline returns `timedOut`.

## Parallel side chats

Use `create_chats` with a `requests` array to launch independent workers in
one tool call, including when the harness executes its tool calls sequentially:

```json
{
  "requests": [
    {"project": "/repo", "title": "Review tests", "prompt": "Review test coverage"},
    {"project": "/repo", "title": "Review API", "prompt": "Review API compatibility"}
  ]
}
```

Use `send_messages` with the same envelope for existing chats; each item uses
`send_message` arguments. Both accept 1–32 requests, run them concurrently,
and return `results` in input order with `index`, `isError`, and either `result`
or `error`. A failed request does not cancel or roll back successful requests.
Each request defaults to `wait: false`; explicit waits also run concurrently.
Only batch independent work, not ordered messages to the same chat. Requests
may mix `kind: "chat"` and `kind: "side"`; guards run per request.

When using individual tools, launch **all** chats/messages with `wait: false`
first, then collect replies with `wait_for_turn`. Waiting on each individual
launch before issuing the next serializes work at the caller, even though
both the MCP transport and engine support concurrent chat runs.

## Smoke recipe

```sh
BIN=target/debug/roboco
{ echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}'
  echo '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_chats","arguments":{"limit":5}}}'
  sleep 5; } | ROBOCO_IPC_PORT=27655 $BIN mcp
```

`create_chat` with `"prompt": "Reply with exactly the word pong", "wait": true`
against a live daemon returns the assistant's `pong` in a few seconds; archive
the chat afterwards with `archive_chat`. Unit tests (`cargo test -p roboco-mcp`)
drive the whole tool set against an in-memory stub `RpcService`.

An isolated stdio instance is available with
`cargo run -p roboco-engine --example mcp_standalone_smoke`. It provides a
temporary project, an origin coordinator and a scripted `codex` adapter
(`smoke-1`) returning `pong`. Discover its ids with the list tools, create
`kind: "chat"` with a prompt and `wait: true`, then check `read_chat`,
`send_message` and a mixed batch. The profile is removed on exit. This checks
MCP dispatch and engine execution without changing your running app's sessions
or requiring provider credentials.

The `mcp_standalone_session_executes_on_this_engine` test in `device_routing`
additionally runs the whole flow against a real assembled engine — standalone
row parentless, the session executed here, id-matched wait replies, side-chat
guards — and pins the fail-closed `targetDeviceId` contract: a target device
that is not this engine is rejected, because engine-local means there is no
other connection to forward to.
