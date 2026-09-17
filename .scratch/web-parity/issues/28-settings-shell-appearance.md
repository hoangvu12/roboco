# 28 — Settings shell and Appearance

**What to build:** A user who opens Settings (from the user menu, or a direct
`/settings/*` URL) sees a real settings shell: a left nav with the "Settings"
caption, an icon per section, the desktop's exact selection wash, and a
pinned "Back" row that returns to chat — not the current three plain text
links. Opening Appearance shows the desktop's full page: an "Appearance"
field label above the mode cards (each a live 148px theme-miniature
preview, not a flat button), a custom popover theme-family selector with a
palette-preview swatch (not a native `<select>`), 30×34 rounded-square accent
swatches with a bottom selection bar (not 20×20 circles), the interface
font/size pickers, the new-thread composer background picker with its
effect choices, and the custom theme library (import/link/review/manage).
Every choice still applies live and persists device-locally, exactly as it
does today, just with the desktop's exact geometry, motion and copy.

**Blocked by:** 02 (Foundation tokens), 03 (Client settings store)

**Status:** ready-for-agent

**Research:** `../../web-client/research/12-settings-shell-appearance.md` §2, §3.0–§3.30, §4, §5 (all rows); `../../web-client/research/01-shell-chrome.md` §3.15.

**Desktop reference (for lookups only):** `crates/ui/src/shell.rs::render_settings_nav` (4306-4429), `::settings_outlet` (3326-3564), `::open_settings`/`::close_settings` (3268-3324), `::render_title_bar` Settings arm (3892-3911), `::render_main` Settings branch (5817-5831); `crates/ui/src/settings/widgets.rs` (full); `crates/ui/src/settings/appearance.rs` (full); `crates/ui/src/appearance.rs` (full); `crates/ui/src/theme_library.rs` (full); `crates/ui/src/typography.rs` (`UiFontFamily`/`UiFontSize`/`FontAvailability`); `crates/theme/src/lib.rs` (`Appearance`, `SurfaceTreatment`, `SurfacePreference`, `AccentPreset`, `AccentSelection`).

