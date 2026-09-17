# 09 — Popover primitive

**What to build:** Today every floating menu on the web (`picker-popover.tsx`,
the space-filter dropdown, the chat context menu) reimplements its own open
state, its own row styling, its own dismissal rules, and none of them
animate closed, show a scrollbar, or show a loading skeleton. After this
ticket there is one generic popover primitive — the web's version of the
desktop's `popover.rs` — that every menu in the app builds on: a frosted
floating card that fades and slides in over 140ms, fades and slides out over
100ms before unmounting, dismisses on outside pointer-down (not click, not
Tab, not wheel), exposes shared row/heading/separator/search-frame/skeleton
building blocks, and offers an on-demand floating scrollbar for long lists.
This ticket does not change what any menu contains — it only rebuilds the
shell they will sit in. Tickets 10, 11, and 14 rebuild the individual menus
on top of it.

**Blocked by:** 02 (Foundation tokens)

**Status:** ready-for-agent

**Research:** `../../web-client/research/05-pickers-popovers.md` §3.0 (theme
helper bodies), §3.1 (`popover_card`), §3.2 (anchored-menu placement), §3.3
(motion/blur/outside-press), §3.4 (`Popup<T>` lifecycle), §3.5 (`menu_row`/
`menu_row_nav`), §3.6 (`menu_heading`), §3.7 (`menu_separator`/
`menu_section`), §3.8 (`search_input_frame`), §3.9 (key caps), §3.10
(skeleton/error states), §3.11 (floating scrollbar), §3.12 (dialog
primitives), §4.1 (`menu_step`), §4.2 (`match_rank`/`filter_indices`), §4.3
(`classify_key`), §5 rows 10, 11, 18, 19, 20, 22 (frame max-height only), 23
(mechanism only), 24–30, 33–38, 40 (primitive only), 44 (component only),
86, 87, 88, §6 (desktop-only notes on blur/contrast-solve/gpui specifics).

**Desktop reference (for lookups only):**
- `crates/ui/src/popover.rs::popover_card` (306), `::popover_card_flush`
  (329), `::palette_card` (820)
- `crates/ui/src/popover.rs` anchored-menu family (`anchored_menu` 420,
  `anchored_menu_below` 447, `anchored_menu_below_gap` 490,
  `anchored_menu_below_end` 458, `anchored_menu_above` 523,
  `anchored_menu_above_at` 549, `anchored_menu_above_end` 590,
  `full_width_menu_above` 564, `menu_at` 621)
- `crates/ui/src/popover.rs::frosted_menu`/`menu_motion`/`exit_progress`
  (351–411)
- `crates/ui/src/popover.rs::Popup<T>` (68–206)
- `crates/ui/src/popover.rs::menu_row`/`::menu_row_nav` (713–765)
- `crates/ui/src/popover.rs::menu_heading`/`::tracked_upper` (771–795)
- `crates/ui/src/popover.rs::menu_separator`/`::menu_section` (799–970)
- `crates/ui/src/popover.rs::search_input_frame` (946–955)
- `crates/ui/src/popover.rs::key_cap`/`::key_hint*`/`::kbd_hint` (841–940)
- `crates/ui/src/popover.rs::skeleton_rows`/`::skeleton_menu_rows`/
  `::skeleton_bar`/`::error_row` (1080–1163)
- `crates/ui/src/popover.rs::MenuScrollbarState`/`::render_rail` (1170–1397),
  `::HorizontalScrollbarState` (1406–1546)
- `crates/ui/src/popover.rs::modal`/`::modal_glass`/`::dialog_card`/
  `::dialog_title`/`::dialog_body`/`::dialog_field`/`::btn_ghost`/
  `::btn_primary`/`::btn_danger` (657–1076)
- `crates/ui/src/popover.rs::menu_step` (214–230), `::match_rank`/
  `::filter_indices` (235–260), `::classify_key` (276–291)
- `crates/ui/src/theme.rs` helper bodies (`ink` 1499/368, `wash` 1531,
  `hairline` 1518/374, `band` 1567, `scrim` 1552/1544, `glass_hover` 908,
  `is_frost` 900, `glass_overlay` 915/864, `glass_selected_bg` 1588,
  `glass_selected_shadows` 1637, `card_selected_bg` 1611,
  `card_selected_shadows` 1649)
- `crates/proto/src/motion.rs` (motion catalog), `crates/proto/src/layout.rs`
  (`SPACE_*`, `PANEL_RADIUS`, `CONTROL_RADIUS`, `GLASS_OVERLAY_ALPHA_*`),
  `crates/ui/src/frost.rs::MENU_BLUR` (23)

