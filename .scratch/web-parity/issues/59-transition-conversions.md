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

- [ ] The E table's every "layout + heavy" row is converted or made instant — note each disposition in the ticket Comments (a checklist run).
- [ ] Sidebar slide / pane glide / composer growth run with zero layout-property transitions animating (grep-level proof: the remaining `transition:` lines in the touched regions only name transform/opacity/background/color/box-shadow).
- [ ] Shared reveal clock unit test green; tool-group reveal timing visually unchanged.
- [ ] `pnpm -r build` + `pnpm test` green; no new literal hex.

## Comments

(User report 2026-09-20 #1/#9 long-tail.)
