# 06 — Titlebar and main-column chrome

**What to build:** The window's top bar and the conversation column stop being
approximations. After this ticket the titlebar's left cluster, its Back/Forward
history buttons (with a real disabled look), its conditional `+`, and its
identity group all sit at the desktop's exact pixels and fonts; the one trailing
pane toggle is a 28 px control with the desktop's wash. The conversation column
gains the permanently-reserved 24 px status strip (so the composer never jumps),
the real "Scroll to bottom" pill, an update strip in the sidebar, an engine-failure
gate screen with the grid backdrop, a page-entrance fade, and a single ordered
Escape ladder instead of scattered per-component `keydown` listeners. Column
widths glide on the desktop's 200 ms resize curve in both directions.

**Blocked by:** 02 (Foundation tokens), 03 (Client settings store), 05 (State
fixes: nav history, send ids, optimistic echo).

**Status:** done

**Research:** `../../web-client/research/01-shell-chrome.md` §3.1, §3.3, §3.3.1,
§3.4, §3.5, §3.6, §3.6.1, §3.6.2, §3.7, §3.12, §3.16, §3.17, §3.18, §3.25,
§3.26, §3.29, §4.1, §4.2, §4.3, §4.7, §4.8, §4.10, §4.15, §5.2 rows T1–T18,
§5.3 rows S46, §5.4 rows P21–P23, §5.6 rows N5, N6, N8, N9, N10, N17, N25.

**Desktop reference (for lookups only):**
`crates/ui/src/shell.rs::render_title_bar` (3895), `::titlebar_drag_region`
(3917), `::render_titlebar_cluster` (3973), `::titlebar_spacer` (3861),
`::window_control_button` (7369), `::nav_history_button` (7523),
`::header_icon_button` (7552), `::titlebar_new_session_alpha` (193),
`::render_main` (5806), `::render_status_strip` (6373),
`::render_jump_to_bottom` (6161), `::jump_pill` (6192),
`::render_update_strip` (5035), `::render_gate_card` (7223),
`::grid_backdrop` (7281), `::resolve_shell_escape` (942),
`::capture_escape_surface` (5325), `::conversation_width` (450);
`crates/ui/src/shell/tabs.rs::render_session_title_bar` (138).

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/components/titlebar.tsx` | edit | `Titlebar`, `TitlebarProps`, `WindowControl`; **new** `NavHistoryButton`, `HeaderIconButton` |
| `web/packages/app/src/components/app-shell.tsx` | edit | `AppShell` (the `--rb-*` inline custom properties, `shellClass`, the nav wiring, the keydown effects, `Welcome`), `useTakeoverStableWidth`, `chatIdOf`, `isEditableTarget` |
| `web/packages/app/src/state/layout.ts` | edit | `TITLEBAR_CONTENT_START`, `titlebarRowLeft`, `titlebarPaneBandWidth`, `TITLEBAR_EDGE_INSET`, `conversationWidth`, `sidebarTarget` |
| `web/packages/app/src/state/chrome.ts` | edit | `Chrome`, `chromeStore`, `useChrome`, `useTitlebar` |
| `web/packages/app/src/state/escape.ts` | **new** | `escapeStack`, `registerEscapeSurface`, `resolveShellEscape` |
| `web/packages/app/src/components/update-strip.tsx` | **new** | `UpdateStrip` |
| `web/packages/app/src/components/gate-card.tsx` | **new** | `GateCard`, `GridBackdrop` |
| `web/packages/app/src/components/sidebar-body.tsx` | edit | mounting `<UpdateStrip/>` between `<SidebarNotice/>`/`<ConnectionPill/>` and `<AccountRow/>` |
| `web/packages/app/src/routes/chat-page.tsx` | edit | `ChatIdentity`, the `useTitlebar` call |
| `web/packages/app/src/components/transcript.tsx` | edit | the `.status-strip` element, the `.jump-bottom` element, `.transcript-fade` |
| `web/packages/app/src/routes/root-layout.tsx` | edit | `RootLayout` — hosts the gate/page-fade wrapper |
| `web/packages/app/src/styles/app.css` | edit | `.shell`, `.titlebar`, `.titlebar-cluster`, `.titlebar-group`, `.titlebar-nav`, `.window-control`, `.window-control-active` (**delete**), `.titlebar-identity`, `.identity-brand`, `.identity-title`, `.identity-folder`, `.identity-badge` (**delete**), `.titlebar-trailing`, `.main`, `.main-inner`, `.shell-pane-gliding`, `.shell-pane-takeover`, `.status-strip`, `.jump-bottom`, `.transcript-fade`; **new** `.header-icon-button`, `.update-strip`, `.gate-card`, `.grid-backdrop`, `.page-fade`, `.attachment-drop-overlay` |
| `web/packages/app/tests/layout.test.ts` | edit | titlebar geometry + conversation-width cases |
| `web/packages/app/tests/titlebar.test.ts` | **new** | `titlebarNewSessionAlpha` cases |
| `web/packages/app/tests/escape.test.ts` | **new** | `resolveShellEscape` cases |

---

## 1. Context a fresh session needs

- The shell is one flex row spanning the **full window height** —
  `[sidebar][conversation][right pane]` — with the titlebar as an **absolute
  overlay on top of that row**, not a band above it. Each column pads itself
  down by `--rb-titlebar-height` (38 px). This is already how
  `app-shell.tsx` + `.shell` / `.titlebar` in `app.css` are built; do not
  restructure it.
- The titlebar has three parts: the **cluster** (absolutely positioned overlay
  at the row's left end — `.titlebar-cluster`), the **identity group** (published
  by the route through `state/chrome.ts::useTitlebar`, rendered into
  `.titlebar-identity`), and the **trailing group** (`.titlebar-trailing`: the
  animated pane band plus the one fixed pane toggle). The trailing section is
  **exactly one persistent control** — the right-pane toggle. Panel surfaces are
  tabs inside the pane, never buttons in the bar.
- Column widths flow as inline CSS custom properties set by `AppShell`:
  `--rb-sidebar-now`, `--rb-sidebar-content`, `--rb-pane-now`, `--rb-pane-open`,
  `--rb-pane-band`, `--rb-titlebar-row-left`, `--rb-main-stable`. CSS transitions
  on `width` / `padding-left` / `left` / `right` supply the desktop's `WidthTween`.
  While a seam is dragged, `:root[data-rb-resizing]` kills those transitions —
  that is the web's equivalent of the desktop clearing `sidebar_tween` /
  `right_tween`.
- Colors are `--rb-*` tokens only. Neutral washes are `rgb(var(--rb-wash) / a)`,
  `rgb(var(--rb-ink) / a)`, `rgb(var(--rb-hairline) / a)`. Motion is
  `var(--rb-motion-<spec>)` + `var(--rb-ease-<curve>)`. Ticket 02 adds any token
  named here that does not exist yet (`--rb-accent-wash`, `--rb-danger-strong`,
  `--rb-surface-raised-hover`, `--rb-glass-overlay`, `--rb-scrim`).
- `NavHistory` (push / replace / truncate / can_back / can_forward) is **ticket
  05's** deliverable. This ticket consumes it: `canBack` / `canForward` come from
  it, replacing `app-shell.tsx:238-239`'s `router.history.canGoBack()` and the
  hard-coded `canForward`.
- `state/chrome.ts` is the route→titlebar channel. It deliberately carries
  **only** `identity` and `onNewSession`; every right-pane control is shell-owned
  and synchronous with the store. Keep it that way — routing the pane through the
  chrome store put it behind an effect and tore the column down mid-glide.
- Vocabulary (`CONTEXT.md`): **chat**, not session/thread; **harness**, not
  provider; **engine**; **space**; *Session* only for the pairing credential.
  Rust identifiers and user-visible desktop strings stay verbatim, which is why
  the desktop's `"New session"` / `"Rename session"` strings appear unchanged
  below — they are copy, not vocabulary.
- Reduced motion: every tween below snaps under
  `@media (prefers-reduced-motion: reduce)` (duration 0), matching the desktop's
  `motion::reduced_motion` early-return in `eval_tween`.

### Token table used throughout

| Token | Value | Source |
| --- | --- | --- |
| `Theme::SPACE_XS` | 4 | `proto/layout.rs:16` |
| `Theme::SPACE_SM` | 8 | `proto/layout.rs:17` |
| `Theme::SPACE_MD` | 12 | `proto/layout.rs:18` |
| `Theme::SPACE_LG` | 16 | `proto/layout.rs:19` |
| `Theme::PANEL_RADIUS` | 10 | `proto/layout.rs:32` |
| `Theme::CONTROL_RADIUS` | 6 | `proto/layout.rs:34` |
| `Theme::TITLEBAR_HEIGHT` | **38** | `proto/layout.rs:44` |
| `Theme::TITLEBAR_TOP_PAD` | **4** | `proto/layout.rs:47` |
| `Theme::STATUS_STRIP_HEIGHT` | **24** | `proto/layout.rs:51` |
| `Theme::TRANSCRIPT_FADE_BAND` | 24 | `proto/layout.rs:56` |
| `SIDEBAR_MIN` / `_MAX` / `_DEFAULT` | 224 / 400 / 256 | `settings.rs:30-32` |
| `RIGHT_PANE_MIN` / `_DEFAULT` | 360 / 520 | `settings.rs:37-38` |
| `CHAT_PANEL_MIN` | 300 | `settings.rs:40` |

### Motion catalog used throughout

| Name | Duration | Curve | Source |
| --- | --- | --- | --- |
| `FADE_IN` | 500 ms | `EASE_OUT_EXPO` = cubic-bezier(0.16, 1, 0.3, 1) | `proto/motion.rs:289, 215` |
| `FADE_QUICK` | 150 ms | `EASE` = cubic-bezier(0.25, 0.1, 0.25, 1) | `proto/motion.rs:291, 219` |
| `DIALOG_IN` | 180 ms | `EASE` | `proto/motion.rs:298` |
| `RESIZE` | **200 ms** | `EASE_OUT` = cubic-bezier(0, 0, 0.58, 1) | `proto/motion.rs:302, 217` |
| `HOVER_FADE` | 150 ms | `EASE_TAILWIND` = cubic-bezier(0.4, 0, 0.2, 1) | `proto/motion.rs:321, 229` |

Element helpers (`motion.rs`):

| Helper | Effect | Source |
| --- | --- | --- |
| `fade_in(id, el)` | `opacity 0→1`, `top: 4px→0` over `FADE_IN` | `motion.rs:330-337` |
| `fade_quick(id, el)` | `opacity 0→1` over `FADE_QUICK` | `motion.rs:353-358` |
| `dialog_in(id, el)` | `opacity 0→1`, `top: 2px→0` over `DIALOG_IN` | `motion.rs:390-397` |
| `hover_blend(key, rest, hover)` | a manual 150 ms `HOVER_FADE` colour lerp — on web just a CSS transition on the same property | `motion.rs:434-455` |

---

## 2. Spec

### 2.1 `#shell-root` — the window root

