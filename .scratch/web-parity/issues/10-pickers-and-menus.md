# 10 — Pickers and menus

**What to build:** Today the composer's run-identity is four separate
searchable dropdowns (Harness / Model / Effort / Sandbox) stacked as tabs
above the composer, the sidebar's space filter has no search and an inert
sort button, and chat management is a hover-revealed `⋯` kebab. After this
ticket the composer carries **one chip** — harness icon, model name, and a
muted "High · 200K · Off"-style traits summary — that opens **one card**: a
harness tab strip, a search row, a virtualized model list with star
favorites and ⌘1–9 jump chips, and a pinned reasoning/options tray below.
Four small footer chips (device, project, checkout kind, ref) each open
their own focused list popover. The sidebar's space filter gets a search
field, device tags, and a working Organize/Sort/Show menu; right-clicking a
space or a chat opens a real context menu (rename/delete for spaces;
rename/archive/copy-links/delete for chats). The invented sandbox picker
is deleted — the desktop hard-codes `workspace-write` and this stops
pretending otherwise.

**Blocked by:** 09 (Popover primitive)

**Status:** ready-for-agent

**Research:** `../../web-client/research/05-pickers-popovers.md` §1, §2, §3.0
(theme helpers, for lookups), §3.13–§3.23, §4.4–§4.15, §5 rows 1, 2, 3, 4, 5,
6, 7, 8, 9, 12, 13, 14, 15 (row-specific consumer only), 16, 17, 21, 22
(per-region list caps), 23 (application to the identity chip), 31, 32, 39,
41–85, §6, §7.

**Desktop reference (for lookups only):**
- `crates/ui/src/pickers.rs` — `trigger_chip` (2229), `footer_chip`/
  `footer_label` (2341–2422), `popover_frame`/`popover_frame_flush`
  (2733–2797), `render_harness_model_popover` (3143), tab strip
  (3232–3312), search row (3317–3339), list host (3405–3430),
  `render_model_row` (3470), traits tray + `render_traits_sections`
  (3432, 3666), `default_badge` (3780), `render_branch_popover` (2939),
  `render_checkout_popover` (3073), `render_space_popover` (2012),
  `render_device_popover` (1928), `retry_row` (2804), `toggle` (915),
  `on_key_down` (2152), `on_search_submit` (2126), `reasoning_label` (201),
  `default_model`/`default_reasoning`/`clamp_reasoning` (165–195),
  `traits_summary` (221), `offered_options` (252), `traits_customized`
  (270), `scoped_model_rows` (3811), `visible_harnesses`/`offered_harnesses`
  (4031), `normalize_model_rows` (3919), selection resolvers
  (`effective_harness` 713, `effective_model_id` 748, `effective_reasoning`
  762, `selected_model` 781, `trait_ladder` 1547, `explicit_options` 796,
  `rail_descriptors` 1567), persistence (`pick_harness` 1375, `pick_model`
  1395, `pick_reasoning` 1421, `pick_option` 1433, `toggle_model_favorite`
  1680, `remember_target` 1874, `update_chat_config` 1474), `ensure_*`
  loading discipline (1028–1276), checkout semantics (1280, 1359, 1748)
- `crates/ui/src/shell/spaces.rs` — `render_spaces_filter` (978),
  `render_spaces_menu` (1187), `render_sidebar_view_menu` (871), space
  context menu (3336)
- `crates/ui/src/shell.rs` — chat context menu `render_overlays`
  (5433–5608), open at 4697–4707
- `crates/ui/src/settings/composer.rs` — `ComposerDefaults`

**Web files to touch:**
| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/components/composer-pickers.tsx` | rewrite | `ComposerPickers` (the chip + the one identity card), replaces the four-tab `IdentityTab` structure entirely |
| `web/packages/app/src/components/composer-footer.tsx` | edit | the four `footer_chip`/`footer_label` mounts (device, project, checkout, ref) and their popovers — coordinate with ticket 13, which owns this file's row geometry; this ticket owns only the chips and popovers it mounts |
| `web/packages/app/src/state/picker-catalog.ts` | edit | `PickerCatalog`: add `force` flag to `loadHarnesses`/`loadModels`, `targetDeviceId` param, opencode retry backoff, wire `invalidate()` to space/device change |
| `web/packages/app/src/lib/picker-search.ts` | no further change | `matchRank`/`filterAndSort` already fixed by ticket 09; this ticket's `scoped_model_rows` port lives in a new file (below) |
| `web/packages/app/src/lib/model-rows.ts` | new | `scopedModelRows`, `normalizeModelRows`, `visibleHarnesses`, `offeredHarnesses` — the pure model-list logic ported from `pickers.rs` |
| `web/packages/app/src/lib/traits-summary.ts` | edit | fix `traitsActive`'s default-level comparison (gap row 9); keep `reasoningLabel`/`traitsSummary` (already correct) |
| `web/packages/app/src/lib/composer-draft.ts` | edit | verify/extend the sticky-defaults shape against §4.13's `ComposerDefaults` fields (favorites, modelOptionsByModel, modelLabels, device, project, noProject) |
| `web/packages/app/src/components/space-filter.tsx` | rewrite | `SpaceFilter` (trigger + search + rows + device tags), `SidebarViewMenu` (new, Organize/Sort/Show), `SpaceContextMenu` (new, rename/delete) |
| `web/packages/app/src/components/chat-menu.tsx` | rewrite | `ChatRowMenu` → right-click-at-pointer trigger (kebab deleted once right-click works), `ChatMenu` gains icons + a Copy sub-page, rename/delete dialogs keep their current shape |
| `web/packages/app/src/components/sidebar-body.tsx` | edit | wire `SidebarViewMenu` next to `SpaceFilter`; no structural change otherwise |
| `web/packages/app/src/styles/app.css` | edit | rebuild `.identity-*`, `.picker-*` on top of ticket 09's `.popover-card`/`.menu-row` classes; fix `.space-filter*`, `.menu-*`, `.chat-menu*`, `.dialog*` per the gap table |
| `web/packages/app/tests/model-rows.test.ts` | new | unit tests for §3 below |
| `web/packages/app/tests/traits-summary.test.ts` | new/edit | unit tests for `traitsSummary`/`traitsActive`/`offeredOptions`/`traitsCustomized` |

---

## 1. Context a fresh session needs

- This ticket is entirely built on ticket 09's primitives:
  `components/popover/{popup,menu,menu-row,scrollbar,skeleton}.tsx` and
  `lib/{popup-lifecycle,popover-anchor,picker-search}.ts`. Do not re-solve
  card shell, motion, dismissal, or row styling here — import them.
- The composer's entire run identity — harness, model, reasoning, and every
  model-specific option (context window, speed, etc.) — lives behind **one**
  chip and **one** popover card. There is no separate "Harness" tab, no
  "Effort" tab, no "Sandbox" tab. `composer-pickers.tsx` today renders four
  tabs (`IdentityTab`) each opening its own `PickerPopover`; this ticket
  deletes that structure.
- `SandboxLevel` has **no picker anywhere on the desktop**. It is written as
  `"workspace-write"` on chat creation and otherwise preserved from the
  existing chat row. Deleting the sandbox tab and its `SANDBOX_LEVELS`
  const is this ticket's first, mechanical step.
- Four more popovers hang off the composer footer: checkout kind
  (Local/NewWorktree), ref (branch), project (new-chat target), device.
  They share `popover_frame`/`popover_frame_flush` (ticket 09's
  `PopoverCard`) plus `SearchInputFrame` and `MenuRowNav` — build them as
  thin content components, not new card shells.
- The sidebar's space palette (`space-filter.tsx`) and the chat context menu
  (`chat-menu.tsx`) are unrelated features that happen to share the same
  popover/menu-row/dialog primitives from ticket 09. This ticket rebuilds
  both on those primitives in the same pass because they are small and
  independently demoable.
- Vocabulary: "harness" not "provider" (`CONTEXT.md`); "space" not
  "project" in code/state, though the **UI copy** says "project" in several
  places (the trigger reads `"All projects"`, the popover row reads
  `"New project…"`) — copy the strings exactly as given below even where
  they say "project".
- `ComposerDefaults` (`settings/composer.rs`) is the desktop's sticky-picks
  file, loaded synchronously before first paint so the remembered
  harness/model/reasoning show immediately. `lib/composer-draft.ts` is the
  web's nearest analog — verify it carries all of §4.13's fields
  (`harness`, `modelByHarness` as `{id,label}` per harness, `reasoning`,
  `modelOptionsByModel`, `modelLabels`, `device`, `project`, `noProject`,
  `favorites`) before wiring persistence in §2.3–§2.5 below.
- RPC methods this ticket's data layer calls: `ListHarnesses`, `ListModels`,
  `ListRefs`, `SwitchRef`, and `Mutate { op: "setChatConfig" }` — see §3.14
  for exact params.

---

## 2. Spec

### 2.1 `IdentityChip` — the one chip, two tones

Desktop: `trigger_chip`, `pickers.rs:2229–2336`. Called once, for the
harness/model identity (there is no separate chip per facet).

**Layout**

| Property | Value | Source |
|---|---|---|
| element id | `"picker-model"` (siblings: `picker-branch`, `picker-checkout`, `picker-model`, `picker-space`, `picker-device`) | `pickers.rs:2245-2251` |
| height | `32px` | `pickers.rs:2257` |
| max-width | `248px` | `pickers.rs:2258` |
| min-width | `0` (shrinkable — the footer chips share one line) | `pickers.rs:2261` |
| display | `flex row`, `align-items: center` | `pickers.rs:2262-2264` |
| gap | `6px` | `pickers.rs:2265` |
| padding-inline | `10px` | `pickers.rs:2266` |
| border-radius | `8px` | `pickers.rs:2267` |
| border | none | — |
| font-size | `ui_rems(12.0)` | `pickers.rs:2269` |
| font-weight | `500` (medium) | `pickers.rs:2270` |
| color | `hover_blend(id, set ? text.opacity(0.9) : text_muted, text)` — `set` is always `true` for this chip | `pickers.rs:2273-2281` |
| background | open: `theme.element_hover` (snaps, no fade). closed: fades `transparent → element_hover` on hover over `HOVER_FADE` | `pickers.rs:2282-2286` |
| cursor | pointer | `pickers.rs:2288` |
| caret | **none** | doc comment `pickers.rs:2253-2255` |

**Children (in order)**
1. **Brand slot** — the harness brand icon at `16px`, tinted per
   `harnessBrandIcon()` (already correct in `@roboco/icons`), OR a mini
   glyph-spinner (`components/glyph-spinner.tsx`) while `iconLoading` (the
   effective harness is unknown and the catalog is still `Idle`/`Loading`).
2. **Label slot** — `SkeletonBar(56)` while `labelLoading` (no remembered
   label for the effective model id and the catalog/model list is still
   loading); otherwise `min-width: 0; text-overflow: ellipsis` holding the
   model's display label. **Never** the literal string `"Model"` as a
   fallback — resolve the label per §3.5's rule (remembered label → the
   raw id), only falling to a bare id, never a placeholder word.
3. **Suffix slot** — present only when `traitsSummary(...)` is non-null:
   `flex: 1000 1000 auto`, `min-width: 0`, `text-overflow: ellipsis`,
   color `tint ?? text_muted.opacity(0.7)`. The huge shrink factor makes the
   suffix yield FIRST under row pressure so the model name truncates last.

**States**

| State | Condition | What changes |
|---|---|---|
| open | the identity popover is open | background snaps to `element_hover` (no fade) |
| hover | pointer over | background + text fade over 150ms to `element_hover` / `theme.text` |
| icon loading | no effective harness resolved, not the no-agents state, and the harness catalog is `Idle`/`Loading` | brand slot → mini glyph spinner |
| label loading | no remembered label for the effective model AND (harness catalog OR the effective harness's model list) is loading | label slot → `SkeletonBar(56)` |
| no agents | `offeredHarnesses` is empty **and** the catalog has finished loading (not while loading/errored) and no effective harness resolved | label = `"No agents available"`; icon = `terminal` tinted `text_muted` |
| traits customized | `traitsCustomized(model, reasoning, ladder, options)` is true | suffix tint = `text.opacity(0.85)` instead of `text_muted.opacity(0.7)` |
| no traits | `traitsSummary(...) === null` (model has no ladder and no options) | suffix slot omitted entirely |

**Interactions**
- `pointerdown` → `noteTriggerPressMatching((open) => open === "model")`
  (ticket 09's helper).
- `click` → toggle the identity popover open/closed via §2.5's `toggle`.
- No tooltip, no right-click, no drag, no keyboard binding on the chip
  itself.

**Text.** The label is the model's catalog label when the list is loaded;
otherwise the remembered label for the effective id, else the raw id.
**Never** the string `"Default model"`. The suffix is `traitsSummary` (§3.2).

**Placement of the open card.** `anchorAboveEnd` (ticket 09) — the card's
RIGHT edge is flush with the chip's right edge, opening upward with a 6px
gap, clamped 8px inside the window.

**Fallback harness icon.** When no harness resolves and it is not the
no-agents state: the Claude mark tinted `#D97757` (already the default case
in `harnessBrandIcon`).

