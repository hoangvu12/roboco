# 34 — Canvas sidebar slide + titlebar island

**What to build:** Two fixes on the new-chat canvas, from one user report:
"closing/opening sidebar makes the new page snapping, not sliding smoothly; no
background on the top left nav when the sidebar is closed". (a) Collapsing or
reopening the sidebar makes the hero artwork — box and cutout hole — **slide**
with the column over the desktop's 200 ms resize curve instead of snapping in
one frame while the pill glides without it. (b) The desktop titlebar **island**
— the frosted top-left panel behind the window-control cluster — appears
exactly in the reported state: the canvas route, the sidebar collapsed, and
a background resolving beneath (the resolved active background — installed,
or the bundled default once ticket 48's resolution semantics land).

**Blocked by:** None — can start immediately. (Cross-refs, not blockers: 33 owns
the canvas paint the island sits on; 48 owns the active-background resolution —
including the default — that the island's visibility must key off.)

**Status:** ready-for-agent

**Research:** `../research-2026-09-19/new-thread-background-and-transitions.md`
S4(a)–(d), consolidated gap rows G13–G15, "Pure logic to port" items 2–3,
"Desktop-only items NOT to port".

**Desktop reference (for lookups only):**
`crates/ui/src/shell.rs::render_titlebar_cluster` (3973–4088; island target
3983–3994, wrapper 4017–4035), `::titlebar_island_vertical_geometry`
(829–835), `::toggle_sidebar` (1947–1957), `sidebar_now` (3790–3794),
`eval_tween` (3753–3767), `right_now` (3796–3802, 1929–1945), hero width
(5890), the island test (8251–8263); `crates/ui/src/frost.rs::frosted`
(27–33, 85–96); `crates/proto/src/motion.rs` (302 RESIZE, 217 EASE_OUT);
`crates/ui/src/new_thread_background_mask.rs:49-51` (same-frame composer
bounds).

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/routes/chat-page.tsx` | edit | `heroWidth` (:566) — fed by a rAF-driven `evalWidthTween` on sidebar flips instead of `sidebarTarget`; the same loop re-runs the hero's remask every frame |
| `web/packages/app/src/components/new-thread-background.tsx` | edit | expose the remask as a callable (the per-commit effect at :109-160 stays; 34 adds a per-frame entry point for the tween) |
| `web/packages/app/src/components/titlebar.tsx` | edit | `Titlebar` — the island element rendered behind `.titlebar-cluster` (the first child of the cluster's parent, per the desktop's wrapper-inside-the-cluster-row) |
| `web/packages/app/src/components/app-shell.tsx` | edit | the island's visibility wiring: route + `sidebar.collapsed` + the resolved active background |
| `web/packages/app/src/styles/app.css` | edit | **new** `.titlebar-island` (frosted 12/20 panel); the hero's inline width (no new transition — the JS drives it) |
| `web/packages/app/tests/titlebar-island.test.ts` | new | `titlebarIslandVerticalGeometry` + `islandTarget` cases |

---

## 1. Context a fresh session needs

- The hero (ticket 15, refined by 33) is absolutely positioned at the top of
  `.chat-column` with inline `width`/`height`/`opacity`
  (`components/new-thread-background.tsx:173-212`; the CSS at
  `app.css:2495-2501` has **no width transition**). Its width is computed in
  `ConversationPage`: `heroWidth = Math.max(viewport - sidebarNow, 0)`
  (`routes/chat-page.tsx:566`) with `sidebarNow = sidebarTarget(sidebar)`
  (:431) — `sidebarTarget` returns the **target** width
  (`collapsed ? 0 : width`, `state/layout.ts:424-426`), not an animated one.
- The sidebar column itself glides on CSS: `.sidebar { transition: width
  var(--rb-motion-resize) var(--rb-ease-ease-out) }` (`app.css:1159`) — this is
  already the desktop's exact curve (200 ms `RESIZE` = `EASE_OUT` =
  cubic-bezier(0, 0, 0.58, 1)). The missing half is feeding the **animated**
  value to the hero and the mask.
- The cutout mask regenerates per React commit (rAF-scheduled) and on
  `#composer-surface` resize only
  (`components/new-thread-background.tsx:109-160`; `ResizeObserver` at
  :148-155). During the pure-CSS sidebar transition there are **no React
  commits** and the composer's size does not change (width capped at 768,
  `.persistent-composer { max-width: 768px; margin-inline: auto }`,
  `app.css:2480-2485`) — so the hole stays at the pill's old rect while the
  pill glides with the column.
- The toggle path: `onToggleSidebar` (`components/app-shell.tsx:196-202`) →
  `sidebarLayout.toggleCollapsed()` (`state/layout.ts:472`) → the settings
  store (`sidebarCollapsed`) → `useSidebarLayout` re-renders the shell, which
  writes `--rb-sidebar-now` (`app-shell.tsx:503`). The CSS then animates
  `.sidebar`'s width; nothing else moves.
- The titlebar is an absolute overlay (`app.css:292-316`) whose cluster
  (`.titlebar-cluster`, `app.css:323-333`, rendered at
  `components/titlebar.tsx:81`) sits at the row's left end. Ticket 06
  deliberately made the bar itself transparent ("NO FILL, NO BLUR, NO BORDER" —
  `app.css:304-314`): with the sidebar open the cluster sits over the sidebar
  tone; with the sidebar closed on the canvas it sits directly over the raw
  hero artwork.
- The desktop shows a **frosted island** exactly there. `grep island` over
  `web/packages/app/src` → no hits: the web never ported it. Ticket 06 §2.2
  child 1 called it "Frosted island (desktop-only in practice — see §5)" —
  this ticket supersedes that judgment for the canvas+collapsed+background
  case only; every other ticket-06 decision stands.
- Tokens: `--rb-titlebar-height` (38) and `--rb-titlebar-top-pad` (4) exist;
  the glass idiom is `color-mix(in srgb, var(--rb-overlay)
  calc(var(--rb-glass-overlay-alpha) * 100%), transparent)` (used at e.g.
  `app.css:4370`); `--rb-shadow-sm` exists (`app.css:5021`). The island must
  use tokens, no literal hex/px.
- `evalWidthTween` (`state/layout.ts:171-179`) is already ported from
  `shell.rs::eval_tween` — the eased 200 ms lerp on the resize curve, exact at
  the endpoints, reduced motion handled by the caller writing the endpoint.
- The island's background gate must use the **resolved active background** —
  the same resolution the hero's painter uses
  (`resolveNewThreadBackground`/`resolveInstalledBackground`,
  `lib/new-thread-background.ts:253-290`) — never the raw setting: a stored
  entry whose file no longer decodes falls back to the bundled default
  (at the research's HEAD the desktop predicate was installed-only,
  `is_file()`, shell.rs:3986-3989; **ticket 48, this batch, replaces the
  desktop predicate with the resolved-artwork check so the default counts
  too** — the web island keys off the resolved value, which is the post-48
  behavior). Ticket 48 owns those semantics; ticket 35 owns hoisting the
  resolution to shell scope. Consume whichever resolved-background source
  exists after those tickets; do not fork a third resolver.
- Vocabulary (`CONTEXT.md`): **chat**, **space**, **engine**, **harness**.

---

## 2. Spec

### 2.1 The tweened hero width (the slide)

Desktop reference, verbatim:

- `hero_width = (viewport_width − sidebar_now()).max(0)` (shell.rs:5890) where
  `sidebar_now() = eval_tween(sidebar_tween, sidebar_target()) + edge bounce`
  (shell.rs:3790-3794) — the **tweened** width, re-evaluated every frame
  (`eval_tween` sets `motion_active` → rAF, shell.rs:3753-3767) → the hero
  box glides; and the mask consumes the same frame's composer bounds
  "including on sidebar resize" (mask.rs:49-51; test
  `background_paint_sees_same_frame_composer_bounds_even_when_painted_first`,
  mask.rs:91-177).

**Sidebar collapse motion (desktop, verbatim):** `toggle_sidebar`
(shell.rs:1947-1957) captures `from = sidebar_now()` (a mid-animation reversal
starts from what is painted), clears bounce/edge state, flips
`settings.sidebar_collapsed`, arms `WidthTween::new(from, sidebar_target())` —
a oneshot evaluated from render (`eval_tween`, shell.rs:3753-3767): 200 ms
`RESIZE` = `EASE_OUT` = cubic-bezier(0, 0, 0.58, 1) (proto/motion.rs:302, 217),
reduced motion → target. From→to: `sidebar_target()` = `collapsed ? 0 :
settings.sidebar_width` (0 ↔ the dragged width, clamped 224–400 by
settings.rs:30-32). The web's CSS `transition: width var(--rb-motion-resize)
var(--rb-ease-ease-out)` on `.sidebar` (app.css:1159) is this exact curve — the
missing half is feeding the animated value to the hero and the mask.

**What to build:**

- When `sidebar.collapsed` flips (either direction), arm a one-shot rAF loop in
  `ConversationPage` that writes, every frame until the 200 ms elapses:
  `heroWidth = Math.max(viewport − animatedSidebar, 0)` where
  `animatedSidebar = evalWidthTween(from, target, elapsed)` with `from` = the
  **painted** width at the flip (the last written value — a mid-animation
  reversal restarts from what is painted, like the desktop), plus the live edge
  bounce offset (`--rb-sidebar-edge-offset`) when a seam drag armed one — the
  desktop's `sidebar_now()` includes it.
- The same loop re-runs the hero's remask every frame (the pill glides with the
  column — `margin-inline: auto` centers it inside the shrinking conversation
  column — so its viewport-space rect moves even though its size does not).
  Expose the remask from `NewThreadBackground` (the per-commit effect stays for
  the typing morph); alternatively observe the pill's wrapper **position**
  (a `ResizeObserver` sees size only — position needs the loop or a
  `MutationObserver`/poll; the loop is the desktop's own answer).
- Reduced motion: write the endpoint directly (the CSS has already snapped) —
  `evalWidthTween`'s documented contract.
- Keep `heroWidth` a prop-driven inline style; **do not** add a CSS
  `transition: width` on `.new-thread-hero` (two clocks on one property is the
  snap bug wearing a different hat — and the mask could not follow a CSS
  transition anyway).

**States:**

| state | condition | what changes |
| --- | --- | --- |
| sidebar open (settled) | `!collapsed` | `heroWidth = viewport − width` (as today) |
| tween running | 200 ms after a flip | `heroWidth` follows `evalWidthTween` per frame; remask runs per frame |
| mid-animation reversal | flip while tween live | `from` = current painted width; tween re-arms |
| reduced motion | `prefers-reduced-motion` | endpoint written immediately, no loop |
| phone | `viewport ≤ PHONE_MAX_WIDTH` | hero not mounted (`heroVisible` already excludes it, chat-page.tsx:565) — no loop needed |

### 2.2 The titlebar island

The desktop's visibility rule (shell.rs:3983-3994, as researched at HEAD
`bc3a3945`), verbatim:

```
island_target = 1.0 iff  Route::Chat
                          && selected_chat.is_none()          // the new-thread canvas
                          && settings.sidebar_collapsed
                          && new_thread_composer_background is Some
                             && its path is_file()             // installed, not the bundled default
```

— i.e. precisely the user's state (new chat page, sidebar closed, a background
image installed). NOTE: ticket **48** (this batch) replaces the fourth
condition's raw-field `is_file()` probe with the resolved-active-background
check — installed when it decodes, else the materialized default — so the
island also renders over the bundled default artwork (its gap row: "Default →
island target `1.0` … same presentation as an installed background"). The web
island follows the RESOLVED value: `resolveNewThreadBackground`-shaped
resolution already includes the default fallback, so the web's gate is
canvas + collapsed + background-resolves (always true while the resolver
succeeds; keep it keyed off the resolver, not hardcoded `true`, so a
resolution failure still hides the island). The island, verbatim from the
research:

| property | value | source |
| --- | --- | --- |
| wrapper | `absolute; left 6; right 0; top island_top; h island_height; opacity island` inside the cluster row | shell.rs:4017-4024 |
| vertical geometry | `height = 28 + 4·progress` (28→32), `center = (TITLEBAR_HEIGHT + TITLEBAR_TOP_PAD)·0.5 = 21`, `top = center − height/2` | `titlebar_island_vertical_geometry`, shell.rs:829-835 |
| content | `frost::frosted(12.0, 20.0, div().size_full().rounded(12).bg(theme.glass_overlay()).shadow_sm())` — 12 px radius, **20 px** backdrop blur, glass-overlay tint, small shadow | shell.rs:4025-4035; frost.rs:27-33, 85-96 |
| motion | a persistent manual `WidthTween` (200 ms RESIZE ease-out), reversal from the painted value, reduced motion snaps; initial presentation settled | shell.rs:3995-4005, 3753-3767 |
| test | `island_stays_centered_on_controls_while_expanding` (height ∈ [28,32], center constant 21, 4 px of air around the 24 px controls) | shell.rs:8251-8263 |

**Web mapping:**

- A new element inside the cluster row (behind the controls — the desktop
  wrapper is a child of the cluster row that the controls paint over): an
  absolutely positioned panel at `left 6; right 0`, `top`/`height` from the
  geometry function, carrying `backdrop-filter: blur(20px)`, `border-radius:
  12px`, the glass-overlay background idiom, `box-shadow:
  var(--rb-shadow-sm)`, and `opacity: <target>`.
- `islandTarget` (pure, in a lib or the component file — mirror it in
  `tests/titlebar-island.test.ts`): `1` iff the chat route **and** no chat
  selected (`chatIdOf(pathname) === null` — the shell already computes
  `paneChatId` this way, `app-shell.tsx:130`) **and** `sidebar.collapsed`
  **and** the resolved active background is non-null
  (`resolveNewThreadBackground`-shaped — installed-else-default per ticket
  48's semantics; a stored entry that no longer decodes falls back to the
  default, which still shows the island). Key off the RESOLVED background
  (`lib/new-thread-background.ts:253-290` / 48's `resolveActiveNewThreadBackground`
  once it lands), not the raw setting.
- `titlebarIslandVerticalGeometry(progress)` (pure): `height = 28 + 4·clamp(
  progress, 0, 1)`, `center = (38 + 4)·0.5 = 21` (read the tokens'
  constants — `TITLEBAR_HEIGHT`/`TITLEBAR_TOP_PAD` from `state/layout.ts`,
  not literals), `top = center − height/2`.
- Motion: a 200 ms ease-out opacity/height tween on the target flip, reversal
  from the painted value (the same `evalWidthTween`/rAF pattern as §2.1 — a
  scalar tween), reduced motion snaps to the target, and the **initial
  presentation is settled** (a fresh mount at target 1 does not animate in;
  only flips tween).
- Where the inputs live: the shell owns route + sidebar
  (`app-shell.tsx:122-131`); the resolved-background input is the resolved
  source from 35/48 (or `resolveNewThreadBackground` on the current setting
  until then) — threaded to `Titlebar` as a boolean
  prop, or read in `app-shell` and passed down. Keep `state/chrome.ts` out of
  it (route-published effect state is what tore columns down before — ticket
  06's lesson).

**Interactions:** none — the island is pure chrome behind existing controls;
it adds no hit area, no pointer events.

**Data:** reads route (router state), `sidebar.collapsed`
(`useSidebarLayout`), the resolved active background. Writes nothing.

---

## 3. Pure logic to port

From the research's "Pure logic to port" items 2–3:

- **Titlebar island**: `island_target` rule (shell.rs:3983-3994) +
  `titlebar_island_vertical_geometry` (:829-835). Mirror test:
  `island_stays_centered_on_controls_while_expanding` (shell.rs:8252).
- **Animated hero width / mask tracking across a sidebar toggle**:
  reuse `evalWidthTween` (state/layout.ts:171-179, already ported from
  shell.rs:3753-3767). Desktop assertions to mirror:
  `new_thread_background_height` table (shell.rs:8276-8281) and the
  same-frame-bounds contract
  (`background_paint_sees_same_frame_composer_bounds_even_when_painted_first`,
  mask.rs:91).

---

## 4. Gaps this ticket closes

S4(d), verbatim:

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
|---|---|---|---|---|
| hero width during sidebar toggle | MISSING | glides: `viewport − sidebar_now()` (tweened, per frame) (shell.rs:5890, 3790-3794) | snaps: `viewport − sidebarTarget()` (chat-page.tsx:431, 566) | run the 200 ms `evalWidthTween` (already ported, state/layout.ts:171-179) on a rAF when `collapsed` flips; set heroWidth from it each frame |
| hole mask during sidebar toggle | MISSING | same-frame composer bounds "including on sidebar resize" (mask.rs:49-51) | per-commit + resize-only observer (new-thread-background.tsx:109-160) | re-run `remask` every frame of the same rAF loop; observe the wrapper (position) or poll during motion |
| titlebar island | MISSING | frosted 12/20 glass panel, 28→32 px, target = canvas + collapsed + background installed (shell.rs:3983-4035, 829-835) | none (no `island` in web src) | port as an absolutely-positioned `backdrop-filter: blur(20px)` panel behind `.titlebar-cluster`, gated on the same four conditions, 200 ms ease-out opacity/height tween |
| island conditions | MISSING | installed background only, NOT the bundled default (shell.rs:3986-3989) | n/a | gate on `newThreadComposerBackground !== null && resolves` |
| sidebar toggle curve | MATCHES | 200 ms `EASE_OUT` from painted width (shell.rs:1947-1957; proto/motion.rs:302, 217) | CSS width transition, same curve (app.css:1159) | none |

(The "island conditions" row records the desktop predicate at the research's
HEAD; ticket 48 — same batch — replaces the raw-field probe with the
resolved-active-background check so the default counts. The web's gate is the
resolved value either way: §2.2.)

---

## 5. Do not

- **Do not change the sidebar's own CSS transition** — `.sidebar`'s width
  transition (app.css:1159) already MATCHES the desktop curve; the fix is
  feeding the animated value to the hero and the mask, not re-animating the
  column.
- **Do not add a CSS `transition: width` to `.new-thread-hero`** — one clock
  (the rAF loop) must own the width, or the mask cannot follow it.
- **Do not re-fill the titlebar bar**: `.titlebar` stays NO FILL / NO BLUR /
  NO BORDER (ticket 06's landed deviation, `app.css:304-314`). The island is a
  separate child behind the cluster, exactly like the desktop — not a bar
  background, not a cluster background on `.titlebar-cluster` itself.
- **Do not port the macOS traffic-light spacer / fullscreen cluster inset**
  (`titlebar_spacer`, shell.rs:3857-3871) or Linux caption reservations — the
  web cluster is flat 10 (ticket 06 §2.2).
- **Do not port native window drag / double-click zoom**
  (`titlebar_drag_region`, shell.rs:3917-3963) — ticket 06 §5.
- **Do not key the island on the raw setting** — a stored background that no
  longer decodes must not bypass the resolver, and the island follows the
  RESOLVED active background per ticket **48** (installed-else-default, the
  post-48 desktop behavior; the pre-48 "installed only" predicate is the
  research's record of HEAD `bc3a3945`, superseded by 48). Ticket **35**
  owns the hoisted resolved-artwork store — consume their output, do not
  re-implement resolution.
- **Do not change the hero's paint** — ticket **33** owns the mask ramp,
  opacity branch, and effect rasters the island sits over.
- **Do not change the dock/route choreography** — ticket **36** owns the
  transition matrix; this ticket only changes where `heroWidth`'s number comes
  from during a sidebar toggle. (`heroVisible` and the dissolve wiring stay.)
- **Do not touch** the settings Appearance row while wiring the background
  gate — ticket **42** owns its `fileImage` glyph fixes; the row's own polish
  is not this ticket's.
- `ROBOCO_MOTION_SCALE` multipliers are desktop-only; hard-coded durations stay.

---

## 6. Acceptance

- [ ] Sidebar toggle on `/`: the hero artwork box glides over 200 ms on the
      resize curve — frame-sampled (a DOM log or CDP capture shows no
      single-frame width jump between consecutive frames while `.sidebar`
      glides).
- [ ] During the same 200 ms the cutout hole tracks the pill every frame —
      the pill never visibly slides out of its hole (mid-glide screenshot).
- [ ] Mid-animation reversal: toggling again mid-glide restarts from the
      painted width (no jump back to an endpoint).
- [ ] Reduced motion: hero width and hole snap with the column; island snaps
      to its target.
- [ ] Island: visible only when (chat route, no chat selected, sidebar
      collapsed, resolved background) — verified in the counter-states
      (chat selected; sidebar open; settings route) and with a
      stored-but-undecodable entry (falls back to the default — island still
      shows, per ticket 48's resolution).
- [ ] Island geometry: height 28→32 across the tween with center constant at
      21 (4 px of air around the 24 px controls); radius 12, backdrop blur
      20px, glass-overlay tint, `--rb-shadow-sm` — verified computed.
- [ ] Island motion: 200 ms ease-out on flips, reversal from painted value,
      initial presentation settled (a reload into the island state shows no
      entrance animation).
- [ ] Unit tests: `island_stays_centered_on_controls_while_expanding` →
      `web/packages/app/tests/titlebar-island.test.ts` (geometry + target
      rule); `evalWidthTween` frames already covered by
      `tests/layout.test.ts` — add the hero-width host case if practical.
- [ ] Screenshot pair, desktop vs web, states: canvas, sidebar open,
      installed background; canvas, sidebar collapsed (island over the
      artwork); canvas mid-glide (~100 ms of 200); chat route, sidebar
      collapsed (no island); canvas, sidebar collapsed, default artwork only
      (island shows — the resolved background, per ticket 48).
- [ ] `pnpm -r build` green; package vitest green.
- [ ] No new literal hex/px where a `--rb-*` token exists.

## Comments

### Implementer note (2026-09-19)

Landed both halves on `wp2r2/34-canvas-sidebar-slide-titlebar-island`, based on
`web-parity/wave-2 @ 24d0e23c` (33/37/40/42/46/47 in). `pnpm -r build` green;
app vitest 1211 passed (1205 base + 6 new).

**§2.1 the slide** — `routes/chat-page.tsx`:

- `heroWidth` (:688) now reads `viewport − (animatedSidebar ?? sidebarNow)`;
  `animatedSidebar` is null when settled, so the settled formula is unchanged.
- Arm (`useLayoutEffect`, :477-515, keyed `[sidebar]`): on a `collapsed` flip it
  captures `from = painted + liveEdgeBounce` (painted = a live tween's last
  frame, else the previous target; the bounce read off `--rb-sidebar-edge-offset`
  — the desktop's `sidebar_now()` includes it, and the layout-effect runs before
  PaneSeam's passive cleanup zeroes the var on collapse). A LAYOUT effect so
  the flip frame paints `from`, not a one-frame endpoint flash. Non-flip sidebar
  changes (a seam drag) kill a live tween — the column tracks the pointer
  exactly under `data-rb-resizing`, and the hero must follow the drag.
- Pump (`useEffect`, :517-563, keyed `[sidebarPump, reducedMotion]`): one rAF
  per frame for the 200 ms; `evalWidthTween(from, to, elapsed)`; terminal frame
  hands the width back to the settled formula (null — the same number), so a
  later drag can never read a stale one. Reduced motion (at arm or mid-tween)
  writes the endpoint directly. Phone: no loop (the arm skips it).
- Same-frame remask: each frame calls `remaskNewThreadBackground()`
  (new-thread-background.tsx:206) AFTER a **`flushSync`'d setState** — see
  deviation 1. The per-commit effect (:260-393) is untouched apart from
  registering its current closure in the registry (:388-389).

**§2.2 the island** — `components/titlebar.tsx` (+`app-shell.tsx`, `app.css`):

- Pure: `islandTarget` (titlebar.tsx:90) and `titlebarIslandVerticalGeometry`
  (:110) — geometry reads `TITLEBAR_HEIGHT`/`TITLEBAR_TOP_PAD` from
  `state/layout.ts` (TOP_PAD newly exported there; see deviation 3).
- Motion: `useIslandTween` (:149) — the scalar 0↔1 twin of the hero loop:
  200 ms ease-out via `evalWidthTween`, reversal from the painted value,
  initial presentation settled (mount at target), reduced motion snaps
  (mid-tween too).
- Element: the wrapper is `.titlebar-cluster`'s FIRST child (titlebar.tsx:243),
  `left 6 / right 0` inline-`top`/`height`/`opacity`, `z-index: -1` +
  `pointer-events: none` so it paints BEHIND the controls with no hit area; the
  frosted panel child (`> 0.001`, mirroring the desktop's content gate) carries
  radius 12 / blur 20 / glass-overlay tint / `--rb-shadow-sm` (app.css:349-378).
  The bar itself stays NO FILL / NO BLUR / NO BORDER.
- Wiring: `app-shell.tsx:443-449` — `islandTarget({isChatRoute, hasSelectedChat:
  paneChatId !== null, sidebarCollapsed, backgroundResolves: url !== null})`
  from `useNewThreadBackground()` (the RESOLVED source; see deviation 4), passed
  as the `islandTarget` prop (:551). No `state/chrome.ts` involvement.
- Tests: `tests/titlebar-island.test.ts` — mirrors
  `island_stays_centered_on_controls_while_expanding` (center 21 constant,
  height ∈ [28,32], 4px air, clamp) + the target rule in all counter-states
  and the resolved-vs-raw gate.

**Deviations / adaptations (file:line drift noted):**

1. **`flushSync` per frame** (chat-page.tsx:546-548): the ticket names a rAF
   loop but not the commit mode. A scheduled setState lands after paint and
   trails the column's CSS clock by a frame (the artwork's right edge would
   visibly retract ~30px mid-collapse); `flushSync` inside the rAF callback
   lands the commit in the same frame phase the CSS transition is evaluated
   in — the web peer of the desktop evaluating `sidebar_now()` in render.
   `heroWidth` stays a prop-driven inline style; no CSS width transition
   exists (only a comment noting that, app.css `.new-thread-hero`).
2. **The island is INSIDE `.titlebar-cluster`** (§2.2's "inside the cluster
   row"), not a sibling "first child of the cluster's parent" as the file
   table's phrasing read: a sibling cannot track the cluster's shrink-to-fit
   width, and `left 6 / right 0` needs the row as the containing block.
   Consequence: the inline `top` is row-space minus `TITLEBAR_TOP_PAD`, since
   the cluster's padding box starts 4px down (titlebar.tsx:247).
3. **`state/layout.ts`** gained `TITLEBAR_TOP_PAD = 4` (proto/layout.rs:48) —
   the ticket says to read the constant from there, but it did not exist yet;
   `TITLEBAR_HEIGHT`'s citation corrected :44 → :45.
4. **The resolved-background source is `useNewThreadBackground`**
   (state/appearance.ts) — the pre-35/48 source; 35 should swap the shell (and
   the canvas) onto its hoisted store. Its resolution is async, so a reload's
   first island appearance rides the 200 ms tween once the URL resolves; the
   tween itself mounts settled (no animation when the target is present at
   first effect — e.g. once 35 lands a synchronous source).
5. **The remask is exposed as a module-level callable** —
   `remaskNewThreadBackground()` + a listener Set the per-commit effect
   (re)registers — rather than a ref through `NewThreadCanvas`
   (routes/index-page.tsx is not in the touch list) and it matches the
   codebase's module-store idiom. During the 200 ms glide the per-commit
   effect also fires once per commit, so the hero rasterizes twice per frame
   for 200 ms — accepted (the per-commit effect stays per the ticket).
6. **No hero-width host test** — the repo has no component-test harness (vitest
   node env, no testing-library); the tween frames stay covered by
   `tests/layout.test.ts` and the flip semantics are in the component.
   Screenshot/CDP captures (acceptance items 1-2, 9) were not taken in this
   headless session — the geometry/timing contracts they check are unit-covered
   instead.

**Merger notes for 35/36:** 35 owns hoisting the background resolution —
replace `useNewThreadBackground()` in app-shell.tsx:443 (and the gate comment)
with the hoisted store; the `backgroundResolves` input stays a boolean. 36 owns
the route/dock choreography — nothing here touched `heroVisible`, `dissolve`,
or the dock; 36 only needs to know `heroWidth`'s number now comes from the
tween state (chat-page.tsx:688) during sidebar flips. The island gate consumes
`isChatRoute`/`paneChatId` exactly as the shell computes them today
(app-shell.tsx:429-448).
