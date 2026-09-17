# 13 — Composer core

**What to build:** The composer stops being a `<textarea>` in a rounded box and
becomes the desktop's floating 26px-radius pill inside a centred 768px column:
correct pill geometry in both layouts, the **width-driven** compact↔expanded flip
with hysteresis and a 180 ms height morph (text glides, controls stay pinned),
auto-grow that clamps the *textarea box* to 76–260 and the *pill* to 124–308, a
28px send button that is Send / **Queue** / Stop (never "Steer"), the real send
path (run vs. `QueueMessage` with `holdForTurnEnd: true`, a capability gate, one
message id, an optimistic echo that is suppressed for queued sends, tracked
interrupts), per-chat drafts, the failure notice and queue-degraded caption, and
the 24px session footer with the checkout/branch labels and the context-usage
ring. After this ticket a user can type a long message and watch the pill grow
and collapse exactly like the desktop, send during a live run and get a queued
row instead of a steer, and see why a send failed instead of losing their text.

**Blocked by:** 02 (Foundation tokens), 03 (Client settings store), 05 (State
fixes: nav history, send ids, optimistic echo), 10 (Pickers and menus).

**Status:** ready-for-agent

**Research:** `../../web-client/research/04-composer.md` §3.0, §3.1–§3.4, §3.6–§3.8,
§3.12, §3.14, §3.15, §3.20, §3.21, §4, §5.1, §5.2, §5.3, §5.4, §5.5.
Context ring cross-ref: `../../web-client/research/02-transcript.md` §3.17
(`context_usage`).

**Desktop reference (for lookups only):** `crates/ui/src/composer.rs::Composer::render`,
`::render_send_button`, `::on_submit`, `::on_modified_submit`, `::send`,
`::send_blocked`, `::run_live`, `crates/ui/src/pickers.rs::render_footer`,
`crates/ui/src/pickers.rs::footer_label`, `crates/ui/src/context_usage.rs::render`.

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/components/composer.tsx` | edit (near-rewrite) | `Composer` — the column, the pill surface, the expanded body, the compact body, the actions row, the attach button, the send button |
| `web/packages/app/src/components/composer-footer.tsx` | edit | `ComposerFooter` — the 24px footer slot, the checkout-kind label, the branch label, the spring, the CR badge slot |
| `web/packages/app/src/components/context-usage.tsx` | edit | `ContextUsageIndicator` + its 260px tooltip |
| `web/packages/app/src/components/composer-pickers.tsx` | edit (geometry only) | the utility-group placement (2px gap to the paperclip). The chips and popovers themselves are ticket 10 |
| `web/packages/app/src/lib/composer-flip.ts` | new | `composerFlip`, `composerWidthChanged`, `caretVisible`, `inputContentHeight`, `composerTotalHeight`, `inputMaxScroll`, `inputOverflowEdges`, `inputRevealHeight`, `inputScrollOffset`, `inputScrollOffsetForCursor`, `inputDragScrollDelta`, `pressIntent`, `FlipMorph`, `flipMorphStep`, `morphClusterInset`, `morphTextPad`, `collapseTextGlide`, `morphClusterDy` |
| `web/packages/app/src/lib/composer-send.ts` | new | `sendButtonMode`, `sendBlocked`, `composerHasContent`, `modifiedSubmitTarget`, `shouldPublishOptimisticEcho`, `beginInterrupt`, `retainLiveInterrupts`, `interruptParams` |
| `web/packages/app/src/lib/composer-actions.ts` | edit | `buildRunRequest`, `sendRun`, new `queueMessage`; **delete** `sendSteer` and `maybePersistConfig` |
| `web/packages/app/src/lib/composer-draft.ts` | edit | per-chat draft text map (`drafts`), keyed `""` for the new-chat canvas |
| `web/packages/app/src/state/picker-catalog.ts` | edit | `loadHarnesses` latch fix |
| `web/packages/app/src/routes/chat-page.tsx` | edit | stop mounting `QueuePanel` / `ComposerFooter` as siblings; feed the composer a measured available width |
| `web/packages/app/src/styles/app.css` | edit | `.composer`, `.composer::before`, `.composer-expanded`, `.composer-compact`, `.composer-input`, `.composer-input-wrap`, `.composer-actions`, `.composer-utility`, `.composer-attach`, `.composer-send`, `.composer-stop-square`, `.composer-footer`, `.footer-chip`, `.footer-chip-label`, `.context-usage*`; **delete** `@keyframes composer-flip` (app.css:2045–2052) and the `@media (max-width:768px)` font bump (app.css:2054–2059) |
| `web/packages/app/tests/composer-flip.test.ts` | new | the §3 flip/morph/auto-grow/scroll unit tests |
| `web/packages/app/tests/composer-send.test.ts` | new | the §3 send-path unit tests |

---

## 1. Context a fresh session needs

- The composer is the floating pill at the bottom of the conversation column,
  plus everything stacked around it inside **one centred 768px column**: an
  optional failure notice, an optional queue-degraded caption, the queue tray
  tucked behind the pill, the pill itself (staged strips, the text editor, the
  actions cluster), and the 24px session-footer row underneath.
- It renders in **two layouts**: *compact* (one 49px line, controls inline on
  the right) and *expanded* (a growing textarea with an absolutely
  bottom-pinned 46px actions row). The flip between them is **width-driven**
  with hysteresis, animated by a manually driven height morph.
- The bottom edge of the pill is stationary on screen — the composer sits at
  the bottom of the column, so growth moves the **top** edge. That is why the
  controls are pinned to the bottom and only the text glides.
- Today on the web: `components/composer.tsx::Composer` renders a `.composer`
  div containing `<AttachmentStrip>`, a `.composer-input-wrap` with a plain
  `<textarea class="composer-input">`, and a `.composer-actions` row with
  `<ComposerPickers>`, `.composer-attach` and `.composer-send`. The flip is a
  pure height test (`scrollHeight > 50`). `routes/chat-page.tsx` mounts
  `<QueuePanel>` and `<ComposerFooter>` as **siblings** of the composer, not
  children of its column.
- The send path lives in `lib/composer-actions.ts`: `sendRun` (which calls an
  invented `maybePersistConfig` first and mints three UUIDs), `sendSteer`
  (invented — the desktop composer never steers) and `sendInterrupt`.
- Tokens: colors are `var(--rb-<role>)`; neutral washes are
  `rgb(var(--rb-wash) / a)`, `rgb(var(--rb-ink) / a)`,
  `rgb(var(--rb-hairline) / a)`; spacing/radii `var(--rb-space-*)`,
  `var(--rb-radius-*)`; motion `var(--rb-motion-*)` + `var(--rb-ease-*)`.
  Ticket 02 adds the missing roles (`text_dim`, `input_glass_bg` contrast-raise,
  `composer_sidebar_tint`, the frosted pill border).
- Icons come from `@roboco/icons` (`<Icon name=… size={…} />`). The names this
  ticket needs all exist: `paperclip`, `arrowUp`, `dangerTriangle`, `folder`,
  `folderWithFiles`, `gitBranch`.
- Vocabulary: **chat** (not session/thread), **harness** (not provider),
  **engine**, **space**.
- Adjacent surfaces this ticket deliberately leaves alone: mentions / slash /
  wizard (14), the new-thread canvas and the composer dock (15), the queue
  panel body (16), the attachment strip body (17), the comments chip (23).

---

## 2. Spec

### 2.0 Shared constants (transcribe these verbatim)

Every one of these is a `pub const` (or module const) at the top of
`composer.rs`. Put the ones this ticket uses in `lib/composer-flip.ts`;
the rest are listed so nothing is re-derived later.

| Name | Value | Meaning | Source |
|---|---|---|---|
| `TEXTAREA_PAD_V` | 20 | `pt-4`(16) + `pb-1`(4) on the expanded textarea box | composer.rs:48 |
| `TEXTAREA_MIN` | 76 | floor of the textarea BOX (content+padding), applies even when empty | composer.rs:53 |
| `TEXTAREA_MAX` | 260 | cap of the textarea BOX | composer.rs:54 |
| `ACTIONS_ROW_HEIGHT` | 46 | `pt-1`(4) + 32px chip + `pb-2.5`(10) | composer.rs:58 |
| `PILL_BORDER_V` | 2 | 1px hairline top + bottom | composer.rs:60 |
| `COMPOSER_RADIUS` | 26 | pill + queue-tray corner radius | composer.rs:62 |
| `COMPOSER_MIN_HEIGHT` | 124 | `76 + 46 + 2` | composer.rs:65 |
| `COMPOSER_MAX_HEIGHT` | 308 | `260 + 46 + 2` | composer.rs:66 |
| `COMPACT_TOTAL_HEIGHT` | 49 | `py-3`(24) + one 22.75px line → 47 + 2 hairline | composer.rs:70 |
| `COMPOSER_MAX_WIDTH` | 768 | `max-w-3xl` outer column width | composer.rs:72 |
| `QUEUE_SIDE_INSET` | 16 | queue tray is inset this much per side | composer.rs:74 |
| `QUEUE_COMPOSER_OVERLAP` | 18 | pill covers this much of the tray's bottom | composer.rs:77 |
| `NEW_THREAD_SELECTOR_ROW_HEIGHT` | 20 | floating device/project chip row (ticket 15) | composer.rs:80 |
| `SESSION_FOOTER_HEIGHT` | 24 | footer slot height | composer.rs:83 |
| `COMPOSER_WIDTH_EPSILON` | 0.5 | ignore sub-pixel width noise | composer.rs:94 |
| `MIN_COMPACT_INPUT_WIDTH` | 200 | below this the composer always expands | composer.rs:96 |
| `INPUT_LINE_HEIGHT` | 22.75 | `14 × 1.625` (`leading-relaxed`) | composer.rs:98 |
| `INPUT_TEXT_SIZE` | 14 | | composer.rs:99 |
| `INPUT_FADE_BAND` | 12 | top/bottom scroll fade ramp inside the input | composer.rs:101 |
| `AUTO_ADVANCE_MS` | 220 | wizard single-select auto-advance delay (ticket 14) | composer.rs:103 |
| `DRAG_SCROLL_FRAME_MS` | 16 | drag-selection autoscroll cadence (60fps) | composer.rs:105 |
| `COLLAPSE_HYSTERESIS` | 32 | expanded→compact slack | composer.rs:111 |
| `RESIZE_SETTLE_MS` | 150 | collapse deferral during interactive resize | composer.rs:115 |
| `CARET_BLINK_MS` | 500 | caret blink half-period | composer.rs:151 |
| `CLUSTER_Y_DELTA` | 2.5 | compact↔expanded cluster centering delta | composer.rs:394 |
| `CLUSTER_X_DELTA` | 4 | right-inset delta (`pr-2` 8 ↔ `px-3` 12) | composer.rs:402 |
| `ACTION_UTILITY_GAP` | 2 | pickers ↔ paperclip optical join | composer.rs:406 |
| `ACTION_PRIMARY_GAP` | `Theme::SPACE_SM` (8) | utility group ↔ Send | composer.rs:408 |
| `ROUTE_SNAP_MS` | 250 | flips committed within this of a nav SNAP | composer.rs:455 |
| `QUEUED_ATTACHMENTS_MIN` | `(0, 2, 12)` | min engine version for `pending://` refs on a queued send | composer.rs:489 |
| `UNDO_COALESCE` | 700 ms | single-char edit run merge window | composer.rs:854 |
| `UNDO_LIMIT` | 200 | retained undo steps | composer.rs:857 |
| `MENTION_PREFIX` | `'@'` | ticket 14 | composer.rs:864 |
| `MENTION_TOOLTIP_DELAY` | 420 ms | ticket 14 | composer.rs:865 |
| `MENTION_TOOLTIP_HEIGHT` | 24 | ticket 14 | composer.rs:866 |
| `MENTION_SIDE_PAD` | `"\u{00A0}"` | ticket 14 | composer.rs:867 |
| `FILE_MENTION_SCHEME` | `"roboco-file:"` | ticket 14 | composer.rs:870 |
| `STRIP_THUMB` | 56 | staged thumbnail size (ticket 17) | composer.rs:288 |
| `STRIP_GAP` | 8 | (ticket 17) | composer.rs:289 |
| `STRIP_PAD_TOP` | 12 | `pt-3` | composer.rs:290 |
| `STRIP_PAD_X` | 16 | `px-4` | composer.rs:291 |
| `APPSHOT_TILE_MIN_WIDTH` | 96 | desktop-only | composer.rs:523 |
| `APPSHOT_IMAGE_INSET` | 12 | desktop-only | composer.rs:524 |
| `APPSHOT_PREVIEW_HEIGHT` | 148 | desktop-only | composer.rs:525 |
| `APPSHOT_IMAGE_MAX_WIDTH` | 320 | desktop-only | composer.rs:526 |
| `APPSHOT_IMAGE_MAX_HEIGHT` | 132 | desktop-only | composer.rs:527 |
| `APPSHOT_TILE_HEIGHT` | 192 | desktop-only | composer.rs:528 |
| appshot remove-button background | `theme.bg.opacity(0.92)` | inline literal, not a named const: the 22px `rounded_full` remove button on an appshot card (`frost::layered`, `top` 6 / `right` 6, `shadow_sm`, `opacity 0` → 1 on group hover). **Desktop-only — do not port**; recorded so the literal audit resolves | composer.rs:4903 |
| `Theme::SPACE_XS/SM/MD/LG` | 4 / 8 / 12 / 16 | | proto/layout.rs:16–19 |
| `Theme::CONTROL_RADIUS` | 6 | | proto/layout.rs:34 |
| `popover::CARD_RADIUS` | 12 | every floating card | popover.rs |
| `badges::BADGE_HEIGHT` | 24 | | badges.rs:58 |
| `attachments::MAX_ATTACHMENT_BYTES` | 24 MiB | | attachments.rs:31 |
| `appshots::MAX_STAGED_APPSHOT_BYTES` | 96 MiB (4×24) | desktop-only | appshots.rs:55 |