`shell.rs:7747-7839`

**Layout**

| Property | Value | Source |
| --- | --- | --- |
| id | `"shell-root"` | 7748 |
| position | `relative` | 7751 |
| display | `flex`, `flex-direction: row` | 7752-7753 |
| size | `100% × 100%` | 7754 |
| background | `theme.glass()` | 7755, 7627 |
| color | `theme.text` | 7756 |
| font-family | `theme.font_sans` | 7757 |
| font-size | `ui_rems(14.0)` — 14 px scaled off the 16 px root | 7758 |

**Children (in order)** — a zero-size focus sink, `sidebar_tone`,
`fade_in("phase-app", page)`, then splash / gate drag strip / caption controls.
On web: `.shell` keeps its current three columns; wrap the ready page
(everything but the gate) in a `.page-fade` element carrying the `fade_in`
entrance (§2.10, gap N5).

**Per-frame bookkeeping** — the desktop stamps one `render_time` per frame
(7590) so every tween in the frame reads the SAME clock, and stamps
`viewport_width` / `viewport_height` from `window.viewport_size()` (7616,
7877-7881). On web: `useViewportWidth()` already subscribes to `resize`; keep one
source of truth and do **not** publish a second effect-derived copy of a column
width (that lag is what made the pane strip lurch — see `titlebar.tsx:100-113`).

---

### 2.2 `render_titlebar_cluster` — the top-left control cluster

`shell.rs:3973-4088`. A **paint-only overlay** pinned at the window's top-left,
above the sidebar and both headers. The sidebar animates *beneath* it, so the
buttons never move or remount on collapse. The container itself has **no id and
no listeners** — everything between the buttons falls through to the drag strip
below.

**Layout**

| Property | Value | Source |
| --- | --- | --- |
| position | `absolute; top:0; left:0` | 4008-4010 |
| height | `Theme::TITLEBAR_HEIGHT` (38) | 4011 |
| display | `flex row; align-items:center` | 4012-4014 |
| padding-top | `Theme::TITLEBAR_TOP_PAD` (4) | 4015 |
| padding-x | `TITLEBAR_CLUSTER_PAD = 10` | 4016, 249 |

**Children (in order)**

1. Frosted island (desktop-only in practice — see §5).
2. `titlebar_spacer(TITLEBAR_CLUSTER_PAD)` — macOS only (4037). On web
   `titlebar_spacer_width` ⇒ **0**: render **no element at all** (the desktop
   returns `None` off macOS precisely so there is no phantom flex child,
   3861-3871).
3. Linux left-caption reservation — **0 on web**.
4. `window_control_button("toggle-sidebar", icons::SIDEBAR_MINIMALISTIC_LEFT)`
   → `toggle_sidebar` (4047-4052). Asset `sidebar-minimalistic-left.svg` — a
   mirrored variant, the flip baked into the asset (`icons.rs:86-89`).
5. **Nav group** — `margin-left TITLEBAR_GROUP_GAP (8); flex row;
   align-items:center; gap TITLEBAR_CONTROL_GAP (2)` (4053-4074):
   * `nav_history_button("nav-back", icons::ARROW_LEFT, can_back)` → `navigate_back`
   * `nav_history_button("nav-forward", icons::ARROW_RIGHT, can_forward)` → `navigate_forward`
6. **New-chat `+`** — rendered when `plus_alpha > 0.01`; wrapper is `flex-none;
   margin-left TITLEBAR_GROUP_GAP (8); opacity plus_alpha` containing
   `window_control_button("titlebar-new-session", icons::PLUS)` →
   `open_new_session` (4075-4086).

**Geometry constants** (`shell.rs:230-249`, asserted at 8447-8459)

| Constant | Value | Meaning |
| --- | --- | --- |
| `TITLEBAR_CONTROL_GAP` | **2** | within-group rhythm (Back/Forward) |
| `TITLEBAR_GROUP_GAP` | **8** (= `SPACE_SM`) | between titlebar groups |
| `TITLEBAR_IDENTITY_GAP` | **12** (= `SPACE_MD`) | nav cluster → chat identity |
| `TITLEBAR_ACTION_EDGE_INSET` | **6** | trailing-edge inset |
| `CLUSTER_BUTTONS_WIDTH` | **82** = 24·3 + 8 + 2 | the persistent cluster's own width |
| `TITLEBAR_ACTION_SLOT_WIDTH` | **32** = 8 + 24 | extra width when the `+` joins |
| `TITLEBAR_CLUSTER_PAD` | **10** | the cluster row's own horizontal inset |

**Per-platform geometry** (§3.6.2) and what it collapses to on web:

| Function | Rule | Source | Web value |
| --- | --- | --- | --- |
| `titlebar_cluster_start(fullscreen)` | `fullscreen ? 12 : 88` | 215-217 | n/a |
| `titlebar_spacer_width(is_macos, fullscreen, container_pad)` | `0` off macOS; else `max(cluster_start − container_pad, 0)` | 222-227 | **0** |
| `caption_buttons_width(count)` | `0` if count == 0; else `count·24 + (count−1)·2` | 253-258 | **0** |
| `cluster_buttons_start(is_macos, fullscreen, linux_left)` | macOS → `titlebar_cluster_start`; Linux with left captions → `10 + caption_buttons_width(n) + 2`; else `10` | 264-272 | **10** |
| `cluster_clearance(...)` | `max(cluster_buttons_start + 82 + 8 − container_pad, 0)` | 276-285 | 90 |
| `titlebar_right_padding(is_windows, linux_right, base)` | `base + (windows ? 108 : linux_right > 0 ? 10 + caption_buttons_width(n) : 0)` | 7420-7428 | **= base (6)** |

⇒ On web `title_bar_content_start = 10 + 82 + 12 = **104**`. This is exactly
`state/layout.ts::TITLEBAR_CONTENT_START` today; do not change it.

**`titlebar_new_session_alpha`** (`shell.rs:193-199, 4092-4097`):

```
titlebar_new_session_alpha(is_chat_route, has_selected_chat)
    = (is_chat_route && has_selected_chat) ? 1.0 : 0.0
```

i.e. the `+` shows **only while an existing chat is selected on the chat route** —
never on the blank canvas, never in Settings. Test:
`new_session_action_lives_in_the_titlebar_only_when_useful` (`shell.rs:8408`).

**Motion**

| What | Trigger | Spec | From → to | Reduced motion |
| --- | --- | --- | --- | --- |
| `+` opacity | `titlebar_new_session_alpha` flips | `RESIZE` 200 ms `EASE_OUT` | `opacity 0 ↔ 1` | snap |
| `--rb-titlebar-row-left` | the `+` alpha and the sidebar width | `RESIZE` 200 ms `EASE_OUT` | old → new px | snap |
| control hover background | pointer enter/leave | `HOVER_FADE` 150 ms `EASE_TAILWIND` | rest → hover | snap |

The `+` must stay **mounted** and animate `opacity`; today
`titlebar.tsx:80-89` mounts/unmounts it outright (gap T3).

**Data** — `onToggleSidebar` (existing, `app-shell.tsx:108-114`); `onBack` /
`onForward` / `canBack` / `canForward` from ticket 05's `NavHistory`;
`onNewSession` gated on `route === chat && selectedChat !== null`.

---

### 2.3 Titlebar button primitives

#### `window_control_button` — `shell.rs:7369-7412` (the **left cluster only**)

| Property | Value |
| --- | --- |
| size | `24 × 24`, `flex-none`, centered |
| radius | `6` |
| cursor | `pointer` |
| background | `hover_blend(key, glass_hover().opacity(0), glass_hover())` — 150 ms fade. `glass_hover()` = `theme.element_hover` = `var(--rb-hover)` |
| hit-testing | `.occlude()` — the window hit-test STOPS here |
| mouse-down | `window.prevent_default()` |
| click | `cx.stop_propagation()` then the handler |
| icon | **`size 16`**, colour `theme.text_muted` |

#### `nav_history_button` — `shell.rs:7523-7548`

Enabled → exactly `window_control_button`. **Disabled** → a non-interactive
`24 × 24` centred box, still occluding, with **no background** and the icon at
`size 16`, colour `theme.text_muted.opacity(0.35)` (7530-7545).

> Web: `pointer-events: none` on the button; dim the **icon only**
> (`color: color-mix(in srgb, var(--rb-text-muted) 35%, transparent)`), not the
> whole button. Today `.window-control:disabled { opacity: 0.35 }` dims the
> background too (gap T9).

#### `header_icon_button` — `shell.rs:7552-7586` (the **two trailing controls**)

| Property | Value |
| --- | --- |
| size | **28 × 28**, `flex-none`, centered |
| radius | `6` |
| background | `hover_blend(key, wash(0.0), wash(0.11))` ⇒ `rgb(var(--rb-wash) / 0.11)` on hover, transparent at rest |
| occlusion / mouse-down / click | same as `window_control_button` |
| icon | `size 16`, colour `theme.text_muted` |

Add a `.header-icon-button` class for these two; keep `.window-control` at
24 × 24 for the left cluster (gaps T5, T6).

**The pane toggle has no active/pressed state.** Delete `.window-control-active`
and the `active={paneOpen}` prop passed at `titlebar.tsx:130` (gap T8).

---

### 2.4 `render_session_title_bar` — the chat-route title row

`shell/tabs.rs:138-362`

**Layout — outer bar**

| Property | Value | Source |
| --- | --- | --- |
| height | `Theme::TITLEBAR_HEIGHT` (38) | tabs.rs:359 |
| flex | `flex-none` | 359 |
| background | **none** (glass overlay; the sidebar tone and content show through) | 356-358 |
| border-bottom | **none** | 357-358 |
| id / behaviour | `titlebar_drag_region("chat-titlebar", …)` — §2.5 | 360 |

**Layout — inner content row**

