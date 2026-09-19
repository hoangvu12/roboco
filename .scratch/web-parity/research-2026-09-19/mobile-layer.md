# Mobile layer — the phone experience, researched (2026-09-19)

Read-only research at `web-parity/wave-1@bc3a3945`. Scope: the phone layer
(`web/packages/app`, ≤768px) that spec decision 5 declared out of scope
(`.scratch/web-parity/spec.md:29-31` — "The existing phone layer (drawer
sidebar, docked composer) stays as is; do not break it, do not extend it")
and the eleven user-reported symptoms M1–M11. Everything below was verified
by reading the tree; no servers or builds were run. Line numbers are from
this HEAD.

Sibling files in this directory: `new-thread-background-and-transitions.md`
(does not exist yet as of this writing — M5's hero/mask material that lands
there should be cited, not duplicated, once it exists),
`spaces-sidebar-mirroring.md` (M10's owner — not researched here),
`composer-model-picker-send.md` (M11's owner — not researched here).

Base UI fact, verified first because three symptoms depend on it:

- The dependency is **`@base-ui/react` 1.8.0** — not `@baseuijs`, not
  `@baseui-components` (`web/packages/app/package.json:15`). React Aria is
  NOT a dependency (package.json:14-27 has no `react-aria*`).
- That version **ships a complete Drawer component**:
  `node_modules/@base-ui/react/drawer/` exports `Root, Provider,
  DrawerIndent, DrawerIndentBackground, Trigger, Portal, Popup, SwipeArea,
  Content, Backdrop, Viewport, Title, Description, Close,
  VirtualKeyboardProvider` (`drawer/index.d.ts`), re-exported from the
  package barrel (`node_modules/@base-ui/react/index.d.ts`: `export * from
  "./drawer/index.js"`). `DrawerRoot` supports `open`, `modal: true |
  'trap-focus' | false`, `swipeDirection: 'up' | 'down' | 'left' | 'right'`
  (default `'down'`), `snapPoints`, `onOpenChangeComplete`,
  `actionsRef {unmount, close}` (`drawer/root/DrawerRoot.d.ts:7-130`) —
  the same control surface `RbDialog` already uses. It also ships
  `unstable-use-media-query` (`unstable-use-media-query/index.d.ts`,
  subpath import `@base-ui/react/unstable-use-media-query`) with SSR
  options. No component in `src/` uses either today (grep for
  `@base-ui/react/drawer` → 0 hits).

---

## M1 — phone composer padding ≠ transcript padding

### (a) Mechanism trace

The phone composer is inset three times before its text starts:

1. The phone override narrows the composer column instead of its padding:
   `@media (max-width: 768px) { .composer { width: calc(100% - 2 *
   var(--rb-space-sm)); } }` (`app.css:6796-6798`, comment "The pill is
   edge-to-edge on desktop only; a phone needs its gutters"). `--rb-space-sm`
   = 8px (`web/packages/theme/src/index.ts:290` over the artifact's
   `space.sm: 8`, `theme/src/generated/artifact.json` layout.space).
2. The column's own padding is unchanged at phone:
   `.composer { padding: 0 var(--rb-space-lg) var(--rb-space-lg); }`
   (`app.css:2965-2977`) — 16px.
3. The pill's input box adds `padding: 0 8px 0 16px` in compact mode
   (`app.css:3215-3220`).

The transcript's phone gutter is one rule:
`.trow { padding-inline: var(--rb-space-md); }` (`app.css:9050-9053`,
`@media (max-width: 768px)`) — 12px. The desktop's 48px version is
`@media (min-width: 769px)` (`app.css:7007-7011`).

Net on a 375px window: the pill's card edge sits 8+16 = **24px** from the
window edge, the typed text 8+16+16 = **40px**, while chat message text sits
**12px**. The status strip is a third value: `padding-inline: 24px`
(`app.css:7021-7030`).

### (b) Target spec

Desktop model being translated: the composer column and the transcript
content column share one centered axis — the composer is `w_full max-w-3xl
mx-auto` with `px-4` (`composer.rs:7347-7355`, ported at `app.css:2965-2977`)
and the transcript's 736px column sits inside the same 768px cap with 48px
gutters (`app.css:6994-7005`, `7007-7011`), so the pill edge (16px inside
the 768 box) and the message gutters read as one rhythm. On the phone the
transcript is full-bleed with a 12px gutter — the composer should adopt that
same gutter:

| property | value | source |
| --- | --- | --- |
| `.composer` phone width | `100%` (delete the `calc(100% - 2 * --rb-space-sm)` shim) | new rule in the 6738 phone block, replacing `app.css:6796-6798` |
| `.composer` phone padding-inline | `var(--rb-space-md)` (12px) | mirrors `.trow` phone (`app.css:9052`) |
| `.status-strip` phone padding-inline | `var(--rb-space-md)` | `app.css:7030` phone override |
| pill inner paddings | unchanged (16px is the desktop's own inner inset, `composer.rs:7734-7851`) | — |

`.persistent-composer`'s inline `width: composerWidth` px
(`routes/chat-page.tsx:779`) is harmless at phone (the column measures the
full viewport), but the wrapper's phone cap can be `100%` for the same
reason as the `.composer` shim.

### (c) Root cause

The phone layer was written before the parity wave and "kept working" per
decision 5; its composer gutter was an 8px shim (`app.css:6796-6798`), never
reconciled with ticket 18's phone transcript gutter (`app.css:9050-9053`,
landed later with a different value).

### (d) Gap rows

| item | kind | expected | web value (file:line) | fix |
| --- | --- | --- | --- | --- |
| composer column gutter | geometry | 12px, matching `.trow` phone | 8px shim + 16px padding = 24px card / 40px text (`app.css:6796-6798`, `2970`, `3219`) | phone rule: `width: 100%; padding-inline: var(--rb-space-md)` |
| status strip gutter | geometry | 12px | 24px (`app.css:7030`) | phone override to `--rb-space-md` |

---

## M2 — right pane opens from the bottom on phone; tabs invisible

### (a) Mechanism trace

The phone block turns the shell's row into a column and stacks the pane
under the chat:

- `@media (max-width: 768px) { .shell { flex-direction: column; } }`
  (`app.css:6744-6746`, comment "the sidebar leaves the flow for a drawer,
  so the row becomes a stack: the conversation on top, the pane beneath it").
- `.right-pane { width: auto !important; flex: 1; min-height: 0; }`
  (`app.css:6766-6771`) and `.right-pane[aria-hidden="true"] { display:
  none; }` (`app.css:6773-6775`) — the pane, when open, is a half-height
  row below the transcript. Takeover is "the pane, full bleed":
  `.shell-pane-takeover .main { display: none; }` (`app.css:6788-6791`).

The tab strip is invisible for a second, independent reason — its width is
computed by the DESKTOP geometry functions, which still subtract the
sidebar's dragged width at phone:

- `AppShell` computes `paneOpenWidth = resolvePaneWidth({...pane, open:
  true}, viewport, sidebarWidth)` (`app-shell.tsx:179`), where
  `sidebarWidth = sidebarTarget(sidebar)` is the persisted column width
  (e.g. 304) even though the phone sidebar is a fixed overlay out of flow.
  `resolvePaneWidth` → `Math.min(pane.width, rightPaneMaxWidth(viewport,
  sidebar))` (`state/right-pane.ts:707-719`) and
  `rightPaneMaxWidth = max(0, viewport − sidebar − 300)`
  (`state/layout.ts:54-56`): on a 375px window with sidebar 304 this is
  **0**, so `--rb-pane-now` and `--rb-pane-open` are 0.
- The band reads that number twice: `--rb-pane-band` is written from
  `titlebarPaneBandWidth({viewport, paneWidth, rowLeft, takeover})`
  (`app-shell.tsx:514-519` over `state/layout.ts:404-415`), which subtracts
  `rowLeft` — itself `max(sidebar + 16, 136)` = 320 on that phone (see M3) —
  so `avail = 375 − 320 − 6 − 16 = 33` and the band resolves to
  `max(0, min(0 − 6, 33) − 28) = 0`. The CSS consumes it:
  `.titlebar-pane-band { width: var(--rb-pane-band); }` (`app.css:503-510`)
  and `.titlebar-pane-band-inner { width: max(0px, calc(var(--rb-pane-open)
  − 6px − 28px)); }` (`app.css:526-529` → 0). Zero-width band ⇒ "i dont see
  the tabs".

### (b) Target spec

Desktop reference being adapted: the pane is a right-anchored column
(`shell.rs:3826-3855` `right_pane_container` — outer width rides the resize
tween, inner right-anchored at the content width) and its tabs live in the
titlebar band (`shell/tabs.rs:222-297` trailing section; web port
`titlebar.tsx:118-150`, `right-tab-strip.tsx:80-100`). The desktop has NO
phone layout (minimum window sizes make one impossible), so the phone pane
is a mobile-native pattern modeled on the repo's own left drawer:

- The pane column at ≤768 leaves the shell flow and becomes a right-side
  overlay drawer: `position: fixed; top: 0; right: 0; bottom: 0; width:
  min(30rem, 88vw); transform: translateX(100%); transition: transform
  var(--rb-motion-menu-in) var(--rb-ease-ease-out); z-index: 30` — the exact
  mirror of the left drawer at `app.css:6816-6832` (which uses
  `left: 0; width: min(20rem, 85vw); translateX(-100%)`), with the same
  backdrop treatment (`app.css:6834-6844`, `--rb-z-titlebar` stays 40 above
  it per `app.css:247-248`'s documented ladder decision).
- The pane toggle (`titlebar.tsx:148`, `HeaderIconButton "Toggle panel"`)
  stays in the titlebar's trailing group and remains the open/close control
  at every width — the phone equivalent of the one-control-two-meanings
  sidebar toggle (`app-shell.tsx:193-202`).
- The surface tab strip must be VISIBLE on phone. Recommended: render
  `RightTabStrip` as a 38px header row INSIDE the drawer (above
  `.right-pane-body`), reusing the desktop's chip geometry verbatim
  (`right-tab-strip.tsx:30-41`: 112px chips, 4px gap, `shell.rs:6690-6691`).
  The alternative (recompute `--rb-pane-band` for phone and keep the strip
  in the titlebar) fights the 38px band the identity also needs (M3). See
  Open questions Q1.
- `aria-hidden` + `display: none` when closed stays (the mounted-at-zero
  rule exists for the width transition; a transform drawer wants the same
  mounted-hidden shape as the left sidebar: `app.css:6823` transform vs
  `app.css:6773` display none — prefer the transform form so the
  open/close plays the same 200ms slide the left drawer does).
- The phone block's stacking rules (`app.css:6744-6746`, `6766-6791`) are
  deleted in favor of the drawer; `.shell` stays a row.

### (c) Root cause

The phone block predates ticket 07 and treated the pane as "the second
stacked region" (comment at `app.css:6739-6743`), while all pane geometry
(the width functions, the band, `rowLeft`) is computed from the desktop
model where the sidebar is an in-flow column — at phone those functions
return 0/33px and the tab band collapses.

### (d) Gap rows

| item | kind | expected | web value (file:line) | fix |
| --- | --- | --- | --- | --- |
| pane mount at phone | geometry | right-side overlay drawer, slides from right | stacked flex row under `.main` (`app.css:6744-6746`, `6766-6775`) | phone rules → fixed right drawer mirroring `app.css:6816-6832` |
| pane tab strip at phone | component | visible 38px strip with the surface chips | width 0 via `--rb-pane-band`/`--rb-pane-open` = 0 (`app-shell.tsx:179`, `514-519`; `state/layout.ts:404-415`; `app.css:503-529`) | render `RightTabStrip` in the drawer header; OR feed phone-corrected pane widths into the band vars |
| pane width resolution at phone | logic | pane width from the viewport (sidebar is out of flow) | `rightPaneMaxWidth(375, 304) = 0` (`state/layout.ts:54-56` via `app-shell.tsx:179`) | pass `sidebarWidth = 0` at phone (see M3's shared fix) |
| takeover at phone | behavior | drawer full-bleed / expanded state | `.shell-pane-takeover .main { display: none }` (`app.css:6788-6791`) | expanded = drawer at 100vw, same toggle semantics (`state/right-pane.ts:299-301`) |

---

## M3 — chat title invisible in the phone titlebar

### (a) Mechanism trace

The titlebar row pads itself by `--rb-titlebar-row-left` (`app.css:292-316`,
`padding-left: var(--rb-titlebar-row-left)` at line 298), which `AppShell`
writes from the desktop function with the LIVE sidebar width:

- `app-shell.tsx:435-441`: `rowLeft = isChatRoute ? titlebarRowLeft({sidebar:
  sidebarWidth, showsNewSession, takeover}) : TITLEBAR_CONTENT_START`.
- `state/layout.ts:370-384` `titlebarRowLeft` = `max(sidebar + SPACE_LG,
  TITLEBAR_CONTENT_START + plusInset)` — the desktop's `content_left`
  (`shell/tabs.rs:185-186`).
- At phone the sidebar is a fixed overlay (`app.css:6816-6827`) but
  `sidebarWidth` is still the dragged column width (e.g. 304): rowLeft =
  320. On a 375px phone the identity group starts at x=320, and the
  trailing pane toggle (28px + 6px inset, `app.css:480-489`) occupies the
  right end — the title has ~20px, so "i cant see the title of the chat."

The identity itself truncates correctly once given room:
`.titlebar-identity { min-width: 0; overflow: hidden; }` (`app.css:434-441`),
`.identity-title` 12px/500 `text @ 85%` with ellipsis (`app.css:452-460`),
`.identity-folder` hidden at phone already (`app.css:6811-6814`).

### (b) Target spec

Desktop reference: the identity sits right of the window-control cluster
with `TITLEBAR_IDENTITY_GAP` 12 (`state/layout.ts:276`, `tabs.rs:318-350`'s
gap-6 row), inset `content_left = (sidebar_now + SPACE_LG).max(
title_bar_content_start() + plus_inset)` (`tabs.rs:185-186`). Phone
translation — the sidebar term is 0 (it's an overlay), so the title sits
"close to the buttons" exactly as the user asked:

- `TITLEBAR_CONTENT_START` = 136 (`state/layout.ts:355-356`), `+` slot 32
  (`state/layout.ts:280`) → phone rowLeft = 168 with a chat selected, 136
  otherwise; settings route stays `TITLEBAR_CONTENT_START` (104–136) as
  today (`app-shell.tsx:435-441`).
- Title 12px Medium `text @ 85%`, ellipsized; the `folder @ device` tag
  stays hidden at phone (`app.css:6811-6814`). Harness mark stays (14px,
  `titlebar.tsx:870-884` `ChatIdentity`).

### (c) Root cause

One shared defect with M2: `AppShell` feeds the in-flow sidebar width into
the desktop geometry at phone widths where the sidebar is out of flow.

### (d) Gap rows

| item | kind | expected | web value (file:line) | fix |
| --- | --- | --- | --- | --- |
| rowLeft at phone | geometry | `TITLEBAR_CONTENT_START + plusInset` (168/136) | `max(304+16, …) = 320` (`app-shell.tsx:435-441`; `state/layout.ts:383`) | branch in `AppShell`: `const sidebarForGeometry = phone ? 0 : sidebarWidth` — one line fixes M2's band and M3's inset together |
| title visibility | behavior | title readable next to the cluster | ~20px of row (`app.css:298` + 320 inset) | same fix; no CSS change needed (truncation already correct) |

---

## M4 — chat scrolls when the titlebar is held / the drawer is opened

### (a) Mechanism trace — what exists and what is missing

There are NO touch handlers anywhere on the titlebar: `titlebar.tsx` renders
plain buttons (`titlebar.tsx:162-247`) with only `onClick`; no
`onTouchStart/Move`, no drag logic, no long-press timers. The scroll is not
an app feature — it is unguarded browser default behavior reaching the
transcript through one of two verified paths.

What the app has, and lacks, around it:

- `.titlebar` is `position: absolute; inset: 0 0 auto 0; z-index:
  var(--rb-z-titlebar)` = 40 (`app.css:292-316`, `:251`) — it overlays the
  top 38px of the whole window, INCLUDING the open phone drawer (drawer
  z 30, backdrop z 20 — `app.css:6822`, `:6838`; the ladder comment
  documents "The phone sidebar itself stays at 30, deliberately under the
  titlebar — its cluster must stay clickable to close what it opened",
  `app.css:247-248`). So with the drawer open, every touch on the top band
  lands on the titlebar, not the drawer.
- `.titlebar` has NO `touch-action` and NO `user-select` (`app.css:292-316`
  and the cluster/identity rules `:323-471` — the only `user-select: none`
  sites in the file are the drag handles and lightbox, listed below). A
  touch drag that starts on the titlebar therefore (i) is treated by the
  browser as a pan gesture, and (ii) long-presses into the iOS/Android
  text-selection callout on the title's text.
- A pan gesture starting on a non-scrollable element chains to the nearest
  scrollable ANCESTOR. The titlebar's chain is `.shell` (no overflow,
  `app.css:264-279`) → `body` (`overflow: hidden`, `app.css:18-31`) → the
  document. **`html`, `body` have no `overscroll-behavior`** (grep over
  `app.css` finds `overscroll-behavior` only at `:2673`
  `.queue-panel-list`, `:3243` `.composer-input`, `:6896` `.transcript`) —
  and `html, body, #root { height: 100% }` (`app.css:12-16`) with `.shell
  { height: 100% }` (`app.css:264-265`) is the classic layout-viewport
  height that iOS Safari disagrees with across URL-bar states: `overflow:
  hidden` on body alone does NOT stop iOS scroll-chaining/rubber-banding,
  and a 100%-height document whose height tracks the large viewport leaves
  the document scrollable by the toolbar height. A drag held on the
  titlebar scrolls THAT document — the whole chat visibly slides ("the main
  chat keep scrolling up… when i hold the titlebar").
- The drawer's own list is also unguarded: `.sidebar-list { overflow-y:
  auto }` with NO `overscroll-behavior: contain` (`app.css:1232-1239`) —
  a fling that ends at the list's boundary chains to the same document.
  This is the "when i open left sidebar" half of the report: opening the
  drawer puts the user's finger on the titlebar band above the drawer
  (z-order above), and any vertical drag there scrolls the document.
- Second-order mechanism (Android Chrome): a touch near the top edge
  shows/hides the URL bar → `resize` → `useViewportHeight`
  (`routes/chat-page.tsx:828-839`) and `useViewportWidth`
  (`state/layout.ts:538-549`) re-render, the transcript scroller resizes,
  and its ResizeObserver fires `stick.kick()` (`components/transcript.tsx:
  796-800`) — with the bottom pin live the spring rewrites `scrollTop`
  toward the end (`components/stick-controller.ts:510-575`), and an
  own-turn runway re-asserts its held position after "every layout"
  (`stick-controller.ts:626-656`). Those writes are intended (they are the
  ported `wake_spring`), but on a device where mere touching mutates the
  viewport they fire from non-user input.

### (b) Target spec

Mobile-native pattern (the desktop has no touch layer to port; GPUI never
scrolls the window from chrome drags — the desktop titlebar is a
`WindowControlArea::Drag` region handed to the OS compositor,
`shell.rs:3913-3935`). The web phone needs the standard app-shell guards:

| guard | rule | where |
| --- | --- | --- |
| document scroll lock | `html, body { overscroll-behavior: none; height: 100%; }` + `body { position: fixed; inset: 0; overflow: hidden }` OR `height: 100dvh` on `#root/.shell` | `app.css:12-16`, `:264-265` |
| titlebar gestures | `.titlebar { touch-action: manipulation; user-select: none; -webkit-user-select: none; }` (manipulation keeps taps, kills double-tap-zoom and pans) | `app.css:292-316` |
| drawer containment | `.sidebar-list { overscroll-behavior: contain; }` (same as `.transcript`, `app.css:6896`) | `app.css:1232-1239` |
| every interior scroller | `overscroll-behavior: contain` on `.settings-scroll` (`app.css:9424-9429`), `.settings-nav-sections` (`:9448-9455`), `.drawer` (`:5631-5640`), `.add-space-list` | each scroller |
| existing correct sites (leave) | `.lightbox` `touch-action: none` + `user-select: none` (`app.css:5244-5258`), `.files-split-handle` `:6244`, `.files-image-viewport` `:6603`, history column drags `:14186`, `:14208`, `:14396` | — |

### (c) Root cause

The parity wave ported the desktop's scroll semantics for the regions the
desktop HAS (the transcript's contained wheel, the queue list's contained
overscroll — `app.css:2673`, cited from `queue.rs:2004`) but never installed
the mobile app-shell baseline: no `overscroll-behavior` on the document, no
`touch-action`/`user-select` on the always-touched chrome (titlebar), and
100%-height viewports that iOS can still scroll.

### (d) Gap rows

| item | kind | expected | web value (file:line) | fix |
| --- | --- | --- | --- | --- |
| document scroll chaining | CSS | `overscroll-behavior: none` + fixed/dvh body | absent (`app.css:12-31`) | add |
| titlebar touch defaults | CSS | `touch-action: manipulation; user-select: none` | absent (`app.css:292-316`) | add |
| drawer list chaining | CSS | `overscroll-behavior: contain` | absent (`app.css:1232-1239`) | add |
| viewport height unit | CSS | `100dvh` shell (URL-bar stable) | `height: 100%` chain (`app.css:12-16`, `:264-265`) | `100dvh` + `100vh` fallback |

---

## M5 — phone new-thread page is empty ("where is the bg at?")

### (a) Mechanism trace

The phone media block strips everything the desktop canvas has, and the JS
refuses to mount the rest:

- Hero: `@media (max-width: 768px) { .new-thread-hero { display: none; } }`
  (`app.css:6803-6805`, comment "the new-thread hero and its floating
  selector row are desktop-width chrome (spec decision 5): the phone canvas
  keeps the composer in its slot, no artwork, no re-anchoring") — and the
  component is not even mounted: `const heroVisible = (!hasSelection ||
  dockFrame.active) && !phone;` (`routes/chat-page.tsx:565`, `phone` at
  `:440`).
- Selectors: `@media (max-width: 768px) { .dock-target-selectors { display:
  none; } }` (`app.css:6807-6809`).
- Composer anchoring: `const dockReduced = reducedMotion || phone;`
  (`routes/chat-page.tsx:443`) — the dock never re-anchors the composer to
  the canvas center: the prepaint branch is `if (wrapper !== null && stack
  !== null && !phone) { … wrapper.style.transform = … }` else clears the
  transform (`routes/chat-page.tsx:509-524`). The composer stays glued in
  the bottom stack.

What remains at phone: `--rb-bg` body, the status strip (empty/idle), the
composer in the bottom slot, the footer Layer A git chips
(`composer.tsx:2843-2852`). "it has literally nothing."

### (b) Target spec

Desktop reference being adapted (all already ported; only the phone gates
flip):

- The hero element: `new_thread_background` (`shell.rs:857-914`) —
  absolute, top-left of the conversation column, full canvas width, height
  `min(viewport × 0.72, 760)` (`shell.rs:698-699`, `:852-855`), the
  double-pass cutout artwork, 120ms readiness fade. Web port:
  `components/new-thread-background.tsx` + `.new-thread-hero*`
  (`app.css:2495-2601`); the cutout mask is regenerated from a
  ResizeObserver on `#composer-surface` (`new-thread-background.tsx:108-
  150`) — width-agnostic, works at 375px.
- The composer's canvas position: `(viewportHeight − height) × 0.5 + 8`
  (`composer_dock.rs:380-385`, "Anchor by the top of the input surface").
  Web port exists (`lib/composer-dock.ts` prepaint, `routes/chat-page.tsx:
  509-524`) but is phone-disabled.
- The selector rows: device + project chips floating 28px above the pill
  (`pickers.rs:2426-2498`; web `composer.tsx:2670-2678` +
  `components/composer/new-thread-selectors.tsx:110-130`, CSS
  `app.css:2987-3003`) and the git row in the footer slot
  (`composer.tsx:2843-2852`).

Phone spec:

1. Mount the hero at phone: change `heroVisible` to `(!hasSelection ||
   dockFrame.active)` (`routes/chat-page.tsx:565`) and delete the
   `display: none` (`app.css:6803-6805`). `heroWidth` already computes
   `viewport − sidebarNow` (`routes/chat-page.tsx:566`) — with M3/M2's
   `sidebarForGeometry = 0` fix that is exactly the phone canvas width.
   Hero height reuses the ported `new_thread_background_height` — on a
   667px phone that is 480px (0.72 ratio), which is the correct proportion
   under the centered composer.
2. Center the composer: EITHER flip the phone arm of `dockReduced` so
   `dock.prepaint` runs at phone (`routes/chat-page.tsx:443`, `:509-524`) —
   the glide then also plays on phone, which the phone layer's "snap"
   principle argues against — OR (recommended, simpler) keep `dockReduced`
   and add a phone CSS rule that visually centers the wrapper's slot:
   `.persistent-composer { margin-block: auto }` inside the chat column
   flex, with the bottom stack at the column bottom. The exact choice is an
   open question (Q3) because the desktop answer is the animated dock.
3. Show the selectors: delete `.dock-target-selectors { display: none }`
   (`app.css:6807-6809`). On phone the row sits 28px above the centered
   pill exactly as on desktop; the chips' popovers then need the M8
   responsive treatment (drawer on phone) to be usable.
4. Keep the phone "snap" for the dissolve/handoff channels
   (`dockReduced`), so route changes stay instant — the hero's own
   readiness fade (`app.css:2510-2523`) still plays, 120ms.

### (c) Root cause

Decision 5 froze the phone canvas at its pre-parity shape ("no artwork, no
re-anchoring", `app.css:6801-6802`) and tickets 13/15 were written with
"phone layer out of scope" guards (`13-composer-core.md:1271-1272`,
`15-new-thread-route.md:666`), so the phone never received the parity
canvas that the desktop got in the same wave.

### (d) Gap rows

| item | kind | expected | web value (file:line) | fix |
| --- | --- | --- | --- | --- |
| hero mounted at phone | component | hero renders (min(0.72·vh, 760) tall) | `&& !phone` gate + `display: none` (`chat-page.tsx:565`; `app.css:6803-6805`) | remove both |
| composer position | geometry | centered: `(vh − h)/2 + 8` | bottom-stack slot, transform cleared at phone (`chat-page.tsx:509-524`) | dock prepaint at phone, or centered flex + snapped dock |
| selector rows | component | device+project chips above the pill | `display: none` (`app.css:6807-6809`) | remove; chips get M8 drawer treatment |
| status strip | component | unchanged (idle indicator, both routes) | already renders (`chat-page.tsx:772`) | — |

---

## M6 — settings pages are not mobile friendly (appearance named)

### (a) Mechanism trace

The settings shell itself is sound on phone (nav is the sidebar drawer
content; `settings-layout.tsx:10-16` renders only the scrolling outlet;
`.settings-scroll` `overflow-y: auto` `app.css:9424-9429`; the phone block
adjusts page padding and stacks rows `app.css:11956-11971`). The breakage
is fixed-width furniture inside the pages:

1. **Appearance — interface font row (the named offender).**
   `.settings-font-row` (`app.css:10412-10417`) keeps `justify-content:
   space-between; gap: 24px`, its trailing cluster `.settings-font-controls`
   is `flex: none` (`app.css:10435-10440`), and the two dropdown triggers
   are hard widths: `.settings-select-trigger { width: 220px }`
   (`app.css:10444-10459`) and `.size-trigger { width: 128px }`
   (`app.css:10465-10467`). 220 + 8 + 128 + 24 gap = 380px of non-shrinking
   content inside `.settings-page`'s phone content box (375 − 2×16 padding
   = 343px, `app.css:11960-11962`) → horizontal overflow; because
   `overflow-y: auto` on `.settings-scroll` computes `overflow-x` to auto,
   the page scrolls sideways.
2. **Appearance — effect pills.** `.settings-effect-choices { max-width:
   430px; margin-left: 10px; justify-content: flex-end }` (`app.css:10096-
   10104`) with `.settings-row { flex-wrap: wrap }` at phone
   (`app.css:11968-11970`) — wraps below the label, but a 430px-capped,
   right-aligned cluster on a 343px row overflows until it wraps and then
   hangs left-of-nothing (the row's own alignment breaks visually).
3. **Appearance — theme select row.** The 218px-wide trigger
   (`theme-select-trigger`, ticket 28 Comments: "the custom 218×34
   PickerCard popover") + `PickerCard width 260` menu
   (`settings-appearance.tsx:374-395`) — the trigger fits, the popover
   clamps (8px margin, `positioning.ts:76-86`) but loses rows to clipping
   on narrow screens; acceptable, listed for completeness.
4. **All modal dialogs.** `.dialog-card { width: 360px }` (`app.css:4830-
   4841`) inside `.modal-backdrop { padding: var(--rb-space-lg) }`
   (`app.css:4744-4753`): 360 > 375 − 32 = 343 → the rename/delete dialogs
   (`chat-menu.tsx:290`, `:359`; `space-filter.tsx:669`, `:728`) and the
   theme import/review dialogs (`settings-appearance.tsx:662`, `:870`)
   overflow horizontally on ≤392px viewports. No phone override exists for
   `.dialog-card` (only `.add-space-card` has one, `app.css:13438-13444`).
5. **Shortcuts.** `.shortcuts-header` `gap: 24px` (`app.css:11584-11589`)
   and the min-72px segmented options (`app.css:11660-11671`) fit; the kbd
   chips ride `.settings-row` wrap. Listed as OK.
6. **Files / pills / accounts** — wrap correctly via the phone block
   (`app.css:12967-12980`, `.settings-account-row` wrap) and
   `.pill-row { flex-wrap: wrap }` (`app.css:11534-11539`). OK.

### (b) Target spec

Mobile-native (the desktop settings grid is a single fixed 768-capped
column — `settings.rs` layout is width-stable, nothing to adapt):

- Every `flex: none` trailing cluster in a settings row gets a phone rule:
  `width: 100%; justify-content: flex-start;` under `@media (max-width:
  768px)`, and the fixed-width triggers become fluid:
  `.settings-select-trigger { width: 100% }`, `.size-trigger { width: 50% }`
  (or wrap the two triggers, `flex-wrap: wrap`).
- `.dialog-card` phone: `width: min(360px, calc(100vw - 2 *
  var(--rb-space-lg)))` — or adopt the M8 responsive pattern so the dialogs
  become drawers at phone, which removes the overflow by construction.
- `.settings-effect-choices` phone: `max-width: none; justify-content:
  flex-start; margin-left: 0;`.

### (c) Root cause

Ticket 28/29 ported the desktop's fixed furniture verbatim (widths are
parity numbers) and the phone block (`app.css:11956-11971`) only covered
the generic row shapes, not the appearance page's fixed clusters.

### (d) Gap rows

| item | kind | expected | web value (file:line) | fix |
| --- | --- | --- | --- | --- |
| font row triggers | geometry | fluid/stacked ≤768 | 220px + 128px fixed (`app.css:10444-10467`) | phone widths/wrap |
| effect pill cluster | geometry | full-width, left-aligned | 430px cap, right-aligned (`app.css:10096-10104`) | phone override |
| dialog cards | geometry | ≤ viewport − 32 | fixed 360px (`app.css:4830-4841`) | `min()` width or M8 drawer |
| theme select popover | behavior | usable | 260px popover (`settings-appearance.tsx:381`) | M8 drawer at phone |

---

## M7 — selecting a chat / settings page should close the phone drawer

### (a) Mechanism trace

- The drawer's open flag is `AppShell`'s local `sidebarOpen`
  (`app-shell.tsx:112`), closed by the backdrop click (`app-shell.tsx:584-
  588`), the Escape ladder (`app-shell.tsx:351-359`), and the toggle
  (`app-shell.tsx:196-202`).
- Chat rows navigate via plain router `Link`s: `<Link to="/chat/$chatId" …>`
  (`components/chat-list.tsx:476-477`; archived rows
  `components/archived-section.tsx:145-146`). Settings rows likewise
  (`components/settings-nav.tsx:58-68`). The user menu's Settings item uses
  `navigate` (`components/account-row.tsx:71`). **No selection handler
  anywhere references the drawer** — `setSidebarOpen(false)` has exactly
  three call sites (backdrop, toggle, Escape) (`app-shell.tsx:198`, `:205`,
  `:587`).

### (b) Target spec

Mobile-native (the desktop sidebar is a persistent column; it never
"closes" on selection — there is nothing to port). Recommended shape, one
site, covers every entry surface (chat Link, settings Link, account menu
navigate, future ones):

```
AppShell: useEffect(() => {
  if (!sidebarOpen) return;
  if (window.matchMedia(`(max-width: ${PHONE_MAX_WIDTH}px)`).matches) {
    setSidebarOpen(false);
  }
}, [pathname]);            // close-on-navigate at phone
```

guarded by the same matchMedia as the toggle (`app-shell.tsx:197`) so
desktop never closes. Alternative (threaded `onNavigate` prop into
`SidebarBody`/`SettingsNavBody`) touches three files for the same result —
not recommended.

### (c) Root cause

The drawer predates the router-driven sidebar content and was only ever
closed by its own affordances; navigation links were never taught about it.

### (d) Gap rows

| item | kind | expected | web value (file:line) | fix |
| --- | --- | --- | --- | --- |
| drawer close on select | behavior | phone drawer closes when a row navigates | no close call (`chat-list.tsx:476`; `settings-nav.tsx:59-68`; `app-shell.tsx:112/196-207`) | pathname-change effect in `AppShell` |

---

## M8 — "does base ui have drawer?" → responsive dialog/drawer pattern

### (a) Research answers

- **Yes** — `@base-ui/react` 1.8.0 ships Drawer (verified above, package
  `web/packages/app/package.json:15`, parts in
  `node_modules/@base-ui/react/drawer/`). It is a sheet-style primitive:
  `swipeDirection` default `'down'`, `snapPoints`, `DrawerSwipeArea`,
  `DrawerVirtualKeyboardProvider` (keyboard-aware layout), `DrawerProvider`
  (shared indent/background coordination), `modal: true | 'trap-focus' |
  false` with the same focus-trap/scroll-lock semantics as Dialog
  (`drawer/root/DrawerRoot.d.ts:21-33`).
- React Aria is not installed; the repo's wrappers are
  `components/base/dialog.tsx` (`RbDialog` `:67-91`, `RbDialogGlass`
  `:132-153`), `components/base/popover.tsx` (`RbPopover` `:150-213`),
  `components/ui/Dialog.tsx` (`Dialog` mount-while-open shell `:29-44`),
  `components/ui/PickerCard.tsx` (`:88-119`). **None of them branches on
  width today** (grep `isMobile|useMediaQuery|matchMedia` over
  `components/base` + `components/ui` → 0 width hits). The only
  width-aware hook in the app is a local `useMediaQuery` in
  `components/transcript.tsx:1299-1311` (min-width 769, drives
  `lastRowPad` `:564-577`).

### (b) Target spec — the responsive primitive

Standardize on ONE media hook and ONE responsive surface:

1. `state/media.ts` (new): promote `transcript.tsx:1299-1311`'s hook;
   export `useMediaQuery`, `useIsPhone()` (`(max-width: 768px)` — the
   stylesheet's breakpoint, `PHONE_MAX_WIDTH` `state/layout.ts:43`), and
   `useIsDesktop()`. Replace the raw one-shot `window.matchMedia` in
   `app-shell.tsx:197` and the `viewport <= PHONE_MAX_WIDTH` innerWidth
   compare in `chat-page.tsx:440` (innerWidth and the CSS media query can
   disagree by rounding — the dead-band comment at `app-shell.tsx:193-196`
   documents exactly that class of bug). Adopting
   `@base-ui/react/unstable-use-media-query` is the alternative; it works
   (verified above) but adds an unstable-named subpath import where the
   repo already has the 12-line hook.
2. `components/base/responsive-surface.tsx` (new): `RbResponsiveDialog` —
   renders `RbDialog`'s exact tree at `useIsDesktop()`, and at phone
   renders Base UI's Drawer with the same children: `Drawer.Root {open,
   onOpenChange, modal}` + `Drawer.Portal` + `Backdrop
   className="modal-backdrop"` + `Popup className="modal-card
   rb-drawer-card"`, `swipeDirection="down"` (dismiss toward the bottom
   edge), no snap points (the dialogs are small). CSS adds a phone sheet:
   `.rb-drawer-card { position: fixed; left: 0; right: 0; bottom: 0;
   border-radius: 16px 16px 0 0; max-height: calc(100dvh -
   var(--rb-space-lg)); }` under `@media (max-width: 768px)`. The
   entrance can reuse `rb-dialog-in` (`app.css:4818-4827`); reduced motion
   snaps (`app.css:4811-4816` pattern).
3. `RbDialogGlass` gets the same option (the add-space palette as a
   bottom sheet at phone is the natural mobile form of a 680px palette;
   its existing phone override `app.css:13438-13444` already goes
   full-width).

Which existing sites adopt it — every dialog, today centered with no phone
treatment:

| site | file:line | today (all widths) |
| --- | --- | --- |
| Rename chat | `components/chat-menu.tsx:290` (`Dialog`) | centered modal, 360px card |
| Delete chat | `components/chat-menu.tsx:359` | centered modal |
| Rename space | `components/space-filter.tsx:669` | centered modal |
| Delete space | `components/space-filter.tsx:728` | centered modal |
| Theme import | `routes/settings-appearance.tsx:662` | centered modal |
| Theme review | `routes/settings-appearance.tsx:870` | centered modal |
| Add-space palette | `components/add-space-palette.tsx:146` (`RbDialogGlass`) | centered, 680px card |

Popover/menu sites that should become drawers at phone (recommended —
these are the surfaces the user must reach while typing; desktop keeps the
popover exactly as ticket 09/10 specced, `popover.rs:420-638`):

| site | file:line | phone today |
| --- | --- | --- |
| Device chip popover (224px) | `composer-footer.tsx:224-252` | floating card, clamp-only |
| Project chip popover (280px) | `composer-footer.tsx:357-368` | floating card (see M9) |
| Checkout chip (224px) | `composer-footer.tsx:508-530` | floating card |
| Ref chip (320px) | `composer-footer.tsx:705-726` | floating card |
| Canvas device+project row | `composer/new-thread-selectors.tsx:110-130` | hidden (`app.css:6807-6809`, M5) |
| Model/harness identity picker (304px) | `composer-pickers.tsx:229-300` | floating card |
| Spaces menu (listbox) | `space-filter.tsx:170-270` | floating card inside drawer sidebar |
| Sidebar view menu | `space-filter.tsx:484-528` | floating card |
| Theme family menus (260px) | `settings-appearance.tsx:374-412` | floating card |
| Font/size Selects | `settings-appearance.tsx:500-563` (`RbSelect`) | floating card |
| Chat context menu | `chat-menu.tsx:68` (`RbContextMenu`) | pointer-anchored card |
| Right-pane `+` menu (168px) | `right-tab-strip.tsx` (`PLUS_MENU_W :41`) | floating card |

Implementation note: `PickerCard` composes `RbPopover`
(`components/ui/PickerCard.tsx:100-117`); the responsive branch belongs in
`PickerCard` (one place) — at phone render the card body inside the Drawer
sheet with the trigger unchanged, so every consumer converts at once.

### (c) Root cause

Not a defect so much as a missing layer: the wrappers were built for the
desktop parity contract (`base/dialog.tsx:1-38`, `base/popover.tsx:8-44`)
and decision 5 excluded phone work; Base UI's Drawer arrived with the 1.x
upgrade and was never adopted.

### (d) Gap rows

| item | kind | expected | web value (file:line) | fix |
| --- | --- | --- | --- | --- |
| responsive dialog primitive | component | Drawer ≤768 / Dialog ≥769 | none (`base/dialog.tsx:67-153`) | `RbResponsiveDialog` |
| media hook | state | one shared `useIsPhone` | local copy in `transcript.tsx:1299-1311`; raw matchMedia `app-shell.tsx:197`; innerWidth compare `chat-page.tsx:440` | `state/media.ts` |
| popover phone form | component | sheet/drawer body | floating popover cards (table above) | `PickerCard` phone branch |

---

## M9 — "the project selector dialog show on mobile but not on desktop"

### (a) What is verified

There is **no JS width branch** in any of the three files the hunch named:
`components/composer/new-thread-selectors.tsx` (grep `matchMedia|innerWidth|
phone` → only reduced-motion elsewhere), `components/surface-picker.tsx`
(no width logic at all, `surface-picker.tsx:1-126`),
`components/add-space-palette.tsx` (only a device-icon heuristic at `:56`).
The width dependence is pure CSS, and it is INVERTED relative to the
user's expectation in exactly one place:

- `.dock-target-selectors { display: none }` at ≤768 (`app.css:6807-6809`):
  the canvas's device+project chip row — the desktop's ONLY on-canvas
  project selector (`pickers.rs:2426-2498`, mounted at all widths by
  `composer.tsx:2670-2678`) — is REMOVED on phone.
- The project chip that CAN still appear on phone is the footer's draft-row
  `ProjectChip` (`composer-footer.tsx:141-142`), rendered when an
  established chat is uncommitted (`committed = chat.config !== null ||
  chat.branch !== null`, `composer-footer.tsx:99`), with
  `role="dialog"` and a 280px popover (`composer-footer.tsx:356-368`).

So the user-visible asymmetry: on DESKTOP the new-chat page offers the
project selector as the always-visible chip row (no dialog); on PHONE that
row is gone (M5), and the project picker the user meets is the floating
`role="dialog"` card reached from the uncommitted-chat footer — the M10
broken-send flow lands them precisely in that state. "Dialog shows on
mobile but not on desktop" is that inversion, read from the two sides.

### (b) Target spec

Fix by construction rather than by patching the branch: M5 re-shows
`.dock-target-selectors` at phone and M8 gives the chips the
drawer-at-phone treatment — the project selector then exists on both
widths with the same reachability (chips on the canvas), and the footer's
draft `ProjectChip` keeps its desktop popover contract
(`pickers.rs:2012-2124`) at ≥769.

### (c) Root cause

`app.css:6807-6809` (the phone layer's decision-5 strip of the selector
row) — no inverted JS branch exists to fix.

### (d) Gap rows

| item | kind | expected | web value (file:line) | fix |
| --- | --- | --- | --- | --- |
| canvas project selector, phone | component | chips visible (drawer-form popovers) | `display: none` (`app.css:6807-6809`) | M5 + M8 (delete the rule) |
| which surface the user meets | behavior | same selector both widths | phone: only the uncommitted footer's `role="dialog"` popover (`composer-footer.tsx:141-142`, `:356-368`) | superseded by the above |

Residual honesty note: the user's one-line report cannot be replayed
statically (no browser run in this research); if after M5/M8 land the
asymmetry persists, the next suspect is the popover's `anchorAboveEnd`
placement (`composer-footer.tsx:360` + `positioning.ts:119`) — `side: 'top'
+ align: 'end'` with `align: 'shift'` clamping (`positioning.ts:43-47`)
behaves differently at 375px than at 1440px — which M8's phone drawer form
also removes.

---

## M10 / M11 — owned elsewhere (recorded only)

- M10 ("This chat is not on engine's list" after picking a new project and
  submitting): owned by the sibling file
  `.scratch/web-parity/research-2026-09-19/spaces-sidebar-mirroring.md`
  (not present at research time). The error string the user quotes is the
  web's row-miss fallback at `routes/chat-page.tsx:702-710` ("That chat is
  not in this engine's list.") — that display site is the symptom, not the
  cause; do not re-research it here.
- M11 ("Send failed: crypto.randomUUID is not a function"): owned by
  `.scratch/web-parity/research-2026-09-19/composer-model-picker-send.md`
  (not present at research time). The failure surfaces through the
  composer's failure notice (`composer.tsx:2640-2648`).

---

## Current mobile layer inventory

Every width branch and phone rule in the app, as of HEAD:

**JS branches (3 total):**

| site | file:line | what it does |
| --- | --- | --- |
| sidebar toggle | `app-shell.tsx:196-202` | `matchMedia(max-width: 768px)` → open phone drawer instead of collapsing the column |
| phone flag | `routes/chat-page.tsx:440-443` | `viewport <= PHONE_MAX_WIDTH` → `dockReduced`, composer never re-anchors |
| hero gate | `routes/chat-page.tsx:565` | `&& !phone` → hero unmounted at phone |
| transcript desktop flag | `components/transcript.tsx:100, 564, 577` | local `useMediaQuery(min-width: 769px)` → `lastRowPad` 16 vs clearance-based |

**CSS blocks (`app.css`):**

| block | lines | contents |
| --- | --- | --- |
| chat underlay (desktop-only) | `2435-2448` | ≥769: transcript underlay/margin −38, bottom-stack `margin-block: auto` |
| chat-body column | `2614-2618` | ≤768: `.chat-body` column |
| phone layout (the core) | `6738-6851` | shell column; pane stacked (`6766-6791`); composer shim (`6796-6798`); hero + selectors hidden (`6803-6809`); identity folder hidden (`6811-6814`); left drawer (`6816-6832`); backdrop (`6834-6844`) |
| backdrop desktop reset | `6853-6857` | ≥769 hides the drawer backdrop |
| transcript desktop fade | `6957-6971` | ≥769 top-inset 38 / bottom-band; phone stays 0/1px |
| trow desktop gutters | `7007-7011` | ≥769 48px (phone: 12px at `9050-9058`, plus `.user-bubble` 88%) |
| settings phone | `11956-11971` | page padding 20/16/48; option rows column; rows wrap |
| changes phone | `12817-12872` | toolbar/banner wrap; diff rows unwrap, markers hidden |
| phone polish | `12915-13000` | topbar/wordmark (dead classes — no component uses `.topbar`/`.chat-header`, grep → 0 hits); banner wrap; engine drawer full-screen (`12946-12958`); term-dock handle (`12962-12965`); settings rows (`12967-12970`); account rows (`12973-12980`); `.chat-header` (dead, `12988-12999`) |
| add-space phone | `13438-13444` | card `width: 100%; max-width: 680px` |
| history phone | `14729-14740` | toolbar wrap, title 120px |
| hover:none | `9353-9360` | archived rows keep the unarchive pill (touch) |
| reduced-motion ×11 | `1745, 3452, 3474, 3600, 3624, 4811, 9067, 10966, 12874, 13425, 14703` | snap parity (not phone) |

**Existing touch/overscroll guards:** `.transcript` `overscroll-behavior:
contain` (`app.css:6896`), `.queue-panel-list` (`:2673`), `.composer-input`
(`:3243`); `touch-action: none` on `.lightbox` (`:5256`),
`.files-split-handle` (`:6244`), `.files-image-viewport` (`:6603`), history
column drags (`:14186`, `:14208`, `:14396`). Missing everywhere else (see
M4).

**Other phone facts:** viewport meta has no `viewport-fit=cover` and no
`interactive-widget` (`web/packages/app/index.html:5`); `html, body, #root`
`height: 100%` (`app.css:12-16`); the engine drawer is a centered card
(`.drawer-backdrop` `app.css:5617-5629`, z 50) forced full-screen at phone
(`app.css:12946-12958`); the terminal dock is a bottom drawer with an 8px
drag handle at phone (`app.css:12960-12965`); the escape ladder treats the
phone sidebar + engine drawer as one surface (`state/escape.ts:81-89`,
priority 12).

---

## Recommended mobile layout system

1. **Viewport height.** Replace the `height: 100%` chain with
   `height: 100dvh` (fallback `100vh`) on `#root`/`.shell`
   (`app.css:12-16`, `:264-265`), and add `overscroll-behavior: none` on
   `html, body`. This is the M4 fix and the iOS URL-bar-stable height; the
   desktop is unaffected (dvh == vh when there is no browser chrome…
   actually dvh == the dynamic viewport, which at desktop widths with no
   URL bar equals vh — safe to apply globally, or scope it to
   `@media (max-width: 768px)` if the desktop capture pipeline should stay
   byte-identical).
2. **Keyboard.** Add `interactive-widget=resizes-content` to the viewport
   meta (`index.html:5`) so the Android IME resizes the layout — the
   composer/bottom stack then stays above the keyboard instead of being
   covered; with `100dvh` iOS handles this natively.
3. **Safe areas.** Add `viewport-fit=cover` (`index.html:5`) and use
   `env(safe-area-inset-top)` on the phone titlebar band
   (`padding-top` addition to `.titlebar`, `app.css:296-297` — phone rule
   only, the desktop 38px stays exact) and `env(safe-area-inset-bottom)` on
   the phone composer's bottom padding (`app.css:2970` phone override) and
   the engine drawer's full-screen phone form (`app.css:12948-12954`).
   Without `viewport-fit=cover` the insets are 0 (harmless), with it they
   become real on notched devices.
4. **Scroll containment per region.** `overscroll-behavior: contain` on
   every interior scroller (M4's table) + `touch-action: manipulation` on
   `.titlebar` and the app's button reset. Scrollers list for the audit:
   `.transcript` ✓, `.sidebar-list` ✗, `.settings-scroll` ✗,
   `.settings-nav-sections` ✗, `.drawer` ✗, `.add-space-list` ✗,
   `.files-*` ✗ (several already have `touch-action: none` handles).
5. **Responsive primitive.** One `useMediaQuery` (promote
   `transcript.tsx:1299-1311` → `state/media.ts`), consumed by
   `app-shell.tsx:197`, `chat-page.tsx:440`, the M8 responsive surface, and
   the M7 close-on-navigate. Container queries are not recommended for
   this app: the shell's layout math (`state/layout.ts` width functions)
   already needs the width as a JS number, and the CSS already keys on the
   same 768/769 boundary — a second (container) system would drift, the
   exact dead-band class `app-shell.tsx:193-196` warns about.
6. **The one-control rule stays.** Every phone overlay reuses the existing
   z-ladder (`app.css:233-255`): drawers at 30 (under the titlebar, its
   cluster stays clickable), sheets/dialogs at 50/70 — no new tiers.

---

## Consolidated gap table

| # | item | kind | expected | web value (file:line) | fix sketch |
| --- | --- | --- | --- | --- | --- |
| M1 | composer gutter | geometry | 12px phone gutter = `.trow` | 24/40px (`app.css:6796-6798`, `2970`, `3219`) | phone rule width 100% + padding md |
| M1 | status strip gutter | geometry | 12px | 24px (`app.css:7030`) | phone override |
| M2 | pane mount | geometry | right overlay drawer | stacked row (`app.css:6744-6746`, `6766-6775`) | mirror `app.css:6816-6832` |
| M2 | tab strip | component | visible chips | band = 0 (`app-shell.tsx:514-519`; `state/layout.ts:404-415`) | strip in drawer header (Q1) |
| M2 | pane width at phone | logic | sidebar term 0 | `rightPaneMaxWidth(375, 304)=0` (`state/layout.ts:54-56`) | `sidebarForGeometry = phone ? 0` |
| M3 | rowLeft at phone | geometry | 168/136 | 320 (`app-shell.tsx:435-441`) | same one-line branch |
| M4 | document chaining | CSS | overscroll none + dvh | absent (`app.css:12-31`) | add |
| M4 | titlebar touch | CSS | manipulation + no select | absent (`app.css:292-316`) | add |
| M4 | drawer list chaining | CSS | contain | absent (`app.css:1232-1239`) | add |
| M5 | hero at phone | component | mounted | `!phone` + display none (`chat-page.tsx:565`; `app.css:6803-6805`) | remove both |
| M5 | composer position | geometry | centered | bottom slot (`chat-page.tsx:509-524`) | dock at phone or centered flex (Q3) |
| M5 | selector row | component | visible | display none (`app.css:6807-6809`) | remove + M8 |
| M6 | font row | geometry | fluid | 220+128 fixed (`app.css:10444-10467`) | phone widths |
| M6 | dialog cards | geometry | ≤ vw−32 | 360 fixed (`app.css:4830-4841`) | min() or M8 |
| M6 | effect pills | geometry | full-width left | 430 right (`app.css:10096-10104`) | phone override |
| M7 | drawer close on select | behavior | closes on navigate | never (`chat-list.tsx:476`; `settings-nav.tsx:59`) | pathname effect |
| M8 | responsive surface | component | Drawer ≤768 / Dialog ≥769 | none (`base/dialog.tsx`) | `RbResponsiveDialog` + `PickerCard` branch |
| M8 | media hook | state | shared | local/absent (`transcript.tsx:1299-1311`; `app-shell.tsx:197`; `chat-page.tsx:440`) | `state/media.ts` |
| M9 | project selector phone | component | chips like desktop | row hidden, only footer `role="dialog"` popover (`app.css:6807-6809`; `composer-footer.tsx:141-142`, `356-368`) | fixed by M5+M8 |
| M10 | broken send | logic | — | `chat-page.tsx:702-710` (display site) | owned by spaces-sidebar-mirroring.md |
| M11 | randomUUID | runtime | — | `composer.tsx:2640-2648` (display site) | owned by composer-model-picker-send.md |

---

## Pure logic to port + desktop test names

Almost nothing new ports: the mobile layer is web-native (the desktop has
no phone layout — its minimum geometry assumptions, `CHAT_PANEL_MIN` 300
plus `SIDEBAR_MIN`, make a 375px window impossible). What applies:

- **Reuse, already ported:** `new_thread_background_height`
  (`shell.rs:852-855`, desktop asserts `shell.rs:8276-8280`: `400 → 288`,
  `600 → 432`, `1000 → 720`, `1200 → 760`) — the phone hero just mounts
  the existing port; the dock's canvas anchor `(vh − h) × 0.5 + 8`
  (`composer_dock.rs:380-385`) is already `lib/composer-dock.ts`.
- **New web-only logic, each gets a new unit test named after the function
  it branches:** `titlebarRowLeft` phone arm (a
  `titlebarRowLeft phone sidebar is out of flow` case beside the existing
  layout tests), `titlebarPaneBandWidth`/`resolvePaneWidth` phone inputs
  (same file), the M7 close-on-navigate effect (an app-shell-level test
  asserting `sidebarOpen` flips false on pathname change only under a
  mocked 375px matchMedia). No desktop test names map to these — that is
  the point; they are mobile-native rules and must say so in the ticket.

## Desktop-only items (do not attempt at phone)

- Native window dragging / `WindowControlArea::Drag` and double-click zoom
  (`shell.rs:3913-3935`) — the browser owns the window.
- macOS traffic-light and Linux caption cluster geometry
  (`state/layout.ts:289-339` ports; phone has neither).
- The desktop's minimum-geometry model itself (`CHAT_PANEL_MIN`, pane
  seams, takeover tween) — phone replaces, not ports, these.
- Vibrancy/frosted surfaces (removed on web per ticket 28's Comments),
  appshots, native menus, embedded browser tabs (spec decision 6,
  `spec.md:32-34`).
- The pane's drag seam and FLIP resort at phone — retired by
  `app.css:6757-6760`.

## Open design questions

1. **Phone pane tabs: where?** (a) A 38px strip inside the right drawer
   (recommended — one chip row, reuses `RightTabStrip` verbatim) vs (b)
   recompute the titlebar band at phone and keep the desktop's
   titlebar-band placement (`tabs.rs:248`). (b) preserves the desktop
   metaphor but the 38px band also carries cluster + identity (M3) + the
   pane toggle — 336px of 375 is already spent. No desktop analog exists
   at phone widths; this is a genuine choice.
2. **Right pane as side drawer vs bottom sheet.** The user asked "open
   from right side" — spec above follows that (right drawer, mirroring the
   left). Base UI's Drawer is bottom-sheet-shaped (swipe-down default,
   snap points); a side drawer built on it needs custom CSS placement and
   `swipeDirection: 'right'`. Decide once: navigation panes (sidebar,
   right pane) = side drawers; transient pickers/dialogs (M8) = bottom
   sheets. Recommended split as specced; confirm with the user.
3. **Phone new-thread composer: animate or snap?** Run the dock's glide
   and dissolve channels at phone (full desktop parity of motion) vs
   snap + static centered composer (the phone layer's standing "snap"
   principle, `chat-page.tsx:441-443`). Recommended snap; the hero's own
   120ms readiness fade still plays.
4. **M9's exact repro** — which card the user called "the project selector
   dialog" needs one live session once M5/M8 land (see the honesty note);
   the static analysis says the asymmetry disappears with them.
