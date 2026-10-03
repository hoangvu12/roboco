# 06 — Queue edit-lease failures land in the composer's notice

**What to build:** Queue edit-lease failures render as the composer's red
failure notice on the desktop; the web routes the same strings to sidebar
toasts. Port the placement, and add the one arm the web silently swallows.

**Desktop truth** (all land in `Composer::failure`, rendered per
composer.rs:7309-7411 as the red notice chip):
- begin: locked → "That queued message is being edited on another device"
  (queue.rs:1460); other Ok → "no longer available" (:1463); Err → "Connect
  to the chat host…" (:1468)
- finish: missing local lease state → "The edit lease was lost; your text is
  still in the editor" (**:1559**); conflict/missing → "…kept locally"
  (:1617, :1621); unknown → "The edit lease changed…" (**:1630**); Err →
  "Couldn't reach the chat host…" (:1636)
- renewal non-renewed → "Edit protection expired; review this message
  before sending" (~:1682), loop breaks, text stays in the editor.

**Web today:** the same strings go to `sidebarNotice.set()` —
queue-panel.tsx:262, :266, :284 (begin) and chat-page.tsx:516, :566, :569,
:572, :578, :583 (renewal/finish). **The :1559 arm never fires:**
chat-page.tsx:541-543 returns silently when `lease === null ||
lease.messageId !== rowId`.

**Blocked by:** None.

**Status:** ready-for-human

**Research:** `.scratch/web-parity-next/research.md` (queue item).

**Desktop reference (for look-ups only):** the queue.rs lines above;
composer.rs:7309-7411 (the failure surface), `Composer::failure` state
shape.

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/components/composer.tsx` | edit | new optional prop `editFailureRef?: MutableRefObject<((message: string) => void) \| null>`; composer assigns `(message) => setFailure({ message, key: chat.id })` (the `editCommitRef` pattern at chat-page.tsx:486 → Composer :1393 → assignment composer.tsx:2838-2845) |
| `web/packages/app/src/routes/chat-page.tsx` | edit | replace the nine `sidebarNotice.set(...)` calls in `onEditFinish` + the renewal effect with `editFailureRef.current?.(message)`; pass `onEditFailure` into QueuePanel through the `queueSlot` drill (:1404-1414); **add the :1559 arm** — the silent return at :541-543 raises "The edit lease was lost; your text is still in the editor" |
| `web/packages/app/src/components/queue-panel.tsx` | edit | swap :262/:266/:284 to the prop |
| `web/packages/app/tests/queue-actions.test.ts` (or a new mounted suite) | edit | outcome→message mapping assertions |

## 1. Notes

- **Failure key = `chat.id`** — lease failures are chat-scoped; the
  composer's chat-scoped filter (`failure.key === null \|\| failure.key ===
  chat.id`, composer.tsx:3262-3264) then shows/hides them on route flips.
  The existing `setFailure` precedents key the same way (:2297, :2481).
- Strings stay verbatim; `keepRow` restore semantics unchanged (the edit
  stays open, text preserved — already matches desktop).
- Keep renewal's `setEditingRow(null)` at chat-page.tsx:515 (documented
  divergence).
- The notice chip is click-dismissable (`NoticeChip`, id
  `composer-failure`, composer.tsx:3462-3470) and clears on chat flip
  (:709).

## 2. Tests

Prefer hoisting the outcome→message mapping into `lib/queue-actions.ts`
and extending `tests/queue-actions.test.ts` (it already covers
begin/finish/renew outcomes placement-agnostively, plus
`describeQueueError`); the missing-lease arm gets its own case. A small
mounted test (jsdom idiom, `tests/section-menu.test.ts` pattern) pins that
the composer shows the notice for the row's chat and not another.

## 3. Acceptance checklist

- [x] All lease failures render as the composer's red notice, chat-scoped
- [x] The missing-lease arm surfaces :1559's string instead of returning
- [x] Sidebar toasts removed for these paths (other toast users untouched)
- [x] Tests + full app suite green

## Comments

Implemented on `ticket/wpn-06-queue-lease-failure-placement` (reviewed,
full-suite verified):

- The outcome→message copy lives in `lib/queue-actions.ts`
  (`describeBeginEditFailure` / `describeFinishEditFailure` /
  `describeRenewEditFailure` + the three non-outcome constants), pinned
  verbatim by `tests/queue-actions.test.ts` — the missing-lease arm
  (:1559) included.
- The composer's `editFailureRef` (the `editCommitRef` pattern) assigns
  `(message) => setFailure({ message, key: chat.id })`;
  `tests/composer-edit-failure.test.ts` (mounted, jsdom) pins the notice
  paint, click-dismiss, chat-scoping (stands down on route flip, re-keys
  under the new chat), and the unmount stand-down.
- The non-commit finish arm maps per-outcome (conflict/missing get their
  own copy), matching the desktop's action-independent post-RPC match
  (queue.rs:1609-1634 — `finish_queue_edit` handles all four actions with
  one match). The old web lump (everything → "The edit lease changed…")
  was the drift, not the desktop truth.
- The missing-lease arm sets `keepRow = true` (the edit stays open, text
  preserved) — the desktop's `let … else` returns with the edit intact and
  the message promises the text stays in the editor.
- Renewal's `setEditingRow(null)` kept (documented divergence).
- Verification: `pnpm exec tsc --noEmit` clean; full app suite
  `pnpm exec vitest run` — 147 files / 2201 tests green (review commit);
  focused seams green before that.