| Property | Value | Source |
| --- | --- | --- |
| size | `100% × 100%` | 299-300 |
| display | `flex; align-items:center` | 301-302 |
| padding-top | `Theme::TITLEBAR_TOP_PAD` (4) — shifts content 2 px below the raw centre so the flex centre is 21 px | 303 |
| gap | `8` | 304 |
| padding-left | `row_left` (below) | 305 |
| padding-right | `titlebar_right_pad(TITLEBAR_ACTION_EDGE_INSET = 6)` | 306 |

**`row_left`** (tabs.rs:185-221) — already ported verbatim in
`state/layout.ts::titlebarRowLeft`; keep it, and add the comment that the `− 14`
cancels `TITLEBAR_IDENTITY_GAP(12)` minus the strip's own left pad
(tabs.rs:216-218):

```
content_left = max(sidebar_now + Theme::SPACE_LG(16),
                   title_bar_content_start() + plus_inset)

plus_inset   = TITLEBAR_ACTION_SLOT_WIDTH(32) * titlebar_plus_alpha   // 0 or 32

title_bar_content_start()                                   // shell.rs:3882-3890
  = tween(cluster_buttons_start(is_macos, fullscreen, linux_left_captions))
    + CLUSTER_BUTTONS_WIDTH(82)
    + TITLEBAR_IDENTITY_GAP(12)

// takeover mode only:
cluster_end = title_bar_content_start() - TITLEBAR_IDENTITY_GAP(12) + plus_inset - 14
row_left    = takeover ? max(sidebar_now - 8, cluster_end) : content_left
```

**`animated_width`** — the trailing reveal band (tabs.rs:243-253), already
ported as `state/layout.ts::titlebarPaneBandWidth`; keep it:

```
animated_width = max(min(right_now − pr, avail) − 28, 0)
right_now      = eval_tween(right_tween, right_target)     // the live pane width
pr             = titlebar_right_pad(6)
avail          = viewport_width − row_left − pr − gap_budget
gap_budget     = 8 in takeover, 16 otherwise               // the row's 8px child gaps
− 28           = the fixed toggle button's own slot
```

**Children (in order)**

1. **Identity group** — hidden entirely when `takeover` is true (tabs.rs:310).
   `min-w:0; flex row; align-items:center; gap 6` (313-317).
   * Harness brand mark — `crate::pickers::harness_brand_icon(harness)`,
     **`size 14`**, `flex-none`, colour = the brand tint or `theme.text_muted`
     (318-328). Rendered only when the chat has a harness config.
   * Title — `min-w:0; truncate; font-size ui_rems(12.0); font-weight MEDIUM
     (500)`; colour `theme.text_muted.opacity(0.7)` on the new-chat canvas, else
     `theme.text.opacity(0.85)` (329-341).
   * Target tag — `flex-none; font-size ui_rems(12.0); colour
     theme.text_muted.opacity(0.5)`; text `"{folder} @ {device}"` (342-350).
2. `<div flex_1 />` spacer (353) — keep this even when the identity is empty, so
   the trailing group stays right-anchored (gap T14).
3. **Trailing** — `None` on the new-chat canvas; otherwise
   `#right-titlebar-controls`.

**States**

| State | Condition | What changes |
| --- | --- | --- |
| takeover | pane open **and** expanded | identity group not rendered at all; `row_left` switches to the takeover branch; band width uses `gap_budget = 8` |
| new-chat canvas | no chat selected | title is `""`, no harness mark, **no trailing group at all** |
| chat with no title | `chat.title` absent | title reads `"New session"` |

**Text (verbatim)**

| Case | String | Source |
| --- | --- | --- |
| New-chat canvas title | `""` (nothing — deliberate) | tabs.rs:172 |
| Chat with no title | `"New session"` | tabs.rs:165 |
| Target tag | `format!("{folder} @ {device}")` where folder falls back to `"~"` and device to `"Unknown device"` | tabs.rs:159-167 |

Titles are single-lined through `transcript::single_line(...)` before display
(tabs.rs:164).

**Data** — the identity is published by `routes/chat-page.tsx`'s `useTitlebar`
call and rendered by `ChatIdentity`. Fix there: `size={14}` on the brand icon is
already right; the title/folder **fonts** are wrong (gap T10), the group gap is
wrong (T11), and `.identity-badge` must go (T13).

#### 2.4.1 `#right-titlebar-controls`

`tabs.rs:222-297`

| Property | Value | Source |
| --- | --- | --- |
| flex | `flex-none; height 100%; flex row; align-items:center` | 226-232 |
| rendered when | `!on_canvas` (a chat is selected) | 222-224 |

Children, in order:

1. **Animated reveal band** — rendered **only when `right_pane_open`** (233).

   | Property | Value | Source |
   | --- | --- | --- |
   | width | `animated_width` (above) | 253 |
   | layout | `h-full; flex-none; flex row; align-items:center; gap 4; overflow hidden` | 256-262 |
   | padding | `padding-left 8; padding-right 4` | 265-266 |

   Children: `<div flex_1 min_w_0 h_full overflow_hidden>{ tab strip }</div>`
   (269-276), then `header_icon_button("expand-changes",
   right_pane_expand_icon(expanded))` (277-282).
   `right_pane_expand_icon` (tabs.rs:34-40): `COLLAPSE_ARROWS` when expanded,
   `EXPAND_ARROWS` when not (`collapse-arrows.svg` / `expand-arrows.svg`).

   > **Unmount the band when the pane is shut** (gap T18). It is currently kept
   > mounted at width 0 with `overflow:hidden`, which leaves the expand
   > `<button>` tabbable inside a zero-width box. Keep the *column* mounted (the
   > CSS width transition needs something to animate from) but render no
   > focusable control inside it while `!paneOpen` — e.g. gate the band's
   > children, not the band element.

2. **`header_icon_button("toggle-changes", icons::SIDEBAR_MINIMALISTIC)`**
   (289-294) — always present while a chat is selected; click →
   `toggle_right_pane`. This is the one fixed control.

*(The tab strip's own contents are ticket 07.)*

---

### 2.5 `titlebar_drag_region` — layout only

`shell.rs:3917-3963`. Marks the strip as `WindowControlArea::Drag`:

| Event | Effect | Source |
| --- | --- | --- |
| mouse-down (left) | `titlebar_should_move = true` | 3930-3933 |
| mouse-up (left) / mouse-down-out | `titlebar_should_move = false` | 3925-3929 |
| mouse-move while the left button is held | hands the drag to the compositor (`window.start_window_move()`), then disarms | 3943-3951 |
| click with `click_count() == 2` | macOS: `window.titlebar_double_click()`; elsewhere `window.zoom_window()` | 3952-3962 |

**Browsers have no window-drag.** Port **the layout of the strip only** — the
38 px bar with `TITLEBAR_TOP_PAD` and the two paddings. Drop the drag, the
double-click zoom, and `start_window_move` entirely (§6 desktop-only). Do **not**
add `-webkit-app-region: drag`.

---

### 2.6 Titlebar — settings route

`shell.rs:3898-3909`. A bare 38 px drag strip with **no content**: `size_full;
flex; items_center; padding-top 4; padding-left title_bar_content_start();
padding-right titlebar_right_pad(6)`, id `"settings-header-titlebar"`. The
section label lives in the settings nav sidebar, **not** in the bar (comment at
5817-5819).

On web: on `/settings/*`, publish `identity: null` and `onNewSession: null`, and
render no trailing group. `row_left` uses `title_bar_content_start()` (104), not
the sidebar-tracking branch.

---

### 2.7 `render_main` — the conversation column

`shell.rs:5806-6154`

**Settings route** (5820-5831): `flex_1; min-w:0; h-full; padding-top
Theme::TITLEBAR_HEIGHT (38); flex column`, wrapping the section outlet in a
`flex_1 min-h:0` box. Settings **never underlaps** the titlebar.

**Chat route** — `#chat-dropzone` (5990-6153)

| Property | Value | Source |
| --- | --- | --- |
| id | `"chat-dropzone"` | 5991 |
| position | `relative` | 5992 |
| flex | `flex_1; min-w:0; h-full; flex column` | 5993-5997 |

**Children (in order)**

1. **New-thread hero** (`new_thread_background_layer`, 6023) — rendered when
   `!has_selection || dock_frame.active`, deliberately **outside** the transcript
   edge-fade scope so it paints under the overlaid titlebar instead of going
   transparent across the titlebar band (6020-6022). **Ticket 15 owns the hero
   itself**; this ticket only leaves the slot for it, in this position.
2. **Transcript underlay** (6024-6065) — `absolute; inset 0; bottom = term_h`
   (the animated terminal height), containing
   `edge_faded(TRANSCRIPT_FADE_BAND = 24, top, bottom)` with **asymmetric bands**:
   * `.inset_top(Theme::TITLEBAR_HEIGHT)` — fully faded **by** the titlebar's
     bottom edge (the title text is opaque; overlap read as collision).
   * `.band_top(Theme::TRANSCRIPT_FADE_BAND)` (24).
   * `.band_bottom(bottom_band)` where
     `bottom_band = max(bottom_stack − term_h − Theme::STATUS_STRIP_HEIGHT, 1)`
     — opaque from the composer **pill's** top, zero at the underlay's bottom
     edge (the reserved status strip above the pill is empty air) (6045-6049).
   * **Always on** — gating on measured scroll state left the top unfaded for one
     frame on chat switch (6032-6035).

   The fade curve is **quadratic**, not linear (`edge_fade.rs`, §3.29):
   `alpha = base × (clamped distance / band)²`. CSS mask stops:

   ```css
   mask-image: linear-gradient(to bottom,
     rgba(0,0,0,0)      0px,
     rgba(0,0,0,0.0625) calc(B * 0.25),   /* 0.25² */
     rgba(0,0,0,0.25)   calc(B * 0.5),    /* 0.5²  */
     rgba(0,0,0,0.5625) calc(B * 0.75),   /* 0.75² */
     rgba(0,0,0,1)      B,
     … mirrored for the bottom band);
   ```

   *(The transcript's own rows are ticket 18; this ticket owns the **fade
   geometry** on `.transcript-fade` / the transcript scroll container.)*
3. `<div flex_1 min_h_0 />` — a spacer with **no id and no listeners**, so
   pointer and wheel events over it fall through to the list below (6072).
4. **Bottom chrome stack** (6073-6125): `flex-none; relative; flex column`.
   * A paint-time canvas measuring the stack's height into `bottom_stack` (plus
     `composer.dock_clearance_correction()`), recording
     `bottom_stack_has_composer`, and requesting another frame on change
     (6084-6101). Web equivalent: a `ResizeObserver` on the bottom stack writing
     a `--rb-bottom-stack` px value used by the transcript's bottom fade band.
     `bottom_stack_measurement_matches(measured, expected) = measured == expected`
     (shell.rs:837).
   * `render_status_strip` (§2.8).
   * The docked composer, rendered when `has_spaces || no_project ||
     has_appshots`. `#persistent-composer`: `relative; width composer_width;
     opacity = composer_dock.opacity(); margin-x auto`, containing the composer
     and, when a chat is selected, `render_jump_to_bottom` (6103-6123).
     *(The composer itself is ticket 13.)*
   * `render_terminal_container` (6124) — **ticket 26**.
