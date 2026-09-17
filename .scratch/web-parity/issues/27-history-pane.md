# 27 — History pane

**What to build:** A new right-pane tab, "History," showing the checkout's
commit graph: a scrollable list of commits with a lane graph on the left
(rendered as SVG), commit subject + ref badges (branch/remote/tag pills)
in the middle, and optional Author/Date/SHA columns on the right, with
column resize/reorder, a search box, "Fetch all," an All-commits/
Branch-tips toggle, and pagination ("Load more"). Clicking a commit opens
a new diff tab pinned to that one commit (parent vs. commit), titled with
the commit's subject. This is an entirely new surface — nothing under
`web/packages/app/src` references git history today.

**Blocked by:** 07 (Right pane host and multi-instance tabs), 22 (Changes
pane). Per spec.md decision 2, right-pane tabs are multi-instance and
ticket 07 delivers that model; this ticket needs 07's tab model to support
one dedicated "History" tab plus N independent pinned commit-diff tabs,
and needs 22's `DiffScope` type (`lib/diff.ts`) to carry a `"commit"`
value and `GetCheckoutDiff` plumbing that already accepts a `commitSha`.

**Status:** ready-for-agent

**Research:** `../../web-client/research/08-history.md` — full file (§1–§7,
all rows in §5, all RPC contracts, both desktop-only lists). This ticket
inlines everything needed; do not re-read the Rust.

**Desktop reference (for lookups only):** `crates/ui/src/history.rs`
(5,257 lines — the whole `GitHistory` entity), `crates/ui/src/changes.rs`
(History-mode excerpts of `render_header_controls`, `Changes::for_history`),
`crates/ui/src/shell.rs` (~450-470, ~1891-1901, ~2598-2632, ~6460-6500,
~6594-6675, ~6760-6785, ~7100-7143 — `RightSurface` enum,
`add_history_surface`/`add_commit_diff_surface`, surface-picker/`+`-menu
rows), `crates/ui/src/surface_chrome.rs` (47 lines), `crates/ui/src/popover.rs`
(popover_card/menu_row excerpts), `crates/ui/src/settings.rs` (~136-230,
~453-469 — `GitHistoryColumns`/`GitHistoryColumnOrder`/`GitHistoryColumnWidths`/
`GitHistoryAuthorDisplay`), `crates/proto/src/entities.rs` (~259-322 — wire
types), `crates/rpc/src/lib.rs` (~116-123 — method constants),
`crates/engine/src/rpc.rs` (~1503-1608), `crates/engine/src/repos.rs`
(~48-50, ~505-830, ~1007-1030, ~1615-1630).

