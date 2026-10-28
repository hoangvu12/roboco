# 14 — Agents: ADR and docs

**What to build:** (moved from the deleted `fleet-agents` ticket 04)

- **ADR 0013** (written in ticket 10) gains an "Agents" section. It covers:
  - agents on any server can create and drive chats on every connected server, by engine-forwarded control over the peer session;
  - why on-behalf-of control is acceptable for engines although the 2026-10-25 research rejected it for clients: attribution is per presenting engine and revocable, and agents aren't clients;
  - the forward allowlist;
  - that loopback IPC trust becomes cross-machine, which is why the IPC token is a prerequisite.
- **ADR 0004:** controlling a chat on another server is not sync. The only cross-server durable state is the child host's notice outbox and the parent's dedupe ledger.
- **`docs/mcp.md`:** device-addressed tools, `<chatId>@<device>`, offline errors, cross-machine notices. Port #706's examples (standalone, projectless, mixed batch) and add device-addressed ones.
- **`docs/reference/remote-access.md`:** a short "Agents across your devices" section.

**Blocked by:** 10, 12, 13

**Status:** ready-for-agent

**Parent:** `.scratch/fleet-redesign/spec.md`

- [ ] No doc still says the MCP tools are device-local without the "connected servers" qualification
- [ ] ADR 0013 and ADR 0004 updated; `docs/mcp.md` examples run against a two-engine loopback setup

## Comments