**Gap this closes**: rows 41 (chip loading states — build the spinner/
ghost-bar slots, never a bare "Model" fallback), 47–51 (mostly MATCHES —
verify colors/geometry/shrink/open-background-snap still hold after the
rewrite; only row 51, the open-state transition, needs an explicit fix:
disable the CSS transition on the open state so it snaps instead of fading).

### 2.2 `FooterChip` / `FooterLabel`

Desktop: `pickers.rs:2341–2422`. The small ghost dropdown buttons for
device, project, checkout kind and ref.

**`FooterChip` layout**

| Property | Value | Source |
|---|---|---|
| height | `20px` | `pickers.rs:2353` |
| max-width | `280px` | `pickers.rs:2354` |
| gap | `6px` | `pickers.rs:2358` |
| padding-inline | `8px` | `pickers.rs:2359` |
| border-radius | `6px` (`FOOTER_CHIP_RADIUS`) | `pickers.rs:2360`, `:34` |
| font-size | `ui_rems(12.0)` | `pickers.rs:2361` |
| font-weight | `500` | `pickers.rs:2362` |
| color | `hover_blend(id, text_muted.opacity(0.7), text.opacity(0.8))` | `pickers.rs:2363-2367` |
| background | open: `element_hover`; else fades `transparent → element_hover` on hover | `pickers.rs:2368-2372` |

Children: leading icon `12px` at `text_muted.opacity(0.7)`; label
`min-width: 0; text-overflow: ellipsis`; trailing chevron-down at `12px`,
`text_muted.opacity(0.5)`.

**`FooterLabel`** (read-only, committed chats): `height: 20px`,
`max-width: 160px`, `min-width: 0`, `gap: 6px`, `padding-inline: 8px`,
`ui_rems(12.0)`, `500`, `color: text_muted.opacity(0.6)`; icon `12px` at
the same color; label truncates. **No chevron, no background, no hover.**

**Where each chip appears** (mount helpers are ticket 09's placement
functions; the row geometry that positions these clusters belongs to ticket
13 — this ticket owns only the chips and the popovers they open):

| Cluster | Chips | Mount helper |
|---|---|---|
| New-chat target row | `picker-device` (monitor icon) + `picker-project` (folder icon), `gap: 4px` | device → `anchorAbove`; project → `anchorAboveEnd` |
| New-chat git row | `picker-checkout` + `picker-branch`, leading-aligned, `gap: 4px` | both → `anchorBelow` |
| Composer footer (draft) | `picker-checkout` then `picker-branch`, left-aligned, `padding-inline: 10px` | both → `anchorAbove` |
| Composer footer (committed chat) | `FooterLabel(folderWithFiles \| folder, "Local checkout" \| "Worktree")` + `FooterLabel(gitBranch, chat.branch ?? "No ref")`, then a flexible spring, then the change-request badge | — |

`workspaceFooterRow()`: `width: 100%`, `min-width: 0`, `flex row`,
`align-items: center`, `gap: 4px`; the footer variant adds
`padding-inline: 10px`. Port test
`workspace_footer_pair_keeps_its_leading_edge_and_gap` (`pickers.rs:4340`)
as a layout assertion: the pair keeps its leading edge across container
widths 320/680/1000px, the gap between the two chips is exactly 4px, they
share a top, and the trailing chip's right edge sits `width - 20` from the
pair's left edge.

**Offline device chip**: text color overrides to `theme.warning.opacity(0.8)`
when the effective device is offline.

**Checkout-kind icon**: `folderWithFiles` when `(Local, no worktree)`, else
`folder`.

**Gap this closes**: row 75 (build all four draft chips + their
read-only variants; `composer-footer.tsx` today renders one static chip).

### 2.3 The harness + model popover

Desktop: `render_harness_model_popover`, `pickers.rs:3143–3464`. Local
constant `LIST_HEIGHT = 216px`. Build as `PopoverCardFlush` (ticket 09),
width **`304px`** fixed, max-height **`640px`** on the frame.

**Card children, in order** — flex column, no gap:
1. Tab strip (§2.3.1)
2. Search row (§2.3.2)
3. List host (§2.3.3) — the virtualized list plus the floating scrollbar
4. Traits tray (§2.3.5) — conditional on the model having a ladder or
   options

**Card-level takeover states** (render *instead of* the whole stack):

| State | Condition | Render |
|---|---|---|
| catalog loading | harness catalog is `Idle`/`Loading` | `height: 216px, padding: 8px` + `SkeletonMenuRows(5)` |
| catalog error | harness catalog is `Error(msg)` | `height: 216px, padding: 8px` + `ErrorRow` + Retry (`retry-row` id `"harness-retry"`) |
| no agents | catalog `Ready` but `rail_descriptors()` (offered harnesses, with the committed one force-included) is empty | `padding: 16px`, flex column, centered, `gap: 8px`: `terminal` icon `20px` at `text_muted`; `"No agents available"` at `ui_rems(13)` in `theme.text`; `"Enable an installed agent in Settings → Agents, or install an agent CLI."` at `ui_rems(12)` in `text_muted`, centered |

#### 2.3.1 Tab strip

`pickers.rs:3232–3312`.

| Property | Value |
|---|---|
| container | `flex: none`, `height: 40px`, `padding-inline: 6px`, `border-bottom: 1px solid hairline(0.08)`, flex row, `align-items: center`, `gap: 2px` |
| tab box | `32 × 32px`, `border-radius: 8px`, `position: relative`, flex centered |
| tab hover | `background: ink(0.06)` — only when not the viewed tab and not disabled |
| viewed marker | `absolute`, `bottom: -4px`, `left: 6px`, `right: 6px`, `height: 2px`, `border-radius: 1px`, `background: theme.accent` — `-4px` lands the bar exactly on the row's bottom hairline (a 32px tab inside a 40px row) |

**Children (left → right)**
1. **Favorites tab** (`id "model-tab-favorites"`): star (bold) icon at
   `15px`, `theme.text` when the favorites view is active, else
   `text_muted.opacity(0.75)`. Click → switch the rail to Favorites, re-anchor
   the keyboard cursor to the resolved model's index, reset scroll to 0,
   scroll it into view.
2. **One tab per offered harness** (`offeredHarnesses`, in catalog order):
   the brand icon at `16px`, colored `tint ?? (isViewed ? text : text_muted)`.
   - `isViewed = !favoritesView && effectiveHarness === harness`.
   - `isDisabled = locked && effectiveHarness !== harness` →
     `opacity: 0.35`, no pointer cursor, no hover wash. **The click handler
     stays attached**: it switches the list back to the locked harness but
     `pickHarness` no-ops while locked.
   - Click (enabled) → switch to that harness's list and `pickHarness`.

**Tabs never hide.** A live search filters only the viewed tab's list;
switching tabs re-scopes the same query.

**Brand marks** — already correct in `@roboco/icons::harnessBrandIcon`
(claude/mock → `claudeMark` tinted `#D97757`; codex → `openaiMark`; cursor
→ `cursorMark`; devin → `devinMark`; grok → `grokMark`; hermes →
`hermesMark`; pi → `piMark`; opencode → `opencodeMark`; all untinted except
Claude). No change needed here — just consume it.

**Gap this closes**: rows 4 (build the strip), 5 (favorites tab + row
star, §2.3.4), 46 (harness-lock visuals: today `.identity-tab-locked` is
`opacity: 0.6` on the whole tab and doesn't reproduce the "click still
switches the list" behavior — fix both the opacity to `0.35` and the click
handler).

#### 2.3.2 Search row

`pickers.rs:3317–3339`.

| Property | Value |
|---|---|
| container | `flex: none`, `height: 40px`, `padding-inline: 10px`, `border-bottom: 1px solid hairline(0.08)`, flex row, `align-items: center`, `gap: 8px` |
| icon | magnifying-glass at `14px`, `flex: none`, `text_muted.opacity(0.7)` |
| input | `flex: 1`, `min-width: 0`, `ui_rems(13.0)`, borderless, transparent background |
| placeholder | `"Search models…"` |

#### 2.3.3 List host

`pickers.rs:3405–3430`.

