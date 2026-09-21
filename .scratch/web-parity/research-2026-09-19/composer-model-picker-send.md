# Composer + model picker + send path — root-cause research (2026-09-19)

Branch `web-parity/wave-1` @ `bc3a3945` (2026-09-19). Read-only investigation;
no servers, no builds. Everything below is code analysis against HEAD, with
prior research (`research-2026-09-17/composer-popover-issues.md`) treated as
still-accurate background (the crash it root-caused is fixed at HEAD by the
ticket-13 rewrite; nothing here contradicts it).

Surfaces: the composer pill (`components/composer.tsx` + the composer CSS
block in `styles/app.css`), the model picker chip + identity card
(`components/composer-pickers.tsx`), the picker catalog
(`state/picker-catalog.ts`), and the send/create-chat path
(`lib/composer-actions.ts`, `lib/chat-actions.ts`). Desktop reference:
`crates/ui/src/composer.rs`, `crates/ui/src/pickers.rs`.

---

## S1 — "the composer, the model picker, and everything on the same line, isn't pushed to the bottom of the composer when it's large"

### (a) Web mechanism trace

The pill is one DOM shape for both modes (`composer.tsx:2698-2706`):

```
<div class="composer-pill" data-mode=…  style="height: <layout.pillHeight>px">
  <CommentsChip/> <AttachmentStrip/>
  <div class="composer-body">            ← composer.tsx:2716
    <div class="composer-input-box" style="height: <layout.boxHeight>px; …">  ← 2717-2726
    <div class="composer-actions" style="bottom: -clusterDy; …">            ← 2727-2733
  </div>
  <div class="composer-input-measure"/>  ← 2778-2780 (absolute, out of flow)
</div>
```

CSS:

- `.composer-pill` — `position: relative; display: flex; flex-direction:
  column; overflow: hidden;` (app.css:3161-3172), height inline
  `layout.pillHeight` (composer.tsx:2701-2704).
- `.composer-pill[data-mode="expanded"] .composer-body` — `display: flex;
  flex-direction: column; position: relative` (app.css:3188-3192). **No
  `flex: 1`, no height** — `.composer-body` (app.css:3194-3196) is only
  `min-width: 0`. In a column flex container the default `align-items:
  stretch` widens items, it does not grow them along the main axis, so the
  body's height is its **content** height = the input box's inline height.
- `.composer-input-box` (expanded) — inline `height: layout.boxHeight`,
  `paddingTop: layout.textPad` (composer.tsx:2720-2722; CSS app.css:3208-3213).
- `.composer-pill[data-mode="expanded"] .composer-actions` —
  `position: absolute; left: 0; right: 0; height: 46px; padding: 4px 8px 10px
  12px` (app.css:3298-3304) with inline `bottom: -layout.clusterDy`
  (composer.tsx:2729-2732). **Its containing block is `.composer-body`**
  (nearest positioned ancestor, app.css:3191), not the pill.
- `layout.boxHeight` = `max(pillHeight − stripH − PILL_BORDER_V −
  ACTIONS_ROW_HEIGHT, 0)` (composer.tsx:833), i.e. exactly `pillHeight −
  stripH − 48` with no strips.

Numeric walk for a 4-line draft (no attachments): contentHeight = 4 × 22.75 =
91 → `composerTotalHeight` = clamp(91 + 20, 76, 260) + 46 + 2 = **159**
(composer.tsx:777-784; `composerTotalHeight` in lib/composer-flip.ts:187).
`boxHeight` = 159 − 0 − 2 − 46 = **111**. So:

- The pill's content box is 157px tall.
- The in-flow content (body = input box) is 111px; the leftover **46px sits
  as blank space at the pill's bottom** (flex-start default).
- The absolute actions row anchors to the body's bottom edge (y = 111) and is
  46px tall, so it spans **[65, 111]** — hanging below the input box is
  impossible; it *overlaps the input box's bottom 46px* (text rows 3–4 of the
  4-line draft live at [61.5, 107] inside that box).

Result, exactly as the user describes: the model-picker/paperclip/send
cluster renders **on the same line as the text** (overlapping the last text
rows), and the composer's bottom 46px is empty. The taller the composer, the
more obvious ("isn't pushed to the bottom of the composer when it's large").
At the minimum expanded height (124, the always-expanded new-thread canvas:
`newChat` at composer.tsx:446, `expandedRender = expanded || newChat` at
2618) the same 46px blank band exists under the row.

### (b) Desktop reference spec

The desktop's expanded body **is the pill itself** — there is no nested body
wrapper (`composer.rs:7724-7783`):

| property | value | source |
|---|---|---|
| expanded container | `pill.h(px(pill_height)).overflow_hidden().relative().flex().flex_col()` — the pill, full height, is the positioning context | composer.rs:7734-7738 |
| children (in order) | comments chip, appshot strip, attachment strip, textarea box (`h(textarea_height)` in flow, `px 16 pt text_pt pb 4`), **actions row** | 7739-7751, 7752-7783 |
| actions row | `absolute().left_0().right_0().bottom(px(-cluster_dy)).h(px(ACTIONS_ROW_HEIGHT))` — anchored to the **pill's bottom edge** | 7752-7758 |
| actions row height | `ACTIONS_ROW_HEIGHT = 46` (`pt 4` + 32px chips + `pb 10`) | composer.rs:58, 7758, 7768-7769 |
| actions row layout | flex row, `items_center`, `gap(ACTION_PRIMARY_GAP)` 8, `pl 12`, `pr morph_cluster_inset(true, t)` 8→12, `pt 4`, `pb 10` | 7759-7769 |
| utility group | `flex_1 min_w_0`, `justify_end`, `gap(ACTION_UTILITY_GAP)` 2 — pickers then attach | 7770-7781 |
| textarea box height | `textarea_height = pill_height − strip_h − appshot − comments − PILL_BORDER_V − ACTIONS_ROW_HEIGHT`, floored at `INPUT_LINE_HEIGHT + text_pt + 4` while collapsing | 7744; formula per ticket 13 §2.6 (composer.rs:7606-7616) |
| pill heights | `COMPOSER_MIN_HEIGHT 124` / `COMPOSER_MAX_HEIGHT 308` / `COMPACT_TOTAL_HEIGHT 49` | composer.rs:65-66, 70 |
| constant bottom edge | "The pill's bottom edge is stationary on screen … so the controls pin to the bottom and only the text glides with the reveal … none of them fade" | comment, composer.rs:7718-7722 |

