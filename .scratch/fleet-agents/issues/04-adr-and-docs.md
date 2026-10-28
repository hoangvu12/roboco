# 04 — ADR amendment and docs: fleet members are clients of each other

**What to build:**

- An ADR 0011 amendment (dated): fleet member engines hold ordinary, revocable sessions on each other (`fleet_peer_sessions`), and agents on any member can create and drive chats on any member. It covers why (rooms story 15) and that membership is the only switch. It also lists what is unchanged: chats stay engine-local, nothing syncs, plain rooms can't do this.
- ADR 0004: a one-line note that controlling a chat on another engine over a session is not sync.
- `docs/mcp.md`: device-addressed tools, scoped ids, offline errors, cross-machine notices.
- `docs/reference/remote-access.md` (fleet section) and CONTEXT.md (fleet).
- `.scratch/rooms/spec.md`: a comment that story 15 is delivered by this spec.

**Blocked by:** 01–03

**Status:** ready-for-agent

**Parent:** `.scratch/fleet-agents/spec.md`

- [ ] ADR amendments, `docs/mcp.md`, remote-access, CONTEXT.md updated; rooms story 15 cross-referenced
- [ ] No doc still says the MCP tools are device-local without the fleet qualification

## Comments
