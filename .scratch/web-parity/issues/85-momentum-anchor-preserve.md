# 85 — Phone: content jumps up/down during fast swipes (anchor preserve fights fling momentum)

**What to build:** On a phone, fast swipe-scrolling the transcript makes the content visibly jump up and down. The escape-anchor preserve wrote an ABSOLUTE scroll position — the anchor's capture-time spot — and on mobile the commit lands while compositor momentum has already advanced past it, so every frame yanked the viewport backward while momentum pushed forward. Make the correction a CONTENT DELTA added to the current position: identical for a still viewport, inert against the user's own motion.

**Blocked by:** None.

**Status:** done

## 1. Evidence (user report + mechanism, 2026-09-21 ~6:30 AM)

User (phone, warm-switching bundle): "the chat often flickering when i swipe up or down too fast… content jumping, like flickering up and down, jumping a lot". Mobile-only by construction:

- `captureAnchor` (transcript.tsx) runs on EVERY scroll event and records the first visible row + `offset = top − rowTop` — but not the position it was captured at.
- The per-commit preserve effect wrote `scrollTop = positions[ix] + anchor.offset` — mathematically the CAPTURE-TIME scroll position (plus any content drift since). On discrete scrolling (desktop wheel) the commit's `scrollTop` still equals the capture position → no write. On touch, scrolling is compositor-driven momentum: the scroll event captures at frame N, React's commit (the rAF-batched `setView` render) lands a frame or two later, by which time momentum has advanced `scrollTop` by tens-to-hundreds of px → the effect teleports the viewport BACK every frame → momentum pushes forward again → the reported up-down jumping. Fast swipes = large per-frame deltas = "jumping a lot".

## 2. Fix

| File | Change |
| --- | --- |
| `web/packages/app/src/components/transcript.tsx` `captureAnchor` | The anchor also carries `top` — the scroll position it was captured at (its zero point). |
| The per-commit preserve effect | Write `scrollTop + contentDelta` where `contentDelta = positions[ix] + anchor.offset − anchor.top` (how far the anchor row itself moved — pure content drift), instead of teleporting to the absolute target. After the write, advance `anchor.top` by the same delta so a second commit before the next scroll event cannot double-apply. With the user still (desktop, or between events), `scrollTop === anchor.top` and the write is exactly the old absolute correction; with the user mid-fling, their own motion is untouched. |
| The viewport-restore path | The restored row anchor takes `top` from the position the clamped restore write actually landed on. |

Semantics preserved: content growing/ shrinking ABOVE the anchor still keeps the anchor row visually stationary (the delta IS that correction); rows below the anchor never moved it (unchanged); pinned/own-turn/fold-compensation owners still pre-empt the preserve (guards untouched).

## 3. Tests

`tests/transcript-replay-integration.test.ts` — "mounted momentum-safe anchor preserve (ticket 85)": mounts the real surface, escapes upward, captures the anchor at 700, simulates compositor momentum advancing to 900 with NO scroll event (the commit-lands-later race), then re-measures a row above the anchor (+200 content drift). Asserts the write is `900 + 200 = 1100`. Verified red on the old absolute write (lands at 900 — the user's momentum erased); all 22 prior cases in the harness stay green (the still-viewport anchor-preservation cases are behavior-identical under the delta form).

## 4. Verification

- Full suite **1553/1553** (96 files); `pnpm -r build` green.
- User phone re-test pending (staged to the running engine).

## 5. Acceptance

- [x] The momentum regression test passes and fails on the pre-fix code.
- [x] Existing anchor/restore/replay suites unchanged.
- [ ] Phone: fast swipes no longer jump; content stays under the finger's motion.

## Comments

2026-09-21 — Diagnosed statically from the report's "mobile-only + fast swipes" signature: compositor momentum vs main-thread absolute writes. The second possible fighter — the restick spring engaging near the bottom during a down-fling — is NOT addressed here; if bottom-edge jumps survive this fix, that is the follow-up (defer restick while momentum is active).
