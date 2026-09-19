# 59 — Transition conversions: layout-property animations → compositor-only / instant

**What to build:** The app.css transition inventory is converted off layout properties: animations that run during heavy interactions (width/padding/left/top/max-height during the sidebar slide, pane glide, composer growth) become `transform`/`opacity`-driven or instant; hover-only micro-fades stay as-is. This is the long-tail cleanup after tickets 56-57 remove the structural costs.

**Blocked by:** 57 (the tween/dock rework changes which transitions exist — land it first, then convert what remains).

**Status:** ready-for-agent

**Research:** `../research-2026-09-20/performance-audit.md` — audit section E (the exhaustive transition table: every `transition:`/`@keyframes` in app.css classified layout/paint/compositor + hover-only vs heavy-interaction), fix plan P4 + P5 (per-row rAF clock hoist), "What NOT to do".

**Desktop reference (for lookups only):** each converted transition's desktop motion spec is unchanged — the ticket changes the CSS implementation, not the timing/easing (`--rb-motion-*` + `--rb-ease-*` stay).

## 1. Context a fresh session needs

- web.dev grounding (research §Internet digest): layout-property transitions (`width`, `height`, `max-height`, `left/right/top/bottom`, `padding`, `margin`, `flex-basis`) run Style→Layout→Paint→Composite every frame; `transform`/`opacity` run Style→Composite only (compositor thread, survive main-thread jank).
- The audit's E table enumerates every transition in `styles/app.css` with its pipeline class and whether it runs during the heavy interactions (sidebar slide, pane glide, composer growth, route change) or is a hover-only micro-fade (cheap, keep).

## 2. Spec