**Web files to touch:**
| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/components/history/history-pane.tsx` | new | `HistoryPane` (root, mirrors `ChangesSurface`'s wiring shape) |
| `web/packages/app/src/components/history/history-toolbar.tsx` | new | `HistoryToolbar`, `HistoryCount`, `HistorySearchControl`, `HistoryFetchButton`, `HistoryViewButton` |
| `web/packages/app/src/components/history/history-graph.tsx` | new | `HistoryGraph` (SVG lane canvas), `graphColor`, hover hit-testing wiring |
| `web/packages/app/src/components/history/history-row.tsx` | new | `HistoryRow`, `RefBadge`, `RefArea`, `AuthorCell`, `DateCell`, `ShaCell` |
| `web/packages/app/src/components/history/history-columns-menu.tsx` | new | `ColumnMenu`, `AuthorMenu` (popovers) |
| `web/packages/app/src/lib/git-history.ts` | new | every pure function in §3 (`layoutGraph`, `collapseBranchRuns`, `compactCommitsToVisible`, `branchRefKey`, `gitHistoryMatches`, `historyTransitionRows`, `historyListSplice`, `resolveHistoryScrollAnchor`, graph-geometry family, ref-badge sizing, `formatDate`, `historyAuthorName`/`historyAuthorInitial`, `decodeHistoryAvatar`, column layout math) |
| `web/packages/app/src/state/history-store.ts` | new | `HistoryStore` (RPC calls, pagination, search debounce, avatar resolution) |
| `web/packages/app/src/state/right-pane.ts` | edit | extend `RightSurface`/tab model per §1's note (coordinate with ticket 07 — if 07 already generalized this, just add the `history`/`commit` variants) |
| `web/packages/engine-client/src/methods.ts` | edit | add `LIST_GIT_HISTORY`, `SEARCH_GIT_HISTORY`, `RESOLVE_GIT_AVATARS`, `FETCH_ALL`, `SWITCH_REF`, `LIST_REFS` constants |
| `web/packages/app/src/lib/diff.ts` | edit | confirm/extend `DiffScope` to include `"commit"` if ticket 22 has not already added it |
| `web/packages/app/src/styles/app.css` | edit | `.history-*` rule blocks |
| `web/packages/app/tests/git-history.test.ts` | new | pure-logic unit tests (see §3) |

## 1. Context a fresh session needs

- **History is entirely missing on web.** `web/packages/app/src/state/right-pane.ts`
  defines `RightSurface = "changes" | "files" | "terminal" | "preview"` —
  no history member, and its own comment doesn't even mention History as
  a known gap (it names File/Subagent/Browser tabs only). The wire types
  are already generated and ready to import
  (`@roboco/proto`'s `GitHistoryCommit`/`GitHistoryRef`/`GitHistoryRefKind`/
  `GitHistoryComparison`/`GitHistoryPage`), and every icon this surface
  needs already exists in `@roboco/icons` (`gitBranch`, `cloud`, `tag`,
  `magnifer`, `foldVertical`, `expandArrows`, `checklist`, `close`,
  `check`, `refresh`, `altArrowDown`). `GlyphSpinner`/`MatrixSpinner`
  (`components/glyph-spinner.tsx`) are already ported 1:1 and this surface
  should reuse them directly — do not build new spinners.
- Architecturally, History is a `Changes` surface in `DiffScope::History`
  on desktop (same entity type backing Working tree / Branch / Latest
  turn), so a commit click can reuse the existing diff-tab-opening path.
  **On web, model History as its OWN right-pane tab kind** (not a
  `DiffScope` value) — ticket 22 already decided `DiffScope` stays
  `"workingTree" | "branch" | "turn" | "commit"` with no `"history"`
  member (see ticket 22 §2.2/§2.3's "N/A for web" notes). This ticket adds
  the `"commit"` scope's REAL wiring (a click here is what finally
  exercises it) plus a wholly separate History tab/surface.
- **Tab-model dependency (read before starting):** the web's
  `RightSurface`/`ChatPaneState.tabs` model
  (`web/packages/app/src/state/right-pane.ts`,
  `components/right-tab-strip.tsx`) is a closed, SINGLE-instance-per-kind
  union today (each surface appears at most once in `pane.tabs`). Desktop's
  `RightSurface::Diff(u64)` is multi-instance — one History tab AND N
  independent pinned per-commit diff tabs, each its own id. Ticket 07
  (already a blocker on this ticket) delivers that generalized,
  multi-instance model per spec.md decision 2 — add `{kind: "history"}`
  and `{kind: "commit", id, sha, subject}` tab variants to whatever
  tagged-union ticket 07 built, keyed by a generated id (mirroring the
  desktop's `diffs: HashMap<id, Entity<Changes>>` + `diff_seq`). One
  History tab, N independent pinned commit-diff tabs — each commit-row
  click (§2.9) opens a NEW tab, it never replaces an existing one.
- **Entry points to open History**: desktop offers a right-pane
  empty-surface picker card (icon `gitBranch`, label "History," gated on
  the space having git detected) and an equivalent row in the pane's `+`
  menu. If neither of those exist yet on web (they're generalized as part
  of ticket 07's work too), add the minimal affordance needed to open
  History — e.g. a button in the pane header — and note the gap in
  Comments; do not block this whole ticket on 07 shipping a full picker
  grid.
- **Paging** is uniform across all four RPCs at `HISTORY_PAGE_SIZE = 100`,
  cursor-based and forward-only (`GitHistoryPage.nextCursor` is an offset,
  echoed back verbatim as the next request's `cursor`; `null` means
  nothing left to load). The server independently clamps any
  client-supplied `limit` into `[1, 200]` regardless of what the client
  sends — the client should still always send `limit: 100`.
- **Graph rendering**: desktop uses an imperative GPUI `canvas()` with
  manual per-frame path painting and viewport culling. Port to inline SVG
  (`<path d="M… C…">`) sized to the measured container — every geometry
  formula in §3 is UI-framework-agnostic and ports as-is; do not port the
  manual culling loop (the browser gets free clipping/culling from
  `overflow: hidden` on a scrollable ancestor).
- **Column drag/resize**: desktop uses native GPUI drag-and-drop with a
  floating ghost pill. Reuse the SAME HTML5 `dataTransfer` drag-and-drop
  pattern `components/right-tab-strip.tsx` already implements for tab
  reordering (`onDragStart`/`onDragOver`/`onDrop`) — do not invent a new
  drag mechanism. Resizing is a plain pointer-move drag, no browser API
  needed.
- **Responsive measurement**: desktop's `container_query` (a per-frame
  GPUI element-size callback) becomes a `ResizeObserver`-backed hook, the
  same pattern already used in `terminal-dock.tsx`/`transcript.tsx`/
  `diff-view.tsx`.
- **Settings persistence**: `gitHistoryColumns`/`gitHistoryColumnWidths`/
  `gitHistoryColumnOrder`/`gitHistoryAuthorDisplay` need to live in
  whichever store ticket 03 ("Client settings store") establishes. If
  ticket 03 has landed, add these keys there; if not, use local component
  state for now and flag the missing persistence in Comments — do not
  invent a parallel settings mechanism.
- Tokens: no literal hex; `theme.accent`/`busy`/`success`/`warning`/
  `danger`/`text_muted` (the 6-color lane palette) map to the existing
  `--rb-*` role vars. `HOVER_FADE` (150ms, `easeTailwind`) drives the
  hover-focus dimming crossfade; `COLLAPSE` (180ms, `easeOut`) drives the
  compact/full graph-geometry morph and row enter/exit fold; `MENU_IN`/
  `MENU_OUT` (140ms/100ms, `ease`) drive the column/author popovers;
  `RESIZE` (200ms, `easeOut`) drives the search control's expand/collapse.
- Vocabulary: "commit," "branch," "chat" (never "session"/"thread"),
  "engine" (never "provider").

## 2. Spec

### 2.1 Toolbar

**Layout**

| Property | Value | Source |
|---|---|---|
| height | `TITLEBAR_HEIGHT` = 38.0 | surface_chrome.rs:7 |
| padding-x | `EDGE_INSET` = 8.0 | surface_chrome.rs:12,34 |
| gap | `CONTROL_GAP` = 4.0 | surface_chrome.rs:11,37 |
| border | 1px top + 1px bottom, `theme.border` | surface_chrome.rs:38-40 |
| background | `theme.surface.opacity(0.26)` if frosted, else `theme.surface` | surface_chrome.rs:41-45 |

**Children (in order)**:
1. Branch-name title — mono, 11.5px/14px line-height, `theme.text_dim`,
   `max-width: 160px`, truncated, no click. Text = `chat.branch` or
   `"HEAD"` if none.
2. `HistoryCount` (§2.2) — `flex-1 min-w-0 overflow-hidden` (no separate
   flex spacer for History mode).
3. Trailing group (`gap: CONTROL_GAP`): `HistorySearchControl` (§2.3) →
   `HistoryFetchButton` (§2.4) → `HistoryViewButton` (§2.5) →
   `history-refresh` (24×24, radius 6, icon `refresh` 14px,
   `theme.text_muted`, click → `history.refresh()`).

### 2.2 `HistoryCount`

**Layout**

| Property | Value | Source |
|---|---|---|
| container | `h-full min-w-0 flex-1 flex items-center overflow-hidden` | history.rs:1841-1847 |
| commit-count text | 11px/14px, `theme.text_muted` | history.rs:1862-1864 |
| comparison pill shown | only when container width ≥ `HISTORY_COMPARISON_MIN_WIDTH` = 260.0 | history.rs:69,1850 |
| "N ahead" | 10.5px/13px, `theme.accent.opacity(0.88)` | history.rs:1890-1895 |
| separator "·" | 10.0px, `theme.text_faint`, only when both ahead>0 and behind>0 | history.rs:1898-1904 |
| "N behind" | 10.5px/13px, `theme.warning.opacity(0.82)` | history.rs:1906-1916 |
| gap count↔pill | 10.0 | history.rs:1856 |
| gap inside pill | 4.0 | history.rs:1885 |

**Text**: `"{count} commit"` / `"{count} commits"` (pluralized on
`count === 1`); `"{ahead} ahead"`; `"{behind} behind"`; tooltip
`"Compared with {base}: {ahead} ahead, {behind} behind"`. `count` is
`null` while nothing has loaded yet (render nothing). Comparison pill only
shows when `ahead > 0 || behind > 0`.

**Data**: commit count = search total (or the loaded-so-far length as a
fallback) while searching, else `headCommitCount` from the last page;
comparison = the last page's `comparison`, hidden if all-zero.

### 2.3 `HistorySearchControl`

**Layout (collapsed)**

| Property | Value | Source |
|---|---|---|
| size | 24×24 | history.rs:1686 |
| radius | 6 | history.rs:1691 |
| icon | `magnifer`, 14px, `theme.text_muted` | history.rs:1709-1712 |
| background | hover-blend `wash(0.0) → wash(0.14)` | history.rs:1693-1697 |
| tooltip | "Search commits", 350ms delay | history.rs:1713-1719 |

**Layout (expanded)**

| Property | Value | Source |
|---|---|---|
| height | 24 | history.rs:1750 |
| width | `HISTORY_SEARCH_WIDTH` = 196.0, `min-width: 80`, `flex-shrink: 1` | history.rs:64,1751-1753 |
| padding | left 8 (`EDGE_INSET`), right 2 | history.rs:1758-1759 |
| radius | 6 | history.rs:1760 |
| background | `ink(0.035)` | history.rs:1761 |
| gap | 6 (icon/input/close) | history.rs:1757 |
| status icon slot | 14×14; `GlyphSpinner` (scale 1.5) while search-loading, else `magnifer` 11px `theme.text_faint` | history.rs:1732-1747 |
| input | placeholder `"Search"`, 11px text / 14px line-height | history.rs:1401-1403 |
| close button | 16×16, radius 3.5, `close`/9px `theme.text_faint`, hover `ink(0.08)` | history.rs:1783-1803 |

**States**

| State | Condition | Change |
|---|---|---|
| Collapsed | default | icon-only trigger |
| Expanding | click on collapsed trigger | mode → Expanded; focus the input on the next frame (a synchronous `ref.current.focus()` after mount is sufficient on web — no `on_next_frame` workaround needed) |
| Expanded, empty, idle | text empty for `HISTORY_SEARCH_IDLE_DISMISS` = 1,500ms with no keystroke | auto-collapses |
| Expanded, has text | any non-empty text | idle-dismiss timer cancelled |
| Collapsing | blurred while empty, or idle-dismiss fires | 200ms width/opacity tween back to the icon, then unmount the input |

**Interactions**: click collapsed trigger → expand + focus. Typing →
update the query synchronously (local, instant re-filter via
`gitHistoryMatches`, §3) plus a server round trip on a
`HISTORY_SEARCH_DEBOUNCE` = **70ms** debounce (SearchGitHistory). Blur
while empty → collapse. Click × → clear text, schedule idle-dismiss.

**Motion**

| What | Trigger | Spec | From → to | Reduced motion |
|---|---|---|---|---|
| width + opacity morph | expand/collapse | `RESIZE` (200ms, `easeOut`) — `--rb-motion-resize`/`--rb-ease-ease-out` | width `24px ⇄ 196px`, opacity `0.45 ⇄ 1.0` | snap instantly |

**Text**: placeholder `"Search"`.

### 2.4 `HistoryFetchButton`

**Layout**

| Property | Value | Source |
|---|---|---|
| height | 24, `px-8`, radius 6 | history.rs:1556-1563 |
| gap | 6 (icon/label) | history.rs:1562 |
| background | `wash(0.05)` while fetching, else hover-blend `wash(0.0) → wash(0.14)` | history.rs:1564-1572 |
| icon (idle) | `cloud`, 14px, `theme.text_muted.opacity(0.75)` | history.rs:1596-1600 |
| icon (fetching) | `GlyphSpinner` scale 1.75, tinted `theme.glyph` | history.rs:1587-1594 |
| label | 11px, `theme.text_muted` idle / `theme.text_faint` fetching | history.rs:1602-1611 |

**States**: idle label `"Fetch all"`; fetching label `"Fetching…"`, not
clickable.

**Interactions**: click (idle only) → `FETCH_ALL` RPC, then reload page 0
on success; also invalidate any cached branch list / scoped diffs
elsewhere in the Changes surface, since fetched remote refs can change
branch-comparison results.

**Text**: `"Fetch all"`, `"Fetching…"`.

### 2.5 `HistoryViewButton`

**Layout**: 24×24, radius 6, `theme.accent.opacity(0.12)` background when
showing tips else the standard hover-blend wash; icon `foldVertical` 14px,
`theme.accent` when active else `theme.text_muted`. Tooltip 350ms delay.

**States**: `viewMode === "branchTips"` → active, tooltip "Show all
commits"; else tooltip "Show branch tips".

**Interactions**: click toggles `viewMode` between `"allCommits"` and
`"branchTips"` (animates rows per §2.8's row enter/exit fold).

### 2.6 Fetch-error / search-error banners

**Layout**: `h(28px)`, `flex items-center px(8)`, `border-b-1
theme.danger.opacity(0.16)`, `bg theme.danger.opacity(0.05)`, text 11px
`theme.danger_muted`, truncated.

**Text**: fetch banner = `"Fetch failed: {error}"`; the second banner only
appears once commits are already visible and shows the raw search/list
error string as-is.

### 2.7 Column header row

**Layout**

| Property | Value | Source |
|---|---|---|
| height | 24 | history.rs:4459 |
| border-bottom | 1px `hairline(0.06)` | history.rs:4463-4464 |
| text | 9.5px, `theme.text_faint` | history.rs:4465-4466 |
| graph spacer width | current `GraphGeometry.width` (animates during compact-morph) | history.rs:4444-4448 |
| "Commit" cell | `flex-1 min-w-80` | history.rs:4468 |

**Children (in order)**: graph spacer → "Commit" label →
`history-optional-column-headers` (visible Author/Date/SHA headers, in
persisted order) → `history-columns-button`.

**Per-column header cell** (`render_column_header_cell`): width =
persisted width (or the fixed 80px min for "Commit"), draggable (HTML5 DnD
per §1, carrying a floating ghost pill: label text, `border_strong`
border, `surface_raised` bg, `shadow_md`, 10.5px text, opacity 0.9), with
a resize handle (6px-wide invisible strip centered on the divider,
`cursor: col-resize`, hover tint `border_strong.opacity(0.7)`,
double-click resets that pair's widths to defaults). Drop-target
indicator: a 2px accent-colored vertical line on the landing side.

Author header additionally handles right-click → opens the author-display
menu (§2.11) at the click point.

**`history-columns-button`**: 20×20, `top:2 right:3`, radius 5, icon
`checklist` 12px `theme.text_muted`, invisible until the header row is
hovered or the menu is open, hover bg `ink(0.08)`. Click → opens the
column-picker menu (§2.10) at the click point.

### 2.8 Graph (SVG lane canvas + per-row graph cell)

**Layout constants**

| Const | Value |
|---|---|
| `HISTORY_ROW_HEIGHT` | 36.0 |
| `HISTORY_LANE_SPACING` | 12.0 (natural) |
| `HISTORY_NODE_RADIUS` | 3.0 |
| `HISTORY_HEAD_RING_PADDING` | 2.0 |
| `HISTORY_STROKE_WIDTH` | 1.5 |
| `HISTORY_GRAPH_FOCUSED_STROKE_WIDTH` | 2.25 |
| `HISTORY_GRAPH_SATURATION` | 0.72 (HSL saturation multiplier) |
| `HISTORY_GRAPH_SIDE_PADDING` | `EDGE_INSET*2 - NODE_RADIUS` = `8*2-3` = 13.0 |
| `HISTORY_GRAPH_TRAILING_PADDING` | 12.0 |
| `HISTORY_GRAPH_MIN_COMPACT_WIDTH` | 48.0 |
| `HISTORY_GRAPH_MAX_WIDTH_RATIO` | 0.34 (never exceeds 34% of pane width) |
| `HISTORY_GRAPH_RESIZE_STEP` | 2.0 (width snaps to 2px steps) |
| `HISTORY_GRAPH_COMPACT_ENTER_SUBJECT_WIDTH` | 160.0 |
| `HISTORY_GRAPH_COMPACT_EXIT_SUBJECT_WIDTH` | 184.0 (hysteresis vs. enter) |
| `HISTORY_GRAPH_COMPACT_ENTER_LANE_SPACING` | 4.0 |
| `HISTORY_GRAPH_COMPACT_EXIT_LANE_SPACING` | 5.0 |
| `HISTORY_GRAPH_ROW_OVERLAP` | 0.75 (px each curve over/undershoots row edges so adjacent rows' strokes meet without a seam) |
| `HISTORY_GRAPH_HIT_RADIUS` | 5.5 |
| `HISTORY_GRAPH_UNFOCUSED_OPACITY` | 0.24 (stroke floor while another lane is focused) |
| `HISTORY_ROW_UNFOCUSED_OPACITY` | 0.6 (row content dim floor while another lane is focused) |

**Palette** (cycling by `nodeColorId % 6`, saturation × 0.72): `theme.accent`,
`theme.busy`, `theme.success`, `theme.warning`, `theme.danger`,
`theme.text_muted`.

**Rendering** (SVG, replacing the desktop's imperative canvas): one `<svg>`
absolutely positioned behind the row list, one `<path>` per (palette-color
× pass). Each row contributes 0-3 segments:
- **Incoming** — cubic Bezier `(fromX, -overlap)` → `(toX, middle)`, both
  control points at `middle*0.55` height, matching x's.
- **Outgoing** — cubic Bezier `(fromX, middle)` → `(toX, rowHeight+overlap)`,
  control points at `middle*1.45`.
- **Through** — straight line if `fromLane === toLane`, else a symmetric
  cubic Bezier with both controls at `middle`.

`middle = rowHeight / 2`. In COMPACT mode (all lanes collapsed to a single
rail), every row instead paints one vertical straight segment down the
rail x; the fold affordance still renders in place. A HEAD commit gets a
ring: 1px-bordered circle of radius `nodeRadius + HISTORY_HEAD_RING_PADDING`,
border = the lane color, fill = `theme.bg` (reads as hollow).

**Focus (hover) behavior**: hovering the graph or a row highlights every
segment sharing that node's `colorId`: focused segments get
`HISTORY_STROKE_WIDTH → HISTORY_GRAPH_FOCUSED_STROKE_WIDTH` (1.5→2.25) and
full row opacity; everything else fades ROW CONTENT toward
`HISTORY_ROW_UNFOCUSED_OPACITY` (0.6) and STROKE COLOR toward the
background by `(1 - HISTORY_GRAPH_UNFOCUSED_OPACITY) * hoverT`, where
`hoverT` rides HOVER_FADE (150ms, `easeTailwind`) — the whole focus/defocus
is a 150ms cross-fade, not an instant swap. Implement with CSS transitions
on `stroke-width`/`opacity`/`stroke` driven by a hovered-color-id class or
inline style, not per-frame JS animation.

**Branch fold control** (per-row, `AllCommits` view only, only on commits
carrying a foldable branch/remote ref): 16×16 circular button just right
of the node, border `color.opacity(0.32)`, bg `theme.bg.opacity(0.96)`,
icon `expandArrows` (collapsed) or `foldVertical` (expanded) at 9px tinted
`color.opacity(0.9)`. Invisible unless the branch is already collapsed OR
the row's graph cell is hovered. Tooltip 250ms delay:
`"Collapse {label}"` or `"Expand {label} ({n} hidden)"` / `"Expand
{label}"` if no hidden count is known yet.

