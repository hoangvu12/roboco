# 15 — Queue rows: hide attachment filenames, fix hover overflow

**What to build:** Attachment filenames disappear from queued message
rows; thumbnails and the +N chip carry the file or Appshot name as a
tooltip and accessibility label instead. Queue hover backgrounds stay
inside the rounded corners (row inset guarded against the tray's rounded
corners).

**Blocked by:** None.

**Status:** ready-for-agent

**Upstream SHAs:** `66226055` (#650) — 1 file, `crates/ui/src/queue.rs`.
**Web parity (deliverable):** `web/packages/app/src/lib/queue-row-logic.ts`
(`queueAttachmentSummary`) + `components/queue-panel.tsx` — drop the
filename text from web rows, carry names as `title`/aria on thumbnails
and the +N chip, keep the rounded-corner hover inset intact.

**Verification budget:** `cargo check -p roboco-ui -j 3`; targeted nextest
queue tests; web `pnpm -r build` + row-logic vitest.

- [ ] Filenames hidden; tooltips/a11y carry names (desktop + web)
- [ ] Hover backgrounds clipped to rounded corners (both)
- [ ] Tests green; web row-logic tests updated
- [ ] Port commit records upstream SHA

## Comments
