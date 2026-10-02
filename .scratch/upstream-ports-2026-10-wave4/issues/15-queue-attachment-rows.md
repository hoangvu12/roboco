# 15 — Queue rows: hide attachment filenames, fix hover overflow

**What to build:** Attachment filenames disappear from queued message
rows; thumbnails and the +N chip carry the file or Appshot name as a
tooltip and accessibility label instead. Queue hover backgrounds stay
inside the rounded corners (row inset guarded against the tray's rounded
corners).

**Blocked by:** None.

**Status:** ready-for-agent

**Upstream SHAs:** `66226055` (#650) — 1 file, `crates/ui/src/queue.rs`.
Web parity: queue row rendering in `web/packages/app/src/` — verify
thumbnail tooltip/naming and extend only if a gap exists.

**Verification budget:** `cargo check -p roboco-ui -j 3`; targeted nextest
queue tests; web `pnpm -r build` if touched.

- [ ] Filenames hidden; tooltips/a11y carry names
- [ ] Hover backgrounds clipped to rounded corners
- [ ] Tests green; web gap check recorded
- [ ] Port commit records upstream SHA

## Comments
