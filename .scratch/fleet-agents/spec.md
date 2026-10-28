# Fleet agents: agents create and drive chats on every machine in the fleet

Status: ready-for-agent (starts after `.scratch/fleet-merge/` tickets 01–02)
Spec date: 2026-10-28
Origin: rooms spec user story 15 (`.scratch/rooms/spec.md:83-84`): "As an MCP user, I want the MCP server to hold room memberships and address any member engine, so agent tooling spans the fleet like the desktop does." No rooms ticket carried it, so it was never built. Fleet follow re-landed the rooms core without it. The user tried it on 2026-10-28 and expected it to work.

## Problem Statement

An agent's Roboco tools (`crates/roboco-mcp`) talk to their own engine only:

- The MCP server is a thin RPC client of its own engine (`crates/roboco-mcp/src/roboco.rs:1-23`).
- `resolve_target` rejects any device other than its own (`roboco.rs:413-440`, `tools.rs:670`, `:693`, `:836`).
- `list_devices` reports one row.

The engine has no session on any other engine. Only the desktop and phone apps hold those. So an agent on the PC can't start a chat on Linux SG, even though the fleet exists so that all of a user's machines work as one.

## Solution

Every fleet member engine opens its own sessions to the other members with the fleet secret it already retains. The MCP tools address any member by device.

1. **Engine-to-engine fleet sessions.**
   - Each engine in a fleet presents the retained fleet secret at every book member's `POST /room/present` (the existing path, `rooms-v2`+). The result is an ordinary, individually revocable session on that member, labelled e.g. "Linux SG engine (fleet)".
   - The engine dials book addresses the way clients do: LAN directly, Tailcat through the engine's adapter.
   - The minted credentials are kept in the engine's pairing database (a `fleet_peer_sessions` table, owner-only, same protection as the fleet secret). They are refreshed on book changes and dropped when the engine leaves the fleet.