Motion specs referenced (all from `crates/proto/src/motion.rs`):

| Name | Spec |
|---|---|
| `motion::COLLAPSE` | 180 ms, `EASE_OUT` = `cubic-bezier(0, 0, 0.58, 1)` |
| `motion::NEW_THREAD_TRANSITION` | 420 ms, `EASE_RESORT` = `EASE_OUT_QUINT` = `cubic-bezier(0.22, 1, 0.36, 1)` |
| `motion::FADE_QUICK` | 150 ms, `EASE` = `cubic-bezier(0.25, 0.1, 0.25, 1)` |
| `motion::FADE_IN` | 500 ms, `EASE_OUT_EXPO` = `cubic-bezier(0.16, 1, 0.3, 1)`, +4px rise |
| `motion::MENU_IN` | 140 ms, `EASE`, opacity 0.3→1 + translateY −2→0 |
| `motion::HOVER_FADE` | 150 ms, `EASE_TAILWIND` = `cubic-bezier(0.4, 0, 0.2, 1)` |

`motion::speed_scale()` is a dev knob (`ROBOCO_MOTION_SCALE`, default 1)
multiplying every duration; the web equivalent is a no-op constant `1`.

---

### 2.1 Composer column (`Composer::render`, the `container` div)

**Layout**

| property | value | source |
|---|---|---|
| width | `w_full`, `max_w` 768 (`COMPOSER_MAX_WIDTH`), `mx_auto` | composer.rs:7347–7350 |
| direction | flex column | 7351–7352 |
| gap | `Theme::SPACE_SM` (8) | 7353 |
| padding-x | `Theme::SPACE_LG` (16) | 7354 |
| padding-bottom | `Theme::SPACE_LG` (16) | 7355 |

**Children (in order)**
1. failure notice (conditional) — §2.2
2. queue notice caption (conditional) — §2.3
3. **wizard** (early return) — ticket 14; when the wizard is active nothing
   below is rendered (7440–7443)
4. queue tray (conditional) — §2.4 (wrapper only; body is ticket 16)
5. new-thread selector row (conditional) — ticket 15
6. pill surface — §2.5
7. session-footer slot (conditional) — §2.10
8. attachment lightbox (early return when `preview` is set) — ticket 17

**States**

| state | condition | effect |
|---|---|---|
| wizard | `self.wizard.is_some()` | pill, queue, selectors, footer all unrendered; wizard panel fades in over `FADE_QUICK` (ticket 14) |
| queue-edit escape | `editing_queued.is_some()` | container binds `on_key_down`: Escape with no mention/slash token open calls `cancel_queue_edit` and stops propagation (7473–7483) |
| lightbox | `preview.is_some()` | lightbox appended; focus moves to `preview_focus` on the frame it opens (ticket 17) |

**Data** — reads `AppState.selected_chat`, `AppState.transcript`,
`AppState.context_usage`, `AppState.connectivity`, `AppState.queue`, review
comments; the `Pickers` entity for the resolved harness/model and checkout plan.

**Web mapping.** `.composer` currently has `max-width: 768px` + `margin: auto`
but **no column gap and `padding: 0`** (app.css:1619–1628). Make it
`display:flex; flex-direction:column; gap: var(--rb-space-sm); padding: 0
var(--rb-space-lg) var(--rb-space-lg);` and move `<ComposerFooter>` and
`<QueuePanel>` inside it (they are siblings today at `chat-page.tsx:253–273`).

---

### 2.2 Failure notice

Rendered when `self.failure` is `Some` **and** `failure_key` is `None` (global)
or equals `current_key` (composer.rs:7311–7315). Chat-scoped failures survive
navigation and only render under their own chat; they are never cleared by a
chat switch.

**Layout**

| property | value | source |
|---|---|---|
| id | `"composer-failure"` | 7383 |
| margin | `mx` 4, `mt` 6 | 7384–7385 |
| layout | flex row, `items_start`, gap 8 | 7386–7389 |
| radius | 12 | 7390 |
| border | 1px | 7391–7392 |
| padding | `px` 12, `py` 8 | 7393–7394 |
| font | `ui_rems(12)`, line-height 16 | 7395–7396 |
| cursor | pointer (click dismisses: sets `failure=None`, `failure_key=None`) | 7398–7403 |

**Palette** — two variants, chosen by `message == "Engine not connected"`:

| variant | border | background | text |
|---|---|---|---|
| offline (amber) | `theme.warning.opacity(0.16)` | `theme.warning.opacity(0.05)` | `theme.warning_muted.opacity(0.9)` |
| failure (red) | `theme.danger.opacity(0.16)` | `theme.danger.opacity(0.05)` | `theme.danger_muted.opacity(0.9)` |

**Children** — `icons::DANGER_TRIANGLE` (`@roboco/icons` `dangerTriangle`) at
14px, `mt` 2, coloured with the variant text colour; then a `min-width:0` div
with the message.

**Text (verbatim, every string that can appear here)**

- `"Engine not connected"` (amber variant)
- `"Engine is offline; reconnecting"`
- `"Update the chat's engine to queue messages during a response."`
- `"Couldn't stage the attachment locally."`
- `"Couldn't upload the attachment — the device may be offline."`
- `"Could not create the chat on its engine: {err}"`
- `"Send failed: {e}"`
- `"Send failed: queue did not return an id"`
- `"Stop failed: {err}"`
- `"Answer failed: {err}"` (ticket 14 raises this one)
- `"The queued message was removed; your edit remains in the composer"`
- `"Remove an Appshot before adding another (96 MB staged Appshot limit)."` (desktop-only; do not port)

---

### 2.3 Queue-degraded caption

Shown when the delivery path is degraded (composer.rs:7319–7345):
- existing chat: `state.chat_delivery_degraded(chat_id)`
- new-chat canvas: the picked target device is remote **AND**
  (`connectivity.state ∈ {Offline, Reconnecting}` OR the target device is not
  online)

**Layout** — wrapped in `motion::fade_in("composer-queue-notice", …)` (500 ms
`EASE_OUT_EXPO`, opacity 0→1 + translateY 4→0). `mx` 8, `mt` 6, flex row,
`items_center`, gap 6, `text_size` 11, line-height 14, colour
`theme.text_faint`. Children: a 5px `rounded_full` dot then a
`min-width:0; text-overflow:ellipsis` label.

**Dot colour** — `theme.warning` when `connectivity.state == Offline`, else
`theme.text_faint`.

**Text (verbatim)**
- offline: `"Offline — messages will send when you're back online."`
- otherwise: `"Messages will send once the connection recovers."`

It is deliberately **not** a warning box — it clears itself the moment the path
heals.

---

### 2.4 Queue tray slot (wrapper only — body is ticket 16)

| property | value | source |
|---|---|---|
| wrapper | `motion::fade_quick("composer-queue", …)` (150 ms `EASE`, opacity) | 7459 |
| margin-x | `QUEUE_SIDE_INSET` (16) | 7462 |
| margin-bottom | `−(Theme::SPACE_SM + QUEUE_COMPOSER_OVERLAP)` = **−26** | 7465 |

The negative bottom margin cancels the column's 8px gap and tucks the tray 18px
behind the pill, which paints after it.

`show_queue_latest_shortcut` = `queue_shortcut_revealed && editing_queued.is_none()
&& !pickers.is_open() && !composer_has_content(...)` (7448–7455).

---

### 2.5 Pill surface (`#composer-surface`)

**Layout** — `div().relative().id("composer-surface")` (7876–7894).

**Children (in order)**
1. `frost::frosted(surface_radius, 16.0, body)` — the pill itself with a
   **16px** backdrop blur masked to `surface_radius`.
2. a canvas absolutely `inset_0` publishing the surface bounds into
   `self.surface_bounds` (read by the new-thread background's cutout mask —
   ticket 15). Web: read the pill's bounding rect.
3. `render_file_mention_popup` (conditional) — ticket 14
4. `render_slash_popup` (conditional) — ticket 14

**`surface_radius`** = `COMPOSER_RADIUS − 4 × dock_amount` = **26** at the hero,
**22** when docked (7603). With no dock frame (this ticket), it is 26.

**Pill chrome** (`pill`, 7700–7717)

| property | value | source |
|---|---|---|
| radius | `surface_radius` | 7711 |
| border | 1px, colour `pill_border` | 7712–7713 |
| `pill_border` (frosted, dark) | `hsla(210/360, 0.18, 0.78, 0.09)` — literal HSL | 7692 |
| `pill_border` (frosted, light) | `hsla(210/360, 0.18, 0.32, 0.10)` — literal HSL | 7693 |
| `pill_border` (opaque) | `theme.border` | 7696 |
| background (frosted) | `theme.composer_sidebar_tint()` — hue-compensated tint at alpha 0.15, keeping 85% of the blurred backdrop visible (theme.rs:937) | 7714 |
| background (opaque) | `theme.input_glass_bg()` = `flatten(theme.input_bg, theme.bg)` + `shadow_lg()` | 7715–7717 |
| overflow | hidden | 7735 / 7802 |
| height | `pill_height` (animated, §2.8) | 7734 / 7801 |