Compact body (`composer.rs:7801-7850`): `pill.h(pill_height).
overflow_hidden().flex().flex_col().**justify_end()**` with one
`h(COMPACT_TOTAL_HEIGHT − PILL_BORDER_V)` = **47px** row (`flex_row
items_center`): input holder `flex_1 min_w_0 pl 16 pr 8 relative
top(-text_glide)`, cluster `flex_none … pl 4 pr morph_cluster_inset(false, t)
12→8 relative top(-cluster_dy)`. `justify_end` pins the row to the pill's
bottom so during the collapse morph "the pill top sweeps down over a
stationary row" (comment, composer.rs:7788-7792).

**The composer height/growth state machine** (for S1/S5, all values
verified):

| state | condition | what changes |
|---|---|---|
| compact | `composer_flip` false | pill 49; one 47px row (input + cluster inline); body bottom-justified |
| expand decision | `text_width > capacity` (compact) / `has_newline` always / `capacity < 200` always / `resizing` keeps expanded | composer.rs:126-144 |
| collapse decision | `text_width < capacity − 32` (COLLAPSE_HYSTERESIS), deferred `RESIZE_SETTLE_MS` 150 during resize | composer.rs:111-115, 126-144 |
| expanded | flip committed | pill = `clamp(content + 20, 76, 260) + 46 + 2` (124–308); actions row absolute at pill bottom; textarea box in flow above it |
| auto-grow morph | `|target − last_target| > 0.5` | 180ms COLLAPSE/EASE_OUT height morph, retargeting mid-flight (composer.rs:169-173 area; ported at lib/composer-flip.ts:187, 470) |
| flip morph | committed mode change only | 180ms; cluster dy 2.5→0, insets 8↔12, text pad 12→16, compact text glide (composer.rs:394-455) |
| new-thread canvas | `expanded_mode \|\| new_chat` | always expanded; mode flips never commit (composer.rs:7488 per ticket 15; web composer.tsx:754-758) |
| dock frame active | shell installs `set_dock_frame` | shared clock owns height (`dock_height`), both morphs killed (composer.rs:4140-4149) |
| wizard mounted | `wizard.is_some()` | the pill is replaced by the question panel in place (composer.rs:7440-7443 per ticket 14) |

### (c) Root cause

The web inserted a `.composer-body` wrapper between the pill and the
input-box/actions and **never stretched it** (app.css:3188-3196 has no
`flex-grow`/height), so the absolutely-positioned actions row anchors to the
input-box-sized body instead of the full-height pill. The desktop has no such
wrapper — the pill *is* the body (composer.rs:7734-7738). Secondary: the
compact body is top-anchored in the pill (no `justify-end`), breaking the
collapse sweep's stationary-row contract.

### (d) Gap rows

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
|---|---|---|---|---|
| Expanded actions-row containing block | WRONG | the pill, full `pill_height` (composer.rs:7734-7758) | `.composer-body`, content-sized = input box (composer.tsx:2716; app.css:3188-3196, 3298-3304) | Add `flex: 1 1 auto; min-height: 0` to `.composer-pill[data-mode="expanded"] .composer-body` (or drop the wrapper and make input-box/actions direct pill children like the desktop) |
| Pill bottom blank band | WRONG | no leftover — actions row fills the pill's bottom 46px (7744+7758 arithmetic) | 46px empty strip under the actions row in every expanded state (boxHeight formula composer.tsx:833) | falls out of the row above |
| Actions row overlaps text | WRONG | textarea box ends where the actions row begins (7744 vs 7752-7758) | row spans `[boxHeight−46, boxHeight]` over the input box's last 46px (composer.tsx:2727-2733 + 833) | falls out of the first row |
| Compact row anchoring | WRONG | pill `justify_end()` — row pinned to the bottom; top sweeps down over it (composer.rs:7801-7805, 7788-7792) | `.composer-pill[data-mode="compact"] .composer-body { height: 47px; flex: none }` top-anchored, no justify-end (app.css:3180-3186) | `justify-content: flex-end` on the compact pill (or `margin-top: auto` on the body) |

---

## S2 — "the model picker always show loading… when i refresh the page, it shows them correctly now"

### (a) Web mechanism trace

The chip's loading slots (`components/composer-pickers.tsx`):

- `iconLoading = !harnesses.loaded && chatConfig === null &&
  defaults.harness === null && !noAgents` (composer-pickers.tsx:210).
- `labelLoading = draft.model !== null && modelLabel === draft.model &&
  rememberedLabel === null && (!harnesses.loaded || modelsList.loading)`
  (composer-pickers.tsx:205-209).
- `noAgents = harnesses.loaded && harnesses.error === null &&
  offered.length === 0` (composer-pickers.tsx:117).

`!harnesses.loaded` is true for the catalog's **Idle, Loading AND Error**
slots (`LoadableList` at picker-catalog.ts:45-53: `loaded` is only set by
`listWithRows`, never by `listWithError` at picker-catalog.ts:75-77). So the
moment the harness slot carries an error, the chip renders the glyph spinner
+ skeleton bar **forever** — an errored catalog is visually
indistinguishable from a loading one.

The card-level takeover is error-aware (error → ErrorRow, composer-pickers
tsx:586-592) but the loading takeover is not gated on a in-flight fetch: `if
(!harnesses.loaded && harnessError === null) return SkeletonMenuRows`
(composer-pickers.tsx:577-585) — an **Idle** slot also shows skeletons
forever if nothing kicks it.

Who kicks the catalog, and when:

1. `ConversationPage` effect: `void session.catalog.loadHarnesses()` on
   `[session, chatId]` (chat-page.tsx:117-124) — once per page mount /
   session or chat change. Non-forced: `shouldLoad = !loaded || error !==
   null || force` (picker-catalog.ts:203).
2. Card open: `loadHarnesses({force: true})` + `prefetchModels(true)`
   (composer-pickers.tsx:220-225).
3. Heal on engine (re)connect: the catalog subscribes `client.onStatus` in
   its constructor (picker-catalog.ts:123-135) and re-kicks errored,
   not-loaded slots on every `"connected"` event
   (`#retryOfflineSlots`, picker-catalog.ts:395-409).

