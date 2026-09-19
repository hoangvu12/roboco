# 50 — Mobile layout system

**What to build:** The phone layer gets its app-shell baseline. Today, holding
a finger on the titlebar scrolls the whole chat behind it, opening the phone
sidebar scrolls the same document, the viewport jumps every time the iOS URL
bar collapses or the Android keyboard opens, notched devices draw the titlebar
under the notch, and the chat title is invisible on a 375px screen because the
title row starts at x=320 of 375. After this ticket: the document cannot
scroll at all (100dvh shell, `overscroll-behavior: none`, every interior
scroller contained), touch gestures on the titlebar no longer pan or
long-press-select, the viewport is URL-bar-stable, safe-area insets pad the
titlebar and composer, and the chat title sits right next to the window
buttons with healthy truncation. Desktop widths (≥769px) render exactly as
before.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

**Research:** `../research-2026-09-19/mobile-layer.md` M3 (chat title
invisible in the phone titlebar) + M4 (chat scrolls when the titlebar is held
/ the drawer is opened) + the "Current mobile layer inventory" section + the
"Recommended mobile layout system" section (all six items).

**Spec amendment (user directive 2026-09-19):** spec decision 5 ("phone widths
are out of scope", `.scratch/web-parity/spec.md:29-31`) is amended for this
ticket — phone-layer work (≤768px) is in scope here; decisions 1–4 still govern
every ≥769px surface unchanged.

**Desktop reference (for lookups only):** The titlebar identity layout being
adapted: `crates/ui/src/shell/tabs.rs::render_session_title_bar` (138-362 —
`row_left` at 185-221, the identity group at 310-350, the gap-6 row at
318-350) and `shell.rs::titlebar_drag_region` (3917-3963) — already ported by
ticket 06 as `state/layout.ts::titlebarRowLeft` (`:370-384`),
`components/titlebar.tsx`, and `routes/chat-page.tsx::ChatIdentity`
(`:870-884`; the research cites `titlebar.tsx:870-884` but `ChatIdentity`
lives in `routes/chat-page.tsx:870` — `titlebar.tsx` is 247 lines). The
desktop has NO phone analog for the touch/scroll layer: the desktop titlebar
is a `WindowControlArea::Drag` region handed to the OS compositor
(`shell.rs:3913-3935`) and GPUI never scrolls the window from chrome drags —
the guards, `100dvh`, insets, and containment spec below are mobile-native,
from the research's M4 §(b) and "Recommended mobile layout system".

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/index.html` | edit | viewport meta (`:5`): add `viewport-fit=cover` and `interactive-widget=resizes-content` |
| `web/packages/app/src/styles/app.css` | edit | `html, body, #root` + `html, body` overscroll (`:12-31`), `.shell` height (`:264-279`), `.titlebar` touch-action/user-select + safe-area top (`:292-316`), `.sidebar-list` (`:1232-1239`), `.settings-scroll` (`:9424-9429`), `.settings-nav-sections` (`:9448-9455`), `.drawer` (`:5631-5640`), `.add-space-list` (`:13263`), composer phone bottom padding (safe-area, phone override of `:2965-2977`), engine drawer phone form (`:12948-12954`); **new** phone-scoped `100dvh` block |
| `web/packages/app/src/components/app-shell.tsx` | edit | the `sidebarForGeometry = phone ? 0 : sidebarWidth` branch feeding `rowLeft` (`:435-441`) and the pane geometry (`:179`, `:514-519`) |
| `web/packages/app/src/state/media.ts` | new (only if 49 has not landed it) | `useIsPhone()` consumed by `AppShell`'s geometry branch — identical spec in 49 §2.1; converge, do not fork |
| `web/packages/app/tests/layout.test.ts` | edit | the `titlebarRowLeft` phone-arm case + the phone-input width case beside the existing `describe("titlebarRowLeft")` (`:130-167`) and `column widths` (`:58-110`) blocks |

---

## 1. Context a fresh session needs

- **Spec amendment (decision 5).** The wave-1 spec froze the phone layer
  ("stays as is; do not break it, do not extend it", `spec.md:29-31`). The
  user directive of 2026-09-19 amends that for this ticket: phone work is now
  in scope. Everything this ticket changes must be a no-op at ≥769px.
- **The titlebar geometry pipeline.** The titlebar row pads itself by
  `--rb-titlebar-row-left` (`app.css:292-316`, `padding-left:
  var(--rb-titlebar-row-left)` at `:298`), which `AppShell` writes from the
  desktop function with the LIVE sidebar width: `app-shell.tsx:435-441` —
  `rowLeft = isChatRoute ? titlebarRowLeft({sidebar: sidebarWidth,
  showsNewSession, takeover}) : TITLEBAR_CONTENT_START`.
  `state/layout.ts:370-384` `titlebarRowLeft` = `max(sidebar + SPACE_LG,
  TITLEBAR_CONTENT_START + plusInset)` — the desktop's `content_left`
  (`tabs.rs:185-186`). At phone the sidebar is a fixed overlay
  (`app.css:6816-6827`) but `sidebarWidth` is still the dragged column width
  (e.g. 304): rowLeft = 320. On a 375px phone the identity group starts at
  x=320, and the trailing pane toggle (28px + 6px inset, `app.css:480-489`,
  `:299`) occupies the right end — the title has ~20px, so "i cant see the
  title of the chat."
- **The identity itself truncates correctly once given room:**
  `.titlebar-identity { min-width: 0; overflow: hidden; }` (`app.css:434-441`),
  `.identity-title` 12px/500 `text @ 85%` with ellipsis (`app.css:452-460`),
  `.identity-folder` hidden at phone already (`app.css:6811-6814`). The
  harness mark stays (14px, `routes/chat-page.tsx:870-884` `ChatIdentity`).
  No CSS change is needed for the title itself — only the row's inset.
- **There are NO touch handlers anywhere on the titlebar:** `titlebar.tsx`
  renders plain buttons (`titlebar.tsx:162-247`) with only `onClick`; no
  `onTouchStart/Move`, no drag logic, no long-press timers. The scroll is not
  an app feature — it is unguarded browser default behavior (§2.7).
- **Current overscroll/touch guards (the research's inventory):**
  `overscroll-behavior: contain` exists ONLY at `.transcript`
  (`app.css:6896`), `.queue-panel-list` (`:2673`), `.composer-input`
  (`:3243`); `touch-action: none` on `.lightbox` (`:5256`),
  `.files-split-handle` (`:6244`), `.files-image-viewport` (`:6603`), history
  column drags (`:14186`, `:14208`, `:14396`). Missing everywhere else —
  `html`/`body` have no `overscroll-behavior`, the titlebar has no
  `touch-action`/`user-select` (grep-verified).
- **Viewport facts.** The viewport meta has no `viewport-fit=cover` and no
  `interactive-widget` (`web/packages/app/index.html:5` — today
  `content="width=device-width, initial-scale=1"`); `html, body, #root`
  `height: 100%` (`app.css:12-16`); `.shell { height: 100% }`
  (`app.css:264-265`); the engine drawer's phone form uses
  `max-height/min-height: 100vh` (`app.css:12950-12951`); the only `100dvh`
  site today is `.rb-dialog-card`'s max-height (`app.css:4795`, leave it).
- **The z-ladder (fixed, `app.css:233-255`):** titlebar 40 over the phone
  drawer (30) and its backdrop (20) — "The phone sidebar itself stays at 30,
  deliberately under the titlebar — its cluster must stay clickable to close
  what it opened" (`app.css:247-248`). This is why every touch on the top
  38px band lands on the titlebar, including with the drawer open (§2.7).
- **Current mobile layer inventory rows relevant here (copied from the
  research's inventory):**

  | site | file:line | what it does |
  | --- | --- | --- |
  | sidebar toggle | `app-shell.tsx:196-202` | `matchMedia(max-width: 768px)` → open phone drawer instead of collapsing the column |
  | phone flag | `routes/chat-page.tsx:440-443` | `viewport <= PHONE_MAX_WIDTH` → `dockReduced`, composer never re-anchors |

  | block | lines | contents |
  | --- | --- | --- |
  | phone layout (the core) | `6738-6851` | shell column; pane stacked (`6766-6791`); composer shim (`6796-6798`); hero + selectors hidden (`6803-6809`); identity folder hidden (`6811-6814`); left drawer (`6816-6832`); backdrop (`6834-6844`) |
  | phone polish | `12915-13000` | engine drawer full-screen (`12946-12958`); term-dock handle (`12962-12965`) |

- **Parallel-ticket note (ticket 49).** `state/media.ts` (the shared
  `useIsPhone`) is specified identically in 49 §2.1. 49 normally creates it.
  This ticket is not blocked by 49: if 49 has not landed, create
  `state/media.ts` from that identical spec first — one file, one export set;
  never a second matchMedia source. 49 owns converting the two raw call sites
  (`app-shell.tsx:197`, `chat-page.tsx:440`); this ticket's own consumption
  is the new `sidebarForGeometry` line only.
- **Vocabulary** (`CONTEXT.md`): chat, harness, engine, space; *Session* only
  for the pairing credential. No user-visible strings change in this ticket.

---

## 2. Spec

Copied verbatim from the research's "Recommended mobile layout system"
(items 1–6, with ownership annotations) and M3/M4 §(b); the causal chain is
§2.7.

### 2.1 Viewport height + document scroll lock

The research, verbatim:

> 1. **Viewport height.** Replace the `height: 100%` chain with
>    `height: 100dvh` (fallback `100vh`) on `#root`/`.shell`
>    (`app.css:12-16`, `:264-265`), and add `overscroll-behavior: none` on
>    `html, body`. This is the M4 fix and the iOS URL-bar-stable height; the
>    desktop is unaffected (dvh == vh when there is no browser chrome…
>    actually dvh == the dynamic viewport, which at desktop widths with no
>    URL bar equals vh — safe to apply globally, or scope it to
>    `@media (max-width: 768px)` if the desktop capture pipeline should stay
>    byte-identical).

**Scope decision (this ticket):** the Do-not rule "do not touch desktop-width
CSS behavior" selects the research's second option — phone-scoped. Keep
`html, body, #root { height: 100% }` (`app.css:12-16`) and `.shell`'s
`height: 100%` (`app.css:264-265`) at ≥769, and add ONE phone block:

```css
@media (max-width: 768px) {
  html, body, #root { height: 100vh; height: 100dvh; } /* fallback, then dynamic */
  .shell { height: 100vh; height: 100dvh; }
}
```

`overscroll-behavior: none` on `html, body` applies globally per the
research's own wording ("add `overscroll-behavior: none` on `html, body`") —
it is render-neutral at desktop because `body` never scrolls there
(`overflow: hidden`, `app.css:18-31`); scope it to the phone block only if
capture diffing says otherwise.

**Current `height: 100%`/`100vh` sites (grep over `app.css`, listed):** grep
finds 54 `height: 100%` sites; all but the two viewport-chain sites are
parent-relative inner boxes that inherit whatever the chain resolves to —
they do not change. The sites that change:

| site | today | becomes |
| --- | --- | --- |
| `html, body, #root` (`app.css:12-16`) | `height: 100%` | phone-scoped `100vh` → `100dvh` (cascade fallback) |
| `.shell` (`app.css:264-265`) | `height: 100%` | phone-scoped `100vh` → `100dvh` |
| engine drawer phone form (`app.css:12950-12951`) | `max-height: 100vh; min-height: 100vh` | `100dvh` both (already inside the phone block `:12948-12954`) |
| `.rb-dialog-card` max-height (`app.css:4795`) | already `calc(100dvh - …)` | leave |
| `.drawer` max-height (`app.css:5633`) | `80vh` (a desktop value) | leave |

### 2.2 Keyboard (Android IME)

The research, verbatim:

> 2. **Keyboard.** Add `interactive-widget=resizes-content` to the viewport
>    meta (`index.html:5`) so the Android IME resizes the layout — the
>    composer/bottom stack then stays above the keyboard instead of being
>    covered; with `100dvh` iOS handles this natively.

Edit: `web/packages/app/index.html:5` →
`<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover, interactive-widget=resizes-content" />`
(`viewport-fit=cover` is §2.3's half of the same tag).

### 2.3 Safe areas

The research, verbatim:

> 3. **Safe areas.** Add `viewport-fit=cover` (`index.html:5`) and use
>    `env(safe-area-inset-top)` on the phone titlebar band
>    (`padding-top` addition to `.titlebar`, `app.css:296-297` — phone rule
>    only, the desktop 38px stays exact) and `env(safe-area-inset-bottom)` on
>    the phone composer's bottom padding (`app.css:2970` phone override) and
>    the engine drawer's full-screen phone form (`app.css:12948-12954`).
>    Without `viewport-fit=cover` the insets are 0 (harmless), with it they
>    become real on notched devices.

Concretely (all phone-scoped, `@media (max-width: 768px)`):

| rule | value | source |
| --- | --- | --- |
| `.titlebar` padding-top | `calc(var(--rb-titlebar-top-pad) + env(safe-area-inset-top))` — the desktop 38px band + 4px top pad stay exact at ≥769 | research item 3 |
| `.composer` bottom padding | `calc(var(--rb-space-lg) + env(safe-area-inset-bottom))` (phone override of `app.css:2970`) | research item 3 |
| engine drawer phone form | add `env(safe-area-inset-bottom)` to its padding (`app.css:12948-12954`) | research item 3 |

### 2.4 Scroll containment per region + titlebar gestures

The research, verbatim:

> 4. **Scroll containment per region.** `overscroll-behavior: contain` on
>    every interior scroller (M4's table) + `touch-action: manipulation` on
>    `.titlebar` and the app's button reset. Scrollers list for the audit:
>    `.transcript` ✓, `.sidebar-list` ✗, `.settings-scroll` ✗,
>    `.settings-nav-sections` ✗, `.drawer` ✗, `.add-space-list` ✗,
>    `.files-*` ✗ (several already have `touch-action: none` handles).

M4 §(b)'s guard table, verbatim (the fix IS this table):

| guard | rule | where |
| --- | --- | --- |
| document scroll lock | `html, body { overscroll-behavior: none; height: 100%; }` + `body { position: fixed; inset: 0; overflow: hidden }` OR `height: 100dvh` on `#root/.shell` | `app.css:12-16`, `:264-265` |
| titlebar gestures | `.titlebar { touch-action: manipulation; user-select: none; -webkit-user-select: none; }` (manipulation keeps taps, kills double-tap-zoom and pans) | `app.css:292-316` |
| drawer containment | `.sidebar-list { overscroll-behavior: contain; }` (same as `.transcript`, `app.css:6896`) | `app.css:1232-1239` |
| every interior scroller | `overscroll-behavior: contain` on `.settings-scroll` (`app.css:9424-9429`), `.settings-nav-sections` (`:9448-9455`), `.drawer` (`:5631-5640`), `.add-space-list` | each scroller |
| existing correct sites (leave) | `.lightbox` `touch-action: none` + `user-select: none` (`app.css:5244-5258`), `.files-split-handle` `:6244`, `.files-image-viewport` `:6603`, history column drags `:14186`, `:14208`, `:14396` | — |

This ticket implements the dvh arm of the first row (per §2.1's scope
decision — NOT the `position: fixed` body arm; `100dvh` + `overscroll-behavior:
none` is the chosen alternative the row offers). The `touch-action:
manipulation` addition to "the app's button reset" means the shared button
class the titlebar controls use (`.window-control` / `.header-icon-button`)
also gets `touch-action: manipulation` — taps stay, pans and double-tap-zoom
die.

### 2.5 The shared media hook + the one-control rule

The research, verbatim:

> 5. **Responsive primitive.** One `useMediaQuery` (promote
>    `transcript.tsx:1299-1311` → `state/media.ts`), consumed by
>    `app-shell.tsx:197`, `chat-page.tsx:440`, the M8 responsive surface, and
>    the M7 close-on-navigate. Container queries are not recommended for
>    this app: the shell's layout math (`state/layout.ts` width functions)
>    already needs the width as a JS number, and the CSS already keys on the
>    same 768/769 boundary — a second (container) system would drift, the
>    exact dead-band class `app-shell.tsx:193-196` warns about.

> 6. **The one-control rule stays.** Every phone overlay reuses the existing
>    z-ladder (`app.css:233-255`): drawers at 30 (under the titlebar, its
>    cluster stays clickable), sheets/dialogs at 50/70 — no new tiers.

Ownership: item 5's FILE (`state/media.ts`) and the two raw call-site
conversions (`app-shell.tsx:197`, `chat-page.tsx:440`) are ticket 49's; this
ticket consumes `useIsPhone()` for the `sidebarForGeometry` branch (§2.6) and
creates the file first if 49 has not landed (identical spec, §1's
parallel-ticket note). Item 5's M7 consumer and item 6's sheet placement are
later tickets' (49 and 51-55). **Do not add container queries** and **do not
add z-index tiers** — both are standing rules for every phone ticket.

### 2.6 Titlebar at phone — the chat title (M3)

The research's target spec, verbatim:

> Desktop reference: the identity sits right of the window-control cluster
> with `TITLEBAR_IDENTITY_GAP` 12 (`state/layout.ts:276`, `tabs.rs:318-350`'s
> gap-6 row), inset `content_left = (sidebar_now + SPACE_LG).max(
> title_bar_content_start() + plus_inset)` (`tabs.rs:185-186`). Phone
> translation — the sidebar term is 0 (it's an overlay), so the title sits
> "close to the buttons" exactly as the user asked:
>
> - `TITLEBAR_CONTENT_START` = 136 (`state/layout.ts:355-356`), `+` slot 32
>   (`state/layout.ts:280`) → phone rowLeft = 168 with a chat selected, 136
>   otherwise; settings route stays `TITLEBAR_CONTENT_START` (104–136) as
>   today (`app-shell.tsx:435-441`).
> - Title 12px Medium `text @ 85%`, ellipsized; the `folder @ device` tag
>   stays hidden at phone (`app.css:6811-6814`). Harness mark stays (14px,
>   `titlebar.tsx:870-884` `ChatIdentity`).

**Arithmetic note (checked against the cited lines, per the spec's
numbers rule):** `TITLEBAR_CONTENT_START` computes to **104**
(`TITLEBAR_CLUSTER_PAD 10 + CLUSTER_BUTTONS_WIDTH 82 + TITLEBAR_IDENTITY_GAP
12`, `state/layout.ts:355-356`, constants at `:272-280` — ticket 06 asserted
the same 104), and `TITLEBAR_ACTION_SLOT_WIDTH` = 32 (`:280`). So the phone
values out of the existing function with `sidebar = 0` are **136** (chat
selected, `+` visible: `max(0 + 16, 104 + 32)`) and **104** (otherwise and on
the settings route); the research's "168/136" double-counts the 32px slot.
The discrepancy is harmless — **the implementer never hardcodes the number**:
the fix is a call-site branch feeding the existing pure `titlebarRowLeft`.

**The fix (the research's gap row, verbatim):** "branch in `AppShell`:
`const sidebarForGeometry = phone ? 0 : sidebarWidth` — one line fixes M2's
band and M3's inset together." In `app-shell.tsx`:

```ts
const phone = useIsPhone();                       // state/media.ts (§2.5)
const sidebarForGeometry = phone ? 0 : sidebarWidth;
```

- `rowLeft` (`app-shell.tsx:435-441`) passes `sidebar: sidebarForGeometry`
  into `titlebarRowLeft` → 136/104 at phone (identity next to the buttons).
- `paneOpenWidth` (`app-shell.tsx:179`, `resolvePaneWidth({...pane, open:
  true}, viewport, sidebarForGeometry)`) and the band inputs
  (`app-shell.tsx:514-519`) use the same term → `rightPaneMaxWidth(375, 0) =
  75` instead of 0, so `--rb-pane-open`/`--rb-pane-band` stop collapsing.
  (The pane's phone FORM — right-side drawer, in-drawer tab strip — is
  ticket 52's; this ticket only fixes the geometry inputs.)
- `titlebarRowLeft` itself (`state/layout.ts:370-384`) is NOT modified — its
  desktop semantics are ticket 06's, verified by `tests/layout.test.ts`.

**Title layout at phone (what the user sees, 375px):** the row is
`[cluster … 10px pad][82px controls][12px gap][+ 32px slot when a chat is
selected][identity at x=136 … ellipsized 12px/500 title, harness mark 14px,
no folder tag][flex spacer][trailing pane toggle 28px + 6px edge inset
(`app.css:480-489`, `:299`)]` — roughly 199px of title room on a 375px
viewport. No CSS change to the identity (truncation is already correct,
§1); the folder-tag hide (`app.css:6811-6814`) stays.

### 2.7 The causal chain this closes (M4, verbatim from the research)

> There are NO touch handlers anywhere on the titlebar: `titlebar.tsx`
> renders plain buttons (`titlebar.tsx:162-247`) with only `onClick`; no
> `onTouchStart/Move`, no drag logic, no long-press timers. The scroll is not
> an app feature — it is unguarded browser default behavior reaching the
> transcript through one of two verified paths.

> - `.titlebar` is `position: absolute; inset: 0 0 auto 0; z-index:
>   var(--rb-z-titlebar)` = 40 (`app.css:292-316`, `:251`) — it overlays the
>   top 38px of the whole window, INCLUDING the open phone drawer (drawer
>   z 30, backdrop z 20 — `app.css:6822`, `:6838`; the ladder comment
>   documents "The phone sidebar itself stays at 30, deliberately under the
>   titlebar — its cluster must stay clickable to close what it opened",
>   `app.css:247-248`). So with the drawer open, every touch on the top band
>   lands on the titlebar, not the drawer.
> - `.titlebar` has NO `touch-action` and NO `user-select` (`app.css:292-316`
>   and the cluster/identity rules `:323-471` — the only `user-select: none`
>   sites in the file are the drag handles and lightbox, listed below). A
>   touch drag that starts on the titlebar therefore (i) is treated by the
>   browser as a pan gesture, and (ii) long-presses into the iOS/Android
>   text-selection callout on the title's text.
> - A pan gesture starting on a non-scrollable element chains to the nearest
>   scrollable ANCESTOR. The titlebar's chain is `.shell` (no overflow,
>   `app.css:264-279`) → `body` (`overflow: hidden`, `app.css:18-31`) → the
>   document. **`html`, `body` have no `overscroll-behavior`** (grep over
>   `app.css` finds `overscroll-behavior` only at `:2673`
>   `.queue-panel-list`, `:3243` `.composer-input`, `:6896` `.transcript`) —
>   and `html, body, #root { height: 100% }` (`app.css:12-16`) with `.shell
>   { height: 100% }` (`app.css:264-265`) is the classic layout-viewport
>   height that iOS Safari disagrees with across URL-bar states: `overflow:
>   hidden` on body alone does NOT stop iOS scroll-chaining/rubber-banding,
>   and a 100%-height document whose height tracks the large viewport leaves
>   the document scrollable by the toolbar height. A drag held on the
>   titlebar scrolls THAT document — the whole chat visibly slides ("the main
>   chat keep scrolling up… when i hold the titlebar").
> - The drawer's own list is also unguarded: `.sidebar-list { overflow-y:
>   auto }` with NO `overscroll-behavior: contain` (`app.css:1232-1239`) —
>   a fling that ends at the list's boundary chains to the same document.
>   This is the "when i open left sidebar" half of the report: opening the
>   drawer puts the user's finger on the titlebar band above the drawer
>   (z-order above), and any vertical drag there scrolls the document.

**Root cause (the research's §(c), verbatim):** "The parity wave ported the
desktop's scroll semantics for the regions the desktop HAS (the transcript's
contained wheel, the queue list's contained overscroll — `app.css:2673`,
cited from `queue.rs:2004`) but never installed the mobile app-shell
baseline: no `overscroll-behavior` on the document, no
`touch-action`/`user-select` on the always-touched chrome (titlebar), and
100%-height viewports that iOS can still scroll."

**The fix is the containment above** (§2.1's dvh + document overscroll,
§2.4's titlebar guards and per-scroller containment) — no touch handlers are
installed anywhere. Second-order mechanism (also from the research, addressed
by the same fix): on Android Chrome a touch near the top edge shows/hides the
URL bar → `resize` → `useViewportHeight` (`routes/chat-page.tsx:828-839`) and
`useViewportWidth` (`state/layout.ts:538-549`) re-render, the transcript
scroller resizes, and its ResizeObserver fires `stick.kick()`
(`components/transcript.tsx:796-800`) — with the bottom pin live the spring
rewrites `scrollTop` (`components/stick-controller.ts:510-575`). Those writes
are intended (the ported `wake_spring`) but fire from non-user input on a
device where mere touching mutates the viewport; the URL-bar-stable `100dvh`
shell removes the mutation.

---

## 3. Pure logic to port

**Breakpoint resolution.** `PHONE_MAX_WIDTH = 768` (`state/layout.ts:43`) is
the stylesheet's boundary: every phone block in `app.css` is
`@media (max-width: 768px)` and every desktop block `(min-width: 769px)`.
`useIsPhone()` resolves `(max-width: 768px)` through `matchMedia`, so JS and
CSS agree by construction — innerWidth and the media query can disagree by
rounding, which is the dead-band class of bug the comment at
`app-shell.tsx:193-196` documents ("asking at a wider one left a dead band
where the click flipped the drawer flag while CSS still drew the column").

**useMediaQuery choice (the research's recommendation, copied):** promote
`transcript.tsx:1299-1311`'s 12-line hook into `state/media.ts`. "Adopting
`@base-ui/react/unstable-use-media-query` is the alternative; it works
(verified above) but adds an unstable-named subpath import where the repo
already has the 12-line hook." → **Choose the local hook.** (Ticket 49 owns
the file and the two raw call-site conversions; this ticket consumes it for
`sidebarForGeometry`, creating the file first from the identical spec if 49
has not landed.)

**Desktop test names: NONE map.** The research is explicit: "Almost nothing
new ports: the mobile layer is web-native (the desktop has no phone layout —
its minimum geometry assumptions, `CHAT_PANEL_MIN` 300 plus `SIDEBAR_MIN`,
make a 375px window impossible)… No desktop test names map to these — that is
the point; they are mobile-native rules and must say so in the ticket."

**NEW web unit tests to write (`web/packages/app/tests/layout.test.ts`),
named after the function they branch, beside the existing
`describe("titlebarRowLeft")` (`:130-167`) and `describe("column widths")`
(`:58-110`) blocks:**

- `titlebarRowLeft phone sidebar is out of flow` —
  `titlebarRowLeft({sidebar: 0, showsNewSession: true, takeover: false})`
  === 136 and `{sidebar: 0, showsNewSession: false, takeover: false}` ===
  104 (today's phone reality is 320 because `AppShell` feeds the live
  sidebar width, e.g. 304: `max(304 + 16, …)`).
- `right_pane_ceiling_keeps_a_phone_floor` (phone-input case for the research's
  M2 logic row) — `rightPaneMaxWidth(375, 0)` === 75 (vs
  `rightPaneMaxWidth(375, 304)` === 0, the collapse `AppShell` produces
  today via `state/layout.ts:54-56`).
- Optionally `titlebarPaneBandWidth` phone inputs (same file, beside
  `:149-166`): with `{viewport: 375, rowLeft: 136, takeover: false}` and a
  real `paneWidth`, the band no longer resolves to 0.
- The `sidebarForGeometry` branch itself is one line inside `AppShell` —
  the node environment renders nothing (the `base-popover.test.ts` header's
  own rule), so it is verified by the 375px acceptance captures, not a render
  test.

---

## 4. Gaps this ticket closes

Copied verbatim from the research's §(d) tables (M3 and M4) and its
consolidated gap table.

**M3 rows:**

| item | kind | expected | web value (file:line) | fix |
| --- | --- | --- | --- | --- |
| rowLeft at phone | geometry | `TITLEBAR_CONTENT_START + plusInset` (168/136) | `max(304+16, …) = 320` (`app-shell.tsx:435-441`; `state/layout.ts:383`) | branch in `AppShell`: `const sidebarForGeometry = phone ? 0 : sidebarWidth` — one line fixes M2's band and M3's inset together |
| title visibility | behavior | title readable next to the cluster | ~20px of row (`app.css:298` + 320 inset) | same fix; no CSS change needed (truncation already correct) |

*(The "168/136" cell is the research's own; §2.6's arithmetic note resolves
it to 136/104 from the cited constants — same one-line fix either way.)*

**M4 rows:**

| item | kind | expected | web value (file:line) | fix |
| --- | --- | --- | --- | --- |
| document scroll chaining | CSS | `overscroll-behavior: none` + fixed/dvh body | absent (`app.css:12-31`) | add |
| titlebar touch defaults | CSS | `touch-action: manipulation; user-select: none` | absent (`app.css:292-316`) | add |
| drawer list chaining | CSS | `overscroll-behavior: contain` | absent (`app.css:1232-1239`) | add |
| viewport height unit | CSS | `100dvh` shell (URL-bar stable) | `height: 100%` chain (`app.css:12-16`, `:264-265`) | `100dvh` + `100vh` fallback |

**Consolidated-table rows for this ticket:**

| # | item | kind | expected | web value (file:line) | fix sketch |
| --- | --- | --- | --- | --- | --- |
| M3 | rowLeft at phone | geometry | 168/136 | 320 (`app-shell.tsx:435-441`) | same one-line branch |
| M4 | document chaining | CSS | overscroll none + dvh | absent (`app.css:12-31`) | add |
| M4 | titlebar touch | CSS | manipulation + no select | absent (`app.css:292-316`) | add |
| M4 | drawer list chaining | CSS | contain | absent (`app.css:1232-1239`) | add |

**Side effect, not claimed:** the M2 logic row ("pane width at phone |
logic | sidebar term 0 | `rightPaneMaxWidth(375, 304)=0` (`state/layout.ts:54-56`)
| `sidebarForGeometry = phone ? 0`") is fixed here as a side effect of the
shared branch; M2's mount/tab-strip rows (the right pane's phone FORM) stay
ticket 52's. M1 (composer gutters), M5/M9 (new-thread canvas — 53), M6
(settings furniture), M7 (drawer close-on-navigate) are 51-55's and are not
touched here.

---

## 5. Do not

- **Do not rebuild the desktop-width experience.** Decisions 1–4 still apply
  at ≥769px; do not touch desktop-width CSS behavior — the `100dvh` and
  safe-area rules are phone-scoped (§2.1's scope decision);
  `overscroll-behavior: none` on `html, body` is render-neutral at desktop
  (body never scrolls, `app.css:18-31`) but scope it too if capture diffing
  shows any change.
- **Do not break the existing phone drawer sidebar while landing this.** The
  left drawer (`app.css:6816-6832`), its backdrop (`:6834-6844`), the
  toggle's one-control-two-meanings (`app-shell.tsx:196-202`), and the
  z-ladder (titlebar 40 over drawer 30, `app.css:247-248`) must keep working.
  `touch-action: manipulation` keeps taps — it kills pans and double-tap-zoom,
  not clicks, so the drawer's close-from-titlebar-cluster flow is untouched.
- **51/52/53/54/55 own the feature-level mobile work — cross-reference by
  number, do not implement their slices here:** M1 (composer gutters), M2's
  pane form (52; only its geometry inputs land here), M5/M9 (new-thread
  canvas + the `.dock-target-selectors` un-hide — 53), M6 (settings
  furniture), M7 (drawer close-on-navigate). Ticket 49 owns the dialogs'
  sheet form and the `state/media.ts` call-site conversions.
- **Do not port the desktop's titlebar drag region** — `WindowControlArea::
  Drag`, double-click zoom, `start_window_move` (`shell.rs:3913-3935`) and
  `-webkit-app-region: drag` are desktop-only (ticket 06 already dropped
  them; the research's desktop-only list). The browser owns the window.
- **Do not add touch/pointer drag handlers to the titlebar.** The scroll is
  not an app feature (§2.7); the fix is the CSS guards. Adding JS touch
  logic would be INVENTED surface area.
- **Do not add container queries** — the research's item 5 recommends against
  them (the shell's layout math needs the width as a JS number; a second
  container system would drift — the dead-band class `app-shell.tsx:193-196`
  warns about).
- **Do not add new z-index tiers** — the ladder at `app.css:233-255` is
  closed (research item 6).
- **Do not change `titlebarRowLeft`'s desktop semantics** (`state/layout.ts:
  370-384`) or any other pure width function — the phone arm is a call-site
  branch (`sidebarForGeometry`), not a function change; the existing
  `tests/layout.test.ts` cases must stay green unmodified.
- **Do not hardcode the phone rowLeft** (136/104 or the research's 168/136) —
  the number comes out of the existing pure function once `sidebar = 0`
  (§2.6's arithmetic note).
- **Do not use the `position: fixed` body arm** of M4's first guard row —
  this ticket implements the `100dvh` arm (§2.4); a fixed body would fight
  the sheet/dialog portals.

---

## 6. Acceptance

- [ ] Hold the phone titlebar (touch-drag on the top 38px band, drawer closed
      AND open) → the transcript does not scroll and the document does not
      rubber-band.
- [ ] Fling the open phone sidebar's list to its boundary → the document
      behind does not scroll (`.sidebar-list` containment).
- [ ] Rotate the device / open the iOS keyboard / collapse the URL bar → no
      viewport jump (100dvh; the shell no longer tracks the large viewport;
      the bottom-pinned transcript does not re-kick from a toolbar resize).
- [ ] Chat title visible next to the buttons on a 375px viewport: with a chat
      selected the identity starts at x=136 (the `+` slot included), the
      12px/500 title ellipsizes in ~199px, the folder tag stays hidden; on
      the new-chat canvas and the settings route the row starts at 104.
- [ ] Long-press the titlebar's title text → no text-selection callout
      (`user-select: none`).
- [ ] Double-tap on the titlebar → no zoom (`touch-action: manipulation`);
      single taps on the cluster/toggle still fire their actions.
- [ ] Every interior scroller contains its overscroll at phone: transcript
      (regression — `app.css:6896` stays), settings scroll, settings nav,
      engine drawer, add-space list. Desktop wheel behavior unchanged.
- [ ] Notched device (or Chrome device emulation with insets): the titlebar
      clears the notch (`env(safe-area-inset-top)`), the composer and the
      engine drawer clear the home indicator
      (`env(safe-area-inset-bottom)`); non-notched devices render identically
      to before (insets resolve to 0).
- [ ] Android IME: focusing the composer resizes the layout — the composer
      stays above the keyboard (`interactive-widget=resizes-content`).
- [ ] Desktop (≥769px): a screenshot of the titlebar with a chat selected
      (sidebar at default 256 and mid-drag) is pixel-identical to a
      pre-ticket capture; the `titlebarRowLeft` glide still tracks the
      sidebar.
- [ ] Unit tests: `titlebarRowLeft phone sidebar is out of flow` and the
      `rightPaneMaxWidth(375, 0) = 75` phone-input case land in
      `tests/layout.test.ts` (new, mobile-native — no desktop test maps,
      §3); all existing layout tests stay green unmodified.
- [ ] Screenshot pair at 375px: states — chat selected (title visible next
      to the cluster), phone drawer open mid-fling at the list boundary,
      composer focused with the keyboard up, settings page scrolled hard
      against its boundary.
- [ ] `pnpm -r build` green; package vitest green.
- [ ] No new literal hex/px where a `--rb-*` token exists.

## Comments

(empty; appended during implementation)
