# 10 — Message queue: command and skill labels

**What to build:** Queued command and skill rows render their real label
(command name / skill name) instead of a generic one. Extract the row
label into a `queue_row_text` helper so the projection is covered by unit
tests alongside the attachment-trailer handling (tests cover command,
skill and file rows).

**Blocked by:** None.

**Status:** ready-for-agent

**Upstream SHAs:** `b3d7f48b` (#682) — 1 file, `crates/ui/src/queue.rs`.
Web parity: `web/packages/app/src/lib/queue-actions.ts` renders queue rows
— verify label parity there and extend only if a gap exists.

**Verification budget:** `cargo check -p roboco-ui -j 3`; targeted nextest
`test(queue_row)`; web `pnpm -r build` if touched.

- [ ] Command/skill/file queue rows labeled correctly
- [ ] `queue_row_text` unit tests green
- [ ] Web gap check recorded
- [ ] Port commit records upstream SHA

## Comments