The first-load race the heal exists for: `EngineClient.call` throws
`RpcError("transport", "Engine is offline; reconnecting")` whenever the
socket is not established (engine-client/src/client.ts:235-242), and the
registry only calls `client.connect()` **after** the async IndexedDB cache
seed resolves (engine-client/src/registry.ts:277-283). Meanwhile the root
layout flips the app phase to `"ready"` as soon as the cache seeds rows —
`anythingLive = engines.some(e => e.state === "connected" || e.chats.loaded
|| e.spaces.loaded)` (root-layout.tsx:50-53) — so `ConversationPage` mounts
and fires `loadHarnesses()` **while the client is still pre-dial**; the call
throws instantly and the slot latches `Error("Engine is offline;
reconnecting")`. The `"connected"` heal then re-kicks it — that is the
intended path, and it is why a plain refresh usually ends up working.

Coverage holes in that heal (any of these leaves the eternal spinner):

- The heal fires **only on a `connected` status event**. Any failure that
  lands while the connection is already up — the 30s unary call timeout
  (client.ts:81, 545-560; a slow cold engine, a mid-call teardown that
  resolves after reconnect… ) — sets `Error` with no future event to re-kick
  it. The web has no per-render idle re-kick: the only later triggers are a
  chatId change (chat-page.tsx:119-124) or opening the card
  (composer-pickers.tsx:220-225).
- `#retryOfflineSlots` never touches a slot stuck in `loading: true` with
  `#harnessesInFlight` held by a promise that has not settled
  (picker-catalog.ts:399 checks `!this.#harnessesInFlight`); the `opencode`
  model ladder can legitimately hold `loading` for ~36s+ (2 retries at 2s/4s
  around 30s calls, picker-catalog.ts:263-304) and reads as "always loading"
  in the meantime.
- `setTargetDevice` is never called from the composer path (only the two
  settings pages, settings-accounts.tsx:199, settings-agents.tsx:139), so
  catalog targeting/invalidation on space change does not exist on this
  surface.

Why refresh fixes it: a reload re-orders the race (the heal fires on the
first `"connected"` of the fresh client, and the engine's harness registry is
warm — `ListHarnesses` "never forces a lazy resolve… never leave
ListHarnesses slow" per crates/engine/src/registry.rs:3-4, 414-418, 670), so
the second load lands rows. The first load's failure is then whatever flaky
window it hit; the error-blind chip made it read as eternal loading instead
of an error.

### (b) Desktop reference spec

- Chip loading is **Idle/Loading only, never Error**:
  `catalog_loading = matches!(self.harnesses, Loadable::Idle |
  Loadable::Loading)` (pickers.rs:4207); `models_loading` requires the
  harness's model slot to be neither `Ready` nor `Error` (pickers.rs:4208-
  4213); `chip_icon_loading = effective_harness.is_none() && !no_agents &&
  catalog_loading` (pickers.rs:4216-4217); `chip_label_loading = !no_agents
  && model_label.is_empty() && (catalog_loading || models_loading)`
  (pickers.rs:4220-4221).
- An errored catalog still renders a **label** on the chip: `model_label`
  resolves remembered label → configured id (pickers.rs:4186-4206) — never a
  spinner, never a placeholder word.
- `no_agents_available() = harnesses.ready().is_some_and(offered empty)` —
  "False while the catalog is still loading or failed" (pickers.rs:825-833).
- **Per-render eager kick**: the pickers' render calls
  `self.ensure_harnesses(false, cx)` + `self.prefetch_models(false, cx)`
  every frame (pickers.rs:4164-4168) — `ensure_harnesses` non-forced only
  loads from `Idle`; `Loading` never re-fires; `Ready`/`Error` need `force`
  (pickers.rs:1028-1044). The registry-connected re-kick comes for free
  because every state change re-renders the composer and thus the pickers.
- Forced reload on open + stale-while-revalidate (pickers.rs:1032-1036):
  ready rows stay on screen while the fresh catalog lands (the `if !
  matches!(…Ready)` guard at pickers.rs:1050-1053).
- The opencode model ladder: two extra attempts at 2s/4s keeping one Loading
  slot alive (pickers.rs:1138-1147).

### (c) Root cause

Two stacked defects: (1) the web's chip loading states are **error-blind**
(`!harnesses.loaded` spans Idle|Loading|Error — composer-pickers.tsx:205-210
vs desktop pickers.rs:4207-4221), so any catalog error renders as an eternal
spinner/skeleton; (2) the web lacks the desktop's per-render re-kick
(pickers.rs:4164-4168), leaving exactly two re-kick triggers (chatId change,
card open) after the one-shot heal-on-`connected` misses (the boot race:
page mounts from the cache seed before the first dial — root-layout.tsx:50-
53, registry.ts:277-283, client.ts:235-242).

### (d) Gap rows

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
|---|---|---|---|---|
| Chip icon loading gate | WRONG | `catalog_loading` = Idle\|Loading only (pickers.rs:4207, 4216-4217) | `!harnesses.loaded` spans Error too (composer-pickers.tsx:210) | `const catalogLoading = !harnesses.loaded && harnesses.error === null`; gate iconLoading/labelLoading on it; on error show the remembered/raw label like pickers.rs:4186-4206 |
| Chip label loading gate | WRONG | `models_loading` excludes Ready\|Error (pickers.rs:4208-4213) | `modelsList.loading` only, but `!harnesses.loaded` arm is error-blind (composer-pickers.tsx:205-209) | same catalogLoading + a modelsLoading that treats an errored slot as loaded |
| Catalog re-kick cadence | MISSING | every pickers render, Idle-only, idempotent (pickers.rs:4164-4168) | page mount + card open only (chat-page.tsx:117-124; composer-pickers.tsx:220-225) | re-kick idle slots on a cheap cadence (composer render effect or focus/interval), porting ensure_*'s Idle-only rule |
| Post-connect error has no heal | MISSING | render-loop kick + every open forces (pickers.rs:4164-4168, 1032-1036) | heal only on a `connected` event (picker-catalog.ts:395-409) | re-kick errored slots on window focus / a timer, or surface the error state on the chip so the user can Retry |
| Boot race: initial load pre-dial | BEHAVIOR | desktop renders its own registry; no cache-seed phase flip | page mounts while client pre-dial (root-layout.tsx:50-53; registry.ts:277-283) → instant transport error (client.ts:235-242) | keep the heal, but also treat `Error("Engine is offline…")` on a not-yet-connected client as retryable (re-arm on the next status change of any kind, not just `connected`) |
| Card takeover for an Idle slot | PARTIAL | loading takeover = Loading\|Idle with the render kick behind it (pickers.rs:3154-3165) | skeletons for Idle with nothing scheduled (composer-pickers.tsx:577-585) | schedule a non-forced load when the card opens with an Idle slot (not just force) |

