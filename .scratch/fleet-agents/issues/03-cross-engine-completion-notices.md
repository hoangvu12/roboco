# 03 — Side chats on another machine report back to their parent

**What to build:** Orchestration v2's completion notices across engines.

- A chat created through 02 on a remote engine records its parent as (parent engine device id, parent chat id), along with the `notify` flag. The agent-origin stamp on the first user entry names the remote parent.
- When that side chat finishes, fails, stops, is interrupted or needs input on a turn the parent started or steered, the hosting engine delivers the notice to the parent's engine over its fleet session (01). The parent's engine files it like a local notice: idle → immediately, busy → after the current turn, several merged into one (`crates/engine` completion-notice code; `crates/engine/tests/completion_notices.rs` is the reference behaviour).
- Delivery is retried while the parent's engine is offline and dropped after a bounded window, with a log line that carries no secrets.
- `notify: false` delivers nothing.

**Blocked by:** 02

**Status:** ready-for-agent

**Parent:** `.scratch/fleet-agents/spec.md`

- [ ] Loopback test: a remote side chat finishing → exactly one notice on the parent's engine, with the right outcome; busy parent → after its turn; several → merged
- [ ] `notify: false` → no notice; parent engine offline → delivered after it returns (within the window)
- [ ] Local notices unchanged (`completion_notices` suite green)

## Comments
