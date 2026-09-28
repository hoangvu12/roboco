# 01 — Engine: steering never kills the turn; queued attachments resolve

**What to build:** Port the engine half of upstream #592: `kick_drains`
re-evaluates the command queue AND the message queue after an upload commit
(roboco's `doc_host.rs:637-643` currently drains commands only — the exact
pre-fix bug); a queued row carrying `pending://` image refs is held in order
until its bytes land (UploadCommit re-drains) and dispatches resolved local
paths; the host holds a steer for a live turn that has no mailbox instead of
falling through to an interrupting dispatch (`live_run_steerable` in
`sessions.rs` + the routing that uses it).

**Blocked by:** None.

**Status:** ready-for-agent

**Upstream SHAs:** `5017efa9` (engine files only: `crates/engine/src/doc_host.rs`,
`crates/engine/src/sessions.rs`, `crates/engine/tests/e2e.rs`,
`crates/engine/tests/queued_attachments.rs`). Skip every hunk in
`apps/ios/**`, `crates/client/**`, `crates/mobile/**` — the mobile Steer
button and peer-link upload behavior are out of scope; only the host-engine
semantics above port.

**Verification budget:** `cargo check -p roboco-engine -j 3`; targeted
nextest: `test(queued_attachments) or test(message_queue) or test(steer)` in
engine; the box quirk list applies (passwd shim for uid tests; engine
accounts suite is parallel-flaky — rerun failures isolated). No proto
changes → no wiregen.

- [ ] kick_drains drains commands + queue after upload commits
- [ ] pending:// rows held until bytes land, dispatched resolved
- [ ] steer for a live turn without a mailbox holds instead of interrupting
- [ ] Ported tests green (queued_attachments, e2e steer hunks)
- [ ] Port commit records upstream SHA + exclusions
