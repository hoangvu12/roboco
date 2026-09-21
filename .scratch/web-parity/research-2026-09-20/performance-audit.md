# Web performance audit — new-chat page + chat switches (web-parity wave-2)

**Scope:** `web/packages/app` at `web-parity/wave-2` (HEAD `32876f8f`, 2026-09-20).
Read-only audit; every claim carries `file:line`. Companion to the user's
directive: *"we still prefer performance, so it doesn't feel laggy while using"* —
the web is not gpui; the UI does not render on GPU by default, so every
per-frame React render, every width animation, and every `backdrop-filter` is
charged against the same main thread that must also stream text.

---

## Internet research digest (the durable rules)

Source: web.dev — the grounding for every classification below.

1. **The pixel pipeline** — https://web.dev/articles/rendering-performance
   Every frame runs JS → Style → Layout → Paint → Composite. Work earlier in
   the chain invalidates everything after it. Animating a **layout property**
   (`width`, `height`, `left`, `top`, `right`, `padding`, `margin`,
   `flex-basis`, `grid-template-*`) runs Style+Layout+Paint **every frame**.
   Animating a **paint-only property** (`background`, `color`, `box-shadow`)
   skips layout but still repaints. Only **`transform` and `opacity`** are
   compositor-only: the browser moves/fades an already-rasterized layer and
   skips Style (recalc), Layout and Paint entirely. The frame budget for
   animation work is **≈10ms** — everything that cannot fit produces dropped
   frames, which users read as "laggy", not as "slow".
2. **High-performance CSS animations** — https://web.dev/articles/animations-guide
   Prefer `transform`/`opacity` animations; a width change that must be
   animated can often be expressed as `transform: scaleX()` on a layer whose
   layout width is already at its endpoint. `will-change` (or any
   transform-hint) promotes an element to its own compositor layer — use it
   sparingly and deliberately: every promoted layer costs GPU memory and too
   many layers de-batch painting.
3. **`backdrop-filter` is an ongoing paint, not a style** (follows from the
   two web.dev rules + Chrome compositing docs): a blurred surface must
   re-filter **whatever pixels move underneath it, every frame they move**.
   Over a streaming transcript (constant repaint) or a width-animated column,
   a `backdrop-filter: blur(16–44px)` region is re-filtered per frame — the
   most expensive thing you can put over moving content. This is why the
   desktop gpui app can afford frost (GPU-side window effects) and a browser
   cannot, at the same fidelity.

In one line: **per-frame React state, layout-property transitions, and
`backdrop-filter` over moving content are the three ways this app currently
spends the 10ms budget.** All three appear simultaneously in the two reported
symptoms.

---

## S1 mechanism — the new-chat page

### S1(a) Sidebar toggle on the new-thread canvas

What runs per frame of the 200ms toggle (route `/`, hero mounted, composer
docked at canvas anchor):

**Frame N of the toggle, in order:**

1. **rAF pump + `flushSync` full-tree re-render** — the tween loop at
   `routes/chat-page.tsx:554-589`: one `requestAnimationFrame` per frame; at
   `chat-page.tsx:570-574` it evaluates `evalWidthTween`
   (`state/layout.ts:171-179`, the 200ms `RESIZE` easeOut curve) and calls
   **`flushSync(() => setAnimatedSidebar(value))`** — a *synchronous* render +
   commit of the entire `ConversationPage` tree inside the rAF callback:
   `NewThreadCanvas` → `NewThreadBackground` (3 canvases), `Composer`
   (`components/composer.tsx`, ~3000 lines, not memoized), `QueuePanel`,
   `StatusStrip`, `ComposerFooter`, `TerminalDock`, the titlebar identity.
   Cost class: **JS (React render + commit) per frame** — this alone can
   exceed the 10ms budget.
2. **Hero canvas re-raster ×2** — right after the flush
   (`chat-page.tsx:575`), `remaskNewThreadBackground()`
   (`components/new-thread-background.tsx:207-214`) runs the mounted hero's
   remask closure (`new-thread-background.tsx:251-389`). Per remask, TWO
   passes run (`new-thread-background.tsx:366-367`: reveal + cutout), and each
   pass (`:305-365`):
   - re-allocates the canvas backing store when the hero rect changed
     (`:311-316` — and it changed, because the flush wrote a new inline
     `width` on `.new-thread-hero` at `:400`),
   - `drawImage` of the full cover-fit artwork (`:327-333`),
   - `cutoutMaskRaster` — a **full CSS-resolution `Float32Array` grid**
     (`lib/new-thread-background.ts:239-274`): a per-row `out.fill` over the
     whole hero plus the per-pixel smoothstep/SDF loop inside the hole band,
   - a **full `new ImageData(w, h)`** alloc + a 4-byte-per-pixel fill loop
     (`:353-360`), `putImageData` (`:361`), and a `destination-in` full-canvas
     composite (`:362-364`).
   On a 1512×547 hero that is ≈3.3MB per Float32 grid + ≈3.3MB per ImageData,
   ×2 passes ≈ **13MB of allocations and ~1M+ pixel-loop iterations per
   remask**. And it runs **twice per frame**: the per-commit layout effect has
   **no dependency array** (`new-thread-background.tsx:251-389` — runs on
   every commit and schedules its own rAF remask at `:371`), so the flushSync
   commit schedules one remask for the next frame while the tween loop fires
   its own in this one — the "2 rasters per frame was accepted" decision from
   ticket 33/34's merger notes (`issues/33-…md:206`, `issues/34-…md:38`) is
   exactly the lag. Cost class: **JS + Paint + GPU upload per frame**.
3. **Column layout cascade (browser-managed, still layout)** — the CSS
   width transition on `.sidebar` (`styles/app.css:1273`, 200ms easeOut — the
   exact desktop curve) reflows the whole three-column flex row every frame:
   `.main` is the flex remainder, `.chat-column` resizes per frame, its
   `ResizeObserver` (`chat-page.tsx:683-696`) fires per frame →
   `setColumnWidth` → **another (scheduled, non-flushed) full page re-render
   per frame**; `composerWidthTarget` (`chat-page.tsx:780-781`) follows it →
   the composer's `availableWidth` prop changes per frame → the composer's
   layout pass `evaluateRef` re-runs per frame
   (`components/composer.tsx:873-878`, effect keyed on `availableWidth`) →
   `el.style.height` write + `setLayout` **setState per frame**
   (`composer.tsx:840,857`). Cost class: **Style + Layout + Paint per frame,
   plus JS**.