5. **Drop overlay** (6126-6152): `#attachment-drop-overlay; absolute; inset 0;
   opacity 0; background theme.scrim().opacity(0.4 / 0.6); flex;
   align-items:center; justify-content:center; font-size ui_rems(13.0); colour
   theme.text`; text **`"Drop to attach"`**. A matching drag raises opacity to 1.
   GPUI matches against the **concrete TypeId** of the payload, so resize markers
   can never reveal it (6138-6141) — on web, check `event.dataTransfer.types`
   contains `"Files"` or the workspace-path type before revealing.
   **This ticket owns the overlay element, its geometry and its string; ticket 17
   owns what happens to the dropped files.**

**Outlet selection** (5897-5978)

| Condition | Outlet |
| --- | --- |
| `has_selection \|\| departing_transcript` | the transcript, with `relative; top: 8·(1 − dock_frame.transcript())`, `opacity = dock_frame.transcript()` (or 0 while `!transcript_geometry_ready`); a departing transcript is also fixed at `transcript_width` and covered by an `absolute inset-0 occlude` veil so it is visual history, not an interaction surface |
| `!has_spaces && !no_project` | the onboarding card — **built by ticket 15**, spec transcribed below so its values are not lost |
| otherwise | `Empty` |

**Onboarding card** (`shell.rs:5929-5975`) — *this ticket does not build it
(ticket 15 does); it is transcribed here because it is the third arm of
`render_main`'s outlet and its literals appear nowhere else.* Comment at
5930-5931: "Onboarding (first boot / after the destructive wipe): no folders to
work in yet — one clear affordance." Outer `size_full; flex column;
align-items:center; justify-content:center`, wrapping
`motion::fade_in("no-spaces-canvas", …)` around a `flex column;
align-items:center`:

| Child | Property | Value | Source |
| --- | --- | --- | --- |
| logo | icon `icons::ROBOCO_LOGO`, width **`41.9`** px | `.w(px(41.9))` | 5946-5947 |
| logo | height `48.0` px | `.h(px(48.0))` | 5948 |
| logo | colour `theme.text.opacity(**0.09**)` | `.text_color(theme.text.opacity(0.09))` | 5949 |
| heading | `margin-top 24; font-size ui_rems(16.0); weight MEDIUM; colour theme.text`; text **`"Add a project to get started"`** | | 5951-5958 |
| subline | `margin-top 6; font-size ui_rems(13.0); colour theme.text_muted.opacity(0.7)`; text **`"A project is a folder on one of your devices."`** | | 5959-5967 |
| button | `popover::btn_primary(theme, "Add a project")`, id `"onboarding-add-space"`, `margin-top 20`; click → `open_add_space` | | 5968-5973 |

`btn_primary` (`popover.rs:1049-1061`): `padding-x 12; padding-y 6; radius 8;
background theme.text; font-size ui_rems(13.0); weight MEDIUM; colour
theme.on_solid; cursor pointer; hover opacity 0.9`.

The **41.9 × 48** logo box is the mark's own aspect ratio, not a rounded square —
implement it as an explicit `width: 41.9px; height: 48px`, not a single `size`.

**Column-width motion** (the `WidthTween` / `RESIZE` half of this ticket)

| What | Trigger | Spec | From → to | Reduced motion |
| --- | --- | --- | --- | --- |
| `.sidebar` width (`--rb-sidebar-now`) | `toggle_sidebar` | `RESIZE` 200 ms `EASE_OUT` | `sidebar_now()` → `sidebar_target()` | snap |
| `.right-pane` width (`--rb-pane-now`) | open/close/expand | `RESIZE` 200 ms `EASE_OUT` | painted width → target | snap |
| `.titlebar` `padding-left` (`--rb-titlebar-row-left`) | either of the above | `RESIZE` 200 ms `EASE_OUT` | old → new | snap |
| `.titlebar-pane-band` width (`--rb-pane-band`) | pane width | `RESIZE` 200 ms `EASE_OUT` | old → new | snap |
| `.pane-seam-sidebar` `left` / `.pane-seam-right` `right` | either | `RESIZE` 200 ms `EASE_OUT` | old → new | snap |
| **all of the above** | a seam **drag** | **no transition** | tracks the pointer exactly | n/a |

`toggle_sidebar` (1947-1957) captures `from = sidebar_now()` — so a toggle
mid-animation reverses from **what is painted**, not from the settled value.
`sidebar_now()` (3790-3794) = `eval_tween(sidebar_tween, sidebar_target()) +
eval_resize_edge_bounce(...)`; `sidebar_target() = sidebar_collapsed ? 0 :
settings.sidebar_width`. The CSS `transition: width` on `.sidebar` already gives
the reverse-from-painted behaviour for free; verify it, and verify
`:root[data-rb-resizing]` still suppresses it during a drag.

**`conversation_width`** (`shell.rs:450`, `state/layout.ts:37`):

```
conversation_width(viewport, sidebar, right) = max(viewport − sidebar − right, 0)
```

Asserted: `conversation_width(1320,256,520) = 544`;
`conversation_width(1320,256,1064) = 0` (`shell.rs:8283-8297`).

---

### 2.8 `render_status_strip`

`shell.rs:6373-6443`

**Layout**

| Property | Value | Source |
| --- | --- | --- |
| height | `Theme::STATUS_STRIP_HEIGHT` (24), `flex-none` | 6381-6382 |
| width | `100%`, `max-width 768` | 6383-6384 |
| margin-x | `auto` | 6385 |
| display | `flex; align-items:center; gap Theme::SPACE_SM (8)` | 6386-6388 |
| padding-x | `Theme::SPACE_LG + 8` = **24** | 6389 |
| font-size | `ui_rems(11.0)` | 6390 |

The height is **always reserved** so the composer below never shifts.

> **Where it mounts.** The desktop reserves this strip in the **shell's bottom
> chrome stack**, not inside the transcript. Today `.status-strip` is a bare
> `flex: none; height: var(--rb-status-strip-height)` div rendered inside
> `.transcript-wrap` (`transcript.tsx:435`) with no width cap, no padding, no
> font and no content. Tickets 04 (row 11) and 18 (row 77) remove it from
> `transcript.tsx`; **this ticket is where the real one is built**, in
> `render_main`'s bottom stack above the composer. If 04 has already removed the
> old element, build the new one; if not, move it.

**States**

| `Indicator` | Content |
| --- | --- |
| no chat selected | empty strip |
| `Working` | **empty** — the working loader lives in the transcript now |
| `AwaitingInput` | **empty** — the QuestionPanel below is the surface |
| `Errored` | colour `theme.danger`, text **`"Run failed"`** |
| `None` **and** `composer.is_sending()` | `loaders::gradient_spinner("sending-indicator", theme, speed 2.5)` + `<div font-size ui_rems(12.0) colour theme.text_muted>` text **`"Sending…"`** |
| `None` | empty strip |

**Text (verbatim):** `"Run failed"`, `"Sending…"`.

**Data** — reads the chat's effective indicator (`lib/view.ts`) and the
composer's sending flag (ticket 05's `PendingSend`). `gradient_spinner`'s exact
geometry is ticket 20's (`loaders.rs`); until then use the existing
`components/glyph-spinner.tsx` and leave a Comment noting the substitution.

---

### 2.9 `render_jump_to_bottom` / `jump_pill`

`shell.rs:6161-6255`. Shown only when `transcript.jump_button_shown()`.

**Positioner** (6166-6177): `absolute; top −36; left 0; right 10; flex;
justify-content:center`. It shares the composer's measured dock transform and
paints **after** the composer, outside the transcript fade.

**Pill** (6211-6254)

| Property | Value |
| --- | --- |
| height | `30` |
| radius | `rounded-full` |
| border | `1px solid theme.border` |
| shadow | `shadow-md` |
| cursor | `pointer` |
| background | glass → `theme.glass_overlay()`; opaque → `hover_blend(key, theme.surface_raised, theme.surface_raised_hover)` |
| hover wash (glass only) | an **inner** full-height layer with `hover_blend(key, transparent, theme.glass_hover())` — a div has one background, and mixing the tint *toward* the wash would thin the pill on hover |
| inner layout | `h-full; rounded-full; flex; align-items:center; gap 6; padding-left 11; padding-right 13` |
| glyph child | `font-size ui_rems(13.0); colour theme.text_muted`; text **`"↓"`** |
| label child | `font-size ui_rems(13.0); colour theme.text`; text **`"Scroll to bottom"`** |
| outer wrapper | `frost::frosted(radius 15, blur 16, motion::dialog_in(anim_key, pill))` — frost **outside** the entrance animation so the pill composes as one scene layer |

**Motion**

| What | Trigger | Spec | From → to | Reduced motion |
| --- | --- | --- | --- | --- |
| pill entrance | `jump_button_shown()` becomes true | `DIALOG_IN` 180 ms `EASE` | `opacity 0→1`, `top 2px→0` | snap |
| hover background | pointer | `HOVER_FADE` 150 ms | rest → hover | snap |

**Text (verbatim):** `"↓"` and `"Scroll to bottom"`.

CSS mapping for the frost (`frost.rs`, §3.28):
```css
.jump-pill { border-radius: 15px; backdrop-filter: blur(16px); isolation: isolate; }
```
with a `@supports (backdrop-filter: blur(1px))` fallback to a solid tint.

> The current `.jump-bottom` is an invented 32 px circular icon button at
> `right: var(--rb-space-lg); bottom: calc(fade-band + 8px)`
> (`app.css:3391-3409`, `transcript.tsx:439`). Replace it wholesale with the pill
> above, positioned at `top: −36` relative to the composer wrapper (gap P22).

The subagent pane hosts a second instance anchored `absolute; bottom 16; left 0;
right 0; flex; justify-content:center` with keys `"subagent-jump-to-bottom"` /
`"subagent-jump-pill"` (6522-6536) — **ticket 07/19 wires that instance**; build
the pill as a reusable component so it can.

---

### 2.10 Page entrance — `fade_in("phase-app", page)`

`shell.rs:8048`, `motion.rs:330-337`. The whole ready page is one keyed
`fade_in`: **500 ms `FADE_IN` / `EASE_OUT_EXPO`, `opacity 0→1` and `top: 4px→0`**,
so arriving from a gate fades the page in (gap N5).

Key it on the gate phase so it restarts when the app goes Failed → Ready.

---

### 2.11 `render_update_strip` + `UpdateFlow`

`shell.rs:5035-5092`. Returns nothing unless the engine's `UpdateStatus` says
`update_available` **and** `latest_version` is `Some` **and** that version is not
the dismissed one.

**Text (verbatim)**, by install kind and `UpdateFlow`:

| Condition | Label | Clickable |
| --- | --- | --- |
| desktop install, `UpdateFlow::Idle` | `"Update available — v{latest}"` | yes |
| desktop install, `Downloading` | `"Downloading v{latest}…"` | **no** |
| desktop install, `Ready(_)` | `"Update ready — restart to apply"` | yes |
| desktop install, `Failed(msg)` | `"Update failed: {message}"` | yes |
| non-desktop install | ``"Update available — v{latest} · run `roboco update`"`` | yes |

**Layout** (5069-5090)

| Property | Value |
| --- | --- |
| id | `"update-strip"` |
| margin-x | `Theme::SPACE_SM` (8); **no** margin-bottom (the user-menu block below carries its own padding) |
| padding | `padding-x SPACE_SM (8); padding-y 6` |
| radius | `Theme::CONTROL_RADIUS` (6) |
| background | failed → `theme.danger.opacity(0.14)`; else `theme.accent_wash` |
| hover background | failed → `theme.danger.opacity(0.22)`; else `theme.accent.opacity(0.16)` |
| colour | failed → `theme.danger`; else `theme.accent` |
| font | `ui_rems(11.0)`, weight `MEDIUM` |
| label wrapper | `flex_1; min-w:0` |

**Click** (5096-5112): non-desktop install → dismiss this version. Desktop:
`Idle`/`Failed` → start the download; `Downloading` → no-op; `Ready(staged)` →
apply + relaunch.

> **Web scope:** build **only the non-desktop (advisory) branch** —
> ``"Update available — v{latest} · run `roboco update`"``, whose click dismisses
> that version (persist the dismissed version through ticket 03's settings
> store). The download/stage/relaunch branches are desktop-only (§5). Keep the
> `UpdateFlow` union in the type so the desktop branches can be added later
> without reshaping the component.

**Mount point:** `sidebar-body.tsx`, between `<ConnectionPill/>` and
`<AccountRow/>` — matching `render_chat_sidebar`'s order (`shell.rs:4997-5026`:
connection pill → update strip → sidebar notice → user menu).

---

### 2.12 `render_gate_card` + `GatePhase` + `grid_backdrop`

`shell.rs:7223-7275`, `7281-7365`.

**Phases** (`shell.rs:238-240` of the component tree):

| `GatePhase` | Renders |
| --- | --- |
| `Loading` | **only** the root + splash |
| `Failed(error)` | root + `render_gate_card` |
| `Ready` | the page |

**Card**: `size_full; relative; background theme.bg`, containing
`grid_backdrop(theme)` and an `absolute inset-0 flex; align-items:center;
justify-content:center` wrapper around `motion::fade_in("gate-card-failed",
<content>)`.

**Content** (only for `Failed`): `flex column; align-items:center; gap
Theme::SPACE_MD (12)`:
* error text — `font-size ui_rems(14.0); colour theme.text_muted`;
* **`"Retry"`** button — `id "retry-engine"; padding-x 12; padding-y 6; radius 8;
  1px solid theme.border; font-size ui_rems(13.0); colour theme.text; cursor
  pointer; hover background theme.glass_hover()`. Click → `retry_engine` →
  `AppState::bootstrap`.

**`grid_backdrop`** — the roboco `.bg-grid` port:

| Property | Value | Source |
| --- | --- | --- |
| line colour | `Theme::hairline(0.035)` | 7282 |
| `STEP` | 44 | 7284 |
| `SPAN` | 2640 | 7285 |
| verticals | `i · 44` for `i` in `1..60`, `width 1`, `top 0; bottom 0` | 7286-7294 |
| horizontals | `i · 44` for `i` in `1..45` (`SPAN·0.75/STEP`), `height 1`, `left 0; right 0` | 7295-7303 |
| container | `absolute; inset 0; overflow hidden` | 7304-7307 |
| mask approximation (gpui has no `mask-image`) | four edge gradients back into `theme.bg`: top `height 120` `linear-gradient(180deg, bg 0%, bg@0 100%)`; bottom `height 260` at `0deg`; left `width 200` at `90deg`; right `width 200` at `270deg` | 7312-7363 |

**Web:** draw the grid with two `repeating-linear-gradient`s at a 44 px step in
`rgb(var(--rb-hairline) / 0.035)`, and use a real CSS mask —
`mask-image: radial-gradient(ellipse 50% 40% at 50% 50%, black, transparent)` —
which is what the original web version did. The four gradients are a gpui
workaround; do **not** port them.

**Web mapping of `GatePhase`:** `Loading` while the engine session is connecting
and no cached view exists; `Failed` when the session reports a fatal connect
error; `Ready` otherwise. Replace the in-`.main` connection banner
(`app-shell.tsx:295-308`) for the **fatal** case only — the non-fatal transport
states stay as the banner/connection pill (§5.7 of the research: they are
legitimate web-only chrome).

---

### 2.13 Escape handling

Two phases, exactly as `shell.rs` (gaps N8, N9). Build this as **one
capture-phase `document` listener** in `state/escape.ts`, replacing the ad-hoc
per-component `window` listeners (`app-shell.tsx:156-172`, `composer.tsx:392`,
`chat-page.tsx:48-58`).

**Capture phase** — `capture_escape_surface` (`shell.rs:5325-5369`), checked in
this order; the first match wins and `stopPropagation()`s:

| # | Check | Result |
| --- | --- | --- |
| 1 | delete-confirm / delete-space-confirm / chat menu / space menu / user menu is open | `true` — **blocked, nothing closes** (they already have a Cancel path) |
| 2 | `rename_dialog.is_some()` | clear it, `true` |
| 3 | `rename_space_dialog.is_some()` | clear it, `true` |
| 4 | `add_space.is_some()` | clear it, `true` |
| 5 | `spaces_menu.is_open()` | `close_spaces_menu`, `true` |
| 6 | `spaces_menu.get().is_some()` (already closing) | `true` |
| 7 | `right_plus.is_open()` | `close_right_plus`, `true` |
| 8 | `right_plus.get().is_some()` | `true` |
| 9 | otherwise | delegate to the active `Changes` surface's `handle_escape` |

**Bubble phase** — `resolve_shell_escape` (`shell.rs:942-971`):

```
if key != "escape"                                       → OtherKey
else if blocking_overlay                                 → Blocked
else if !escape_stops_active_agent
     || route != Chat
     || interrupting                                     → Ignored