**Interactions** — `on_mouse_down(Left)` anywhere on the pill focuses the input,
**unless** `pickers.is_open()` (open menus keep their own keyboard/search
focus). Padding and action controls are therefore all part of the text
composer's hit area (7701–7710).

**Web today:** `.composer::before` is `color-mix(in srgb, var(--rb-input) 82%,
transparent)` with `backdrop-filter: blur(24px)` (app.css:1652–1654) and
`border: 1px solid var(--rb-border)` (app.css:1651). Blur must be **16**;
background must follow the frost/opaque split; the frosted border colour needs
the new token from ticket 02.

---

### 2.6 Pill body — expanded

`expanded = expanded_mode || new_chat` (7488): the new-thread canvas always
renders expanded (ticket 15). For this ticket, `expanded = expanded_mode`.

**Layout** (7734–7783)

| property | value |
|---|---|
| height | `pill_height` |
| overflow | hidden |
| position | relative |
| direction | flex column |

**Children (in order)**
1. `render_comments_chip` — ticket 23 (height contribution 36 when present)
2. `render_appshot_strip` — desktop-only, skip
3. `render_attachment_strip` — ticket 17
4. **textarea box**: `h(textarea_height)`, `flex_none`, `overflow_hidden`,
   `px` 16, `pt` `text_pt` (= `morph_text_pad(layout_morph_t)`, **12→16**),
   `pb` 4, child = the input.
5. **actions row**: `absolute`, `left_0`, `right_0`, `bottom(-cluster_dy)`,
   `h(ACTIONS_ROW_HEIGHT)` (**46**), flex row `items_center`,
   `gap(ACTION_PRIMARY_GAP)` (**8**), `pl` 12,
   `pr` `morph_cluster_inset(true, layout_morph_t)` (**8→12**), `pt` 4, `pb` 10.
   - child A: `flex_1 min_w_0` row, `items_center`, **`justify_end`**,
     `gap(ACTION_UTILITY_GAP)` (**2**), containing `self.pickers` then `attach`.
   - child B: `send_button`.

`textarea_height` = `pill_height − strip_h − appshot_strip_height −
comment_strip_h − PILL_BORDER_V − ACTIONS_ROW_HEIGHT`, floored at
`INPUT_LINE_HEIGHT + text_pt + 4` when the route is collapsing to a single
line, else 0 (7606–7616).

---

### 2.7 Pill body — compact

**Layout** (7801–7850) — `h(pill_height)`, `overflow_hidden`, flex column,
**`justify_end`** (bottom-justified so the pill top can sweep down over a
stationary row).

**Children (in order)**
1. comments chip, 2. appshot strip, 3. attachment strip (same as expanded)
4. **the single row**: `h(COMPACT_TOTAL_HEIGHT − PILL_BORDER_V)` = **47**, flex
   row `items_center`
   - input holder: `flex_1 min_w_0`, `pl` 16, `pr` 8, `relative`,
     `top(-text_glide)`
   - cluster: `flex_none` row `items_center`, `gap(ACTION_PRIMARY_GAP)` (8),
     `pl` 4, `pr` `morph_cluster_inset(false, layout_morph_t)` (**12→8**),
     `relative`, `top(-cluster_dy)`
     - utility sub-group: `flex_none` row, `gap(ACTION_UTILITY_GAP)` (2):
       pickers, attach
     - send button

`text_glide` = `collapse_text_glide(from, progress)` where `from` is the
morph's start height — see §3.

The 22.75px line centres to the same 12px inset as `py-3`, so the compact input
needs no explicit vertical padding.

**Web today:** the compact layout fakes the reserve with
`.composer-compact .composer-input { padding-right: 200px }` (app.css:1738)
and absolutely positioned actions (app.css:1726–1732). Replace both with the
flex row above.

---

### 2.8 Pill height, auto-grow and the flip

**Auto-grow.** `input_content_height(wrapped_lines) = max(lines, 1) × 22.75`,
then `composer_total_height(content_height) = clamp(content_height + 20, 76,
260) + 46 + 2` (range 124–308). The **textarea box** is what clamps to 76–260;
the **pill** is that plus 46 plus 2. The web currently sizes the *textarea* to
the pill height (`composer.tsx:452, 482`) — that is the bug.

**Height morph.** A second `FlipMorph` is armed whenever
`|target − last_target| > 0.5` so auto-grow is animated, not stepped.

**The flip.** `composer_flip(expanded, text_width, capacity, has_newline,
resizing)` — full rules and tests in §3. `capacity` is the **compact-mode**
input capacity, a layout-stable width: measured directly while compact
(`last_width − 8`) and, while expanded, the learned compact capacity shifted by
the container-width delta (`compact_capacity + (last_width − expanded_anchor)`).
It is never the post-flip measured width, which differs per mode and would feed
back into the decision. Before the first measurement it is `+Infinity` (default
to compact).

Guards: at most one flip per layout pass (`epoch > flip_epoch && last_width >
0`); a committed flip resets `expanded_anchor` and `last_seen_width` so the mode
change's width jump is not read as an interactive resize.

**Available width.** The shell feeds a stable column width clamped to 768 with a
0.5px epsilon (`composer_width_changed`). Web: measure the conversation column
(`state/layout.ts::conversationWidth`) and pass it down; `RESIZE_SETTLE_MS`
(150) is how long after the last width change a collapse is still deferred.

**Scroll / reveal rules.**
- `input_max_scroll(content, viewport) = max(content − viewport, 0)`.
- `input_overflow_edges` returns `(false, false)` when
  `input_max_scroll(content, settled) <= 1.0` — **only settled overflow gets a
  scroll fade**, never the transient overflow while a growing draft animates
  into its box.
- `input_reveal_height` stops the clip at a complete row boundary while
  resizing — never slice glyphs with a moving clip.
- Auto-scroll to caret is measured against the **settled** viewport height, not
  the animating one, so existing text stays fixed relative to the input origin
  throughout a height animation.
- At a scroll boundary, when the input is itself scrollable, the wheel event is
  **swallowed** so it never chains into the transcript — the native equivalent
  of `overscroll-behavior: contain` (composer.rs:2918–2925).
- Manual wheel scrolling sets `follow_cursor = false` until the next caret move
  or edit.

**Motion**

| what | trigger | spec | from → to | reduced motion |
|---|---|---|---|---|
| flip morph (height) | a **committed** mode change | `motion::COLLAPSE` 180 ms `EASE_OUT` = `cubic-bezier(0, 0, 0.58, 1)` | `from` = last rendered height → live target | snap (`flip_morph_step` returns `None`) |
| height morph (auto-grow) | `\|target − last_target\| > 0.5` | same 180 ms `EASE_OUT` | last target → new target | snap |
| cluster right-inset | during a morph | tracks morph progress | expand `8 → 12`; collapse `12 → 8` | snap |
| textarea top padding | during a morph | tracks morph progress | `12 → 16` | snap |
| compact text glide | during a collapse morph | tracks morph progress | `max(from − 53, 0) → 0` | snap |
| cluster dy | during a morph | tracks morph progress | `2.5 → 0` | snap |
| attach hover | pointer enter/leave | `HOVER_FADE` 150 ms `EASE_TAILWIND` | transparent → `rgb(var(--rb-ink) / 0.10)` | instant |
| route snap | a flip within `ROUTE_SNAP_MS` (250) of a nav SNAP | — | no animation; in-flight morphs killed | n/a |

The whole control cluster rides the stationary bottom anchor at **full alpha**
throughout — any fade on the picker chips reads as flicker.

---

### 2.9 The text input — what a `<textarea>` does NOT give for free

The desktop's `ComposerInput` (composer.rs:1630) + `ComposerTextElement` (3356)
is a hand-rolled editor. Most of it maps to a native textarea. These are the
parts that do not, in the order of how much they matter:

**Container**

| property | value | source |
|---|---|---|
| role | multiline text input | 1732 |
| aria-label / aria-placeholder | the placeholder string | 3742–3743 |
| cursor | I-beam | 3746 |
| width | `w_full` | 3796 |
| font-size | 14 (`INPUT_TEXT_SIZE`) | 3797 |
| line-height | 22.75 (`INPUT_LINE_HEIGHT`) | 3798 |
| font-family | `theme.font_sans` | 3800 |
| colour | `theme.text_faint` when content is empty (placeholder), else `theme.text` | 3734–3738 |

**Placeholder text (verbatim)** — default `"Do anything…"` (4198, restored at
5920 / 6810). The two wizard placeholders belong to ticket 14. The web's
`isWorking ? "Steer the live run…" : "Do anything…"` (composer.tsx:475) is
wrong: it is **always** `"Do anything…"`.

**Scroll fade (`paint_bounds`, composer.rs:3119).** The text, chip washes,
selection and caret are masked to a rect that is the element bounds with the
same left and width, **outset upward** by `overflow_top_padding` (0 when
compact, `text_pt` = 12→16 when expanded, composer.rs:7635) but only while the
top edge is actually overflowing, and with height `input_reveal_height(...) +
that padding`. Net effect: text scrolls *under* the pill's top padding and
fades there, instead of being cut at the padding edge. Web: a `mask-image`
linear gradient of `INPUT_FADE_BAND` = **12px** at top and bottom, applied only
when `input_overflow_edges` says that edge is overflowing.

**Caret blink.** 2px wide, `line_height` tall, colour `theme.caret`
(3534–3545). Painted only when the input is focused **and** the window is
active **and** the blink phase is on (3716–3722).
`caret_visible(ms) = (ms / 500) % 2 == 0` where `ms` is time since
`blink_anchor`; `blink_anchor` resets on every edit and every caret move, so
typing bursts never blink. A repaint task at 500 ms cadence runs only while
focused. With an empty field the caret still paints at the content origin.
**Web: this is native and matches** — port `caretVisible` as pure logic only
(for the unit test), do not draw a caret.

**Selection.** `theme.selection` fill; one quad when start/end share a row,
otherwise three (first row from `start.x` to the right edge, a full middle
block, and the last row from x=0 to `end.x`) (3547–3586). Native.

**Press intent** (`press_intent(click_count, shift)`, composer.rs:258)

| clicks | shift | intent | arms drag? |
|---|---|---|---|
| ≥2 | any | `SelectAll` (takes the whole field) | **no** |
| 1 | true | `ExtendSelection` | yes |
| 1 | false | `PlaceCaret` | yes |

Two clicks or more take the whole field, and every further click keeps it. A
select-all must not arm the drag, or the next mouse-move would shrink it back
to a drag from the press position. **Port as pure logic** for the test; the
browser's double-click is word-select, which is an accepted divergence — record
it in Comments.

**Drag selection + autoscroll.** While selecting, pointer moves clamp into the
input bounds and `select_to` that index. If the pointer leaves the box
vertically, a 16 ms (`DRAG_SCROLL_FRAME_MS`) loop scrolls by
`input_drag_scroll_delta(y, top, bottom, line_height) = sign(d) × clamp(|d| ×
0.2, 1.0, line_height)` — distance-proportional, capped at one text row per
frame. During edge autoscroll `follow_cursor` is false (the loop owns the
viewport). **Web: a textarea does not autoscroll on drag past its edge.** Port
the delta function and drive `el.scrollTop` from a `pointermove` listener while
the primary button is down.