**Interactions**: pointer-move over the graph hit-tests node-then-segments
(`hoveredGraphPath`, §3) to set the hovered path; row hover (anywhere in
the row) also sets the same shared hover so the WHOLE row (not just the
graph column) triggers the lane highlight. Click the fold control toggles
that branch/remote's collapse; click elsewhere on the row opens the
commit (§2.9).

**Motion — geometry morph** (compact ⇄ full): `COLLAPSE` (180ms,
`easeOut`) interpolates `width`/`laneSpacing`. Collapsing converges every
lane onto the rail's x BEFORE the rail-only paint mode kicks in (only
becomes visually `compact` at `progress ≈ 1`); expanding starts from the
rail and leaves `compact` only at `progress ≈ 0`. Plain width-only
resizes (no mode flip) are NOT animated — snap immediately to the new
fitted value, snapped to the 2px resize step.

### 2.9 Commit row

**Layout**

| Property | Value | Source |
|---|---|---|
| height | `HISTORY_ROW_HEIGHT` = 36.0 | history.rs:4059 |
| text | 11px base | history.rs:4065 |
| hover bg | `ink(0.025)` | history.rs:4068 |
| focused-lane wash | `ink(0.018 * hoverT)` when this row's lane is the hovered one | history.rs:4022-4024,4067 |
| cursor | pointer | history.rs:4066 |