else if indicator ∈ {Working, AwaitingInput}
     → selected_chat.map(InterruptChat).unwrap_or(Ignored)
else                                                     → Ignored
```

Outcomes at the call site (`on_key_down`, 5414-5430): `Blocked` →
`stop_propagation` only; `InterruptChat(id)` → `stop_propagation` +
`composer.interrupt_chat(id)`; everything else → nothing.

`escape_stops_active_agent` is a **client setting** (ticket 03), surfaced on the
Shortcuts page as `"Stop active agent with Escape"`; `Restore defaults` sets it
to `false`, i.e. it is **opt-in** (`shortcuts.rs:700-708`).

**Web plumbing:** `state/escape.ts` exposes a small registry —
`registerEscapeSurface(priority, handler)` — so menus, dialogs and the add-space
palette (tickets 09–11) push themselves onto the ladder without this module
importing them. The **priority order above is fixed here**; later tickets only
register.

**Tab key** — `on_key_down` (5391-5403): a bare `Tab` (no ctrl/alt/platform)
walks the accessible focus order (`focus_prev` / `focus_next`), then
`stop_propagation`s. On web this is native; the only requirement is that
**nothing custom swallows Tab** at the shell level (gap N10).

**Focus recovery** (`shell.rs:99-119`) does not port. The *observable* rules that
do (gap N25): after clicking away from an input, global shortcuts still work;
closing the right pane or the terminal returns focus to the composer.

---

### 2.14 z-index ladder

gpui `deferred` priorities: menus **1**, modals **2** (`popover.rs:629-637`,
`688-704`). Today `.titlebar` and `.drawer-backdrop` are **both 40** and the
drawer only wins by DOM order (gap N17). Define one documented ladder in
`app.css` and use it everywhere:

| Layer | z-index |
| --- | --- |
| pane seams | 5 |
| titlebar | 40 |
| drawers / sheets | 50 |
| menus + popovers (`deferred` priority 1) | 60 |
| modals + dialogs (`deferred` priority 2) | 70 |
| gate card | 80 |

---

## 3. Pure logic to port

Copied from research §4. Each gets a vitest case named after the desktop test.

### 3.1 Titlebar geometry (§4.2)

```
titlebar_new_session_alpha(is_chat_route, has_selected_chat)
    = (is_chat_route && has_selected_chat) ? 1.0 : 0.0
```

Signature: `titlebarNewSessionAlpha(isChatRoute: boolean, hasSelectedChat: boolean): number`
→ `web/packages/app/src/state/layout.ts`.

Desktop tests → web tests:
- `new_session_action_lives_in_the_titlebar_only_when_useful` (`shell.rs:8408`)
- `titlebar_cluster_matches_roboco_window_controls` (8447) — asserts
  `CLUSTER_BUTTONS_WIDTH == 82` and `TITLEBAR_ACTION_SLOT_WIDTH == 32`
- `titlebar_spacer_selects_per_platform_and_fullscreen` (8462) — on web, assert
  the spacer width is 0 and `TITLEBAR_CONTENT_START == 104`
- `cluster_clearance_clears_the_overlay_buttons` (8508)

`titlebarRowLeft` and `titlebarPaneBandWidth` already exist and already match
(§5.2 T16, T17). Keep their existing tests in `tests/layout.test.ts`; add the
`− 14` comment.

### 3.2 Pane width math (§4.3)

```
right_pane_max_width(viewport, sidebar)      = max(viewport − sidebar − 300, 0)
right_pane_takeover_width(viewport, sidebar) = max(viewport − sidebar, 0)
conversation_width(viewport, sidebar, right) = max(viewport − sidebar − right, 0)
```

Asserted values (`shell.rs:8283-8297`): `right_pane_max_width(1200,256) = 644`;
`right_pane_max_width(800,256) = 244`; `right_pane_takeover_width(1200,256) = 944`;
`conversation_width(1320,256,520) = 544`; `conversation_width(1320,256,1064) = 0`.

Desktop tests: `right_pane_ceiling_preserves_the_chat_floor` (8283),
`right_pane_takeover_consumes_the_chat_column` (8293). Already ported in
`tests/layout.test.ts` — verify the `conversation_width` cases above are present.

### 3.3 Escape resolution (§4.7)

Signature:
```ts
type EscapeOutcome =
  | { kind: "otherKey" }
  | { kind: "blocked" }
  | { kind: "ignored" }
  | { kind: "interruptChat"; chatId: string };