4. **Parallel layout-property transitions** riding the same 200ms:
   `.titlebar` `padding-left` (`app.css:334`) — the identity row glides, so
   the titlebar re-lays-out per frame; `.pane-seam-sidebar` `left`
   (`app.css:1193`) and `.pane-seam-right` `right` (`app.css:1198`) —
   absolutely positioned, still Style+Layout per frame. All are **layout**
   animations (web.dev rule 1).
5. **The titlebar island loop** — the same click flips `sidebar.collapsed`,
   which flips the island target (`components/titlebar.tsx:206-240`): a second
   rAF loop, `useIslandTween` (`titlebar.tsx:149-204`), does
   `setPainted(...)` **per frame** (Titlebar re-render per frame) for 200ms,
   while the island panel itself is a `backdrop-filter: blur(20px)` surface
   (`app.css:421-428`) sitting **over the hero artwork that is moving every
   frame** — constant backdrop re-filtering (web.dev rule 3). The island is
   visible *exactly* in the reported state (canvas route, sidebar collapsed)
   — `titlebar.tsx:229-239`.
6. **The composer pill's own `backdrop-filter: blur(16px)`**
   (`app.css:3248-3249`) floats over the hero canvas that is being fully
   repainted per frame (item 2) — re-filtered per frame.

**Top 3 cost sources for S1(a), ranked:**

| # | Source | file:line | Pipeline cost per frame |
|---|--------|-----------|-------------------------|
| 1 | `flushSync` full-page re-render per rAF (tween loop) | `routes/chat-page.tsx:554-589` (flush at `:572`) | JS (React render+commit of the whole conversation tree) |
| 2 | Double full-canvas remask (2 paint pipelines + 2 Float32 grids + 2 ImageData + canvas realloc + GPU upload) | `components/new-thread-background.tsx:251-389` via `:575` | JS + Paint + Composite upload |
| 3 | Width/padding `transition`s re-laying out the row every frame, amplified by the columnWidth ResizeObserver → composer evaluate cascade | `app.css:1273,334,1193,1198`; `chat-page.tsx:683-696`; `composer.tsx:873-878` | Style + Layout + Paint |

(The `backdrop-filter` surfaces — island + pill + queue panel — are a
constant multiplier on top of all three, which is why the defrost directive
exists.)

### S1(b) New-thread → existing chat (the dock choreography)

The route change keeps one fiber (both routes render `ConversationPage`,
`routes/chat-page.tsx:78-101`), `hasSelection` flips, and:

1. **The render-phase tick + re-render** — `chat-page.tsx:662-665` ticks the
   dock *during render* and publishes through `setDockFrameState` (React
   discards the pass and re-renders immediately). One extra full render of
   the page on the navigation commit.
2. **The dock pump: a per-frame page re-render for 420–470ms** —
   `chat-page.tsx:760-773`: while `dockFrame.active`
   (`lib/composer-dock.ts:433-489`, critically damped glide, 0.420s dock /
   0.47s undock) every rAF does `setDockFrameState` + `setDockPump` → the
   entire page (including the freshly mounted `TranscriptView`, not
   memoized) re-renders per frame. `TranscriptView` mounts with the new
   chat's rows in the same commit (the swap, §S2) so N rows re-render per
   frame for ~0.5s. Cost: **JS per frame**.
3. **Per-commit layout-effect work** — `chat-page.tsx:709-748` runs on every
   one of those frames: `stack.getBoundingClientRect()` (forced layout read),
   `wrapper.style.transform` write (fine — compositor), and the bottom-chrome
   publication `column.style.setProperty("--rb-bottom-stack", …)` +
   `bottomClearance.set(height)` (`:741-747`) — the pill height glides with
   the dock amount (`composer-dock.ts:127-135` via
   `composer.tsx:780-821`), so the measured stack height changes **every
   frame**, and every >0.5px change re-renders the transcript surface
   (subscribers of `bottomClearance`, `state/layout.ts:505-534`, pad +
   fade-band per frame). Cost: **Layout read + Style + JS**.
4. **The hero dissolve → remask per commit** — `heroVisible` stays true
   while the dock is active (`chat-page.tsx:793`,
   `composer-dock.ts:328-330`), and the per-commit remask effect
   (`new-thread-background.tsx:251-389`, no deps) re-runs its two-pass
   full-canvas pipeline on **every commit of the pump** (~25 commits over the
   glide), because the composer surface (the hole's target rect) is moving
   every frame. Cost: **JS + Paint per frame**.
5. **The composer pill's morph per frame** — `composer.tsx:882-893` (rAF
   `setTick` while morphing) + the evaluate pass writing
   `el.style.height` per frame (`composer.tsx:840`); the wrapper's inline
   `width`/`opacity` (`chat-page.tsx:1066-1069`) and `.chat-body`'s
   inline `opacity`/`translateY` (`chat-page.tsx:1009-1021`) also change per
   frame — these last two are compositor-friendly (opacity/transform), the
   height is layout but confined to the pill.
6. **Artwork readiness loop** — only on a cold artwork id:
   `NewThreadArtworkStore#evaluate → #scheduleFrame`
   (`state/appearance.ts:256-285`) re-renders via rAF while the 120ms fade
   runs (`:111-116`). Small, bounded.
7. **Backdrop-filter over all of it** — the composer pill
   (`app.css:3248-3249`) and the queue tray (`app.css:2726-2727`,
   `rb-fade-quick` enter at `:2730`) sit directly over the transcript that
   just mounted and the hero dissolving behind it — re-filtered every frame.

**Top 3 cost sources for S1(b), ranked:**

| # | Source | file:line | Pipeline cost per frame |
|---|--------|-----------|-------------------------|
| 1 | Dock pump re-renders the whole page (with the mounted transcript) per frame for ~0.5s | `routes/chat-page.tsx:760-773` | JS |
| 2 | Per-commit double remask while the composer surface glides (dissolve) | `components/new-thread-background.tsx:251-389` | JS + Paint |
| 3 | Bottom-clearance feedback: pill height glides → `--rb-bottom-stack` + `bottomClearance` per commit → transcript re-render per frame | `chat-page.tsx:741-747`; `state/layout.ts:505-534` | Style + Layout + JS |

---

## S2 mechanism — chat→chat switch ("scrolls down, closes the group tabs, every time")

### What the web does

A chat→chat switch is same-column (`docked` stays true, the dock never arms —
`lib/composer-dock.ts` case C, `issues/36-…md:170`), so the dock choreography
is *not* the cost. The cost is the transcript swap:

