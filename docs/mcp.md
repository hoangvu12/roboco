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

A chat created through `create_chat` records the creating chat as its parent:
`Chat.parent_chat_id` (proto) ⇄ `parentChatId` on the registry/workspace chat
row (`Mutate createChat { parentChatId? }` → `WorkspaceHost::create_chat_with_parent`).
Chats with a parent cannot create chats through MCP, including batch creation
or an explicit parent override. A side chat cannot be selected as a parent;
only one level of side chats is supported.

The default is the origin chat (`ROBOCO_CHAT_ID`); an explicit `parent` argument
(id, prefix, or title) overrides it. `list_chats { parent }` returns a chat's
children, and every chat summary carries `parentChatId`. The field is additive
and serde-defaulted: rows written by older engines read as parentless, and a
dangling id (parent deleted) is tolerated rather than cascaded.

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
| Cursor  | SDK `Agent.create` / `Agent.resume` → inline `mcpServers.roboco`, plus `local.settingSources: ["user", "team", "mdm", "plugins"]` so `~/.cursor/mcp.json` and plugin servers load (not `project`: the SDK skips MCP approvals, so repo-defined servers would run unprompted) |
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
name (default: the local engine's device).

| Tool               | Engine calls                                              |
| ------------------ | --------------------------------------------------------- |
| `whoami`           | `LocalDevice`, `EngineInfo`, origin chat summary          |
| `list_devices`     | `WatchDevices` snapshot                                   |
| `list_projects`    | `WatchSpaces` snapshot                                    |
| `list_harnesses`   | `ListHarnesses`                                           |
| `list_models`      | `ListModels {harness}`                                    |
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
Only batch independent work, not ordered messages to the same chat.

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