**Children (in order, left→right)**: graph cell (§2.8, width = current
graph geometry) → subject+refs cell (`flex-1`,
`min-width: HISTORY_COMMIT_SUBJECT_MIN_WIDTH`=80.0, `overflow: hidden`) →
optional-columns group (`flex-shrink: 1`, whichever of Author/Date/SHA are
enabled, in persisted order).

**Subject + refs cell** (own `ResizeObserver`): subject text
`flex-1 min-w-0 truncate`, 12px, `theme.text`; ref badges area width =
`refAreaWidth(measuredWidth)` (§3), rendered only when `refs.length > 0`.

**Ref badge**: height 16, `max-width: 112` (`HISTORY_REF_BADGE_MAX_WIDTH`),
`px:5`, radius 4, gap 2 (icon↔label), bg = `refColor(kind).opacity(0.07)`,
text 10px `refColor(kind).opacity(0.9)`, icon 10px
`refColor(kind).opacity(0.78)`. `refColor`: Branch → `theme.accent`;
Remote → `theme.busy`; Tag → `theme.warning`. Icon: Branch → `gitBranch`,
Remote → `cloud`, Tag → `tag`. Tooltip (350ms) = `refDescription`:
`"Branch: {label}"` / `"Remote branch: {label}"` / `"Tag: {label}"`.

**Ref overflow badge**: `"+{hidden}"`, 10px `theme.text_faint`, tooltip
lists every hidden ref's description. Only rendered when `hidden > 0`.

**Optional cells**:
- **Author** (`AuthorCell`): width = persisted `widths.author` (default
  88, min 44/max 220). Avatar mode: centered 20×20 circle, 1px border
  `hairline(0.12)`, bg `wash(0.08)`, either the resolved avatar image
  (`object-fit: cover`) or a fallback initial letter (9px text, 18px
  line-height, `theme.text_faint`, `position: relative; top: 0.5px` for
  optical centering); tooltip = full name, 300ms delay. Name mode:
  `pr-8 truncate`, `theme.text_muted`.
- **Date** (`DateCell`): width = persisted `widths.date` (default 88, min
  68/max 180). 10.5px `theme.text_muted`, `pr-8`, truncated. Text via
  `formatDate` (§3).
- **SHA** (`ShaCell`): width = persisted `widths.sha` (default 74, min
  58/max 140). Clickable 24px pill, mono, 10.5px, hover bg `ink(0.07)`;
  shows first 7 hex chars, or `"Copied"` in `theme.accent` for 1,200ms
  after a click.