1. **Retain-paint window, then a full remount.** The new chat's
   `TranscriptStore` is created per `chatId` (`chat-page.tsx:200-209`); until
   its first frame loads, the outlet keeps the *old* chat's rows painted
   (`chat-page.tsx:812-855`, `lastPaintedTranscriptRef` at `:842-853`, the
   occluding `.departing-veil` at `:1045`, CSS `app.css:2687-2691`). The load
   flips on the offline-cache seed (`state/transcript-store.ts:353-365` →
   `seedEntries` `:415-422`) or the live frame (`:488-494`). Then
   `TranscriptView` **remounts** — it is keyed `active.docId`
   (`components/transcript.tsx:210`): one synchronous React commit unmounts N
   old rows and mounts N new rows. Big JS spike, once.
2. **A fresh virtualizer: every row mounts on an *estimate*, then measures.**
   `transcript.tsx:669-706` computes prefix sums with
   `heights.get(id) ?? estimateRowHeight(...)` (`:683-685`); real heights land
   in ResizeObserver batches (`:747-767`, `bumpMeasure` per batch) → re-render
   + prefix-sum recompute → `scrollHeight` changes stepwise over many frames.
3. **The scroll behavior during that settle window — the "it scrolling down":**
   - `applyRestoredViewport` polls up to **12 rAF frames** waiting for the
     saved anchor row to measure (`transcript.tsx:929-966`) — during the poll
     the scroller sits at `scrollTop ≈ 0`, showing the chat from the TOP,
     then jumps (`stick.restoreViewport` `:1000-1004` — instant write, or
     `snapToEnd` `:978/986/1011`).
   - If the chat was left pinned at the bottom (or has no saved viewport),
     `snapToEnd()` runs once (`components/stick-controller.ts:183-194`) —
     but the pin stays engaged, and the per-commit effect
     (`transcript.tsx:1072-1085`) calls `stick.kick()` on **every measurement
     batch**, so the mugen stick spring (`components/stick-controller.ts:520-585`,
     `lib/stick-spring.ts:95-120`) **glides** the viewport down through each
     height correction for several hundred ms. That glide is the visible
     "scrolling down" — and it re-fires on every batch, so it reads as the
     page animating itself.
4. **The tool-group motion replay — "try to close the opened group tabs" and
   "animate the grouped tool calls":**
   - Fold state does not survive the switch: `ToolGroupMotionStore` is one
     per *surface* mount (`transcript.tsx:324`), so a fresh mount has **no
     folds**; `open` falls back to `effectiveAutoOpen`
     (`components/tool-group.tsx:126-127`), and `autoOpen` is built from the
     entry's streaming status (`lib/transcript.ts:1499,1547`). A chat whose
     tail was left open (auto-opened while it streamed) arrives **closed**;
     a chat whose tail is still streaming arrives **open + shimmering**
     (`tool-group.tsx:259`, `.tool-shimmer` 3400ms infinite,
     `app.css:8602-8626`) — and when the stream settles/desyncs, the
     rendered-open flip seeds a **fold close tween**
     (`lib/tool-motion.ts:513-526` `noteRendered`, 140ms height collapse,
     `app.css:8628-8632` "JS-driven explicit height").
   - The reveal epochs themselves ARE guarded on switch — the baseline sync
     clears them (`transcript.tsx:326-345` → `tool-motion.ts:539-550`) —
     matching the desktop's `veil_attach_pending` contract. But the
     **shimmer restarts every time**: `sync` sets
     `reveal.shimmerStartedAt = now` for every live group on every sync
     (`tool-motion.ts:569-571`), including the baseline frame.
   - While any reveal/fold is live, **each `ToolGroupRow` runs its own rAF
     loop with `setNow(performance.now())` per frame**
     (`tool-group.tsx:204-215`) — per-frame React re-renders of every group
     row for up to 480ms (`TOOL_ROW_REVEAL_MS`/`TOOL_CONNECTOR_REVEAL_MS`,
     `tool-motion.ts:83-85`).
5. **`backdrop-filter` again**: the composer pill + queue tray blur over the
   rows that are mounting/scrolling/animating — re-filtered every frame of
   the settle window.

### What the desktop does instead (side-by-side)

| Aspect | Desktop (gpui) | Web | Web file:line |
|--------|----------------|-----|---------------|
| Transcript entity | ONE `Transcript` entity; `select_chat` clears rows and re-derives them **atomically** from doc state — no blank, no remount, no estimate→measure cascade | Fresh `TranscriptStore` + **remount** keyed on docId; rows start on estimates and settle over many frames | `crates/ui/src/state.rs:1740-1792` vs `state/transcript-store.ts:353-365`, `components/transcript.tsx:210` |
| Reveal replay on switch | `replay_baseline = veil_attach_pending && !entries_empty` clears `tool_group_reveals` — "prevents a whole existing task tree from reanimating on every chat switch" | Ported (baseline sync) — reveals stay still; **but** folds/shimmer/auto-open close-tween are per-mount and replay | `crates/ui/src/transcript.rs:4059-4072` vs `lib/tool-motion.ts:539-550,569-571,513-526` |
| Panel widths on switch | "snap, no tween — the panels belong to the destination chat": clears `right_tween`, `right_takeover_content_tween`, `main_takeover_tween`, `terminal_tween` | Parity exists: `data-pane-snap` kills the width transition for the key-change commit | `crates/ui/src/shell.rs:1837-1862` vs `components/right-pane.tsx:129-146`, `app.css:1124-1130` |
| Composer morph across the switch | `ROUTE_SNAP` — a session/route change within the window **snaps** the flip morph; "switching sessions (chat↔chat or chat↔new-session) snaps the pill" | No route-snap gate on the chat→chat swap; the pill's local morphs can arm from measurement churn | `crates/ui/src/composer.rs:5849-5874,7290-7304,9233-9234` |
| Scroll on switch | Rows land in one gpui frame; the saved viewport is applied without a spring catch-up phase (there is no multi-frame measurement drift to smooth) | Poll window at the top → jump/snap → **spring glides** through each measurement batch | `crates/ui/src/transcript.rs:3915,3954,4032-4057` vs `transcript.tsx:929-966,1072-1085`, `stick-controller.ts:520-585` |

### Why the web animates every time (summary)

- The remount throws away all per-surface motion state (folds, shimmer) and
  rebuilds the height model from estimates → the spring has something to
  smooth on **every** switch (`transcript.tsx:210,324,683-685` +
  `stick-controller.ts:520-585`).
