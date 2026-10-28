# 13 — Agents: side chats on another machine report back

**What to build:** orchestration v2's completion notices, across servers. (Moved from the deleted `fleet-agents` ticket 03.)

- A chat created through ticket 12 on a remote server records its parent as (parent device id, parent chat id), along with `notify`. The agent-origin stamp on its first user entry names the remote parent.
- When the side chat finishes, fails, stops, is interrupted or needs input on a turn the parent started or steered, its server tells the parent's server over the peer session. The parent's server files the notice like a local one: idle → immediately, busy → after the current turn, several merged into one (reference: `crates/engine/tests/completion_notices.rs`).
- **Push plus pull catch-up** (`../research-agents-2026-10-28.md`):
  - Push: the child's host writes to a durable outbox and calls `RecordRemoteOutcome {parentChatId, sideChat:{deviceId, chatId, title}, outcome, noticeId}`. The parent dedupes on a deterministic `noticeId(kind, chat, turn_key)`.
  - Pull: when the parent's link to the child's host comes up, the parent catches up over that host's `WatchSessions`, within a **6 h** window.
- **Only delegated turns are reported.** A turn the user typed into the side chat themselves stays private.
- **No cross-machine wall clocks.**
  - Acks use the child host's serial, returned with `read_chat`/`get_chat`, so a parent that already read the result gets no duplicate.
  - Stop/interrupt suppression uses the parent's own dispatch records.
- `notify: false` delivers nothing. If the parent chat is deleted or archived, the notice is dropped quietly.
- **Desktop grouping:** check that a child whose parent lives on another server is neither hidden nor mis-grouped by the desktop's parent/child logic. Fix it, or file a follow-up.

**Blocked by:** 12

**Status:** ready-for-agent

**Parent:** `.scratch/fleet-redesign/spec.md`

- [ ] Loopback: a remote side chat finishing → exactly one notice on the parent's server, with the right outcome; busy parent → after its turn; several → merged
- [ ] `notify: false` → none; parent server offline → delivered after it returns (within the window); no duplicate after `read_chat`
- [ ] Local notices unchanged (`completion_notices` suite green)

## Comments