---

## S3 — "the model picker… doesn't look similar to the desktop AT ALL"

### (a) Web mechanism trace

The identity card internals are a faithful port of the desktop card (verified
value-by-value below), so the dominant way it "doesn't look similar AT ALL"
is **state, not layout**: with the S2 catalog error/stuck loading, the open
card renders the card-level takeover — a bare 216px `SkeletonMenuRows` block
(composer-pickers.tsx:577-585, CSS `.model-list-loading` app.css:4042-4046) —
with **no tab strip, no search row, no traits tray**. The desktop's
Loading/Idle takeover is the same skeleton (pickers.rs:3154-3165), but the
desktop never sits in it: its render-loop kick + heal land rows within a
frame of connect. A web user stuck in the takeover sees a gray block where
the desktop shows the tabbed picker — "AT ALL".

Layout divergences that exist independent of state (all verified):

- The chip carries a native `title=` tooltip (`title={`${descriptor?.name ??
  effectiveHarness} · ${modelLabel}…`}`, composer-pickers.tsx:245) — the
  desktop chip has **no tooltip** (pickers.rs:2229-2336 has none; ticket 10
  §2.1 "No tooltip").
- The web spinner shows for errored catalogs (S2 row above) where the desktop
  shows a real label.
- The chip is rendered inside the broken actions row placement (S1) — the
  cluster sits mid-pill overlapping text instead of pinned to the bottom, so
  even the closed chip reads "not like the desktop".
- The card's anchor is `placement="anchorAboveEnd"` (composer-pickers.tsx:232)
  through Base UI's no-flip clamp-only positioner
  (components/base/popover.tsx:150-214, `noFlipPositionerProps` per
  popover.tsx:9-15) — matching the desktop's
  `anchored_menu_above_end`-equivalent flush-right placement; the
  previously-broken dead-`chipRef` anchors (research-2026-09-17 Bug 2) were
  the footer chips, fixed by ticket 10's Base UI adoption.

### (b) Desktop reference spec — the identity card, geometry verbatim

Trigger chip (`trigger_chip`, pickers.rs:2229-2336): `h 32`, `max_w 248`,
`min_w_0`, flex row, `items_center`, `gap 6`, `px 10`, `rounded 8`,
`ui_rems(12)` MEDIUM; text `hover_blend(text.opacity(0.9) → text)` (2273-
2281); bg open snaps to `element_hover` (2282-2286); brand icon 16px;
`icon_loading` → mini glyph spinner (2296-2304); `label_loading` →
`skeleton_bar(56)` (2315-2317); suffix `flex_shrink(1000)`, `text_muted@
0.7`, active tint `text@0.85` (2326-2334).

Card (`render_harness_model_popover`, pickers.rs:3143-3464) on
`popover_frame_flush(304.0, …)` (pickers.rs:4252-4259; frame max-height 640
per ticket 09):

| region | geometry | source |
|---|---|---|
| card shell | radius 12, 1px `hairline(0.10)`, `shadow_lg`, glass overlay over 44px blur (frost.rs:23), flush = no inset | popover.rs:306-331 (per web app.css:4360-4382 — matches) |
| width | 304 fixed | pickers.rs:4258; web composer-pickers.tsx:236 (`width={304}`) |
| tab strip | `h 40`, `px 6`, `border_b_1 hairline(0.08)`, row `items_center`, `gap 2`; tabs 32×32 `rounded 8`; favorites star 15px; harness brands 16px tinted; viewed marker 2px accent at `bottom -4` (left/right 6); disabled (locked) tabs `opacity 0.35` with the click handler attached | pickers.rs:3232-3312 |
| search row | `h 40`, `px 10`, `border_b_1 hairline(0.08)`, `gap 8`; magnifier 14px `text_muted@0.7`; input `ui_rems(13)`, placeholder "Search models…" | pickers.rs:3317-3339 |
| list host | `id model-list-scroll-host`, relative, `flex_none`, `h LIST_HEIGHT 216`, `py 6`, `bg ink(0.02)`; uniform_list virtualized, `px 6` | pickers.rs:3147, 3405-3430 |
| empty states | searching → "No models found"; favorites → "No starred models yet — hit a row's star"; slot error → retry row; else 5 skeleton rows | pickers.rs:3373-3402 |
| model row | `px 8`, `py 5` (compact) / `py 6` (favorites), `rounded 6`, flex row `items_center` `gap 10`; label `ui_rems(12.5)` MEDIUM `theme.text`; attribution `ui_rems(11)` `text_muted@0.7`; selected = `card_selected_bg()` + inset 1px `hairline(0.09)` ring; keyboard cursor `ink(0.05)`; **hover moves the cursor**; ⌘N kbd chip for ix < 9; star button 22×22 `rounded 6`, star 13px `theme.warning`/`text_muted@0.45`, `hover ink(0.08)`, stops propagation | pickers.rs:3470-3659 (per ticket 10 §2.3.4; web app.css:4096-4234 matches) |
| traits tray | `flex_none`, `border_t_1 hairline(0.08)`, `max_h 236`, `overflow_y_scroll`, `px 6 pb 6`; sections `gap 2`; rows `py 5`, `rounded 6`, `ui_rems(12.5)`; `"Default"` badge = bare 10px semibold `text_muted@0.6`; **no check marks**; keyboard nav never enters | pickers.rs:3439-3454, 3666-3774, 3780 |
| no-agents takeover | `p 16` column centered `gap 8`: terminal 20px, "No agents available" 13px `theme.text`, body 12px `text_muted` centered | pickers.rs:3195-3223 |
| loading/error takeover | `h 216`, `p 8`, 5 skeleton rows / retry row | pickers.rs:3153-3180 |