**Web files to touch:**
| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/components/settings-layout.tsx` | edit | `SettingsLayout`, `.settings-nav`, `.settings-nav-title`, `.settings-nav-link`, section icons, Back row |
| `web/packages/app/src/routes/settings-appearance.tsx` | edit | `AppearanceSettingsPage`, `VariantRow`, `AccentSwatch`, mode-card previews, theme-family popover, font/size pickers, background rows, theme-library rows, import/review dialogs |
| `web/packages/app/src/lib/appearance-store.ts` | edit | `AppearanceStore`, `resolveAppearance`, `accentHelper`, `surfaceHelper`, `accentSwatchColor`, font/size helpers (new), theme-library helpers (new) — reads/writes through ticket 03's client settings store instead of its own bespoke `roboco.appearance.v1` key where ticket 03 has landed an equivalent field |
| `web/packages/app/src/router.tsx` | edit | `settingsIndexRoute` redirect target (Devices, not Remote access — Devices itself ships in ticket 29; until then keep the redirect to the first section that exists and note the TODO), `appearanceRoute` |
| `web/packages/app/src/styles/app.css` | edit | `.settings-nav*` (~4577-4624), `.settings-page/.settings-title/.settings-subtitle` (~4625-4666), `.settings-card/.settings-row*` (~4679-4730), `.option-card*` (~4988-5010, replace with preview-frame variant), `.choice*` (~5012-5040, keep), `.swatch*` (~5043-5062, replace geometry), new: `.settings-back`, `.settings-field-label`, `.theme-select-trigger`, `.theme-select-menu`, `.font-trigger`, `.size-trigger`, `.background-row`, `.background-effect-choice`, `.theme-library-*`, `.import-dialog*`, `.review-dialog*` |
| `web/packages/app/src/components/popover.tsx` (or wherever ticket 09's `Popup<T>` primitive lands) | edit (depends on ticket 09; if not yet landed, build a local anchored-menu helper scoped to this ticket's two triggers and note the debt) | theme-family trigger/menu, font/size trigger/menu |

## 1. Context a fresh session needs

- Settings is a full route swap (`/settings/*`), not a modal: choosing a
  section replaces the sidebar (chat list → settings nav) and the main
  column (transcript → the section's page); the titlebar stays mounted
  with no section label — the desktop shows none either (confirmed:
  `shell.rs:3898-3909` renders a bare drag strip on the Settings route, no
  text). Do not add a titlebar label; that is correct desktop behavior, not
  a gap.
- Today's web `SettingsLayout` (`components/settings-layout.tsx`) is a
  static nav with 3 of 10 links (Remote access, Accounts, Appearance), no
  icons, no "Settings" caption styling, no Back row, and an accent-tinted
  selected state. This ticket fixes the shell itself and the Appearance
  page; ticket 29 adds the other 7 sections' pages (Devices, Agents,
  Files, Notifications, Shortcuts, Archived; Appshots is desktop-only and
  never ships on web).
- `SettingsSection::ALL` order is fixed and is the nav's display order —
  copy it exactly (§2 below). Note the label/variant crossover: the Rust
  enum variant `Harnesses` has user-facing label **"Agents"**, and the
  variant `Agents` has user-facing label **"Accounts"**. The nav row for
  "Agents" (label) routes to what ticket 29 will build as
  `/settings/harnesses`; the nav row for "Accounts" (label) is the
  already-shipped `/settings/accounts`.
- All settings pages share one 768px-max centered column
  (`widgets::page_column`) and one shared widget vocabulary
  (`widgets.rs`): `page_header`, `page_subtitle`, `field_label`,
  `section_card`, `card_row`, `section_header`, `status_dot`, `badge*`,
  `url_fragment`, `row_tile`, `row_title`, `meta_line`, `toggle_switch`,
  `ghost_action`, `error_strip`/`warning_strip`, `option_card`. The web's
  existing `.settings-page`/`.settings-card`/`.settings-row` classes
  already port most of these geometrically (§4 of research 12 confirms
  MATCHES); this ticket only touches the pieces Appearance uses that are
  wrong or missing (`option_card`, `field_label`, `swatch`, the
  theme-family trigger) — do not regress the ones that already match.
  Ticket 29 reuses the rest verbatim.
- Every Appearance choice is device-local, stored in
  `ui-settings.json` on desktop; on web it is `localStorage`
  (`roboco.appearance.v1` today — ticket 03 introduces a unified
  client-settings store that this ticket should read/write through
  wherever ticket 03 has landed an equivalent field, keeping
  `appearance-store.ts`'s existing public API (`AppearanceStore`,
  `useAppearance`) so `settings-appearance.tsx` does not need a rewrite of
  its data flow, only its rendering).
- Tokens: colors are `var(--rb-<role>)`, spacing/radii are
  `var(--rb-space-*)`/`var(--rb-radius-*)`, motion is
  `var(--rb-motion-<spec>)` + `var(--rb-ease-<curve>)`. Ticket 02 adds any
  token this ticket needs that does not exist yet (e.g. a neutral
  `glass-selected` background distinct from an accent tint). No literal
  hex/px where a token exists.
- Vocabulary: chat (not session/thread), harness (not provider), engine,
  space.

## 2. Spec

### 2.0 Shared widgets (`widgets.rs`) — full reference table

Every settings page (this ticket's Appearance page, and every page ticket
29 builds) is assembled from this fixed set of builders. Copied verbatim
from research `13-settings-sections.md` §3.0 (geometry) and research
`12-settings-shell-appearance.md` §3.11–3.12 (`badge_active`,
`ghost_hover`, `option_card_row`/`option_card`, which 13's table omits).
Ticket 29 references this table rather than repeating it — do not drop
rows when reusing it.

| Widget | Geometry | Source |
| --- | --- | --- |
| `page_column()` | `w_full`, `max_w(768px)`, `mx_auto`, `px(24px)`, `pt(32px)`, `pb(64px)`, flex column | `widgets.rs:17-27` |
| `page_header(title, count)` | flex row, `items_baseline`, `gap(10px)`; title `16px` semibold `theme.text`; count `13px` `theme.text_muted.opacity(0.7)` | `widgets.rs:31-52` |
| `page_subtitle(copy)` | `mt(4px)`, `13px`, `theme.text_muted` | `widgets.rs:55-61` |
| `field_label(label)` | `13px` medium, `theme.text` | `widgets.rs:65-71` |
| `section_card()` | `mt(24px)`, `rounded(12px)`, `border_1(theme.border)`, `bg(theme.card_glass_bg())`, `overflow_hidden`, flex column | `widgets.rs:147-157` |
| `card_row(first)` | `px(16px) py(10px)`; `border_t_1(theme.border)` unless `first`; `hover(bg(ink(0.015)))`; flex row, `items_center`, `gap(12px)` | `widgets.rs:162-172` |
| `section_header(label, trailing)` | `mt(28px) mb(10px)`, flex row `items_center gap(8px)`; label `12px` medium `theme.text_muted.opacity(0.85)`, flex_1 | `widgets.rs:176-198` |
| `status_dot(color)` | `size(8px)` `rounded_full` | `widgets.rs:202-204` |
| `badge_tinted(label)` | `px(8px) py(2px)` `rounded_full`, `border_1(accent.opacity(0.3))`, `bg(accent.opacity(0.1))`, `10.5px`, `text_color(accent)` | `widgets.rs:208-221` |
| `url_fragment(url)` | `min_w_0 truncate`, mono font, `11px`, `theme.text_muted.opacity(0.8)` | `widgets.rs:225-233` |
| `row_tile(icon_path)` | `size(36px)` `rounded(10px)` `border_1(theme.border)` `bg(ink(0.03))`, centers a `16px` icon `theme.text_muted` | `widgets.rs:237-253` |
| `row_title(title)` | `min_w_0 truncate`, `13px` (`ROW_TITLE_SIZE`) medium, `theme.text` | `widgets.rs:257-265` |
| `meta_line(fragments)` | `mt(Theme::TEXT_STACK_GAP)`, flex row wrap, `gap_x(8px) gap_y(2px)`, `12px` (`ROW_DESCRIPTION_SIZE`), `theme.text_muted.opacity(0.65)`; fragments joined by a `·` dot at `opacity(0.3)` | `widgets.rs:269-293` |
| `badge(label)` | `px(8px) py(2px)` `rounded_full` `border_1(theme.border)`, `10.5px`, `theme.text_muted` | `widgets.rs:296-307` |
| `badge_active(label)` | same pill geometry as `badge`, no border, `bg(theme.success.opacity(0.12))`, `text_color(theme.success_muted.opacity(0.9))` — the emerald "Active" pill | `widgets.rs:311-323` |
| `toggle_switch(on)` | `32×18px` `rounded_full`; track `bg(theme.text)` when on else `ink(0.15)`; knob `14px` circle at `left: 16px` (on) / `2px` (off), `top(2px)`; knob color `theme.on_solid` (on) / `ink(0.7)` (off) | `widgets.rs:328-345` |
| `ghost_action()` | flex row `items_center gap(6px)`, `rounded(8px)`, `px(10px) py(6px)`, `12px`, `theme.text_muted`, `cursor_pointer` (caller adds its own `.hover`) | `widgets.rs:351-363` |
| `ghost_hover(s)` | `bg(ink(0.06))`, `text_color(theme.text)` — the hover state `ghost_action` callers apply | `widgets.rs:367-369` |
| `error_strip(message)` | `mt(16px) px(16px) py(12px) rounded(12px) border_1(danger.opacity(0.2)) bg(danger.opacity(0.06))`, `12.5px`, `danger_muted.opacity(0.9)`, leading `DANGER_TRIANGLE` icon `16px` at `mt(2px)` | `widgets.rs:374-399` |
| `warning_strip(message)` | same shape, amber tokens, icon `14px`, `12px` text | `widgets.rs:404-429` |
| `option_card_row()` | flex row, `items_start`, `gap(16px)`, `w_full` | `widgets.rs:80` |
| `option_card()` | card: flex 1, `min_w_0`, flex column, `items_center`, `gap(8px)`, `cursor_pointer`; preview frame border `1px`, `theme.accent` if selected else `theme.border`; caption `13px`, selected → weight Medium + `theme.accent`, unselected → weight Normal + `theme.text_muted` | `widgets.rs:109-139` |
| `OPTION_CARD_HEIGHT` / `OPTION_CARD_RADIUS` | `148px` / `6px` | `widgets.rs:84,86,91,119,121` |
| `ROW_TITLE_SIZE` / `ROW_DESCRIPTION_SIZE` | `13.0` / `12.0` | `widgets.rs:13-14` |

Web CSS counterparts (all under `.settings-*` in `app.css`) already
mirror these pixel values closely per research 13 §3.0: `.settings-page`
= `max-width:768px; padding:32px 24px 64px` (exact match to
`page_column`); `.settings-card` = `border-radius:12px` (exact); `.settings-
row`/`card_row` = `padding:10px 16px` (exact); `.toggle` = `32×18px`,
thumb `14px` at `left:16px`/`2px` (exact); `.badge` = `10.5px`,
`padding:2px 8px` (exact) — no numeric gap in the shared widget layer
itself. This ticket's actual fixes are the Appearance-specific pieces
listed in §4 (`option_card`'s missing preview frame, the accent swatch
shape, the theme-family selector, etc.), not this shared table.

### 2.1 `SettingsLayout` — settings nav shell

**Layout**

| property | value | source |
| --- | --- | --- |
| width | `self.settings.sidebar_width` — the same user-draggable width as the chat sidebar (224–400, default 256), NOT a hardcoded settings-only width | `shell.rs:4327-4332` |
| height | `100%`; flex column | `shell.rs:4333-4335` |

**Children (in order)**

1. Scrollable region, `flex_1`, `px(SPACE_SM)` (8.0):
   - "Settings" caption — `px(8.0)`, `pt(12.0) pb(4.0)`, `font-size 11px`,
     weight Medium, `theme.text_muted.opacity(0.6)`, text **"Settings"**.
     (`shell.rs:4343-4350`)
   - Section list — `flex column; gap 2px`. One row per
     `SettingsSection::ALL`, filtering out `Appshots` (desktop/Linux-only,
     never present on web). (`shell.rs:4353-4359`)
2. "Back" row, pinned bottom — outer `px(8.0) pb(12.0)`; inner row
   `flex row; items_center; gap 6px; radius 8px; px(8.0) py(6.0);
   font-size 13px; color theme.text_muted; cursor pointer`; hover →
   `bg(theme.glass_hover()); color theme.text`. Icon `ALT_ARROW_LEFT` (a
   chevron, deliberately NOT the straight history-back arrow) at 16px,
   `theme.text_muted`. Text **"Back"**. (`shell.rs:4401-4427`)

**Section row** (`shell.rs:4362-4397`)

| property | value |
| --- | --- |
| id | `"settings-nav-{label}"` |
| display | flex row; items_center; gap 8px |
| radius | 8px |
| padding | `px(8.0) py(6.0)` |
| font-size | 13px |
| icon | 16px, color `theme.text_muted` (always muted, even when selected) |

**States**

| state | condition | change |
| --- | --- | --- |
| selected | `item == section` | `bg(glass_selected_bg())` (the theme's neutral translucent selection wash — 11% dark / 6% light `wash`, NOT an accent tint), `font-weight Medium`, `color theme.text` |
| unselected | else | `color theme.text_muted` |
| hover | pointer over | `bg(theme.glass_hover())`, `color theme.text` |

**Sections, labels and icons** (`SettingsSection::ALL`, order is fixed — `shell.rs:386-431,4315-4326`):

| Variant | Label (verbatim) | Icon (`@roboco/icons` name) |
| --- | --- | --- |
| `Devices` | "Devices" | `monitor` |
| `RemoteAccess` | "Remote access" | `keyMinimalistic` |
| `Harnesses` | "Agents" | `widget` |
| `Agents` | "Accounts" | `keyMinimalistic` |
| `Appearance` | "Appearance" | `tuning` |
| `Files` | "Files" | `folder` |
| `Notifications` | "Notifications" | `bell` |
| `Shortcuts` | "Shortcuts" | `keyboard` |
| `Appshots` | "Appshots" | `monitor` (desktop/Linux only — filtered out entirely on web, never render this row) |
| `Archived` | "Archived sessions" | `archiveMinimalistic` |

All 9 non-Appshots icon names above already exist in `@roboco/icons`
(`web/packages/icons/src/generated/index.ts`); no new icon assets needed.

**Interactions** — click a row → navigate to that section, close
Settings-adjacent popovers. Click Back → navigate to `/` (or the last
active chat route), matching `close_settings`'s intent (`route = Chat`,
focus composer). This ticket wires the nav and Back row for the two
sections it ships (Appearance, and whichever of the 3 existing routes
keep working); ticket 29 wires the remaining 7 routes' components — but
**this ticket's nav must render and link all 9 rows**, even for routes
ticket 29 has not built yet (link them to their eventual paths; ticket 29
adds the route components). This ticket must not leave the nav at 3 rows.

**Data** — none (pure client-side nav/routing).

Web gap this closes (research 12 §5 / research 13 §5): "Settings nav
sections" MISSING (3→9 shown, all built; only Appearance's page and the
existing 3 routes have real content until ticket 29), "Settings nav icons"
MISSING → added, "Settings nav caption size/opacity" WRONG VALUE (12px
plain → 11px + 0.6 opacity) → fixed, "Settings nav selected background"
WRONG VALUE (accent tint → neutral wash) → fixed, "'Back' row" MISSING →
added, "Settings nav width" WRONG VALUE (fixed 180px → `sidebar_width`) →
fixed.

### 2.2 `widgets::page_column` / `page_header` / `page_subtitle` / `field_label` (shared, reference only)

These already match on web (`.settings-page`, `.settings-title`,
`.settings-subtitle`) except two minor value fixes this ticket makes while
touching Appearance:

| Widget | Layout | Source | Web fix |
| --- | --- | --- | --- |
| `page_column` | `max-width 768px; margin 0 auto; padding 32px 24px 64px` | `widgets.rs:17-27` | Already matches (`.settings-page`) — no change |
| `page_header` | flex row, items_baseline, gap 10px; title 16px Semibold; optional count 13px `text_muted.opacity(0.7)` | `widgets.rs:31-52` | Already matches (`.settings-title`/`.settings-title-count`) — no change |
| `page_subtitle` | `mt(4px); 13px; text_muted`; Appearance overrides `max-width 512px; line-height 20px` | `widgets.rs:55-61`, `appearance.rs:2484-2490` | Web `.settings-subtitle` uses `max-width: 34rem` (≈544px) and no explicit line-height — tighten to `512px` / `line-height: 20px` |
| `field_label` | 13px Medium, `theme.text` | `widgets.rs:65-71` | New CSS class `.settings-field-label` — did not exist for Appearance's mode row; add it |

### 2.3 Appearance-mode row

**Layout** — `mt(32px)` (web was 24px — fix to 32px), flex column, gap
12px: `field_label("Appearance")` (currently missing entirely on web —
add it) then a row of 3 option cards. (`appearance.rs:2491-2499`)

**Children (in order)**: "Appearance" field label → option-card row
(System, Light, Dark).

**`option_card` layout** (`widgets.rs:79-142`)

| property | value | source |
| --- | --- | --- |
| row | flex row, items_start, gap 16px, full width | `widgets.rs:80` |
| card | flex 1, min-w 0, flex column, items_center, gap 8px, cursor pointer | `widgets.rs:109-116` |
| preview frame height | `OPTION_CARD_HEIGHT = 148px` | `widgets.rs:84,119` |
| preview frame radius | `OPTION_CARD_RADIUS = 6px` | `widgets.rs:86,121` |
| preview frame border | 1px, `theme.accent` if selected else `theme.border` | `widgets.rs:123-125` |
| caption font | 13px | `widgets.rs:129` |

**States** — selected: caption weight Medium, color `theme.accent`;
unselected: weight Normal, color `theme.text_muted`. (`widgets.rs:130-139`)

**Preview artwork inside the frame — `miniature`/`miniature_split`** (this
is the part the web is missing entirely today: a flat text button with no
graphic)

| property | value | source |
| --- | --- | --- |
| `bar(fraction, tone)` | height 5px, width `fraction * 100%`, radius 3px | `appearance.rs:502-508` |
| miniature root | flex row, `bg(theme.surface)`, rounded to `OPTION_CARD_RADIUS` (all corners for Light/Dark; left corners only / right corners only for System's two halves) | `appearance.rs:635-640` |
| "sidebar" column | width 44px, flex column, gap 7px, `px(8) pt(14)`, 4 bars at fractions 0.70(strong)/1.0/0.85/1.0 | `appearance.rs:641-656` |
| "content" pane | flex 1, `my(8) mr(8)`, rounded 6px, `border_1(theme.border)`, `bg(theme.bg)`, gap 7px, `p(10)`, 4 bars at 0.62(strong)/0.88/0.76/0.52 | `appearance.rs:657-676` |
| line tone | `theme.text.opacity(0.22)` | `appearance.rs:632` |
| strong tone | `theme.text.opacity(0.34)` | `appearance.rs:633` |

**Logic** — System mode renders the **split** miniature: left half is the
Light-appearance theme (accent/surface applied) clipped to left corners,
right half is Dark, each exactly half width. Light/Dark modes render one
full miniature built from that appearance's resolved theme (variant +
accent + surface). This is a live re-derivation, not a static image — it
must reflect whatever variant/accent/surface is currently selected, even
before the mode card itself is clicked. (`appearance.rs:680-725`)

**Data** — `AppearanceMode::ALL = [System, Light, Dark]`, exactly 3, all
labeled. Selected = `mode == current mode`. Click → set mode.
(`appearance.rs:41-52,2006-2009`)

**Text** — labels "System"/"Light"/"Dark" (`appearanceModeLabel`, already
correct on web).

Web gap this closes: "Appearance-mode option cards" WRONG VALUE/MISSING
(flat button, no preview → 148px live-theme-miniature preview frame),
"'Appearance' field label" MISSING → added, "Mode-row top margin" WRONG
VALUE (24px → 32px) → fixed.

### 2.4 `section_card` row order (Appearance)

Exact order (`appearance.rs:2013-2286`): Light theme row → Dark theme row
→ Accent color row → Glass row → New-thread composer background row →
(if a valid background image exists) Background effect row → (if a
background error is set) error strip → Theme-library header row →
IMPORTED group (if non-empty) → LINKED group (if non-empty).

Today's web order stops after Glass (4 of 9 items). This ticket adds every
remaining row in this exact order. Do not reorder; do not interleave.

### 2.5 Light/Dark theme rows — `render_theme_selector`

**Layout — trigger** (`appearance.rs:1055-1119`)

| property | value | source |
| --- | --- | --- |
| size | 218×34px | `appearance.rs:1066-1067` |
| padding | 10px | `appearance.rs:1068` |
| radius | 8px | `appearance.rs:1069` |
| border | `theme.border_strong` open / `theme.border` closed | `appearance.rs:1071-1075` |
| background | `theme.surface_raised.opacity(0.75)` open / `0.42` closed | `appearance.rs:1076` |
| content | `palette_preview` swatch (30×18, 3-way split of surface/bg/accent, rounded 5px, `border_1(theme.border)`) + truncating variant name (12.5px Medium) + `SORT_VERTICAL` icon 14px (opacity 0.9 open / 0.45 closed) | `appearance.rs:734-747,1104-1119` |

**Layout — menu** — a popover card, 260px wide, flex column, gap 2px;
`menu_heading` "Light themes"/"Dark themes"; one row per variant in that
appearance's registry order, each with the same `palette_preview` + name +
a trailing `CHECK` icon (`theme.accent`, 14px) when active.
(`appearance.rs:1121-1191`)

**Data** — options are every builtin variant authored for that appearance
(web scope: builtin variants only, per `appearance-store.ts`'s own doc
comment — no custom theme library variants merge in here until §2.9 below
ships an entry into the same registry).

**Interactions** — opening one selector's menu closes the other
appearance's menu (mutually exclusive Light/Dark popups). Click a row →
set that appearance's variant, close the menu.

**Motion** — `MENU_IN` 140ms `cubic-bezier(0.25,0.10,0.25,1.00)`, opacity
0.3→1.0 + `top -2px→0`; exit `MENU_OUT` 100ms same curve, reverse. A
400ms "just dismissed" guard suppresses an immediate re-open from the same
click that dismissed it. (`appearance.rs:116-119,131-134`; table in
§2.10 below)

Web gap this closes: "Theme-family selector" WRONG BEHAVIOR/WRONG VALUE —
replace the native `<select>`/`<optgroup>` (`VariantRow` in
`settings-appearance.tsx`) with this custom popover trigger + menu. If
ticket 09's `Popup<T>` primitive has landed, build this on top of it;
otherwise build a locally-scoped anchored-menu (open/closing/closed,
outside-click dismiss, the motion above) and flag the duplication for
ticket 09 to absorb later.

### 2.6 Accent color row

**Layout** — row tile icon `tuning`; title "Accent color"; meta =
`accent_helper(current_accent)` (already correct text on web). Trailing:
`flex_none`, `ml(10px)`, flex row, items_center, gap 6px, one
`accent_swatch` per choice. (`appearance.rs:2074-2090`)

**`accent_swatch` layout** (`appearance.rs:931-1001`) — replaces the
web's current 20×20 circle-with-outline-ring entirely:

| property | value | source |
| --- | --- | --- |
| outer | 30×34px, `pb(4px)`, `border-bottom 2px` (selection indicator bar) | `appearance.rs:976-985` |
| selection bar color | `swatch_theme.accent` if selected else transparent | `appearance.rs:981-985` |
| inner chip | 30×30px, `p(2px)`, rounded 8px, `border_1` (`border_strong` selected / `border` unselected), `bg(surface_raised.opacity(0.42))` | `appearance.rs:987-999` |
| ThemeDefault sample | rounded 6px, `bg(accent_wash)`, 3 vertical glyph bars width 4px heights 13/16/11px, rounded 2px, colors `glyph.light`/`glyph.mid`/`glyph.deep` | `appearance.rs:943-971` |
| Preset sample | flat rounded-6px square, `bg(swatch_theme.accent)` | `appearance.rs:972` |

**Data** — 8 swatches total: `[ThemeDefault, Roboco, Orange, Amber, Green,
Cyan, Blue, Pink]` (Theme default + all 7 `AccentPreset::ALL`). Hex pairs
(dark, light):

| preset | dark | light |
| --- | --- | --- |
| Roboco (default) | `#8b7cf6` | `#5b43e8` |
| Orange | `#fb923c` | `#c2410c` |
| Amber | `#fbbf24` | `#a16207` |
| Green | `#4ade80` | `#15803d` |
| Cyan | `#22d3ee` | `#0e7490` |
| Blue | `#60a5fa` | `#2563eb` |
| Pink | `#f472b6` | `#be185d` |

Verify `@roboco/theme`'s `accentPresets` table is byte-identical to this
before trusting `accentSwatchColor`'s existing output (not previously
diffed — see research 12 §7.3).

**Text** (`accent_helper`, already ported correctly): ThemeDefault →
"Theme default · Uses the palette's intended color."; Preset →
`"{label} · Controls, glyphs, selections, code, and activity."`

**Interactions** — click → set accent selection.

Web gap this closes: "Accent swatch shape" WRONG VALUE — 20×20 circle →
30×34 rounded-square chip with bottom bar; ThemeDefault must render the
3-bar glyph sample, not fall back to a flat color (today
`accentSwatchColor` returns `variant.accent.primary` for ThemeDefault too
— add the distinct glyph-bar render path for that one choice).

### 2.7 Glass (surface) row

Already matches on web (`.choice`/`.choice-row`, `surfaceHelper`,
`surfaceLabel`) per research 12 §3.22 — confirmed MATCHES. No change
needed beyond re-verifying it still renders correctly once inserted at
its correct position in the row order (§2.4). Exact `surface_choice`
pill geometry, for that re-verification (`appearance.rs:544-574`):
`h(30px)`, `px(10px)`, `radius 7px`, `border_1`, `border_color theme
.accent` selected / `theme.border` unselected; `bg theme.accent_wash`
selected / `theme.surface_raised.opacity(0.28)` unselected
(`appearance.rs:559-563`); `text-size 11.5px` (`appearance.rs:564`);
`font-weight Medium` selected / `Normal` unselected; `text_color
theme.accent` selected / `theme.text_muted` unselected.

### 2.8 New-thread composer background row + Background effect row

**Layout — background row** — leading tile: if a valid background is
installed, a 36×36 rounded-10px frame with `border_1(hairline(0.10))`
containing the image at 34×34, rounded 9px, object-fit cover; otherwise
the standard `row_tile(FILE_IMAGE)`-style 36px icon tile. Title "New
thread composer background". (`appearance.rs:2136-2155`)

**Meta lines** (join with the desktop's `meta_line` dimmed "·" fragment
joiner — the web's current `.settings-row-meta` is a single-string slot;
this row needs 2 fragments when installed, so add a small "join fragments
with a dimmed ·" helper if one doesn't exist by the time this ticket
lands):

| state | meta lines |
| --- | --- |
| installed & file exists | `{background.name}` · "Softened automatically on frosted themes." |
| installed but file missing | "Image unavailable" · "Choose a replacement or remove it." |
| none installed | "Add an image behind the composer on empty new threads." |

**Interactions** — actions area (`ml(10px)`, gap 6px): when installed →
"Replace image" + "Remove" (danger-colored); when absent → "Choose
image". Desktop uses a native file picker; web uses
`<input type="file" accept="image/*">`. (`appearance.rs:2194-2229`)

**Pure logic to port** — `install_new_thread_composer_background`
(`settings.rs:305-362`): validate the picked file actually decodes as an
image (reject with "This background image is unsupported or damaged.
Choose a valid image such as PNG or JPEG." on failure); persist it (web:
an object URL / IndexedDB blob keyed like the rest of ticket 03's client
settings, not a filesystem copy); update the setting; on any save failure
never orphan the new file with no fallback. `remove_new_thread_composer_background`
(`settings.rs:364-386`): clear the field, retire the old managed
resource.

**Background-effect row (conditional)** — only rendered when a background
is installed AND its resource still resolves. Tile `tuning`; title
"Background effect"; meta = the effect's description. Trailing:
`ml(10px)`, `max-width 430px`, flex-wrap, justify-end, gap 6px, one pill
per effect. Pill geometry: height 28px, `px(9px)`, font 11px — same
selected/unselected/hover treatment as the Glass row's pills.
(`appearance.rs:2234-2273,584-622`)

**Data** — `NewThreadBackgroundEffect::ALL = [None, Dither, Ascii,
Halftone, Scanlines]`, default `None`:

| effect | label | description |
| --- | --- | --- |
| None | "None" | "Shows the original artwork." |
| Dither | "Dither" | "Rebuilds the artwork with a dithered color palette." |
| Ascii | "ASCII" | "Recreates the artwork with colored characters on black." |
| Halftone | "Halftone" | "Recreates the artwork with colored print dots on black." |
| Scanlines | "Scanlines" | "Adds a pronounced horizontal display-line texture." |

**Interactions** — click a pill → set the effect. Rendering the actual
dither/ASCII/halftone/scanline transform on the chosen image is a canvas
filter; if a full pixel-accurate port is out of scope for this ticket's
time budget, ship the picker and persistence correctly and apply at least
`None`/basic CSS filters for the others, and note the gap explicitly in
Comments rather than silently skipping the row.

Web gap this closes: "New-thread composer background" MISSING → added
(browser-appropriate file input instead of native picker); "Background
effect picker" MISSING → added.

### 2.9 Interface font/size pickers

**Layout — frame** — `mt(36px)`, flex column, gap 10px, rendered in the
app's fixed system font (deliberately NOT the user's chosen interface
font, so the control that changes the font stays legible if the choice is
bad). Inner row: flex row, justify-between, items-center, gap 24px — left
side `field_label("Interface font")` + description "Used across the
interface and conversations. Code, diffs, and terminal keep their current
fonts and sizes." (12px, max-width 520px, line-height 18px); right side
the two dropdowns, gap 8px. (`appearance.rs:2501-2554`)

**Font trigger** — 220×36px, `px(11px)`, radius 9px, border
`theme.border_strong` open / `theme.border` closed, background
`ink(0.025)`; content: truncating label + down-chevron 14px. Menu: 220px
wide, max-height 320px scroll, flex column gap 2px; each row 13px, gap
10px, `px(8px) py(6px)`, radius 8px, trailing 18px check slot for the
active family. (`appearance.rs:2295-2391`)

**Options** — order-stable, exactly 5 in this order: "Geist" (default),
"Geist Mono", "System UI", plus any OS-detected installed fonts (a web
port has no OS font probe — ship the 3 fixed choices; treat "any installed
font" as N/A on web, not a bug, since there is no equivalent detection
API worth building here). Unavailable choices render at 45% opacity and
are not clickable — N/A on web since only the 3 fixed choices are ever
offered and all 3 are always available (a browser always has Geist/Geist
Mono webfonts bundled and a System UI stack).

**Size trigger** — same recipe, 128px wide, menu 128px wide, no scroll cap
(7 rows fit). Options: `UiFontSize::ALL = 12, 13, 14, 15, 16, 18, 20` px;
default 16px; label `"{n} px"`.

**Interactions** — click trigger toggles the menu (closes the other
dropdown first); keyboard: Up/Left steps back, Down/Right steps forward,
Home/End jump to first/last, Enter/Space commits or opens, Escape reverts
and closes. Click a row commits immediately.

**Pure logic to port** — `step_font(current, delta, availability)`
(`appearance.rs:462-480`): steps by `delta.signum()` through the
available choices, clamping (not wrapping) at either end. Since web only
ever offers 3 always-available choices, this degenerates to a simple
clamped index step — port it as such rather than the full
availability-aware desktop version, and name the test after the desktop's
`font_keyboard_navigation_stops_at_edges_and_skips_unavailable` (edge
cases: stepping past either end is a no-op).

**Motion** — same `MENU_IN`/`MENU_OUT`/400ms-guard recipe as §2.5's
theme-family selector.

**Text** — error strip (only reachable in theory since web always has all
3 fonts available, but keep the string for parity/logging):
"This font could not be loaded. Comet is using Geist." — verbatim,
including the "Comet" leftover product-name typo from the desktop source;
do not "fix" it.

Web gap this closes: "Interface font picker" MISSING → added (3-choice
web-appropriate version). "Interface font-size picker" MISSING → added
(all 7 sizes, same as desktop). "Font-load-failure notice" MISSING → wire
the string even if largely unreachable on web.

### 2.10 Motion catalog used throughout Appearance

| what animates | trigger | spec | from → to | reduced motion |
| --- | --- | --- | --- | --- |
| Any popover/dropdown menu open (font, size, theme-family) | menu opens | `MENU_IN` = 140ms, `cubic-bezier(0.25,0.10,0.25,1.00)` | opacity 0.3→1.0, `top -2px→0` | honors `prefers-reduced-motion: reduce` — shorten/skip per the app's shared reduced-motion handling |
| Menu close | menu begins closing | `MENU_OUT` = 100ms, same curve | opacity 1.0→0, `top 0→-2px` | same |
| Modal (import/review dialog) entrance | dialog mounts | `DIALOG_IN` = 180ms, same curve | opacity 0→1, `top +2px→0` | same |
| Hover color/bg fades (menu rows, ghost buttons) | pointer enter/leave | `HOVER_FADE` = 150ms, tailwind-ease curve | interpolated color blend | same |
| Font/size/theme dropdown "just dismissed" debounce | close→same-trigger click | 400ms wall-clock guard (not an animation — a re-open suppressor) | n/a | n/a |

Use the app's existing `--rb-motion-menu-in`/`rb-menu-in` keyframe catalog
(`app.css:898-903,1059` per research 12 §3.30) — it already exists, it is
simply not wired to `.settings-nav-link`, `.option-card`, `.choice`,
`.swatch`, or the new theme-family/font/size triggers. Wire it to all of
them in this ticket.

### 2.11 Theme library (import / link / review / manage)

**Header row** — tile `folderWithFiles`; title "Theme library"; meta
"Import or link custom themes."; trailing primary button "Add theme" →
opens the import dialog. (`appearance.rs:1914-1936`)

**Group headers** — "IMPORTED" then "LINKED" (only rendered if that group
is non-empty), `px(16px) pt(12px) pb(4px)`, `border-top`, 10.5px
Semibold, `theme.text_faint`. (`appearance.rs:1938-1971`)

**`render_library_entry` row** — tile `global` (linked) or `document`
(imported snapshot); title = entry name; status line (11px):
- Ready: `"{source.label()} · {n} variant{s} · {source path or 'Self-contained snapshot'}"`, muted color
- Warning: `"Using last known good · {message}"`, warning color

Trailing actions (all ghost buttons, `gap(2px)`): "Reload" (linked only),
"Reveal" (desktop-only — opens the OS file browser; **skip entirely on
web**, see §5), "Review" → opens the review dialog, "Duplicate as
editable", "Unlink" (linked only), "Remove" (danger-colored). Any action's
failure sets a library-level error and re-renders. (`appearance.rs:1760-1903`)

Web scope note: "Link to source" install mode and "Reveal"/"Reload"
depend on a durable, linkable OS filesystem path a browser does not have.
Web-appropriate substitute: ship "Import a copy" (snapshot) as the only
install mode via `<input type="file">`, persisted as an IndexedDB blob
through ticket 03's client settings store; omit Link/Reveal/Reload
entirely (§5 "Do not").

**Import dialog** — card 600px wide, max-height 760px, `border_1
(hairline(0.08))` (the same `hairline` value reused by the internal
row separators and the footer border below, `appearance.rs:1219`);
header: title "Add a theme" (15px Semibold) + body "Import a local theme
into your library or keep it linked to its source." (13px, line-height
19px) — web: drop the "or keep it linked to its source" clause since
Link mode does not ship (adjust copy to "Import a local theme into your
library."); trailing close button `size(28px)`, `radius 7px`, `border_1`,
`border_color theme.border`, `bg theme.surface_raised.opacity(0.28)`,
hover → `theme.surface_raised_hover` (`appearance.rs:1593-1602`). Body: a
"Source" file picker (`<input type="file">`, web substitute for the
native picker + text field).

Below the source field, desktop renders a "Keep it up to date" section of
two `mode_control` radio cards (Snapshot/Link); since this ticket ships
snapshot-only import (§5 "Do not"), render just the one description line
in its place rather than a radio choice — but keep the exact text
treatment desktop uses for it: label row `text-size 12px`, `font-weight
Medium`; description `mt(4px)`, `ml(23px)` (indented under the 16px radio
dot + label row above it), `text-size 10.5px`, `text_color theme
.text_muted.opacity(0.68)` (`appearance.rs:1271-1286`). Text: "Works
independently from the original file." (the Snapshot card's description
— the only one still relevant once Link is dropped).

Before any compile has run, show an info line: leading `INFO_CIRCLE` icon
`13px` at `mt(1px)`, text `line-height 16px`, `text_color
theme.text_muted.opacity(0.72)` (`appearance.rs:1536`). Text: "Roboco
finds light and dark variants automatically." (`appearance.rs:1527-1544`)

"Detected themes" section after a successful parse: one row per detected
variant with an 18px checkbox-style select square, `palette_preview`,
name + appearance label, a "Details"/"Hide details" toggle expanding a
scene preview (see `import_scene_preview`/`report_panel` below). Failed
variants render as an amber strip: `mt(8px) p(10px) rounded(8px)`, `bg
theme.warning.opacity(0.08)`, `text-size 11px`, `text_color
theme.warning` (`appearance.rs:1512-1525`):
`"{name} could not be compiled · {message}"`. A dialog-level error (when
present) renders the same shape in danger tones: `mt(12px) p(10px)
rounded(8px)`, `bg theme.danger.opacity(0.08)`, leading `DANGER_TRIANGLE`
icon 13px, `text-size 11px`, `line-height 16px`, `text_color theme
.danger` (`appearance.rs:1547-1567`). Footer: `border_t_1(hairline(0.08))`,
`bg theme.surface_raised.opacity(0.18)`, `px(20px) py(12px)`, `justify-end
gap(8px)` (`appearance.rs:1614-1623`): "Cancel" + a primary button
labeled "Analyze theme" before parsing, "Import selected" after; disabled
at 45% opacity with no click handler while nothing is selected.
(`appearance.rs:1391-1568,1614-1664`)

**`compact_action`** (`appearance.rs:749-767`) — the shared trailing
ghost-button recipe reused by every theme-library row action (Reload,
Reveal — dropped on web, Review, Duplicate as editable, Unlink — dropped
on web, Remove) and by the import dialog's Cancel/footer buttons:
`h(28px)`, `px(9px) py(0px)`, `radius 7px`, `border_1`, `border_color
theme.border`, `bg theme.surface_raised.opacity(0.34)`, `text-size
11.5px`.

**`import_scene_preview`** (the "Details" expansion for one detected
variant) — a 3-pane mock editor, height 86px, built from the candidate
theme variant: a 152px `miniature`, a flex-1 "code" pane rendering a fake
code line + an 8-swatch ANSI strip in the theme's syntax colors, and an
84px "diff" pane with three `h(12px) rounded(3px)` bars: `bg(theme.diff_add
.opacity(0.35))` (`appearance.rs:855`), `bg(theme.diff_del.opacity(0.35))`
(`appearance.rs:861`), and a plain `bg(theme.accent_wash)` third bar
(`appearance.rs:863`) — port these two opacities exactly; they are the
only place a candidate theme's actual diff-add/diff-del colors are
previewed before import.

**`report_panel`** (the mapping/validation log under a `import_scene_preview`)
— a scrollable (`max-height 168px`), `11px` monospace-adjacent log,
background `bg(theme.surface_raised.opacity(0.35))` (`appearance.rs:890`):
a one-line summary followed by one line per adjustment/fallback/warning/
validation-issue/dropped-mapping/mapping entry (format in §2.11's
`report_panel` paragraph below).

**Pure logic to port**:
- `compile_import` (`appearance.rs:311-341`): rejects an empty source
  with "Choose a local theme file or extension folder." (web: "Choose a
  theme file." since there's no folder picker); `family_id =
  "custom-{slug(name)}"`.
- `source_name(path)` (`appearance.rs:428-439`): if the file name is
  exactly `package.json`, use the parent directory's name instead; else
  the file stem; empty/missing → "Custom theme". (Web: file input gives a
  filename only, no path — the `package.json`-parent-dir branch is
  unreachable on web; keep the function shape for parity but note the dead
  branch.)
- `slug(value)` (`appearance.rs:441-460`): lowercase, keep ASCII
  alphanumerics, collapse runs of other characters to a single interior
  `-` (never leading), empty result → `"theme"`.
- `finish_import` (`appearance.rs:404-425`): rejects an empty selection
  with "Select at least one variant to import."

**Review dialog** — 660px wide, max-height 720px, scrollable; title
"Theme mapping"; body `"{entry.name} · {entry.source.label()}"`;
per-variant scene preview + report panel; footer "Done" button clears the
review state. (`appearance.rs:1709-1758`)

**`report_panel`** — one-line summary:
`"{n} mapped · {n} adjusted · {n} inferred/fallback · {n} unsupported · {n} warnings · {n} validation"`
followed by one line per issue. (`appearance.rs:868-929`)

**Library load-warning line** (conditional, rendered at the bottom of the
Appearance page body, after the interface font/size block) — when the
custom-theme library failed to fully load: `mt(8px)`, `text-size 11.5px`,
`text_color theme.warning` (`appearance.rs:2555-2563`). No fixed string —
the warning text is whatever the library load reported; render it
verbatim, do not paraphrase it.

Web gap this closes: "Theme library" MISSING entirely → added (snapshot
import only; Link/Reveal/Reload explicitly out per §5).

## 3. Pure logic to port

- `resolve(mode, system) -> Appearance` — already ported
  (`appearance-store.ts::resolveAppearance`); no change. Desktop tests:
  `system_mode_follows_the_os`, `pinned_modes_ignore_the_os`,
  `default_mode_is_system`, `mode_serialises_stably`.
- `accent_helper` / `surface_helper` — already ported with matching text;
  no change. Tests: substring assertions on "Pink ·"/"glyphs",
  "opaque default"/"where supported"/"every theme".
- `step_font(current, delta, availableChoices) -> choice` — port as a
  simple clamped index step over the 3 fixed web choices (§2.9). Test
  name: `font_keyboard_navigation_stops_at_edges_and_skips_unavailable`
  (edge cases: step left from first choice is a no-op; step right from
  last choice is a no-op).
- `source_name(path) -> string`, `slug(value) -> string` — port verbatim
  per §2.11's rules. No existing desktop test names beyond the function
  behavior described above; write `sourceNameFallsBackToCustomTheme` and
  `slugCollapsesNonAlphanumericRunsAndNeverEmpty` as the web unit tests.
- `install_new_thread_composer_background` / `remove_...` validation
  rules (§2.8) — port the "reject non-image" and "never orphan on save
  failure" rules; test names:
  `backgroundInstallRejectsUndecodableImage`,
  `backgroundRemoveClearsFieldAndRetiresResource`.

## 4. Gaps this ticket closes

| item | kind | desktop value | web value (file:line) | fix |
| --- | --- | --- | --- | --- |
| Settings nav sections | MISSING | 10 sections (`shell.rs:402-413`) | 3 links (`settings-layout.tsx:14-34`) | Render all 9 web-relevant rows (Appshots excluded); wire the 2 this ticket + existing 3 fully, link the other 4 to their eventual routes for ticket 29 |
| Settings nav icons | MISSING | 16px icon per row (`shell.rs:4392-4394`) | none | Add `@roboco/icons` glyphs per §2.1's table |
| Settings nav caption size/opacity | WRONG VALUE | 11px, `text_muted.opacity(0.6)` (`shell.rs:4347-4349`) | 12px, plain (`app.css:4593-4598`) | Fix to 11px + 0.6 opacity |
| Settings nav selected background | WRONG VALUE | neutral `glass_selected_bg()` (`shell.rs:4378`) | accent tint (`app.css:4613-4617`) | Swap to neutral wash token |
| "Back" row | MISSING | pinned bottom, chevron + "Back" (`shell.rs:4401-4427`) | none | Add |
| Settings nav width | WRONG VALUE | `sidebar_width` (`shell.rs:4327-4332`) | fixed 180px | Bind to the same width state as the chat sidebar |
| Appearance-mode option cards | WRONG VALUE/MISSING | 148px live-theme-miniature preview (`widgets.rs:79-142`, `appearance.rs:624-725`) | flat text button (`app.css:4988-5010`) | Add the preview frame + live miniature render |
| "Appearance" field label | MISSING | `field_label(theme,"Appearance")` (`appearance.rs:2497`) | none (`settings-appearance.tsx:37`) | Add |
| Mode-row top margin | WRONG VALUE | `mt(32px)` (`appearance.rs:2493`) | 24px (`app.css:4982`) | Fix to 32px |
| Theme-family selector | WRONG BEHAVIOR/WRONG VALUE | custom 218×34 popover trigger + palette-swatch rows (`appearance.rs:1028-1194`) | native `<select>` (`settings-appearance.tsx:97-125`) | Build the popover trigger + menu |
| Accent swatch shape | WRONG VALUE | 30×34 rounded-square with bottom bar, ThemeDefault 3-bar glyph (`appearance.rs:931-1001`) | 20×20 circle, flat ThemeDefault color (`app.css:5050-5062`) | Match geometry, add glyph render |
| Meta-line multi-fragment rows | WRONG BEHAVIOR (latent) | `meta_line` joins fragments with dimmed "·" (`widgets.rs:269-293`) | single string slot | Add a fragment-join helper, used by the background row |
| New-thread composer background | MISSING | choose/replace/remove, validated decode (`appearance.rs:2133-2232`) | none | Add (web file-input substitute) |
| Background effect picker | MISSING | 5 pills (`appearance.rs:2233-2273`) | none | Add |
| Interface font picker | MISSING | 220×36 dropdown (`appearance.rs:2295-2391`) | none | Add (3 fixed web choices) |
| Interface font-size picker | MISSING | 128×36 dropdown, 7 sizes (`appearance.rs:2393-2474`) | none | Add (all 7 sizes) |
| Theme library | MISSING | full import/link/review/manage pipeline | none | Add snapshot-import-only version |
| Motion on settings/appearance controls | MISSING | `MENU_IN`/`MENU_OUT`/`DIALOG_IN`/`HOVER_FADE` | none observed | Wire existing `--rb-motion-*` catalog to nav, cards, choices, swatches, new triggers |
| Settings page subtitle max-width/line-height | WRONG VALUE (minor) | 512px / 20px (`appearance.rs:2488-2489`) | 34rem / unset (`app.css:4656-4661`) | Tighten to 512px / 20px |

## 5. Do not

- Do not add a titlebar section label on the Settings route — the desktop
  renders none either; this is confirmed correct behavior, not a gap.
- Do not build "Link to source" install mode, "Reveal", or "Reload" for
  the theme library — no browser equivalent of a durable, linkable OS
  path exists. Snapshot import only.
- Do not build macOS `NSAppearance` sync, native window vibrancy
  re-application, or any native file/folder picker — use
  `<input type="file">` everywhere the desktop uses `prompt_for_paths`.
- Do not build the Appshots settings row or its desktop-only filter logic
  — it is permanently absent from web.
- Do not attempt an OS font-availability probe for the interface font
  picker — ship the 3 fixed choices (Geist, Geist Mono, System UI) as
  always-available.
- Do not build the other 7 settings section pages (Devices, Agents,
  Files, Notifications, Shortcuts, Archived) — those are ticket 29. This
  ticket only ensures the nav links to their eventual routes.
- Do not build the sound/notification decision engine or its settings
  toggles — that is ticket 30.
- Do not build the Fleet/multi-engine registry — that is ticket 31.
- Do not invent UI beyond what is specified above (per spec.md decision
  4: invented web-only UI is deleted, not polished).

## 6. Acceptance

- [ ] Settings nav renders all 9 web-relevant sections, in
      `SettingsSection::ALL` order, each with its correct icon and label
      (including the Harnesses→"Agents"/Agents→"Accounts" label
      crossover).
- [ ] Settings nav caption "Settings" is 11px at 0.6 opacity; selected row
      uses a neutral wash, not an accent tint; nav width tracks the
      sidebar's dragged width.
- [ ] "Back" row is pinned to the nav's bottom, uses the chevron icon
      (not a straight arrow), and navigates back to chat.
- [ ] Appearance page renders, top to bottom: "Appearance" field label →
      3 mode cards with live theme-miniature previews → Light theme row →
      Dark theme row → Accent color row → Glass row → New-thread
      background row → (conditional) Background effect row → Interface
      font/size block → Theme library section.
- [ ] Mode cards show a real live preview (split halves for System, one
      full miniature for Light/Dark) reflecting the current variant/
      accent/surface selection, inside a 148px/6px-radius frame with an
      accent/border ring.
- [ ] Theme-family selector is a custom 218×34 popover trigger with a
      palette-preview swatch, not a native `<select>`; opening one closes
      the other.
- [ ] Accent swatches are 30×34 rounded-square chips with a bottom
      selection bar; ThemeDefault renders the distinct 3-bar glyph
      sample.
- [ ] New-thread background row: choose/replace/remove an image via file
      input, validated as a real image, persisted, live-applied; the
      effect picker appears only once a valid background is installed.
- [ ] Interface font and size pickers exist, are keyboard-navigable
      (arrow/home/end/enter/escape), and commit immediately on click.
- [ ] Theme library: "Add theme" opens the import dialog; a valid theme
      file parses into one or more selectable variants; import adds
      entries to the library list; Review/Duplicate/Remove/Unlink work
      per §2.11 (Reveal/Reload/Link omitted, confirmed absent).
- [ ] All popover/menu open-close motion uses the shared `MENU_IN`
      (140ms)/`MENU_OUT` (100ms) catalog; `prefers-reduced-motion: reduce`
      shortens or removes it.
- [ ] Unit tests: `font_keyboard_navigation_stops_at_edges_and_skips_unavailable` → a web test in the appearance-store test file; `sourceNameFallsBackToCustomTheme`, `slugCollapsesNonAlphanumericRunsAndNeverEmpty`, `backgroundInstallRejectsUndecodableImage`, `backgroundRemoveClearsFieldAndRetiresResource` → new tests alongside `appearance-store.ts`.
- [ ] Screenshot pair, desktop vs web: (a) Settings nav with Appearance
      selected; (b) Appearance page top (mode cards + theme rows); (c)
      Appearance page accent/glass rows; (d) new-thread background row
      with an image installed and its effect pills visible; (e) theme
      import dialog with a parsed theme showing 2+ detected variants.
- [ ] `pnpm -r build` green; package vitest green.
- [ ] No new literal hex/px where a `--rb-*` token exists.

## Comments

### Research addendum (2026-09-17)

Two amendments from the post-wave-1 research
(`.scratch/web-parity/research-2026-09-17/pane-settings-issues.md` §2.3, §4):

- **Frosted is removed on the web (product decision, 2026-09-17).** This
  ticket's §2.7 specs the desktop Glass row verbatim; do NOT port the
  Frosted choice. `resolveSurfaceTreatment` is forced opaque
  (`lib/appearance-store.ts`), a persisted `frosted` heals to `opaque` in
  `state/ui-settings.ts`, and the five `html[data-surface="frosted"]` CSS
  groups are deleted. Update §2.7 and the §5 gap table accordingly so wave 4
  does not re-add it. Landed ahead of this ticket on `web-parity/wave-1`.
- **Missing file-table row**: the sidebar swap this ticket specs (§1
  "replaces the sidebar") is implemented in `app-shell.tsx` ~534-538 —
  `AppShell` owns the `<aside class="sidebar">` column and mounts
  `SidebarBody`; the ticket's "Web files to touch" table lists only
  `settings-layout.tsx`, which currently renders the nav inside the main
  column. Whoever picks this up must branch in `app-shell.tsx` (render the
  settings nav inside `.sidebar-inner` on `/settings/*`) or hoist it.
