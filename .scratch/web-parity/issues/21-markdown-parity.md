# 21 — Markdown parity

**What to build:** Every assistant message body, rendered the way the desktop
renders it. Headings and paragraphs on the exact type scale; code fences with a
real header (language label, copy, wrap toggle); inline code as an accent wash
instead of a bordered chip; monochrome always-underlined links that validate
their destination before they become clickable, show the raw URL on hover, and
carry a copy-link menu; accent list markers and interactive task checkboxes;
frameless hairline tables; the accent-tinted blockquote box; and — the biggest
functional hole — markdown **images actually rendering as images**. Streaming
text keeps its fade-in veil, now including code blocks, and selection is painted
in the theme's selection color.

**Blocked by:** 02 (Foundation tokens), 18 (Transcript rows).

**Status:** done

**Research:** `../../web-client/research/03-markdown.md` §3.1–§3.15, §4, §5 (all
rows). Cross-reference: `02-transcript.md` §5 rows 62 and 80.

**Desktop reference (for lookups only):** `crates/ui/src/markdown/render.rs`
(block dispatch :492, `heading_metrics` :706, `table_columns` :727,
`render_table` :761, `code_block_header` :1928, `code_copy_button` :1881,
`code_block_frame` :1969, `render_code_block_source_with_actions` :2015,
`paint_text_selection` :1229, inline-code wash :1130),
`markdown/parser.rs::autolink_runs` (:489-576), `markdown/mend.rs` (:59-206),
`markdown/veil.rs`, `markdown/link_destination.rs`,
`markdown/link_interaction.rs`, `markdown/link_presentation.rs`,
`markdown/mermaid.rs`, `markdown/selection.rs`, `workspace_links.rs`,
`browser/model.rs::transcript_address` (:37-106), `crates/syntax/src/lib.rs`
(`HighlightKind` :60-92).

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/components/markdown.tsx` | edit | `MarkdownBlockView`, `MarkdownTreeView`, `renderBlock`, `InlineRuns`, `InlineRunView`, `CodeBlock`, `SyntaxTokenView`, `alignCss`; new `MarkdownImage`, `MarkdownLink`, `LinkDestinationCard`, `LinkContextMenu`, `TaskCheckbox`, `CodeBlockHeader`, `MermaidBlock`, `WorkspaceFileLink` |
| `web/packages/app/src/lib/markdown.ts` | edit | `parseInline`, `parseMarkdown`, `closeHanging`, `MarkdownCache`, `PENDING_LINK_URL`, `InlineStyle`/`InlineRun`/`Block`/`BlockTree`; new `autolinkRuns`, `findUrlStart`, `bareUrlLen`, `tableColumns` |
| `web/packages/app/src/lib/links.ts` | new | `transcriptAddress`, `normalizeAddress`, `linkPresentationTruncate`, `resolveWorkspaceFileLink` |
| `web/packages/app/src/lib/veil.ts` | edit | `VeilTracker` — per-line slicing so code blocks veil |
| `web/packages/app/src/lib/syntax.ts` | edit | `SyntaxRole` union — add the unmapped roles |
| `web/packages/app/src/components/transcript.tsx` | edit | `MarkdownRow`, `LiveMarkdownRow`, `VeiledBlock`, `splitRunsForVeil`, `StyledRun` — extend the veiled path to code blocks; share the inline renderer with `markdown.tsx` |
| `web/packages/app/src/styles/app.css` | edit | `.row-md`, `.md-p`, `.md-h`, `.md-h1`–`.md-h6`, `.md-quote`, `.md-list`, `.md-task`, `.md-checkbox`, `.md-checkbox-done`, `.md-code`, `.md-codeblock`, `.md-pre`, `.md-codeline`, `.md-copy`, `.md-link`, `.md-link-pending`, `.md-table-wrap`, `.md-table`, `.md-rule`, `.tk-*`, `.veil-fade`, `@keyframes rb-veil`; new `.md-codehead`, `.md-codehead-lang`, `.md-codehead-actions`, `.md-action`, `.md-img`, `.md-link-card`, `.md-link-menu`, `.md-marker`, `.md-item`, `.md-mermaid`, `::selection` scope |
| `web/packages/app/src/lib/appearance-store.ts` (or ticket 03's settings store) | edit | persisted `code_fences_fit_content` (default `false`) |
| `web/packages/app/tests/markdown.test.ts` | new | the pure-logic cases in §6 |

---

## 1. Context a fresh session needs

- Every assistant and user message body in the transcript, and every text file
  preview, is rendered through Roboco's own markdown stack: a block-granular
  parse (`lib/markdown.ts`) turned into elements (`components/markdown.tsx`),
  with token-based syntax highlighting on code fences (`lib/syntax.ts`), a
  streaming "veil" fade-in for freshly-appended text (`lib/veil.ts`), and a
  display-only "mend" pass that keeps half-typed `**bold`/`[link](` markers from
  flickering while streaming (`closeHanging`).
- The transcript virtualizes at **block** granularity — one `Block` per
  virtualized row, not one tree per message — so there is no literal
  `flex-col gap:12` container. Ticket 18's `topGapFor` returns `MD_BLOCK_GAP = 12`
  as the next row's `padding-top` when it is a sibling block of the same message
  part. That reproduces the desktop's 12px rhythm and already **MATCHES**.
- `MarkdownRow` renders a settled block; `LiveMarkdownRow` renders a streaming
  block through `VeiledBlock`. `StyledRun` (in `transcript.tsx`) and
  `InlineRunView` (in `markdown.tsx`) are two copies of the same inline
  renderer — collapse them into one while you are here, since every inline fix
  below has to land in both otherwise.
- Colors are theme roles: `theme.text` → `var(--rb-text)`, `text_muted`,
  `text_faint`, `border`, `border_strong`, `accent` → `var(--rb-accent)`,
  `code_text` = `accent.primary`, `code_wash` = `accent.wash`,
  `selection` = accent @ 0.35 dark / 0.24 light. Ticket 02 provides any missing
  token (`--rb-code-text`, `--rb-code-wash`, `--rb-selection`).
- Motion: `HOVER_FADE` = 150ms, delay 0, `EASE_TAILWIND`
  `cubic-bezier(0.4,0,0.2,1)` → `var(--rb-motion-hover-fade)` /
  `var(--rb-ease-ease-tailwind)` (already correct for the copy button).
- Out of this ticket: the row shell, gaps, hover strip, user bubble (18); tool
  groups and thought line styling (19); the rail/badges/loaders (20); the files
  preview's own markdown parser (`lib/markdown-doc.ts` — a different surface).

---

## 2. Spec

### 2.1 Tree container

| Property | Value | Source |
|---|---|---|
| direction | column | `render.rs:431-433` |
| gap between top-level blocks | `MD_BLOCK_GAP` = 12px | `render.rs:30` |

**MATCHES** for sibling top-level blocks via `topGapFor`. Gap **inside** a single
block's own multi-block children (a list item or blockquote containing more than
one block) is a separate story — see §2.13.

### 2.2 Paragraph

| Property | Value | Source |
|---|---|---|
| font size | `MD_TEXT_SIZE` = 14px | `render.rs:32` |
| line height | `MD_LINE_HEIGHT` = 22px | `render.rs:33` |
| weight | normal (400) | `render.rs:1665-1669` |
| color | `theme.text` | `render.rs:977` |
| margin | none (spacing is the block gap) | — |

Web `.md-p { margin:0; white-space:pre-wrap; overflow-wrap:anywhere }`,
`.row-md { font-size:14px; line-height:22px }` (`app.css:3517-3527`) —
**MATCHES**. Leave alone.

### 2.3 Heading (h1–h6) — `heading_metrics(level)` (`render.rs:706-713`)

| Level | Desktop size/line | Weight | Web today (`app.css:3535-3551`) |
|---|---|---|---|
| h1 | **19px / 27px** | SEMIBOLD (600) | 20px / (1.35 × 20 = 27px) |
| h2 | **16px / 24px** | SEMIBOLD | 17px / (1.35 × 17 ≈ 23px) |
| h3 | **15px / 22px** | SEMIBOLD | 15px / (1.35 × 15 ≈ 20px) |
| h4–h6 | **14px / 22px** | SEMIBOLD | 14px / (1.35 × 14 ≈ 19px) |

Weight is forced to SEMIBOLD via `text_element(..., bold_default: true, ...)`
(`render.rs:1665-1669`), independent of the CSS `font-weight`. Fix the two font
sizes and replace the relative `1.35` multiplier with **fixed px line-heights per
level** — the desktop keeps a generous ~22px line box down to h6.

Children: inline runs, same inline model as paragraphs.

### 2.4 Code block

**Outer frame** (`code_block_frame`, `render.rs:1969-1990`):

| Property | Value | Source |
|---|---|---|
| radius | 10px | `render.rs:1982` |
| background | `theme.ink(0.035)` | `render.rs:1983` |
| border | 1px `theme.border` | `render.rs:1984-1985` |
| overflow | hidden | `render.rs:1986` |

**Header** (`code_block_header`, `render.rs:1928-1966`; present only when a
language or actions exist):

| Property | Value | Source |
|---|---|---|
| height | `CODE_HEADER_HEIGHT` = 28px | `render.rs:39`, `:1938` |
| padding | `pl:12 (CODE_PADDING_X), pr:5` | `render.rs:1940-1941` |
| border-bottom | 1px `theme.border` | `render.rs:1942-1943` |
| background | `theme.ink(0.02)` | `render.rs:1944` |
| direction | row, `justify-between`, `items-center` | `render.rs:1945-1948` |
| language label | text-size 11px, color `theme.text_muted`, **verbatim fence-info string** | `render.rs:1952-1954` |
| action row gap | 2px | `render.rs:1962` |

**Action buttons**:

| Button | Size | Icon | Icon size | Tooltip text | Source |
|---|---|---|---|---|---|
| Fit/wrap toggle | 22×22 (`CODE_ACTION_SIZE`), radius 6 | `icons::WRAP_TEXT` | 13px, `text_muted` | "Use horizontal scrolling" (when fit) / "Fit content" (when not) | `render.rs:2075-2112` |
| Mermaid source toggle | 22×22, radius 6 | `icons::EYE` (showing source→diagram) / `icons::FILE_CODE` | 13px, `text_muted` | "Show diagram" / "Show source" | `render.rs:1810-1824` |
| Copy | height 22, `px:6`, radius 5, gap 4 | `icons::COPY` → `icons::CHECK` when copied | 12px, `text_muted` | — (label swaps to "Copied", text-size 10.5) | `render.rs:1881-1926` |

Hover background on every action button:
`hover_blend(key, transparent, theme.ink(0.08))` over `HOVER_FADE` 150ms
`EASE_TAILWIND`. The fit toggle uses `ink(0.09)`/`ink(0.13)` as its resting/hover
shades when **active** (`render.rs:2079-2083`) instead of transparent/`ink(0.08)`.

**Code body**:

| Property | Value | Source |
|---|---|---|
| padding | `px:12 (CODE_PADDING_X), py:10 (CODE_PADDING_Y)` | `render.rs:36-38`, `:2127-2128` |
| font | `theme.font_mono` | `render.rs:2129` |
| font size | `CODE_TEXT_SIZE` = 12.5px | `render.rs:35`, `:2130` |
| line height | `CODE_LINE_HEIGHT` = 18px, one `div` per line, height exactly `18px` (not-fit) or `min-h:18` (fit) | `render.rs:36`, `:2131`, `:2151-2154` |
| wrap | `whitespace_nowrap` (default, horizontal-scroll) or `whitespace_normal` (fit-content toggled on) | `render.rs:2132-2138` |
| default fit state | `false` (horizontal scroll) — persisted setting `code_fences_fit_content`, default `false` | `settings.rs:626,697` |
| horizontal scrollbar hit-height | `CODE_SCROLLBAR_HIT_HEIGHT` = 10px, thumb height `MENU_SCROLLBAR_HOVER_THUMB_WIDTH` (active) / `MENU_SCROLLBAR_THUMB_WIDTH` (idle), thumb color `theme.text_faint` at 0.68/0.5 opacity | `render.rs:41`, `:2192-2247` |

Web today: body padding `10px 12px`, font 12.5/18, native `overflow-x:auto`, an
absolute top-right `.md-copy` at `opacity 0→1` on hover/focus — all fine. What is
missing is the **header band**, the **language label**, and the **fit toggle**.
The custom scrollbar is an accepted platform difference (§5 "Code block custom
scrollbar", low priority).

**Interactions**: click copy → clipboard + "Copied" for ~1.2s (web
`setTimeout(1200)`, `markdown.tsx:173`; the transcript owns this state on
desktop — 1200ms matches transcript.rs:5689). The fit toggle flips a
**persisted, global** setting (`code_fences_fit_content`) affecting every code
fence in the app, not just one block (`render.rs:267-271`).

### 2.5 Inline code

| Property | Value | Source |
|---|---|---|
| font | `theme.font_mono`, **same size as surrounding text** (14px body / heading size) — not shrunk | `render.rs:953-957` |
| text color | `theme.code_text` = `accent.primary` (accent-tinted, not plain foreground) | `render.rs:915-917`, `theme.rs:1312` |
| wash background | `theme.code_wash` = `accent.wash` (accent-tinted, paint-only underlay, not a run background) | `render.rs:918-920`, `theme.rs:1313` |
| wash radius | `INLINE_CODE_RADIUS` = 4.5px | `render.rs:923` |
| wash horizontal overhang | `INLINE_CODE_PAD_X` = 2px past glyph edges | `render.rs:924` |
| wash vertical inset | `INLINE_CODE_INSET_Y` = 2px inset from the 22px line box | `render.rs:925` |
| border | **none** (soft wash only) | — |

Web today (`app.css:3579-3586`) has a fixed 12.5px font, inherited plain color,
`--rb-raised` background and a 1px `--rb-border` the desktop does not have. Fix
all four; keep the 4.5px radius and the 2px horizontal padding.

### 2.6 Links

**Inline appearance**: color `theme.text` (**monochrome — links are *not*
accent-colored**), underline 1px `theme.text_muted`, no wavy
(`render.rs:968-970`, `1008-1012`). Web is inverted today (`--rb-accent`,
underline on hover only, `app.css:3634-3642`). Swap to neutral text + permanent
underline.

**Hover destination card** (`link_destination.rs`) — a small card near the
pointer showing the raw URL:

| Property | Value | Source |
|---|---|---|
| max width | `clamp(viewport.width − 24, 1, 360)` | `link_destination.rs:14-18` |
| max height | `clamp(viewport.height − 80, 1, 160)` | same |
| font size / line height | 11px / 14px | `link_destination.rs:67-68` |
| padding | 6px / 4px | `link_destination.rs:60-61` |
| radius | 4px | `link_destination.rs:62` |
| border | 1px `theme.border_strong` | `link_destination.rs:64` |
| background | `theme.surface_raised` | `link_destination.rs:65` |
| shadow | `shadow_sm` | `link_destination.rs:66` |
| wrap | URL text has zero-width-space grapheme breaks inserted so even one long path segment can wrap | `link_destination.rs:22-25` |

Shown after gpui's standard hoverable-tooltip delay (**~650ms** observed in
`link_interaction.rs` tests — the exact default is a gpui built-in, research §7)
or immediately on keyboard focus; dismissed on Escape, on losing focus, or on
outside click. Positioned at the focused link's **first hit rect's bottom-left**,
anchored top-left, snapped to the window with an 8px margin
(`link_interaction.rs:229-269`).

**Right-click / Shift+F10 context menu** (`link_interaction.rs:273-420`) — a
260px-wide popover card at the pointer (or below the focused link for keyboard):

| Row | Icon | Enabled when |
|---|---|---|
| "Open in Roboco" | `icons::GLOBE` | `target.navigation.is_ok()` |
| "Open in external browser" | `icons::ARROW_UP_RIGHT` | `target.navigation.is_ok()` |
| "Copy link address" | `icons::COPY` | always |
| — separator — | | |
| "Open links in Roboco" (checkbox row, check icon when on) | — | toggles persisted setting `open_web_links_in_roboco` |

Keyboard: Tab/Shift+Tab or ↑/↓ cycle menu rows; Escape closes and returns focus
to the link. On the link itself: Tab/Shift+Tab move focus between links;
Enter/Space activates; Shift+F10 opens the menu.

**Web scope**: there is no embedded browser on web, so "Open in Roboco" and the
`open_web_links_in_roboco` toggle do **not** port (research §5 says so
explicitly). Build the menu with "Open link" (new tab) and "Copy link address".

**Click activation guard** (`link_interaction.rs:465-474`): a mouse click only
activates the link when it's button-1, single-click, and the up/down positions
are **within 4px** of each other (`click_is_activation`) — this lets a
text-selection drag start on top of a link without navigating. It also refuses to
activate when `selection::selected_text()` is non-empty (a completed drag). Add
the equivalent `mousedown`/`mouseup` position check plus a
`window.getSelection()` check before allowing navigation.

**Destination validation** (`browser/model.rs:37-106`, used via
`transcript_address`): only `http://`/`https://` schemes are accepted; the
address must contain no control/whitespace characters and no backslash; the
authority must be non-empty and not start with `/`, `?`, or `#`; every `%` escape
must be a valid two-hex-digit sequence; the result is then normalized/parsed as a
URL and rejected if it carries a username or password (`allowed_navigation`,
`browser/model.rs:99-106`). Rejected examples from the test suite
(`markdown/links.rs:76-97`): `javascript:…`, `data:…`, `mailto:…`,
credentials-in-URL, embedded newlines, bare `https://`, bare `example.com` (no
scheme), and malformed `%` escapes. **This is the highest-priority gap on this
surface**: `markdown.tsx:152` passes `href={style.link}` straight to the DOM
today. Every href goes through `transcriptAddress` first; a rejected link renders
as inert styled text (the same treatment `.md-link-pending` already gets).

**Width-based truncation** (`link_presentation.rs`): when a link's flattened
label would overflow the available width, the label truncates to a grapheme
boundary + `…` via binary search on shaped width, **never lets a short label get
longer just to add an ellipsis** (`if range.end - (range.start+prefix) >
'…'.len_utf8()`, `link_presentation.rs:108`), and keeps an `OffsetMap` so
double-click/selection/copy still resolve against the **original** untruncated
text (test: `partial_and_cross_block_copies_map_back_to_source`). This
presentation only activates **inside the transcript**
(`opts.link.source_session.is_some()`, `render.rs:806`) — generic markdown
previews never truncate links. Low priority on web (CSS wrapping is a reasonable
substitute at chat width); implement the `truncate` + `OffsetMap` port only if
pixel parity is required, and keep `overflow-wrap: anywhere` otherwise.

**Workspace file-link decoration** (`resolve_workspace_file_link`,
`workspace_links.rs:14-155`, + the `sole_file_reference` family,
`render.rs:1711-1793`): resolves an agent-authored link/plain-text token into a
safe workspace-relative path + optional line/column, **rejecting**: empty/`?`/NUL
targets, `file://`-prefixed absolute paths outside the workspace root, any scheme
other than `roboco-file:`/`file://`/schemeless, `..`/`.`/empty path segments,
backslashes, drive letters (`:` anywhere in the path), and percent-encoding that
doesn't round-trip (`roboco-file:` mentions only). Line/column parse from `#L123`
fragments or trailing `:123` / `:123:45` suffixes. A paragraph whose whole
content is one such reference gets a file-icon badge and a click-to-open
affordance.

**Bare-URL autolinking** (`parser.rs::autolink_runs`, `:489-576`): a bare
`http(s)://` URL not already inside a link or code span is promoted to a
clickable link. Boundary rule: the URL must not be glued to a preceding
alphanumeric character (`find_url_start`, `:536-552`); trailing punctuation
(`.,;:!?*_~`) is trimmed, and a trailing `)` is trimmed **only if it doesn't
balance an opening `(` inside the URL** (`bare_url_len`, `:558-576`).

**Element geometry note** (why the desktop hand-rolls two `Element` impls):
`LinkRanges::prepaint` computes, for every link, one hit-target rect **per visual
line it wraps onto** via `range_rects(layout, range, pad_x:0, inset_y:0)` — a
wrapped link gets N sibling hit divs, not one (`link_interaction.rs:126-227`).
Browsers already hit-test wrapped inline elements natively, so no port is needed;
likewise `ResponsiveText`'s measure/re-truncate passes
(`link_presentation.rs:197-260`) have no web equivalent unless truncation is
built.

### 2.7 Blockquote

| Property | Value | Source |
|---|---|---|
| left border | 2px, color `theme.accent.opacity(0.6)` | `render.rs:528-529` |
| background | `theme.accent.opacity(0.05)` | `render.rs:530` |
| radius | **top-right and bottom-right only, 6px** (left corners square, matching the border rail) | `render.rs:531-532` |
| padding | `pl:12, pr:10, py:6` | `render.rs:533-535` |
| children direction/gap | column, gap **8px** | `render.rs:536-538` |
| text color | `theme.text_muted` | `render.rs:539` |

Web today (`app.css:3553-3559`) has `padding-left:12px` only, a neutral
`--rb-border-strong` rail, no wash, no radius and no child gap. Rebuild the whole
box model.

### 2.8 List

**Item row**: `flex row`, gap **8px** between marker and content
(`render.rs:658`). Vertical gap between sibling items: **4px**
(`render.rs:558`). Vertical gap between a list item's own multiple child blocks:
**4px** (`render.rs:664`).

**Marker — ordered**: `min-w:18px`, text-size `MD_TEXT_SIZE` / line
`MD_LINE_HEIGHT`, color `theme.accent`, text `"{start + item_ix}."`
(`render.rs:631-639`).

**Marker — unordered**: `min-w:18px`, height `MD_LINE_HEIGHT` (22px), centered, a
real filled circle **5×5px**, `ml:1`, `rounded-full`, `bg: theme.accent` —
explicitly **not** the `•` glyph ("reads too small at 14px",
`render.rs:560-561`) (`render.rs:640-655`).

**Marker — task**: a 16×16px checkbox, `border-1 rounded-3`, border color
`theme.accent` when checked else `theme.border`; background `theme.accent` when
checked else transparent; check icon 12px `icons::CHECK` colored `theme.bg` when
checked; disabled (opacity 0.5) when no toggle handler is wired; **interactive
click toggles the task** (`render.rs:570-627`).

Effective indent is `18px marker + 8px gap = 26px`, not the web's
`padding-left: 22px`.

### 2.9 Table (`render_table`, `render.rs:761-899`; `table_columns`, `:727-742`)

| Property | Value | Source |
|---|---|---|
| chrome | frameless — **no outer border, no header background fill, no corner radius**; the only chrome is horizontal hairlines | `render.rs:43-48` |
| header/row divider | `TABLE_DIVIDER` = 1px, color `table_hairline()` = `theme.hairline(0.10)` | `render.rs:52`, `:63-65`, `:845` |
| cell padding | `TABLE_CELL_PADDING` = **12px uniform** | `render.rs:50`, `:854` |
| header weight | `TABLE_HEADER_WEIGHT` = BOLD (**700**) | `render.rs:54`, `:788-792` |
| cell text | body scale 14px / 22px | `render.rs:855-856` |
| column width | content-proportional flex: `flex-grow`/`flex-shrink` = column's natural (max-content + 2×12 padding) width, floored at `TABLE_MIN_COLUMN_CONTENT` = 48px content before padding; `min-width` = `min(natural, TABLE_MIN_COLUMN_WIDTH = 96px)` | `render.rs:56-61`, `:727-742` |
| overflow | the whole table scrolls horizontally once column floors exceed the viewport (`min_table_width` = Σ minimums) | `render.rs:890-898` |
| alignment | per-column GFM align → center / right / default-left | `render.rs:857-861` |

Web today: `border-collapse: collapse` with a full 1px box on **every** cell, a
`--rb-raised` header fill, `4px 12px` padding and weight 600. Rebuild to
frameless + horizontal hairlines only, uniform 12px padding, weight 700. Port
`table_columns` and apply it as per-cell flex-basis/min-width, or accept the
native auto-layout as a lower-priority visual gap (call it out in Comments if you
skip it).

### 2.10 Rule

Desktop: 1px height, full width, `bg: theme.border`, **no margin** — spacing
comes entirely from the 12px block gap (`render.rs:696-700`). Web today uses
`--rb-border-strong` and adds `margin: 4px 0` (double-spaced). Fix both.

### 2.11 Images — **must render an `<img>`**

Desktop treats an image run as a first-class media element: when the host
provides `opts.media.image`, `text_element` (`render.rs:1593-1637`) splits the
paragraph into a `flex-col gap:8` stack of (surrounding text elements, image
elements) **in original order**, so "several images with captions" lay out as
separate rows; inside a table cell with an image run, the same media path renders
instead of flattened text (`render.rs:862-876`).

Web today never reads `run.style.image` (`markdown.tsx:130-159`,
`transcript.tsx:753-776`), so an image run falls into the generic link branch and
renders as a plain `<a>` whose visible text is the alt text (or the raw URL if
alt is empty). **No `<img>` element is ever produced.** This is the largest
functional gap on this surface. Add an `image` branch producing
`<img src alt>` inside the same `flex-col gap:8` split, with the src run through
`transcriptAddress` (plus whatever attachment/blob scheme the transcript already
resolves).

### 2.12 Mermaid

A fenced block whose language is `mermaid` (case-insensitive) renders through a
host-provided diagram closure instead of the plain code-line renderer
(`render.rs:1805-1841`), with a toggle button (`icons::EYE` / `icons::FILE_CODE`,
"Show diagram" / "Show source") switching between the rendered diagram and the
raw source in the ordinary code-block chrome.

Bounds (`mermaid.rs:6,52-61`): `MAX_SOURCE_BYTES` = 16KiB / 256 lines / 2048
whitespace-or-`;{}>`-delimited tokens; the rendered SVG is capped at 2MiB.

Theme mapping (`Palette::from_theme`, `mermaid.rs:37-50`): diagram background =
`theme.surface` flattened onto `theme.bg`, primary fill = `theme.surface_raised`,
text = `theme.text`, muted/line color = `theme.text_muted`, borders =
`theme.border_strong`, accent (sequence activation fill) = `theme.accent`; font
family = `theme.font_sans`, font size fixed 14px. On web:
`--rb-surface`, `--rb-raised`, `--rb-text`, `--rb-text-muted`,
`--rb-border-strong`, `--rb-accent`.

The native renderer (`mermaid_rs_renderer`, `ENGINE_VERSION` =
`"mermaid-rs-renderer/0.3.1"`, a process-wide `RENDER_LOCK` mutex) is
**desktop-only**; web uses a client-side Mermaid.js render with the palette
mapping above. If Mermaid.js is judged too large a dependency, say so in Comments
and ship the toggle-less code block — but do not silently skip it.

### 2.13 Multi-block gaps inside containers

`.md-p { margin: 0 }` plus native block flow means a list item or blockquote
containing several blocks collapses to zero gap. Add
`display:flex; flex-direction:column; gap:4px` to the list item and `gap:8px` to
the blockquote (§5 "Multi-block gap inside list items/quotes").

### 2.14 Streaming veil

Pure math, a verified 1:1 port already. Named identifiers, each with its exact
value:

| Identifier | Value | Meaning / formula it feeds | Desktop | Web |
|---|---|---|---|---|
| `VEIL_EMA_SEED_MS` | `160.0` | initial value of the inter-append-gap EMA before any append has happened, so the very first chunk's duration isn't computed from a zero/undefined gap | `veil.rs:34` | `lib/veil.ts:10` |
| `VEIL_MIN_FADE_MS` | `120.0` | lower clamp on `veil_duration_ms`: a chunk's fade never resolves faster than 120ms even under a very fast/bursty stream | `veil.rs:36` | `lib/veil.ts:12` |
| `VEIL_MAX_FADE_MS` | `400.0` | upper clamp: a chunk's fade never takes longer than 400ms even after a long idle gap | `veil.rs:37` | `lib/veil.ts:13` |
| `VEIL_CURVE_POW` | `1.6` | exponent of the dissolve curve: `veil_opacity(p) = 1 − (1 − p)^1.6` — alpha rises faster than linear early in the fade | `veil.rs:39`, `:58-60` | `lib/veil.ts:15`, `:20-23` |
| `VEIL_GAP_CLAMP_MS` | `1000.0` | ceiling applied to the raw inter-append gap **before** it feeds the EMA update, so one abnormally long pause doesn't blow the EMA out and floor every subsequent chunk at the 400ms max | `veil.rs:41` (private) | `lib/veil.ts:17` (private) |

| Constant/formula | Value | Desktop | Web |
|---|---|---|---|
| EMA seed | 160ms | `veil.rs:34` | `lib/veil.ts:10` |
| min/max fade duration | 120ms / 400ms | `veil.rs:36-37` | `lib/veil.ts:12-13` |
| dissolve curve | `alpha = 1 − (1−p)^1.6` | `veil.rs:39,58-60` | `lib/veil.ts:15,20-23` |
| duration from EMA | `clamp(ema×3, 120, 400)` | `veil.rs:64-66` | `lib/veil.ts:26-28` |
| concurrent-chunk boost | `1 + 0.3 × max(0, n−2)` | `veil.rs:70-72` | `lib/veil.ts:31-33` |
| EMA update | `ema×0.7 + min(gap,1000)×0.3` | `veil.rs:75-77` | `lib/veil.ts:36-38` |
| non-append rewrite | clamps in-flight chunks to the shared prefix, re-veils only the changed tail | `veil.rs:129-155` | `lib/veil.ts:74-91` |
| attach/seed semantics | text present when a row (re)attaches (e.g. reconnect) is the baseline — never fades | `veil.rs:115-122`, `RowVeil::seeded` `:192-199` | `VeilTracker.seed()` `lib/veil.ts:65-67`, used at mount in `LiveMarkdownRow` `transcript.tsx:651-655` |

Rendering differs by construction (gpui paint-only opacity split vs. a DOM
`<span class="veil-fade">` with a CSS `@keyframes rb-veil` baked to the same
`(1−p)^1.6` curve in 8 stops, `app.css:3698-3714`) — an acceptable, unavoidable
platform difference; **MATCHES** in effect.

**Two things to fix:**
1. **Code blocks never veil on web.** `LiveMarkdownRow`
   (`transcript.tsx:637-678`) only special-cases
   `block.kind === "paragraph" || "heading"`; a streaming code block renders
   instantly, whereas desktop veils code lines exactly like prose
   (`render.rs:2065-2070`, `2141-2146`, slicing veil spans per code line via
   `slice_spans`). Extend the veiled path to `codeBlock`, slicing chunk ranges
   per rendered line.
2. **Reduced motion.** Desktop disables the veil entirely at the call site —
   `let veil = (!motion::reduced_motion(cx)).then(|| …)` (`transcript.rs:5494`).
   Web achieves the equivalent with
   `@media (prefers-reduced-motion: reduce) { .veil-fade { animation: none } }`
   (`app.css:4118-4125`) — **MATCHES**; keep it, and make sure the new code-line
   veil is covered by the same rule.

### 2.15 Mend (`mend.rs`, `close_hanging`)

Fully ported 1:1 (`lib/markdown.ts::closeHanging`, lines 645-843, mirrors
`mend.rs` line-for-line including delimiter bookkeeping, bracket/link URL
handling, and the setext zero-width-space guard). `PENDING_LINK_URL =
"roboco:pending-link"` matches exactly (`mend.rs:46`, `lib/markdown.ts:18`), and
the renderer correctly styles-but-disables a pending link
(`markdown.tsx:145-157`: `<span class="md-link md-link-pending">` instead of
`<a>`). **No changes needed** — but spot-check against the checklist in §3.2.

### 2.16 Selection

**`paint_text_selection`** (`render.rs:1229-1257`) is the selection-painting path
for **plain (non-markdown) text** — specifically the user's own message bubble
(doc comment at `render.rs:1224-1228`) — as opposed to markdown text, which
paints its selection wash via the canvas underlay inside
`flat_text_presented_element`. Rule: read `selection::wash_range(key)`; if a
range is active for this element's key, compute its rects with
`range_rects(layout, &range, pad_x: 0.0, inset_y: 0.0)` — i.e. **full
line-height boxes with no horizontal overhang and no vertical inset**, unlike the
inline-code wash's `pad_x:2 / inset_y:2` — and paint each with radius 0, no
border. The color is `selection_wash(theme)` = `theme.selection`
(`render.rs:1220-1222`), the same role the composer and native inputs use
(`theme.rs:711`, values `accent.opacity(0.35 dark / 0.24 light)` at
`theme.rs:120`). After painting it registers the element into the same per-frame
registry and re-registers the mouse listeners, so a drag started in the user
bubble continues seamlessly into an adjacent markdown row and vice versa.

**Web**: browsers give us the cross-element drag for free within the mounted DOM,
and a process-global `Selection` singleton, so the only concrete deliverable here
is `::selection { background: var(--rb-selection) }` scoped to markdown content
and the user bubble (§5 `::selection` color). The virtualization-survival model
(`resolve_spans`/`update_drag`/`extend_virtualized_drag`, `selection.rs:54-199`)
is **not trivially portable**; ticket 18 adds the drag-edge auto-scroll, and
widening the virtualizer's overscan is the accepted mitigation. Say so in
Comments rather than attempting the registry.

### 2.17 Syntax roles → `--rb-syntax-*`

The desktop's `HighlightKind` (`crates/syntax/src/lib.rs:60-92`) has **31**
variants: `Comment`, `Keyword`, `String`, `StringSpecial`, `Escape`, `Number`,
`Boolean`, `Type`, `TypeBuiltin`, `Constructor`, `Function`, `FunctionBuiltin`,
`Macro`, `Property`, `Constant`, `Variable`, `VariableSpecial`, `Parameter`,
`Operator`, `Punctuation`, `Tag`, `Attribute`, `Label`, `MarkupHeading`,
`MarkupRaw`, `MarkupLink`, `MarkupReference`, `MarkupEmphasis`, `MarkupStrong`,
`Embedded`, `Invalid`. (Research 03 §5 says "29 roles, 12 missing"; the enum as
it stands today is 31 — use the list above.)

`lib/syntax.ts:10-27`'s `SyntaxRole` union covers **17**: comment, keyword,
string, stringSpecial, escape, number, boolean, type, function, property,
constant, variableSpecial, operator, punctuation, tag, attribute, macro.
Unmapped, with no `.tk-*` CSS rule (`app.css:3675-3691`): `typeBuiltin`,
`constructor`, `functionBuiltin`, `variable`, `parameter`, `label`,
`markupHeading`, `markupRaw`, `markupLink`, `markupReference`, `markupEmphasis`,
`markupStrong`, `embedded`, `invalid`.

`@roboco/theme` emits `--rb-syntax-<kebab-key>` for every palette key
(`web/packages/theme/src/index.ts:129`). Add the union members and one
`.tk-<role> { color: var(--rb-syntax-<kebab>); }` rule each, so a future
tokenizer upgrade is free. Low priority given the hand-rolled tokenizer's scope,
but cheap.

---

## 3. Pure logic to port

### 3.1 `autolink_runs` / `find_url_start` / `bare_url_len` (`parser.rs:489-576`)

Bare `http(s)://` promotion with alnum-boundary and trailing-punctuation-trim
rules. **NOT ported.** Edge cases from `parser.rs` tests `bare_urls_autolink`
(`:1072`) and `autolink_leaves_non_urls_alone` (`:1102`): a URL glued to a
preceding letter/digit stays text; trailing `.,;:!?*_~` trims; a trailing `)`
trims only if unbalanced against a `(` earlier in the URL.

### 3.2 `close_hanging` (`mend.rs:59-206`) — ✅ ported, verify

Checklist from `mend.rs` tests (all should have web equivalents; spot-check
recommended): balanced text needs nothing; bold/italic/strike close;
half-streamed closers complete (`**bold*` → `**bold**`); nested closers close
innermost-first; bare openers with no content stay literal; closers insert before
trailing whitespace; intraword `_`/single `*` never delimit; list markers
(`* item`) are not openers; inline code shields markers inside it; links/images
mend to the `PENDING_LINK_URL` sentinel; nested parens inside a URL don't confuse
the balance scan; emphasis opened inside a link's text closes inside the link;
emphasis unclosed inside an already-*completed* `[...]` is dropped, not mended; a
lone trailing `-`/`--`/`=`/`==` under a text line gets a zero-width space
(setext-flicker guard) and closers land above the underline line.

### 3.3 Veil (`veil.rs`) — ✅ ported

`veil_opacity` / `veil_duration_ms` / `veil_boost` / `veil_ema_next` /
`ElemVeil::advance` / `RowVeil` — see the table in §2.14. Only the per-line
slicing for code blocks is new.

### 3.4 `table_columns` (`render.rs:727-742`)

natural = `max(content, 48) + 24`; minimum = `min(natural, 96)`;
`min_table_width` = Σ minimums. **NOT ported** — web relies on native table
auto-layout instead.

### 3.5 `link_presentation::truncate` (`link_presentation.rs:71-147`)

Width-bounded label truncation with grapheme-safe binary search and an
`OffsetMap` back to the original text (needed so selection/copy of a truncated
link still yields the full URL). **NOT ported.** Low priority.

### 3.6 `resolve_workspace_file_link` (`workspace_links.rs:14-155`)

Resolves an agent-authored link/plain-text token into a safe workspace-relative
path + optional line/column, rejecting: empty/`?`/NUL targets, `file://`-prefixed
absolute paths outside the workspace root, any scheme other than
`roboco-file:`/`file://`/schemeless, `..`/`.`/empty path segments, backslashes,
drive letters (`:` anywhere in the path), and percent-encoding that doesn't
round-trip (`roboco-file:` mentions only). Line/column parse from `#L123`
fragments or trailing `:123` / `:123:45` suffixes. **NOT ported** —
`markdown-doc.ts` has a much simpler `markdownLinkTarget`/`resolveWorkspacePath`
for the *files preview* surface only, not transcript links.

### 3.7 `transcript_address` / `normalize_address` (`browser/model.rs:37-97`)

Link-click validation: scheme allow-list (http/https only),
control-char/whitespace/backslash rejection, empty-authority rejection,
`%`-escape validation, then URL parse + reject-on-userinfo. **NOT ported** — the
top-priority gap.

### 3.8 `word_range` (`selection.rs:290-320`)

Unicode-aware double-click word bounds (alnum+`_` run; a lone symbol selects
itself; whitespace selects nothing). Native browser double-click word-selection
is a reasonable substitute; verify against `selection.rs` test `word_ranges`
(`:432-443`) only if a custom implementation is ever built.

### 3.9 `resolve_spans` / `update_drag` / `extend_virtualized_drag` (`selection.rs:54-199`)

The virtualization-survives-drag algorithm. **NOT ported**, and not trivially
portable to native `Selection`/`Range`. Do not attempt in this ticket.

---

## 4. Gaps this ticket closes

Copied verbatim from research 03 §5 (all rows).

| Item | Kind | Desktop value | Web value (file:line) | Fix |
|---|---|---|---|---|
| Link scheme/shape validation | MISSING | `transcript_address` allow-lists http/https, rejects control chars, userinfo, bad `%`-escapes (`browser/model.rs:37-97`) | raw `href={style.link}` (`markdown.tsx:152`) | Port `transcript_address` as a TS function; run every link href through it before rendering as `<a>`, else render inert text |
| Images | MISSING | image runs render as `<img>` via host media closure (`render.rs:1593-1637`) | `run.style.image` never read; renders as `<a>` with alt text (`markdown.tsx:130-159`) | Add an `image` branch in `InlineRunView`/`StyledRun` rendering `<img src=... alt=...>` |
| Bare URL autolink | MISSING | `autolink_runs` (`parser.rs:489-576`) | not implemented in `lib/markdown.ts` | Port `autolink_runs`/`find_url_start`/`bare_url_len` into `parseInline` |
| Mermaid diagrams | MISSING | native SVG render + toggle (`render.rs:1805-1841`, `mermaid.rs`) | plain unhighlighted code block | Client-side Mermaid.js render with the theme-role palette mapping in §2.12; add show-source toggle |
| Code block header (language label) | MISSING | 28px header, 11px muted language text (`render.rs:1928-1966`) | none | Add a header bar to `.md-codeblock` showing `language` |
| Code block wrap/fit toggle | MISSING | persisted `code_fences_fit_content`, WRAP_TEXT icon button (`render.rs:2075-2113`) | none — always `white-space: pre` (`app.css:3607`) | Add a toggle button + persisted preference switching `white-space: pre` ↔ `pre-wrap` |
| Code block custom scrollbar | MISSING | themed thumb, drag-to-scroll (`render.rs:2192-2249`) | native browser scrollbar | Low priority — acceptable platform difference |
| Link hover destination card | MISSING | card with raw URL near pointer (`link_destination.rs`) | none | Add a hover/focus popover showing `href` |
| Link right-click/Shift+F10 menu | MISSING | Open in Roboco / external / Copy link (`link_interaction.rs:273-420`) | none (browser native context menu only) | Out of scope for "Open in Roboco" (no embedded browser on web); still add "Copy link address" custom menu if parity desired |
| Link click-during-selection guard | MISSING | `click_is_activation` rejects clicks that moved >4px or occur with active selection (`link_interaction.rs:465-474`) | native `<a>` click always navigates | Add a `mousedown`/`mouseup`-position check + `window.getSelection()` check before allowing navigation |
| Long link truncation | MISSING | width-bound ellipsis truncation with `OffsetMap` (`link_presentation.rs`) | CSS `overflow-wrap: anywhere` wraps instead | Low priority — wrapping is a reasonable substitute for chat width; skip unless pixel parity is required |
| Workspace file link resolution + icon decoration | MISSING | `resolve_workspace_file_link` + file-icon badge beside sole file links (`workspace_links.rs`, `render.rs:1711-1793`) | none in transcript (exists separately, more simply, for the files-preview surface in `markdown-doc.ts`) | Port `resolve_workspace_file_link` and wire a file-icon + click-to-open affordance in transcript links |
| Inline code color/background | WRONG VALUE | text = `theme.code_text` (accent), bg = `theme.code_wash` (accent wash), no border, radius 4.5, pad_x 2 / inset_y 2 (`render.rs:915-925`) | text = plain, bg = `--rb-raised`, **added** 1px `--rb-border`, fixed 12.5px font regardless of context (`app.css:3579-3586`) | Swap to `--rb-accent`-derived text/wash roles, drop the border, inherit font-size from context |
| Link color/underline | WRONG VALUE | monochrome `theme.text` + always-on 1px underline (`render.rs:968-970`, `1008-1012`) | `--rb-accent` color, underline only on `:hover` (`app.css:3634-3642`) | Match: neutral text color, permanent underline |
| Heading sizes h1/h2 | WRONG VALUE | h1 19px, h2 16px (`render.rs:707-710`) | h1 20px, h2 17px (`app.css:3535-3541`) | Correct the two px values |
| Heading line-heights h3–h6 | WRONG VALUE | fixed 22px regardless of level (`render.rs:711`) | relative `1.35×` font-size (≈20/19px at h3/h4-6) (`app.css:3529-3551`) | Use fixed px line-heights per level instead of a relative multiplier |
| Blockquote chrome | WRONG VALUE | accent-tinted rail (0.6 opacity) + accent wash bg (0.05) + `pr:10 py:6` + top/bottom-right radius 6 + 8px child gap (`render.rs:525-551`) | neutral `border-strong` rail, no bg wash, no radius, `pl` only, no child gap (`app.css:3553-3559`) | Rebuild `.md-quote` to match the full box model and accent tint |
| List indent | WRONG VALUE | marker `min-w:18` + gap `8` = 26px effective (`render.rs:581,658`) | `padding-left: 22px` (`app.css:3562`) | Adjust to 26px, or accept as a minor value gap |
| List marker color | WRONG VALUE | accent-colored ordered numbers and unordered dot (`render.rs:637,653`) | default browser marker color (`markdown.tsx:64-83`) | Style `::marker` with `color: var(--rb-accent)` |
| Unordered marker shape | WRONG VALUE | a real 5×5 accent circle, not a bullet glyph (`render.rs:640-655`) | native `disc` glyph | Replace with a custom marker (CSS `list-style: none` + a generated dot) |
| Task checkbox | MISSING interactivity + WRONG VALUE chrome | 16×16 interactive box, accent border/fill + check icon when checked (`render.rs:570-627`) | static `☑`/`☐` glyph, `aria-hidden`, non-interactive (`markdown.tsx:65-70`) | Render an interactive checkbox control wired to a toggle handler; match the box chrome |
| Table chrome | WRONG VALUE | frameless, flat-hairline dividers only, no header bg, no radius (`render.rs:43-48`) | full grid borders + header bg (`app.css:3652-3667`) | Rebuild `.md-table` to remove vertical/outer borders and header fill, keep only horizontal hairlines |
| Table cell padding | WRONG VALUE | uniform 12px (`render.rs:50,854`) | `4px 12px` (asymmetric) (`app.css:3660`) | Use uniform 12px |
| Table header weight | WRONG VALUE | 700/BOLD (`render.rs:54`) | 600 (`app.css:3665`) | Bump to 700 |
| Table column sizing | WRONG BEHAVIOR | content-proportional flex with 48px/96px floors (`render.rs:727-742`) | native browser auto-table-layout | Port `table_columns` and apply as inline flex-basis/min-width per cell, or accept as a lower-priority visual gap |
| Rule color/spacing | WRONG VALUE | `theme.border`, no extra margin (`render.rs:696-700`) | `--rb-border-strong`, `margin: 4px 0` (`app.css:3668-3672`) | Switch color role, drop the margin (rely on the 12px block gap) |
| Multi-block gap inside list items/quotes | MISSING | 4px between a list item's own blocks (`render.rs:664`), 8px between a quote's own blocks (`render.rs:538`) | none — `.md-p { margin:0 }` with no compensating gap inside `<li>`/`<blockquote>` | Add `display:flex; flex-direction:column; gap:4px` (list item) / `gap:8px` (quote) to those containers |
| Code block streaming veil | MISSING | code lines veil like prose, sliced per line (`render.rs:2065-2070,2141-2146`) | `LiveMarkdownRow` only veils `paragraph`/`heading` blocks (`transcript.tsx:637-678`) | Extend the veiled path to `codeBlock`, slicing chunk ranges per rendered line |
| Text-selection continuity across virtualization | MISSING | selection registry survives rows scrolling out of view (`selection.rs:161-199`, `render.rs` REGISTRY) | native `Selection`/`Range`, which loses unmounted content | Either widen the virtualization overscan enough that drags rarely cross the boundary, or build an equivalent JS selection model |
| `::selection` color | MISSING | `theme.selection` = accent @ 0.35/0.24 opacity (`theme.rs:120`) | browser default highlight (no `::selection` rule found) | Add `::selection { background: var(--rb-selection) }` scoped to markdown content |
| Syntax token roles | MISSING (partial) | 29 `HighlightKind` roles incl. `typeBuiltin`, `constructor`, `functionBuiltin`, `label`, `markupHeading/Raw/Link/Reference/Emphasis/Strong`, `invalid` (`crates/syntax/src/lib.rs:61-93`) | `SyntaxRole` union covers 17 roles, no `.tk-*` CSS class for the other 12 (`lib/syntax.ts:10-27`, `app.css:3675-3691`) | Low priority given the hand-rolled tokenizer's scope; add the missing `.tk-*` CSS rules at minimum so a future tokenizer upgrade is free |
| Row/tree block-gap | MATCHES | `MD_BLOCK_GAP` = 12px (`render.rs:30`) | `MD_BLOCK_GAP = 12` (`lib/transcript.ts:948`) | none |
| Body paragraph size/line-height/weight | MATCHES | 14px/22px/400 (`render.rs:32-33,1665-1669`) | `.row-md`/`.md-p` (`app.css:3517-3527`) | none |
| Streaming veil math | MATCHES | `veil.rs` | `lib/veil.ts` | none |
| Mend (marker closing) | MATCHES | `mend.rs` | `lib/markdown.ts:645-843` | none |
| `PENDING_LINK_URL` sentinel + inert pending-link rendering | MATCHES | `mend.rs:46` | `lib/markdown.ts:18`, `markdown.tsx:145-157` | none |
| Reduced-motion veil disable | MATCHES (different mechanism) | `!motion::reduced_motion(cx)` gate (`transcript.rs:5494`) | `@media (prefers-reduced-motion: reduce) { .veil-fade { animation: none } }` (`app.css:4118-4125`) | none |
| Copy-button hover fade timing | MATCHES | `HOVER_FADE` 150ms/`EASE_TAILWIND` (`motion.rs:495-497,972-974`) | `var(--rb-motion-hover-fade)`/`var(--rb-ease-ease-tailwind)` (`app.css:3622`) | none |

Also closed here, from research 02 §5: **row 62** (streaming veil — verify the
attach baseline is captured from the doc REPLAY frame, not the attach-time sync,
and that reduced motion disables it) and **row 80** (`copy_ui_for` /
`code_ui_for` — the 1200ms "Copied" state and the global Fit toggle; the
transcript owns the state, this ticket owns the UI).

---

## 5. Do not

- Do not port "Open in Roboco" or the `open_web_links_in_roboco` setting — there
  is no embedded browser on web (settled in the research's §5 row).
- Do not port the desktop's text-selection registry (`REGISTRY`, `RegEntry`,
  `range_rects` glyph math, `resolve_spans`/`extend_virtualized_drag`) — it
  exists because gpui has no native selection. Ship `::selection` and leave the
  virtualization-continuity gap documented.
- Do not port X11/BSD middle-click primary-selection paste
  (`render.rs:1487-1489`), the `browser-fixture` link registry, the gpui
  `RenderCache`/`flatten_cached`/per-line `CachedCode` (React memoization is the
  web equivalent), or the native `mermaid_rs_renderer` / `RENDER_LOCK` /
  `ENGINE_VERSION`.
- Do not treat `crates/ui/src/links.rs` as part of this surface — despite the
  name it is the sidebar's "copy conversation link" feature
  (`roboco://open/chat/…`), unrelated to in-markdown links.
- Do not invent a type scale file: every markdown text style is defined locally
  as constants in `render.rs`; `typography.rs` is the device-local font
  family/size **settings** system, a different surface.
- Do not touch the row shell, gaps, hover strip or user bubble (18), the tool
  tree or thought-line styling (19), the rail/badges/loaders (20), or
  `lib/markdown-doc.ts` (the files-preview parser).
- Do not restyle `.md-copy`'s hover timing — it already matches.

---

## 6. Acceptance

- [ ] Headings render at 19/27, 16/24, 15/22, 14/22 (h4–h6) at weight 600.
- [ ] Inline code is accent-tinted text on an accent wash, no border, radius 4.5,
      2px horizontal padding, and inherits the surrounding font size (including
      inside a heading).
- [ ] Code fences have a 28px header with the verbatim fence-info language label
      at 11px `text-muted`, a copy button that swaps to "Copied" for ~1.2s, and a
      wrap/fit toggle bound to a persisted global `code_fences_fit_content`
      (default off = horizontal scroll).
- [ ] Links are `--rb-text` with a permanent 1px `--rb-text-muted` underline; an
      href rejected by `transcriptAddress` renders as inert styled text, never an
      `<a>`; hovering a link shows the destination card after the tooltip delay;
      right-click opens a menu with "Open link" and "Copy link address"; a
      4px-plus drag or an active selection does not navigate.
- [ ] A bare `https://…` in prose becomes a link with the boundary and
      trailing-punctuation rules; `foo https://x.com/a(b)c.` autolinks correctly.
- [ ] `![alt](url)` renders an `<img>`; a paragraph mixing text and images lays
      out as a `flex-col gap:8` stack in original order.
- [ ] Blockquotes have the 2px accent@0.6 rail, accent@0.05 wash, 6px right
      corners, `12/10/6` padding and an 8px child gap.
- [ ] Ordered markers are accent-colored numbers in an 18px slot with an 8px gap;
      unordered markers are 5×5 accent circles; items sit 4px apart; task items
      render an interactive 16×16 accent checkbox that toggles.
- [ ] Tables are frameless with horizontal hairlines only, uniform 12px cell
      padding, 700 header weight, GFM alignment, and scroll horizontally when
      needed.
- [ ] `<hr>` is a 1px `--rb-border` bar with no extra margin.
- [ ] A streaming code block's lines fade in with the veil, and under
      `prefers-reduced-motion: reduce` nothing fades.
- [ ] Selecting text inside a message paints `var(--rb-selection)`.
- [ ] Every `HighlightKind` has a `SyntaxRole` member and a `.tk-*` rule.
- [ ] Unit tests in `web/packages/app/tests/markdown.test.ts`:
      `bare_urls_autolink`; `autolink_leaves_non_urls_alone`;
      `transcript_address` accept/reject table (`javascript:`, `data:`,
      `mailto:`, credentials, embedded newline, bare `https://`, bare
      `example.com`, bad `%` escape); `table_columns` (natural/min/`min_table_width`);
      the `close_hanging` checklist from §3.2; the veil constants and
      `veil_duration_ms`/`veil_opacity`/`veil_ema_next` values; per-line veil
      slicing for a code block.
- [ ] Screenshot pair, desktop vs web, states: (a) a reply with h1–h3, a
      paragraph and inline code; (b) a code fence with a language label, copy and
      fit toggle, in both fit states; (c) a reply containing a link (resting,
      hovered with its destination card, context menu open); (d) an image and a
      caption; (e) a blockquote with two paragraphs; (f) an ordered, an unordered
      and a task list; (g) a 4-column table; (h) a streaming code block
      mid-veil.
- [ ] `pnpm -r build` green; `pnpm --filter @roboco/app test` green.
- [ ] No new literal hex/px where a `--rb-*` token exists.

## Comments

Landed on `wp2/21-markdown-parity` (worktree `21-markdown`), 2026-09-18. The
round-2 CSS pre-pass (headings, inline code, links, blockquote, table chrome,
hr, `::selection`, code-frame radius/ink wash, strong 600, strike color, the
14 `.tk-*` rules) and the pending-link guard in `StyledRun` were already in
the base; this ticket landed the renderer-logic layer on top.

**What landed**

- `lib/links.ts` (new): `transcriptAddress`/`normalizeAddress`
  (browser/model.rs:37-106, including the control/whitespace/backslash,
  empty-authority and `%`-escape gates), `resolveWorkspaceFileLink`
  (workspace_links.rs, component-wise root stripping so a POSIX target never
  resolves under a Windows drive root), `linkPresentationTruncate` +
  `OffsetMap` (link_presentation.rs, grapheme-boundary binary search with the
  `'…'.len_utf8()` short-label guard), and `graphemeBreaks` (the hover card's
  ZWSP wrap breaks).
- `lib/markdown.ts`: `autolinkRuns`/`findUrlStart`/`bareUrlLen`
  (parser.rs:489-576, code-point-aware boundary/trim rules) applied after the
  mend in `materialize` and to table cells; the fence language is now the
  VERBATIM first token of the fence info (the old lowercasing was a label
  bug; the highlighter lowercases for its own lookup); `tableColumns` +
  `TABLE_*` constants (render.rs:727-742).
- `components/markdown.tsx`: the code header (28px band, 11px muted verbatim
  label, `ink(0.02)` fill, border-bottom) with the copy button (22px, radius
  5, COPY→CHECK icon, 10.5px "Copied" for 1200ms) and the fit/wrap toggle
  (22×22, radius 6, WRAP_TEXT icon, ink 0.08/0.09/0.13 hover shades) bound to
  the persisted GLOBAL `codeFencesFitContent` (default off = horizontal
  scroll); `MarkdownLink` — every href through `transcriptAddress`, rejected
  destinations render inert styled text (`md-link md-link-pending`),
  workspace-resolvable destinations render as an internal button opening the
  file's right-pane tab, validated ones as guarded anchors; the hover
  destination card (650ms delay, bottom/start, `md-link-card`: 11/14,
  `--rb-raised`, border-strong, `--rb-shadow-sm`, 360×160 caps); the
  right-click menu ("Open link" / "Copy link address", 260px card) over
  `RbContextMenu` + `MenuRow`; the `click_is_activation` guard (≤4px
  up/down delta + empty `window.getSelection()` before an internal
  activation; external anchors only preventDefault on a guard trip so
  ctrl/middle-click keep browser behavior). Images: `RunsOrMedia` — a
  paragraph/heading/cell with image runs splits into a `flex-col gap:8`
  stack in original order, `<img src alt>` for validated http(s) or `data:`
  sources, alt-text fallback otherwise; lists rebuilt on the desktop's
  marker model (18px slot + 8px gap, accent ordered numbers, the real 5×5
  accent disc, 4px item/block rhythm, the same marker at every depth);
  `TaskCheckbox` (16×16, radius 3, accent chrome, 12px check) with the
  `onToggle` seam; tables apply `tableColumns` minimums per column
  (canvas-measured max-content, bold headers) over `width: 100%` auto
  layout; `MarkdownSurface` context (workspace root + open-file hook)
  threaded from `TranscriptView` (chat-page wires `chat.cwd` +
  `rightPaneStore.addFileSurface`; the subagent dialog stays inert).
- `components/transcript.tsx`: `StyledRun` deleted — `InlineRunView` is the
  one inline renderer (veiled rows, thought details, settled blocks share
  validation/media/guards); `LiveMarkdownRow` veils `codeBlock` rows too,
  handing `CodeBlock` the chunk ranges (per-line `sliceTokensForVeil`).
- `lib/veil.ts`: `sliceTokensForVeil` — the pure `slice_spans` port (token
  cuts at chunk boundaries); `lib/syntax.ts`: the 14 missing `SyntaxRole`
  members.
- `tests/markdown.test.ts` (new, 56 tests): `bare_urls_autolink`,
  `autolink_leaves_non_urls_alone`, `find_url_start`/`bare_url_len` edges,
  the `transcript_address` reject table + normalization, `table_columns`,
  the full `close_hanging` checklist (ported test-for-test from mend.rs),
  the veil constants + `veilDurationMs`/`veilOpacity`/`veilEmaNext`,
  per-line veil slicing, `resolve_workspace_file_link` (all three desktop
  tests), `truncate`/`OffsetMap`, and parse-integration guards.

**Deviations / judgment calls**

- **Mermaid ships as source** (no diagram renderer, no EYE/FILE_CODE toggle):
  Mermaid.js is ~2.5MB minified — too heavy for the engine-embedded bundle
  for a niche path, and the ticket explicitly allows "ship the toggle-less
  code block" with a comment. The web files-preview surface (ticket 25)
  already made the same call. A `mermaid` fence renders as an ordinary code
  block with its header.
- **Task checkboxes render disabled** (opacity 0.5, no cursor) exactly like
  the desktop TRANSCRIPT: `tasks: None` at transcript.rs:5459/5507 — the
  interactive toggle only exists in the desktop's files preview, which owns
  an editable document. The web transcript has no message-edit seam, so the
  `onToggle` prop ships unwired; the acceptance bullet's "toggles" waits on
  a future edit path (judgment call for a human: wire one or accept the
  disabled parity state).
- **Images render `<img>` per the ticket's contract** even though the
  desktop transcript itself passes `media: None` (transcript.rs:5460/5508)
  and flattens images to alt text — the media closure only runs in the files
  preview. The ticket's §2.11/§6 demands the branch; flagging the desktop's
  current wiring for a human. `data:` sources are allowed as-is (attachment
  embeds); anything else must survive `transcriptAddress`.
- **Link hover card focus**: the desktop opens instantly on keyboard focus;
  the web card opens after the same 650ms delay on both hover and focus
  (Base UI tooltip trigger semantics). Minor, noted.
- **Table column sizing**: `tableColumns` is ported and tested, and the
  minimums apply as per-column `min-width` (canvas-measured max-content,
  bold at 700) over `width: 100%` auto layout; the browser's own
  content-proportional auto layout stands in for the desktop's Taffy flex
  resolution (the same algorithm per render.rs's own comment). Natural
  widths are not forced per cell.
- **The custom code scrollbar** stays the accepted platform difference (§2.4).
- **`appearance-store.ts` edit from the files table: not needed** — the
  persisted `codeFencesFitContent` (default false) already lives in
  ticket 03's `state/ui-settings.ts`; this ticket only wired consumers.

**Verification**

- `pnpm -r build` (web root) green; `pnpm --filter @roboco/app test` green
  (59 files / 951 tests, 56 of them new in `tests/markdown.test.ts`).
- Browser (`web_smoke` @ 127.0.0.1:27699, `ROBOCO_MOCK_DELAY_MS=1200` +
  `ROBOCO_MOCK_REPEAT=3`, ticket 18's documented pacing additions): boot
  with no error boundary (re-checked after pairing, mid-stream, and through
  the full interaction set); the code header renders 28px with the verbatim
  `rust` label at 11px + copy + fit buttons (6 actions over 3 fences); the
  fit toggle flips ALL fences to `pre-wrap`/`min-height` lines and persists
  `codeFencesFitContent: true|false` in `roboco.ui-settings.v1`
  round-trip, aria/tooltip swapping "Fit content" ↔ "Use horizontal
  scrolling"; h2 at 16px/24px/600, inline code accent text on the accent
  wash with 0px border at inherited 14px, ordered markers in the accent,
  `strong` at 600; the streaming veil fades live (sampled in-page at 100ms:
  fading spans with mid-animation opacities).

**Screenshots** (web only, `.scratch/web-parity/shots/21/`; desktop pairs
skipped per the ticket-18 precedent — no desktop app in this environment,
captures pair against the standing references in `.scratch/web-client/parity/`):

- (a) `web-21a-reply-settled.png` — h2 + paragraph + inline code + ordered
  list + rust fence (the fixture's reply).
- (b) `web-21b-code-default.png` / `web-21b-code-fit.png` — the code header
  with language label, copy and fit toggle in both fit states.
- (h) `web-21h-stream-veil.png` / `web-21h-stream-veil-2.png` — mid-stream
  during the paced mock (the veil fading). Note: the mock script delivers
  each fence inside a single TextDelta, so a code row MOUNTS fully formed
  and seeds its veil baseline (the desktop's own attach semantics,
  `RowVeil::seeded`) — the code-line veil path itself is exercised by the
  `sliceTokensForVeil` unit tests and the `LiveMarkdownRow` code branch,
  which no fixture delta can reach.
- `web-21-final-boot.png` — the final no-error-boundary boot state.
- **Skipped (fixture cannot produce them; crates/ changes are out of scope
  for this ticket):** (c) link resting/hover-card/context-menu, (d) image +
  caption, (e) blockquote, (f) unordered/task lists, (g) 4-column table.
  The mock harness's scripted reply (registry.rs `mock_script`) contains no
  links, images, quotes, unordered/task lists or tables, and every user
  message replays it byte-identically. Those elements are covered by the
  ported unit tests (`transcript_address` table, autolink, workspace links,
  truncation) and by the CSS pre-pass values; their interactive chrome
  (card, menu, drag guard, image split) is wired but unphotographed.