- The desktop's atomic row swap has no estimate→measure→correct cascade, and
  its explicit snap gates (reveal baseline, panel tweens, ROUTE_SNAP) all
  fire. The web ported the reveal baseline and the pane snap, but **not** a
  scroll snap and **not** a switch-arrival gate for folds/shimmer —
  `stick-controller.ts:183-194` `snapToEnd` exists but the per-commit
  `kick()` at `transcript.tsx:1072-1085` re-arms the spring immediately.
- The desktop does hit the fold/auto-open replay "once in a while" (the
  user's quote) — that is the same auto-open rule on a live tail
  (`transcript.rs:4055-4057`); on the web the *settle cascade* makes it fire
  on every visit to such a chat, and the per-row rAF `setNow` loop amplifies
  it into sustained lag.

---

## The audit tables

### D. `backdrop-filter` sites (12 uses, 12 selectors)

| # | Selector | file:line | Blur | What moves behind it | Verdict |
|---|----------|-----------|------|----------------------|---------|
| 1 | `.titlebar-island-panel` (ticket 34 island) | `styles/app.css:421-428` (426-427) | 20px | The hero artwork during the sidebar glide and the dock dissolve — moving every frame | **DELETE** (worst case: visible exactly on the new-chat page, S1's page) |
| 2 | `.queue-panel` (queue tray behind the composer) | `app.css:2717-2732` (2726-2727) | 16px | The transcript mounting/streaming under the composer; enters with `rb-fade-quick` (2730) while the backdrop beneath is animating | **DELETE** (replace tint with opaque `--rb-raised`) |
| 3 | `.composer-pill` | `app.css:3240-3251` (3248-3249) | 16px | The streaming transcript + the hero canvas repaints — constant re-filter during streaming, scrolling, and both S1 animations | **DELETE** (the single most expensive surface: it sits over moving content at all times) |
| 4 | `html[data-surface="frosted"] .composer-pill` (ticket 33's frosted branch) | `app.css:3253-3264` (3262-3264) | inherits #3 | dead code — `data-surface` is forced opaque (`lib/appearance-store.ts:87-94`, the 2026-09-17 defrost decision) | **DELETE the rule** (dead + unwanted) |
| 5 | `.wizard-panel` | `app.css:3729-3740` (3735-3736) | 16px | The transcript under the wizard swap | **DELETE** |
| 6 | `.popover-card` (pickers/menus) | `app.css:4485-4501` (4496-4497) | 44px | Whatever is under the popover; 44px is the heaviest radius of blur in the sheet | **DELETE** (opaque `--rb-overlay` plate) |
| 7 | `.palette-card` (command palette) | `app.css:4511-4524` (4519-4520) | 44px | The whole page under the palette | **DELETE** |
| 8 | `.modal-card` (dialogs, incl. ticket 49 `RbDialogGlass`, `components/base/dialog.tsx:81-83,143-176`) | `app.css:4890-4897` (4894-4895) | 44px | The app behind the dialog (streaming transcript keeps moving while a dialog is open) | **DELETE** |
| 9 | `.rail-preview` (rail hover card) | `app.css:7250-7266` (7262-7263) | 44px | The transcript under the hover card | **DELETE** |
| 10 | `.jump-pill` (+ its `@supports` fallback at `app.css:7605-7609`) | `app.css:7589-7603` (7596-7597) | 16px | The transcript scrolling under the pill (the pill shows exactly while the list is moving) | **DELETE** (fold the `@supports` block into the opaque recipe) |
| 11 | `.tool-file-badge` | `app.css:8832-8844` (8843) | 16px | Per-row content; repaints during reveals/streaming | **DELETE** |
| 12 | `html[data-surface="frosted"] .diff-file-header` and `html[data-surface="frosted"] .cr-tooltip` | `app.css:12528-12536` (12530-12531), `app.css:12992-12996` (12994-12995) | 16 / 44px | dead code (forced opaque) | **DELETE both rules** |

All 12 use `--rb-glass-overlay-alpha` translucent plates over the blur; every
one keeps a sane opaque fallback by raising the plate's own alpha (see fix
plan P0).

### E. CSS `transition` / `animation` inventory (layout vs paint vs compositor)

`transition:` sites — 150+ in `app.css`; the overwhelming majority are
`background-color`/`border-color`/`color`/`opacity` hover fades (paint-only or
compositor-only, hover-triggered — fine, keep). The ones that animate
**layout** properties:

| Selector | Property | file:line | Class | Runs during heavy interactions? | Notes |
|----------|----------|-----------|-------|-------------------------------|-------|
| `.titlebar` | `padding-left` (200ms) | `app.css:334` | **Layout** | **YES** — every sidebar glide | The identity row rides the column; re-lays out the titlebar each frame. Convert to `translateX` (fix plan P4) |
| `.titlebar-pane-band` | `width` (200ms) | `app.css:609` | **Layout** | Pane open/close + takeover | Driven by `--rb-pane-band` (`app-shell.tsx:590-595`); the band's own width animation is the desktop's `animated_width` semantic |
| `.right-pane` | `width` (200ms) | `app.css:1121` | **Layout** | Pane open/close; **suppressed on chat switch** by `data-pane-snap` (`right-pane.tsx:129-146`, `app.css:1124-1130`) | The outer clip column; inner pinned to the wider endpoint (the `stablePanelContentWidth` trick, `state/layout.ts:69-87`) |
| `.pane-seam-sidebar` | `left` (200ms) | `app.css:1193` | **Layout** | **YES** — every sidebar glide | Absolute overlay; convert to `translateX` |
| `.pane-seam-right` | `right` (200ms) | `app.css:1198` | **Layout** | Pane glides | Same conversion |
| `.sidebar` | `width` (200ms) | `app.css:1273` | **Layout** | **YES** — the S1(a) glide itself | Browser-managed and curve-exact (matches desktop `RESIZE`); the column model is inherent — keep, but stop amplifying it (P1/P3) |
| `.queue-row` | `top` (150ms TAB_SLIDE) | `app.css:2788` | Layout (relative-offset; siblings don't reflow) | Queue drag reorder | Convert to `translateY` |
| `.diff-folding` | `height` (180ms) | `app.css:12887` | **Layout** | Diff fold toggles | Contained clip row; acceptable, or `grid-template-rows` conversion |
| `.history-search` | `width` + `opacity` (200ms) | `app.css:14180-14181` | **Layout** | History pane (user-triggered) | Low priority; `scaleX` on a fixed-width field is visually equivalent |
| `.history-node` | `width` + `height` + `opacity` (hover) | `app.css:14613-14615` | **Layout** | Hover-only in the history graph | Convert to `transform: scale()` — circles, exactly equivalent |
| `.history-node-ring` | `width` + `height` (hover) | `app.css:14627-14628` | **Layout** | Hover-only | Same |
| `UserFoldedBody` inline | `height ${durationMs}ms` | `components/transcript.tsx:1860-1870` | **Layout** | User-bubble Show-more fold (user-triggered, contained) | The desktop does the same tween; keep or `grid-template-rows` |
| `.term-tab` | `transform` + `background-color` | `app.css:5601-5602` | Compositor + paint | Terminal tab reorder | GOOD — the pattern to copy |

JS-written per-frame layout values (not CSS transitions, same pipeline class):
`.persistent-composer` inline `width` (`chat-page.tsx:1066-1067`, glides on
the dock clock via `composer-dock.ts:406-427`), the composer pill's inline
`height` (`composer.tsx:840`), `.new-thread-hero` inline `width`
(`new-thread-background.tsx:400`), the terminal dock's inline `height`
(`terminal/terminal-dock.tsx:142-171`), the right-pane takeover inner width
(`right-pane.tsx:98-127`), the tool-group fold height
(`app.css:8628-8632` + `tool-group.tsx`), the sidebar disclosure heights
(`sidebar-disclosure.tsx:178-208`).

`@keyframes` inventory (22 total) — classification:

| Keyframes | Animates | file:line | Class | When |
|-----------|----------|-----------|-------|------|
| `rb-rise-in` | `opacity` + `translate` | `app.css:2125-2134` | **Compositor-only** ✓ | Page/gate entrance |
| `rb-menu-in` / `rb-menu-out` | `opacity` + `transform` | `app.css:1577-1581`, `4468-4472` | **Compositor-only** ✓ | Popovers/menus |
| `rb-dialog-in` (×2) | `opacity` + `transform`/`translate` | `app.css:4943-4947`, `7642-7646` | **Compositor-only** ✓ | Dialogs/jump pill |
| `rb-fade-in`, `rb-fade-quick` (×2), `cr-tip-in`, `diff-chevron-fade`, `rb-add-space-exit`, `rb-caret-blink`, `rb-skeleton-pulse`, `skeleton-pulse`, `rb-gspin`, `rb-roboco-pulse` | `opacity` / `background` | `app.css:1666,3205,7653,13004,12568,13239,3559,4779,10065,1782,1878` | Compositor/paint ✓ | Various |
| `rb-veil` (streaming text dissolve) | `opacity` | `app.css:8506-8516` | **Compositor-only** ✓ | Per streaming chunk |
| `rb-tool-shimmer` | `background-position` | `app.css:8619-8626` | Paint | 3400ms **infinite** while a group is active — restarts on every sync (`tool-motion.ts:569-571`) |
| `rb-att-sending-scrim` | `background` | `app.css:5386` | Paint | Sending scrim |
| `history-row-in` / `history-row-out` | **`height`** + `opacity` | `app.css:14570-14588` | **Layout** | History pane row add/remove — convert (P4) |

### F. JS-driven animation loops (rAF / setInterval / flushSync)

| Site | Trigger | Mechanism | Per-frame cost | file:line |
|------|---------|-----------|----------------|-----------|
| **Sidebar tween pump** | Sidebar flip on the canvas route | rAF + **`flushSync`** React state per frame, 200ms | Full `ConversationPage` synchronous re-render + remask | `routes/chat-page.tsx:554-589` (flush `:572`, remask `:575`) |
| Dock pump | Route dock/undock choreography | rAF + `setDockFrameState`/`setDockPump` per frame, 420–470ms (+0.32s handoff) | Full page re-render incl. transcript | `chat-page.tsx:760-773` |
| Hero remask per commit | **Every commit** (no deps) | rAF-scheduled two-pass canvas remask | Full-canvas paints ×2 + Float32 grids ×2 + ImageData ×2 | `components/new-thread-background.tsx:251-389` (rAF `:371`) |
| Titlebar island tween | Island target flip (canvas + collapsed) | rAF + `setPainted` per frame, 200ms | Titlebar re-render (over a blur surface) | `components/titlebar.tsx:149-204` |
| Artwork readiness loop | Cold artwork id | rAF while opacity < 1 (120ms) | Store `#evaluate` + listener renders | `state/appearance.ts:256-285` (scheduler `:111-116`) |
| Tool-group row clock | Any live reveal/fold (`motionActive`) | **Per-row** rAF + `setNow` per frame, up to 480ms | Re-render of every group row | `components/tool-group.tsx:204-215` |
| User-fold compensation | Fold toggle | rAF + `stick.writePreserving` per frame (direct scroll writes, no React) | Scroll writes only | `transcript.tsx:1106-1143` |
| UserFoldedBody phase | Fold toggle | 1-shot rAF `setPhase("to")` then CSS height transition | 2 commits + layout transition | `transcript.tsx:1837-1870` |
| Composer morph clock | Height/flip morph in flight | rAF + `setTick` per frame | Composer re-render + evaluate (inline height write) | `components/composer.tsx:882-893` (evaluate `:760-878`) |
| Stick spring | Pin + content growth | rAF (self-rescheduling) + direct `scrollTop` writes | Direct writes (no React); the S2 glide | `components/stick-controller.ts:500-585` |
| Rail scroll glide | Rail tick click | **`setInterval` 16ms** + direct scroll writes, 500ms | Direct writes | `stick-controller.ts:302-317` (`lib/rail.ts:39-45`) |
| Transcript scroll view | Any scroll | rAF-coalesced `setView` per frame | TranscriptScroller re-render per frame (virtualizer input) | `transcript.tsx:826-829` |
| Right-pane takeover inner width | Takeover glide | rAF + **direct style writes** (no React state) | Direct width writes | `components/right-pane.tsx:98-127` |
| Terminal dock height | Terminal open/drag | rAF + direct style writes | Direct height writes | `terminal/terminal-dock.tsx:142-171` |
| Sidebar disclosure | Section toggle | rAF + direct style writes | Direct height/opacity/top writes | `components/sidebar-disclosure.tsx:178-208` |
| Seam edge bounce | Drag hits a clamp bound | rAF + CSS var write on `documentElement` per frame, 220ms | Style recalc of var consumers | `components/pane-seam.tsx:80-91` |
| Composer drag autoscroll | Text-drag past textarea edge | `setInterval` 16ms + direct `scrollTop` | Direct writes | `composer.tsx:934-979` |
| Selection edge autoscroll | Primary-button drag near edge | `setInterval` (`SELECTION_SCROLL_TICK_MS`) + direct writes | Direct writes | `transcript.tsx:867-880` |
| Composer picker anchoring | Popup open | 1-shot rAF | Cursor scroll write | `components/composer-pickers.tsx:525,753` |
| Edit-lease heartbeat | While a queue edit is open | `setInterval` 20s (RPC, not animation) | — | `chat-page.tsx:327-343` |
| Echo/trailer clock | Streaming/pending sends | `useNow` setInterval 1s–10s | Transcript re-render per tick | `state/hooks.ts:34`, `transcript.tsx:307-315` |
| Settings nav focus | Settings route | 1-shot rAF | — | `components/settings-nav.tsx:49` |
| Sidebar scroll sync | Sidebar list | 1-shot rAF per scroll | — | `components/sidebar-body.tsx:84` |
| Titlebar pill measure | Chrome update | 1-shot rAF | — | `components/titlebar.tsx:180` |
| Diff view | File body mount | 1-shot rAF | — | `components/diff-view.tsx:949` |

Note the split: the **direct-DOM-style writers** (right-pane, terminal,
disclosure, seam, spring, glides) are already the cheap pattern — no React
frames. The expensive loops are exactly the five that drive **React state per
frame** (sidebar tween, dock pump, island, tool-group rows, composer tick) and
the canvas remask.

### G. `will-change`, canvas count, ImageData sizes

- `will-change: transform` — exactly two uses, both deliberate:
  `.persistent-composer` (`app.css:2608`, the dock's transform target) and
  `.sidebar-inner` (`app.css:1292`, pins the sidebar subtree so the collapse
  clips instead of re-rasterizing). Correct and sparse per web.dev rule 2 —
  do not add more.
- Canvases on the new-thread page: 2 visible hero canvases
  (`new-thread-background.tsx:421,429` — reveal + cutout) + 1 offscreen mask
  canvas (`:338-348`) + 1 offscreen raster canvas when an effect is installed
  (`:177-190`). Plus the effect worker's rasterization
  (`lib/new-thread-background-effects-worker.ts` — off-main-thread, fine).
- Per remask allocations: `Float32Array(w·h)` + `new ImageData(w, h)` per
  pass ×2 passes (`new-thread-background.tsx:337,353-361`,
  `lib/new-thread-background.ts:248`). For a 1512×547 hero ≈ 3.3MB + 3.3MB
  per pass → **~13MB per remask, ~26MB per tween frame** at the current
  double cadence — the GC churn alone janks the 200ms glide.

---

## Fix plan (ranked)

### P0 — The decided directive: the web is ALWAYS OPAQUE (delete every `backdrop-filter`)

Delete all 12 sites from table D, each replaced by its own opaque plate
(raise the existing `color-mix(... var(--rb-glass-overlay-alpha) ...)` to a
solid `--rb-overlay`/`--rb-raised`/`--rb-dialog` fill; the visual language
survives as "tinted plate", which is what an opaque surface shows anyway):

1. `app.css:421-428` `.titlebar-island-panel` — blur(20px) → opaque
   overlay plate. (Ticket 34's island keeps its geometry/opacity tween; only
   the frost dies.)
2. `app.css:2726-2727` `.queue-panel` — blur(16px) → `var(--rb-raised)`.
3. `app.css:3248-3249` `.composer-pill` — blur(16px) → the existing opaque
   input flatten (`color-mix(in srgb, var(--rb-input) 82%, var(--rb-bg))`
   already at `:3247`; drop the translucency denominator or keep — without
   the blur the translucent plate no longer re-filters, so keeping the tint
   is fine; the blur is the cost).
4. `app.css:3253-3264` `html[data-surface="frosted"] .composer-pill` —
   **delete the whole rule** (dead: `appearance-store.ts:87-94` forces
   opaque; unwanted per the defrost decision).
5. `app.css:3735-3736` `.wizard-panel` — blur(16px) → opaque.
6. `app.css:4496-4497` `.popover-card` — blur(44px) → opaque
   `--rb-overlay` plate.
7. `app.css:4519-4520` `.palette-card` — blur(44px) → opaque.
8. `app.css:4894-4895` `.modal-card` — blur(44px) → opaque (ticket 49's
   `RbDialogGlass` keeps its scrim + geometry; only the card frost dies).
9. `app.css:7262-7263` `.rail-preview` — blur(44px) → opaque.
10. `app.css:7596-7597` `.jump-pill` — blur(16px) → opaque; fold the
    `@supports not (backdrop-filter…)` fallback (`app.css:7605-7609`) into
    the base rule and delete the `@supports` block.
11. `app.css:8843` `.tool-file-badge` — blur(16px) → the existing
    `rgb(var(--rb-ink) / 0.06)` plate is already nearly opaque; delete the
    filter line.
12. `app.css:12530-12531` `html[data-surface="frosted"] .diff-file-header`
    and `app.css:12994-12995` `html[data-surface="frosted"] .cr-tooltip` —
    **delete both rules** (dead frosted branches).

Then the plumbing: `newThreadBackgroundElementOpacity`'s frosted leg
(`lib/new-thread-background.ts:67-87`) and the hero's
`useRootSurfaceTreatment` MutationObserver
(`components/new-thread-background.tsx:83-107`) can stay (pure, tested,
forced-opaque already) — or be simplified in a follow-up; do not let the CSS
keep a reachable frosted path.

**Expected win:** removes the per-frame backdrop re-filter under the
composer pill during streaming, both S1 animations, and every scroll — the
single largest *constant* GPU/paint tax on the page, and the multiplier on
every other animation.

### P1 — Kill the `flushSync` sidebar tween (S1(a) #1 + #2)

Delete the loop at `routes/chat-page.tsx:554-589` (and the arming layout
effect's tween state at `:494-541`, keeping the seam-drag disarm). Ticket 34's
purpose to preserve: *the hero box and cutout hole track the pill as the
column glides over 200ms* (`issues/34-…md:4-8`). Options, ranked:

- **(A) Recommended — browser-managed lockstep (no React frames):** the hero
  already rides an inline width; instead give `.new-thread-hero` a CSS
  width transition with the **same spec as the column**
  (`transition: width var(--rb-motion-resize) var(--rb-ease-ease-out)`,
  matching `app.css:1273`), driven by a `--rb-hero-width` custom property the
  shell writes once at the flip (target value, like `--rb-sidebar-now` at
  `app-shell.tsx:579`). Both transitions start on the same style change,
  share the curve and duration, so the hero and column stay in lockstep with
  **zero JS per frame** (the browser runs Layout for the absolutely
  positioned hero only — it doesn't reflow siblings). Delete
  `animatedSidebar`/`sidebarPump` entirely; `heroWidth` becomes CSS-owned
  (drop the inline width write at `new-thread-background.tsx:400`).
  *Trade-off vs the desktop:* same visual (slide + re-crop per frame), but
  the **mask** no longer tracks the pill mid-glide (see the remask cadence
  fix below) — the hole lands at `transitionend`. If the mid-glide hole/pill
  mismatch is visible: mid-glide set the cutout pass's opacity to 0 and let
  the reveal pass (which has no hole) carry the artwork, snapping the cutout
  back in on settle — an opacity-only, compositor-cheap dodge.
- **(B) Fallback — snap the hero:** keep the column's CSS glide, let the
  hero+mask snap once at the flip (one remask). Maximum performance, loses
  the hero slide only (the pill still glides with the column). Choose this
  if (A) shows lockstep drift on any browser.
- **(C) Transform drawer** (sidebar becomes an overlay translating
  `translateX(-100%)`, conversation column snaps to full width): fully
  compositor-only, but it changes the layout model the seam drag
  (`state/layout.ts:207-220`), `--rb-sidebar-now` consumers
  (`app-shell.tsx:579`, `app.css:1192`), and the titlebar row-left
  (`state/layout.ts:372-386`) are built on. Defer unless (A)+(B) are
  insufficient.

**Remask cadence (S1(a) #2, applies to every option):** stop remasking per
commit and per frame —

- The per-commit effect at `new-thread-background.tsx:251-389` has **no
  dependency array**; scope it to what actually changes the mask geometry
  (`[artwork, dissolve, heroWidth, effect]`) and, better, gate the rAF
  remask behind a *geometry-dirty* flag set by the `#composer-surface`
  ResizeObserver (`:376-383`, the typing-morph path) and explicit calls on
  settle (`transitionend` for the width/`dissolve` glides) and major resizes.
- Delete the per-frame `remaskNewThreadBackground()` call
  (`chat-page.tsx:575`) and its registry
  (`new-thread-background.tsx:207-214`) — the "2 rasters/frame accepted"
  decision is the jank.
- If a per-frame mask is ever genuinely required (hole must track the pill
  mid-glide), restructure so the **artwork is blitted once** into an
  offscreen canvas and per frame only the mask grid is rebuilt
  (`drawImage(offscreen)` + one `cutoutMaskRaster` + one `destination-in`) —
  half the passes and no canvas realloc — or render the mask into a **second
  canvas layer whose `transform` moves** (the hole's shape is constant
  during a horizontal glide; only its position changes — compositor-only),
  compositing the two canvases as stacked elements instead of one
  `destination-in` bitmap.

**Expected win:** S1(a)'s #1 and #2 costs go to zero per frame; the sidebar
glide is then just the browser's own layout of the row — the same class of
cost the desktop pays for its resize, and within budget.

### P2 — Chat→chat switch: snap, don't animate (S2)

Match the desktop's atomic switch:

1. **Scroll snap through the settle window.** `snapToEnd` exists
   (`stick-controller.ts:183-194`) but the per-commit `stick.kick()`
   (`transcript.tsx:1072-1085`) re-arms the spring against measurement
   drift. Add a **switch-arrival window** to the scroller: armed on the
   swap-remount (a ref stamped when `active.docId` changes /
   `lastPaintedTranscriptRef` hands over, `transcript.tsx:210`,
   `chat-page.tsx:842-853`), cleared when measurement batches quiesce (no
   `bumpMeasure` for ~2 frames) or after a hard cap (~500ms). Inside it:
   `kick()` writes `maxScroll()` directly (the `shouldAnchorLiveStream`
   shape, `stick-spring.ts:84-86`, extended to "or switchArrival") instead
   of stepping the spring — the transcript lands and *stays*, no glide.
2. **No fold tweens on arrival.** `noteRendered`'s rendered-open flip seeds
   a close tween (`tool-motion.ts:513-526`) — gate the seeding on the same
   switch-arrival window (render the endpoint, no tween), so a streaming-tail
   chat's groups settle open/closed without animating the close. This is the
   web peer of the desktop's ROUTE_SNAP (`crates/ui/src/composer.rs:5849-5874`):
   *a switch renders its destination state; motion belongs to live streams*.
3. **Shimmer restart:** skip `reveal.shimmerStartedAt = now` on the baseline
   sync (`tool-motion.ts:569-571` — the desktop's `get_or_insert` semantics
   at `transcript.rs:4100` do restart it, but the web's restart lands mid-
   cycle on every switch; only arm it for non-baseline arrivals).
4. **Restore-window flash:** the 12-frame measurement poll
   (`transcript.tsx:938-959`) paints the chat from the top before jumping —
   keep the poll but keep the scroller **opacity 0** (or at the old chat's
   retained pixels, which the veil already covers,
   `chat-page.tsx:1045`) until `applyRestoredViewport` runs, so the user
   never sees the pre-restore top.
5. **Keep (do not break):** live-stream reveal staggering on NEW arrivals
   (`tool-motion.ts:576-582`) — that is the live behavior, not the switch.

**Expected win:** the S2 "scrolling down + closing tabs" disappears; the
switch becomes one heavy commit (the remount) + instant scroll, matching the
desktop's feel.

### P3 — De-React the dock choreography (S1(b))

The choreography stays (it is the product's signature); the per-frame React
frames go:

1. **Stop re-rendering the transcript per pump frame.** The animated values
   the pump produces for the subtree are `transcriptOpacity`,
   `transcriptRise` (`chat-page.tsx:854-855`, applied as inline style on
   `.chat-body` `:1009-1021`) and the composer wrapper's transform/opacity.
   Publish those through **CSS custom properties on the wrapper** (written
   imperatively in the same rAF that ticks `dockRef`, next to the existing
   `wrapper.style.transform` write at `chat-page.tsx:734`) and let CSS
   consume them; `TranscriptView`'s props stop changing per frame, and
   `memo` it (`components/transcript.tsx:436-451`) so the pump's parent
   re-renders don't descend. The dock's own `setDockFrameState` re-render
   shrinks to the chrome that actually animates (composer/footer slots).
2. **Stop the bottom-clearance feedback loop.** During the glide, the pill
   height changes every frame → `--rb-bottom-stack` + `bottomClearance.set`
   per commit (`chat-page.tsx:741-747`) → transcript re-render per frame.
   The destination-footprint design already exists
   (`dockClearanceCorrection`, `lib/composer-dock.ts:143-151`) — publish
   `bottomClearance` only when the *destination* footprint changes (the
   same 0.5px guard at `state/layout.ts:518-526`, evaluated against the
   settled target, not the animated height), and write the CSS var on settle.
3. **Remask on dissolve steps, not commits** (P1's cadence fix covers this):
   during the 420ms dissolve, remask when the composer surface's rect moved
   more than ~2px since the last remask (a dirty-check in the closure) —
   ~10 remasks instead of ~25, none back-to-back with a React commit.

**Expected win:** S1(b) #1 and #3 drop from "full page + transcript per
frame" to "two inline style writes + a small chrome re-render per frame".

### P4 — Transition-table conversions (the E table)

| Convert | From | To | Why equivalent | file:line |
|---------|------|----|----------------|-----------|
| Titlebar row glide | `padding-left` transition | `transform: translateX(var(--rb-titlebar-row-left))` on the row's content wrapper, same 200ms spec (keep a static min-padding for the cluster) | The row's children move as one group; layout never changes | `app.css:334` |
| Sidebar seam | `left: calc(var(--rb-sidebar-now) - 10px)` + `transition: left` | `left: 0; transform: translateX(calc(var(--rb-sidebar-now) - 10px))` + `transition: transform` | Same motion, compositor-only | `app.css:1191-1194` |
| Right seam | `right: calc(...)` | `transform: translateX(calc(-1 * ...))` | Same | `app.css:1196-1199` |
| Queue row drag slide | `top` (relative) | `translateY` | Same motion; no layout invalidation at all | `app.css:2788` |
| History graph nodes | `width`/`height` hover | `transform: scale()` (transform-origin center) | Circles scale exactly like their box grows | `app.css:14613-14615,14627-14628` |
| History rows in/out | `@keyframes height` | `grid-template-rows: 0fr→1fr` (or clip-path inset) + opacity | Same reveal without sibling layout per frame (contained either way — low priority) | `app.css:14570-14588` |
| Diff fold | `height` 180ms | `grid-template-rows: 0fr→1fr` on the fold container | Contained already; optional | `app.css:12885-12888` |
| History search field | `width` + `opacity` | `scaleX` on a fixed-width field (transform-origin left) + opacity | Visually identical for a text field's reveal | `app.css:14171-14185` |
| Keep as-is | `.sidebar`/`.right-pane`/`.titlebar-pane-band` width transitions | — | Inherent to the column layout model; browser-managed; desktop-parity semantics (`stablePanelContentWidth` already prevents inner reflow) | `app.css:1273,1121,609` |
| Keep as-is | All `background-color`/`border-color`/`color`/`opacity` hover fades | — | Paint/compositor-only, hover-triggered — cheap and good | throughout |

### P5 — Per-frame React render hygiene (the F table's remaining loops)

1. **Hoist the tool-group clock:** each `ToolGroupRow` runs its own rAF +
   `setNow` (`tool-group.tsx:204-215`). During a live reveal every group row
   re-renders per frame from N independent loops. Move one `now` clock to the
   surface (the `ToolGroupMotionStore` already has a subscribe/version
   channel, `tool-motion.ts:444-451`) and tick it from a single rAF while any
   row reports `motionActive` — one setState per frame for all rows.
2. **Composer morph tick:** `setTick` per frame (`composer.tsx:882-893`)
   exists to re-run `evaluateRef`; the evaluate pass already writes the
   height imperatively (`:840`) — during a pure height morph, skip the
   `setLayout` state publish (`:857`) and write the dependent inline styles
   directly (the state is only needed when React-rendered geometry follows).
3. **Island tween:** `useIslandTween`'s per-frame `setPainted`
   (`titlebar.tsx:149-204`) writes three inline styles — convert to the
   direct-write pattern (rAF → `style.top/height/opacity`), keeping the
   reduced-motion snap.
4. `setView` per scroll frame (`transcript.tsx:826-829`) is the virtualizer's
   input — keep, but it gains headroom from P0 (no backdrop re-filter while
   scrolling).

---

## What NOT to do

- **Don't touch desktop Rust.** Every fix above is web-side
  (`web/packages/app`); the desktop citations are the reference contract
  (`crates/ui/src/shell.rs:1837-1862`, `transcript.rs:4059-4072`,
  `composer.rs:5849-5874`). The desktop's own GPU-side motion is not the
  problem and is not in scope.
- **Don't break `prefers-reduced-motion` parity.** Every loop being removed
  or converted already has a reduced-motion snap arm
  (`chat-page.tsx:519,563-568`; `composer-dock.ts` `dockReduced`;
  `tool-group.tsx:104-106`; `titlebar.tsx:185-188`; `stick-controller.ts`
  `#reduced`): the replacements must keep snapping under reduce, and the
  audit's CSS conversions must keep their `@media (prefers-reduced-motion)`
  counterparts (e.g. `app.css:3742-3746`, `:2650-2652`, `:1237-1241`'s
  `data-rb-resizing` kill stays untouched for seam drags).
- **Don't remove hover fades.** The ~140 background/border/color/opacity
  hover transitions are paint-only or compositor-only and hover-triggered —
  cheap, and the app's feel. P4 converts only the layout-property ones.
- **Don't delete the dock choreography or the column model.** The 200ms
  column glide, the 420/470ms composer glide, the panel handoff and the
  retain-paint swap are the product's motion identity and desktop parity
  (`issues/34-…md`, `issues/36-…md`); P1–P3 remove their *React-frame and
  canvas cost*, not the motion. Snapping is added only where the desktop
  itself snaps (chat switch, reduced motion).
- **Don't blanket-add `will-change`.** The two existing uses
  (`app.css:2608,1292`) are deliberate; promoting the transcript or rows
  would trade GPU memory for a paper win (web.dev rule 2).
- **Don't reintroduce `backdrop-filter`** "just for dialogs" after P0 — the
  directive is a product decision (the defrost decision,
  `lib/appearance-store.ts:87-94`, now extended to all glass), and it is what
  makes every other animation affordable.
- **Don't use `toDataURL()`/PNG round-trips for the mask** if reworking the
  hero pipeline — ticket 33 explicitly banned per-frame PNG encoding
  (`issues/33-…md:205-207`); use painted canvases and transforms.
