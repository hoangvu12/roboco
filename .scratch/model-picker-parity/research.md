# Model picker parity — research (no implementation yet)

**Tickets:** `issues/01-phone-sheet-full-width.md` (ready), `02-picker-list-truth.md`
(ready), `03-chip-spec.md` (ready), `04-locked-chat-scoping.md` (ready),
`05-compact-starred-scoping.md` (ready), `06-sheet-escape-focus.md` (ready),
`07-recorded-decisions.md` (needs-triage).

**Status:** research only. Sources: `crates/ui/src/` (desktop, the reference) and
`web/packages/app/src/` (web, the target), read directly. Companion survey:
`.scratch/web-parity-next/research.md`.

**User report:** the web model picker differs from the desktop's; on mobile the
picker sheet is not full width ("even the current model picker doesn't have
full width as a drawer").

## 1. The desktop reference — `render_harness_model_popover` 1:1 inventory

Two presentations share one code path. **The compact card (256px) is the
DEFAULT** (`settings.rs:1081` `compact_model_picker: true`; toggle in
`settings/shortcuts.rs:397-430`); the standard card is the opt-out. The web's
default matches (`ui-settings.ts:501`).

### Standard card (`pickers.rs:3799`, width 304)

- **Frame** (`pickers.rs:5460-5466` → `popover_frame_flush` :3468-3517,
  `popover.rs:306-331`): radius 12, 1px border, `shadow_lg` (opaque only),
  `surface_bg`, `overflow_hidden`, 13rem text, 44px frost blur (frost.rs:26).
- **Height band**: `menu_geometry().height.min(open_model_height)`
  (:3477); `menu_geometry` = available space at trigger minus 14px window
  margin, prefer **above**, flip below only if above<180 && below>above,
  **capped 640** (popover.rs:2525-2539). `open_model_height = 82 + 216 + tray`
  (`model_menu_height` pickers.rs:5184-5193; tray = min(settings·32+7, 236)).
  `model_menu_budgets` (:5195-5203): body = height−82; tray =
  min(desired, body−30); **list = body − tray** — the list absorbs tray
  changes so tabs/search stay stationary.
- **Top→bottom**: (1) catalog takeovers (:3805-3834 — loading/idle → 5
  skeleton rows; error → retry row; full-card, above the tabs); (2) tab strip
  (:3882-3961 — h 40, px 4, bottom hairline 0.08, gap 2; one **favorites star
  tab** 32×32 + one brand-icon tab per offered harness, icon 16px tinted;
  the **viewed marker** is a 2px accent bar at bottom −4, inset 6, rounded 1
  (:4885-4895); tabs never hide during search); (3) search row (:3976-4000 —
  h 40, px 10, bottom hairline, gap 8, magnifier 14px, borderless 13rem input,
  placeholder "Search models…"; **omitted in compact**); (4) refresh-error
  retry row between search and list (:4056-4075); (5) list host
  (:4076-4100 — relative, flex_none, h list_height, py 4, bg ink(0.02);
  **virtualized** `uniform_list` (:4005-4030) px 4, 12px edge fades, floating
  scrollbar rail (popover.rs:1393-1410: thumb 3px/5px hover, 10px hit strip,
  min 24px, linger 1400ms, fade 260ms)); (6) **traits tray** (:4103-4147 —
  only when `!compact && title.is_none() && (ladder ∥ options)`; border_t
  hairline 0.08, h tray_height, overflow_y_scroll, px 4, sections pt 4 pb 4
  gap 2).
- **Empty states** (:4032-4050): "No models found" / "No starred models yet —
  hit a row's star" / slot error retry / 5 skeletons.
  `empty_list_note` (:5000): px 8 py 24, 12rem text_muted. Skeletons
  (popover.rs:1352-1380): rows h 14, widths cycle 42/58/48/66%, gap 8,
  py 6 px 4, opacity 0.35+0.4·pulse, 0.08 stagger.

### Rows (`render_model_row` pickers.rs:4153-4339)

- Geometry: px 8, py 6 (favorites, two-line) / py 5 (harness tab, one-line),
  rounded 7, gap 10; wrapper pb 2 bakes the 2px inter-row gap.
- Favorites (two-line): label 12.5rem MEDIUM truncate; subline = harness icon
  11px + harness name 11rem + "·" + attribution (model description, skipped
  when it repeats the harness name) 11rem truncate (:4225-4276).
- Harness tab: label 12.5 MEDIUM + inline attribution 11 text_muted
  (:4218-4253). **No avatar, no price, no context-window metadata.**
- States (:4180-4224): selected = `card_selected_bg` (wash 0.11 dark/0.06
  light) + `card_selected_shadows` (inset 1px ring); keyboard cursor =
  ink(0.05); **hover moves the cursor** (one moving highlight, no separate
  hover wash).
- ⌘N chip for ix<9 (:4287-4290): `kbd_hint` (popover.rs:1156-1165 — px 5
  py 1 rounded 5 bg ink(0.05), 10rem mono).
- Star (:4291-4330): 22×22, rounded 7, hover ink(0.08), star 13px; fav =
  STAR_BOLD theme.warning / idle STAR text_muted; stop_propagation.
- Keyboard (:2596-2746): ↑/↓ (+ctrl-p/n) wrap; count = rows + setting
  triggers; Enter activates or opens the highlighted setting; **→ opens the
  setting trigger**; ⌘1…9 jump-picks (shell forwards Mod+N,
  `jump_model_slot` :1166-1178); no Home/End; Escape closes;
  `scroll_to_item(Nearest)`. **Type-to-search**: search focused on open; every
  edit resets active to 0, closes the nested menu, scrolls to top
  (:660-686).

### Nested pages = the pinned traits tray, not pages

- Trigger rows (`render_traits_sections` :4498-4720): `menu_row` base
  (popover.rs:470-498 — gap 10 px 8 py 6 rounded 7, 13rem) overridden h 30
  (26 compact), py 0; label flex_1 truncate; current value max_w 100 truncate
  text_muted; chevron ALT_ARROW_RIGHT 12px.
- **Nested choice flyout** (:4630-4680): `popover_card w 232`, inner max_h
  240 scroll, choice rows h 30 py 0 gap 2 = label + flex_1 + bare "Default"
  badge (10rem SEMIBOLD text_muted, **no outline pill**) + CHECK 14px when
  selected. Side: flips left when `trigger.right + 244 > viewport.width`;
  `nested_menu` (popover.rs:682-704 — offset −10, priority 2, margin 8).
  **Hover opens the flyout** (dwell/leave logic :4410-4454); click on the
  trigger **dismisses** its own child (:4538-4555).
- Compact navigation (:842-900): Panel — Up/Down open the model list on a
  neighbor, Enter/Space → models, Tab cycles providers, Left/Right/Home/End
  set effort, F toggles fast, Escape closes; Models page — Escape → panel,
  ⌘⇧F stars the highlighted row; Providers page — Starred row + one row per
  harness (32px rows), picking = `pick_harness` + back to panel.

### Open/close semantics

- Trigger chip (`trigger_chip` :2874-3010): h 32, max_w 248, gap 6, px 6
  (web cites px 10 — see §2 divergence), rounded 8, 12rem MEDIUM; brand icon
  16px; label truncates with edge-fade while width animates
  (`resizing_chip_text` :5211-5238); **suffix = traits_summary** ("High · 1M
  · Fast", :230-260) muted text_muted@0.7, brightens to text@0.85 only when
  customized (:5485-5492); **fast glyph** FAST_TIER_BOLD 13px accent
  (:5493-5503); open bg = element_hover; hover fade 150ms; measured+eased
  width (`model_chip_width` :2830-2873); loading → skeleton bar 56 /
  glyph spinner. Sits in the actions row beside mic/Send
  (composer.rs:58-59, 10525-10561).
- **Open-on-click only** (no hover-open); click while open toggles closed;
  `open_model_menu` (:1173-1178) — the `/model` command and the `Mod-/`
  `OpenModelPicker` shortcut — opens **only, never closes**.
- On open (:1180-1310): recompute the height band; reset compact page state;
  clear search; prime rail = **Favorites if any stars, else the harness
  rail** (compact → All); anchor active on the selected row +
  scroll_to_item(Nearest); focus the search input; **force** reload
  harnesses + prefetch models (stale-while-revalidate, :1320-1479; OpenCode
  retries 3×).
- Escape → `ReturnComposerFocus`; click-out → dismiss with focus at the
  destination; exit MENU_OUT 100ms (fade + 2px retreat, blur rides down);
  entrance MENU_IN 140ms (opacity 0.3→1 + 4px travel, 6px gap, margin 8).
- **The card stays open after a model pick** (user request, :1739-1742);
  only Escape/click-out/chip close it. **No state persists across opens**
  (search cleared, scroll top, rail re-primed); geometry cached per
  PickerKind.

### Data + edge cases

- Catalog: `LIST_HARNESSES` (with targetDeviceId) forced on every open;
  `LIST_MODELS` per harness prefetched for every offered + effective harness.
- Offered set = visible ∧ installed ∧ enabled; the rail force-includes the
  committed chat's harness; **locked chats** (side chats with non-editable
  config, `harness_locked` :861-864) restrict every view to their harness;
  existing chats show only their own tab.
- `selected_only` row: a chosen model absent from the fresh catalog is
  inserted at index 0 of its harness tab, unclickable, described "Selected in
  this chat; absent from the current model list" (:1991-2028).
- No-agents takeover (:3851-3879): terminal icon 20px, "No agents available"
  13rem, body "Enable an installed agent in Settings → Providers, or install
  an agent CLI."
- Favorites persist in `composer-defaults.json` with label memory, per-harness
  last model, per-model reasoning/options (settings/composer.rs:46-140);
  `pick_model` on a new chat = draft + sticky memory, on an existing chat =
  the `setChatConfig` mutate.

### What the card does NOT do (guardrails for the port)

No login/account walls; no per-row pricing/context/token metadata; no model
detail pages; no grouped headers; no check marks in the tray or on rows;
no hover-open; no tooltips on standard rows/chip; **no global search across
harnesses — the query never leaves the viewed tab** (`scoped_model_rows`
:4901-4989; the `model_rows` docstring :1945 saying otherwise is stale); no
scroll/tab/search persistence; no drag-reorder of favorites.

**Width-dependence:** the card is width-fixed (304/256) — nothing inside
reflows. The responsive axes are vertical: the height band (min-180 flip,
640 cap, 14px margins, 30px list floor) and the nested flyout's left-flip.
A phone form must decide how `open_model_height` (298 base) maps when the
viewport is short.

## 2. The web port — current state and admitted divergences

One `PickerCard` (`composer-pickers.tsx:440-547`), width 304/256, inline
`maxHeight: 640`; structure = tab strip → search → virtualized list →
pinned traits tray (self-cited at :26). Compact arm = Panel/Models/Providers
pages with the effort slider and F/Tab/Arrows/Esc-per-page (:1010-1290).

Verified divergences from the desktop reference (web citations):

| # | Divergence | Web | Desktop |
|---|---|---|---|
| W1 | Band math | Fixed 216 in-chat; new-chat clamp (space−82−tray).clamp(30,216), tray cap 236 (model-picker-geometry.ts:15-63) | 640-cap band with above/below flip + `model_menu_budgets` tray/list split (§1) |
| W2 | Keyboard host | Capture-phase **window** listener (documented deviation — takeovers have no input) :803-810 | card-owned key routing (:2596-2746) |
| W3 | Cursor+hover merge | separate hover styling implied by CSS | hover MOVES the cursor (:4180-4224) |
| W4 | Refresh-on-open cadence | per-render `ensure_harnesses` effects (:380-390), window-focus re-arm (:396-410) | forced reload + prefetch on every open (:1180-1310) |
| W5 | Chip padding | px 10 (CSS 4760) | px 6 (:2874-3010) |
| W6 | Exit grace | +20ms exit grace dropped (popover.tsx:35-40) | exit window per popover.rs:482-530 |
| W7 | Search scope | (verify) query scoped per tab? | query never leaves the viewed tab (:4896-4900) |
| W8 | Focus return | focus-out dismissal veto (popover.tsx:190-199); sheet arm drops `escapeFocusTarget` (PickerCard.tsx:107-110) | `ReturnComposerFocus` (:1130-1138) |
| W9 | Nested flyout | portaled side flyout; **phone: in-place drill-down under a back header** (NestedMenu.tsx:196-256, ticket 15) | side flyout w/ hover-open + click-dismisses-own-child (:4410-4555) |
| W10 | Row heights | 29 compact / 48 favorites (virtualized, overscan 6) :1810-1870 | py 6/py 5 + pb 2 wrapper (~2-line ~48 / 1-line ~29) — matches by construction |

(W7 needs a live check against the web's `filterModels` before porting
anything — the desktop's own docstring is stale, so only behavior counts.)

## 3. Mobile full-width audit (the user's complaint, root-caused)

`.rb-drawer-card` (the phone sheet) is `position:fixed; left:0; right:0;
bottom:0` (app.css:5866-5887). `RbDrawerSheet` puts the caller's
`cardClassName` **on the sheet popup itself** (responsive-surface.tsx:169).
A class carrying `width:` on that element over-constrains left+right+width —
**width wins**, so the sheet paints a flush-left card instead of spanning
the viewport.

| Surface | Fixed width surviving into the sheet | Verdict |
|---|---|---|
| **Model picker (standard)** | `.identity-card { width: 304px }` (app.css:4821) | **BUG — 304px flush-left** |
| **Model picker (compact)** | + inner `.compact-card { width: 256px }` (app.css:17729) | **BUG — double: 256 inside 304** |
| **Sidebar section menu** | `.section-menu-body { width: 180px }` (app.css:2743; sidebar-sections.tsx:285-317) | **BUG — 180px flush-left** |
| Composer footer chips, account-usage, badges, changes-scope | widths via `width={}` props — **dropped** by the phone arm (PickerCard.tsx:97-125) | full width ✓ |
| Right-pane `+` menu | distinct `right-plus-menu-sheet` class (right-tab-strip.tsx:459; app.css:5939) | full width ✓ |
| User menu, spaces/title/theme/engine/device/context-usage | no width rules | full width ✓ |
| Dialogs | `.rb-drawer-card .dialog-card { width: 100% }` (app.css:5908, ticket 15) | full width ✓ |
| Settings selects | `.rb-drawer-card .settings-select-menu { width: 100% }` (app.css:12625, ticket 54) | full width ✓ |
| Glass palettes (add-space, command, settings dialog) | inner cards have phone `width:100%` (app.css:15276/15607/11360) | full width ✓ |

Not sheets (still float at phone): composer mention/slash completions
(absolute, left:0 right:0 over the composer — accidental phone-fit,
app.css:4373-4381), history column menus, the chat context menu (216px,
documented in ticket 54 notes). The sidebar drawer (`min(20rem, 85vw)`,
app.css:8429) and right-pane drawer (`min(30rem, 88vw)`, app.css:8282) are
deliberate partial-width side drawers — intentional, not part of this bug.

### Recommended fix shape (when we implement)

Follow ticket 15's precedent but **generalize to the frame**, since three
arms now share the failure mode:

1. `.rb-drawer-card { width: 100% }` inside the existing phone block
   (app.css:5866) — same specificity, later source order beats
   `.identity-card` and `.section-menu-body`; fixes both victims and
   future-proofs any fixed-width class that lands on a sheet popup.
2. `.rb-drawer-card .compact-card { width: 100% }` for the one inner
   fixed-width card.

Per-class fixes would repeat ticket 15's per-arm scoping without its per-arm
chrome needs and would miss the next offender.

### Other mobile notes for the picker

- Touch targets: compact rows 29px, tabs 32×32, star icon 13px — under 44px
  guidance; the compact effort slider is a drag rail with tiny stops.
- Keyboard: `interactive-widget=resizes-content` + the sheet's
  `max-height: calc(100dvh − --rb-space-lg)` (app.css:5876) shrink the sheet
  under the keyboard; the inner list keeps its 216 band.
- Safe area: the sheet's `--rb-safe-sheet-pad` composes through
  `.rb-drawer-card.popover-card` (app.css:5925) — the identity card inherits
  it via its `popover-card` class.
- The desktop has no phone form (min window geometry), so the phone mapping
  is web-native by necessity: full-width sheet (fixed above), in-place
  nested drill-down (already the web's ticket-15 shape), the height band
  yielding to the sheet's own clamp.

## 4. Suggested implementation order (for the eventual spec)

1. The two-rule full-width fix (§3) — small, high-value, unblocks the whole
   sheet family.
2. The 1:1 geometry/behavior gaps that are pure web fixes: W3 (hover moves
   cursor), W5 (chip padding), W4 (forced reload on open), the
   refresh-retry row, `selected_only` row, no-hover-open guard, card-stays-open-after-pick.
3. The height-band math (W1) — decide whether the web adopts
   `model_menu_budgets` verbatim or records its fixed-216 as a sanctioned
   deviation.
4. Keyboard host (W2) and focus return (W8) — refactor-grade, test with the
   takeover states.
