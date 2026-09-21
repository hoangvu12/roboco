# 78 — Selection edge auto-scroll containment

**What to build:** The transcript's selection drag edge auto-scroll (ticket 18 §3.7) currently arms from ANY window-level `pointermove` with `buttons !== 0` and runs for as long as any primary-button contact sits in/near the scroller's edge bands. Reported live on the NEW bundle (2026-09-20 evening, phone): holding a finger on the titlebar scrolls the chat upward continuously for the whole hold; holding the bottom of the page does the same downward. Fix the arming so the auto-scroll only runs for a genuine text-selection drag that began on the transcript scroller, and clear the tracker on `pointercancel`.

**Blocked by:** None.

**Status:** done

## 1. Evidence (web-parity/followup @ c81dd2fe)

- The tracker lives in the scroller's attach effect, `web/packages/app/src/components/transcript.tsx:999-1041`. `onPointerDown` is attached to the scroller element (correct origin gate), but `onPointerMove` — attached at WINDOW level, passive — **arms unconditionally**: `if (event.buttons === 0) { drag = null; return; } drag = { x, y }` (transcript.tsx:1015-1021). A phone hold with micro-drift anywhere in the window (titlebar, composer, safe-area) therefore arms `drag` even though the press never touched the transcript.
- `selectionScrollStep` (`web/packages/app/src/lib/transcript.ts:1271-1294`) extrapolates beyond the scroller rect with a 36px edge band and no inside-rect clamp — the titlebar sits above `bounds.top + 36`, the composer/safe-area below `bounds.bottom - 36`, so armed drags there produce full-rate steps (up to ~1000px/s) via `stick.writePreserving` every 24ms.
- The only clears are window `pointerup` (transcript.tsx:1027). There is **no `pointercancel` listener** — unlike the composer's copy of the same pattern (composer.tsx:1110), pane-seam (pane-seam.tsx:142), right-tab-strip (right-tab-strip.tsx:193) and tree-split-panel (tree-split-panel.tsx:160), which all handle `pointercancel`.
- A press that begins INSIDE the scroller on non-interactive rows also arms the tracker with no selection requirement, so a stationary hold within the bottom 36px band scrolls without any movement at all.
- Ticket 76's `touch-action: none` on the phone titlebar (app.css:416) closed only the browser-pan path; it cannot affect this JS-owned leak (and by keeping the pointer stream in-app it makes the leak easier to hit). Phone bundle identity was verified: roboco.nguyenvu.dev served `main-BjXmfE5R.js`/`main-KuJfCbhL.css`, matching this branch's `web/packages/app/dist`.
- The feature's own comment states its intent: a **selection drag** pinned near an edge auto-scrolls (transcript.tsx:1000-1002), matching the desktop's `step_selection_scroll` (a real selection drag). Nothing in the current code checks for a selection.

## 2. Spec

| Rule | Required behavior |
| --- | --- |
| Arming | Only the scroller's own `pointerdown` on non-interactive content arms the tracker (existing behavior, keep). Window `pointermove` may only UPDATE an armed tracker, never arm one. |
| Selection gate | The 24ms interval takes a step only while the document has a non-collapsed selection (`document.getSelection()` non-null, `isCollapsed === false`, `rangeCount > 0`). Collapsed/absent selection → no step. |
| Cancellation | `pointercancel` at window level clears the tracker (same listener set as `pointerup`). |
| Preserved behavior | `beginScrollNavigation` + `writePreserving` write path, the t² ramp, 24ms cadence, 36px band, interactive-target bail in `onPointerDown`, and the desktop comment references all stay as-is. |
| Refactor | Extract the tracker + gate into a small pure unit in `web/packages/app/src/lib/transcript.ts` (next to `selectionScrollStep`) so the arming/cancel/selection rules are unit-testable; the component wires listeners to it. |

## 3. Tests

- Unit tests (pure tracker, in the suite that already covers `lib/transcript.ts` scroll helpers — extend `tests/transcript-model.test.ts` or the stick/scroll suite): move-never-arms; press-arms; interactive-target press does not arm; `pointercancel` clears; buttons-0 move clears; selection gate blocks a collapsed selection and passes a non-collapsed one.
- Existing suites stay green (`pnpm test` from `web/packages/app/`).

## 4. Acceptance

- [ ] Holding (with micro-drift) on the phone titlebar no longer scrolls the chat — any duration.
- [ ] Holding the bottom of the page (composer/safe-area/last rows) no longer scrolls.
- [ ] Mouse drag-select pinned near the top/bottom edge still auto-scrolls (desktop `step_selection_scroll` parity).
- [ ] Unit tests above pass; full `pnpm test` green; `pnpm -r build` from `web/` green.
- [ ] Phone re-test by the user on the follow-up build (pending until the next build is handed over).

## Comments

Created 2026-09-20 evening from the live round-2 report (titlebar/bottom hold scroll) plus read-only research of the follow-up branch. Root cause is the app's own selection auto-scroll arming leak, present in both the old and new bundles.

## Comments

**2026-09-21 � implemented and merged to `web-parity/followup`.** `fix(web): ticket 78 selection autoscroll containment`: `SelectionDragTracker` + `selectionDragAutoscrolls` in `lib/transcript.ts` (move-never-arms, `press`/`pressInteractive`, clear on release/cancel), the transcript attach effect rewired to the tracker with a new window `pointercancel` listener, and the 24ms tick gated on a non-collapsed `document.getSelection()`. Tests: 3 new cases in `tests/transcript-model.test.ts` (arm/track/clear matrix + selection gate); full suite **1506/1506** (91 files) and `pnpm -r build` green. Phone re-test pending until the next build is handed over (acceptance rows 1-2).