**Click-outside.** `on_mouse_down_out` (capture phase): if the left button went
down elsewhere while this input had focus, blur. Capture runs before the
clicked control handles the press, so another input can take focus normally
during bubbling (3786–3792).

**Undo / redo.** `record_edit(range, new_text)` is called with the range about
to be replaced, **before** content changes. A run merges into the current step
only while: same kind (Insert/Insert or Delete/Delete), contiguous with the
previous edit's tail, **single character**, not starting with `\n`, space or
tab, and within `UNDO_COALESCE` (**700 ms**). Deletes merge on
`range.end == last_tail` within the same window. Any fresh edit clears the redo
stack. The undo stack caps at `UNDO_LIMIT` (**200**), dropping the oldest.
`set_text` (draft load, clear-on-submit) **clears both stacks** — a programmatic
replacement is a new document, not an edit. Restoring (undo or redo) sets
`last_edit = None` so the next edit never merges into a step undo just crossed.
Snapshots carry `content`, `selected_range`, `selection_reversed`.
**Web: native textarea undo is an accepted divergence** — do not hand-roll an
undo stack. What you MUST do: when the composer replaces the text
programmatically (draft load, clear on submit), use a full value assignment so
the browser's own undo stack is reset, and document the divergence in Comments.

**Paste.** Clipboard **images** and copied **file paths** beat text and stage as
attachments (`PastedImages` → `stage_clipboard_image`, `PastedPaths` →
`add_paths`). Today the web's `onPaste` sits on the strip wrapper
(`attachment-strip.tsx:190`) and is never reached from the textarea. Put an
`onPaste` on the textarea that inspects `clipboardData.files` /
`clipboardData.items` first and only falls through to text when there is none.
Non-image files are skipped **silently**.

**Ghost / inline completion** — the main composer does not set a ghost (palettes
do). Skip.

**IME** — the browser's textarea owns composition entirely. Skip. (One rule
survives: while `isComposing` is true, do not treat Enter as submit.)

**Layout caching** — GPUI-specific; skip.

**Key-binding contexts.** The desktop has three named contexts. The browser has
none, so the same three scopes become *which element owns the `keydown` handler
and what it calls `preventDefault()` on*:

| desktop context | web equivalent |
|---|---|
| `MESSAGE_COMPOSER_CONTEXT` (`"MessageComposer"`) | the chat composer `<textarea>`'s own `onKeyDown`. It handles `Enter` / `Shift+Enter` / `Mod+Enter` per the `ComposerSendBehavior` setting and `Escape` (dismiss an open mention/slash popup), and calls `preventDefault()` only for the keys it consumes. Every other editing key is native — **do not intercept it**. |
| `GENERIC_COMPOSER_CONTEXT` (`"Composer"`) | the **same** `<textarea>` while the wizard is mounted (ticket 14). |
| `PALETTE_SEARCH_CONTEXT` (`"PaletteSearch"`) | the palette/picker search `<input>` (ticket 10). Its `onKeyDown` must **not** `preventDefault()` bare `ArrowUp`/`ArrowDown`/`ArrowLeft`/`ArrowRight`/`Enter`/`Tab` — let them bubble to the palette container. |

**Enter policy this ticket owns** (`ComposerSendBehavior`, read from the
ticket-03 client settings store, JSON key `composerSendBehavior`, values
`"enter" | "modEnter"`, default **`enter`**):

| behavior | bindings |
|---|---|
| `Enter` (default) | `Enter → Submit`, `Mod+Enter → ModifiedSubmit` |
| `ModEnter` | `Enter → NewlineOrAccept`, `Mod+Enter → ModifiedSubmit` |

`Mod` is Cmd on macOS, Ctrl elsewhere. Exactly two bindings, never extra
modifier variants. `Shift+Enter` is always a newline.

The web today binds **only** Mod+Enter to submit and makes bare Enter a newline
(composer.tsx:387–391) — that is the desktop's *non*-default, and it is wrong
for both settings values.

**Escape.** Escape dismisses an open completion (ticket 14); with the wizard it
pages back (ticket 14); with a queue edit it cancels the edit (§2.1). The web's
current "Escape interrupts a working run when the textarea is empty"
(composer.tsx:392–396) has **no desktop counterpart — delete it**.

**Full editing keymap (desktop, for the porting checklist).** `input_bindings`
(composer.rs:1415) binds the following in both composer contexts:

