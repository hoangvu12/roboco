# 15 — Queue rows: hide attachment filenames, fix hover overflow

**What to build:** Attachment filenames disappear from queued message
rows; thumbnails and the +N chip carry the file or Appshot name as a
tooltip and accessibility label instead. Queue hover backgrounds stay
inside the rounded corners (row inset guarded against the tray's rounded
corners).

**Blocked by:** None.

**Status:** ready-for-human

**Upstream SHAs:** `66226055` (#650) — 1 file, `crates/ui/src/queue.rs`.
**Web parity (deliverable):** `web/packages/app/src/lib/queue-row-logic.ts`
(`queueAttachmentSummary`) + `components/queue-panel.tsx` — drop the
filename text from web rows, carry names as `title`/aria on thumbnails
and the +N chip, keep the rounded-corner hover inset intact.

**Verification budget:** `cargo check -p roboco-ui -j 3`; targeted nextest
queue tests; web `pnpm -r build` + row-logic vitest.

- [x] Filenames hidden; tooltips/a11y carry names (desktop + web)
- [x] Hover backgrounds clipped to rounded corners (both)
- [x] Tests green; web row-logic tests updated (tests written/ported on both
      sides; execution deferred to the wave-final batched pass — user
      directive)
- [x] Port commit records upstream SHA

## Comments

- Ported `66226055` (#650) by intent into `crates/ui/src/queue.rs`: rows no
  longer print the attachment-filename summary line — `queue_row`'s text
  column collapses to the single truncated label row. `PANEL_PAD_X=4` +
  `PANEL_PAD_TOP=4` inset the rows inside the tray (GPUI clips children
  rectangularly; the row's own side pad shrinks to `ROW_PAD_X − PANEL_PAD_X`
  so content keeps its distance) so the rounded hover/editing wash stays
  inside the tray's rounded top corners. Thumbnails gain the attachment's
  row label (file name or "{app} Appshot" from `queue_attachment_labels`)
  as a `QueueActionTooltip` tooltip (350 ms delay) and "Preview {label}"
  aria; the +N chip's tooltip/aria names every hidden attachment via the new
  `queue_hidden_attachments_label` ("2 more: a · b"), replacing the anonymous
  count-only aria.
- Tests ported: `row_hover_corners_stay_inside_the_panel_curve` (geometric
  guard: row-corner reach ≤ PANEL_RADIUS − border) and
  `overflow_chip_names_every_hidden_attachment`.
- Web parity: `queue-row-logic.ts` — `queueAttachmentSummary` removed (the
  desktop row's second line is gone), `queueHiddenAttachmentsLabel` added;
  `queue-panel.tsx` — single-line row text, thumbnails take a `label` prop
  (native `title` + uniform `Preview {label}` aria), +N chip carries
  `title`/aria from the hidden-attachment names; `app.css` — `.queue-panel`
  gains the 4px inset padding, `.queue-row` side pad 8→4px,
  `.queue-row-summary` rule removed. `QUEUE_PANEL_PAD_TOP` stays 0 with an
  updated note (the web panel-y is list-relative, measured below the pad).
- Exclusions: none — upstream's diff is queue.rs only; the web side is the
  ticket's named parity deliverable (upstream had no web change).
- Verification: `pnpm -r build` from web/ (tsc --noEmit + vite, clean);
  `cargo check -p roboco -j 3` at chunk end; test execution deferred to the
  wave-final batched pass (user directive).

- Wave-final batched verification (2026-10-03, merged main `cf94f415`): one
  batched pass over all lanes — ui lib 1521/1521; engine 529/530 (the one
  failure is the documented pre-existing
  `previews::preview_watch_follows_the_session_checkout_and_owning_device`
  baseline); harness 504/509 (the five failures are the documented
  environmental `#!/usr/bin/python3` fixture shebang and uid-1001
  user-database quirks; CI runs them); mcp 26/26; voice 18/18; theme 31/31;
  `wiregen --check` and `roboco-theme-export --check` fresh; web `pnpm -r
  build` green, app vitest 2122/2122, engine-client vitest green. The
  deferred test-execution criterion is demonstrated; closed by the
  wave-final pass.
