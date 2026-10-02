# 10 — Message queue: command and skill labels

**What to build:** Queued command and skill rows render their real label
(command name / skill name) instead of a generic one. Extract the row
label into a `queue_row_text` helper so the projection is covered by unit
tests alongside the attachment-trailer handling (tests cover command,
skill and file rows).

**Blocked by:** None.

**Status:** ready-for-agent

**Upstream SHAs:** `b3d7f48b` (#682) — 1 file, `crates/ui/src/queue.rs`.
**Web parity (deliverable):** `web/packages/app/src/lib/queue-row-logic.ts`
mirrors `queue_visible_text` — port the command/skill label projection
there so desktop and web rows agree (its header already tracks upstream
`queue.rs` line ranges; keep the note current).

**Verification budget:** `cargo check -p roboco-ui -j 3`; targeted nextest
`test(queue_row)`; web `pnpm -r build` + queue row-logic vitest.

- [ ] Command/skill/file queue rows labeled correctly (desktop + web)
- [ ] `queue_row_text` unit tests green; web row-logic tests updated
- [ ] Port commit records upstream SHA

## Comments