2. **MCP tools span the fleet.**
   - `list_devices` lists every fleet member, with online/offline.
   - `list_projects`, `list_harnesses`, `list_models`, `create_chat(s)`, `send_message(s)`, `read_chat`, `get_chat`, `list_chats`, `interrupt_chat`, `wait_for_turn`, `respond_to_input`, `fork_chat` and `archive_chat` route by device or chat to the owning engine over its fleet session.
   - Chat references from other engines are scoped, so ids never collide (the registry's `ScopedId` pattern, `crates/engine-client/src/registry.rs:34-64`).
   - The local engine stays the default when `device` is omitted.
3. **Side chats report back across machines.**
   - A side chat created on SG by a chat on the PC records its parent as (PC device, chat).
   - When it finishes, fails, stops or needs input, SG delivers the completion notice to the PC over SG's fleet session. The PC files it like a local notice (orchestration v2 rules: idle → immediate, busy → after the turn, merged).
   - `notify: false` is respected.
4. **Data stays put.** Each chat lives entirely on the engine that hosts it. Nothing is synced or copied. `read_chat` reads over the session, like a client does (ADR 0004 holds).
5. **Docs.** An ADR 0011 amendment ("engines are clients of fellow fleet members") and ADR 0004's note that this is control over a session, not sync. `docs/mcp.md` and the MCP server instructions text are updated ("The engine is device-local…" changes).

## User Stories

1. As the user, an agent in a chat on my PC creates a side chat on Linux SG (`create_chats` with `device: "Linux SG"`), and it runs there.
2. As the user, that agent gets the side chat's completion notice when it finishes, and reads the result with `read_chat`, exactly as for a local side chat.
3. As the user, `list_devices` from any agent shows all my fleet machines, with which are online.
4. As the user, an offline member makes the call fail with a plain error ("Linux SG is offline"), never a hang.
5. As the user, I can see each engine-to-engine session in a member's session list and revoke it. A machine I left out of the fleet (opt-out + `room leave`) is never reachable this way.

## Implementation Decisions

### Settled by the user
- This is the intended behaviour of the fleet (rooms story 15; reaffirmed 2026-10-28: "that's literally one of the reasons why we made this fleet").

### Decided in this spec (overrule in a ticket comment before building, never silently)
- **No extra per-machine switch.** Membership is the switch: a machine in the fleet accepts fleet sessions; a machine left out (opt-out + `room leave`) does not. Agents on any member can drive any member. The ADR amendment says so plainly.
- **Engine-forwarded, upstream's shape** (research §TL;DR item 2). The MCP adds `targetDeviceId`. The engine's fail-closed guard (`crates/engine/src/rpc.rs:1452-1457`) becomes a `forward()` over the fleet session, for fleet members only, with an explicit allowlist, stream re-subscription and per-method deadlines. Credentials never leave the engine process.
- **Forward allowlist** (wider than upstream's, because we have no sync):
  - chat ops in `Mutate`;
  - `QueueCommand`, `QueueMessage`, `ForkSideChat`, `AckSideChat`;
  - `WatchChats`, `WatchSessions`, `WatchSpaces`, `WatchDocMessages`, `WatchPreviews`;
  - `ListHarnesses`, `ListModels`, `ListAgentAccounts`.

  Admin, pairing and room RPCs never forward.
- **Agent-facing ids:**
  - outputs give the raw chat UUID plus `deviceId`/`deviceName`;
  - chat-addressed tools also accept an optional `device`, or `<chatId>@<device>`;
  - resolution is a concurrent fan-out over members' `WatchChats`, with a chat→device cache;
  - the opaque `ScopedId` stays client-side only.
- **Members are identified by `EngineInfo.deviceId`,** not by address or label. Sessions are minted lazily (first use, or a book change), reused, and revoked on re-mint.
- **Return addresses:** `(originDeviceId, originChatId)` on `QueueCommand`/`QueueMessage`, and `parentDeviceId` beside `parentChatId` on a remote createChat.
- **Notices: push plus pull catch-up.**
  - The child's host pushes from a durable outbox, and the parent dedupes by a deterministic `noticeId`.
  - The parent pulls missed outcomes when its link to the child's host comes up.
  - Only delegated turns are reported, within a 6 h window.
  - Acks use the child host's serials, and Stop suppression uses the parent's own dispatch records. Never cross-machine wall clocks.
- **Idempotent retries:** createChat on the MCP-minted chat id, QueueCommand on its command id. Sends never depend on a transcript read.
- **Prerequisite: the IPC token** (wave-10 ticket 03). Roboco's loopback IPC has no credential today (`crates/engine/src/listener.rs:43-47`). With forwarding, any local process could otherwise drive every fleet member.
- Only fleet rooms grant this. Plain rooms keep the verifier-only invariant and never hold secrets at rest, so they can't.
- Sessions are labelled with the presenting engine's device name, so a member's session list shows which machine holds them.

## Testing Decisions

- **Engine**: two or three loopback engines in one fleet (the existing `crates/engine/tests/rooms.rs` harness). Each mints fleet sessions to the others. Leaving the fleet revokes and drops them. Opt-out/leave blocks new ones. Secrets and credentials never appear in logs or `Debug`.
- **MCP** (`crates/roboco-mcp` tests against loopback engines):
  - `list_devices` shows members;
  - `create_chats` on a remote device creates the chat there;
  - `read_chat`/`send_messages`/`interrupt_chat` route correctly;
  - scoped ids don't collide;
  - an offline member fails fast.
- **Completion notices**: a remote side chat finishing delivers one notice to the parent on the other engine; `notify: false` delivers none; busy → after the turn.
- **Manual**: on the user's machines after fleet merge, an agent on the PC runs a side chat on Linux SG and one on the Laptop and gets both notices.

## Out of Scope

- Syncing or mirroring chats between engines (ADR 0004 holds).
- Agents reaching engines outside the fleet (plain rooms, hand-paired-only engines).
- A UI for agent-created remote chats beyond what the desktop already shows: they appear because the desktop is paired to those engines.

## Tickets

| # | ticket | blocked by |
|---|---|---|
| 01 | `issues/01-engine-fleet-peer-sessions.md` | fleet-merge 01, 02; wave-10 03 (IPC token) |
| 02 | `issues/02-mcp-tools-span-the-fleet.md` | 01 |
| 03 | `issues/03-cross-engine-completion-notices.md` | 02 |
| 04 | `issues/04-adr-and-docs.md` | 01–03 |
| 05 | `issues/05-final-verification.md` | 01–04 |

One PR, branch `feature/fleet-agents` off `main`, after the fleet-merge PR.

Research: `research-2026-10-28.md` (how upstream does it through WorkOS + its cloud, and what carries over). The decisions above already include its corrections.

## User decisions (2026-10-28)

- Agents creating and driving chats on other fleet machines is a core purpose of the fleet (rooms story 15). The user expected it to work today. Build it right after fleet merge.
