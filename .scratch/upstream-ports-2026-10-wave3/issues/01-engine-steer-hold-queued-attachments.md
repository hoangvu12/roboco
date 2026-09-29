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

## Comments

**Branch:** `wave3/01-engine-steer-hold` → merged `Merge wave3/01` (ports/2026-10-wave3). Commit `a606ecfe` (4 files, +292/−12): kick_drains drains the queue; pending:// rows held via arm_attachment_wait; dispatch_queued resolves landed refs (resolve_attachment_refs) and rejects still-missing; held_until_turn_end gains the unsteerable-turn hold; sessions.rs live_run_steerable; ported tests steer_into_unsteerable_live_turn_holds_instead_of_interrupting + queued_row_waits_for_attachment_bytes_then_sends_resolved_paths.

**Verification:** cargo check engine clean; nextest binary(queued_attachments) or binary(message_queue) or test(steer) 36/36; adjacent suites 57/57; new tests 3× isolated stable. Post-merge re-verified: 36/36.

**Judgment calls:** hold-gate extended Roboco's catalog-based held_until_turn_end by intent (upstream's defers_to_turn_end precision noted as future alignment); "mobile steer"→"remote steer" framing; EngineCore::assemble 3-arg adaptation; iOS/client/mobile hunks skipped.
