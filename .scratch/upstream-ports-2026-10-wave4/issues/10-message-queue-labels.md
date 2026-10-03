# 10 — Message queue: command and skill labels

**What to build:** Queued command and skill rows render their real label
(command name / skill name) instead of a generic one. Extract the row
label into a `queue_row_text` helper so the projection is covered by unit
tests alongside the attachment-trailer handling (tests cover command,
skill and file rows).

**Blocked by:** None.

**Status:** ready-for-human

**Upstream SHAs:** `b3d7f48b` (#682) — 1 file, `crates/ui/src/queue.rs`.
**Web parity (deliverable):** `web/packages/app/src/lib/queue-row-logic.ts`
mirrors `queue_visible_text` — port the command/skill label projection
there so desktop and web rows agree (its header already tracks upstream
`queue.rs` line ranges; keep the note current).

**Verification budget:** `cargo check -p roboco-ui -j 3`; targeted nextest
`test(queue_row)`; web `pnpm -r build` + queue row-logic vitest.

- [x] Command/skill/file queue rows labeled correctly (desktop + web)
- [x] `queue_row_text` unit tests green; web row-logic tests updated
      (tests written/ported on both sides; execution deferred to the wave-final
      batched pass — user directive)
- [x] Port commit records upstream SHA

## Comments

- Ported `b3d7f48b` (#682) by intent into `crates/ui/src/queue.rs`: new
  `queue_row_text(text, attachments)` helper — `queue_visible_text` (trailer
  hiding, unchanged) then `composer::sent_mention_display`'s projection, so
  `/compact`, `$skill` and `@file` chips label queue rows the way the
  transcript does instead of showing raw `roboco-invoke:`/`roboco-file:`
  links. The row's `_ =>` label arm calls it; editing/delivery arms and the
  edit-seeding path still read the stored text (canonical links preserved).
- Test `queue_rows_label_commands_skills_and_files` ported verbatim (rebrand:
  `roboco-invoke:`/`roboco-file:`, `roboco_proto`/`roboco_rpc` crates):
  command + skill + file links project to "/compact then $review-pr on
  @queue.rs", the legacy trailer case still hides the filename list, plain
  text collapses to one line.
- Web parity: `src/lib/queue-row-logic.ts` gains `queueRowText` (the same
  projection through `sentMentionDisplay` from `./mentions`) and
  `components/queue-panel.tsx`'s row text uses it; the lib header's helper
  list and line-range notes updated. `tests/queue-row-logic.test.ts` gains
  the mirror describe (command/skill/file projection, trailer hiding).
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