resolveShellEscape(input: {
  key: string;
  blockingOverlay: boolean;
  escapeStopsActiveAgent: boolean;
  route: "chat" | "settings";
  interrupting: boolean;
  indicator: "working" | "awaitingInput" | "errored" | "completed" | "idle" | null;
  selectedChatId: string | null;
}): EscapeOutcome
```
→ `web/packages/app/src/state/escape.ts`.

Desktop tests → `tests/escape.test.ts`:
- `escape_interrupts_only_the_active_live_chat` (`shell.rs:8300`)
- `escape_ignores_non_live_or_ineligible_views` (8328)
- `escape_interrupt_is_opt_in` (8380)

### 3.4 Misc pure helpers (§4.15)

```
bottom_stack_measurement_matches(measured_has_composer, expected_has_composer)
    = measured == expected                                        // shell.rs:837
lerp(from, to, t) = from + (to − from)·t                          // motion.rs:430
```

### 3.5 Combo formatting (§4.10) — **not this ticket**

`badge_combo` / `display_combo` / `platform_combo` belong to ticket 12.

---

## 4. Gaps this ticket closes

Copied verbatim from research §5, filtered to this ticket.

### From §5.2 Titlebar

| # | Item | Kind | Desktop | Web | Fix |
| --- | --- | --- | --- | --- | --- |
| T1 | Forward button enablement | WRONG BEHAVIOUR | `nav_history_button("nav-forward", …, can_forward)`; `NavHistory::can_forward()` = `index + 1 < entries.len()` (`shell.rs:590`) | `canForward` is **hard-coded `true`** (`app-shell.tsx:239`) | implement a real `NavHistory` (§4.1) — TanStack's `canGoBack` alone is not enough; you need a forward index too |
| T2 | `+` visibility | WRONG BEHAVIOUR | `titlebar_plus_alpha` is **1.0 only when `route == Chat && selected_chat.is_some()`** (`shell.rs:193-199`) — hidden on the blank canvas and in Settings | `onNewSession` falls back to `onNewChat` whenever paired (`app-shell.tsx:240`), so the `+` shows on `/` and `/settings` too | gate on "a chat is selected **and** we're on a chat route" |
| T3 | `+` opacity fade | MISSING | the `+` wrapper carries `opacity: plus_alpha` and the whole `row_left` inset tweens over `RESIZE` (200 ms) as the alpha flips | the button is mounted/unmounted outright | keep it mounted and animate `opacity` + the `--rb-titlebar-row-left` transition together |
| T4 | Icon size in window controls | WRONG VALUE | `window_control_button` / `nav_history_button` / `header_icon_button` all render the icon at **`size 16`** (`shell.rs:7411, 7543, 7585`) | `<Icon size={14}/>` (`titlebar.tsx:167`) | 16 |
| T5 | `header_icon_button` box size | WRONG VALUE | **28 × 28** for the pane toggle + expand (`shell.rs:7562`); 24 × 24 only for the left cluster | `.window-control { width:24px; height:24px }` for **all** of them (`app.css:~300`) | give the two trailing controls a 28 × 28 variant; keep the left cluster at 24 |
| T6 | `header_icon_button` hover wash | WRONG VALUE | `hover_blend(key, wash(0.0), wash(0.11))` (`shell.rs:7570-7574`) | `.window-control:hover { background: var(--rb-hover) }` (= `element_hover`) | the trailing controls use `rgb(var(--rb-wash) / 0.11)`, not `--rb-hover` |
| T7 | Left-cluster hover wash | MATCHES in role, check value | `hover_blend(key, glass_hover().opacity(0), glass_hover())` = `theme.element_hover` | `var(--rb-hover)` | ✅ |
| T8 | `window-control-active` | INVENTED | the desktop's toggle has **no** active/pressed state — it is a plain `window_control_button` | `.window-control-active { background: rgb(var(--rb-wash)/0.11); color: var(--rb-text) }` applied when `paneOpen` (`titlebar.tsx:126`, `app.css`) | remove, unless a deliberate web affordance is agreed |
| T9 | Disabled nav button | WRONG BEHAVIOUR | a disabled nav button renders as a **non-interactive box with only the icon at `text_muted.opacity(0.35)`** — no background, and still `.occlude()`d (`shell.rs:7530-7545`) | `.window-control:disabled { opacity: 0.35 }` dims the **whole button** including its background | dim the icon only |
| T10 | Titlebar identity font | WRONG VALUE | title `ui_rems(12.0)` weight `MEDIUM`, colour `theme.text.opacity(0.85)`; target tag `ui_rems(12.0)`, colour `theme.text_muted.opacity(0.5)` (`tabs.rs:333-348`) | `.identity-title { font-size: 13px; font-weight: 600 }`, `.identity-folder { font-size: 11px }` | 12 px / 500 for the title, 12 px for the folder |
| T11 | Identity gap | WRONG VALUE | `gap 6` inside the identity group (`tabs.rs:317`) | `.titlebar-identity { gap: var(--rb-space-sm) }` = 8 | 6 |
| T12 | Harness mark size | WRONG VALUE | `size 14` in the titlebar (`tabs.rs:322`) | `Icon` default, unset size | 14 |
| T13 | `.identity-badge` | INVENTED | the titlebar identity has **no badge** — only mark + title + "folder @ device" | a fully-styled 16 px mono badge exists in CSS | remove or wire to a documented desktop element |
| T14 | Empty identity box | WRONG BEHAVIOUR | on the new-chat canvas the title is `""` and the harness mark is absent, but the identity group still occupies its slot and the `flex_1` spacer follows (`tabs.rs:310-353`) | `identity !== undefined` renders an empty `flex:1` div; `identity === null` still renders | equivalent in effect, but verify the `flex_1` spacer exists so the trailing group stays right-anchored |
| T15 | Titlebar background | MATCHES | no fill, no blur, no border — the glass shell shows through (`tabs.rs:356-358`) | `.titlebar` has no background | ✅ |
| T16 | `row_left` math | MATCHES | see §3.3 | `titlebarRowLeft` in `state/layout.ts:95` reproduces it including the `− 14` | ✅ — but document the `− 14` (it cancels `TITLEBAR_IDENTITY_GAP(12)` minus the strip's own left pad, `tabs.rs:216-218`) |
| T17 | Pane band width | MATCHES | `max(min(right_now − pr, avail) − 28, 0)`, `gap_budget = takeover ? 8 : 16` (`tabs.rs:243-253`) | `titlebarPaneBandWidth` reproduces it exactly | ✅ |
| T18 | Focusable controls inside a 0-width band | WRONG BEHAVIOUR | the whole trailing band is **not rendered** when the pane is shut (`tabs.rs:233`) | the band stays mounted at width 0 with `overflow:hidden`; the expand `<button>` remains tabbable | unmount the band when `!paneOpen` |

### From §5.3 Sidebar

| # | Item | Kind | Desktop | Web | Fix |
| --- | --- | --- | --- | --- | --- |
| S46 | **Update strip** | MISSING | `render_update_strip` (§3.12) sits between the connection pill and the user menu | absent | at minimum port the advisory branch (``"Update available — v{x} · run `roboco update`"``) |

### From §5.4 Panes, seams and resizing

| # | Item | Kind | Desktop | Web | Fix |
| --- | --- | --- | --- | --- | --- |
| P21 | Status strip | MISSING | a **permanently reserved** 24 px strip above the composer (`max-width 768; margin-x auto; padding-x 24; font 11px`) so the composer never shifts; shows `"Run failed"` on error and a spinner + `"Sending…"` while sending (§3.17) | not present in the shell | add — the reservation is what stops the composer jumping |
| P22 | Jump-to-bottom pill | MISSING here | `absolute; top −36; left 0; right 10; justify-center`; pill `height 30; rounded-full; 1px border; shadow-md; gap 6; pl 11; pr 13`; `"↓"` + `"Scroll to bottom"`; `frosted(15, 16)` + `dialog_in` (§3.18) | *(transcript surface — verify there)* | flag for the transcript doc |
| P23 | Drop overlay | MISSING | `#attachment-drop-overlay` over the whole conversation column: `opacity 0` → 1 on a matching drag, `scrim@(0.4/0.6)`, centred `"Drop to attach"` at `ui_rems(13)` (§3.16) | not present | add |

### From §5.6 Routing, overlays, global

| # | Item | Kind | Desktop | Web | Fix |
| --- | --- | --- | --- | --- | --- |
| N5 | Route `fade_in` | MISSING | the whole ready page is one keyed `fade_in` (500 ms `EASE_OUT_EXPO`, `opacity 0→1` + `top 4→0`) so arriving from the splash or any gate fades the page in | nothing | add |
| N6 | **Gate card** | MISSING | `render_gate_card` — the engine-failure screen with the 44 px `hairline(0.035)` grid backdrop, the error copy at `ui_rems(14) text_muted`, and a `"Retry"` button (§3.25) | the connection banner inside `.main` | add a real gate screen for the failed state |
| N8 | Escape handling | MISSING | a two-phase model: **capture** resolves shell surfaces (`capture_escape_surface`, §4.7) before a focused descendant can eat the key; **bubble** interrupts the live chat when `escape_stops_active_agent` | per-component `window keydown` listeners in bubble phase; no interrupt, no `escape_stops_active_agent` setting | implement the ordered ladder in a single capture-phase document listener |
| N9 | Escape blocks rather than closes for some surfaces | MISSING | delete-confirm, chat menu, space menu and user menu **block** Escape (returning `true`) without closing — they already have a Cancel path | Escape closes every menu | match the ladder exactly |
| N10 | Tab focus walking | MISSING | a bare Tab walks accessible controls including individual transcript link ranges (`window.focus_next/prev`) | native browser tabbing | ✅ by default, but check that nothing custom swallows Tab |
| N17 | z-index collision | WRONG VALUE | gpui `deferred` priorities: menus 1, modals 2 | `.titlebar` and `.drawer-backdrop` are **both 40**; the drawer only wins by DOM order | give menus and modals two distinct tiers above everything else |
| N25 | Focus recovery | MISSING | `restore_mounted_focus` keeps shortcuts alive after a control unmounts (§4.14) | n/a on web | the *observable* rules still port: after clicking away from an input, shortcuts work; closing the pane or the terminal returns focus to the composer |