**Web files to touch:**
| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/components/popover/popup.tsx` | new | the open/closing/closed `Popup` hook/component wrapper, exit occlusion overlay, outside-pointerdown dismissal, focus handling |
| `web/packages/app/src/components/popover/menu.tsx` | new | `PopoverCard`, `PopoverCardFlush`, `MenuHeading`, `MenuSeparator`, `MenuSection`, `SearchInputFrame`, `KeyCap`/`KbdHint`, dialog primitives (`Modal`, `ModalGlass`, `DialogCard`, `DialogTitle`, `DialogBody`, `DialogField`, `BtnGhost`, `BtnPrimary`, `BtnDanger`) |
| `web/packages/app/src/components/popover/menu-row.tsx` | new | `MenuRow`, `MenuRowNav` (selected / highlighted / disabled variants) |
| `web/packages/app/src/components/popover/scrollbar.tsx` | new | `MenuScrollbar` (the on-demand floating rail), the `HorizontalScrollbar` twin |
| `web/packages/app/src/components/popover/skeleton.tsx` | new | `SkeletonRows`, `SkeletonMenuRows`, `SkeletonBar`, `ErrorRow`, the pulse animation |
| `web/packages/app/src/lib/popup-lifecycle.ts` | new | the `Popup<T>`-equivalent state machine: `open`/`closing`/`closed`, `beginClose`/`finishClose`/`reapPopup` timer, `noteTriggerPress`/`takePressWasOpen`, `exitProgress` |
| `web/packages/app/src/lib/popover-anchor.ts` | new | the anchored-menu placement helpers (`anchorBelow`, `anchorBelowEnd`, `anchorAbove`, `anchorAboveAt`, `anchorAboveEnd`, `menuAt`) — clamp-into-viewport math, no flip except where a specific menu asks for a flip fallback in a later ticket |
| `web/packages/app/src/lib/picker-search.ts` | edit | `matchRank` (rows 10–11), keep `filterAndSort` name and signature so ticket 10 can keep calling it |
| `web/packages/app/src/components/picker-popover.tsx` | delete | superseded by `components/popover/*`; ticket 10 replaces its call sites |
| `web/packages/app/src/styles/app.css` | edit | new shared classes: `.popover-card`, `.popover-card-flush`, `.menu-row`, `.menu-row-selected`, `.menu-row-highlighted`, `.menu-heading`, `.menu-separator`, `.menu-section`, `.search-input-frame`, `.key-cap`, `.kbd-hint`, `.skeleton-row`, `.skeleton-menu-row`, `.skeleton-bar`, `.error-row`, `.menu-scrollbar-rail`, `.menu-scrollbar-thumb`, `@keyframes rb-menu-in`, `@keyframes rb-menu-out`, `@keyframes rb-skeleton-pulse`; delete `.picker-popover*` and `.picker-row*` (ticket 10 rebuilds their call sites against the new classes) |
| `web/packages/app/tests/popup-lifecycle.test.ts` | new | unit tests for §3 below |
| `web/packages/app/tests/picker-search.test.ts` | new | unit tests for `menu_step`/`match_rank`/`filter_indices`/`classify_key` |

---

## 1. Context a fresh session needs

- The desktop's `popover.rs` (1720 lines) is a small library of primitives —
  a card shell, a placement family, a motion/blur/dismissal wrapper, a row
  style, section chrome, a search frame, key caps, skeletons, a floating
  scrollbar, and dialog chrome — that every menu and picker in the app is
  built from. This ticket ports that library to the web, once, as
  `components/popover/*` + `lib/popup-lifecycle.ts` + `lib/popover-anchor.ts`.
- `web/packages/app/src/components/picker-popover.tsx` (214 lines) is the
  web's closest existing analog. It conflates the card shell, the search
  input, the row list, and the keyboard handling into one component with the
  wrong dismissal rule (click-outside does nothing; Tab closes it — neither
  matches the desktop) and no exit animation, no scrollbar, no skeletons.
  This ticket deletes it and replaces it with composable primitives; ticket
  10 rebuilds every picker on top of them.
- Tokens this ticket consumes (added by ticket 02, referenced not defined
  here): `--rb-overlay`, `--rb-glass-overlay-alpha`, `--rb-hairline`,
  `--rb-wash`, `--rb-ink`, `--rb-hover`, `--rb-text`, `--rb-text-muted`,
  `--rb-text-faint`, `--rb-danger`, `--rb-border`, `--rb-motion-menu-in`
  (140ms), `--rb-motion-menu-out` (100ms — **add this token if ticket 02
  did not**: it is not yet used anywhere in `app.css`), `--rb-motion-hover-fade`
  (150ms), `--rb-motion-dialog-in` (180ms), `--rb-motion-roboco-pulse`
  (2400ms), `--rb-ease-ease`, `--rb-ease-ease-tailwind`,
  `--rb-space-xs/sm/md/lg` (4/8/12/16), `--rb-radius-control` (6),
  `--rb-radius-panel` (10). Add `--rb-radius-card: 12px` if it does not
  exist yet (gap row 86 — floating cards use `CARD_RADIUS = 12`, not
  `PANEL_RADIUS = 10`).
- `frost.rs::MENU_BLUR = 44.0` is the backdrop-blur radius under every
  floating card; the web equivalent is CSS `backdrop-filter: blur(44px)`,
  which — unlike gpui's `BackdropBlur` primitive — fades naturally with the
  element's own opacity, so the web does **not** need to ride the blur
  radius down during the close animation the way `frosted_menu` does
  (`popover.rs:369`); it can just fade opacity and let `backdrop-filter`
  follow. See §6 of the research and this ticket's Do-not section.
- `theme.rs`'s contrast-checked `glass_overlay()` alpha (base 0.50 dark /
  0.85 light, raised in 1/20 steps until a 4.5:1 / 3.0:1 contrast check
  passes) is a 21-step runtime solve the web does not need to reproduce at
  paint time — ticket 02 precomputes the result into
  `--rb-glass-overlay-alpha`. This ticket just consumes that variable.
- Vocabulary: "popup" (this ticket's own term for the open/closing/closed
  state machine) mirrors the desktop's `Popup<T>`; do not confuse with
  "popover" (the card itself).
- No menu in the app has a scrim (dimmed backdrop) except `modal`/
  `modal_glass` — see §2.12. Every other floating card sits directly over
  the page with no dimming, dismissed by `occlude()` + an outside-pointerdown
  listener, not a backdrop click-catcher.
- There is no focus trap and no hover-open delay anywhere in `popover.rs`
  (gap rows 87, 88 — both listed as "MATCHES (absent)": do not add either).
  The lone exception, a 350ms tooltip show-delay on the sidebar's
  view-options button, belongs to ticket 10.

---

## 2. Spec

### 2.0 Theme helper bodies (context every component below reads)

Copied from `theme.rs`, verbatim (research §3.0):

| Helper | Body | Source |
|---|---|---|
| `ink(a)` | dark: `hsla(0,0,1.0, a)` (white). light: `hsla(0,0,0, a * INK_FILL_SCALE)`, `INK_FILL_SCALE = 1.0` — i.e. the same alpha, tone flipped. Web: `rgb(var(--rb-ink) / a)` | `theme.rs:1499`, `:368` |
| `wash(a)` | dark: `hsla(0,0,0.92, a)` (soft white, not pure). light: `hsla(0,0,0.10, a * 1.0)`. Web: `rgb(var(--rb-wash) / a)` | `theme.rs:1531` |
| `hairline(a)` | dark: `hsla(0,0,1.0, a)`. light: `hsla(0,0,0, min(a * INK_HAIRLINE_SCALE, 0.5))`, `INK_HAIRLINE_SCALE = 1.35`. Web: `rgb(var(--rb-hairline) / a)` with `--rb-hairline-scale` | `theme.rs:1518`, `:374` |
| `band()` | dark: `hsla(0,0,0, 0.16)`. light: `hsla(0,0,0, 0.045)` | `theme.rs:1567` |
| `scrim(aDark)` | dark: `hsla(0,0,0, aDark)`. light: `hsla(0,0,0, 0.32 * (aDark / 0.60))`. `SCRIM_ALPHA_DARK = 0.60` | `theme.rs:1552`, `:1544` |
| `Theme::glass_hover()` | **returns `theme.element_hover` verbatim** — no computation. Web: `var(--rb-hover)` | `theme.rs:908` |
| `Theme::is_frost()` | `surface_treatment == Frosted && (macos \|\| linux \|\| windows)` — desktop-only gate, see Do-not | `theme.rs:900` |
| `Theme::glass_overlay()` | If `!is_frost()` → `surface_overlay` opaque. Else: base alpha = `GLASS_OVERLAY_ALPHA_DARK = 0.50` (dark) / `GLASS_OVERLAY_ALPHA_LIGHT = 0.85` (light), then `contrast_checked_tint_alpha(surface_overlay, base, adverse_backdrop)` raises coverage in 1/20 steps toward 1.0 until `contrast(text, composite) >= 4.5` **and** `contrast(text_muted, composite) >= 3.0`. Adverse backdrop is white on dark, black on light. Result = `surface_overlay.opacity(that alpha)`. Web: `--rb-glass-overlay-alpha` + `var(--rb-overlay)`, precomputed by ticket 02 | `theme.rs:915`, `:864`, `layout.rs:87–90` |
| `glass_selected_bg()` | dark `wash(0.11)`, light `wash(0.06)` — the *chrome* (sidebar rows, tabs) selection fill | `theme.rs:1588` |
| `glass_selected_shadows()` | `= card_selected_shadows()` (aliased; the two appearances share one recipe now) | `theme.rs:1637` |
| `card_selected_bg()` | dark `wash(0.11)`, light `wash(0.06)` — selection **inside a floating card** (menu rows, picker rail, segmented chips) | `theme.rs:1611` |
| `card_selected_shadows()` | one `BoxShadow { color: dark hairline(0.09) / light hsla(0,0,0,0.07), offset 0 0, blur 0, spread 1px, inset: true }` — an inset ring, painted ON TOP of the fill, zero layout cost. Never a drop shadow: a filled rect behind a translucent fill reads as a grey plate. Web: `box-shadow: inset 0 0 0 1px rgb(var(--rb-hairline) / 0.09)` | `theme.rs:1649` |
| `frost::MENU_BLUR` | `44.0` px backdrop blur under every floating card | `frost.rs:23` |

Motion catalog used by this surface (`proto/src/motion.rs`):

| Name | Value | CSS token |
|---|---|---|
| `MENU_IN` | 140ms, `EASE` = `cubic-bezier(0.25, 0.1, 0.25, 1)` | `--rb-motion-menu-in`, `--rb-ease-ease` |
| `MENU_OUT` | 100ms, `EASE` | `--rb-motion-menu-out` |
| `HOVER_FADE` | 150ms, `EASE_TAILWIND` = `cubic-bezier(0.4, 0, 0.2, 1)` | `--rb-motion-hover-fade`, `--rb-ease-ease-tailwind` |
| `DIALOG_IN` | 180ms, `EASE` | `--rb-motion-dialog-in` |
| `CHEVRON` | 200ms, `EASE` | `--rb-motion-chevron` |
| `ROBOCO_PULSE` | 2400ms period, `EASE` | `--rb-motion-roboco-pulse` |

Layout constants: `SPACE_XS = 4`, `SPACE_SM = 8`, `SPACE_MD = 12`, `SPACE_LG = 16`,
`PANEL_RADIUS = 10`, `CONTROL_RADIUS = 6` (`layout.rs:16–34`). Font sizes are
`typography::ui_rems(px) = rems(px / 16)`; use `rem`-based sizes on the web
wherever the desktop calls `ui_rems`, so the popover scales with the user's
UI font-size setting.

### 2.1 `PopoverCard` / `PopoverCardFlush` — the floating card shell

Desktop: `popover_card`/`popover_card_flush`, `popover.rs:306–331`.

**Layout**

| Property | Value | Source |
|---|---|---|
| border | `1px solid hairline(0.10)` | `popover.rs:311-312` |
| border-radius | `CARD_RADIUS = 12.0` px — a dedicated `--rb-radius-card` token, **not** `PANEL_RADIUS (10)` | `popover.rs:306`, `:313` |
| box-shadow | `shadow_lg()` (gpui elevation shadow; exact offset/blur/spread not in this repo — see Comments/open question) | `popover.rs:314` |
| padding | `4px` all sides | `popover.rs:315` |
| overflow | `hidden` | `popover.rs:316` |
| font-size | `ui_rems(13.0)` | `popover.rs:316` |
| color | `theme.text` | `popover.rs:317` |
| background | `var(--rb-overlay)` at `--rb-glass-overlay-alpha`, with `backdrop-filter: blur(44px)` | `popover.rs:320-324` |

`PopoverCardFlush` = `PopoverCard` with `padding: 0` — for cards that own
their internal panes (used by ticket 10's harness/model card split).

`palette_card(width, cornerRadius)` (`popover.rs:820`) — the command-palette
sibling (ticket 11 consumes this): explicit width, caller's radius,
`hairline(0.10)` border, same glass/opaque background, `shadow_lg`,
`overflow: hidden`, flex column, `color: theme.text`, **no padding**. Build
it here as `PopoverCard`'s sibling since it shares the background/border/
shadow recipe; ticket 11 supplies width and radius.

**Gap this closes** (row 19, 20, 86): the current `.picker-popover` uses
`background: var(--rb-dialog)` with no blur, `box-shadow: 0 8px 30px rgb(0 0
0 / 0.35)`, and `padding: 6px` — all wrong values. `.panel` uses
`--rb-radius-panel` (10) where floating cards need `--rb-radius-card` (12).

### 2.2 Placement — the anchored-menu family

`popover.rs:420–638`. Every variant paints on a floating layer (the web:
a **portal to `document.body`** with `z-index`) above everything, and
`snap_to_window_with_margin(px(8.0))` is the flip/clamp rule on **every**
variant: the card is pushed back inside the window with an 8px margin.
**There is no side-flip** — the chosen side is fixed by which helper the
caller picks, not computed at render time.

| Helper | Anchor corner | Trigger→card gap | Typical caller (built by a later ticket) |
|---|---|---|---|
| `anchorBelow` (`anchored_menu_below`) | top-left of trigger | `6px` | sidebar space filter (10) |
| `anchorBelowGap(gap)` (`anchored_menu_below_gap`) | top-left | caller's gap (changes header passes ~10) | changes header (22) |
| `anchorBelowEnd` (`anchored_menu_below_end`) | top-right | `6px` | sidebar view-options menu (10) |
| `anchorAbove` (`anchored_menu_above`) | bottom-left | `6px` | composer footer chips: device, checkout, ref (10, 13) |
| `anchorAboveAt(x, y)` (`anchored_menu_above_at`) | bottom-left at explicit point | `6px` | text completions caret anchor (14) |
| `anchorAboveEnd` (`anchored_menu_above_end`) | bottom-right | `6px` | **the run-identity chip**, the project chip (10) |
| `fullWidthMenuAbove` (`full_width_menu_above`) | spans trigger width, bottom-anchored | `6px` | full-width composer menus (13) |
| `menuAt(x, y)` (`menu_at`) | top-left at explicit point, **no flip, clamp only** | none | context menus: chat, space, file tree, browser, markdown link (10) |

Build each as a small function in `lib/popover-anchor.ts` returning a
`{ left, top }` (or `{ right, top }` for the `*_end`/`*_below_end` variants)
given the trigger's `DOMRect` and the card's measured size, clamped to
`[8, viewport - size - 8]` on both axes. `chat-menu.tsx:101–104` already
implements this exact clamp for one menu — port that logic here as the
shared helper instead of leaving it duplicated per menu.

**Gap this closes** (row 23, mechanism only — per-menu application is
ticket 10/11's job): the desktop's `menu_at` **does not flip**, it only
clamps. `chat-menu.tsx:103` currently flips above when it would overflow —
that behavior moves to `menuAt` being clamp-only when ticket 10 rebuilds the
chat menu on this primitive.

### 2.3 Motion, blur and the outside-press guard

`popover.rs:351–411`.

**Motion**

| What animates | Trigger | Spec | From → to | Notes |
|---|---|---|---|---|
| Card opacity + Y | popover opens | `MENU_IN` (140ms, `--rb-ease-ease`) | `opacity 0.3 → 1.0`; `translateY(-2px) → 0` | roboco also scales `0.96 → 1`; gpui divs have no scale at the pinned rev so the desktop drops it — **the web should keep the scale**: `transform: translateY(-2px) scale(0.96) → none` |
| Card opacity + Y | popover closes (the `closing` phase) | `MENU_OUT` (100ms, `--rb-ease-ease`) | `opacity 1 → 0`; `translateY(0) → -2px` | Progress is computed from the wall clock at render time, never from the CSS animation's own clock, so a same-id replay mid-exit does not flash back to full opacity — use a real timestamp-driven `exitProgress()` (§3), not a CSS animation restart, if the popup can reopen mid-exit |
| Backdrop blur | during the exit | N/A on the web | — | The desktop rides `MENU_BLUR (44) → 0` during the exit because gpui's `BackdropBlur` ignores element opacity. CSS `backdrop-filter` fades naturally with the element's own opacity — **do not port this animation**, just fade opacity and let the blur follow (see Do-not) |
| Hover wash + text on rows/chips | pointer enter/leave | `HOVER_FADE` (150ms, `--rb-ease-ease-tailwind`) | per-element | Reduced motion snaps to the endpoint. Use a CSS `transition`, not a JS blend — the desktop's `hover_blend` is a gpui workaround for lacking CSS transitions and has no web equivalent need |

**Exit occlusion.** While exiting, put a full-bleed
`position: absolute; inset: 0` div on top of the closing card's content: a
dying menu's rows must not take clicks, and it also stops stray clicks
reaching whatever sits underneath (`popover.rs:406`).

**Outside-press guard.** Register a **capture-phase** `pointerdown` listener
on `window` while the popup is open or closing; if the press target is
outside the card, close it and call `stopPropagation()`/`preventDefault()`
on that **one** event only — do **not** swallow the click that follows.
This mirrors `frosted_menu`'s bubble-phase `stop_propagation()` after the
dismiss listeners run in capture (`popover.rs:375–384`): the desktop
consumes the mouse-down, not the click, so content behind the menu reacts to
the *next* interaction normally, not the dismissing one.

**Focus.** Track focus into the card; on a mouse-down inside the card,
re-focus the card's frame if focus had escaped it; on mouse-down outside,
dismiss and blur if focus was inside (`pickers.rs:2743–2756`, generalized
here as a reusable behavior). This is **not a focus trap** — Tab is never
intercepted anywhere in this primitive (gap row 87, "matches (absent)" —
keep it absent).

**Nested menus.** There is no nested-popover primitive in `popover.rs`. Do
not build one here. (Ticket 10's chat-menu Copy sub-page replaces the
card's contents in place with a "Back" row — not a second floating layer —
when it is rebuilt.)

**Scrim.** This primitive's menus have **no scrim**. Only `Modal`/
`ModalGlass` (§2.12) do.

### 2.4 `Popup<T>` — the open/closing/closed lifecycle

`popover.rs:68–206`. Every menu in the app owns one instance of this state
machine; build it once as a hook (or small class) in
`lib/popup-lifecycle.ts` and a thin `<Popup>` wrapper component in
`components/popover/popup.tsx` that mounts the card through the three
states below.

**States**

| State | `isOpen` | `isClosing` | `get()` | `asOpen()` | What renders |
|---|---|---|---|---|---|
| closed | false | false | `null` | `null` | nothing |
| open | true | false | value | value | card, `menu_in` motion, live hit-testing |
| closing | **false** | true | value | `null` | card still mounted, `menu_out` motion + occluding overlay, dead hit-testing |

- `beginClose()` stamps `closingSince = now()` and returns `true` only the
  first time it is called for a given open session; the caller then
  schedules `reapPopup`.
- `reapPopup` waits `MENU_OUT total (100ms) * speedScale + 20ms` (120ms at
  1× speed) then calls `finishClose()`, which drops the state **only if**
  the exit has actually run its course — a popup reopened in the meantime is
  left alone (`popover.rs:174–206`).
- Logic paths (which menu is "open", key handlers) read `asOpen()` so a
  closing popup already reads as closed; render paths read `get()` so the
  card keeps painting through its exit animation.

**`noteTriggerPress` / `takePressWasOpen`** (`popover.rs:152–169`) — the
click-to-close fix. The card's outside-pointerdown handler fires on the
*same press* that the trigger's click will later complete, so by click time
the popup already reads as closed and a naive `toggle()` would close-and-
reopen in the same gesture. The trigger's `pointerdown` handler therefore
records (in a ref, keyed by which trigger) whether the popup was still
mounted at press time; the trigger's `click` handler consumes that note and,
if it says "was open", stays closed instead of reopening.
`noteTriggerPressMatching(predicate)` is the multi-trigger variant: a press
on a *different* trigger does not count, so that click switches menus
instead of swallowing (ticket 10's `pickers.rs:2292` equivalent uses
`(open) => open === kind`).

**Web translation.** A three-state hook, not a boolean:
`"closed" | "open" | "closing"`, a 100ms timer before unmount,
`pointer-events: none` on the closing card, and a "was open at pointerdown"
ref per trigger.

**Gap this closes** (rows 25, 26, 34): the web today unmounts a popover
instantly on close (no `MENU_OUT`, no `note_trigger_press`, no lifecycle).
Escape should call the equivalent of `animate_close()` — begin the close AND
return focus to whatever opened the popup (ticket 10's composer chips route
this to the composer; the primitive here only needs to expose a callback
slot for it, e.g. an `onClosed` that a picker-specific caller can use to
refocus). Outside clicks call plain `dismiss()` — they do **not** trigger
that focus-return; they leave focus wherever the click landed
(`pickers.rs:871-890`, test `picker_completion_and_dismissal_have_distinct_focus_behavior`).

### 2.5 `MenuRow` / `MenuRowNav` — one menu row

`popover.rs:713–765`.

**Layout** (`MenuRow`)

| Property | Value | Source |
|---|---|---|
| display | `flex row`, `align-items: center` | `popover.rs:715-718` |
| gap | `10px` | `popover.rs:719` |
| padding | `8px` inline, `6px` block | `popover.rs:720-721` |
| border-radius | `8px` | `popover.rs:722` |
| font-size | `ui_rems(13.0)` | `popover.rs:722` |
| cursor | pointer | `popover.rs:723` |

**States**

| State | Condition | What changes |
|---|---|---|
| active / selected | `active === true` | `background: card_selected_bg()` (dark `wash(0.11)`, light `wash(0.06)`); `color: theme.text` — **no transition, applied instantly** (`popover.rs:725-727`) |
| rest | `active === false` | `color` transitions between `text.opacity(0.9)` and `text` on hover, `background` transitions between `wash(0.0)` (**not** transparent-black — a fully transparent black kills the glass and flashes dark mid-fade) and `card_selected_bg()`, both over `HOVER_FADE` (150ms `--rb-ease-ease-tailwind`) (`popover.rs:729-739`, `theme.rs:1496`) |
| keyboard-highlighted | `MenuRowNav(selected=false, highlighted=true)` | `background: card_selected_bg()`, `color: theme.text` — the **same** wash as selected; the two states never appear on the same row (selected wins) (`popover.rs:752-765`) |

Each row needs a unique, frame-stable `fadeKey` (its own id string is the
convention) so the hover transition ties to the right element.

Note: the doc comment in `popover.rs` describes a Radix-style two-tone
distinction (`white/10` selected vs `white/[0.08]` highlighted) that the
shipped code does not implement — both states use `card_selected_bg()`. This
ticket ports the **shipped** behavior (one wash), not the documented intent
(open question 3 in the research; do not "fix" this without checking with a
human first).

**Gap this closes** (rows 15, 37 — the row recipe itself; row 14's
model-row-specific ring is ticket 10's job): the web's `.menu-item` uses
`gap: var(--rb-space-sm)` (8, not 10), `padding: 6px 8px`, and no active/
highlighted wash distinction at all; `.picker-row` uses `padding: 6px 10px`.
Build one shared `MenuRow` at the desktop's 10/8/6/8 metrics; ticket 10
applies `MenuRowNav`'s selected+ring variant to the model list specifically.

### 2.6 `MenuHeading` — section head

`popover.rs:771–795`.

| Property | Value |
|---|---|
| padding | `8px` inline, `6px` top, `4px` bottom |
| font-size | `ui_rems(10.0)` |
| font-weight | `500` (medium) |
| color | `theme.text_muted.opacity(0.6)` |
| text-transform | uppercase |
| letter-spacing | `0.1em` |

The desktop uppercases and inserts a `U+200A` hair space between every
character pair (`tracked_upper`) because gpui has no letter-spacing at the
pinned rev. **The web must use real CSS `text-transform: uppercase;
letter-spacing: 0.1em;` and must NOT port the hair-space workaround** — it
would break copy/paste and screen readers (gap row 35).

Ticket 10 supplies the actual heading strings (`"Reasoning"`, each model
option's label, `"Organize"`, `"Sort"`, `"Show"`); this ticket only builds
the reusable `<MenuHeading>{children}</MenuHeading>` component.

### 2.7 `MenuSeparator` / `MenuSection`

| Element | Layout | Source |
|---|---|---|
| `MenuSeparator` | `height: 1px`, `margin-inline: -4px` (full-bleed — cancels the card's 4px inset so the hairline runs edge to edge), `margin-block: 4px`, `background: hairline(0.07)` | `popover.rs:799-803` |
| `MenuSection` | `margin-top: 4px`, `padding-top: 4px`, `border-top: 1px solid hairline(0.06)` (edge-to-edge of the card's inset, unlike `MenuSeparator`'s negative margin), `display: flex`, `flex-direction: column`, `gap: 2px` | `popover.rs:961-970` |

Ticket 10's project popover uses a **third**, local one-off divider
(`margin-block: 2px`, `margin-inline: -4px`, `height: 1px`, `flex: none`,
`background: theme.border.opacity(0.6)`) — build that inline in that
ticket, not as a shared primitive (`pickers.rs:2113-2120`).

**Gap this closes** (row 36): the web's `.menu-sep` uses
`margin: var(--rb-space-xs) 0` with no negative inline margin (so it does
not run full-bleed) and `background: var(--rb-border)` instead of
`hairline(0.07)`. `.chat-menu-sep` uses `margin: 3px 4px` — also wrong.

### 2.8 `SearchInputFrame`

`popover.rs:946–955`.

| Property | Value |
|---|---|
| margin-bottom | `4px` |
| padding | `10px` inline, `6px` block |
| border-radius | `8px` |
| background | `ink(0.04)` |
| border | **none** |
| font-size | `ui_rems(13.0)` |
| width | fills the card's 4px inset |

Used by the branch / project / device popovers and the sidebar space menu
(ticket 10). The harness/model card does **not** use it — it has its own
40px search row (ticket 10, §3.16.2 of the research).

**Gap this closes** (row 38): `.picker-popover-search` today is
`padding: 2px` wrapping a `30px`-tall input — wrong padding, wrong radius,
and it has a visible border via the shared `.input` class where the desktop
frame is borderless.

### 2.9 Key caps and kbd hints

| Element | Layout | Source |
|---|---|---|
| `KeyCap` | `height: 22px`, `padding-inline: 5px`, `border-radius: 5px`, flex row centered, `gap: 4px`, `background: ink(0.05)` | `popover.rs:841-852` |
| `KeyHintLabel` | `ui_rems(10.5)`, `text_muted.opacity(0.45)` | `popover.rs:855-860` |
| `KeyHint(icon, label)` | row, `gap: 5px`: `KeyCap` holding a `12.5px` icon at `text_muted.opacity(0.7)`, then the label | `popover.rs:864-878` |
| `KeyHintText(cap, label)` | same, cap holds a word at `11px` in the monospace font at `text_muted.opacity(0.7)` | `popover.rs:882-896` |
| `KeyHintPair(a, b, label)` | cap holds two `12.5px` glyphs split by a `1px × 11px` `hairline(0.10)` divider | `popover.rs:900-926` |
| `KbdHint(label)` | `flex: none`, `padding: 5px 5px` — wait, `px 5px, py 1px` — `border-radius: 5px`, `background: ink(0.05)`, `ui_rems(10.0)`, monospace, `text_muted.opacity(0.6)` | `popover.rs:929-940` |

Only `KbdHint` is consumed on this surface (ticket 10's model rows' `"⌘1"`
through `"⌘9"` jump chips, `pickers.rs:3626`); build all five since other
future tickets may need them, but ticket 10 is the only current consumer.

### 2.10 Loading / error states

| Element | Layout | Source |
|---|---|---|
| `SkeletonRows(count)` | flex column, `gap: 6px`, `padding-block: 4px`; each row `height: 28px`, `border-radius: 6px` (`CONTROL_RADIUS`), `background: ink(0.04)`, `opacity: 0.35 + 0.4 * pulseWave(phase)` where `phase = staggeredPhase(raw, i, 0.08)` | `popover.rs:1080-1103` |
| `SkeletonMenuRows(count)` | flex column, `gap: 8px`, `padding-block: 6px`, `padding-inline: 4px`; each row `height: 14px`, `border-radius: 7px`, `background: ink(0.05)`, width cycles the `WIDTHS` ladder below, same pulse + 0.08 stagger | `popover.rs:1124-1150` |
| `SkeletonBar(width)` | `width: <width>`, `height: 11px`, `border-radius: 5.5px`, `background: ink(0.08)`, same pulse at stagger 0 | `popover.rs:1108-1117` |
| `ErrorRow(message)` | flex column, `gap: 6px`, `padding: 8px` (`SPACE_SM`), `ui_rems(12.0)`, `color: theme.danger`, child = the message verbatim | `popover.rs:1154-1163` |

**The `WIDTHS` ladder** (`popover.rs:1131`) — a private, deterministic
4-entry table of **relative** ghost-bar widths (fractions of the row's
container width, i.e. CSS `%`), cycled `i % 4`:

| Row index `i` | `WIDTHS[i % 4]` | CSS width |
|---|---|---|
| 0, 4, 8, … | `0.42` | `42%` |
| 1, 5, 9, … | `0.58` | `58%` |
| 2, 6, 10, … | `0.48` | `48%` |
| 3, 7, 11, … | `0.66` | `66%` |

The ladder is short and deterministic on purpose: the stagger reads organic
without randomness, and randomness would repaint differently on every open
(`popover.rs:1119-1123`).

**Pulse math** (`proto/src/motion.rs:51-57`):
```
staggeredPhase(raw, i, stagger) = (raw - i*stagger).rem_euclid(1.0)
pulseWave(phase) = 0.5 - 0.5*cos(phase * 2π)   // 0 at phase 0, 1 at 0.5, back to 0 at 1
```
`raw` rides the shared `ROBOCO_PULSE` clock (2400ms period), phase-locked
across every mounted loader in the app — implement as one shared
`requestAnimationFrame` (or CSS animation with a synchronized start time) so
every skeleton in the app pulses in lockstep. Reduced motion returns a
static `0` (no pulse) — respect `prefers-reduced-motion: reduce`.

**Which loader each menu uses** — a reference table for the tickets that
wire these in (this ticket builds the components; it does not call them):

| Call site | Helper | `count` | Owning ticket |
|---|---|---|---|
| Harness/model card — catalog loading takeover | `SkeletonMenuRows` | 5 | 10 |
| Harness/model card — model list loading | `SkeletonMenuRows` | 5 | 10 |
| Harness/model card — traits tray loading | `SkeletonMenuRows` | 3 | 10 |
| Ref (branch) popover body | `SkeletonRows` | 4 | 10 |
| Run-identity chip label slot | `SkeletonBar(56.0)` | — | 10 |

`SkeletonRows` (full-width slabs) is also used outside this surface by the
composer, Settings → Accounts, Settings → Agents, and the add-space palette
— build it generically here so those tickets can import it too.

**Gap this closes** (row 40, primitive only — the wiring into each specific
menu is ticket 10's job): the web today shows `aria-busy` with no visual
loading state at all.

### 2.11 The floating menu scrollbar

`popover.rs:1170–1397`. An **on-demand** rail: hidden until the list is
hovered or a drag holds it — the browser's native scrollbar must be hidden
(`scrollbar-width: none` / `::-webkit-scrollbar { display: none }`) on any
list that mounts this component, since this rail replaces it visually.

**Constants**

| Name | Value |
|---|---|
| `MENU_SCROLLBAR_TRACK_INSET` | `4.0` px (top and bottom) |
| `MENU_SCROLLBAR_HIT_WIDTH` | `10.0` px (invisible hit strip on the right edge) |
| `MENU_SCROLLBAR_THUMB_WIDTH` | `3.0` px (resting) |
| `MENU_SCROLLBAR_HOVER_THUMB_WIDTH` | `5.0` px (hovered/dragged) |
| `MENU_SCROLLBAR_MIN_THUMB` | `24.0` px |

**Rail** (`render_rail`, `popover.rs:1366-1396`): `position: absolute`,
`top: 0`, `bottom: 0`, `right: 0`, `width: 10px`. The thumb is an
**absolutely-positioned child** inside that fixed-width strip, so hover
expansion never reflows rows: `top: TRACK_INSET + thumbTop`, `right: 2px`,
`width: thumbWidth`, `height: thumbHeight`, `border-radius: thumbWidth / 2`,
`background: text_faint.opacity(active ? 0.68 : 0.5)`.

**Visibility:** `visible = listHovered || dragging`;
`active = barHovered || dragging`.

**Geometry** (`MenuScrollbarMetrics::from_viewport`, `popover.rs:1198-1223`):
```
if viewport <= 0 or maxScroll <= 0           -> hidden (no thumb)
track   = max(viewport - 2*4, 0);  if 0      -> hidden
content = viewport + maxScroll
thumb   = clamp(track * viewport / content, MIN_THUMB=24, track)
scroll  = clamp(current, 0, maxScroll)
travel  = max(track - thumb, 0)
thumbTop = travel * scroll / maxScroll
```

**Interactions**
- Press on the **thumb** keeps its relative grab offset; press on the
  **track** centres the thumb under the pointer first (`grab = thumb / 2`),
  then scrolls (`begin_press`, `popover.rs:1306`).
- Drag maps `thumbTop = clamp(pointerInTrack - grab, 0, travel)` →
  `scroll = thumbTop / travel * maxScroll` (`drag_to`, `:1325`).
- `pointerInTrack(y) = y - scrollAreaBounds.top - 4`.
- Release drops the drag; the rail stays armed only while the list is still
  hovered (`end_press`, `:1346`).
- On the web, implement the drag with `pointer capture`
  (`setPointerCapture`) on the thumb/track element instead of the gpui
  captured-drag-stream + drag-ghost machinery (`MenuScrollbarDrag` /
  `MenuScrollbarDragGhost`, `popover.rs:1228-1237` — desktop-only, see §6).

A `HorizontalScrollbar` twin with identical math exists for code planes
(`popover.rs:1406-1546`) — build it alongside `MenuScrollbar` for API
symmetry even though nothing in scope (05) uses it; a later ticket
(markdown/code panes) will.

**Gap this closes** (row 18): the web today uses native
`overflow-y: auto` everywhere a list scrolls — no floating rail exists.

### 2.12 Dialog primitives

Used by ticket 10's rename/delete dialogs (space context menu, chat context
menu) and ticket 11's add-space palette. Build them here since they share
the card background/shadow recipe with `PopoverCard`.

| Element | Layout | Source |
|---|---|---|
| `Modal` | viewport-sized `occlude()`d div, `scrim(0.6)` backdrop, centres its child card, plays `DIALOG_IN` (180ms `--rb-ease-ease`), bakes a `16px` card radius to match `DialogCard` | `popover.rs:657-682` |
| `ModalGlass` | same, `scrim(0.35)`, caller-supplied corner radius | `popover.rs:684-705` |
| `DialogCard` | `width: 360px`, `padding: 20px`, `border-radius: 16px`, `background: theme.surface_dialog`, `border: 1px solid hairline(0.10)`, `shadow_lg`, flex column, `color: theme.text` | `popover.rs:978-990` |
| `DialogTitle` | `ui_rems(15.0)`, semibold, `theme.text` | `popover.rs:993` |
| `DialogBody` | `ui_rems(13.0)`, `line-height: 19px`, `theme.text_muted` | `popover.rs:1002` |
| `DialogField` | `width: 100%`, `padding: 8px 12px`, `border-radius: 8px`, `border: 1px solid hairline(0.08)`, `background: ink(0.04)`, `ui_rems(14.0)` | `popover.rs:1012` |
| `BtnGhost` | `padding: 6px 12px`, `border-radius: 8px`, `ui_rems(13.0)`; text fades `text_muted → text` on hover; background fades `wash(0.0) → ink(0.06)` on hover | `popover.rs:1028-1046` |
| `BtnPrimary` | same box, `background: theme.text`, `ui_rems(13.0)`, medium weight, `color: theme.on_solid`, `hover { opacity: 0.9 }` | `popover.rs:1049-1061` |
| `BtnDanger` | same box, `background: theme.danger_strong`, `color: white`, `hover { opacity: 0.9 }` | `popover.rs:1064-1076` |

`scrim(a)` — see §2.0's theme-helper table. `Modal`/`ModalGlass` are the
**only** things in this whole surface that dim the page; every menu card is
undimmed.

---

## 3. Pure logic to port

### 3.1 `menuStep(active: number | null, count: number, delta: -1 | 1): number | null`

`popover.rs:214-230`. Wrap-around list navigation.
- `count === 0` → `null` (empty menus stay out), regardless of `active`.
- `active === null` → enter at the edge matching the direction: `0` when
  `delta >= 0`, `count - 1` otherwise.
- otherwise `((active + delta) % count + count) % count` (Euclidean
  remainder — wraps both ways).

Tests (port `popover.rs:1587` verbatim as `menuStep`):
`menuStep(null, 0, 1) === null`; `menuStep(3, 0, 1) === null`;
`menuStep(null, 3, 1) === 0`; `menuStep(null, 3, -1) === 2`;
`menuStep(2, 3, 1) === 0`; `menuStep(0, 3, -1) === 2`;
`menuStep(1, 3, 1) === 2`.

### 3.2 `matchRank(query, label): number | null` and `filterIndices`

`popover.rs:235-260`.
- The query is **trimmed and lowercased**; the label is lowercased.
- Empty (or all-whitespace) query → rank `1` for everything, preserving
  input order.
- Label **starts with** the query → rank `0`.
- Label **contains** the query → rank `1` — **regardless of where** the
  match sits (this is the key divergence from the current web code).
- No match → `null`.
- `filterIndices` sorts by `(rank, inputIndex)` — stable within each rank —
  and returns indices into the input.

Tests (port `popover.rs:1601`, `:1615` verbatim): with
`["main", "feature/main-sync", "master", "dev"]`,
`filterIndices("ma")` = `[0, 2, 1]` (both prefix matches before the
substring match, in input order); `"MA"` gives the same result
(case-insensitive); `"zzz"` is empty; `""` and `"   "` both give
`[0, 1, 2, 3]`. `matchRank("re", "release") === 0`;
`matchRank("lease", "release") === 1`; `matchRank("x", "release") === null`;
`matchRank("", "anything") === 1`.

**Fix required** (gap rows 10, 11): `lib/picker-search.ts`'s current
`matchRank` returns `1 + haystack.indexOf(needle)` for substring hits (so it
orders by match *position*) and only special-cases `query.length === 0`
(not a trimmed/whitespace-only query). Rewrite `matchRank` to:
1. Trim the query before comparing.
2. Return a flat `1` (not `1 + at`) for any substring hit.
3. Treat an all-whitespace query the same as an empty one.

Keep the exported name `matchRank` and the `filterAndSort` helper's
existing signature (`items`, a `label` accessor, `query`) so ticket 10 does
not need to change every call site — only the ranking rule inside changes.
Rename `filterAndSort`'s sort tie-break to `(rank, inputIndex)` to match
`filterIndices` exactly (it already does this correctly per the current
source — verify and keep).

### 3.3 `classifyKey(key, cmd, ctrl): MenuKey`

`popover.rs:276-291`.

| Input | Result |
|---|---|
| `"ArrowUp"` / `"up"` | `Up` |
| `"ArrowDown"` / `"down"` | `Down` |
| `"n"` with ctrl | `Down` (readline/emacs motion) |
| `"p"` with ctrl | `Up` |
| `"Enter"` with cmd **or** ctrl | `ModEnter` |
| `"Enter"` | `Enter` |
| `"Escape"` | `Escape` |
| `"Backspace"` | `Backspace` |
| anything else | `Other` |

Ctrl+N/Ctrl+P are safe to claim frame-wide — neither chord is a
text-editing binding in the app's keymaps. Tests (port `popover.rs:1623`
verbatim): `classifyKey("n", false, false) === Other` and
`classifyKey("p", true, false) === Other` — the modifier must be **ctrl**,
not cmd/meta.

**Gap this closes** (row 30): no menu on the web currently mirrors Ctrl+N/
Ctrl+P as Down/Up. Build `classifyKey` here; ticket 10 wires it into every
picker's key handler.

### 3.4 `Popup` lifecycle state machine

Port as `lib/popup-lifecycle.ts`, a small state container (a class or a
`useReducer`-backed hook) exposing:
- `open(value)`, `dismiss()` (immediate close, no return-focus contract),
  `beginClose()` → `boolean` (true only the first call per open session),
  `finishClose()` (drops state only if the exit actually completed),
  `isOpen()`, `isClosing()`, `get()`, `asOpen()`.
- `noteTriggerPress()` / `noteTriggerPressMatching(predicate)` and
  `takePressWasOpen()` as described in §2.4.
- `exitProgress(now)` — `0` at the moment `beginClose()` was called, `1`
  after `MENU_OUT.total * speedScale` has elapsed; used to drive the CSS
  exit animation's start point if a popup is interrupted mid-exit and
  reopened (so a hasty re-open does not visually snap).
- A `reapPopup` scheduler: `setTimeout(finishClose, MENU_OUT.total *
  speedScale + 20)` (120ms at 1× speed), cancelled and rescheduled correctly
  across rapid open/close/open sequences.

Port the desktop test `trigger_press_note_distinguishes_dismiss_from_open`
(`popover.rs:1553`) as a unit test: both handler orders work; mid-exit
counts as "mounted" for the purposes of the note; the note is consumed
exactly once (a second read returns nothing / false).

---

## 4. Gaps this ticket closes

| # | Item | Kind | Desktop value | Web value (file:line) | Fix |
|---|---|---|---|---|---|
| 10 | `matchRank` substring ordering | WRONG BEHAVIOR | all substring hits share rank `1`; ties break by input order (`popover.rs:235-259`) | returns `1 + indexOf` → orders by match position (`lib/picker-search.ts:27`) | Return `1` for any substring hit |
| 11 | `matchRank` whitespace query | WRONG BEHAVIOR | query is trimmed; `"   "` matches everything in input order (`popover.rs:236`, test `:1611`) | `query.length === 0` only (`lib/picker-search.ts:15`) | Trim first |
| 18 | Floating menu scrollbar | MISSING | on-demand rail: 10px hit strip, 3px/5px thumb, min 24px, `text_faint` at 0.5/0.68, track inset 4, track-click centres the thumb (`popover.rs:1170-1397`) | native `overflow-y: auto` | Build `MenuScrollbar` |
| 19 | Card background / blur | WRONG VALUE | `glass_overlay()` (contrast-checked `surface_overlay` at base 0.50 dark / 0.85 light) over a 44px backdrop blur, `border 1px hairline(0.10)`, `radius 12`, `padding 4` (`popover.rs:306-325`, `frost.rs:23`) | `.picker-popover { padding: 6px; background: var(--rb-dialog) }` (`app.css:1903`) | `PopoverCard` at the desktop values |
| 20 | Card shadow | WRONG VALUE | `shadow_lg()` | `0 8px 30px rgb(0 0 0 / 0.35)` (`app.css:1914`); space menu uses `0 12px 32px rgb(0 0 0 / 0.36)` (`app.css:897`) | One shadow token for all floating cards (flag the concrete `shadow_lg` values as unresolved — see Comments) |
| 22 | Card max-height (frame only) | WRONG VALUE | frame `max-height: 640px` | `.picker-popover { max-height: 320px }` (`app.css:1910`) | `PopoverCard`'s own max-height is 640px; per-region list caps (216/224/236) are ticket 10's job |
| 23 | Placement mechanism | WRONG | `menu_at` clamps into the window at 8px; it does **not** flip (`popover.rs:621-638`) | `chat-menu.tsx:103` flips above on overflow | `menuAt` in `lib/popover-anchor.ts` is clamp-only |
| 24 | Open motion | WRONG VALUE | `MENU_IN` 140ms `EASE`, `opacity 0.3 → 1`, `translateY -2px → 0` (+ scale `0.96 → 1`) | `@keyframes picker-in`: `opacity 0→1`, `translateY(4px) → 0` with `--rb-ease-ease-out` (`app.css:2034`, `:1916`) | 140ms, `--rb-ease-ease`, start opacity 0.3, `translateY(-2px) scale(0.96)` |
| 25 | Close motion | MISSING | `MENU_OUT` 100ms `EASE`, `opacity → 0`, `translateY 0 → -2px`, occluding overlay on top, unmount ~120ms later | unmounts instantly | Build the open/closing/closed lifecycle |
| 26 | `noteTriggerPress` | MISSING | a press that found the popup mounted leaves it closed — a plain toggle would close-and-reopen (`popover.rs:152`) | naive `setOpen(cur => cur === null ? x : null)` + outside-pointerdown closer firing on the same press | Record "was open at pointerdown" per trigger |
| 27 | Outside-press bubble guard | MISSING | the dismissing press is consumed during bubble so content behind can't act on it; the FOLLOWING click passes through (`popover.rs:375-384`) | — | Swallow that one `pointerdown`, not the next `click` |
| 28 | Click-outside dismissal | WRONG BEHAVIOR (comment is wrong too) | outside mouse-down **dismisses** every picker (`pickers.rs:2751`) | `picker-popover.tsx:11-12` claims click-outside does NOT close; `onMouseDown` is stopped | Close on outside press; delete the stale comment |
| 29 | `Tab` closes the popover | INVENTED | no Tab handling anywhere in `popover.rs`/`pickers.rs` | `picker-popover.tsx:106-110` | Remove — do not carry into the new primitive |
| 30 | Ctrl+N / Ctrl+P navigation | MISSING | mirror ↓/↑ in every picker (`popover.rs:283-284`) | — | Build `classifyKey` |
| 33 | Enter while search box focused | MATCHES (behaviorally) | input's submit event routes to the picker's submit handler | `onKeyDown` on the input | Keep the pattern in the new primitive |
| 34 | Escape returns focus | MISSING | `animate_close` emits a return-focus signal; outside clicks deliberately do not (`pickers.rs:871-876`) | `setOpen(null)` only | Expose an `onClosedByEscape` hook distinct from `onDismissed` |
| 35 | `menu_heading` | MISSING | uppercase, `ui_rems(10)`, medium, `text_muted@0.6`, `px 8 pt 6 pb 4`, 0.1em tracking (`popover.rs:771`) | — | Build with real `letter-spacing` |
| 36 | `menu_separator` metrics | WRONG VALUE | `h 1px`, `mx -4px` (full-bleed), `my 4px`, `hairline(0.07)` (`popover.rs:799`) | `.menu-sep { margin: 4px 0; background: var(--rb-border) }` | Match, including the negative inline margin |
| 37 | `menu_row` metrics | WRONG VALUE | `gap 10`, `px 8`, `py 6`, `radius 8`, `ui_rems(13)`; rest text `text@0.9` | `.menu-item`: `gap: var(--rb-space-sm)` (8), `padding: 6px 8px` | One shared row recipe at the desktop values |
| 38 | Search frame | WRONG VALUE | `mb 4`, `px 10`, `py 6`, `radius 8`, `background ink(0.04)`, borderless | `.picker-popover-search { padding: 2px }` wrapping a bordered `.input` | Match |
| 40 | Skeleton loading (primitive) | MISSING | `skeleton_rows` / `skeleton_menu_rows` / `skeleton_bar`, all on the shared 2.4s pulse with 0.08 stagger (`popover.rs:1080-1150`) | `aria-busy` only | Build the components; ticket 10 wires them in |
| 44 | Retry affordance (component) | WRONG | inline, inside `error_row`, below the message: `px 8 py 3`, `radius 6`, `border 1px theme.border`, hover `element_hover`, text `"Retry"` (`pickers.rs:2804-2838`) | `.composer-pickers-retry { position: absolute; bottom: -28px }` outside the popover | Build `ErrorRow` + retry button as one component; ticket 10 positions it inside the card |
| 86 | `--rb-radius-*` for popovers | WRONG VALUE | floating cards are `12px` (`CARD_RADIUS`), not `PANEL_RADIUS (10)`; rows are `8px` (`menu_row`) or `6px` | `.panel` uses `--rb-radius-panel`; rows use `--rb-radius-control` | Add `--rb-radius-card: 12px`; rows use their own literal 8px/6px per §2.5 |
| 87 | Focus trap | MATCHES (absent) | none — Tab is not intercepted | — | Do not add one |
| 88 | Hover-open delays | MATCHES (absent) | none except a 350ms tooltip owned by ticket 10 | — | — |

---

## 5. Do not

- Do not build any picker-specific content: the harness/model card, the
  branch/checkout/project/device popovers, the trigger chip, the spaces
  menu, the sidebar view-options menu, the space/chat context menus. All of
  that is ticket 10 (and 11 for the add-space palette), built **on top of**
  this ticket's primitives.
- Do not port `contrast_checked_tint_alpha`'s 21-step runtime solve
  (`theme.rs:864`) — ticket 02 precomputes `--rb-glass-overlay-alpha`.
- Do not port the blur-radius-ride-to-zero exit trick
  (`frosted_menu`, `popover.rs:369`) — it exists only because gpui's
  `BackdropBlur` ignores element opacity; CSS `backdrop-filter` fades with
  the element naturally, so just fade opacity.
- Do not gate `backdrop-filter` on a platform check the way
  `Theme::is_frost()` does (`theme.rs:900`, desktop-only: macOS/Linux/
  Windows native builds only). The web always has `backdrop-filter`; if
  anything gate on `prefers-reduced-transparency`, not platform.
- Do not add a focus trap (Tab is never intercepted anywhere in this
  primitive — gap row 87).
- Do not add hover-open delays anywhere in this ticket (gap row 88); the one
  exception (a 350ms tooltip on the sidebar sort button) belongs to ticket
  10, not here.
- Do not build a nested-submenu / second-floating-layer primitive — no such
  thing exists on the desktop; ticket 10's chat-menu Copy page swaps the
  card's content in place.
- Do not add a scrim to any menu built on this primitive — only `Modal`/
  `ModalGlass` dim the page.
- Delete `Tab` closing a popover (gap row 29) and do not reintroduce it in
  the new primitive.
- Delete `onWheel`-closes-the-menu wherever you find it (currently
  `chat-menu.tsx:121`, gap row 72) is **not** owned by this file directly
  (`chat-menu.tsx` is ticket 10's file) — but the popup-lifecycle hook built
  here must not expose or encourage a wheel-close affordance; when ticket 10
  rebuilds the chat menu on this primitive, `onWheel` must not survive.
- Do not port `MenuScrollbarDrag`/`MenuScrollbarDragGhost` (gpui's typed
  drag-stream + invisible drag preview, `popover.rs:1228-1237`) — use
  `pointer capture` on the web instead.
- Do not port `UniformListScrollHandle` internals — that is gpui's
  virtualization plumbing; a later ticket (10) picks a web virtualization
  approach for the 7k-row model list, not this one.
- Do not implement `ROBOCO_OPEN_PICKER` / `ROBOCO_SLOW_CATALOG_MS` /
  `ROBOCO_HARNESS=mock` dev-only env knobs — desktop-only test rig.

---

## 6. Acceptance

- [ ] `PopoverCard`/`PopoverCardFlush` render at `radius 12`, `padding 4`,
      `border 1px hairline(0.10)`, `background var(--rb-overlay)` at
      `--rb-glass-overlay-alpha` with `backdrop-filter: blur(44px)`.
- [ ] A popup opens with `opacity 0.3→1`, `translateY(-2px)→0`,
      `scale(0.96)→1` over 140ms `--rb-ease-ease`, and closes with
      `opacity 1→0`, `translateY(0)→-2px` over 100ms, unmounting ~120ms
      after `beginClose()`, honoring `prefers-reduced-motion` by snapping.
- [ ] Outside pointerdown dismisses an open popup; the causing pointerdown
      is consumed, the following click is not; Tab does not close it; wheel
      does not close it.
- [ ] Clicking a trigger whose popup was open at press time leaves it
      closed (no close-then-reopen flicker) — verified via
      `noteTriggerPress`.
- [ ] `MenuRow` shows the selected wash instantly (no transition) and the
      hover wash faded over 150ms; `MenuRowNav`'s highlighted state reads
      identically to selected.
- [ ] `MenuHeading` renders uppercase with `letter-spacing: 0.1em` via CSS,
      no hair-space characters in the DOM text.
- [ ] `MenuSeparator` runs full-bleed (negative inline margin cancels the
      card's inset); `MenuSection`'s top border does not.
- [ ] `SkeletonRows`/`SkeletonMenuRows`/`SkeletonBar` pulse in lockstep on
      the shared 2400ms clock with the documented widths ladder, and freeze
      under reduced motion.
- [ ] `MenuScrollbar` stays hidden until the list is hovered or dragged,
      expands its thumb 3px→5px on hover/drag, and dragging the track
      recentres the thumb under the pointer before scrolling.
- [ ] Unit tests: `menu_step` (`popover.rs:1587`) → `menuStep` in
      `tests/picker-search.test.ts`; `filter_indices`/`match_rank`
      (`popover.rs:1601`, `:1615`) → `filterIndices`/`matchRank`;
      `classify_key` (`popover.rs:1623`) → `classifyKey`;
      `trigger_press_note_distinguishes_dismiss_from_open`
      (`popover.rs:1553`) → `tests/popup-lifecycle.test.ts`.
- [ ] Screenshot pair, desktop vs web: a generic menu open (e.g. the
      rebuilt-later spaces menu stubbed with placeholder rows is acceptable
      if no ticket-10 consumer exists yet — otherwise use the harness
      picker popover mid-transition once ticket 10 lands), states: open,
      mid-close (paused at 50ms into the exit), row hovered, row
      keyboard-highlighted, skeleton loading.
- [ ] `pnpm -r build` green; package vitest green.
- [ ] No new literal hex/px where a `--rb-*` token exists.

## Comments

(empty; appended during implementation)

- Open question carried from research §7.4: the concrete offset/blur/
  spread/colour of gpui's `shadow_lg()`/`shadow_md()` are not in this repo.
  This ticket picks one CSS shadow value as a placeholder for every floating
  card (`box-shadow: 0 8px 30px rgb(0 0 0 / 0.35)`, the web's current
  popover value) and flags it here rather than guessing the gpui fork's
  numbers. Replace when the real values are available.