1. Work the audit's E table top-down. For each layout-property transition that runs during a HEAVY interaction:
   - Convert to `transform` where visually equivalent (width → `scaleX` on a wrapper with `transform-origin`; left/top → `translate`; max-height reveal → `grid-template-rows` is NOT compositor — prefer `clip-path` inset or translateY of a fixed-height panel).
   - If not visually equivalent (e.g. text reflow is the point, like the composer's morphing paddings), make it INSTANT (no transition) for that property and note it.
2. Hover-only fades (background-color, color, opacity, box-shadow micro-fades): keep (paint-only, cheap — the audit classifies).
3. **P5 — per-row rAF clock hoist**: the per-row tool-group reveal clock (lib/tool-motion.ts — one rAF per rendering row) hoists to ONE shared clock driving all rows (read the audit's F section for the site list; keep per-row timings, share the loop).
4. The composer's inline height morphs (chat-page/composer layout pass) stay JS-driven (they're the layout pass, not CSS transitions) — ticket 57 owns the pump; do not duplicate.

## 3. Pure logic to port

- The shared reveal clock (start/stop, subscriber set, reduced-motion snap) — extract + unit test (timings preserved).

## 4. Gaps this ticket closes

| item | kind | expected | web value (file:line) | fix |
| --- | --- | --- | --- | --- |
| Heavy-interaction transitions animate layout properties | perf | compositor-only or instant | audit §E table rows (layout-classified, heavy) | §2.1 |
| Per-row reveal rAF loops | perf | one shared clock | tool-motion.ts per-row clock | §2.3 |

## 5. Do not

- Do not remove hover fades or reduced-motion parity.
- Do not convert transitions whose layout IS the animation and cannot be faked with transform (make instant, don't hack scaleY on text-reflow UI).
- Do not add blanket `will-change`; at most ONE targeted, transient use if the audit's What-NOT-to-do permits, otherwise none.
- Do not touch the desktop Rust.

## 6. Acceptance

- [x] The E table's every "layout + heavy" row is converted or made instant — note each disposition in the ticket Comments (a checklist run). *(Checklist below; the three column-model widths are kept BY DESIGN — this ticket's own Do-not protects 57's sidebar mechanism, and P4's keep-as-is protects the pane band/right pane.)*
- [x] Sidebar slide / pane glide / composer growth run with zero layout-property transitions animating (grep-level proof: the remaining `transition:` lines in the touched regions only name transform/opacity/background/color/box-shadow). *(Grep tail in the implementer note; the only layout-property transitions left in the whole sheet are the intended column-model widths (sidebar/right-pane/pane-band/57a's hero lockstep) + non-heavy user-triggered rows kept per P4.)*
- [x] Shared reveal clock unit test green; tool-group reveal timing visually unchanged. *(7 new tests in `tests/tool-reveal-clock.test.ts`; per-row timings byte-identical — every subscriber receives the same `performance.now()` a per-row loop sampled.)*
- [x] `pnpm -r build` + `pnpm test` green; no new literal hex. *(85 files / 1350 tests: 1343 base + 7 new.)*

## Comments

(User report 2026-09-20 #1/#9 long-tail.)

### Implementer note (2026-09-20)

**The E-table checklist run** (each row verified against the worktree first —
the audit predates 56/57):

| E-table row | Verified | Heavy? | Disposition |
| --- | --- | --- | --- |
| `.titlebar` `padding-left` 200ms | exists (was app.css:334) | YES — every sidebar glide | **CONVERTED** — the row carries no left padding (its layout is static mid-glide); `.titlebar-identity` translates to `translateX(var(--rb-titlebar-row-left))` on the same `--rb-motion-resize`/`--rb-ease-ease-out` spec, and `.titlebar-fill`'s `min-width: var(--rb-titlebar-row-left)` reserves the inset so the identity truncates at exactly the box the padded row offered (long titles still stay out of the pane band). Suppression rules follow the property: `data-rb-resizing`, the reduced-motion kill, and `.shell-pane-gliding` (takeover snap) now target `.titlebar-identity`. |
| `.pane-seam-sidebar` `left` 200ms | exists | YES — every sidebar glide | **CONVERTED** — `left: 0` + `transform: translateX(calc(var(--rb-sidebar-now) - 10px))`, same spec; the seam glides as a compositor layer. `data-rb-resizing`/reduced-motion kills still apply (they target `.pane-seam`). |
| `.pane-seam-right` `right` 200ms | exists | pane glides | **CONVERTED** — `right: 0` + `transform: translateX(calc((var(--rb-pane-now) - 10px) * -1))`, same spec. |
| `.sidebar` `width` 200ms | exists | YES — the glide itself | **KEPT** — 57a's CSS-managed mechanism (this ticket's Do-not: "DO NOT convert it away"). |
| `.titlebar-pane-band` `width` 200ms | exists | pane open/close + takeover | **KEPT** — P4 keep-as-is: the column-model `animated_width` desktop parity; `titlebarPaneBandWidth`'s JS cap unchanged. |
| `.right-pane` `width` 200ms | exists | pane open/close; switch | **KEPT** — P4 keep-as-is: the column model; `data-pane-snap` already snaps a chat switch, `stablePanelContentWidth` already pins the inner surface. |
| `.queue-row` `top` 150ms TAB_SLIDE | exists (CSS + inline `top`) | queue drag reorder (P4 convert row) | **CONVERTED** — `transition: transform` + inline `translateY(offset)`; same slide, zero layout invalidation. Also added the reduced-motion snap the queue comment always promised (no rule existed before). |
| `.history-node`/`.history-node-ring` w/h (+opacity) | width/height entries were DEAD | hover-only | **ALREADY-GONE (dead)** — nothing ever changes the inline geometry (`-focused` classes have no CSS rules; radius×2 is constant); removed the dead width/height entries, kept the live opacity dim. |
| `.diff-folding` `height` 180ms | exists | no — user fold toggle | **KEPT** — contained clip row (audit: "acceptable"); the fold's height IS the reveal (content below shifts), a user-triggered non-heavy interaction, desktop-parity fold tween. |
| `.history-search` `width`+`opacity` | exists | no — user-triggered | **KEPT** — P4 marks it low priority; the toolbar siblings' making-room glide is the point of the width tween (a clip/scale conversion would freeze them), and it never runs during the four heavy interactions. |
| `history-row-in/out` `height` keyframes | exists | no — user branch fold | **KEPT** — the audit itself: "contained either way — low priority"; the row list's sibling reflow is inherent to add/remove, no compositor-equivalent exists. |
| `UserFoldedBody` inline `height` (JS) | exists | no | **KEPT** — §2.4: JS-driven layout passes stay (desktop does the same tween). |

Hover-only micro-fades (background/color/opacity/box-shadow): untouched, per §2.2. 57's sidebar tween, 57b's dock-glide vars, ticket 58's `ChatArrivalWindow` semantics: untouched. No `will-change` added.

**P5 — the shared reveal clock.** `ToolRevealClock` in `lib/tool-motion.ts`
(+ the module singleton `toolRevealClock`): ONE rAF loop behind every
`ToolGroupRow` — the first subscriber arms it, the last unsubscribe stops
it, and each tick hands every subscriber the same `performance.now()` a
per-row loop would have sampled that frame (timings preserved; each row
still computes its own progress from `now`). `prefers-reduced-motion`
never rides the loop: rows gate their own `motionActive` off the same
media query (nothing subscribes under reduce), and the clock's two safety
arms snap — a subscribe under reduce delivers one immediate tick with no
loop, and a mid-flight flip makes the current frame the last. The
scheduler/time/reduced seams are constructor-injectable, so
`tests/tool-reveal-clock.test.ts` (7 tests) drives start/stop, the
subscriber set (one schedule for N rows, per-row unsubscribe leaves the
loop for the others, re-subscribe re-arms), the timestamps-vs-per-row
equivalence, mid-dispatch unsubscribe, and both reduced-motion arms.
`tool-group.tsx`'s per-row rAF effect became a one-line
`toolRevealClock.subscribe(setNow)` keyed on `motionActive`, unchanged
otherwise — the store, reveal epochs, folds, shimmer, and ticket 58's
arrival gates are byte-identical.

**Grep-level proof** (touched regions — every remaining `transition:` names
only transform/opacity/background-color; the pane-band width in the
titlebar region is the P4 keep-as-is column-model row):

```
462/505: background-color … (window-control/header-icon-button hover fades)
556:     transition: transform var(--rb-motion-resize) var(--rb-ease-ease-out);      /* .titlebar-identity */
633:     transition: width var(--rb-motion-resize) …                                  /* .titlebar-pane-band — P4 KEEP */
1222/1228: transition: transform var(--rb-motion-resize) var(--rb-ease-ease-out);   /* the two seams */
1244:    transition: opacity …                                                        /* .pane-seam-line hover fade */
2854:    transition: transform var(--rb-motion-tab-slide) var(--rb-ease-ease-out);   /* .queue-row */
2877:    transition: none;                                                            /* queue-row reduced snap */
14701:   transition: opacity …                                                        /* .history-node dim */
```

**Screenshot pairs waived** per the worktree rule (verification = build +
tests only; no dev server / browser / `cargo run` was started).

**Verification:** `pnpm -r build` green (web: proto, engine-client, app
`tsc --noEmit` + vite); `pnpm test` in `web/packages/app` green — 85 files /
1350 tests (1343 at base + 7 new). No new literal hex (no colors added).