Trigger → data → loading chain (desktop): chip click → `toggle` (open, prime
rail Favorites-if-favorites else Harness, anchor cursor on the selected row,
reset scroll, focus the search input, **force-reload harnesses + prefetch
every offered harness's models**) — pickers.rs:1003-1019 area + the
toggle-step list in ticket 10 §2.5; the render loop keeps both catalogs warm
every frame (pickers.rs:4164-4168).

### (c) Root cause

The card's *layout* matches the desktop nearly 1:1 (values above); what makes
it look nothing like the desktop is (1) the S2 stuck-loading takeover that
replaces the whole card with a skeleton block, (2) the S1 actions-row
misplacement that parks the chip mid-pill, and (3) small additive deltas
(native `title` tooltip on the chip; error-blind spinner). None of these is a
"wrong geometry" bug in the card itself.

### (d) Gap rows

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
|---|---|---|---|---|
| Chip native tooltip | INVENTED | no tooltip on the chip (pickers.rs:2229-2336) | `title={…}` (composer-pickers.tsx:245) | delete the `title` |
| Chip on error | WRONG | label from remembered/configured id (pickers.rs:4186-4206) | spinner + skeleton (composer-pickers.tsx:205-210) | S2 fix |
| Card in stuck-loading | WRONG | skeleton takeover exists but never persists (render kick, pickers.rs:4164-4168) | same takeover, persistent (composer-pickers.tsx:577-585) | S2 fix |
| Chip placement in pill | WRONG | bottom-pinned actions row (composer.rs:7752-7758) | mid-pill over text (S1) | S1 fix |
| Card geometry (spot check) | MATCHES | 304/640/r12/blur44 (pickers.rs:4252-4259; popover.rs:306-331) | width 304, maxHeight 640 (composer-pickers.tsx:236-237); `.popover-card` r12/blur44 (app.css:4360-4376) | — |
| Tab strip / search / list / tray | MATCHES | pickers.rs:3232-3312 / 3317-3339 / 3405-3430 / 3439-3454 | app.css:3916-3924 / 3986-4013 / 4017-4033 / 4243-4284 | — |

---

## S4 — "the composer shouldn't have scrollbar"

### (a) Web mechanism trace

The layout pass sets the textarea's overflow directly
(composer.tsx:835-837):

```ts
const inputHeight = mode ? Math.max(boxHeight - textPad - 4, 0) : INPUT_LINE_HEIGHT;
el.style.height = `${inputHeight}px`;
el.style.overflowY = mode && contentHeight > inputHeight ? "auto" : "hidden";
```

`overflowY: "auto"` is applied whenever the wrapped content exceeds the
visible input — a native scrollbar then paints inside the pill (the CSS
`.composer-input { overflow: hidden }` at app.css:3242 is overridden by the
inline style; nothing hides the bar — `scrollbar-width: none` /
`::-webkit-scrollbar { display: none }` exist only for the completion list
(app.css:3502-3505), the model list (app.css:4024-4033) and the traits tray
(app.css:4243-4253), NOT for `.composer-input`). The wizard's borrowed-input
auto-grow does the same (`el.style.overflowY = el.scrollHeight > 120 ?
"auto" : "hidden"`, composer.tsx:1761-1770). The 12px fade ramps are already
ported via `mask-image` keyed on `data-fade-top/bottom`
(composer.tsx:838-844; app.css:3246-3259).

### (b) Desktop reference spec

The desktop input is a hand-rolled editor with **no scrollbar ever**: the
textarea box is `overflow_hidden` (composer.rs:7746) and scrolling is
wheel-driven — `on_scroll_wheel` (composer.rs:2898-2933) applies
`input_scroll_offset` (clamp to `input_max_scroll`), swallows the wheel at
the boundary when the input itself is scrollable (the GPUI equivalent of
`overscroll-behavior: contain`, comment at composer.rs:2917-2924), sets
`follow_cursor = false`, and repaints with the 12px fade ramps
(`INPUT_FADE_BAND = 12`, composer.rs:101; `input_overflow_edges` — only
**settled** overflow gets a fade, never the transient overflow of a growing
draft). Drag-selection autoscroll drives the same `scroll_top`
(composer.rs:2864-2896). The web ports all of the scroll math
(`inputScrollOffset`, `inputOverflowEdges`, `inputDragScrollDelta` —
lib/composer-flip.ts:196-320) and the mask ramps, then undoes the "no
scrollbar" half by setting `overflowY: auto`.

### (c) Root cause

The inline `overflowY: "auto"` (composer.tsx:837, and 1769 for the wizard
input) paints a native scrollbar the desktop never shows; the desktop's
equivalent affordance is wheel-scrolling + the 12px fade ramps, both of which
the web already has.

### (d) Gap rows

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
|---|---|---|---|---|
| Input scrollbar | INVENTED | none — `overflow_hidden` box + wheel offsets + fades (composer.rs:7746, 2898-2933, 101) | `el.style.overflowY = "auto"` when content overflows (composer.tsx:837) | keep native wheel scrolling but hide the bar: `.composer-input { scrollbar-width: none }` + `.composer-input::-webkit-scrollbar { display: none }` (drop the inline `auto`; the fade masks are the affordance) |
| Wizard input scrollbar | INVENTED | same (the borrowed input is the same editor) | `overflowY: auto` past 120px (composer.tsx:1769) | same class-level fix |

---

## S5 — full state-space audit: desktop behavior vs web behavior

Legend: D = desktop (file:line), W = web (file:line). "match" = behavior
matches within accepted divergences already recorded in the tickets'
Comments.

**Growth / geometry**

| state | desktop | web | verdict |
|---|---|---|---|
| compact rest | pill 49, one 47px bottom-justified row (composer.rs:70, 7801-7805) | pill 49 inline height (composer.tsx:2701; REST_LAYOUT 192-200), body 47 top-anchored (app.css:3180-3186) | match settled; morph-time anchoring differs (S1 gap row 4) |
| expand trigger | width-driven with 32px hysteresis, newline always, capacity < 200, resize defers collapse 150ms (composer.rs:126-144) | ported: `composerFlip` (lib/composer-flip.ts:112), mirror-measured `textWidth` (composer.tsx:703), `composerFlip(...)` call (composer.tsx:753) | match |
| capacity source | compact-mode input capacity, layout-stable, learned + container delta (composer.rs:117-125, 7224-7231) | compactCapacity/expandedAnchor refs (composer.tsx:650-752) | match (deviation 9 in ticket 13 Comments documents the no-8px-subtract) |
| expanded heights | box clamp 76–260, pill 124–308 (composer.rs:53-66) | same constants (lib/composer-flip.ts:39-53; composer.tsx:777-784, 833) | match |
| morphs | 180ms COLLAPSE/EASE_OUT height + flip morphs, cluster dy/insets/text pad/glide, route snap 250, reduced-motion snap (composer.rs:394-455; motion.rs) | all ported (composer-flip.ts:429-524; composer.tsx:789-861, 639-884) | match |
| new-thread canvas | always expanded, flips never commit, auto-grow still morphs (composer.rs:7488, 7284-7300) | `newChat` forces mode (composer.tsx:446, 754-758, 2618) | match |
| dock frame | `set_dock_frame` kills morphs, `dock_height` owns height (composer.rs:4140-4149, 7489-7500) | ported (composer-dock.ts:127-135; composer.tsx:776-814) | match |
| actions row as text grows | pins to the pill's stationary bottom, full alpha (composer.rs:7718-7783) | rides the input-box bottom, overlaps text, 46px blank (S1) | **WRONG — S1** |
| input overflow | no scrollbar; wheel + 12px fades on settled edges only (composer.rs:2898-2933, 101, 181-192) | native scrollbar (S4); fades ported (composer.tsx:838-844) | **WRONG — S4** |

**Content states**

| state | desktop | web | verdict |
|---|---|---|---|
| empty vs non-empty | `composer_has_content(text, attachments+appshots, comments)` — trim; staged image/comment alone is content (composer.rs:505 per ticket 13; send gate 5994-5998) | ported (lib/composer-send.ts; composer.tsx:2090, 2412) | match |
| single-line paste | wraps; width flip decides | same (mirror measure) | match |
| multi-line paste | newline → always expanded | `hasNewline` (composer.tsx:704) → flip | match |
| placeholder | always "Do anything…" at rest (composer.rs:5920, 6810; wizard pair 6751-6760) | `COMPOSER_REST_PLACEHOLDER` (composer.tsx:1753-1756; lib/wizard.ts) | match |
| drafts | per-chat map, `""` = canvas, swapped on nav, cleared on submit (ticket 13 §2.12) | `chatDrafts` module map (lib/composer-draft.ts:374-400; swap composer.tsx:470-483; clear composer.tsx:1938-1939) | match (in-memory only — refresh loses drafts; desktop loses them on app restart; accepted divergence, worth a comment) |
| attachments attached | strip above input in both modes; heights feed the pill (composer.rs:7682-7684; STRIP_* composer.rs:288-291) | AttachmentStrip + `attachmentStripHeight` (composer.tsx:765-772, 2708-2715) | match |
| comments chip | 36px strip when staged | CommentsChip + `commentStripHeight` (composer.tsx:2707; lib/review-comments.ts) | match |

**Send-path states**

| state | desktop | web | verdict |
|---|---|---|---|
| idle chat | Send | Send (composer.tsx:2414-2416) | match |
| working + content | Queue — `QueueMessage {holdForTurnEnd: true}` (composer.rs:573-579, 6547-6577) | queueMessage (composer.tsx:1952-1966; lib/composer-actions.ts:216-235) | match |
| working + empty | Stop → interrupt (composer.rs:573-579, 6000) | stop → interrupt (composer.tsx:2092-2094, 1838-1848) | match |
| send blocked | 4 conditions; Stop never blocked (composer.rs:5944-5966) | sendBlocked mapping (composer.tsx:2417-2424, 2099-2113; deviation 6 in ticket 13 Comments) | match (documented seams) |
| queue capability gate | MESSAGE_QUEUE_V1/(ATTACHMENTS) pre-checked (composer.rs:6096-6110) | ported, engine-only check (composer.tsx:1822-1864; deviation 2) | match (documented) |
| new chat send | mint id → createChat **with config/branch/cwd** → select → run; projectless cwd = "~" (composer.rs:6433-6440, 6494-6539) | `createChat` sends only `{op, chatId, spaceId|deviceId}` (chat-actions.ts:37-46); **blocks** projectless sends with "This chat has no working directory yet" (composer.tsx:1866-1869) | **GAP** — web blocks the desktop's legal projectless send; createChat drops config/branch (row below) |
| failure recovery | typed text + attachments back by id, new chat deleted, route back (ticket 13 §2.12) | ported (composer.tsx:2008-2036) | match |
| optimistic echo | before RPC, never for queued, refreshed post-upload (composer.rs:6188-6233, 6401-6423) | ported (composer.tsx:1929-1937, 1979-1990) | match |
| interrupt tracking | idempotent set, released on settle (composer.rs:6707-6741) | ported (composer.tsx:972-980, 1838-1848) | match |
| Mod+Enter empty | activates latest queued row, never Stop (composer.rs:6012-6014) | ported (composer.tsx:2279-2289) | match |
| IME | isComposing never submits (ticket 13 §2.9 note) | `event.nativeEvent.isComposing` (composer.tsx:2203) + composition pause (composer.tsx:336-340) | match |
| Enter policy | ComposerSendBehavior enter/modEnter, exactly two bindings (composer.rs:1364) | `messageEnterBindings` (composer.tsx:2120-2124) | match |
| Escape | completion → wizard back → queue-edit cancel → bubble (composer.rs:7473-7483; ticket 14 §2.7) | same ladder (composer.tsx:2214-2234, 2353-2361) | match |
| keyboard history (ArrowUp) | none exists on the desktop | none on the web | match (do not invent) |

**Focus / popups / wizard**

| state | desktop | web | verdict |
|---|---|---|---|
| focus ring | none — pill chrome state-independent (ticket 13 deviation 10) | `outline: none` (app.css:3241), no accent borders | match |
| pill mouse-down focus | focus input unless a menu is open (composer.rs:7701-7709) | `onPillMouseDown` (composer.tsx:2496-2506) | match |
| mention/slash popups | token machines, 80ms debounce, atomic chips (composer.rs:3866-3990) | ported (composer.tsx:1152-1290, 2130-2200) | match |
| wizard | replaces the pill; latch; auto-advance 220ms; 2s safety net (composer.rs:6903+, 676+) | ported (components/composer/wizard.tsx; composer.tsx:728-820 area) | match (remount deviation recorded in ticket 14 Comments) |
| queue edit | seeds row text, restores on lease close (queue.rs:1394-1398) | ported (composer.tsx:485-542) | match |
| working indicator | `run_live` = Working\|AwaitingInput (composer.rs:5931-5940) | same (composer.tsx:406-409) | match |
| queue-degraded caption | connectivity-gated caption (composer.rs:7319-7345) | engine-state stand-in (composer.tsx:2426-2441; deviation 3 ticket 13) | match (documented seam) |

---

## S6 — "Send failed: crypto.randomUUID is not a function"

### (a) Every `crypto.randomUUID` call site in web/packages (grep, verified)

| # | file:line | function | runs when |
|---|---|---|---|
| 1 | `web/packages/app/src/lib/chat-actions.ts:29-31` | `defaultMintId` | **every new-chat send** — `createChat` mints the chat id (`options.mintId ?? defaultMintId` at chat-actions.ts:38) |
| 2 | `web/packages/app/src/lib/composer-actions.ts:123-125` | `defaultMint` | every send's message id — `mintMessageId()` (composer.tsx:1907) and `sendRun`'s `(options.mintMessageId ?? defaultMint)` (composer-actions.ts:166) |
| 3 | `web/packages/app/src/lib/queue-actions.ts:186-188` | `mintEditorInstanceId` | queue-row edit leases |
| 4 | `web/packages/app/src/state/transcript-store.ts:263-265` | `defaultMintEchoId` | echo id fallback |
| 5 | `web/packages/app/src/lib/review-comments.ts:45-47` | `mintId` | new diff comments |
| 6 | `web/packages/app/src/lib/attachments.ts:271-282` | `finalizeStage` | staging every dropped/pasted image |
| 7 | `web/packages/app/src/state/add-space.ts:111-113` | `mintId` | the add-space palette |

No id helper exists in `@roboco/engine-client` (grep for `uuid|makeId|newId|
generateId|nonce` → no matches; the closest is `scoped-id.ts`'s
`encodeScopedId`, an encoder, not a mint). The desktop has no such problem:
it mints `Uuid::new_v4()` in Rust (e.g. the chat id and message id in the
send path, composer.rs:6249-6258 area / doc commands), which has no
secure-context gate.

### (b) Exact failure path for the reported error

New-chat send: `submit()` → `send()` → `chatId === ""` branch → `await
createChat(...)` inside a try (composer.tsx:1876-1890). `createChat` calls
`defaultMintId()` **synchronously** (chat-actions.ts:38); when
`crypto.randomUUID` is undefined it throws `TypeError: crypto.randomUUID is
not a function`, the async call rejects, and the catch renders exactly the
user's string: `` setFailure({ message: `Send failed:
${describeSendError(error)}`, key: null }) `` (composer.tsx:1887-1889 —
`describeSendError` returns `error.message` for Errors, chat-actions.ts:111-
113). The draft survives (the snapshot-and-clear happens later,
composer.tsx:1893+), which matches the user seeing the notice with their
text intact.

Two adjacent facts:

- For an **existing** chat the crash site is `mintMessageId()` at
  composer.tsx:1907 — which sits **outside** the try block (the try starts
  at composer.tsx:1951), so the same missing function produces an *uncaught*
  async rejection (the click path is `void submit()` — composer.tsx:2285,
  2296): no notice, no clear, a silently dead send. Worse UX than the new-
  chat path, same root cause.
- Why the browser lacks the function: `crypto.randomUUID` is **only exposed
  in secure contexts** (HTTPS or localhost) and needs Chrome 92+/Safari
  15.4+/Firefox 95+. The engine serves the web client itself over **plain
  HTTP**: the remote (paired) listener embeds and serves the built bundle
  (`crates/engine/src/web.rs:1-16` rust-embed; `crates/engine/src/listener.rs:2`
  "The remote (paired) bind additionally serves the embedded web client
  pages"; listener.rs:148-151 serves the React app at `/` and `/pair`), with
  no TLS anywhere in the listener (`remote_access.rs` has no TLS wiring), and
  pairing accepts plain `http` origins (`crates/engine/src/pairing.rs:200`).
  The store models this first-class: `engineWsEndpoint` maps `http→ws,
  https→wss` (lib/engine-store.ts:85-91). Opening the web client at
  `http://<lan-host>:<port>` from any other machine (or any non-localhost
  host) is a **non-secure context** → `crypto.randomUUID` is undefined →
  every id mint above breaks. localhost dev/smoke never sees it (localhost is
  a secure context), which is why tickets 13/15's live verifications passed.