---

## 5. Do not

**Deferred — do not build (open question, not scope):**
- **`SplashPhase` / the boot splash.** The desktop crossfades a splash overlay
  out 680 ms after the engine reports Ready (`SPLASH_OUT` = 150 ms delay +
  500 ms, plus 30 ms ⇒ 680 ms; `shell.rs:916-921, 1856-1876, 8089-8099`). The
  research lists this as an **open question needing a human** (00-index "Open
  questions", 01 §7.1: "Should the web client reproduce this, or is the
  bundle-load + pairing flow a different surface entirely?"). **Deferred.** Build
  `GatePhase::Loading` as a plain empty root with no overlay; leave the
  `SplashPhase` union out of the code entirely so nobody half-implements it.

**INVENTED — remove, do not re-add:**
- `.window-control-active` and the `active={paneOpen}` prop (T8).
- `.identity-badge` in `app.css` and the `"Archived"` badge in `ChatIdentity`
  (T13). **Ticket 04's deletion pass (row 2) may already have removed it** — if
  so, just verify. Do not re-add it, and do not repurpose the class: ticket 08
  writes the jump-hint chip's rule fresh as `.chat-row-jump`.
- The circular 32 px `.jump-bottom` icon button (P22) — replaced, not restyled.

**Desktop-only — do not attempt:**
- `titlebar_drag_region`'s `start_window_move` / `titlebar_double_click` /
  `zoom_window` (`shell.rs:3917-3963`). Keep the strip's layout; drop the drag.
- `render_windows_caption_controls`, `windows_caption_button`,
  `WINDOWS_CAPTION_*`, `resolve_linux_captions`,
  `render_linux_caption_controls`, `linux_caption_button`,
  `caption_buttons_width` — all collapse to zero on web.
- macOS traffic-light inset: `titlebar_cluster_start`, `titlebar_spacer`, the
  fullscreen `titlebar_tween`. `titlebar_spacer_width` ⇒ 0;
  `cluster_buttons_start` ⇒ 10.
- The frosted island (§3.6.1) — it exists only to sit behind macOS traffic
  lights on the collapsed-sidebar artwork canvas.
- `UpdateFlow`'s desktop-install branches (download / stage / relaunch).
- `PendingExit` / `prepare_window_close` / `prepare_quit` /
  `apply_staged_update`; native app menus and ⌘Q/⌘W/⌘M/⌘H.
- `restore_mounted_focus` (gpui focus model).

**Owned by other tickets — do not touch:**
- The right-pane **tab strip contents**, the surface picker, the `+` menu, the
  pane's body and its resize handle → **ticket 07**. This ticket owns only the
  band that *hosts* the strip and the 28 px toggle/expand buttons.
- The sidebar's rows, filter, archived shelf, resort glide, jump-hint chips →
  **ticket 08**. This ticket only mounts `<UpdateStrip/>` into it.
- Popover/menu primitives, the chat context menu, rename & delete dialogs →
  **tickets 09, 10**.
- The keymap, shortcut bus, jump hints, `escapeStopsActiveAgent`'s **settings
  UI** → **ticket 12**. This ticket builds the escape *ladder* and reads the
  setting; it does not add any new key bindings.
- The composer, its placeholder, its Enter policy → **ticket 13**.
- The new-thread hero (`new_thread_background_*`) and the onboarding card
  (`"Add a project to get started"`) → **ticket 15**.
- Transcript rows, the working trailer, the rail → **tickets 18–21**.
- `render_terminal_container` — the bottom terminal dock → **ticket 26**.
- `loaders::gradient_spinner` exact geometry → **ticket 20**.

---

## 6. Acceptance

- [ ] Left cluster: 24 × 24 controls, **16 px** icons, `TITLEBAR_CLUSTER_PAD` 10,
      `TITLEBAR_GROUP_GAP` 8 between groups, `TITLEBAR_CONTROL_GAP` 2 inside the
      nav group.
- [ ] Back/Forward are enabled from ticket 05's `NavHistory`; a **disabled** one
      shows no background and a 35 %-alpha icon, and is not focusable.
- [ ] The `+` is present **only** on the chat route with a chat selected, stays
      mounted, and cross-fades its opacity over `RESIZE` (200 ms) while
      `--rb-titlebar-row-left` transitions on the same curve.
- [ ] The two trailing controls are 28 × 28 with a `rgb(var(--rb-wash)/0.11)`
      hover wash; the pane toggle has **no** active state.
- [ ] Identity: 6 px gap, 14 px harness mark, 12 px/500 title at
      `text@85 %`, 12 px folder tag at `text_muted@50 %`; no badge; the `flex_1`
      spacer keeps the trailing group right-anchored even with an empty identity.
- [ ] The trailing band renders **no focusable control** while the pane is shut.
- [ ] On `/settings/*` the bar is empty, `row_left` = 104, no trailing group.
- [ ] `.status-strip` is 24 px tall, `max-width: 768px`, `margin-inline: auto`,
      `padding-inline: 24px`, `font-size: 11px`, and is reserved even when empty;
      it shows `"Run failed"` on error and the spinner + `"Sending…"` while
      sending.
- [ ] The jump pill is a 30 px `rounded-full` bar reading `↓  Scroll to bottom`,
      `top: −36` over the composer, with `backdrop-filter: blur(16px)`, a
      `DIALOG_IN` entrance and the desktop's paddings (11 left / 13 right, gap 6).
- [ ] `#attachment-drop-overlay` covers the conversation column, reveals only for
      a file/workspace-path drag, and reads **`"Drop to attach"`**.
- [ ] The transcript's edge fade uses the **quadratic** stops, is inset by 38 px
      at the top, and its bottom band is
      `max(bottom_stack − term_h − 24, 1)`.
- [ ] The update strip renders the advisory string
      ``Update available — v{x} · run `roboco update` `` at 11 px/500 on
      `--rb-accent-wash`, dismisses that version on click, and sits between the
      connection pill and the account row.
- [ ] A fatal engine failure shows the gate card: grid backdrop (44 px step,
      `rgb(var(--rb-hairline)/0.035)`, radial mask), 14 px muted error copy, and
      a bordered `"Retry"` button; Ready fades the page in over 500 ms
      `EASE_OUT_EXPO` with a 4 px rise.
- [ ] Toggling the sidebar or the pane glides every dependent value
      (`--rb-sidebar-now`, `--rb-pane-now`, `--rb-pane-band`,
      `--rb-titlebar-row-left`, both seam offsets) on `RESIZE` 200 ms `EASE_OUT`;
      a drag suppresses all of them; `prefers-reduced-motion: reduce` snaps.
- [ ] One capture-phase Escape listener implements the 9-step ladder, then the
      bubble-phase `resolveShellEscape`; menus/dialogs **block** without closing;
      the interrupt is gated on `escapeStopsActiveAgent` (default `false`).
- [ ] The z-index ladder in §2.14 is in `app.css` with one comment naming the
      gpui priority it maps from; `.titlebar` and `.drawer-backdrop` no longer
      collide.
- [ ] Unit tests:
      `new_session_action_lives_in_the_titlebar_only_when_useful` →
      `tests/titlebar.test.ts`;
      `titlebar_cluster_matches_roboco_window_controls`,
      `titlebar_spacer_selects_per_platform_and_fullscreen`,
      `cluster_clearance_clears_the_overlay_buttons`,
      `right_pane_ceiling_preserves_the_chat_floor`,
      `right_pane_takeover_consumes_the_chat_column` →
      `tests/layout.test.ts`;
      `escape_interrupts_only_the_active_live_chat`,
      `escape_ignores_non_live_or_ineligible_views`,
      `escape_interrupt_is_opt_in` → `tests/escape.test.ts`.
- [ ] Screenshot pair, desktop vs web, states: (a) chat selected, sidebar
      expanded, pane closed; (b) chat selected, sidebar collapsed (mid-glide and
      settled); (c) blank canvas — no `+`, empty identity, no trailing group;
      (d) settings route — empty bar; (e) Back disabled / Forward disabled;
      (f) status strip showing `"Sending…"`; (g) jump pill visible;
      (h) drag-over showing `"Drop to attach"`; (i) gate card.
- [ ] `pnpm -r build` green; `pnpm --filter @roboco/app test` green.
- [ ] No new literal hex/px where a `--rb-*` token exists.

## Comments

### 2026-09-17 — implemented (branch `wp1/06-titlebar`)

**Landed** (work from two interrupted sessions, verified and finished in a
third pass — the component/state layer and most of `state/*` were in the
worktree uncommitted; this pass added the stylesheet, the tests, two bug
fixes found in verification, and all captures)

- **Titlebar cluster** (`titlebar.tsx`, `app.css`): 24×24 `window-control`s
  with 16px icons at the desktop's rhythm (measured in-browser: toggle x=10,
  Back x=42, Forward x=68, `+` x=100 — cluster pad 10, group gap 8, control
  gap 2); `header-icon-button` is a separate 28×28 class with the
  `rgb(var(--rb-wash)/0.11)` hover wash; `.window-control-active` and the
  `active={paneOpen}` prop are gone (ticket 04 had already deleted the CSS;
  this ticket removed the prop wiring). A disabled nav button renders with
  **no background** and only the icon at `text_muted @ 35%`
  (`color-mix`, `pointer-events: none`), verified computed.
- **`+` gating + fade**: `titlebarNewSessionAlpha` in `state/layout.ts`
  (§3.1, tested). The `+` stays MOUNTED, cross-fades opacity+visibility on
  `RESIZE` 200ms `EASE_OUT` while `--rb-titlebar-row-left` transitions on the
  same curve; at alpha 0 it is `visibility: hidden` and `tabIndex -1`
  (verified: blank canvas and Settings show alpha 0 / opacity 0 /
  hidden / tabindex −1). `onNewSession` has NO fallback handler — only the
  chat route with a selected chat publishes one (T2).
