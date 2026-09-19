# 37 — Composer expanded layout: bottom-pinned actions row, no scrollbar

**What to build:** When a draft grows the composer pill, the actions cluster
(model picker chip, paperclip, send) sits pinned to the pill's **bottom edge**
under the text, exactly like the desktop — never overlapping the last text
rows, never leaving a blank band under it — and during a collapse the compact
row stays bottom-justified while the pill's top sweeps down over it. The
composer input (and the wizard's borrowed input) never shows a native
scrollbar: overflow scrolls by wheel with the already-ported 12px fade ramps
as the only affordance. After this ticket, a four-line draft looks like the
desktop's expanded composer, and scrolling a tall draft shows no bar.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

**Research:** `../research-2026-09-19/composer-model-picker-send.md` S1
((a)–(d)), S4 ((a)–(d)), S5 ("Growth / geometry" table), "Pure logic to port
+ desktop test names" item 4.

**Desktop reference (for lookups only):** `crates/ui/src/composer.rs::Composer::render`
— the expanded body `composer.rs:7724-7783` (pill as its own positioning
context, actions row `absolute().left_0().right_0().bottom(-cluster_dy)`
at `7752-7758`), the compact body `composer.rs:7801-7850`
(`justify_end` at `7801-7805`, the stationary-row comment `7788-7792`),
constants `ACTIONS_ROW_HEIGHT` (`:58`), `COMPACT_TOTAL_HEIGHT` (`:70`),
`COMPOSER_MIN_HEIGHT`/`COMPOSER_MAX_HEIGHT` (`:65-66`),
`PILL_BORDER_V` (`:60`), `INPUT_FADE_BAND` (`:101`), the wheel handler
`on_scroll_wheel` (`composer.rs:2898-2933`) and `input_overflow_edges`
(`composer.rs:181-192`).

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/styles/app.css` | edit | `.composer-pill[data-mode="expanded"] .composer-body` (grow to the pill height, app.css:3188-3192), `.composer-pill[data-mode="compact"]` (bottom-justify, new rule next to app.css:3180-3186), `.composer-input` (scrollbar hiding + the scrollable-state rule, app.css:3229-3244) |
| `web/packages/app/src/components/composer.tsx` | edit | the layout pass's inline `el.style.overflowY` write (composer.tsx:837) and the wizard borrowed-input's inline `el.style.overflowY` write (composer.tsx:1769) — both replaced by a data-attribute gate; nothing else in the file changes |

---

## 1. Context a fresh session needs

- The composer pill is ONE DOM shape for both modes, re-laid-out by
  `data-mode`, so the textarea never remounts (ticket 13's landed design —
  keep it). Today (`composer.tsx:2698-2706`):

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

- CSS wiring: `.composer-pill` — `position: relative; display: flex;
  flex-direction: column; overflow: hidden;` (app.css:3161-3172), height
  inline `layout.pillHeight` (composer.tsx:2701-2704).
  `.composer-pill[data-mode="expanded"] .composer-body` — `display: flex;
  flex-direction: column; position: relative` (app.css:3188-3192) and
  `.composer-body` is only `min-width: 0` (app.css:3194-3196) — **no `flex: 1`,
  no height**, so the body's height is its content height = the input box's
  inline height. `.composer-pill[data-mode="compact"] .composer-body` — one
  47px top-anchored row (app.css:3180-3186).
  `.composer-pill[data-mode="expanded"] .composer-input-box` (app.css:3208-3213)
  takes the inline `height: layout.boxHeight`, `paddingTop: layout.textPad`
  (composer.tsx:2720-2722). `.composer-pill[data-mode="expanded"]
  .composer-actions` — `position: absolute; left: 0; right: 0; height: 46px;
  padding: 4px 8px 10px 12px` (app.css:3298-3304) with inline
  `bottom: -layout.clusterDy` (composer.tsx:2729-2732). **Its containing block
  is `.composer-body`** (nearest positioned ancestor, app.css:3191), not the
  pill — that is the bug.
- `layout.boxHeight` = `max(pillHeight − stripH − PILL_BORDER_V −
  ACTIONS_ROW_HEIGHT, 0)` (composer.tsx:833), i.e. exactly `pillHeight −
  stripH − 48` with no strips.
- Numeric walk for a 4-line draft (no attachments), from the research:
  contentHeight = 4 × 22.75 = 91 → `composerTotalHeight` = clamp(91 + 20, 76,
  260) + 46 + 2 = **159** (composer.tsx:777-784; `composerTotalHeight` in
  lib/composer-flip.ts:187). `boxHeight` = 159 − 0 − 2 − 46 = **111**. So the
  pill's content box is 157px tall; the in-flow content (body = input box) is
  111px; the leftover **46px sits as blank space at the pill's bottom**
  (flex-start default); and the absolute actions row anchors to the body's
  bottom edge (y = 111) and is 46px tall, so it spans **[65, 111]** —
  *overlapping the input box's bottom 46px* (text rows 3–4 of the 4-line draft
  live at [61.5, 107] inside that box). The taller the composer, the more
  obvious. At the minimum expanded height (124, the always-expanded
  new-thread canvas: `newChat` at composer.tsx:446, `expandedRender =
  expanded || newChat` at 2618) the same 46px blank band exists under the row.
- The scrollbar half: the layout pass sets the textarea's overflow directly
  (composer.tsx:835-837): `el.style.overflowY = mode && contentHeight >
  inputHeight ? "auto" : "hidden"`. `overflowY: "auto"` paints a native
  scrollbar inside the pill; the CSS `.composer-input { overflow: hidden;
  overscroll-behavior: contain }` (app.css:3242-3243) is overridden by the
  inline style, and nothing hides the bar — `scrollbar-width: none` /
  `::-webkit-scrollbar { display: none }` exist only for the completion list
  (app.css:3502-3505), the model list (app.css:4024-4033) and the traits tray
  (app.css:4243-4253), NOT for `.composer-input`. The wizard's borrowed-input
  auto-grow does the same (`el.style.overflowY = el.scrollHeight > 120 ?
  "auto" : "hidden"`, composer.tsx:1761-1770). The 12px fade ramps are already
  ported via `mask-image` keyed on `data-fade-top/bottom` (composer.tsx:838-844;
  app.css:3246-3259).
- The pure layer already exists and matches the desktop:
  `lib/composer-flip.ts` (constants, `composerTotalHeight`,
  `inputMaxScroll`, `inputOverflowEdges`, `inputScrollOffset`,
  `inputDragScrollDelta`, the morph helpers) and the drag-autoscroll that
  drives `el.scrollTop` (composer.tsx:923-952). **This ticket is CSS +
  inline-style work only** and must not disturb any of it.
- Tokens: no new ones. Colors/washes stay `var(--rb-*)` / `rgb(var(--rb-*) /
  a)`; spacing stays the existing literal constants the composer already uses
  (46, 47, 12px fade band) — they mirror desktop `pub const`s, not design
  tokens.
- Vocabulary: **chat** (not session/thread), **space** (not project),
  **engine**, **harness** (not provider).

---

## 2. Spec

### 2.1 Expanded pill body — the actions row pins to the pill's bottom

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

**Web change.** Keep the `.composer-body` wrapper (one DOM shape; the
textarea must not remount) but stretch it to the pill so its bottom edge IS
the pill's bottom edge and the absolute actions row anchors like the
desktop's:

- `.composer-pill[data-mode="expanded"] .composer-body` gains
  `flex: 1 1 auto; min-height: 0;` (app.css:3188-3192).
- Nothing else moves: the input box keeps its inline `height:
  layout.boxHeight` and `flex: none` (app.css:3209), the actions row keeps
  its absolute `left: 0; right: 0; height: 46px; padding: 4px 8px 10px 12px`
  (app.css:3298-3304) and inline `bottom: -layout.clusterDy`
  (composer.tsx:2729-2732). With the body full-height, the row's containing
  block becomes full-height and the row lands at the pill's bottom — the
  46px blank band disappears (the row fills it) and the overlap with the
  input box's last 46px disappears (the box ends exactly where the row
  begins, modulo the 2px border both sides already account for).
- Do NOT drop the wrapper and re-parent `.composer-input-box` /
  `.composer-actions` onto the pill — the research offers that as an
  alternative, but it risks the DOM churn ticket 13's one-shape design
  exists to prevent. The flex fix is the desktop-equivalent geometry.

**States.** The pill's height/growth state machine is already ported and
stays untouched; this table is the audit reference (from the research's S5
growth/geometry audit — the two **WRONG** rows are the ones this ticket
closes, the rest are matches that must stay matches):

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

**Motion.** No new motion. The 180ms height/flip morphs, cluster dy, insets,
text pad, and collapse glide are already ported and drive the same values
(`lib/composer-flip.ts:429-524`; composer.tsx:789-861). The only motion-
relevant contract: the actions cluster rides the stationary bottom anchor at
**full alpha** throughout every morph — no fade on the chips.

### 2.2 Compact pill body — bottom-justified row

The desktop's compact body (`composer.rs:7801-7850`): `pill.h(pill_height).
overflow_hidden().flex().flex_col().**justify_end()**` with one
`h(COMPACT_TOTAL_HEIGHT − PILL_BORDER_V)` = **47px** row (`flex_row
items_center`): input holder `flex_1 min_w_0 pl 16 pr 8 relative
top(-text_glide)`, cluster `flex_none … pl 4 pr morph_cluster_inset(false, t)
12→8 relative top(-cluster_dy)`. `justify_end` pins the row to the pill's
bottom so during the collapse morph "the pill top sweeps down over a
stationary row" (comment, composer.rs:7788-7792).

**Web change.** `.composer-pill[data-mode="compact"]` gains
`justify-content: flex-end;` (the pill is already `display: flex;
flex-direction: column`, app.css:3161-3172). The body stays
`height: 47px; flex: none` (app.css:3180-3186). Strips (comments chip /
attachment strip) ride above the row inside the bottom-justified group,
matching the desktop's child order. Settled compact rest is visually
unchanged (49 − 47 ≈ the 2px borders); the fix is the **morph-time**
anchoring — during a collapse the row stops moving while the pill's top edge
sweeps down over it.

### 2.3 No native scrollbar — main input and wizard input

The desktop input is a hand-rolled editor with **no scrollbar ever**: the
textarea box is `overflow_hidden` (composer.rs:7746) and scrolling is
wheel-driven — `on_scroll_wheel` (composer.rs:2898-2933) applies
`input_scroll_offset` (clamp to `input_max_scroll`), swallows the wheel at
the boundary when the input itself is scrollable (the GPUI equivalent of
`overscroll-behavior: contain`, comment at composer.rs:2917-2924), sets
`follow_cursor = false`, and repaints with the 12px fade ramps
(`INPUT_FADE_BAND = 12`, composer.rs:101; `input_overflow_edges` — only
**settled** overflow gets a fade, never the transient overflow of a growing
draft). The web ports all of the scroll math (`inputScrollOffset`,
`inputOverflowEdges`, `inputDragScrollDelta` — lib/composer-flip.ts:196-320)
and the mask ramps, then undoes the "no scrollbar" half by setting
`overflowY: auto` inline.

**Web change.** The native wheel is the web's scroll mechanism and stays —
the fix removes only the painted bar and moves the overflow gate out of
inline styles:

1. **composer.tsx:837** — replace the inline
   `el.style.overflowY = mode && contentHeight > inputHeight ? "auto" :
   "hidden"` write with the same boolean written as a data attribute, e.g.
   `el.dataset["scrollable"] = mode && contentHeight > inputHeight ? "true"
   : "false"`. No inline `overflowY` anymore.
2. **composer.tsx:1769** (the wizard's borrowed-input effect, 1761-1770) —
   delete its `el.style.overflowY = el.scrollHeight > 120 ? "auto" :
   "hidden"` write; the same `.composer-input` class rules below cover the
   wizard input (the height auto-grow at 1766-1768 stays).
3. **app.css, `.composer-input`** (3229-3244):
   - keep `overflow: hidden` as the class default and add
     `scrollbar-width: none;` plus
     `.composer-input::-webkit-scrollbar { display: none; }` (the same recipe
     the completion list / model list / traits tray already use,
     app.css:3502-3505, 4024-4033, 4243-4253);
   - add `.composer-input[data-scrollable="true"] { overflow-y: auto; }` so
     the gate that makes the input a scroll container (expanded + content
     taller than the box; wizard: content past 120px) survives as CSS state.
   - `overscroll-behavior: contain` (app.css:3243) already encodes the
     desktop's boundary wheel-swallow; with `data-scrollable="true"` limiting
     the scrollable state, the wheel still chains to the transcript whenever
     the input has no overflow (compact one-liner, empty expanded) — the
     desktop's `on_scroll_wheel` chaining rule.

**Why the gate must survive:** a fully `overflow: hidden` input cannot
wheel-scroll, and the fade ramps read `el.scrollTop` which only native scroll
moves. "Drop the inline auto" means drop the *inline style write* — the
scrollability condition itself is load-bearing and becomes the data attribute.

---

## 3. Pure logic to port

**Nothing new.** The pure layers already exist and match
(`lib/composer-flip.ts`, `lib/composer-send.ts`, `lib/composer-dock.ts`);
this ticket's changes are CSS/inline-style only and must not disturb them.
From the research's port notes:

> Fix verification should reuse the existing suites:
> `tests/composer-flip.test.ts`, `tests/composer-send.test.ts`,
> `tests/model-rows.test.ts`, `tests/traits-summary.test.ts` — all green
> at HEAD per ticket Comments; the S1/S4 fixes are CSS/inline-style only
> and must not disturb them.

The desktop growth state machine (already ported; reference so nothing is
re-derived — all values verified in the research):

| state | condition | what changes |
|---|---|---|
| compact | `composer_flip` false | pill 49; one 47px row (input + cluster inline); body bottom-justified |
| expand decision | `text_width > capacity` (compact) / `has_newline` always / `capacity < 200` always / `resizing` keeps expanded | composer.rs:126-144 |
| collapse decision | `text_width < capacity − 32` (COLLAPSE_HYSTERESIS), deferred `RESIZE_SETTLE_MS` 150 during resize | composer.rs:111-115, 126-144 |
| expanded | flip committed | pill = `clamp(content + 20, 76, 260) + 46 + 2` (124–308); actions row absolute at pill bottom; textarea box in flow above it |
| auto-grow morph | `\|target − last_target\| > 0.5` | 180ms COLLAPSE/EASE_OUT height morph, retargeting mid-flight (composer.rs:169-173 area; ported at lib/composer-flip.ts:187, 470) |
| flip morph | committed mode change only | 180ms; cluster dy 2.5→0, insets 8↔12, text pad 12→16, compact text glide (composer.rs:394-455) |
| new-thread canvas | `expanded_mode \|\| new_chat` | always expanded; mode flips never commit (composer.rs:7488 per ticket 15; web composer.tsx:754-758) |
| dock frame active | shell installs `set_dock_frame` | shared clock owns height (`dock_height`), both morphs killed (composer.rs:4140-4149) |
| wizard mounted | `wizard.is_some()` | the pill is replaced by the question panel in place (composer.rs:7440-7443 per ticket 14) |

---

## 4. Gaps this ticket closes

From the research's S1 (d), verbatim:

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
|---|---|---|---|---|
| Expanded actions-row containing block | WRONG | the pill, full `pill_height` (composer.rs:7734-7758) | `.composer-body`, content-sized = input box (composer.tsx:2716; app.css:3188-3196, 3298-3304) | Add `flex: 1 1 auto; min-height: 0` to `.composer-pill[data-mode="expanded"] .composer-body` (or drop the wrapper and make input-box/actions direct pill children like the desktop) |
| Pill bottom blank band | WRONG | no leftover — actions row fills the pill's bottom 46px (7744+7758 arithmetic) | 46px empty strip under the actions row in every expanded state (boxHeight formula composer.tsx:833) | falls out of the row above |
| Actions row overlaps text | WRONG | textarea box ends where the actions row begins (7744 vs 7752-7758) | row spans `[boxHeight−46, boxHeight]` over the input box's last 46px (composer.tsx:2727-2733 + 833) | falls out of the first row |
| Compact row anchoring | WRONG | pill `justify_end()` — row pinned to the bottom; top sweeps down over it (composer.rs:7801-7805, 7788-7792) | `.composer-pill[data-mode="compact"] .composer-body { height: 47px; flex: none }` top-anchored, no justify-end (app.css:3180-3186) | `justify-content: flex-end` on the compact pill (or `margin-top: auto` on the body) |

From the research's S4 (d), verbatim:

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
|---|---|---|---|---|
| Input scrollbar | INVENTED | none — `overflow_hidden` box + wheel offsets + fades (composer.rs:7746, 2898-2933, 101) | `el.style.overflowY = "auto"` when content overflows (composer.tsx:837) | keep native wheel scrolling but hide the bar: `.composer-input { scrollbar-width: none }` + `.composer-input::-webkit-scrollbar { display: none }` (drop the inline `auto`; the fade masks are the affordance) |
| Wizard input scrollbar | INVENTED | same (the borrowed input is the same editor) | `overflowY: auto` past 120px (composer.tsx:1769) | same class-level fix |

Consolidated table (research "Consolidated gap table"), filtered to this
ticket — rows 1, 2, 3, 11:

| # | item | kind | desktop value (file:line) | web value (file:line) | fix |
|---|---|---|---|---|---|
| 1 | Expanded actions-row containing block | WRONG | pill itself, full height (composer.rs:7734-7758) | `.composer-body` content-sized (app.css:3188-3196; composer.tsx:2716, 2727-2733) | `flex: 1 1 auto; min-height: 0` on the expanded body (or drop the wrapper) |
| 2 | Pill-bottom blank band + row-over-text | WRONG | none (7744+7758) | 46px blank; row overlaps input box's last 46px (composer.tsx:833, 2727) | falls out of #1 |
| 3 | Compact row anchoring | WRONG | pill `justify_end` (composer.rs:7801-7805) | top-anchored 47px body (app.css:3180-3186) | `justify-content: flex-end` on the compact pill |
| 11 | Input scrollbar | INVENTED | none (composer.rs:7746, 2898-2933) | `overflowY: auto` (composer.tsx:837; 1769 wizard) | hide the bar, keep wheel + fades |

---

## 5. Do not

- **Do not** touch `lib/composer-flip.ts`, `lib/composer-send.ts`,
  `lib/composer-dock.ts`, or any morph/flip logic — the pure layers already
  match the desktop; the fixes are CSS/inline-style only. If a value seems to
  need a code change, stop and record it in Comments instead.
- **Do not** drop the `.composer-body` wrapper or otherwise restructure the
  pill's DOM — ticket 13's one-DOM-shape design (the textarea must never
  remount across the flip) stands. The `flex: 1 1 auto` fix is the chosen
  route.
- **Do not** remove the wheel scrolling, the fade ramps
  (`data-fade-top/bottom` masks), or `overscroll-behavior: contain` — they
  are the desktop's affordance and already ported. Do not replace them with
  a styled scrollbar (`scrollbar-width: thin`, custom `::-webkit-scrollbar`
  styling) — the bar is hidden, full stop.
- **Do not** let the wheel over a non-overflowing input be swallowed — the
  scrollable state must stay gated (expanded + content exceeds the box; the
  wizard's 120px cap) so wheel events chain to the transcript otherwise, as
  the desktop's `on_scroll_wheel` does.
- **Do not** fix the model picker chip's loading states, the catalog
  re-kick, the chip's `title` tooltip, or the card's takeover behavior —
  ticket 38 owns them (the S3 "chip placement" row closes here, in 37, via
  the actions-row fix; everything picker-side is 38's).
- **Do not** touch the send path, chat creation, or id minting — ticket 39.
- **Do not** fix transcript scroll / tool-call scroll issues — tickets 40/41
  own those; the composer input is this ticket's only scroll surface.
- **Desktop-only, do not attempt:** the hand-rolled `ComposerTextElement`
  editor (caret blink painting, selection quads, layout caching, utf16
  mapping — composer.rs:1630+), the 700ms/200-step undo coalescing
  (composer.rs:854-857), the appshot strip (composer.rs:523-528, 4903),
  `frost::frosted`/`frost::layered`, `ROBOCO_*` env knobs, the gpui
  `EntityInputHandler` IME machinery, `boot_focus_pending` — all
  desktop-only per tickets 10/13 §5; the native textarea stands.
- **Do not undo ticket 13's recorded deviations:** the defrosted opaque pill
  (deviation 1), the no-8px-subtract capacity (deviation 9), rAF-driven
  morph throttling in hidden tabs (deviation 12). They are decisions, not
  gaps.

---

## 6. Acceptance

- [ ] Expanded, four-line draft: the actions cluster (picker chip, paperclip,
      send) renders pinned to the pill's bottom edge; no 46px blank band
      below it; the last text rows are fully visible above the row (no
      overlap). Taller drafts keep the row pinned while only the text
      scrolls — the pill's bottom edge is stationary.
- [ ] Expanded, one-line draft (the always-expanded new-thread canvas, 124px
      pill): the row sits at the bottom, same as desktop.
- [ ] With staged attachments/comments (strips above the input), the actions
      row still pins to the pill's bottom; the box height formula
      (`composerTotalHeight` + strip heights) is unchanged.
- [ ] Compact rest: visually unchanged (49px pill, one 47px row). During a
      collapse morph the row stays put while the pill's top sweeps down over
      it (frame-step a capture at ~90ms into a 124→49 collapse).
- [ ] No native scrollbar paints in the composer input in any state
      (compact, expanded overflowing, wizard-mounted overflowing), in both
      Chromium and Firefox (`::-webkit-scrollbar` + `scrollbar-width`).
- [ ] Wheel over an overflowing expanded input scrolls the text with the 12px
      fade ramps tracking the settled edges; wheel over a compact or
      non-overflowing input still scrolls the transcript.
- [ ] The wizard's free-text input grows to its 120px cap with no scrollbar
      and still wheel-scrolls past the cap.
- [ ] The existing suites stay green untouched:
      `tests/composer-flip.test.ts`, `tests/composer-send.test.ts` (plus the
      full package run below) — no test file needed modification.
- [ ] Screenshot pair, desktop vs web, states: (a) established chat, compact,
      empty; (b) four-line draft, expanded — row pinned, no blank band;
      (c) eight-line draft at the 260px box cap — text scrolled mid-way with
      both fade ramps showing, no bar; (d) mid-collapse (~90ms) — stationary
      compact row under the sweeping pill top; (e) new-thread canvas (124px)
      with its draft row.
- [ ] `pnpm -r build` green; `web/packages/app` vitest green.
- [ ] No new literal hex/px where a `--rb-*` token exists (the 46/47/12px
      constants are desktop `pub const` mirrors, not token candidates).

## Comments

### Implementation (2026-09-19)

**What landed.** CSS + inline-style-channel work only, exactly the ticket's
regions plus the two gate writes; no pure layer touched
(`lib/composer-flip.ts` / `lib/composer-send.ts` / `lib/composer-dock.ts`
byte-identical to HEAD), no DOM-structure change (the `.composer-body`
wrapper and ticket 13's one-shape design stand):

- **§2.1** `app.css` `.composer-pill[data-mode="expanded"] .composer-body`
  gains `flex: 1 1 auto; min-height: 0` — the body stretches to the pill,
  so the absolute actions row's containing block bottom IS the pill's
  bottom edge (the desktop's pill-level anchor, composer.rs:7752-7758).
  The blank band and the row-over-text overlap fall out of the arithmetic
  (four-line draft: pill 159 = box 111 + row 46 + borders 2; the row spans
  the pill's content box [111, 157], flush under the box). The input-box
  and actions-row rules + their inline styles are untouched.
- **§2.2** `app.css` new rule `.composer-pill[data-mode="compact"] {
  justify-content: flex-end; }` beside the body rules — strips + body
  bottom-justify as one group (empty strips render null, so settled
  compact rest is pixel-identical: the 47px body fills the 49px pill's
  content box exactly); during a collapse the row stays put while the
  pill's top sweeps down over it (composer.rs:7805, 7788-7792).
- **§2.3** `composer.tsx:837` and `:1769` now write the SAME booleans as
  `el.dataset["scrollable"]` (no inline `overflowY` remains anywhere in
  the file); `app.css` `.composer-input` gains `scrollbar-width: none` +
  `.composer-input::-webkit-scrollbar { display: none; }` (the
  completion-list / model-list / traits-tray recipe) and the gate
  `.composer-input[data-scrollable="true"] { overflow-y: auto; }`. Class
  default stays `overflow: hidden`; `overscroll-behavior: contain`, the
  fade masks, the wheel, and the drag-autoscroll are untouched, so the
  wheel chains to the transcript whenever the input has no overflow
  (`on_scroll_wheel`'s chaining rule, composer.rs:2898-2933).

**Deviation / judgment call for a human.**

1. **`.wizard-input-slot .composer-input` lost its `overflow-y: auto`
   line** (the rule the ticket's table does not list — app.css:3784-3789
   at HEAD, ticket 14's wizard slot). Equal specificity + later source
   order than the new gate meant leaving it would keep the wizard's
   borrowed input an UNCONDITIONALLY scrollable container — defeating the
   ticket's own "wizard: content past 120px" gate (§2.3 item 3) and
   violating "Do not" #4 (the wheel must chain while the input fits).
   The slot keeps its `height: auto / min-height: 22.75px /
   max-height: 120px` sizing; overflow now rides the shared
   `data-scrollable` gate. Relatedly, §2.3 item 2's "delete its write"
   was read as "delete the inline style write", per the "Why the gate
   must survive" reconciliation (a fully hidden input cannot
   wheel-scroll): the wizard effect (composer.tsx:1761-1770) writes the
   gate with the same `scrollHeight > 120` condition, so past the cap the
   input still wheel-scrolls (bar hidden) and below it the wheel chains.
2. **No new or modified tests**, per §3/§6 ("no test file needed
   modification") — the S1/S4 fixes are CSS/inline-style only; the named
   suites are the guard and stayed green untouched.

**Verification.**

- `pnpm -r build` (from `web/`) green — proto, engine-client, app
  (tsc --noEmit + vite build).
- `web/packages/app` `pnpm test` green: 72 files / 1165 tests, including
  the untouched `composer-flip` (28), `composer-send` (11), `model-rows`
  (10), `traits-summary` (6) suites.
- The §6 screenshot pair (states a–e, desktop halves included) needs a
  live capture session and is left to the merger, as with tickets
  08/10/12/13; the geometry above is the code-level derivation of (b),
  (c), (e), and the compact-rest invariance of (a)/(d).
