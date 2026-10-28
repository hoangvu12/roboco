# 03 — Side chats on another machine report back to their parent

**What to build:** Orchestration v2's completion notices across engines.

- A chat created through 02 on a remote engine records its parent as (parent engine device id, parent chat id), along with the `notify` flag. The agent-origin stamp on the first user entry names the remote parent.
- When that side chat finishes, fails, stops, is interrupted or needs input on a turn the parent started or steered, the hosting engine delivers the notice to the parent's engine over its fleet session (01). The parent's engine files it like a local notice: idle → immediately, busy → after the current turn, several merged into one (`crates/engine` completion-notice code; `crates/engine/tests/completion_notices.rs` is the reference behaviour).
- Delivery is retried while the parent's engine is offline and dropped after a bounded window, with a log line that carries no secrets.
- `notify: false` delivers nothing.

**From the research (`research-2026-10-28.md`; upstream has no merged cross-device notices, and open PR #1079 is pull-based on synced replicas):**
- **Push plus pull catch-up.**
  - Push: the child's host writes to a durable outbox and calls the parent's engine (e.g. `RecordRemoteOutcome {parentChatId, sideChat:{deviceId, chatId, title}, outcome, noticeId}`). The parent dedupes on a deterministic `noticeId(kind, chat, turn_key)`.
  - Pull: when the parent's link to a child's host comes up, the parent catches up over that host's `WatchSessions` for turns it missed, within a **6 h** window (from #1079).
- **Only delegated turns are reported.** A turn the user typed into the side chat themselves stays private (#1079's `turn.delegator == parent` rule).
- **No cross-machine wall clocks.**
  - Acks (the orchestration-v2 watermark) use the **child host's serial**, returned with `read_chat`/`get_chat`. That way a parent that already read the result gets no duplicate notice.
  - Stop/interrupt suppression uses the parent engine's own record of which dispatches were notifiable. It sees every forwarded `QueueCommand`.
- **Orphans:** a parent chat deleted or archived while an outcome is in flight drops it quietly, as it does locally.
- **Desktop grouping:** check that a child whose parent lives on another engine is neither hidden nor mis-grouped by the desktop's parent/child logic (`visible_chats`-style). Fix it, or file a follow-up.

**Blocked by:** 02

**Status:** ready-for-agent

**Parent:** `.scratch/fleet-agents/spec.md`

- [ ] Loopback test: a remote side chat finishing → exactly one notice on the parent's engine, with the right outcome; busy parent → after its turn; several → merged
- [ ] `notify: false` → no notice; parent engine offline → delivered after it returns (within the window)
- [ ] Local notices unchanged (`completion_notices` suite green)

## Comments