All optional-column cell content dims to the same lane-unfocus opacity as
the graph (§2.8's `HISTORY_ROW_UNFOCUSED_OPACITY`) when another lane is
focused.

**States**: `copied === true` for the clicked row's SHA cell for 1,200ms,
independent per-row (only one SHA "copied" at a time).

**Interactions**: click anywhere except the SHA pill and the fold control
→ open a NEW, independent pinned commit-diff tab (per §1's tab-model
note) scoped to `DiffScope::Commit` with this commit's sha, titled with
the commit's subject (trimmed) or its first 7 sha chars if the subject is
empty. Click
SHA pill → copy full SHA to clipboard, flip to "Copied" for 1.2s. Hover
row → lane focus + row background. Hover graph cell → same focus via
pointer-local hit testing.

**Motion — row enter/exit fold** (view-mode toggle, branch collapse/
expand, or a page splice adding/removing interstitial rows): `COLLAPSE`
(180ms, `easeOut`). Entering rows animate `height: 0 → 36px`, `opacity:
0.35 → 1.0`; exiting rows animate the reverse. Rows unaffected by the
transition are NOT wrapped in this animation — they re-lay-out instantly.

### 2.10 Column picker menu

**Layout**: popover card (`border-1 hairline(0.10)`, `radius 12`
(`CARD_RADIUS`), `shadow-lg`, `p:4`, text 13px, bg `glass_overlay()` if
frosted else `surface_overlay`), width 132, inner `p:3 radius:9`. Rows:
`gap:0 px:7 py:4 rounded:6 text:11.5`. A "Reset" row + a 1px
`hairline(0.08)` divider appear only when any column/width/order differs
from defaults.

**Children (in order)**: "Author" (checkbox = `columns.author`), "Date"
(`columns.date`), "SHA" (`columns.sha`), optional divider, optional
"Reset" row (`theme.text_muted`).

**Interactions**: click a row toggles that column's visibility, persisted
immediately. Click "Reset" restores columns/widths/order to defaults.
Click outside closes.

**Motion**: `MENU_IN` (140ms, `ease`) in, `MENU_OUT` (100ms, `ease`) out —
`--rb-motion-menu-in`/`--rb-motion-menu-out`, `--rb-ease-ease`.

### 2.11 Author-display menu

**Layout**: same popover treatment, width 116. One row: "Name," with a
check glyph when `authorDisplay === "name"` (Avatar is the unchecked/
default state — a two-state toggle presented as ONE togglable row, not a
two-row radio list).

**Interactions**: click flips Avatar⇄Name, persists immediately;
switching to Avatar triggers avatar resolution for every commit already
loaded. Opened by right-clicking the Author column header.

### 2.12 Load-more / loading / retry footer row

**Layout**: 48px tall, centered button 28px tall, `px:11`, radius 7,
border 1px `theme.border.opacity(0.85)`, bg `theme.surface_raised.opacity(0.72)`,
text 11px. Hover (only when not pending): bg `theme.element_hover`,
border `theme.border_strong.opacity(0.75)`, text `theme.text`.

**States**

| State | Condition | Label | Icon | Clickable |
|---|---|---|---|---|
| Loading | search-loading or list-loading | "Loading…" | none | no |
| Retry | not pending, error set | "Retry" | `refresh` | yes |
| Load more | not pending, no error | "Load more" | `altArrowDown` | yes |

**Interactions**: click → `loadOlder()` (§3).

### 2.13 Top-level empty/loading/error states

| State | Condition | Content |
|---|---|---|
| No repo | no chat's `cwd` resolved | Centered, 12px `theme.text_faint`, `"No repository selected"` |
| Initial loading | loading and no commits yet | Centered column: `MatrixSpinner` (scale 3.0) + 12px `theme.text_faint` `"Loading history…"` |
| Empty/error | visible commits empty (and not the two above) | Centered, `px:20`, 12px; color `theme.warning` if an error string present else `theme.text_faint`. Text = the error string if present, else `"No matching commits"` (search active), `"No branch tips found"` (branch-tips view empty), `"No commits found"` (otherwise) |
| List | otherwise | graph canvas + virtualized row list |

## 3. Pure logic to port

All pure, framework-agnostic — port to `lib/git-history.ts` with the exact
test names as the acceptance checklist (adapt to your test framework's
naming convention, e.g. camelCase, but keep the mapping obvious).

- **`layoutGraph(commits, headSha) -> GraphLayout`**. Commits arrive
  child-before-parent (topo order). Walk a list of active lanes (each
  tracks the sha it's waiting to reach); for each commit: find lanes
  already targeting this sha → determines `nodeLane`/`nodeColorId`
  (reuses the first such lane's color, or mints a new color if this is a
  root of a new lane); remove those resolved lanes; open one outgoing
  lane per parent (`parentShas[0]` reuses the incoming lane's id/color —
  "continues straight down"; `parentShas[1..]` each mint a new lane
  inserted immediately after the primary one, for merges); record
  `Through` segments for every lane not involved in this row, `Incoming`
  for lanes resolving into this node, `Outgoing` for lanes leaving it.
  `maxLaneCount` = widest lane count ever observed. `isHead` = `headSha
  === commit.sha`. Tests: `graph_splits_and_rejoins_merge_lanes`,
  `appending_older_commits_preserves_the_loaded_prefix_layout`.
- **`collapseBranchRuns(commits, collapsedRefs, headSha) -> [Commit[],
  Map<string, number>]`**. For each ref key in `collapsedRefs` (see
  `branchRefKey` below), find that ref's lane color at the moment it's
  introduced. A commit is HIDDEN iff its lane's color is one of the
  collapsed colors, AND it is not the ref's own tip commit, AND it
  carries no refs of its own, AND it is not a junction (junction =
  `parentShas.length !== 1` OR more than one child points at it). Hidden
  commits are removed via `compactCommitsToVisible`; the returned map
  counts how many commits were hidden per collapsed ref key (fold
  tooltip's "(n hidden)"). Test:
  `collapsing_a_branch_contracts_linear_parents_and_keeps_junctions`.
- **`compactCommitsToVisible(commits, visible) -> Commit[]`**. Keeps only
  commits whose sha is in `visible`, in original order, rewriting each
  kept commit's `parentShas` to skip over any run of invisible ancestors
  — walk each hidden parent chain ITERATIVELY (explicit stack, NOT
  recursive — must survive very deep/wide histories), de-duplicating
  parents. Also the routine that keeps the graph coherent for SEARCH
  results (visible = matched shas). Tests:
  `search_compaction_connects_matches_across_hidden_commits`,
  `search_compaction_handles_a_twenty_thousand_commit_gap` (must not
  stack-overflow on a 20,000-deep linear gap).
- **`branchRefKey(ref) -> string | null`**. Branch → `"local:{label}"`,
  Remote → `"remote:{label}"`, Tag → `null` (tags cannot be folded). Test:
  `branch_fold_keys_keep_local_and_remote_identity`.
- **`gitHistoryMatches(query, commit) -> boolean`**. Empty/whitespace
  query matches everything. Otherwise: matches if the lowercased sha
  starts with the lowercased trimmed query, OR a fuzzy-match scorer
  accepts `"{sha} {subject}"` against the query. Case-insensitive over
  Unicode (a locale-aware fuzzy matcher, so accented queries like
  `"réparer"` must match `"RÉPARER…"`). This is a client-side re-filter of
  already-loaded pages used for instant feedback WHILE a debounced
  server-side `SearchGitHistory` call is also in flight — implement both
  paths (local filter + debounced RPC), not just one. Tests:
  `history_search_matches_fuzzy_subject_terms_and_sha_prefix`,
  `git_history_matches_unicode_case_insensitively`,
  `history_search_keeps_unicode_engine_result_visible`.
- **`historyTransitionRows(old, target) -> [Commit[], Transition[]]`**.
  Builds one interim list containing every `old` row plus every `target`
  row, ordered so rows before `target`'s first shared anchor keep their
  old position (tagged `Exiting`); every `target` row is emitted in
  `target`'s final order, tagged `Stable` if it existed in `old` or
  `Entering` if new; any `old`-only rows sitting between two `target` rows
  are re-inserted right after their preceding shared anchor (`Exiting`).
  Tests: `transition_rows_fold_old_commits_beside_their_stable_anchor`,
  `transition_rows_expand_new_commits_in_their_final_order`.
- **`historyListSplice(old, oldHasLoadMore, target, targetHasLoadMore) ->
  [Range, number] | null`**. Diffs two ordered key-lists (a trailing
  "load more" sentinel counts as one more key) down to the minimal
  contiguous splice. `LOAD_MORE_KEY = "\0history-load-more"` — a
  NUL-prefixed sentinel that can never collide with a real hex sha; push
  it onto a side's key list only when that side `hasLoadMore`. Returns
  `null` if nothing changed. Test:
  `list_splice_preserves_the_unchanged_prefix_around_a_fold`.
- **`resolveHistoryScrollAnchor(anchor, old, target) -> ScrollAnchor |
  null`**. If the anchored sha still exists in `target`, keep it.
  Otherwise walk forward from its old position to the next surviving sha;
  if none, walk backward to the previous surviving one; reset
  `offsetInItem` to 0 either way. Test:
  `removed_scroll_anchor_moves_to_the_next_surviving_commit`.
- **Graph geometry / responsive layout family**:
  - `GraphGeometry.natural(laneCount)`: `width = SIDE_PADDING +
    TRAILING_PADDING + NODE_RADIUS*2 + (lanes-1)*LANE_SPACING`;
    `laneSpacing = LANE_SPACING` (12.0).
  - `GraphGeometry.fitted(laneCount, width)`: if 1 lane or `width >=
    natural.width`, return natural; else clamp `width` into `[fixedWidth,
    natural.width]` where `fixedWidth = SIDE_PADDING+TRAILING_PADDING+
    NODE_RADIUS*2`, derive `laneSpacing = (width-fixedWidth)/(lanes-1)`.
  - `GraphGeometry.compact(laneCount)`: `laneSpacing = 0`, `compact =
    true`.
  - `.laneX(lane)`: `SIDE_PADDING + NODE_RADIUS + lane*laneSpacing` —
    device-pixel rounding is a desktop-only concern (§6), skip it on web.
  - `responsiveGraphGeometry(laneCount, containerWidth,
    optionalColumnsWidth) -> GraphGeometry`: if natural width already ≤
    `HISTORY_GRAPH_MIN_COMPACT_WIDTH` return natural outright; else fit
    the natural width into `min(contentBudget, shareBudget)` where
    `contentBudget = containerWidth - optionalColumnsWidth -
    HISTORY_COMMIT_SUBJECT_MIN_WIDTH` (floored at
    `HISTORY_GRAPH_MIN_COMPACT_WIDTH`) and `shareBudget = containerWidth *
    0.34` (floored the same way). Tests:
    `responsive_graph_keeps_natural_spacing_when_it_fits`,
    `responsive_graph_compresses_lanes_to_preserve_commit_space`.
  - `shouldUseCompactGraph(target, previous, containerWidth,
    optionalColumnsWidth) -> boolean`: `laneCount <= 1` never compacts.
    Otherwise compares the subject width left over
    (`containerWidth - optionalColumnsWidth - target.width`) and
    `target.laneSpacing` against TWO DIFFERENT thresholds depending on
    current state — exit thresholds (184px subject / 5.0 spacing) are
    looser than enter thresholds (160px / 4.0) — explicit hysteresis so
    the mode doesn't flap at the boundary. Test:
    `narrow_commit_space_switches_to_a_compact_rail_with_hysteresis`.
  - `stabilizedGraphGeometry(target, previous, compact) -> GraphGeometry`:
    if `compact`, return `GraphGeometry.compact` outright. Else snap
    `target.width` to the nearest natural width if extremely close, or
    floor it to the nearest `HISTORY_GRAPH_RESIZE_STEP` (2px) multiple; if
    `previous` has the same lane count and is within one resize-step of
    the new snapped value, keep emitting `previous` unchanged (absorbs
    drag jitter). Tests:
    `responsive_graph_geometry_ignores_sub_step_resize_jitter` (skip the
    device-pixel-snapping test, §6 desktop-only).
  - `interpolateGraphGeometry(from, to, progress) -> GraphGeometry`:
    linear-interpolates `width`/`laneSpacing`; `laneCount` snaps
    immediately to `to`'s; `compact` stays `false` throughout a collapse
    (only flips true at `progress ≈ 1`) and stays `true` throughout an
    expand's very first frame (`progress ≈ 0`). Tests:
    `graph_geometry_morph_converges_lanes_before_entering_the_compact_rail`,
    `graph_geometry_morph_expands_the_rail_from_its_compact_start`.
  - `hoveredGraphPath(row, pointX, pointY, geometry) -> colorId | null`:
    node hit-test first (circle at `(laneX(nodeLane), rowHeight/2)`,
    radius `HISTORY_GRAPH_HIT_RADIUS + NODE_RADIUS`); in compact mode,
    also treat any point near the single rail x as a node hit; otherwise
    test every segment via `segmentDistance` (a polyline approximation of
    the cubic Bezier using `SAMPLES = 10` sampled points, or exact
    point-to-segment distance for a straight `Through`) and return the
    closest one within `HISTORY_GRAPH_HIT_RADIUS`. Tests:
    `graph_hover_detects_vertical_and_curved_paths`,
    `graph_hover_prefers_the_node_and_ignores_empty_space`,
    `compact_graph_keeps_each_rows_color_as_its_hover_identity`,
    `compressed_graph_hit_testing_uses_the_fitted_lane_positions`.
  - `graphColor(color) -> color`: multiplies HSL saturation by
    `HISTORY_GRAPH_SATURATION` (0.72), leaves hue/lightness/alpha
    untouched. Test: `graph_palette_only_reduces_saturation`.
- **Ref badge sizing**: `estimatedRefBadgeWidth(ref) = min(22 +
  label.length*5.7, 112)`. `estimatedRefOverflowWidth(hidden) =
  "+{n}".length * 5.7`. `refAreaWidth(commitColumnWidth) =
  min(inner*HISTORY_REF_AREA_RATIO, inner - 80 - HISTORY_REF_GAP)` where
  `inner = max(commitColumnWidth - 8, 0)`. `HISTORY_REF_AREA_RATIO` =
  0.45 (ref area never exceeds 45% of the commit column's inner width).
  `HISTORY_REF_GAP` = 5.0 (flex gap between badges and before the overflow
  chip). `visibleRefCount(refs, availableWidth) -> number`: greedily
  finds the largest prefix of badges (plus a `+N` overflow badge, if any
  remain) that fits in `availableWidth`, trying every count from 1 upward
  and keeping the last that fits (a too-narrow width for even the first
  badge yields 0, preserving room for the overflow chip alone). Tests:
  `ref_badges_expand_with_the_available_width`,
  `ref_badges_preserve_overflow_when_the_first_badge_is_too_wide`,
  `ref_area_preserves_subject_space_and_caps_at_forty_five_percent`.
- **`formatDate(value) -> string`**: parses RFC 3339, formats
  `"MMM D, YYYY"` (e.g. `"Aug 12, 2026"`, no leading zero on day);
  unparsable input → `"—"` (em dash).
- **`historyAuthorName(name) -> string`**: trimmed-empty → `"Unknown"`,
  else the name as-is. **`historyAuthorInitial(name) -> string`**: first
  non-whitespace character, uppercased; all-whitespace → `"?"`. Test:
  `author_avatar_fallback_uses_the_first_visible_initial` (note:
  `"  josé"` → `"J"` — accented letters still uppercase).
- **`decodeHistoryAvatar(encoded) -> string | null`**: on web, this
  collapses to handing `` `data:image/*;base64,${encoded}` `` to an
  `<img>` — BUT the server's `ResolveGitAvatars` response has NO mime-type
  field, and browsers honor the declared mime strictly (unlike a lenient
  native image decoder). Port the ~15-line magic-byte sniff (PNG/JPEG/
  GIF87a/GIF89a/WEBP — RIFF+WEBP at offset 8) to pick the correct
  `data:image/...` prefix; unknown format or bad base64 → `null` (render
  the initial-letter fallback instead). Test:
  `github_avatar_payload_decodes_into_a_gpui_image` (adapt: assert the
  sniff returns the right mime string per format, and `null` for garbage
  input).
- **Column layout math**: `historyColumnWidth`/`historyColumnLimits`
  (Commit `[80, ∞)`; Author `[44,220]`; Date `[68,180]`; SHA `[58,140]`).
  `resizedHistoryColumnWidths(widths, anchor, requestedDelta)`: dragging
  the Commit↔first-column divider only resizes that ONE neighbor, clamped
  to its own min/max; dragging an INTERIOR divider (e.g. Author↔Date)
  keeps the pair's combined width constant, redistributing the delta and
  clamping so neither side exceeds its own min/max (`minDelta =
  max(leftMin-leftWidth, rightWidth-rightMax)`, `maxDelta =
  min(leftMax-leftWidth, rightWidth-rightMin)`). Tests:
  `commit_divider_resizes_the_first_visible_fixed_column`,
  `interior_column_divider_preserves_width_and_clamps_both_sides`.
  `historyColumnDropIndex(relativeX, renderedWidth, columns, widths) ->
  number`: rescales the pointer's x from the RENDERED (possibly squeezed)
  width to the DESIRED (natural) total width, then walks columns
  accumulating width, returning the first index whose midpoint the
  pointer hasn't passed yet (or the last index). Test:
  `reorder_drop_index_respects_uneven_column_widths`.
  `reorderedHistoryColumns(order, dragged, target) -> ColumnOrder`:
  remove `dragged`, reinsert adjacent to `target`'s new position — AFTER
  `target` if `dragged` moved rightward, BEFORE it otherwise. Test:
  `reordering_visible_columns_preserves_hidden_columns`.
  `visibleHistoryColumns(order, columns) -> Column[]`: filters `order`
  down to columns whose visibility flag is set. Test:
  `visible_columns_follow_persisted_order_and_skip_hidden_entries`.
  `ColumnOrder.normalized()`: dedupes, appends any of Author/Date/SHA
  missing from the persisted order. `ColumnWidths.clamped()`: clamps (or
  resets to default on NaN) each width into its `[MIN, MAX]`.
- **`refDescription(ref) -> string`**: `"Branch: {label}"` / `"Remote
  branch: {label}"` / `"Tag: {label}"`. Test:
  `ref_tooltip_describes_each_reference_kind`.

## 4. Gaps this ticket closes

| Item | Kind | Desktop value | Web value | Fix |
|---|---|---|---|---|
| History surface itself | MISSING | Dedicated right-pane tab (§1-2) | No file exists; `RightSurface` has no history member (`right-pane.ts:16`) | This ticket in full: store, SVG graph renderer, virtualized row list, column/author popovers, search control |
| `RightSurface` cardinality model | WRONG BEHAVIOR (blocking) | `RightSurface::Diff(u64)` — many independent instances | `RightSurface` is a closed, single-instance-per-kind union (`right-pane.ts:16-18`) | Owned by ticket 07 (already a blocker on this ticket); per spec.md decision 2, ticket 07 delivers the multi-instance model this surface requires |
| History tab title/icon | MISSING | Title "History", icon `gitBranch` (vs `list` for ordinary diff tabs) | `SURFACE_TITLES`/`SURFACE_ICONS` have no entry (`right-pane.ts:21-37`) | Add once the tab model supports it |
| Commit-diff tab title | MISSING | The clicked commit's own subject (trimmed), or first 7 sha chars if empty | n/a | §2.9 |
| Entry points to open History | MISSING | Right-pane empty-surface picker card + `+` menu row, gated on git detection | No picker/`+`-menu concept in web pane yet | Add the minimal affordance per §1; full picker grid is ticket 07's |
| `DiffScope` "commit" | MISSING (real wiring) | `DiffScope::Commit` drives `GetCheckoutDiff`'s mode | `commitSha` param already exists in `ChangesStore.setScope` but has nowhere to route (dead parameter, per ticket 22) | This ticket's commit-row click is what finally exercises it end-to-end |
| RPC method constants | MISSING | `ListGitHistory`, `SearchGitHistory`, `ResolveGitAvatars`, `FetchAll`, `SwitchRef`, `ListRefs` | `methods.ts` ends at `LIST_BRANCHES` (line 92) | Add the six constants — string values must match exactly: `"ListGitHistory"`, `"SearchGitHistory"`, `"ResolveGitAvatars"`, `"FetchAll"`, `"SwitchRef"`, `"ListRefs"` (see §RPC contracts) |
| Wire types | MATCHES | `GitHistoryCommit`/`GitHistoryRef`/`GitHistoryRefKind`/`GitHistoryComparison`/`GitHistoryPage` | Already generated at `@roboco/proto` | Import directly, no change |
| Icons | MATCHES | `gitBranch`, `cloud`, `tag`, `magnifer`, `foldVertical`, `expandArrows`, `checklist`, `close`, `check`, `refresh`, `altArrowDown` | All present in `@roboco/icons` | None |
| Loading spinners | MATCHES | mini glyph spinner / gradient spinner | `GlyphSpinner`/`MatrixSpinner` already ported | Reuse directly |
| Column/author preferences persistence | MISSING | `GitHistoryColumns`/`GitHistoryColumnOrder`/`GitHistoryColumnWidths`/`GitHistoryAuthorDisplay`, all persisted | No equivalent settings keys on web | Add to ticket 03's settings store; local state as a fallback if 03 hasn't landed (flag in Comments) |
| Graph rendering approach | DESKTOP-SPECIFIC (implementation note, not a gap) | imperative GPUI canvas, manual culling | n/a | Port to inline SVG per §2.8/§1 |
| Column drag reordering/resizing | MISSING | native drag-and-drop with a floating ghost pill | closest precedent: `right-tab-strip.tsx`'s HTML5 DnD | Reuse that pattern (§1) |
| Responsive container measurement | MISSING (pattern exists elsewhere) | GPUI `container_query` | `ResizeObserver` already used in `terminal-dock.tsx`/`transcript.tsx`/`diff-view.tsx` | Same pattern (§1) |

### RPC contracts (implement in `state/history-store.ts`)

All four calls are one-shot `call()`s (not `watch()`s), all take
`targetDeviceId` for chats hosted on a non-local device (same convention
as `changes-store.ts`'s existing pattern). `HISTORY_PAGE_SIZE = 100` is
the client's uniform page size on every call. Paging is cursor-based and
forward-only: `nextCursor` (an offset) is echoed back verbatim as the next
request's `cursor`; `null` means nothing left to load.

- **`ListGitHistory`**
  - Params: `{ cwd: string, cursor?: number = 0, limit?: number = 100 }`
    (server clamps to `[1,200]`)
  - Result: `GitHistoryPage { commits: GitHistoryCommit[], branchTips:
    GitHistoryCommit[], headSha: string | null, nextCursor: number | null,
    totalCount: number | null, headCommitCount: number | null, comparison:
    GitHistoryComparison | null }`. `branchTips`/`comparison` populated
    only on the first page (`cursor === 0`); `headCommitCount` drives the
    toolbar's "{n} commits" when not searching.

- **`SearchGitHistory`**
  - Params: `{ cwd: string, query: string, cursor?: number = 0, limit?:
    number = 100 }`
  - Result: same `GitHistoryPage` shape (no `branchTips`/`comparison` in
    practice). Empty/whitespace `query` should not even reach this RPC —
    filter synchronously with `gitHistoryMatches` instead, and only call
    this on the 70ms debounce once the query is non-empty.

- **`ResolveGitAvatars`**
  - Params: `{ cwd: string, authors: { sha: string, email: string }[],
    cursor?: number = 0, limit?: number = 100 }` (server also caps
    `authors.length` at 200 and drops any with `sha.length > 64` or
    `email.length > 512`)
  - Result: `Record<string /* lowercased email */, string /* base64 image
    bytes */>`. Resolution only works for GitHub-hosted `origin` remotes;
    anything else returns `{}`. Only call this at all when the
    author-display preference is Avatar. Batch avatar lookups over
    already-loaded commits using the SAME page boundaries the commit list
    was fetched in (`step` by `HISTORY_PAGE_SIZE`).

- **`FetchAll`**
  - Params: `{ repoPath: string }` (NOTE: `repoPath`, not `cwd`, unlike
    the three above)
  - Result: `{ ok: true }`. Runs `git fetch --all --quiet`. On success,
    re-run `ListGitHistory` from cursor 0 and invalidate any cached branch
    list / scoped diffs elsewhere in the Changes surface.

- **`SwitchRef`** / **`ListRefs`** — NOT actually called anywhere in the
  desktop's `history.rs` despite "branch switching" appearing in some
  briefs as an in-scope History behavior. Add the method constants (per
  the gap table) since the wire supports them, but do NOT wire any UI to
  them from this ticket — if branch switching turns out to belong to the
  Changes surface's base-ref picker instead, that is ticket 22's
  concern, not this one's.

## 5. Desktop-only (not for web)

- GPUI `canvas()` imperative path painting with manual viewport-culling
  and a 2-pass render for hover-focus dimming — the browser gets free
  clipping via `overflow: hidden` and CSS transitions for the dimming; no
  manual culling loop needed unless profiling says otherwise.
- Native GPUI drag ghosts (`HistoryColumnGhost`, `HistoryResizeGhost`) —
  use HTML5 `dataTransfer` drag images or a `position: fixed` element
  following `pointermove`, per §1.
- `window.on_next_frame` focus handoff for the search control's expand —
  irrelevant once the search `<input>` is a real DOM node
  (`ref.current.focus()` after mount works synchronously).
- `cx.reduce_motion()` / GPUI test-harness plumbing — substitute
  `prefers-reduced-motion` and ordinary component tests.
- Device-pixel snapping (`laneX`'s rounding, `stabilizedGraphGeometry`'s
  device-scale parameter) — browsers rasterize SVG strokes correctly at
  any zoom/DPR without this; safe to drop entirely.

## 6. Do not

- Do not model History as a `DiffScope` value — it is its own right-pane
  tab kind (§1). Only `"commit"` is a real `DiffScope` addition, and that
  belongs to ticket 22's type (confirm it exists; add it here only if 22
  hasn't).
- Do not remove or fix `right-pane.tsx`'s `SURFACES_DISABLED` flag —
  that's ticket 07's.
- Do not wire `SwitchRef`/`ListRefs` to any UI control from this ticket
  (§4's RPC contracts note) — add the method constants only.
- Do not port GPUI-specific mechanics called out in §5 — reach the same
  observable behavior with web-native primitives.
- Do not invent a settings-persistence mechanism if ticket 03 hasn't
  landed — use local component state and flag the gap in Comments.
- Do not build a full commit-graph "branch switching" UI — no such
  control exists in `history.rs` (confirmed by source read); if the user
  wants this, it's a new feature request, not a parity port.
- Do not attempt byte-exact device-pixel snapping for the graph lanes —
  SVG handles this natively; porting `laneX`'s rounding formula would be
  pure busywork with no visible benefit in a browser.

## 7. Acceptance

- [ ] A "History" tab is reachable from the right pane (via whatever
      minimal entry point this ticket adds, per §1) and shows the
      toolbar (§2.1), graph (§2.8), and row list (§2.9) for a repo with
      at least one merge commit (to exercise multi-lane rendering).
- [ ] Search: typing filters the ALREADY-LOADED page instantly
      (`gitHistoryMatches`) and fires a debounced (70ms) `SearchGitHistory`
      call; the search control expands/collapses per §2.3's states and
      motion.
- [ ] "Fetch all" shows a spinner while in flight, reloads from cursor 0
      on success, and disables its own click while fetching.
- [ ] The All-commits/Branch-tips toggle animates row enter/exit over
      180ms (COLLAPSE) and updates the row list correctly.
- [ ] Column headers can be dragged to reorder and their dividers dragged
      to resize, with the Commit-divider-only-resizes-its-neighbor vs.
      interior-divider-preserves-pair-width rules from §3 both correct;
      right-clicking the Author header opens the author-display menu.
- [ ] Ref badges show branch/remote/tag with correct colors/icons, and an
      overflow `"+N"` chip appears when the commit column is narrow.
- [ ] Clicking a commit row (not the SHA pill or fold control) opens a
      NEW, independent pinned commit-diff tab titled with the commit's
      subject; clicking a second, different commit opens another tab
      alongside it rather than replacing the first.
- [ ] Clicking a SHA pill copies the full sha and shows "Copied" for
      1.2s.
- [ ] "Load more" fetches the next page via the stored cursor; a
      list/search error shows "Retry" instead.
- [ ] Unit tests: every test name listed in §3 passes in
      `web/packages/app/tests/git-history.test.ts` (adapted naming
      convention is fine; the assertion each name describes must be
      preserved).
- [ ] Screenshot pair, desktop vs web: (a) History tab open, full-width
      graph with 2+ lanes and a merge commit; (b) the same view narrowed
      until the graph enters compact/rail mode; (c) search expanded with
      results filtered; (d) the column picker menu open; (e) a commit row
      hovered showing lane-focus dimming on unrelated rows.
- [ ] `pnpm -r build` green; package vitest green.
- [ ] No new literal hex/px where a `--rb-*` token exists.

## Comments

(empty; appended during implementation)