| keystroke | action |
|---|---|
| `tab` | `MentionTab` — accepts a completion when one is selected, otherwise propagates |
| `shift-enter` | `Newline` |
| `backspace` / `delete` | `Backspace` / `Delete` (grapheme- and chip-aware) |
| `left` / `right` / `up` / `down` | caret motion (Up/Down navigate the completion popup when one has a selection) |
| `shift-{left,right,up,down}` | extend selection |
| `home` / `end` | line start / end |
| `shift-home` / `shift-end` | extend to line edge |
| `cmd-left` / `cmd-right` | line start / end (macOS laptops have no Home/End) |
| `cmd-up` / `cmd-down` | document start / end |
| `shift-cmd-{left,right,up,down}` | the selecting variants |
| `cmd-backspace` / `cmd-delete` | delete to line start / end |
| `cmd-z` and `ctrl-z` | Undo |
| `shift-cmd-z` and `shift-ctrl-z` | Redo |
| `{alt on macOS, ctrl elsewhere}-backspace` / `-delete` | delete word left / right |
| `{alt/ctrl}-left` / `-right` | word left / right |
| `shift-{alt/ctrl}-left` / `-right` | select word left / right |
| `cmd-a` / `ctrl-a` | Select all |
| `cmd-c` / `ctrl-c` | Copy (falls back to the transcript's markdown selection when the input has none) |
| `cmd-x` / `ctrl-x` | Cut |
| `cmd-v` / `ctrl-v` | Paste (images and file paths beat text) |
| `enter` | context-dependent — see the `ComposerSendBehavior` table above |
| `{cmd/ctrl}-enter` | `ModifiedSubmit` — message context only |
| `escape` (raw key listener, not a binding) | dismiss the open completion |

A native textarea gives most of these for free. **Verify** on each platform:
Home/End, Cmd+arrows on macOS, `alt`/`ctrl` word motion,
`cmd-backspace`/`cmd-delete` line deletion. Do not re-implement anything that
already works.

---

### 2.10 Attach button

| property | value | source |
|---|---|---|
| id | `"composer-attach"` | 7656 |
| size | 28 × 28, `flex_none`, centred | 7657–7661 |
| radius | `rounded_full` | 7662 |
| background | `motion::hover_blend("composer-attach", transparent, theme::ink(0.10))` — a 150 ms `EASE_TAILWIND` blend | 7665–7670 |
| icon | `paperclip` at **16px**, `theme.text_muted`, `position:relative; left:1px` (the source path's painted bounds are centred at x=11 in a 24px viewbox) | 7673–7680 |

**Interaction** — click → a native path prompt with `files: true, directories:
false, multiple: true, prompt: "Attach"`. Both **Attach and Cancel** set
`focus_pending = true` so focus returns to the draft. Web: a hidden
`<input type="file" accept="image/*" multiple>`; a cancelled input fires no
event, so restore focus on `window.focus`.

**No disabled state, no tooltip.** The web's current `disabled={!composerReady}`
and `title="Attach files"` both go.

---

### 2.11 Send button (`render_send_button`, 7106)

Three modes from `send_button_mode(run_live, has_text)`:

| `run_live` | `has_text` | mode |
|---|---|---|
| false | any | `Send` |
| true | true | `Queue` |
| true | false | `Stop` |

`has_text` is `composer_has_content(text, attachments + appshots, comments)` — a
staged image or a staged diff comment alone counts as content, so a
comment-only submit during a live run reads as **Queue**, not Stop.
`button_mode` additionally forces `Send` whenever `editing_queued.is_some()`
(6969). `run_live(cx)` = the selected chat's `Indicator` is `Working` or
`AwaitingInput` (5931).

**Stop variant** (7115–7128)

| property | value |
|---|---|
| id | `"composer-stop"` |
| size | 28, `flex_none`, `rounded_full`, `bg theme.text`, centred |
| cursor | pointer; hover `opacity(0.85)` |
| child | an **11 × 11** `rounded(3)` square filled `theme.bg` |
| click | `interrupt_selected` |

**Send / Queue variant** (7129–7154) — **visually identical in both modes**:

| property | value |
|---|---|
| id | `"composer-send"` |
| size | 28, `flex_none`, `rounded_full`, `bg theme.text`, centred |
| icon | `arrowUp` at **14px**, `theme.bg` |
| blocked (`send_blocked`) | `opacity(0.35)`, no cursor change, **no click handler** |
| not blocked | `cursor_pointer`, hover `opacity(0.85)`, click → `on_submit` |

**`send_blocked(cx)`** (5944) is true when **any** of:
1. `queue_edit_finishing`
2. a registry exists and the selected request target is missing or not connected
3. `state.review_comment_flush_pending(&current_key)`
4. no chat is selected (new-chat canvas) **and** `pickers.no_agents_available()`
   — **only fires once the harness catalog has loaded**; offline/loading must
   not block.

Desktop's **Stop is never blocked**.

**There is no label and no tooltip on the desktop send button.** Remove
`aria-label={sendLabel}` text values "Steer" / "Commit & send" / "Release" and
every `title=` on `.composer-send`; keep a static `aria-label` of `"Send"` /
`"Queue"` / `"Stop"` for accessibility only.

---

### 2.12 The send path

`on_submit` → `commit_queue_edit` first; if that handled the submit, return
(ticket 16 owns the lease protocol). Otherwise `send`.

**Run vs. queue.**
- `run_live == false` → a **Run**: `QueueCommand` with a `run` payload whose
  `RunRequest` carries `attachments: string[]` and `worktree: WorktreeSpec | null`
  in addition to the fields the web already sends. The desktop sends
  model/reasoning/options **on the `RunRequest` itself**; only a **new** chat
  writes config, via `Mutate createChat`.
- `run_live == true` **and** there is content → a **Queue**:
  `methods::QUEUE_MESSAGE` with `{ chatId, text, attachments, holdForTurnEnd:
  true }`. The reply's `id` becomes the queue row id; a missing id raises the
  failure `"Send failed: queue did not return an id"`.
- `run_live == true` and there is **no** content → **interrupt** (§2.13).

**Queue capability gate.** Before taking the draft, check `MESSAGE_QUEUE_V1`
(and `MESSAGE_QUEUE_ATTACHMENTS_V1` when the send carries attachments) on
**both** the local engine and the chat's host. On failure, do not send: raise
the failure `"Update the chat's engine to queue messages during a response."`
and leave the draft in place. `QUEUED_ATTACHMENTS_MIN = (0, 2, 12)` is the
minimum engine version that accepts `pending://` refs on a queued send.

**Message id.** Mint **once** and use the same id for the optimistic echo, the
command, and the failure cleanup. `composer-actions.ts:139, 140, 148` currently
calls `messageId()` three times, so the echo key can never match; and
`buildRunRequest`'s `messageId` parameter is accepted and then ignored — drop
the parameter.

**Optimistic echo.** `push_echo` + `begin_pending_send` before the RPC;
refreshed in place after upload; removed + `end_pending_send` on failure.
**Never published for a queued send** — gate on
`should_publish_optimistic_echo(queue) = !queue`. (Ticket 05 supplies the echo
store; this ticket wires the gate.)

**Failure recovery.** On a failed send: the **typed** text (not the
comment-folded prompt) is restored to the draft, attachments are merged back by
id, appshots merged back by id, review comments re-added, a failed **new** chat
is deleted and the route returned to the canvas. The current web behaviour
(`sidebarNotice.set(...)` only, text lost) is not acceptable.

**Prompt folding.** `with_attachments` folds staged image paths into the prompt
as
`"{body}\n\nAttached images (local files — open them to view):\n- {path}…"`,
with `body` → `"See the attached image(s)."` when empty. `lib/attachments.ts`
already has `withAttachments` — **verify the exact strings**.
`with_comments` (folding staged review comments and clearing them at send) is
ticket 23.

**Per-chat drafts.** `drafts: HashMap<chat_key, String>`, swapped on navigation;
`""` is the new-chat canvas key. The web currently just calls `setText("")` on
chat change (composer.tsx:102) — port the map. Staged attachments per chat
already match in shape (`stagedByChat`).

**`ModifiedSubmit` (Mod+Enter).** `modified_submit_target(has_content)` returns
`SubmitContent` when there is content, else `ActivateLatestQueued`. Mod+Enter
with a truly empty composer activates the most recently queued row and
**never** turns into Stop. (`activate_latest_queued` itself is ticket 16;
call it through the queue store.)

**Delete `sendSteer` and `setChatConfig`-on-send.** The composer never steers
(`composer-actions.ts:168–183`), and `maybePersistConfig` running before every
send (`composer-actions.ts:130, 211–221`) is invented.

---

### 2.13 Interrupt

- `interrupting: HashSet<chat_id>` plus per-chat tasks, **idempotent per chat**:
  `begin_interrupt(pending, chat_id)` is a set insert.
- `retain_live_interrupts(pending, is_live)` keeps only chats still
  `Working`/`AwaitingInput` — the set is released only when the chat settles.
- `interrupt_params(chat_id)` = `{"chatId": id, "command": {"kind":
  "interrupt"}}`.
- Failure sets the notice `"Stop failed: {err}"`.

`sendInterrupt` (composer-actions.ts:186) exists but has no tracking. Add it.

---

### 2.14 Session-footer slot

Rendered when `bottom_slot > 0`, where `bottom_slot` = `1.0` if the selected
space has `git_detected` or a dock frame exists, else `session_chrome`
(= `1 − new_thread_chrome`; ticket 15 owns the new-thread half).

**Slot** (7947–7982)

| property | value |
|---|---|
| width | `w_full` |
| height | `SESSION_FOOTER_HEIGHT × bottom_slot` (**24** at rest) |
| margin-top | `−Theme::SPACE_SM × (1 − bottom_slot)` |
| margin-bottom | `−Theme::SPACE_SM × bottom_slot` (**−8** at rest) |
| position | relative |

**Layer A — new-thread git selectors** — ticket 15.

**Layer B — session footer** (when `session_chrome_opacity > 0`): absolute
`inset_0`, `w_full`, `h` 24, flex `items_center`,
`opacity(session_chrome_opacity)`, children:
1. `div().flex_1().min_w_0()` wrapping `pickers.render_footer(cx)`
2. `div().flex_none().pr(10)` wrapping `context_usage::render(usage, state, theme)`

The two layers **never overlap** — `route_chrome_opacities` guarantees at most
one is non-zero (ticket 15).

**`pickers::render_footer` — established-chat branch** (pickers.rs:2571–2660).
Returns **nothing unless the chat's space has `git_detected`** — the footer is
not always rendered. Otherwise a `workspace_footer_row()` (`w_full min_w_0`,
flex row `items_center`, `gap` 4) with `px(10)` and `pr_0`, children in order:

1. left group (`flex_row items_center min_w_0`) — one `footer_label`:
   `folderWithFiles` + **`"Worktree"`** when the chat's cwd differs from the
   space path, else `folder` + **`"Local checkout"`**
2. right group (`flex_row items_center gap 4 min_w_0`) — one `footer_label`:
   `gitBranch` + `chat.branch`, falling back to the literal **`"No ref"`**
3. `div().flex_1().min_w_0()` — the spring
4. change-request badge (when present) — `ChangeRequestBadgeSurface::Composer`

**`footer_label`** (pickers.rs:2399)

| property | value |
|---|---|
| height | 20 |
| max-width | **160** |
| min-width | 0 (labels must shrink; four of these share one row on the draft route) |
| layout | flex row `items_center`, `gap` **6** |
| padding-x | **8** |
| font | `ui_rems(12)`, weight **MEDIUM** |
| colour | `theme.text_muted.opacity(0.6)` |
| icon | 12px, same colour |
| label | `min-width:0; text-overflow:ellipsis` |

`footer_label` has **no radius and no background** — drop
`.footer-chip { border-radius: var(--rb-radius-control) }` (app.css:3340) and
the `"Geist Mono"` family on `.footer-chip-label` (app.css:3353).

The clickable draft-route variant of `render_footer` (Checkout 224-wide and
Branch 320-wide popovers) is ticket 15 + ticket 10.

---

### 2.15 Context-usage ring (`context_usage::render`, :9)

| property | value | source |
|---|---|---|
| container | `id("context-usage")`, `flex_none`, flex `items_center`, `gap` 5, `h` 24, `px` 6, `rounded` 6, `text_size` 11 | context_usage.rs:57–66 |
| hover | `bg theme::ink(0.05)` | :68 |
| ring canvas | `size(16)` | :53 |
| stroke width | 1.8 | :31 |
| radius | 6 (centred in the 16px box) | :36–37 |
| arc start | 12 o'clock (`−π/2`), clockwise | :33–34 |
| segments | `ceil(64 × fraction)`, min 2 | :30 |
| track | `theme.text_faint.opacity(0.25)`, full circle | :21, :49 |
| arc + label colour | `>= 0.9` → `theme.danger`; `>= 0.75` → `theme.warning`; other `Some` → `theme.text_muted`; `None` → `theme.text_faint` | :15–20 |
| label | `format!("{:.0}%", f × 100)`, or the literal **`"—"`** when unknown | :54–56 |

**Tooltip** — a `popover_card` at `w` **260**, `p` 12, flex column `gap` 8,
wrapped in a frosted menu-blur, subscribed to state so it live-updates. Title
**`"Context window"`** (12px, MEDIUM, `theme.text`); body 12px, line-height 19,
`theme.text_muted`:

| case | string |
|---|---|
| tokens + window > 0 | `"{tokens} / {window} tokens\n{window − tokens} tokens remaining"` |
| tokens only | `"{tokens} tokens used\nContext limit not reported"` |
| window > 0 only | `"{window} token capacity\nWaiting for context usage"` |
| neither | `"Context usage not reported by this harness yet"` |

Cite: `../../web-client/research/02-transcript.md` §3.17 (`context_usage`) for
the ring geometry; `04-composer.md` §3.21 for the same values as the composer
sees them. The web's `.context-usage` box metrics already match
(app.css:3358–3384) — **verify** the SVG radius/stroke, the tone thresholds in
`context-usage.tsx`, and the `"—"` label; the tooltip is missing entirely.

---

### 2.16 The harness-catalog latch (bug fix)

`PickerCatalog.loadHarnesses` (`state/picker-catalog.ts`) sets `loading: true`
before the call and only clears it in `then`/`catch`; a call that never settles
latches `loading`, and every later attempt early-returns on
`if (this.#harnesses.loading || this.#harnesses.loaded) return`. The composer
gates on `composerReady = harnesses.loaded`, so the textarea, attach button and
send button stay disabled with no message — `composer.tsx` passes
`harnessError` as `harnesses.loaded ? harnesses.error : null`, which is exactly
`null` on the failure path.

Two fixes, both required:

1. **Break the latch.** `loadHarnesses` must clear `loading` on every exit path
   (a `finally`), and must allow a retry when the previous attempt ended in an
   error rather than in rows.
2. **Stop gating the composer on `loaded`.** Per `send_blocked` condition 4,
   the harness catalog only blocks **the new-chat canvas** and only **after**
   the catalog has loaded and reports no agents. An offline or still-loading
   catalog must leave the textarea, the attach button and the send button
   usable. Delete `composerReady` as a disable source; pass `harnesses.error`
   through unconditionally so ticket 10's retry row can show it.

---

## 3. Pure logic to port

Put these in `web/packages/app/src/lib/composer-flip.ts` and
`web/packages/app/src/lib/composer-send.ts`.

### `composerFlip(expanded, textWidth, capacity, hasNewline, resizing) -> boolean`
(composer.rs:126)

```
if hasNewline                                 -> true   // a newline always expands
if capacity < MIN_COMPACT_INPUT_WIDTH (200)   -> true
if expanded  -> resizing || textWidth >= capacity - COLLAPSE_HYSTERESIS (32)
else         -> textWidth > capacity
```

Assertions: 150/300 stays compact; 320/300 expands; a newline expands in either
mode and even mid-resize; capacity 199 always expands but 200 does not; a width
just over capacity expands and that SAME width while expanded does not
collapse; nothing in `(cap−32, cap]` flips in either direction; `cap−33`
collapses; resizing expands live (`false, 500, 300, resizing`) but defers
collapse (`true, 0, 300, resizing` stays expanded).

### `composerWidthChanged(previous, current) -> boolean` (146)
`previous == null || |current − previous| > 0.5`.
Tests: `null→400` true; `400→400` false; `400→400.5` false; `400→400.51` true.

### `caretVisible(msSinceActivity) -> boolean` (156)
`(ms / 500) % 2 == 0`. Tests: true at 0 and 499; false at 500 and 999; true at 1000.

### `inputContentHeight(wrappedLines) -> number` (161)
`max(lines, 1) * 22.75`. Zero lines still measures one.

### `composerTotalHeight(contentHeight) -> number` (169)
`clamp(contentHeight + 20, 76, 260) + 46 + 2`. Range 124–308.
Tests: one line → exactly 124; four lines → `4*22.75 + 20 + 46 + 2`; 100 lines → 308.

### `inputMaxScroll(content, viewport)` (175) = `max(content − viewport, 0)`

### `inputOverflowEdges(content, settled, visible, scrollTop) -> [top, bottom]` (181)
Returns `[false, false]` when `inputMaxScroll(content, settled) <= 1.0` — only
**settled** overflow gets a scroll fade. Otherwise
`[scrollTop > 1.0, scrollTop < inputMaxScroll(content, visible) − 1.0]`.

### `inputRevealHeight(visible, scroll, lineHeight, resizing)` (196)
When not resizing → `visible`. While resizing, stop at a complete row boundary:
`clamp(floor((scroll + visible + 0.001)/lh)*lh − scroll, 0, visible)`.

### `inputScrollOffset(current, deltaY, content, viewport)` (206)
`clamp(current − deltaY, 0, maxScroll)` — positive deltas scroll toward the start.

### `inputScrollOffsetForCursor(current, cursorTop, cursorH, content, viewport, settled)` (216)
Uses `settled ?? viewport`. If `cursorTop < next` → `next = cursorTop`; else if
`cursorTop + cursorH > next + viewport` → `next = cursorTop + cursorH − viewport`;
then clamp to `[0, maxScroll]`.

### `inputDragScrollDelta(pointerY, top, bottom, lineHeight)` (270)
0 while inside; otherwise `sign(d) * clamp(|d| * 0.2, 1.0, lineHeight)` where
`d` is the signed distance past the nearer edge.

### `pressIntent(clickCount, shift)` (258) — see the §2.9 table.
`armsDrag()` is false only for `SelectAll`.

### `FlipMorph` (330)
Fields: `from` (rendered height at commit), `startMs`, `spec`.
```
raw(now)      = clamp((now - startMs) / spec.totalMs, 0, 1)
progress(now) = spec.progress(raw(now))       // eased
done(now)     = raw(now) >= 1
height(target, now) = lerp(from, target, progress(now))
```
`height` tracks the **live** target — auto-grow may move it mid-morph.
`FlipMorph.collapse` uses `motion::COLLAPSE` (180 ms `EASE_OUT`);
`FlipMorph.newThreadTransition` uses `motion::NEW_THREAD_TRANSITION` (420 ms
`EASE_RESORT`) — that one is ticket 15.

Assertions: starts exactly at `from`, never regresses over 18 samples, lands
exactly on target at 180 ms and stays; `progress(0) == 0`, `progress(180) == 1`.

### `flipMorphStep(morph, modeChanged, lastHeight, nowMs, reducedMotion, routeSnap)` (465)
```
if routeSnap || reducedMotion -> null      // navigation NEVER animates the pill
if !modeChanged -> morph is kept only while !morph.done(nowMs)  // same-mode renders never restart
if lastHeight <= 0.0 -> null               // first paint snaps
else -> FlipMorph.collapse(lastHeight, nowMs)
```
A reverse flip mid-flight starts a new morph FROM the current animated height,
so the handoff is continuous. `routeSnap` is true for `ROUTE_SNAP_MS` (250) after
a nav snap.

### `morphClusterInset(expanded, progress)` (413)
`lerp(8, 12, p)` when expanding, `lerp(12, 8, p)` when collapsing — the morph
starts from the OLD mode's resting inset so there is no sideways step at the
commit. Monotone, bounded by the 4px source delta (`CLUSTER_X_DELTA`).

### `morphTextPad(progress)` (425) — `lerp(12, 16, p)`.

### `collapseTextGlide(from, progress)` (434) — `max(from − 53, 0) * (1 − progress)`.
Committed compact text rests 36px above the pill's outer bottom
(49 − 1 hairline − 12 centering); at the commit instant it sat 17px below the
expanded top. Tests: `(124, 0) == 71`, decays monotonically to 0, and
`(50, 0) == 0` (cannot go negative on shallow reversals).

### `morphClusterDy(progress)` (443) — `2.5 * (1 − progress)` (`CLUSTER_Y_DELTA`).

### `sendButtonMode(runLive, hasText)` (573) — see §2.11.

### `composerHasContent(text, attachments, comments)` (505)
`text.trim() !== "" || attachments > 0 || comments > 0`.
Tests: `"   "` → false; `"hi"` → true; `("", 1, 0)` → true; `("", 0, 1)` → true.

### `modifiedSubmitTarget(hasContent)` (515)
`SubmitContent` when there is content, else `ActivateLatestQueued`.

### `shouldPublishOptimisticEcho(queue)` (583) — `!queue`.

### `beginInterrupt(pending, chatId)` (587) — set insert, idempotent per chat.
### `retainLiveInterrupts(pending, isLive)` (591) — keeps only chats still Working/AwaitingInput.
### `interruptParams(chatId)` (595) — `{"chatId": id, "command": {"kind": "interrupt"}}`.

### `messageEnterBindings(behavior, modifierCombo)` (1364) — see the §2.9 table.
`modifierCombo` = `"cmd-enter"` on macOS, `"ctrl-enter"` elsewhere. Exactly two
bindings, never extra modifier variants.

### `attachmentStripHeight(count, innerWidth)` (296) — needed here for the pill height
```
if count == 0 -> 0
usable  = max(innerWidth - 2*16, 56)
perRow  = max(floor((usable + 8) / (56 + 8)), 1)
rows    = ceil(count / perRow)
height  = 12 + rows*56 + (rows - 1)*8
```
`innerWidth` is `strip_width_hint` = `(lastAvailableWidth ?? 768) − 2*16 − 2`
(composer.rs:7511–7512) — the pill's content width, in **both** modes.
(The strip's own rendering is ticket 17; the height function lives here because
the pill height depends on it.)

### `commentStripHeight(count)` (306) — `0` or `12 + 24 = 36`. (Chip itself is ticket 23.)

### Desktop tests that become web unit tests

Port each of these from `crates/ui/src/composer.rs`'s test module, keeping the
name:

| desktop test | what it pins |
|---|---|
| `flip_decision` (8717) | the four `composer_flip` branches |
| `flip_hysteresis_band_prevents_oscillation` (8731) | nothing in `(cap−32, cap]` flips either way; `cap−33` collapses |
| `resize_expands_live_but_defers_collapse` (8754) | `RESIZE_SETTLE_MS` behaviour |
| `stable_outer_width_only_schedules_reflow_on_real_changes` (8410) | `composer_width_changed` / `COMPOSER_WIDTH_EPSILON` |
| `caret_blink_phase` (8769) | `caret_visible` |
| `auto_grow_math` (8780) | `input_content_height` + `composer_total_height` |
| `input_wheel_scroll_uses_gpui_direction_and_clamps` (8839) | `input_scroll_offset` |
| `input_scroll_reveals_only_when_caret_leaves_viewport` (8851) | `input_scroll_offset_for_cursor` |
| `input_drag_autoscroll_is_edge_proportional_and_capped` (8874) | `input_drag_scroll_delta` |
| `resize_reveals_only_complete_rows` (9086) | `input_reveal_height` |
| `resize_keeps_text_anchored_to_the_input_origin` (9100) | settled-viewport anchoring |
| `scroll_fade_ignores_temporary_resize_overflow` (9126) | `input_overflow_edges` settled rule |
| `scroll_fade_tracks_real_overflow_edges` (9143) | `input_overflow_edges` edges |
| `content_resize_retargets_from_visible_height_and_settles` (9154) | the auto-grow morph retarget |
| `flip_morph_starts_once_per_committed_flip` (8889) | `flip_morph_step` same-mode guard |
| `flip_morph_height_ramps_monotonically_to_target` (9177) | `FlipMorph::height` |
| `flip_morph_reverse_hands_off_from_current_height` (9208) | mid-flight reversal |
| `flip_morph_snaps_for_reduced_motion_and_first_paint` (9224) | the two `None` branches |
| `route_change_never_arms_the_morph` (9232) | `ROUTE_SNAP_MS` |
| `morph_anchoring_holds_controls_and_glides_text` (9257) | `collapse_text_glide` + `morph_cluster_dy` |
| `cluster_inset_glides_between_the_source_endpoints` (9282) | `morph_cluster_inset` |
| `flip_morph_tracks_live_target_and_drives_fade` (9304) | live-target tracking |
| `resolved_layout_does_not_keep_notifying_on_repaint` (8915) | one flip per layout pass |
| `layout_cache_reuses_resize_frames_and_invalidates_text_inputs` (9015) | height-only animation never reshapes |
| `a_press_of_two_or_more_clicks_takes_the_whole_field_and_leaves_the_drag_disarmed` (8312) | `press_intent` |
| `message_enter_bindings_cover_both_platform_modifiers` (8330) | `message_enter_bindings` |
| `message_enter_never_adds_extra_modifier_bindings` (8373) | exactly two bindings |
| `staged_comments_alone_are_content` (9351) | `composer_has_content` |
| `send_button_morph` (9395) | `send_button_mode` |
| `a_comment_only_stage_queues_during_a_live_run` (9379) | Queue, not Stop |
| `modified_submit_sends_content_and_activates_latest_queue_row_when_empty` (9359) | `modified_submit_target` |
| `queued_submit_does_not_publish_an_optimistic_transcript_echo` (9403) | `should_publish_optimistic_echo` |
| `interrupt_tracking_is_idempotent_per_chat` (9409) | `begin_interrupt` |
| `interrupt_tracking_releases_only_settled_chats` (9418) | `retain_live_interrupts` |
| `interrupt_payload_keeps_the_captured_chat` (9426) | `interrupt_params` |

**Not ported here (named so nobody hunts for them):**
`appshot_strip_height_tracks_cards` (8808) and
`appshot_images_share_height_and_adapt_width_without_losing_aspect_ratio`
(8815) cover the desktop-only appshot strip;
`single_line_address_reveals_caret_and_maps_scrolled_pointer` (8954) covers the
palette's single-line `ComposerInput` (ticket 10);
`measured_dock_retargets_without_a_first_frame_jump` is a gpui harness test.
The mention/slash/wizard tests belong to ticket 14; the new-thread and dock
tests to ticket 15.

---

## 4. Gaps this ticket closes

From `04-composer.md` §5, verbatim, filtered to this ticket.

### 4.1 Layout / geometry

| item | kind | desktop | web (file:line) | fix |
|---|---|---|---|---|
| Column wrapper (`max-w 768`, `mx auto`, flex col, gap 8, px 16, pb 16) | PARTIAL | composer.rs:7347–7355 | `.composer` has max-width 768 + margin auto but **no** column gap and `padding: 0` (app.css:1619–1628); the notices/queue/footer are siblings mounted by `chat-page.tsx:253–273` | Make the composer own a column with gap `--rb-space-sm`, `padding: 0 var(--rb-space-lg) var(--rb-space-lg)`; move footer + queue inside it |
| `COMPOSER_RADIUS` 26 | MATCHES | 26 | app.css:1624, 1650 | — |
| Pill 1px hairline | PARTIAL | frosted uses a literal cool-slate HSL (`hsla(210°,0.18,0.78,0.09)` dark / `…0.32,0.10` light); opaque uses `theme.border` | `border: 1px solid var(--rb-border)` (app.css:1651) | Add the frosted-variant border color as a theme role/CSS var |
| Pill background | WRONG VALUE | frosted `theme.composer_sidebar_tint()` (alpha 0.15 hue-compensated); opaque `flatten(input_bg, bg)` + `shadow_lg` | `color-mix(in srgb, var(--rb-input) 82%, transparent)`, blur 24 (app.css:1652–1654) | Blur should be **16** (`frost::frosted(radius, 16.0, …)`, composer.rs:7879); background should follow the frost/opaque split |
| `COMPACT_TOTAL_HEIGHT` 49 | MATCHES | 49 | composer.tsx:29 | — |
| `COMPOSER_MIN/MAX_HEIGHT` 124 / 308 | MATCHES as constants | | composer.tsx:30–31 | but they are applied to the wrong box (see next row) |
| Expanded height formula | WRONG BEHAVIOR | `clamp(content + 20, 76, 260) + 46 + 2` | `heightPx = flipExpanded ? max(124, textarea.scrollHeight) : 49` and the textarea itself gets that height (composer.tsx:452, 482) | The **textarea box** clamps 76–260; the **pill** is that + 46 + 2. The web currently sizes the textarea to the pill height |
| Compact input reserve | INVENTED | flex layout: input `flex_1 min_w_0`, cluster `flex_none` | `.composer-compact .composer-input { padding-right: 200px }` (app.css:1738) and absolutely positioned actions (app.css:1726–1732) | Use a flex row: input `flex:1;min-width:0;padding-left:16px;padding-right:8px`, cluster `flex:none` |
| Compact row height 47 | MATCHES | `COMPACT_TOTAL_HEIGHT − PILL_BORDER_V` | app.css:1731 | — |
| Expanded textarea padding `16 16 4` | MATCHES | `px 16, pt 16, pb 4` | app.css:1691 | — |
| Compact textarea padding `12 16` | MATCHES | | app.css:1679 | — |
| Actions row padding `4 12 10` | MATCHES | `pl 12, pr 12, pt 4, pb 10` | app.css:1714 | — |
| Actions row height 46 | MISSING | `h(ACTIONS_ROW_HEIGHT)` absolute at the pill bottom | no explicit height; row is in flow (app.css:1708–1715) | Set `height: 46px`; position absolute at `left:0;right:0;bottom:0` in expanded mode |
| `ACTION_UTILITY_GAP` | WRONG VALUE | **2** | `--rb-space-xs` = **4** (app.css:1723; the comment even says "= 4") | change to 2px |
| `ACTION_PRIMARY_GAP` 8 | MATCHES | `Theme::SPACE_SM` | `--rb-space-sm` (app.css:1713) | — |
| Utility group `justify-end` | MATCHES | | app.css:1722 | — |
| Compact cluster insets `pl 4 / pr 8` | MATCHES | | app.css:1727 | — |
| `INPUT_LINE_HEIGHT` 22.75 / `INPUT_TEXT_SIZE` 14 | MATCHES | | app.css:1685–1686 | — |
| `@media (max-width:768px) { font-size:16px }` | INVENTED | desktop has no responsive font bump | app.css:2054–2059 | Keep only if the browser zoom issue is real; note it breaks the 22.75px line ratio |
| `@keyframes composer-flip` | INVENTED (dead) | — | app.css:2045–2052; never referenced by any `animation-name` | Delete, or implement the real flip morph |
| `.composer-input-wrap` inline `animationDuration` | INVENTED (dead) | — | composer.tsx:469 | Delete |
| Session footer slot height/margins | PARTIAL | `h 24 × bottom_slot`, `mt −8×(1−slot)`, `mb −8×slot` | `.composer-footer` `min-height: 24; padding: 0 10px var(--rb-space-sm)` (app.css:3316–3326) | Add the negative margins so the slot does not add spacing |
| Queue tray tuck (`mx 16`, `mb −26`) | MISSING | composer.rs:7462–7465 | queue panel is a plain sibling (chat-page.tsx:255) | Port |

### 4.2 Flip / morph / motion

| item | kind | desktop | web | fix |
|---|---|---|---|---|
| Flip trigger | WRONG BEHAVIOR | width-based with hysteresis: `text_width > capacity` to expand, `< capacity − 32` to collapse; newline always expands; capacity < 200 always expands; collapse deferred 150 ms during resize | `wantExpanded = clamp(scrollHeight,49,260) > 50` (composer.tsx:207–211) — a pure height test with no hysteresis, no newline rule, no min-width rule, no resize settling | Port `composer_flip` + `capacity` tracking exactly |
| Flip morph animation | MISSING | 180 ms `EASE_OUT` height morph, restarted only on a committed mode change, tracking the live target | none — the height snaps | Port `FlipMorph` + `flip_morph_step` |
| Height morph (auto-grow) | MISSING | a second `FlipMorph` armed whenever `\|target − last_target\| > 0.5` | none | Port |
| Cluster/text glide (`morph_cluster_inset`, `morph_text_pad`, `morph_cluster_dy`, `collapse_text_glide`) | MISSING | all four | none | Port |
| Route snap (`ROUTE_SNAP_MS` 250) | MISSING | flips within 250 ms of a nav snap, in-flight morphs are killed | `NAVIGATION_MS_THRESHOLD = 250` is declared and **exported but never used** (composer.tsx:35, 543) | Wire it into the morph step |
| Reduced motion | PARTIAL | every morph is skipped, no frames scheduled | `@media (prefers-reduced-motion)` only kills the (dead) wrap animation and the picker popover (app.css:2061–2067) | Gate the ported morphs on `prefers-reduced-motion` |
| Attach hover | WRONG VALUE (in fact MATCHES) | `hover_blend` to `theme::ink(0.10)` over 150 ms `EASE_TAILWIND` | `rgb(var(--rb-ink) / 0.1)` over `--rb-motion-hover-fade` `--rb-ease-ease-tailwind` (app.css:1755–1760) | no change |
| Send hover/disabled | MATCHES | hover `opacity 0.85`, blocked `opacity 0.35` | app.css:2018–2025 | — |

### 4.3 Behavior / data

| item | kind | desktop | web | fix |
|---|---|---|---|---|
| Send-button modes | WRONG BEHAVIOR | `Send` / `Queue` / `Stop`, all visually identical except Stop | `Send` / `Steer` / `Stop` with different labels and a `supportsSteering` branch (composer.tsx:400–416) | Remove Steer entirely; live run + content ⇒ **Queue** |
| Steer RPC | INVENTED | the composer never steers; a busy chat gets `methods::QUEUE_MESSAGE` with `holdForTurnEnd: true` | `sendSteer` → `QueueCommand {kind:"steer"}` (composer-actions.ts:168–183) | Replace with `QueueMessage` |
| Queue send params | MISSING | `{chatId, text, attachments, holdForTurnEnd: true}`; reply's `id` becomes the queue row id; a missing id is the error `"Send failed: queue did not return an id"` | — | Port |
| Queue capability gate | MISSING | `MESSAGE_QUEUE_V1` / `MESSAGE_QUEUE_ATTACHMENTS_V1` checked on both the local engine and the chat's host before taking the draft; failure message `"Update the chat's engine to queue messages during a response."` | — | Port |
| `setChatConfig` on send | INVENTED | desktop sends model/reasoning/options on the `RunRequest` itself; only a **new** chat writes config, via `Mutate createChat` | `maybePersistConfig` mutates before every send (composer-actions.ts:130, 211–221) | Remove |
| `RunRequest` fields | MISSING | `attachments: Vec<String>` and `worktree: Option<WorktreeSpec>` | `buildRunRequest` omits both (composer-actions.ts:46–64) | Add |
| message id | WRONG BEHAVIOR (bug) | one `message_id` used for the echo, the command, and the failure cleanup | `messageId()` is called **three times**, minting three different UUIDs (composer-actions.ts:139, 140, 148); `buildRunRequest`'s `messageId` parameter is accepted and then ignored | Mint once; drop the unused parameter |
| Optimistic echo | MISSING | `push_echo` + `begin_pending_send` before the RPC; refreshed in place after upload; removed + `end_pending_send` on failure; **never** published for a queued send | none | Port; gate on `should_publish_optimistic_echo(queue)` |
| Failure recovery | MISSING | prompt restored to the draft (the *typed* text, not the comment-folded prompt), attachments merged back by id, appshots merged back by id, review comments re-added, a failed new chat deleted and the route returned to the canvas | `sidebarNotice.set(...)` only; the text is lost | Port |
| Per-chat drafts | MISSING | `drafts: HashMap<chat_key, String>` swapped on navigation; `""` is the new-chat canvas key | `setText("")` on chat change (composer.tsx:102) | Port |
| Per-chat staged attachments | MATCHES (shape) | `attachments: HashMap<chat_key, Vec<StagedAttachment>>` | `stagedByChat` (composer.tsx:88) | — |
| `with_attachments` prompt fold | PARTIAL | `"{body}\n\nAttached images (local files — open them to view):\n- {path}…"`, body → `"See the attached image(s)."` when empty | `withAttachments` exists in `lib/attachments.ts` | verify the exact strings |
| Interrupt | PARTIAL | `interrupting: HashSet<chat_id>` + per-chat tasks; idempotent; released only when the chat settles; failure sets `"Stop failed: {err}"` | `sendInterrupt` with no tracking (composer-actions.ts:186) | Port the tracking |
| `send_blocked` | WRONG | four conditions (§2.11) | `sendDisabled = busy \|\| !composerReady \|\| (empty && no staged && !isWorking)` (composer.tsx:417–421); note the trailing `&& false` clause is dead code | Port `send_blocked`; note desktop's Stop is **never** blocked |
| Placeholder | WRONG | always `"Do anything…"` (or the two wizard strings) | `isWorking ? "Steer the live run…" : "Do anything…"` (composer.tsx:475) | Always `"Do anything…"` |
| Enter behavior | WRONG | default `ComposerSendBehavior::Enter`: bare Enter submits, Shift+Enter newline, Mod+Enter `ModifiedSubmit`. `ModEnter` setting swaps Enter to newline | only Mod+Enter submits; bare Enter is a newline (composer.tsx:387–391) | Port both behaviors + the setting |
| `ModifiedSubmit` with an empty composer | MISSING | activates the most recently queued row (`activate_latest_queued`), never Stop | — | Port |
| Escape | WRONG | Escape dismisses an open completion; with the wizard it pages back; with a queue edit it cancels the edit | Escape interrupts a working run when the textarea is empty (composer.tsx:392–396) — desktop has no such binding | Remove; port the real Escape layering |
| Full text-editing keymap | MISSING | ~40 bindings (§2.9) | native textarea only | Native textarea gives most of these for free; verify Home/End, Cmd+arrows on macOS, `alt/ctrl` word motion, `cmd-backspace`/`cmd-delete` line deletion |
| Key-binding **contexts** | MISSING | three named contexts, each with a different Enter/navigation-key policy | one `onKeyDown` on the textarea with a single fixed policy (composer.tsx:381–397); picker search inputs have no let-through rule | Give the textarea two modes (message vs wizard) and make every picker/palette search input let bare arrows, Enter and Tab bubble to its list frame |
| Undo coalescing | MISSING | 700 ms merge window, 200-step cap, cleared on `set_text` | native textarea undo | Acceptable divergence; document it |
| Caret blink | MATCHES (native) | 500 ms, solid while typing, hidden when the window is inactive | native | — |
| Paste of image data / file paths | PARTIAL | clipboard images and copied file paths beat text and stage as attachments | `onPaste` on the strip wrapper (attachment-strip.tsx:190) — never reached from the textarea | Forward the textarea's paste into the strip |
| Failure notice | MISSING | §2.2 | `sidebarNotice` toast | Port |
| Queue-degraded caption | MISSING | §2.3 | nothing | Port |
| `set_available_width` / capacity tracking | MISSING | the shell feeds a stable column width clamped to 768, with a 0.5px epsilon | nothing | Port |

### 4.5 Footer / context usage

| item | kind | desktop | web (file:line) | fix |
|---|---|---|---|---|
| Footer visibility | WRONG | `render_footer` returns `None` unless the chat's space has `git_detected`; only the usage indicator remains | always rendered (composer-footer.tsx:26) | Gate on git |
| Checkout-kind label | MISSING | `"Worktree"` / `"Local checkout"` with `FOLDER_WITH_FILES` / `FOLDER` | — | Add |
| Branch fallback | MISSING | literal `"No ref"` | branch chip hidden when null (composer-footer.tsx:27) | Add |
| Footer chip padding-x | WRONG VALUE | 8 | 6 (app.css:3341) | 8 |
| Footer chip gap | WRONG VALUE | 6 | 4 (app.css:3338) | 6 |
| Footer chip font size | WRONG VALUE | `ui_rems(12)`, weight MEDIUM | 11px, default weight (app.css:3343) | 12px / 500 |
| Footer chip color | WRONG VALUE | `theme.text_muted.opacity(0.6)` | `var(--rb-text-faint)` (app.css:3342) | `color-mix(in srgb, var(--rb-text-muted) 60%, transparent)` |
| Footer chip max-width | WRONG VALUE | 160 | 220 (app.css:3349) | 160 |
| Footer chip font family | WRONG | sans (no mono anywhere in the footer) | `"Geist Mono"` on the label (app.css:3353) | remove |
| Footer chip radius/border | INVENTED | `footer_label` has **no** radius and no background | `border-radius: var(--rb-radius-control)` (app.css:3340) | harmless but non-parity; drop |
| Footer icon size | MATCHES | 12 | `size={12}` (composer-footer.tsx:29) | — |
| Footer ordering | WRONG | checkout-kind, branch, spring, CR badge, usage | branch, spring, CR badge, usage | insert the checkout-kind label first |
| Context usage h/px/gap/font | MATCHES | 24 / 6 / 5 / 11 | app.css:3358–3365 | — |
| Context usage radius | MATCHES | 6 | `--rb-radius-control` = 6 | — |
| Context usage hover | MATCHES | `ink(0.05)` | app.css:3372 | — |
| Context usage tones | MATCHES | danger ≥ 0.9, warning ≥ 0.75, else `text_muted`; none → `text_faint` | `data-tone` attributes (app.css:3374–3384) | verify the thresholds in `context-usage.tsx` |
| Context usage ring geometry | VERIFY | 16px box, radius 6, stroke 1.8, start at −90°, track `text_faint@0.25` | `.context-usage-track` / `-arc` (app.css:3386–3392) | check the SVG radius/stroke |
| Context usage tooltip | LIKELY MISSING | 260px card with the token breakdown (§2.15) | — | Port |
| `"—"` label with no usage | VERIFY | literal em-dash | — | check |

### 4.6 Shipped bug

| item | kind | desktop | web | fix |
|---|---|---|---|---|
| Harness-catalog latch | BUG | `send_blocked` cond. 4 only fires once the catalog has loaded; offline/loading never blocks | `PickerCatalog.loadHarnesses` latches `loading` on a call that never settles; `composerReady = harnesses.loaded` then disables the whole composer forever, silently (`parity-checklist.md` "Defect found while comparing") | Clear `loading` in a `finally`, allow retry after an error, and stop gating the composer on `loaded` (§2.16) |

---

## 5. Do not

- **Do not re-add "Steer".** No `sendSteer`, no `supportsSteering` branch, no
  `steeringMode` data attribute, no "Steer the live run…" placeholder, no
  "Steer" label or tooltip. Decision 3 in `spec.md`: steer-now does not exist
  on web.
- **Do not re-add `setChatConfig` on send** (`maybePersistConfig`).
- **Do not re-add** `@keyframes composer-flip`, the
  `.composer-input-wrap` inline `animationDuration`, or the mobile font bump —
  all invented/dead.
- **Do not add** a label or a tooltip to `.composer-send`. The desktop has
  neither.
- **Do not attempt** the appshot strip, `frost::frosted`/`frost::layered`,
  `ROBOCO_ATTACH` / `ROBOCO_MOTION_SCALE` env knobs, the IME
  `EntityInputHandler`, or `gpui::canvas` surface-bounds publication —
  desktop-only (`04-composer.md` §6).
- **Do not hand-roll undo/redo.** The 700 ms coalescing window and the 200-step
  cap are an accepted divergence; the native textarea stack stands. Record it in
  Comments.
- **Do not build** the mention popup, the slash popup or the wizard — ticket 14.
- **Do not build** the new-thread canvas, the floating selector row, the
  new-thread git selectors in the footer slot, the background hero, or the
  composer dock — ticket 15.
- **Do not build** the queue panel body, the queue-edit lease protocol, or
  `activate_latest_queued` itself — ticket 16. (Wire the call sites only.)
- **Do not build** the attachment strip's thumbnails, the lightbox, or the drop
  overlay — ticket 17. (This ticket owns only `attachmentStripHeight`, because
  the pill height depends on it.)
- **Do not build** the comments chip or `with_comments` folding — ticket 23.
  (This ticket owns only `commentStripHeight` and the "comments count as
  content" rule.)
- **Do not build** the picker chips or their popovers — ticket 10. This ticket
  owns only their placement geometry (2px gap, `justify-end`, 32px chip height).
- **Do not touch** the phone layer (drawer sidebar, docked composer at phone
  widths). Decision 5: phone widths are out of scope.

---

## 6. Acceptance

- [ ] The composer is a flex column, max-width 768, `gap: var(--rb-space-sm)`,
      `padding: 0 var(--rb-space-lg) var(--rb-space-lg)`, and the queue tray and
      session footer are its children, not siblings.
- [ ] The pill is radius 26, 1px hairline, `backdrop-filter: blur(16px)`, and
      uses the frost/opaque background split from ticket 02.
- [ ] Expanded: the **textarea box** clamps 76–260 and the **pill** is that + 46
      + 2 (124–308). The actions row is `position:absolute; left:0; right:0;
      bottom:0; height:46px`.
- [ ] Compact: one 47px flex row — input `flex:1; min-width:0; padding-left:16px;
      padding-right:8px`, cluster `flex:none`. No `padding-right: 200px`.
- [ ] `ACTION_UTILITY_GAP` is **2px**, `ACTION_PRIMARY_GAP` is 8px, the utility
      group is `justify-content:flex-end`.
- [ ] Typing past the compact capacity expands; deleting back into the
      `(cap−32, cap]` band does **not** collapse; a newline expands immediately;
      a column narrower than 200px stays expanded; dragging the sidebar expands
      live but defers a collapse for 150 ms.
- [ ] The flip and the auto-grow both animate over 180 ms `cubic-bezier(0, 0,
      0.58, 1)`; the cluster stays pinned at full alpha while the text glides;
      a reverse flip mid-flight hands off from the current height.
- [ ] `prefers-reduced-motion: reduce` snaps every morph with no frames
      scheduled.
- [ ] Send button: 28px circle, `background: var(--rb-text)`, 14px arrow in
      `var(--rb-bg)`; hover 0.85; blocked 0.35 **with no click handler**; Stop
      is an 11px `border-radius:3px` square in `var(--rb-bg)`. No label, no
      tooltip.
- [ ] During a live run with content, the button reads Queue and the send calls
      `QueueMessage` with `holdForTurnEnd: true`; with no content it reads Stop
      and interrupts. A staged image or a staged comment alone reads Queue.
- [ ] The queue capability gate blocks the send with
      `"Update the chat's engine to queue messages during a response."` instead
      of firing a new Run.
- [ ] `sendRun` mints one message id, drops `maybePersistConfig`, and puts
      `attachments` and `worktree` on the `RunRequest`. `sendSteer` is gone.
- [ ] A failed send restores the typed text and the staged attachments;
      the failure notice renders with the correct amber/red variant and
      dismisses on click.
- [ ] Bare Enter sends under the default `composerSendBehavior: "enter"`;
      `Shift+Enter` is always a newline; `Mod+Enter` submits, and with an empty
      composer activates the most recently queued row. Switching the setting to
      `"modEnter"` makes bare Enter a newline. Escape no longer interrupts.
- [ ] Placeholder is always `"Do anything…"`.
- [ ] Per-chat drafts survive navigation; `""` keys the new-chat canvas.
- [ ] Pasting an image into the textarea stages it as an attachment instead of
      pasting a filename; non-image files are skipped silently.
- [ ] Footer renders only when the chat's space has git; order is
      checkout-kind, branch (`"No ref"` fallback), spring, CR badge, usage; chip
      metrics are gap 6, px 8, 12px/500, max-width 160, colour
      `text_muted @ 60%`, no radius, no mono.
- [ ] Context ring: 16px box, r=6, stroke 1.8, from 12 o'clock clockwise, track
      at `text_faint @ 25%`, tones at 0.9 / 0.75, `"—"` with no usage, and the
      260px tooltip with the four verbatim bodies.
- [ ] Killing the harness-catalog request no longer disables the composer: the
      textarea, paperclip and send stay usable and the error surfaces.
- [ ] Unit tests (each named after the desktop test it mirrors, in
      `web/packages/app/tests/composer-flip.test.ts` and
      `composer-send.test.ts`): `flip_decision`,
      `flip_hysteresis_band_prevents_oscillation`,
      `resize_expands_live_but_defers_collapse`,
      `stable_outer_width_only_schedules_reflow_on_real_changes`,
      `caret_blink_phase`, `auto_grow_math`,
      `input_wheel_scroll_uses_gpui_direction_and_clamps`,
      `input_scroll_reveals_only_when_caret_leaves_viewport`,
      `input_drag_autoscroll_is_edge_proportional_and_capped`,
      `resize_reveals_only_complete_rows`,
      `resize_keeps_text_anchored_to_the_input_origin`,
      `scroll_fade_ignores_temporary_resize_overflow`,
      `scroll_fade_tracks_real_overflow_edges`,
      `content_resize_retargets_from_visible_height_and_settles`,
      `flip_morph_starts_once_per_committed_flip`,
      `flip_morph_height_ramps_monotonically_to_target`,
      `flip_morph_reverse_hands_off_from_current_height`,
      `flip_morph_snaps_for_reduced_motion_and_first_paint`,
      `route_change_never_arms_the_morph`,
      `morph_anchoring_holds_controls_and_glides_text`,
      `cluster_inset_glides_between_the_source_endpoints`,
      `flip_morph_tracks_live_target_and_drives_fade`,
      `resolved_layout_does_not_keep_notifying_on_repaint`,
      `layout_cache_reuses_resize_frames_and_invalidates_text_inputs`,
      `a_press_of_two_or_more_clicks_takes_the_whole_field_and_leaves_the_drag_disarmed`,
      `message_enter_bindings_cover_both_platform_modifiers`,
      `message_enter_never_adds_extra_modifier_bindings`,
      `staged_comments_alone_are_content`, `send_button_morph`,
      `a_comment_only_stage_queues_during_a_live_run`,
      `modified_submit_sends_content_and_activates_latest_queue_row_when_empty`,
      `queued_submit_does_not_publish_an_optimistic_transcript_echo`,
      `interrupt_tracking_is_idempotent_per_chat`,
      `interrupt_tracking_releases_only_settled_chats`,
      `interrupt_payload_keeps_the_captured_chat`.
- [ ] Screenshot pair, desktop vs web, states: (a) established chat, empty
      composer, compact; (b) same chat, four-line draft, expanded; (c) live run
      with text staged — send button in Queue mode; (d) live run, empty composer
      — Stop button; (e) failure notice visible (red variant); (f) footer with
      "Worktree" + branch + context ring at a warning fraction.
- [ ] `pnpm -r build` green; `web/packages/app` vitest green.
- [ ] No new literal hex/px where a `--rb-*` token exists.

## Comments

### Research addendum (2026-09-17)

Deep-dive notes: `.scratch/web-parity/research-2026-09-17/hero-context-meter.md`
(Part 2). Two requirements the ticket states nowhere, both required for
desktop parity of the §2.15 context-meter card:

- **Tooltip trigger delay** — the desktop shows the card after a **500 ms
  hover** (gpui `DEFAULT_TOOLTIP_SHOW_DELAY`, zui `div.rs:49`). The web
  must match ~500 ms, not the browser's native title timing.
- **Remove the native `title=`** — the current stand-in at
  `context-usage.tsx:35` must be explicitly deleted when the card lands,
  or both tooltips show at once.

Also explicit: MENU_BLUR is **44** (frost.rs:23), not the pill's 16, and the
open card live-updates by observing usage state (context_usage.rs:71-77) — a
store subscription inside the open tooltip, not a re-mount.
