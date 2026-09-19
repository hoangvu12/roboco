# 57 — Kill the per-frame React renders: sidebar tween, remask cadence, dock pump

**What to build:** The three heaviest animation pipelines stop driving React re-renders and canvas re-rasters per frame. The sidebar slide becomes a browser-managed CSS transition (no `flushSync` loop), the new-thread canvas remasks only on settle/geometry change, and the dock choreography pumps CSS variables / memoized values instead of re-rendering the page per frame. The visuals survive (the slide still slides, the dock still docks) — the implementation becomes compositor/browser-owned.

**Blocked by:** 56 (Always-opaque web — the pill/island blurs are the biggest paint cost under the moving surfaces; land that first).

**Status:** ready-for-agent

**Research:** `../research-2026-09-20/performance-audit.md` — §S1 mechanism (a) and (b) frame-by-frame cost chains, fix plan P1/P3, audit sections A/B/F, "What NOT to do".

**Desktop reference (for lookups only):** `crates/ui/src/shell.rs` `sidebar_now(t)` (the desktop evaluates the tween IN RENDER, one scalar — the web equivalent is a CSS transition the browser interpolates), `composer_dock.rs` choreography (the motion TARGETS remain 1:1; only the web's implementation of the interpolation changes).

## 1. Context a fresh session needs

- The lag (user: "animating anything on the new chat page cause lag, opening/closing sidebar... drop performance really hard") is NOT one bug — it is three stacked per-frame costs the research ranked:
  1. **The flushSync tween**: `routes/chat-page.tsx:554-589` runs a rAF loop for 200ms calling `flushSync` PER FRAME (`:572`) — a synchronous full-page React re-render each frame (JS+Style+Layout+Paint+Composite, the most expensive path).
  2. **Double canvas remask per frame**: the loop calls `remaskNewThreadBackground()` (`:575`) AND a no-deps per-commit effect (`components/new-thread-background.tsx:251-389`) fires on the same commits — two two-pass canvas mask recomputations per frame (~13MB ImageData/Float32 allocations each per the audit).
  3. **The width/padding CSS transitions** on `.sidebar` (app.css:1273), `.titlebar` (:334), the seams (:1193/1198) re-layout the flex row every frame, amplified by the columnWidth ResizeObserver → composer evaluate cascade.
- The route transition (new-thread → chat) re-renders the whole mounted page per frame for ~470ms via the dock pump (`chat-page.tsx:760-773`) and pumps `--rb-bottom-stack`/`bottomClearance` per frame as the pill height glides (`:741-747`).
- Ticket 34 landed the flushSync design (its Comments record the rationale: "flushing inside the rAF callback is the web peer of the desktop evaluating sidebar_now() in render"); ticket 33 landed the per-commit remask. This ticket REPLACES those mechanisms — the parity TARGETS (the 200ms slide, the dock choreography) are unchanged.

## 2. Spec

### 2.1 The sidebar tween — browser-managed, zero React frames

Pick ONE (research fix plan P1 offers A/B/C with trade-offs — read them, then implement the one that preserves ticket 34's visual contract with zero per-frame JS):

- **A (preferred if it preserves the cutout):** a single CSS `transition: width <dur> <ease>` on the layout wrapper driven by the class flip (no JS loop at all), with the canvas cutout handled per §2.2. The React state flips ONCE (open/closed); the browser interpolates.
- **B:** keep a rAF loop but write ONLY direct DOM style (`el.style.width`) on a ref — zero React state, zero flushSync — plus a transitionend settle callback.
- **C:** snap (no animation) — only as a last resort if A/B cannot keep the hero cutout tracking; the user prefers the slide.

Constraint: the hero's cutout hole must stay aligned with the pill during the slide (ticket 34's purpose). With A, the remask per §2.2 must still track (see the research's option: move a SECOND canvas via transform — compositor-only — instead of re-rasterizing the mask per frame).

### 2.2 Remask cadence

- Remask ONLY on: settle (transitionend / rAF-loop end), real geometry change (resize, hero size change), and artwork/effect change. NO remask during the tween.
- During the tween, keep the hole aligned with a compositor-only technique (the research's P1 sketch): the mask canvas (or an inset wrapper) `transform: translateX/scaleX` to track the width, snapped to the true raster on settle. If that proves visually insufficient for the smoothstep dome, fall back to remasking at a CAPPED cadence (e.g. every 4th frame / 48ms) and note it in Comments.
- Delete the no-deps per-commit remask effect (new-thread-background.tsx:251-389) or gate it on the settle conditions above.

### 2.3 The dock pump — de-React

- `routes/chat-page.tsx:760-773` (`pump`): stop re-rendering the page per frame. The per-frame values (`--rb-bottom-stack`, `bottomClearance`, the dissolve alpha) become CSS custom properties written to a ref'd element (direct DOM writes — Style/Composite only, no React render), OR the whole glide becomes a CSS transition/keyframes on those custom properties (registered via `@property` if needed for transition-ability; weigh complexity).
- The render-phase tick (35's) and observePane ordering (36's) stay — they are per-navigation, not per-frame.
- Memoize what the pump re-created per frame (the audit's F section lists the render cascade).

### 2.4 Keep

- The 200ms duration + easing values (ticket 34's spec, `--rb-motion-*` tokens).
- Reduced-motion: snap (unchanged).
- The dock choreography's visible phases (ticket 36's matrix) — implementation only changes.

## 3. Pure logic to port

- The settle-condition predicate (geometry-dirty vs tween-active) — extract + unit test.
- Update `tests/composer-dock.test.ts` / `titlebar-island.test.ts` only where they asserted flushSync-per-frame mechanics (assert the settle contract instead: one state flip + settle callback fires).

## 4. Gaps this ticket closes

| item | kind | expected | web value (file:line) | fix |
| --- | --- | --- | --- | --- |
| Sidebar toggle: React frames during tween | perf | 0 | flushSync per frame, chat-page.tsx:572 | §2.1 |
| Canvas remasks during tween | perf | 0 (transform tracks) | 2/frame, chat-page.tsx:575 + new-thread-background.tsx:251-389 | §2.2 |
| Dock route transition: React frames | perf | 0 (CSS vars/DOM writes) | ~470ms of page re-renders, chat-page.tsx:760-773 | §2.3 |

## 5. Do not

- Do not change the animation's visible timing/easing (only WHO interpolates).
- Do not break reduced-motion (snap paths stay).
- Do not touch ticket 36's observePane/tick ordering or 40's store logic.
- Do not add blanket `will-change` (audit's What-NOT-to-do).
- Do not touch the desktop Rust.

## 6. Acceptance

- [ ] Sidebar toggle on the new-thread page: React DevTools-style check — the component tree re-renders ZERO times during the 200ms (one state flip at start, one settle); code-inspection proof in Comments.
- [ ] No `flushSync` remains in the tween path (grep chat-page.tsx).
- [ ] Canvas: no remask call between tween start and settle (log-based or counter-based unit test).
- [ ] New-thread → chat: no per-frame page re-render (same proof style); dock phases still visible.
- [ ] `pnpm -r build` + `pnpm test` green; no new literal hex.

## Comments

(Landed flushSync design was ticket 34 deviation 1; per-commit remask was ticket 33/34 "accepted" 2-rasters-per-frame — both are the lag the user reported 2026-09-20.)

**2026-09-20 — PART A (57a) landed: CSS-managed sidebar tween + settle-only remask.** Option A (research P1-A) with the remask cadence's compositor track realized as the *raster window* instead of a transform:

- **§2.1 (option A):** `.new-thread-hero[data-sidebar-tween="1"]` gets `transition: width var(--rb-motion-resize) var(--rb-ease-ease-out)` — the column's exact spec, armed by the ONE follow-up commit after the flip (the flip render holds `heroWidth` at the previous target so the flip commit is a visual no-op and the flag+width commit is the only recalc — this is what makes the transition start reliably despite forced layout reads in layout effects). The rAF loop, `flushSync`, `animatedSidebar`/`sidebarPump` are deleted (grep proof: 0 matches for `flushSync` in chat-page.tsx; a source-scan test guards it). Mid-glide reversal = CSS retarget from the painted width (the desktop's `sidebar_now()` capture semantics, free). Reduced-motion: the JS never arms + `.new-thread-hero` joined the 9165-line reduce kill list + `data-rb-resizing` freeze list (snap paths intact).
- **§2.2 (remask cadence):** the no-deps per-commit effect is now deps-scoped (`[artwork, image, rasterCanvas, effect, surface]`); the `remaskNewThreadBackground` registry is deleted. Remask fires only via `HeroRemaskGate` (`lib/sidebar-tween.ts`): settle (the flag-fall commit's layout effect, pre-paint), artwork/effect change (the dep-change rAF), and geometry (hero RO — resizes/drag takeovers; composer-surface RO — typing morph + the dock's pill-height glide) — geometry is ABSORBED while the tween runs (counter-based unit test: 12 frames × 2 observers = 0 remasks; settle = exactly 1).
- **The hole tracks via the raster window:** during the tween the readiness layer holds the current raster's box FIXED (`width: var(--rb-hero-raster-width)` — written by the remask itself) and CENTERED on the animating hero (`left: 50%`). Since the pill is `margin-inline: auto`-centered in the column = the hero's center, and the raster's dome was painted at that same center, the hole tracks the pill per frame with zero bitmap scaling, zero JS, zero re-raster — no transform needed (the ticket's "compositor-only transform, or equivalent"). The hero's `overflow: hidden` windows it; the settle commit returns the layer to `inset: 0` and re-rasters at the settled width in the same frame. The capped-remask fallback was NOT needed.
- **Known trade-off (sanctioned "snap to the true raster on settle"):** the artwork's crop holds at the flip state during the glide (the desktop re-crops per frame) and snaps at settle; in the GROW direction (collapse) the clip window widens past the fixed raster, so up to `(sidebarWidth)/2` of page-bg shows at each edge until settle. The hole/pill alignment — ticket 34's contract — is exact the whole glide.
- **Signal for 59/63:** `lib/sidebar-tween.ts` exports `sidebarTweenActive()` + the `sidebarTweenSignal` singleton (arm at flip / settle at transitionend, cap `SIDEBAR_GLIDE_MS + 120`), `remaskDue(tweenActive, reason)`, `HeroRemaskGate`, `SIDEBAR_GLIDE_MS`. Note: the signal arms on every qualifying flip (hero mounted or not — the column glides regardless); settle is transitionend on the hero or the cap.
- **Minor deviations:** `paneNowWidth`/`heroWidth` now consume the settled sidebar target (the research's "delete animatedSidebar entirely" — the pane is closed on the canvas route where the tween runs, and the pane's own width transition interpolates); the edge-bounce no longer seeds the tween's `from` (the CSS transition starts from the hero's own painted width — strictly smoother); `composer-dock.test.ts`/`titlebar-island.test.ts` had NO flushSync-per-frame assertions to update (verified by grep; only `layout.test.ts`'s `evalWidthTween` tests exist and stay — the helper remains in use by terminal-dock/right-pane/titlebar).
- **Verification:** `pnpm -r build` green; `pnpm test` 1325/1325 (83 files); new `tests/sidebar-tween.test.ts` (15 tests: the truth table, the counter-based zero-remask-during-tween, drag-takeover, artwork-mid-tween, CSS artifact guards incl. the tokens/kill blocks, and the no-flushSync source scan).