- **Row left**: `titlebarRowLeft` kept verbatim with the − 14 comment; the
  settings route uses flat `TITLEBAR_CONTENT_START` (104) — AppShell now
  branches on `isChatRoute` (measured: 104px on `/settings/appearance`,
  272px/136px on the chat route with sidebar 256/collapsed). Identity: gap 6,
  14px harness mark (icon `size={16}`→14 at the call site), 12px/500 title at
  `text @ 85%`, 12px folder at `text_muted @ 50%` (all measured computed);
  `.identity-badge`/`ChangeRequestBadge` gone; the `flex_1` `.titlebar-fill`
  keeps the trailing group right-anchored.
- **Trailing band**: stays mounted at width 0 while shut but renders NO
  children (0 focusable controls verified while closed); band width still
  derives from `--rb-pane-now` (T18 fixed the tabbable-button half; the
  band element itself is kept for the width transition, per the ticket's
  guidance).
- **Status strip** (`transcript.tsx` `StatusStrip` + CSS): 24px, always
  reserved, `max-width 768px; margin-inline auto; padding-inline 24px;
  font-size 11px; gap 8px` (all measured). Shows `"Run failed"` on errored
  and `MatrixSpinner` + `"Sending…"` while a send is pending — captured live
  by suspending the engine process mid-send (see Screenshots). The spinner is
  the standing `MatrixSpinner` port at size 16; `gradient_spinner`'s exact
  geometry stays ticket 20's (substitution noted in the code).
- **Jump pill** (`JumpPill` + `.jump-pill*` CSS): 30px, radius 15, 1px
  border, `shadow-md` (the file's existing elevation idiom), frost
  `blur(16px)` over the dialog tint with an `@supports` fallback, inner hover
  layer at `--rb-hover`, `DIALOG_IN` entrance (`rb-dialog-in` 180ms EASE,
  2px rise), paddings 11/13 on a 6px gap, `↓`/`"Scroll to bottom"` at 13px.
  Anchored `top: −36` over `.persistent-composer` with `right: 10` (measured:
  anchor right inset 210 = 200 column margin + 10). The old circular
  `.jump-bottom` is deleted wholesale. Reusable for ticket 07/19's subagent
  instance.
- **Transcript fade**: the scroller's own `mask-image` — quadratic stops
  0.0625/0.25/0.5625 at 25/50/75% of the 24px top band (measured in the
  computed mask), bottom band `max(--rb-bottom-stack − 24px, 1px)` (term_h is
  0 until ticket 26). `--rb-bottom-stack` is written by a ResizeObserver on
  `.bottom-stack` — **bug fixed in verification**: the observer effect
  originally keyed only on `[chatId]`, ran during the loading early-return
  while the refs were still null, and never re-armed, so the bottom band
  collapsed to 1px. Now keyed `[chatId, row?.chat.id]` (measured: 109px →
  band 85px). The desktop's 38px top inset is realized structurally: the
  column's titlebar padding puts the scroller's top edge at the bar's bottom
  edge, so the 24px band covers exactly the desktop's 38→62px fade region.
- **Drop overlay**: `#attachment-drop-overlay` over `.main`, scrim at
  `--rb-scrim-alpha`, `"Drop to attach"` at 13px, `pointer-events: none`,
  revealed only when `dataTransfer.types` includes `"Files"` (verified via a
  CDP-dispatched file drag; hidden again on dragleave). Ticket 17 owns the
  dropped files.
- **Update strip** (`update-strip.tsx` + CSS): advisory branch only —
  ``Update available — v{x} · run `roboco update` `` on `--rb-accent-wash`,
  11px/500, 8px outer inset, 6/8px padding, 6px radius, click dismisses into
  `ui-settings.dismissedUpdateVersion` (healed + defaulted null). The
  `UpdateFlow` union keeps the desktop's download/stage/relaunch arms in the
  type. Mounted between `ConnectionPill` and `AccountRow`. **Not capturable**:
  the smoke engine never reports `updateAvailable`, so the strip cannot
  appear against the fixture; verified by code + the §2.11 literals.
- **Gate + page entrance** (`root-layout.tsx`, `gate-card.tsx`, CSS):
  `GatePhase` mapping — parked (fatal) → `GateCard` with the 44px
  `hairline(0.035)` grid via two `repeating-linear-gradient`s under the REAL
  radial mask (the desktop's four edge gradients are a gpui workaround and
  are not ported), 14px muted error copy, bordered `"Retry"` (6/12 pad,
  radius 8, glass hover), plus a web-only `Pair again` link (see Judgment
  calls). `GatePhase::Loading` is a bare empty root — no splash, per §5.
  Ready wraps in `.page-fade` keyed by phase: 500ms `EASE_OUT_EXPO` with a
  4px rise (`rb-rise-in`), verified computed. `useEngineRetry` in
  `session-provider` recreates the session (retry nonce).
- **Escape ladder** (`state/escape.ts`): `resolveShellEscape` (§3.3, tested)
  + `escapeStack`/`registerEscapeSurface`/`installEscapeLadder` — ONE
  capture-phase `document` keydown listener walking the 9-step priority
  ladder; consumed keys `stopPropagation()` (which also silences every
  bubble listener). The engine drawer + phone sidebar drawer register at a
  web-only `webDrawer` priority (12) just under the blocking overlays —
  judgment call, see below. The shell's bubble-phase listener resolves the
  interrupt (gated on `escapeStopsActiveAgent`, default `false`; in-flight
  interrupts tracked in a ref so a second Escape cannot stack a Stop).
  Tab is untouched at the shell level (N10).
- **z-index ladder**: the six `--rb-z-*` tiers with the gpui-priority comment
  in `:root`; `.titlebar` 40 / `.drawer-backdrop` 50 no longer collide, and
  the existing menu/dialog/modal/picker/lightbox/sidebar-menu surfaces were
  mapped onto the tokens (menus 60, modals 70, gate 80, seams 5). The phone
  sidebar stays at 30, deliberately under the titlebar (documented in the
  ladder comment).
- **Column-width motion**: unchanged mechanics, verified live — sidebar,
  right pane, pane band, titlebar `padding-left` and both seam offsets
  transition on `RESIZE` 200ms `EASE_OUT`; `:root[data-rb-resizing]` now also
  freezes `.titlebar` (row inset tracks a drag exactly); the whole set snaps
  under `prefers-reduced-motion: reduce` (one new block covers this ticket's
  tweens and entrances).

**Tests** — `tests/titlebar.test.ts` (new,
`new_session_action_lives_in_the_titlebar_only_when_useful`),
`tests/escape.test.ts` (new, the three §3.3 desktop tests),
`tests/layout.test.ts` (+ `titlebar_cluster_matches_roboco_window_controls`,
`titlebar_spacer_selects_per_platform_and_fullscreen`,
`cluster_clearance_clears_the_overlay_buttons`, and the `shell.rs:8283-8297`
asserted values for `right_pane_max_width`/`takeover`/`conversation_width`).
`pnpm -r build` green; `pnpm --filter @roboco/app test` green (35 files,
474 tests).

**Screenshots** (web half; `.scratch/web-parity/shots/06/` in the main
checkout, 1424×905 viewport, final build):
`web-a-chat-sidebar-expanded-pane-closed.png`,
`web-b-sidebar-collapsed-mid-glide.png` + `web-b-sidebar-collapsed-settled.png`,
`web-c-blank-canvas.png`, `web-d-settings-route.png`,
`web-e-nav-history-buttons.png` (Back/Forward both disabled; the
Forward-disabled-with-Back-enabled mixed state was DOM-verified with the
computed 35%-alpha icon and no background),
`web-f-status-strip-sending.png` (engine suspended mid-send so the ~60ms
pending window persists — a real pending state, not a mock),
`web-g-jump-pill.png`, `web-h-drop-attach.png` (CDP-dispatched file drag),
`web-i-gate-card.png` (a real parked session: the engine's credential was
refused after a suspend/resume cycle; captured on the pre-fix bundle, but
`GateCard`/`root-layout` are byte-identical in the final build — only the
chat-page observer deps changed after it).

**Skipped / deviated, all deliberate:**

- **Desktop half of every screenshot pair.** `shot.ps1` needs the desktop
  client foregrounded; the machine was continuously busy with sibling
  ticket agents capturing against the shared `web_smoke` port (three
  different sibling servers cycled through it during this session), and the
  desktop client was not running. Per the runbook and ticket 02's precedent
  this is a documented skip rather than repeatedly stealing focus. Parity
  evidence for the web half is the DOM-geometry verification above (every
  acceptance number measured computed, not eyeballed).
- §3.4's `bottom_stack_measurement_matches` / `lerp` were **not ported as
  functions**: they have no web call site (the ResizeObserver writes px
  directly and CSS transitions do the interpolation), and porting dead code
  would just re-hide that.
- §2.5 `titlebar_drag_region`: layout only, as instructed — no drag, no
  double-click zoom, no `-webkit-app-region`.
- The per-component Escape listeners in `composer.tsx`/`chat-page.tsx` stay:
  popovers/dialogs migrate onto the ladder in tickets 09–11, and the Mod+J
  listener is ticket 12's named removal.

**Judgment calls a human should review:**

1. **`webDrawer` escape priority (12)** — the desktop ladder has no drawer
   rung (drawers are a web-only surface). Placed just under the blocking
   overlays (10) so a drawer closes before any dialog, matching the web's
   previous behavior. If a later ticket disagrees, move the constant.
2. **Gate card's `Pair again` link** — a parked credential cannot be fixed by
   Retry, and the gate covers every route that could re-pair. Without the
   link a dead session strands the browser; it is flagged web-only in both
   the component and the CSS.
3. **Jump pill shadow** — gpui's `shadow-md` has no token; used the file's
   existing elevation idiom (`0 8px 30px rgb(0 0 0 / 0.35)`).
4. **Status-strip spinner size 16** — ticket 20 owns the exact geometry; 16
   reads correctly inside the 24px strip.
5. **Bottom fade band scope** — the web scroller does not underlap the
   composer (no absolute underlay), so the band fades the last
   `bottom_stack − 24` px of VISIBLE transcript. The number follows the
   ticket exactly; the geometry difference (what sits behind the band) is
   inherent to the web's in-flow column and should be sanity-checked
   against a real desktop capture when one is available.