### (c) Recommended fix

Add one id mint with a fallback chain and route all seven call sites through
it. `crypto.getRandomValues` IS available in non-secure contexts, so a
standards-shaped v4 is possible everywhere:

```ts
// web/packages/engine-client/src/id.ts (or app lib/id.ts)
export function mintId(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  if (typeof crypto !== "undefined" && typeof crypto.getRandomValues === "function") {
    const b = crypto.getRandomValues(new Uint8Array(16));
    b[6] = (b[6]! & 0x0f) | 0x40; // v4
    b[8] = (b[8]! & 0x3f) | 0x80; // variant
    const hex = [...b].map((x) => x.toString(16).padStart(2, "0")).join("");
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  }
  return `id-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
}
```

Living in `@roboco/engine-client` gives both app packages one home (the
catalog packages already import from it); a web-`lib` home is equally fine —
pick one and grep-replace the seven sites. Also move the existing-chat
`mintMessageId()` (composer.tsx:1907) inside the try (or wrap `send`'s body)
so ANY future pre-flight throw surfaces as the failure notice instead of a
silent uncaught rejection.

### (d) Gap rows

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
|---|---|---|---|---|
| id minting | MISSING | `Uuid::new_v4()` — no context gate (composer.rs send path) | 7 × bare `crypto.randomUUID()` (table above) | shared `mintId()` fallback helper |
| new-chat send crash | BUG | — | chat-actions.ts:29-31 → composer.tsx:1888 "Send failed: crypto.randomUUID is not a function" | the helper |
| existing-chat send crash (silent) | BUG | — | composer.tsx:1907 outside the try at 1951; `void submit()` at 2285/2296 | the helper + move the mint inside the try |
| plain-HTTP LAN serving | CONTEXT | desktop app is native (no browser) | engine serves the bundle over http (web.rs:1-16; listener.rs:2, 148-151; pairing.rs:200; engine-store.ts:85-91) | the fallback covers it; optionally a dev console warning when `crypto.randomUUID` is missing |

---

## Consolidated gap table

| # | item | kind | desktop value (file:line) | web value (file:line) | fix |
|---|---|---|---|---|---|
| 1 | Expanded actions-row containing block | WRONG | pill itself, full height (composer.rs:7734-7758) | `.composer-body` content-sized (app.css:3188-3196; composer.tsx:2716, 2727-2733) | `flex: 1 1 auto; min-height: 0` on the expanded body (or drop the wrapper) |
| 2 | Pill-bottom blank band + row-over-text | WRONG | none (7744+7758) | 46px blank; row overlaps input box's last 46px (composer.tsx:833, 2727) | falls out of #1 |
| 3 | Compact row anchoring | WRONG | pill `justify_end` (composer.rs:7801-7805) | top-anchored 47px body (app.css:3180-3186) | `justify-content: flex-end` on the compact pill |
| 4 | Chip icon loading gate | WRONG | Idle\|Loading only (pickers.rs:4207, 4216-4217) | `!harnesses.loaded` spans Error (composer-pickers.tsx:210) | error-aware `catalogLoading` |
| 5 | Chip label loading gate | WRONG | excludes Ready\|Error (pickers.rs:4208-4221) | error-blind (composer-pickers.tsx:205-209) | same |
| 6 | Chip label on error | MISSING | remembered/configured id (pickers.rs:4186-4206) | spinner/skeleton (composer-pickers.tsx:247-263) | resolve the label; error surfaces via the card |
| 7 | Catalog re-kick cadence | MISSING | every pickers render, Idle-only (pickers.rs:4164-4168) | page mount + card open (chat-page.tsx:117-124; composer-pickers.tsx:220-225) | cheap periodic/focus re-kick with the Idle-only rule |
| 8 | Post-connect error heal | MISSING | render kick + forced open (pickers.rs:4164-4168, 1032-1036) | only on a `connected` event (picker-catalog.ts:395-409) | retry errored slots on focus/timer; treat offline-errors as retryable on any status change |
| 9 | Card takeover for Idle slot | PARTIAL | kick always pending (pickers.rs:3154-3165) | nothing scheduled (composer-pickers.tsx:577-585) | schedule a load on open with an Idle slot |
| 10 | Chip native `title` tooltip | INVENTED | none (pickers.rs:2229-2336) | composer-pickers.tsx:245 | delete |
| 11 | Input scrollbar | INVENTED | none (composer.rs:7746, 2898-2933) | `overflowY: auto` (composer.tsx:837; 1769 wizard) | hide the bar, keep wheel + fades |
| 12 | Projectless new-chat send | WRONG | cwd `"~"` host-expanded (composer.rs:6433-6440) | blocked with a failure notice (composer.tsx:1866-1869) | allow the send (device home dir) once the engine accepts it |
| 13 | createChat payload | PARTIAL | `{op, chatId, spaceId\|deviceId, cwd?, branch?, config}` (composer.rs:6494-6533) | `{op, chatId, spaceId\|deviceId}` only (chat-actions.ts:37-46) | thread the resolved config (+ worktree branch/cwd) from the draft/checkout plan |
| 14 | id minting | MISSING | `Uuid::new_v4()` | 7 × `crypto.randomUUID` (S6 table) | shared fallback helper |
| 15 | existing-chat mint outside try | BUG | — | composer.tsx:1907 vs try at 1951 | move inside |
| 16 | Draft persistence across refresh | DIVERGENCE | in-memory; lost on app restart | in-memory only (lib/composer-draft.ts:374-400) | accepted; record in Comments |
| 17 | Card geometry/tab/search/list/tray/send button | MATCHES | pickers.rs:3143-3464; composer.rs:7106+ | app.css:3830-4284, 4932-4962; composer-pickers.tsx:229-300 | — |

## Pure logic to port + desktop test names

Nothing NEW to port for S1/S3/S4 — the pure layers already exist and match
(`lib/composer-flip.ts`, `lib/composer-send.ts`, `lib/composer-dock.ts`).
The port work this research surfaces is small and catalog-side:

1. **`catalogLoading`/`modelsLoading` classification** (fixes S2's visuals):
   a pure predicate over `LoadableList` mirroring pickers.rs:4207-4213 —
   `loading = !loaded && error === null` for harnesses; models loading =
   slot present, not `Ready`, not `Error`. Port the desktop test
   `chip_loading_states_exclude_error` (write it mirroring
   pickers.rs:4207-4221's shape; the desktop's own tests live around
   pickers.rs:4992+ — `default_model_is_first_catalog_row`,
   `offered_harnesses_follow_the_catalog_enabled_flags` already have web
   mirrors per ticket 10).
2. **`ensure`-kick discipline** (S2's latch): port `ensure_harnesses`'s
   Idle/Loading/Ready|Error+force table (pickers.rs:1037-1041) as a pure
   `shouldReload(slot, force)` used by whatever cadence the web picks.
   Desktop tests to mirror: the stale-while-revalidate behavior comments
   (pickers.rs:1032-1036) and the opencode ladder (picker-catalog.ts already
   ports it; test names exist in ticket 10's Comments).
3. **`mintId()` fallback** (S6): pure; unit-test the three arms
   (randomUUID present / getRandomValues only / neither — inject a fake
   `crypto`).
4. Fix verification should reuse the existing suites:
   `tests/composer-flip.test.ts`, `tests/composer-send.test.ts`,
   `tests/model-rows.test.ts`, `tests/traits-summary.test.ts` — all green
   at HEAD per ticket Comments; the S1/S4 fixes are CSS/inline-style only
   and must not disturb them.

## Desktop-only items NOT to port

- The hand-rolled `ComposerTextElement` editor itself (composer.rs:1630+):
  caret blink painting, selection quads, layout caching, utf16 mapping — the
  native textarea stands (ticket 13's accepted divergence).
- The 700ms/200-step undo coalescing (composer.rs:854-857) — native undo
  stands (ticket 13 deviation 7).
- Appshot strip (composer.rs:523-528, 4903) — desktop-only, never port
  (ticket 13 §5).
- `frost::frosted`/`frost::layered`, `ROBOCO_*` env knobs
  (`ROBOCO_OPEN_PICKER`, `ROBOCO_SLOW_CATALOG_MS`, `ROBOCO_HARNESS`,
  `ROBOCO_MOTION_SCALE`), the gpui `EntityInputHandler` IME machinery,
  `boot_focus_pending` re-claim loop — all explicitly desktop-only
  (tickets 10/13 §5).
- The gpui-harness layout tests (`measured_dock_retargets_without_a_first_
frame_jump`, `resolved_layout_does_not_keep_notifying_on_repaint` in its
gpui form) — the web's pure seams already model them (ticket 13 deviation
8).
- Desktop pairing-remount choreography (ticket 32) is a web-only defect with
  no desktop counterpart — do not "port" anything for it here, but note the
  keyed `page-fade` remount (root-layout.tsx:80) re-fires the
  ConversationPage effect (`[session, chatId]`, chat-page.tsx:119-124) on
  the loading→ready flip, which is one more re-kick of `loadHarnesses` the
  boot race analysis should account for.