| Property | Value |
|---|---|
| container | `id "model-list-scroll-host"`, `position: relative`, `flex: none`, `height: 216px`, `padding-block: 6px`, `background: ink(0.02)` |
| hover | arms the floating scrollbar (`MenuScrollbar`'s `listHovered`) |
| list | virtualized (see gap row 17 below), `size: 100%`, `padding-inline: 6px` |
| fallback (empty/loading/error) | non-virtualized, `size: 100%`, flex column, `gap: 2px`, `padding-inline: 6px`, holding one note |
| scrollbar | `MenuScrollbar` as an absolute child so the rail never consumes list width |

**Fallback note precedence**, when the row list is empty:
1. searching (query non-empty) → `"No models found"`
2. favorites view → `"No starred models yet — hit a row's star"`
3. the effective harness's model slot is `Error(msg)` → `ErrorRow` + Retry
   (id `"model-retry"`)
4. otherwise → `SkeletonMenuRows(5)` (id `"model-skeleton"`)

`emptyListNote` layout: `padding: 8px`, `padding-block: 24px`,
`ui_rems(12.0)`, `text_muted.opacity(0.6)`, centered text.

**Gap this closes**: row 17 (virtualize the list — a `uniform_list` in
gpui; on the web use windowing (e.g. render only rows within
`scrollTop ± viewport` plus overscan) since catalogs run to ~7,000 rows and
a plain `.map` will visibly lag), row 43 (use these exact empty-list
strings, not `"No matches."`/`"No models for this harness."`).

#### 2.3.4 `ModelRow`

`pickers.rs:3470–3659`. One row of the virtualized list; its index `ix` is
the row's **global** index — ⌘N chips, hover cursor, and activation all key
on it.

**Outer wrapper**: `padding-bottom: 2px` — bakes the inter-row gap into
each item's own box so every item measures the same height for the
virtualizer.

**Row layout**

| Property | Value |
|---|---|
| padding | `8px` inline, `5px` block when compact (a harness tab) / `6px` block on the favorites tab |
| border-radius | `6px` |
| display | flex row, `align-items: center`, `gap: 10px`, cursor pointer |

**States**

| State | Condition | What changes |
|---|---|---|
| selected | this row's harness === effective harness AND its model === the selected model | `background: card_selected_bg()` (dark `wash(0.11)`, light `wash(0.06)`) + `box-shadow: inset 0 0 0 1px hairline(0.09)` (the ring — `card_selected_shadows()`) |
| keyboard cursor | `ix === active` (and not selected) | `background: ink(0.05)` |
| hover | pointer enter | **moves the keyboard cursor to `ix`** — hovering does NOT paint its own wash, so hover and the cursor never wear two washes at once |

**Children (in order)**
1. **Body** — `flex: 1`, `min-width: 0`:
   - *compact* (harness tab): flex row, `align-items: center`, `gap: 6px` —
     label (`flex: none`, `max-width: 100%`, truncate, `ui_rems(12.5)`,
     medium, `theme.text`); optional attribution (`min-width: 0`, truncate,
     `ui_rems(11.0)`, `text_muted.opacity(0.7)`)
   - *two-line* (favorites tab): flex column, `gap: 2px` — label (same as
     above, `width: 100%`); subline: flex row, `align-items: center`,
     `gap: 6px` → brand icon `11px` at `tint ?? text_muted.opacity(0.7)`;
     harness name (`flex: none`, `ui_rems(11.0)`, `text_muted.opacity(0.7)`);
     when attribution exists, a `"·"` at `ui_rems(11.0)`
     `text_muted.opacity(0.45)` then the attribution
     (`ui_rems(11.0)`, `text_muted.opacity(0.7)`, truncate)
2. **⌘N chip** — `KbdHint("⌘{ix+1}")`, rendered only when `ix < 9`
3. **Star button** — `22 × 22px`, `border-radius: 6px`, flex centered,
   cursor pointer, `hover { background: ink(0.08) }`; icon = star-bold
   (starred) / star-outline, `13px`, color `theme.warning` (starred) /
   `text_muted.opacity(0.45)`. Click **stops propagation** then toggles the
   favorite.

**Attribution**: `model.description`, trimmed, kept only when non-empty and
not case-insensitively equal to the harness name (several opencode
providers advertise identically-named models under different providers, so
this slot carries the provider name).

**Interactions**
- Row click → resolve the model: if the row's harness differs from the
  effective harness, switch harness first (unless locked, in which case the
  click no-ops), then pick the model.
- **The card stays open on a model pick** — model and traits share one
  popover; Esc, click-out, or the chip close it.
- Starring **reorders** the list (stars float to the top / leave the
  favorites view), so the toggle re-homes the keyboard cursor onto the
  SELECTED row afterward so exactly one row reads highlighted.

**Gap this closes**: rows 13 (row layout — today `.picker-row` is
`padding: 6px 10px; justify-content: space-between` with the secondary text
pushed right by `margin-left: auto`, nothing like this two-mode layout),
14 (selected treatment — today no background/ring at all, just a color +
weight change), 15 (keyboard cursor — today a plain `:hover` alongside a
separate highlight class, not the "hover moves the cursor" behavior), 16
(⌘1–9 jump chips and the `Cmd+1…9` binding — see §2.5's key table).

#### 2.3.5 Traits tray

`pickers.rs:3432–3454` (container) + `render_traits_sections`
(`pickers.rs:3666–3774`).

**Container** — rendered only when the model's reasoning ladder is
non-empty OR it has model options:

| Property | Value |
|---|---|
| flex | `flex: none` |
| border-top | `1px solid hairline(0.08)` |
| max-height | `236px`, `overflow-y: scroll` |
| padding | `6px` inline, `6px` bottom |

**Body** — flex column, `padding-bottom: 2px`, children = sections.

**Reasoning section** (when the ladder is non-empty):
- flex column, `gap: 2px`
- `<MenuHeading>Reasoning</MenuHeading>`
- one `MenuRow` per level, overrides `padding-block: 5px`,
  `border-radius: 6px`, `ui_rems(12.5)`:
  - child 1: the level's label (`reasoningLabel(level)`)
  - child 2: a flexible spacer
  - child 3: `DefaultBadge` when this level is `defaultReasoning(ladder)`
  - click → pick this reasoning level; **the tray stays open**

**Model-option sections** — one per `model.options`, in catalog order,
preceded by `MenuSeparator` when it isn't the first section:
- flex column, `gap: 2px`
- `<MenuHeading>{option.label}</MenuHeading>`
- one row per `option.choices`, same overrides as the reasoning rows:
  - `isActive = selectedChoice === choice.id`, where
    `selectedChoice = offeredOptions[option.id] ?? option.defaultChoice`
  - `DefaultBadge` when `choice.id === option.defaultChoice`
  - click → pick this option/choice pair

**Loading**: `SkeletonMenuRows(3)` (id `"traits-skeleton"`) while the
selected model is unresolved.

**`DefaultBadge`**: `flex: none`, `ui_rems(10.0)`, semibold,
`text_muted.opacity(0.6)`, text `"Default"` — bare muted text, no border or
fill.

**There are no check marks in the traits tray.** Selection is the row's
`MenuRow` active wash + `theme.text`.

**Keyboard nav never enters the tray** — ↑/↓ walk the MODEL list only; the
tray's rows are mouse-only.

**Gap this closes**: rows 6 (build the tray as a pinned section of the
same card, not a separate searchable popover), 7 (use `reasoningLabel()` —
already correct in `lib/traits-summary.ts`, just stop feeding raw enum ids
to a generic `stringPickerItems`), 8 (`DefaultBadge`).

### 2.4 The other four picker popovers

All four use `PopoverCard` (ticket 09) at their own fixed width and
`popover_frame`'s `max-height: 640px`.

#### 2.4.1 Ref (branch) popover

`render_branch_popover`, `pickers.rs:2939–3069`. Width **`320px`**.

**Children (in order)**: `SearchInputFrame` (placeholder `"Search refs…"`),
body, then optional trailing sections.

**Body states**

| State | Render |
|---|---|
| no space selected | `padding: 8px`, `ui_rems(12.0)`, `text_faint`, `"No project selected"` |
| `Loading`/`Idle` | `SkeletonRows(4)` (id `"branch-skeleton"`) |
| `Error(msg)` | `ErrorRow` + Retry (id `"branch-retry"`) |
| `Ready`, no rows | `padding: 8px`, `ui_rems(12.0)`, `text_faint`, `"No refs found."` |
| `Ready` | list, flex column, `gap: 2px`, `max-height: 224px`, `overflow-y: scroll` |

**Row** — `MenuRowNav`:
- `opacity: 0.55` on **every** row while any switch is in flight
- child 1: ref name, `flex: 1`, `min-width: 0`, truncate
- child 2 (only the switching row): `"switching…"`, `flex: none`,
  `ui_rems(10.0)`, `text_muted.opacity(0.6)`
- child 3 (tag): `flex: none`, `ui_rems(10.0)`, `text_muted.opacity(0.45)`
  — `"current"` when this is the current ref, else `"worktree"` when it has
  a worktree path, else nothing. Current beats worktree.

**Trailing sections**: switch error (`MenuSection` + `padding: 4px 8px`,
`ui_rems(11.0)`, `danger.opacity(0.9)`, git's verbatim message); cap notice
(`MenuSection` + `padding: 4px 8px`, `ui_rems(11.0)`, `text_faint`,
`"Showing {shown} of {total} refs"` — only when `total > shown`,
`shown = min(total, 300)`).

**Gap this closes**: rows 76 (build), 77 (`"Showing X of Y refs"` cap at
`MAX_REF_ROWS = 300`), 78 (mid-session `SwitchRef` with the `switching…`
tag and the verbatim git error).

#### 2.4.2 Checkout-kind popover

`render_checkout_popover`, `pickers.rs:3073–3131`. Width **`224px`**.
Flex column, `gap: 2px`, exactly two rows:

| ix | Kind | Label | Icon (14px, `text_muted`) |
|---|---|---|---|
| 0 | `Local` | `"Current worktree"` when the picked ref has an existing worktree, else `"Current checkout"` | `folderWithFiles` / `folder` |
| 1 | `NewWorktree` | `"New worktree"` | `folderWithFiles` |

`MenuRowNav`, label `flex: 1, min-width: 0`, truncate. Click picks the kind
and closes the popover.

#### 2.4.3 Project (new-chat target) popover

`render_space_popover`, `pickers.rs:2012–2124`. Width **`280px`**. Flex
column, `gap: 2px`.

**Children (in order)**
1. `SearchInputFrame` (placeholder `"Search projects…"`)
2. body: empty → `padding: 8px`, `ui_rems(12.0)`, `text_faint`,
   `"No projects on this device."` (empty query) or `"No projects match."`
   (non-empty query); else a list (`gap: 2px`, `max-height: 224px`,
   `overflow-y: scroll`), each row `MenuRowNav` with a single
   `flex: 1, min-width: 0` truncated label = the space's display name. **No
   `@ device` tag** — rows are already device-scoped.
3. a one-off local divider: `margin-block: 2px`, `margin-inline: -4px`,
   `height: 1px`, `flex: none`, `background: theme.border.opacity(0.6)`
4. `"New project…"` row: `plus` icon `12px` at `text_muted.opacity(0.7)` +
   truncated label. Click → close this popover, then open the add-space
   palette (ticket 11).
5. `"Don't work in a project"` row (selected when the draft/chat has no
   project): `close` icon `12px` at `text_muted.opacity(0.7)` + label.

Rows are scoped to the canvas's device (pick the device first, then its
project); unscoped only while the device is still unknown.

**Gap this closes**: row 76 (build).

#### 2.4.4 Device popover

`render_device_popover`, `pickers.rs:1928–2006`. Width **`224px`**. Flex
column: `SearchInputFrame` (placeholder `"Search devices…"`) then body.

- empty → `padding: 8px`, `ui_rems(12.0)`, `text_faint`, `"No devices match."`
- else list, `gap: 2px`, `max-height: 224px`, `overflow-y: scroll`

Row — `MenuRowNav`:
1. device name, `flex: 1, min-width: 0`, truncate
2. `"You"` when local device: `flex: none`, `ui_rems(10.0)`,
   `text_muted.opacity(0.45)` — a right-aligned tag, not a name suffix
3. `wifiOff` icon `12px`, `flex: none`, `theme.warning.opacity(0.8)` when
   offline

Device order: this device first, then by lowercased name, then by id.

**Gap this closes**: row 76 (build).

#### 2.4.5 `RetryRow`

`retry_row`, `pickers.rs:2804–2838`. `ErrorRow` (ticket 09) plus a Retry
button:

| Property | Value |
|---|---|
| padding | `8px` inline, `3px` block |
| border-radius | `6px` |
| border | `1px solid theme.border` |
| color | `theme.text` |
| hover | `background: theme.element_hover` |
| text | `"Retry"` |

Click behavior by picker kind: Branch/Checkout → force-reload refs;
HarnessModel → reset the harness catalog to `Idle`, clear every model slot,
force-reload; Space/Device → no-op (they load nothing).

**Gap this closes**: row 44 (position the retry button INSIDE the error
row, not `position: absolute; bottom: -28px` outside the card as today).

### 2.5 Open / close / keyboard model

Port `Pickers::toggle`, `on_key_down`, `on_search_submit` as the
`ComposerPickers` component's open-state controller, using ticket 09's
`Popup` lifecycle underneath.

**Opening a picker (`toggle`)** — in this exact order:
1. If the requested picker is already open, or the trigger's press found it
   open (`takePressWasOpen()`), close it (routing through the "return focus
   to composer" path) and stop.
2. Open the requested picker kind.
3. Reset the search box's text (muting its own change-triggered highlight
   reset for one tick) and set its placeholder to `"Search…"`.
4. For the harness/model popover specifically, prime the tab rail
   **before** anchoring the highlight: `Favorites` when the chat is not
   harness-locked and there are saved favorites, else `Harness`.
5. Anchor the keyboard cursor on the row matching the current pick:
   Checkout → 0 or 1; Branch → the current ref's row; HarnessModel → the
   selected model's row; Space → the current space's row; Device → the
   current device's row.
6. For HarnessModel: reset list scroll to 0, then scroll the anchored row
   into view.
7. Focus: Branch/Space/Device/HarnessModel focus their search input (with
   its placeholder — `"Search refs…"`, `"Search projects…"`,
   `"Search devices…"`, `"Search models…"`); every other kind focuses the
   card itself. Branch also clears any stale switch-error state.
8. Kick a **forced** reload: Branch/Checkout → refs; HarnessModel →
   harnesses + prefetch every offered harness's models; Space/Device →
   nothing (already synced).

**Gap this closes**: row 31 (anchor the keyboard cursor on the selected
row, not always `0`), row 79 (force-refresh on every open, stale-while-
revalidate — do not clear currently-shown rows while the fresh fetch is in
flight).

**Keyboard** (mounted on the card so it sees keys bubbling from the focused
search input):

| Guard / key | Context | Action |
|---|---|---|
| popup not open | (card still mounted through its exit animation) | ignore all keys |
| Cmd/Meta+1…9 | HarnessModel open | activate the Nth **visible** model row |
| Up / Ctrl+P | any | move the cursor via `menuStep(current, count, -1)` |
| Down / Ctrl+N | any | move the cursor via `menuStep(current, count, +1)` |
| Escape | any | close + return focus to the composer |
| Enter, search NOT focused | HarnessModel | activate the highlighted model row |
| Enter, search NOT focused | Checkout | pick the highlighted kind |
| Enter, search NOT focused | others | submit (see below) |
| Enter, search IS focused | any | the input's own submit routes to the same submit handler |
| Backspace / anything else | any | passes through to the input normally |

**Row counts for `menuStep`**: Branch → `min(filteredRefRows.length, 300)`;
Checkout → `2`; HarnessModel → the flattened row count from
`scopedModelRows`; Space → `filteredSpaceRows.length + 1` (the +1 is the
"Don't work in a project" row); Device → `filteredDeviceRows.length`. A
`NO_ACTIVE_ROW` sentinel (no highlight) is used only by the space picker
when nothing is selected — the first Down still lands on row 0.

After an Up/Down in HarnessModel, scroll the highlighted row into view.

**`on_search_submit`**: Branch → pick the highlighted ref; Space → pick the
highlighted space, or "no project" when the cursor sits on the trailing
row; Device → pick the highlighted device; HarnessModel → activate the
highlighted model row.

**Typing resets the highlight**: on every non-muted edit, reset the cursor
to `0` for Branch/Space/Device, and for HarnessModel reset the cursor to
`0` **and** reset the list's scroll offset to `0`.

**Gap this closes**: row 30 (Ctrl+N/Ctrl+P — via ticket 09's
`classifyKey`), row 32 (reset the scroll offset too, not just the highlight,
for the model list specifically), row 34 (Escape returns focus to the
composer; outside-click dismissal does not), row 39 (use these exact
placeholders per kind, not `"Search harnesses…"`/`"Search sandbox…"`/a
generic `"Filter…"`).

### 2.6 Sidebar space filter trigger

`render_spaces_filter`, `spaces.rs:978–1183`.

**Container**: `flex: none`, flex row, `align-items: center`, `gap: 4px`,
`padding: 8px 8px 4px` (top/inline/bottom).

**Trigger**

| Property | Value |
|---|---|
| flex | `flex: 1`, `min-width: 0` |
| height | `29px` |
| gap | `8px` |
| border-radius | `8px` |
| padding-inline | `8px` |
| font-size | `ui_rems(13.0)` |
| font-weight | `500` |
| color | fades `text.opacity(0.8) → text` on hover |
| background | open: `glass_hover()` (= `element_hover`); closed: fades `transparent → glass_hover()` on hover |

**Children (in order)**
1. `folder` icon, `16px`, `flex: none`, `text_muted`
2. `flex: 1, min-width: 0` row, `gap: 6px`: label (truncate — `"All
   projects"` when unfiltered, else the space's display name); device tag
   (only when a space is filtered): `flex: none`, `ui_rems(10.0)`, normal
   weight, `text_muted.opacity(0.45)`, text `"@ {device}"` (unknown device
   → `"@ Unknown device"`); `wifiOff` icon `12px`, `flex: none`,
   `warning.opacity(0.8)` when that device is offline
3. chevron-down at `14px`, `flex: none`, `text_muted.opacity(0.6)` — **no
   rotation on open**, it is static

**Interactions**: `pointerdown` → `noteTriggerPress()`; `click` → close if
the press found it open, else open the menu, mounted via `anchorBelow`
(ticket 09) on a `position: relative` trigger.

**Sort button**

| Property | Value |
|---|---|
| size | `29 × 29px` (square, matches the trigger's height), `flex: none`, flex centered |
| border-radius | `8px` |
| border | transparent, `theme.border_strong` on focus-visible |
| background | open: `glass_hover()`; else transparent; hover: `glass_hover()` — plain hover, **no fade blend** |
| color | `theme.text_muted` |
| icon | `sort` at `16px`, `theme.text_muted` |
| a11y | `role="button"`, `aria-label="Sidebar view options"`, `aria-expanded` |
| tooltip | card: `padding: 6px 8px`, `border-radius: 6px`, `border: 1px solid theme.border_strong`, `background: theme.surface_raised`, `shadow_md`, `ui_rems(11.0)`, `theme.text`, text `"Sidebar view options"`; **350ms show delay** |
| keys | Enter/Space/ArrowDown toggle (ArrowDown only opens, never closes); stop propagation |

Its menu mounts via `anchorBelowEnd` — right-aligned so the full-width card
opens leftward without leaving the sidebar.

**Gap this closes**: rows 52 (trigger geometry — today `padding: 4px 6px`
overridden to `4px 8px; font-size: 12px`, both wrong), 53 (missing leading
folder icon), 54 (missing `@ device` tag + offline glyph), 55 (chevron
today rotates 180° on open — desktop chevron is static, remove the
rotation), 56 (container padding — today `var(--rb-space-sm)
var(--rb-space-md) var(--rb-space-xs)` computes to `8px 12px 4px`; the
desktop's inline padding is `8px`, not `12px`), 66 (build the sort
button's menu — today it's an inert `24 × 24px` button with no handler;
also fix its size to `29 × 29px` and its icon name from `sortVertical` to
`sort`).

### 2.7 Sidebar space menu

`render_spaces_menu`, `spaces.rs:1187–1323`.

**Layout**

| Element | Property | Value |
|---|---|---|
| card | base | `PopoverCard` — radius 12, padding 4, `border: 1px solid hairline(0.10)`, glass tint, `shadow_lg` |
| card | width | `sidebarWidth - 16` (two `SPACE_SM` gutters) — matches the trigger row as the sidebar resizes |
| card | placement | `anchorBelow` — 6px below the trigger, clamped 8px inside the window |
| list | id | `"spaces-menu-list"` |
| list | display | flex column, `gap: 2px` |
| list | **max-height** | **`336px`** (`SPACES_MENU_LIST_MAX_HEIGHT`) |
| list | overflow | `overflow-y: scroll` |
| row | leading icon | `15px`, `flex: none`, `text_muted.opacity(0.8)` — `plus` for "New project…", `folder` for "All projects" and every space |
| row | label | `flex: 1, min-width: 0`, truncate |
| row | **"@ device" tag** | `flex: none`, `ui_rems(10.0)`, `text_muted.opacity(0.45)` |
| row | offline glyph | `wifiOff`, `12px`, `flex: none`, `warning.opacity(0.8)` |

**Children (in order)**
1. `SearchInputFrame` holding the menu's own search input (placeholder
   `"Search projects…"`; its own key handling does not intercept ↑↓/Enter —
   those bubble to the card's key handler)
2. the `"spaces-menu-list"` scroll region above

**Row order**
1. `"All projects"` — **only when the trimmed query is empty**
2. every space matching the query, ranked by `filterIndices` over the
   display name, drawn from the sorted space list
3. `"New project…"` — always last

**Row** — `MenuRowNav`:
- leading icon per the table above
- label `flex: 1, min-width: 0`, truncate
- device tag (spaces only), offline glyph as above
- **No check glyph** — the selected row's wash IS the selection signal

`isSelected`: "All projects" → no filter set; a space → the filter matches
its id; "New project…" → always false.

**Interactions**
- Click → "All projects" clears the filter; a space sets it; "New
  project…" closes the menu then opens the add-space palette (ticket 11).
- **Right-click on a space row** opens the space context menu (§2.9) at the
  pointer position.
- Typing resets the cursor to `0`.

**Opening**: closes the sidebar-view menu first; mints a fresh search
input; anchors the cursor on the row matching the current filter (`0` when
unfiltered or the id isn't found); focuses the search input before first
paint.

**Keyboard**: Escape closes + stops propagation. Up/Down →
`menuStep(cursor, rows.length, ±1)` then scroll it into view. Enter **and**
Cmd/Ctrl+Enter activate the highlighted row. Backspace/other → ignored (falls
through to the input).

**Gap this closes**: rows 57 (build the search input — today none), 58
(build the "New project…" row — always last), 59 ("All projects" should use
`folder`, not `list` — today `space-filter.tsx:84` uses `list`), 60 (device
tag + offline glyph — missing today), 61 (selection is the row wash, not a
`font-weight` bump with no background — fix `.menu-item-picked`), 62 (card
metrics: `max-height` should be `336px` not `320px`, `gap` should be `2px`
not `1px`, radius should be `--rb-radius-card` not `--rb-radius-panel`),
63 (placement gap should be `6px`, not `top: calc(100% - 2px)`), 64 (build
full keyboard nav — today only Escape works), 65 (build right-click →
context menu).

### 2.8 Sidebar view-options menu

`render_sidebar_view_menu`, `spaces.rs:871–973`. **New component**,
`SidebarViewMenu`, opened by the sort button in §2.6.

**Card**: `PopoverCard`, `width: sidebarWidth - 16`, flex column.

**Children (in order)**
1. `<MenuHeading>Organize</MenuHeading>`
2. rows 0–1, `gap: 2px`
3. `MenuSeparator`
4. `<MenuHeading>Sort</MenuHeading>`
5. rows 2–3, `gap: 2px`
6. `MenuSeparator`
7. `<MenuHeading>Show</MenuHeading>`
8. rows 4–6, `gap: 2px`

**Rows, in this exact order** (`SIDEBAR_VIEW_ROWS`):

| ix | Variant | Label | Icon | Selected when | Closes menu |
|---|---|---|---|---|---|
| 0 | `ByDevice` | `"By device"` | `laptop` | sidebar organization is `ByDevice` | yes |
| 1 | `InOneList` | `"In one list"` | `list` | sidebar organization is `InOneList` | yes |
| 2 | `LastUpdated` | `"Last updated"` | `clockCircle` | sidebar sort is `LastUpdated` | yes |
| 3 | `Created` | `"Created"` | `calendar` | sidebar sort is `Created` | yes |
| 4 | `ShowBranch` | `"Branch"` | `gitBranch` | `sidebarShowBranch` is true | **no** |
| 5 | `ShowPullRequest` | `"Pull request"` | `pullRequest` | `sidebarShowPullRequest` is true | **no** |
| 6 | `ShowHarness` | `"Harness"` | `bot` | `sidebarShowHarness` is true | **no** |

Radio-style presentation choices (0–3) dismiss on pick; Show toggles (4–6)
stay open for batch changes.

**Row layout** — `MenuRowNav`:
1. leading icon `15px`, `flex: none`, `text_muted.opacity(0.8)`
2. label in a `flex: 1` div
3. a fixed `14px`-wide `flex: none` slot holding a check glyph `14px` in
   `text_muted` when selected, otherwise empty — **the slot is always
   reserved** so labels never shift

Clicking a row first clears the keyboard cursor (so it doesn't linger on a
mouse-driven pick), then activates.

`ShowPullRequest` additionally sets the change-requests-visible flag.

**Keyboard**: Escape closes. Up/Down → `menuStep(cursor, 7, ±1)` — the
cursor is `Option<usize>` starting at `None`, so the first Down lands on 0
and the first Up on 6. Enter/Cmd+Enter activates the highlighted row
(no-op while the cursor is unset).

**Persistence**: writes `sidebarOrganization`, `sidebarSort`,
`sidebarShowBranch`, `sidebarShowPullRequest`, `sidebarShowHarness` to the
same settings store the sidebar already reads (`state/sidebar.ts` — verify
these keys exist; if not, add them there, not in this ticket's new files).

**Gap this closes**: row 66 (the whole menu is missing on the web today).

### 2.9 Space context menu

`spaces.rs:3336–3389`. **New component**, `SpaceContextMenu`. Opened by
right-click mouse-down on a space row inside the space menu; positioned at
the pointer via `menuAt` (ticket 09, clamp-only).

**Card**: `PopoverCard`, width **`170px`** (the narrowest card in the app —
the chat context menu next door is `216px`), flex column.

**Rows**
1. `MenuRow` — `pen` icon `16px` at `text_muted`, text `"Rename…"`. Click →
   open the rename dialog.
2. `MenuSeparator`
3. `MenuRow`, `color: theme.danger` — `trashBinMinimalistic` icon `16px` at
   `theme.danger`, text `"Remove…"`. Click → close the menu, open the
   delete-confirm dialog.

**Rename dialog** (`DialogCard` from ticket 09): prefilled with the
space's display name, placeholder `"Project name"`, Escape cancels, Enter
submits (empty name is a no-op) → `Mutate { op: "renameSpace", spaceId,
name }`.

**Delete confirm** → `Mutate { op: "deleteSpace", spaceId }`.

**Gap this closes**: row 65 (the whole menu, and its rename/delete
dialogs, are missing on the web today).

### 2.10 Chat context menu

`shell.rs:5442–5608`. Rewrite `ChatRowMenu`/`ChatMenu` in `chat-menu.tsx`.
Opened by **right-click mouse-down** on a chat row (both the active list
and the archived shelf); positioned at the pointer via `menuAt`
(clamp-only, no flip).

**Card**: `PopoverCard`, width **`216px`**, flex column. State =
`{ chatId, position, page: "root" | "copy" }`.

**Root page** (in order)
1. `"Rename…"` — `pen` `16px` `text_muted`. → opens the rename dialog
   (existing `RenameChatDialog`, unchanged).
2. `"Archive"` — `archiveMinimalistic` `16px` `text_muted`. → archives the
   chat (existing behavior, unchanged).
3. `"Copy"` — `copy` `16px` `text_muted`; label in a `flex: 1` div;
   trailing `altArrowRight` at `14px`, `text_muted.opacity(0.7)`. → swaps
   the card's content to the Copy page **in place** — no second floating
   layer, no portal remount.
4. `MenuSeparator`
5. `"Delete…"` — `color: theme.danger`, `trashBinMinimalistic` `16px`
   `theme.danger`. → opens the delete-confirm dialog (existing
   `DeleteChatDialog`, unchanged), specified next.

**Delete-chat confirmation dialog** (`shell.rs:5660-5701`, now sourced —
gap row 74 resolved). `popover::modal("delete-chat-dialog", viewport,
card)` over `popover::dialog_card` (ticket 09's `Modal`/`DialogCard`):
- Title (`dialog_title`): `"Delete session?"`
- Body (`dialog_body`, `margin-top: 6px`):
  `“{title}” will be permanently deleted. This can’t be undone.` —
  `{title}` is the chat title single-lined via `transcript::single_line`,
  falling back to `"New session"` when the chat has no title. The curly
  quotes (U+201C/U+201D) and the apostrophe (U+2019) are literal characters
  in the desktop string, not straight quotes.
- Actions row: `margin-top: 16px`, flex row, `justify-content: flex-end`,
  `gap: 8px`: `BtnGhost` `"Cancel"` (id `delete-chat-cancel`, closes the
  dialog) then `BtnDanger` `"Delete"` (id `delete-chat-confirm`, calls
  `delete_chat`, which fires `Mutate { op: "deleteChat", chatId }`).

This matches the web's existing `DeleteChatDialog` string almost exactly —
keep it as-is; the desktop copy is now verified, not guessed.

**Copy page** (in order)
1. `"Back"` — `altArrowLeft` `16px` `text_muted`. → `page = "root"`.
2. `MenuSeparator`
3. `"Roboco conversation link"` — `copy` `16px`. Writes the conversation
   link to the clipboard; sidebar notice `"Roboco conversation link
   copied"` on success, `"Conversation link is not ready yet"` on failure;
   closes the menu.
4. `<harness link label>` — rendered only when the chat has a resolvable
   harness conversation link; label is that link's own display label.
   Notice on copy: `"{label} copied"`.
5. `"Harness session ID"` — rendered only when the chat has a non-blank
   harness session id. Notice on copy: `"Harness session ID copied"`.

All rows are plain `MenuRow`s — no selection state, no check marks, on
either page.

**Trigger change**: replace the hover-revealed `⋯` kebab
(`chat-row-kebab`) with a right-click (`contextmenu`/mouse-down button 2)
handler on the chat row, positioned at the pointer. **Delete the kebab
button and its CSS once right-click opens the menu correctly** — do not
keep it as a parallel affordance (see Do-not; this is a settled decision,
not the open question the research flags).

**Gap this closes**: row 67 (right-click-at-pointer instead of a kebab),
68 (build the Copy sub-page), 69 (add the 16px leading icon to every row),
70 (card width `216px`, not `160px`/`10rem`), 71 (clamp-only placement, no
flip-above), 72 (delete the `onWheel={onClose}` handler — no wheel
dismissal exists on the desktop), 73 (the `.menu-backdrop` occluder is
fine as a transparent click-catcher; verify it stays fully transparent),
74 (the delete-confirm dialog is now sourced from `shell.rs:5660-5701`,
above — keep the existing `DeleteChatDialog` copy, which already matches).

---

## 3. Pure logic to port

### 3.1 `reasoningLabel(level): string`

`pickers.rs:201–213`: `minimal`→`"Minimal"`, `low`→`"Low"`,
`medium`→`"Medium"`, `high`→`"High"`, `xhigh`→`"X-High"`, `max`→`"Max"`,
`ultra`→`"Ultra"`, `ultracode`→`"Ultracode"`, `ultrathink`→`"Ultrathink"`.
Already correct in `lib/traits-summary.ts:4-14` — no change needed, just
stop bypassing it (§2.1's gap row 7).

### 3.2 `defaultModel` / `defaultReasoning` / `clampReasoning`

`pickers.rs:165–195`.
- `defaultModel(models) = models[0]` — both curated catalogs lead with the
  flagship. Port test `default_model_is_first_catalog_row` (`pickers.rs:4992`).
- `defaultReasoning(ladder)`: `"high"` if the ladder offers it, else
  `"medium"`, else `ladder[0]`, else `null` (ladder-less models). Port test
  `default_reasoning_prefers_high_then_medium` (`pickers.rs:5014`).
- `clampReasoning(level, ladder)`: keep `level` if the ladder lists it,
  else `defaultReasoning(ladder)`. Port test
  `clamp_reasoning_keeps_offered_levels_and_heals_foreign_ones`
  (`pickers.rs:5031`).

### 3.3 `traitsSummary(model, reasoning, selections): string | null`

`pickers.rs:221–247`. The chip's second tone. Already implemented correctly
in `lib/traits-summary.ts:31-52` — verify against these rules and the test
below, but no rewrite expected:
1. Push `reasoningLabel(level)` when `reasoning` is set.
2. For **every** `model.options` entry in catalog order: the effective
   choice is the saved selection when it's a string AND the option still
   offers it, else the option's default; push that choice's label.
3. Join with `" · "`. `null` only when nothing was pushed.

Port test `traits_summary_formats_non_defaults` (`pickers.rs:4760`) into
`tests/traits-summary.test.ts`:
- `(model, "high", {context:"1m", speed:"fast"})` → `"High · 1M · Fast"`
- `(model, null, {})` → `"Standard · Normal"` (all defaults still read)
- a saved choice the option no longer offers falls back to the default:
  `(model, null, {speed:"ludicrous"})` → `"Standard · Normal"`
- `(undefined, "ultrathink", {})` → `"Ultrathink"` (reasoning shows without
  a model)
- `(undefined, null, {})` → `null`

### 3.4 `offeredOptions(model, selections): Record<string, string>`

`pickers.rs:252–265`. Retains only entries whose option id exists on the
model **and** whose value is a string naming one of that option's choices.
Port test (`pickers.rs:4822-4831`): from
`{context:"1m", speed:"ludicrous", fastMode:"on"}` only `{context:"1m"}`
survives (assuming the model offers `context` with choice `1m` but not
`speed:"ludicrous"` or a `fastMode` option).

### 3.5 `traitsCustomized(model, reasoning, ladder, selections): boolean`

`pickers.rs:270–289`. True when `reasoning !== defaultReasoning(ladder)`,
OR any option has a saved string differing from its default choice **and**
still one of that option's choices.

**Fix required** (gap row 9): `lib/traits-summary.ts:64`'s `traitsActive`
currently compares against `model?.reasoningLevels[0] ?? null` — wrong for
any ladder that doesn't start with High. Rewrite it to compare against
`defaultReasoning(ladder)` (§3.2) instead. Port the test assertions
(`pickers.rs:4844-4879`) into `tests/traits-summary.test.ts`:
default-choice selections don't count; the default reasoning level doesn't
count; stale ids don't count; a non-default level (e.g. `"medium"` on a
`["medium", "high"]` ladder) does count.

### 3.6 `scopedModelRows(query, rail, effectiveHarness, descriptors, modelsFor, isFavorite)`

`pickers.rs:3811–3896`. The flattened, ordered model list that keyboard
nav, ⌘N jumps, Enter, and the renderer all walk. Build in the new
`lib/model-rows.ts`.

**The query never leaves the viewed tab.** Scope predicate: Favorites rail
→ `isFavorite(harness, model.id)`; Harness rail →
`descriptor.id === effectiveHarness`.

- **With a query**: rank each in-scope model as
  `min(matchRank(query, label), matchRank(query, "{description} {label}") + 2)`
  — a description-only hit ranks 2/3 while a label prefix is 0 and a label
  substring is 1 (the description stays in the haystack so opencode's
  provider attribution can find its models inside one tab). Sort by
  `(rank, unstarred as 0|1, inputIndex)` — starred rows break ties first,
  then input order.
- **Without a query**:
  - Favorites → every starred model across every descriptor, in descriptor
    then catalog order.
  - Harness → the effective harness's catalog, **partitioned** so starred
    rows come first (stable within each partition), everything else after.

Port tests: `tab_search_never_leaves_the_viewed_harness` (`pickers.rs:4573`),
`favorites_tab_search_ranks_only_starred_rows` (`:4603`),
`harness_tab_lists_stars_first_and_description_still_matches` (`:4644`)
into `tests/model-rows.test.ts`.

### 3.7 `visibleHarnesses` / `offeredHarnesses`

`pickers.rs:4031–4069`.
- `visibleHarnesses`: drops the mock harness unless an env/dev flag selects
  it explicitly, but keeps the whole list when dropping mock would leave
  nothing. Port test `mock_harness_hidden_unless_alone` (`:5044`).
- `offeredHarnesses`: `visibleHarnesses` further narrowed to
  `descriptor.installed && (descriptorEnabled(descriptor) || (allowMock &&
  descriptor.id === "mock"))`. **No fallback** — a catalog where nothing is
  both enabled and installed offers nothing, and the composer shows the
  no-agents empty state and blocks new sends. Port tests
  `offered_harnesses_follow_the_catalog_enabled_flags` (`:5070`),
  `offered_harnesses_require_an_installed_cli` (`:5121`).

**Gap this closes** (row 45): `harnessPickerItems` today renders
uninstalled harnesses as disabled rows with `"CLI not detected"` secondary
text — the desktop filters them out entirely, no disabled row at all. This
whole picker is being replaced by the harness tab strip (§2.3.1), which
already only shows offered harnesses, so this fix falls out of the rewrite
— just don't reintroduce a disabled-row concept anywhere.

### 3.8 `normalizeModelRows(harness, models): Model[]`

`pickers.rs:3919–3994`. Display hygiene for catalogs served by older
engines (the space's device may run any engine version). Idempotent over
already-clean lists.
1. Drop the `"default"` alias row (case-insensitive) when any real row
   exists.
2. For an id ending in `[1m]` or `-1m`: if the bare base id is also listed,
   drop the variant row entirely. Otherwise rewrite the id to the base,
   strip a trailing `" (…)"` suffix from the label (`"Opus (1M context)"` →
   `"Opus"`), and append a `contextWindow` option — label
   `"Context Window"`, choices `200k`→`"200K"` and `1m`→`"1M"`,
   `defaultChoice = "1m"` — unless one already exists.
3. For `claudeCode`, replace the label with the curated catalog's label
   when the normalized id matches exactly, or (for bare alphabetic aliases
   like `opus`) the first flagship-ordered family row whose normalized id
   contains it. Normalization = keep ASCII alphanumerics, lowercase.

Port tests: `normalize_drops_default_alias_and_folds_orphan_1m_rows`
(`:4682`), `normalize_gives_claude_rows_their_versioned_catalog_labels`
(`:4734`) into `tests/model-rows.test.ts`.

**Gap this closes** (row 84): entirely missing on the web today.

### 3.9 Selection resolution

| Function | Rule |
|---|---|
| `effectiveHarness` | draft pick → selected chat's `config.harness` → remembered default harness (only when the loaded catalog still offers it; trusted while unloaded) → the **first offered** harness (never the registry's first, which is mock) |
| `effectiveModelId` | draft pick → chat's `config.model` → remembered model for the harness |
| `effectiveReasoning` | (draft ∥ chat config ∥ remembered), then `clampReasoning` against the trait ladder; returned un-clamped while the catalog is unloaded |
| `selectedModel` | the effective id's row if the loaded list still offers it, else `defaultModel(models)`. Never `null` with a non-empty catalog |
| `traitLadder` | `selectedModel().reasoningLevels`, falling back to the harness descriptor's own `reasoningLevels` |
| `explicitOptions` | existing chat → the chat's `modelOptions`. New chat → `offeredOptions(model, defaults.modelOptionsFor(harness, model.id))`; while the catalog isn't loaded, the raw remembered picks |
| `railDescriptors` | `offeredHarnesses(list)`, with the committed harness force-**inserted at index 0** when it's outside the offered set |
| `selectedModelIndex` | the resolved model's index in the VISIBLE rows; `0` when the favorites/search view doesn't contain it, or while loading |
| `selectedSpaceIndex` | the trailing "opt-out" row's index when the draft/chat has no project; else the current space's index; else `NO_ACTIVE_ROW` (no highlight until the user navigates) |
| `selectedDeviceIndex` | the effective device's index in the device list, else `0` |
| `selectedRefIndex` | the chat's branch, else the draft pick, else the current ref; capped to `299` (`MAX_REF_ROWS - 1`) |
| `noAgentsAvailable` | the harness catalog has finished loading AND `offeredHarnesses` is empty. **False while loading or errored** |

### 3.10 Where each selection is persisted

| Pick | Existing chat | New chat |
|---|---|---|
| Harness | **blocked** (chats lock their harness once created) | `config.harness` + the remembered default; clears `config.model`/`config.reasoning` when the harness actually changes; kicks a model reload; resets list scroll; re-anchors the keyboard cursor |
| Model | `Mutate setChatConfig(config.model = id)` | `config.model` + remember `(harness, id, label)` |
| Reasoning | `Mutate setChatConfig(config.reasoning = level)` | `config.reasoning` + the remembered global default (not per-harness) |
| Model option | `Mutate setChatConfig`: remove the key when the choice IS the default, else insert `optionId -> choiceId` | the per-model-options store, same remove/insert rule; only stored when the currently selected model actually offers that option |
| Favorite | the shared favorites list (not per-chat) | same |
| Project / device | the remembered device/project/no-project defaults | same |

`updateChatConfig`: builds the config from the resolved current state,
preserves the existing row's sandbox value, applies the change,
**re-clamps reasoning** to the (possibly just-changed) model's ladder, and
re-runs `offeredOptions` so a model switch can't carry picks the new model
doesn't offer (e.g. a 1M-context pick surviving a switch to a model without
that option). Then it optimistically updates local state (the chip updates
on click before the RPC round-trips) and fires the `Mutate` call.

`ComposerDefaults` persists atomically (temp-file + rename) and loads
synchronously before first paint. Fields: `harness`, `modelByHarness`
(`{id, label}` per harness), `reasoning`, `modelOptionsByModel` (harness →
model id → option map), `modelLabels` (id → label cache, the chip's
fallback while a list loads), `device`, `project`, `noProject`, `favorites`
(`[{harness, model}]`, in starring order).

**Gap this closes** (row 85): verify `lib/composer-draft.ts` carries every
one of these fields; extend it if any are missing (particularly
`favorites`, `modelOptionsByModel`, and `modelLabels`, which the current
four-tab picker never needed).

### 3.11 Loading discipline (`ensure*`)

`pickers.rs:1028–1276`. Three rules for `state/picker-catalog.ts`:
1. **Non-forced loads only fire from `Idle`.** An `Error` must not
   re-trigger a load from the render loop (it would flip back to `Loading`
   before the retry row ever painted, and spam the engine). Retry resets to
   `Idle` first.
2. **Forced loads** (a picker open, a Settings→Agents toggle) go through
   `Ready`/`Error` too, because the enabled set can move under the cache.
3. **Stale-while-revalidate**: a forced refresh of an already-`Ready` slot
   does NOT flip to `Loading` — the currently-shown rows stay on screen
   while the fresh catalog lands.

`ensureModels` retries a failed `ListModels` **twice** for the `opencode`
harness only, backing off `attempt * 2` seconds (2s, then 4s), keeping one
`Loading` slot alive so recovery needs no close/reopen and can't launch
duplicate probes.

**RPC methods and params**

| Method | Wire name | Params |
|---|---|---|
| list harnesses | `"ListHarnesses"` | `{ targetDeviceId? }` |
| list models | `"ListModels"` | `{ harness, targetDeviceId? }` |
| list refs | `"ListRefs"` | `{ repoPath, targetDeviceId? }` |
| switch ref | `"SwitchRef"` | `{ repoPath, refName, targetDeviceId? }` |
| write chat config | `"Mutate"` | `{ op: "setChatConfig", chatId, config }` |

`targetDeviceId` is present only when the space's device differs from the
connected engine's own device — harness/model catalogs come from the
device that RUNS the agents.

**Cache invalidation**: drop draft picks when the selected chat changes; on
a space/device change, bump a generation counter, cancel in-flight loads,
clear the branch draft + checkout kind, reset refs, **and reset both the
harness and model catalogs** (they're per-device). Compare the generation
on every response to drop stale ones. A global "harness catalog changed"
marker lets Settings → Agents force every open composer to refresh.

**Gap this closes** (rows 79–83): `PickerCatalog.loadHarnesses`/
`loadModels` today no-op once `loaded` (no `force` parameter at all);
`{ }`/`{ harness }` params carry no `targetDeviceId`; there's no eager
prefetch of every offered harness's models (one harness loads at a time);
there's no opencode retry backoff; `invalidate()` exists
(`picker-catalog.ts:179`) but nothing calls it on space/device change. Add
a `force: boolean` parameter to both load methods that skips the `loaded`
guard and does **not** clear currently-shown rows (stale-while-revalidate);
add `targetDeviceId` to both RPC calls when the space's device differs from
the connected engine's; add the opencode 2s/4s retry inside `loadModels`;
wire `invalidate()` into whatever emits space/device-change events today.

### 3.12 Checkout semantics

`pickers.rs:1280–1304`, `:1359–1373`, `:1748–1788`.
- Picking a ref is a **no-op on an existing chat** (refs are fixed at
  creation).
- A ref with an existing worktree → `branch = name`, `checkout = "local"`
  (reuse that worktree).
- `checkout === "newWorktree"`, or the ref is already current → just record
  `branch = name`.
- Local mode + a plain non-current ref → **check out** the space folder via
  `SwitchRef`; success records the pick, closes the popover, and
  force-refreshes refs; failure keeps the popover open with git's verbatim
  message. One switch at a time.
- Picking `Local` checkout from `NewWorktree` with a non-current plain ref
  picked drops the branch override (the current branch takes over).
- `checkoutPlan()`: `{ kind: "newWorktree", base }` |
  `{ kind: "reuseWorktree", path, branch }` |
  `{ kind: "currentCheckout", branch }`.
- `checkoutLabel()`: `"New worktree"` | `"Current worktree"` |
  `"Current checkout"`.
- `refLabel()`: `"Select ref"` when nothing resolves; `"From {name}"` in
  NewWorktree mode; the bare name in Local mode.

**Gap this closes** (row 78, mid-session switch UI already specified in
§2.4.1): implement `pickRef`/`pickCheckout` with exactly these rules.

---

## 4. Gaps this ticket closes

| # | Item | Kind | Desktop value | Web value (file:line) | Fix |
|---|---|---|---|---|---|
| 1 | Sandbox picker | INVENTED | no sandbox picker exists anywhere in `crates/ui`; `SandboxLevel::WorkspaceWrite` is written on create and preserved otherwise (`pickers.rs:153`, `:1489`) | `composer-pickers.tsx:30`, `:119`, `:176-185` — a `SANDBOX_LEVELS` list + a Sandbox tab + its own popover | Remove the sandbox tab, the `SANDBOX_LEVELS` const, and its popover. Keep writing `"workspace-write"`. |
| 2 | Four-tab identity popover | INVENTED | one card: harness icon tab strip, search row, virtualized model list, pinned traits tray (`pickers.rs:3456-3463`) | `composer-pickers.tsx:114-121` `.identity-tabs` + four separate `PickerPopover`s | Rebuild as the single card of §2.3 |
| 3 | `.identity-tabs` positioning | INVENTED | — | `app.css:1847-1858`: `bottom: calc(100% + 324px)`, its own bordered box | Delete |
| 4 | Harness tab strip | MISSING | 40px row, described in §2.3.1 | — | Build |
| 5 | Favorites tab + per-row star toggle | MISSING | described in §2.3.1/§2.3.4 | — | Build; persist favorites in `composer-draft.ts` |
| 6 | Traits tray | WRONG BEHAVIOR | pinned tray inside the same card, keeps the card open on pick (§2.3.5) | `composer-pickers.tsx:166-175` — a separate searchable popover of raw level ids | Build the tray; drop the reasoning popover |
| 7 | Reasoning labels | WRONG VALUE | `"Minimal"`…`"Ultrathink"` (`pickers.rs:201`) | raw enum ids (`composer-pickers.tsx:74`) | Use `reasoningLabel()` |
| 8 | `"Default"` badge | MISSING | described in §2.3.5 | — | Build |
| 9 | `traitsActive` default level | WRONG BEHAVIOR | `defaultReasoning(ladder)` = High → Medium → first | `model?.reasoningLevels[0]` (`lib/traits-summary.ts:64`) | Port `defaultReasoning` |
| 12 | Model search haystack | MISSING | also ranks `"{description} {label}"` at rank+2; starred rows break ties before input order (`pickers.rs:3844-3861`) | label only | Port `scopedModelRows` |
| 13 | Model row layout | WRONG | described in §2.3.4 | `.picker-row`: `padding: 6px 10px`, `justify-content: space-between` | Rebuild |
| 14 | Model row selected treatment | WRONG VALUE | `card_selected_bg()` + inset ring (`pickers.rs:3515-3518`) | `.picker-row-selected` — color + weight only, no background, no ring | Apply the wash + ring |
| 15 | Model row keyboard cursor | WRONG VALUE | `ink(0.05)`; hover MOVES the cursor rather than painting its own wash | `.picker-row-highlight` + a separate `:hover` | Match |
| 16 | ⌘1…⌘9 jump chips + bindings | MISSING | `KbdHint("⌘{n}")` on rows 0–8; Cmd+1…9 picks the Nth visible row | — | Build |
| 17 | Virtualized model list | MISSING | `uniform_list` — a 7k-model catalog must scroll smoothly | plain `.map` | Virtualize (windowing) |
| 21 | Card width | WRONG VALUE | harness/model `304px`; ref `320`; checkout `224`; project `280`; device `224` | `min-width: 220px; max-width: 320px` | Fixed widths per picker |
| 22 | Per-region max-height | WRONG VALUE | model list band fixed `216px`; traits tray `max-h 236`; ref/project/device lists `max-h 224` | `max-height: 320px` on the whole popover | Match per region |
| 23 | Placement (identity chip) | WRONG | `anchorAboveEnd` — RIGHT edge flush with the chip | `.picker-popover { bottom: calc(100% + 6px); left: 0 }` — left-aligned | `right: 0` + viewport clamp |
| 31 | Highlight anchoring on open | MISSING | starts ON the selected row | `useState(0)` always | Anchor on open |
| 32 | Highlight reset while typing | WRONG | resets to 0 and, for models, resets scroll too | resets on `[query, items.length]`, no scroll reset | Add the scroll reset |
| 39 | Per-picker placeholders | WRONG VALUE | `"Search refs…"`, `"Search projects…"`, `"Search devices…"`, `"Search models…"` | `"Search harnesses…"`, `"Search sandbox…"`, generic `"Filter…"` | Use the desktop strings |
| 41 | Chip loading states | MISSING | brand→spinner, label→ghost bar, never a bare fallback word | plain fallback text `"Model"` | Build |
| 42 | `"No agents available"` state | MISSING | full-card takeover (§2.3 table) | `"No harnesses installed."` empty hint | Build |
| 43 | Empty-list copy | WRONG VALUE | `"No models found"`, `"No starred models yet — hit a row's star"`, `"No refs found."`, `"No projects on this device."`, `"No projects match."`, `"No devices match."`, `"No project selected"` | `"No matches."`, `"No models for this harness."`, `"No harnesses installed."` | Match verbatim |
| 44 | Retry affordance | WRONG | inline inside `ErrorRow` | `.composer-pickers-retry { position: absolute; bottom: -28px }` outside the popover | Move inside; match metrics |
| 45 | Harness "CLI not detected" row | INVENTED | filters uninstalled harnesses out entirely | `harnessPickerItems` renders them disabled with secondary text | Filter them out (falls out of the §2.3.1 rewrite) |
| 46 | Harness lock | WRONG | locked tabs stay visible at `opacity: 0.35`, no pointer, click still switches the list | `.identity-tab-locked { opacity: 0.6 }` on the tab only | Match |
| 47 | Chip `set` tone | MATCHES | `text.opacity(0.9)` when set | already correct | Keep |
| 48 | Chip suffix shrink | MATCHES | `flex: 1000 1000 auto` | already correct | Keep |
| 49 | Chip suffix colors | MATCHES | `text_muted@0.7`, active `text@0.85` | already correct | Keep |
| 50 | Chip geometry | MATCHES | `h 32, max-w 248, gap 6, px 10, radius 8, 12px medium` | already correct | Keep |
| 51 | Chip open background | WRONG VALUE | open SNAPS (no fade); closed hover fades | both use the same transition | Disable the transition on the open state |
| 52 | Space filter trigger geometry | WRONG VALUE | `h 29, gap 8, px 8, radius 8, ui_rems(13) medium` | `padding: 4px 6px` (then overridden to `4px 8px; font-size 12px`) | Match |
| 53 | Space filter leading folder icon | MISSING | `folder` 16px `text_muted` | no leading icon | Add |
| 54 | Space filter `@ device` tag | MISSING | `ui_rems(10)`, `text_muted@0.45`, `+ wifiOff@0.8` when offline | — | Add |
| 55 | Space filter chevron | WRONG VALUE | `altArrowDown` 14px, `text_muted@0.6`, **no rotation** | rotates 180° via `.chevron-open` | Remove the rotation |
| 56 | Space filter container padding | WRONG VALUE | `px 8, pt 8, pb 4, gap 4` | `8px 12px 4px` computed | Match (inline padding is 8, not 12) |
| 57 | Space menu search input | MISSING | `SearchInputFrame` + `"Search projects…"`; query removes the "All projects" row | none | Build |
| 58 | Space menu "New project…" row | MISSING | always last | — | Build |
| 59 | Space menu row icons | WRONG VALUE | `folder` for both "All projects" and every space | `list` for All, `folder` for spaces | "All projects" uses `folder` |
| 60 | Space menu device tag + offline glyph | MISSING | `"@ {device}"` + `wifiOff@0.8` | — | Build |
| 61 | Space menu selection signal | WRONG | row wash IS the signal, no check glyph | `.menu-item-picked` — color + weight, no wash | Use `card_selected_bg()` wash, drop the weight change |
| 62 | Space menu card metrics | WRONG VALUE | `w = sidebarWidth - 16`, radius 12, padding 4, list max-h 336, row gap 2 | `left/right: var(--rb-space-sm)`, `max-height: 320px`, `padding: var(--rb-space-xs)`, radius 10, `gap: 1px` | Match |
| 63 | Space menu placement | WRONG VALUE | `anchorBelow` — 6px gap, clamped 8px | `top: calc(100% - 2px)` | 6px gap |
| 64 | Space menu keyboard nav | MISSING | ↑↓ wrap + scroll-into-view, Enter/Cmd+Enter activate, Esc closes | Escape only | Build |
| 65 | Space menu right-click → rename/remove | MISSING | context menu at the pointer, card `w 170` | — | Build |
| 66 | Sidebar view-options menu | MISSING | `SORT` button 29×29 + Organize/Sort/Show card, 350ms tooltip | inert 24×24 button, no handler | Build |
| 67 | Chat menu trigger | WRONG | right-click mouse-down, positioned at the pointer | left-click on a hover-revealed kebab | Right-click at pointer; delete the kebab |
| 68 | Chat menu Copy sub-page | MISSING | in-place page swap | — | Build |
| 69 | Chat menu row icons | MISSING | every row has a 16px leading icon | text-only buttons | Add |
| 70 | Chat menu card width | WRONG VALUE | `216px` | `160px` / `10rem` | 216px |
| 71 | Chat menu placement | WRONG BEHAVIOR | `menuAt` clamps only, no flip | flips above on overflow | Clamp only |
| 72 | Chat menu `onWheel` closes | INVENTED | no wheel dismissal anywhere | `chat-menu.tsx:121` | Remove |
| 73 | Chat menu opaque backdrop div | INVENTED (harmless) | context menus have no scrim, only an occluder | `.menu-backdrop { position: fixed; inset: 0 }` | Fine as an occluder; keep it transparent |
| 74 | Delete confirm copy | MATCHES (now sourced) | `shell.rs:5660-5701`: `modal(dialog_card)`, title `"Delete session?"`, body `“{title}” will be permanently deleted. This can’t be undone.` (curly quotes literal), `btn_ghost` Cancel + `btn_danger` Delete → `Mutate { op: "deleteChat", chatId }` | `"“{title}” will be permanently deleted. This can't be undone."` (`chat-menu.tsx:236`) | Keep the existing `DeleteChatDialog` copy — it already matches |
| 75 | Footer chips | MISSING | four `FooterChip`s (§2.2) | one static chip in `composer-footer.tsx` | Build the draft chips and their four popovers |
| 76 | Ref / checkout / project / device popovers | MISSING | §2.4.1–§2.4.4 | — | Build |
| 77 | `"Showing X of Y refs"` cap | MISSING | `MAX_REF_ROWS = 300` | — | Build |
| 78 | Mid-session ref switch | MISSING | `SwitchRef` with `switching…` tag, dimmed rows, verbatim error | — | Build |
| 79 | Catalog force-refresh on open | MISSING | every open forces a reload, stale-while-revalidate | `loadHarnesses`/`loadModels` no-op once loaded | Add `force`, keep stale rows visible |
| 80 | Catalog per-device targeting | MISSING | `targetDeviceId` when the space's device ≠ the connected engine's | `{ }` / `{ harness }` | Add |
| 81 | Catalog eager prefetch | MISSING | every offered harness's models load in parallel | one harness at a time | Add |
| 82 | Catalog opencode retry | MISSING | two extra attempts at 2s/4s | — | Add |
| 83 | Catalog invalidate on chat/space/device change | PARTIAL | drops draft picks + resets both catalogs, generation-gated | `invalidate()` exists, not wired | Wire it |
| 84 | `normalizeModelRows` | MISSING | drop `default` alias, fold orphan `[1m]`/`-1m` rows, adopt curated Claude labels | — | Port |
| 85 | Sticky defaults shape | MISSING/PARTIAL | full `ComposerDefaults` shape (§3.10) | `lib/composer-draft.ts` — verify coverage | Extend to the full shape |

---

## 5. Do not

- **Do not build a sandbox picker.** The desktop has none; `SandboxLevel`
  stays hard-coded to `"workspace-write"` on chat creation and otherwise
  preserved from the existing row. This is a settled decision (spec.md
  "Decisions"), not an open question — delete on sight.
- **Delete the kebab trigger once right-click opens the chat menu
  correctly.** Do not keep both as parallel affordances "for touch" — the
  research raises this as an open question, but the ticket brief for this
  work item settles it: right-click only, kebab removed. If a future ticket
  decides touch needs its own affordance, that is a new decision, not a
  default to preserve here.
- Do not build a nested-popover / second-floating-layer primitive for the
  chat menu's Copy page — it replaces the card's content in place.
- Do not add a focus trap, a hover-open delay (other than the one named
  350ms tooltip in §2.6), or a scrim to any of these menus — see ticket 09's
  Do-not list, which this ticket inherits.
- Do not reimplement the card shell, motion, dismissal, row hover fade, or
  skeleton pulse from scratch — import ticket 09's primitives.
- Do not touch `preview-panel.tsx`, the composer's row-assembly / width-flip
  logic, or anything in `composer.tsx`'s own layout — those are ticket 04's
  research surface and tickets 13/17's job. This ticket owns only the chips
  and the popovers they open.
- Do not build the add-space palette (`⌘K`) — that is ticket 11, even
  though the project popover's "New project…" row opens it.
- Do not build text-completion / `@`-mention / `/`-slash popovers — that is
  ticket 14, even though it reuses `anchorAboveAt` from ticket 09.
- Do not change the delete-confirmation dialog's copy (gap row 74, sourced
  from `shell.rs:5660-5701` in §2.10) — it already matches the desktop
  verbatim, curly quotes included.
- Do not port `ROBOCO_OPEN_PICKER` / `ROBOCO_SLOW_CATALOG_MS` /
  `ROBOCO_HARNESS=mock` dev-only env knobs, or the `boot_focus_pending`
  re-claim loop — desktop-only test/boot-race workarounds.

---

## 6. Acceptance

- [ ] The composer shows exactly one identity chip (harness icon + model
      name + optional muted traits suffix); no Harness/Model/Effort/Sandbox
      tab row exists anywhere.
- [ ] Clicking the chip opens one card: tab strip (favorites + one tab per
      offered harness) → search row → virtualized, scrollable model list
      with ⌘1–9 chips and star toggles → pinned traits tray. The card stays
      open after picking a model.
- [ ] Starring a model reorders the list and the keyboard cursor lands back
      on the selected row.
- [ ] The four footer chips (device, project, checkout, ref) each open
      their own popover at the documented width with search + list +
      loading/error/empty states; committed chats show the read-only
      `FooterLabel` variant instead.
- [ ] The sidebar space filter shows a leading folder icon, an `@ device`
      tag when filtered, a static (non-rotating) chevron, and the sort
      button opens a working Organize/Sort/Show menu that persists its
      picks.
- [ ] The sidebar space menu has a search field, ranks results via
      `filterIndices`, shows device tags, and a right-click on a space row
      opens a rename/delete context menu.
- [ ] Right-clicking a chat row (active or archived) opens a context menu
      at the pointer with Rename…/Archive/Copy▸/—/Delete…; Copy swaps to a
      Back/link rows page in place; the kebab button no longer exists.
- [ ] Unit tests: `default_model_is_first_catalog_row`,
      `default_reasoning_prefers_high_then_medium`,
      `clamp_reasoning_keeps_offered_levels_and_heals_foreign_ones`,
      `traits_summary_formats_non_defaults`,
      `tab_search_never_leaves_the_viewed_harness`,
      `favorites_tab_search_ranks_only_starred_rows`,
      `harness_tab_lists_stars_first_and_description_still_matches`,
      `mock_harness_hidden_unless_alone`,
      `offered_harnesses_follow_the_catalog_enabled_flags`,
      `offered_harnesses_require_an_installed_cli`,
      `normalize_drops_default_alias_and_folds_orphan_1m_rows`,
      `normalize_gives_claude_rows_their_versioned_catalog_labels`,
      `workspace_footer_pair_keeps_its_leading_edge_and_gap` → their web
      ports in `tests/model-rows.test.ts` / `tests/traits-summary.test.ts`.
- [ ] Screenshot pairs, desktop vs web: (1) identity card open on the
      Harness tab with several models, one starred, one selected; (2)
      traits tray open with a non-default reasoning level picked; (3) the
      no-agents empty state; (4) sidebar space menu open with a query
      typed; (5) sidebar view-options menu open; (6) chat context menu root
      page and Copy page; (7) space context menu.
- [ ] `pnpm -r build` green; package vitest green.
- [ ] No new literal hex/px where a `--rb-*` token exists.

## Comments

(empty; appended during implementation)

- Gap row 74 (delete-confirm dialog) is now sourced from
  `shell.rs:5660-5701` (§2.10) and the desktop copy says "session", not
  "chat" — a pre-rename wording mismatch against `CONTEXT.md`'s vocabulary
  (chat, not session). Kept verbatim per the 1:1 rule; flagging the
  mismatch here rather than silently renaming it.
