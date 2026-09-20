# 84 — Phone: transcript flows outside the screen (nowrap content minimum)

**What to build:** At phone width (≤768px) the chat transcript stops being contained by the viewport — content (e.g. a tool chip's long unbreakable URL) pushes the whole transcript column past the screen edge. Restore containment: the transcript chain must clip at the scroller's box exactly as it did before the arrival outlet.

**Blocked by:** None.

**Status:** done

## 1. Evidence (user report + DOM capture, 2026-09-21 ~5:30 AM)

The user re-tested the warm-switching bundle on a phone and reported "the chat isn't being wrapped, like it flowing outside the screen". The pasted DOM shows the transcript rows intact; the widest unbreakable content is `.tool-chip-detail`'s URL (`https://html.duckduckgo.com/html/?q=trứng+lòng+đào+ngâm tương calories+protein`, `white-space: nowrap` in app.css:8885-8894 — and as `display: flex`, `text-overflow: ellipsis` never renders, so it can't shrink visually).

**Mechanism.** Ticket 82/83 inserted `.chat-transcript-outlet` and `.chat-arrival-gate` (both `flex-direction: row`) between `.chat-body` and `.transcript-wrap`. That changed `.transcript-wrap`'s flex role at phone width:

- Before: `.chat-body` is `flex-direction: column` at ≤768px, so the wrap was a CROSS-axis child — width = stretch, no consultation of the content minimum; `.transcript`'s `overflow-x: hidden` clipped at the screen edge.
- After: the wrap is a MAIN-axis (row) child of the gate, where `min-width: auto` = the content-based minimum. The wrap has no `min-width: 0`, so a nowrap chip's ~700px minimum pushes it past a ~390px viewport — and the scroller, stretched to the oversized wrap, clips off-screen: the visible "flowing outside the screen". Desktop stayed fine only because its viewport exceeds the content minimum.

The outlet and the gate themselves carry `min-width: 0` (ticket 83's CSS) — the gap was the wrap, which never needed the opt-out before the outlet existed.

## 2. Fix

| File | Change |
| --- | --- |
| `web/packages/app/src/styles/app.css` `.transcript-wrap` | Add `min-width: 0` (with the reasoning comment). The wrap shrinks to the gate's width; `.transcript` stretches to the wrap and clips content at its own box — the pre-outlet phone behavior, now structurally guaranteed at every width. |
| `web/packages/app/tests/chat-transcript-outlet.test.ts` | CSS-contract describe (the repo's grep idiom, jsdom-cwd read): `.transcript-wrap` and the `.chat-transcript-outlet, .chat-arrival-gate` rule both carry `min-width: 0`. |

Not addressed (separate polish if wanted): `.tool-chip-detail` clips its text hard instead of ellipsizing — `text-overflow` has no effect on `display: flex` boxes. A proper ellipsis needs the detail's text in a block box (or the flex centering replaced by line-height). Cosmetic on both clients' rail rows; no containment impact.

## 3. Verification

- `pnpm vitest run tests/chat-transcript-outlet.test.ts` — 6/6 (4 existing + 2 new contracts).
- Full suite **1552/1552** (96 files); `pnpm -r build` green (`main-BnDXSb47.js`).
- User phone re-test pending (staged to the running engine).

## 4. Acceptance

- [x] The width-contract tests above pass; full suite + build green.
- [ ] Phone: the transcript stays inside the viewport; wide chip details clip inside the scroller.

## Comments

2026-09-21 — Diagnosed from the user's pasted phone DOM (the unbreakable URL chip) plus the flex-chain analysis: the outlet/gate wrappers flipped `.transcript-wrap` from a cross-axis child (phone's column `.chat-body`) into a row-flex child with an automatic content minimum. One-line fix + contract tests.
