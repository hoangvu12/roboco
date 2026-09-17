# 08 — Sidebar

**What to build:** Today the web sidebar is a flat, un-grouped, un-animated
chat list: no device grouping, no sort/organize preferences, no FLIP glide
when rows reorder, no jump-hint chips, no pull-request badge, and several
geometry/copy mismatches (status dot size, archived-row spacing, scroll-edge
fade curve, user-menu contents). After this ticket the sidebar groups chats
by device (when the user picks "By device"), animates reordering the way the
desktop does (a 260ms glide, not a snap), shows the same three-line chat card
with the same corner states (status word, archive pill, jump-hint chip) and
the same archived shelf, and its footer (connection line, user menu) matches
the desktop's strings and geometry. The space-filter/sort **menus** are not
built here (ticket 10) — only their trigger buttons' chrome.

**Blocked by:** 02 (Foundation tokens), 03 (Client settings store), 05 (State
fixes: nav history, send ids, optimistic echo)

**Status:** done

**Research:** `../../web-client/research/16-sidebar-body-add-space.md` §1–§6
(everything except §3.7–§3.14, which is ticket 11's add-space palette), and
`../../web-client/research/01-shell-chrome.md` §3.8–§3.14, §3.29, §4.5, §4.9,
§4.13, §5.3 (S1–S63). Cross-referenced only: `05-pickers-popovers.md`
§3.19–§3.21 (menus this ticket must NOT build) and §07-changes.md §3.14 (the
PR badge component, already built on web).

**Desktop reference (for lookups only):**
`crates/ui/src/shell.rs::render_sidebar` (4283), `::render_chat_sidebar`
(4859), `::render_chat_row` (4435), `::render_connection_pill` (4810),
`::render_update_strip` (5035, owned by ticket 06 — not built here), `::render_user_menu`
(5169), `::chat_row_height` (666), `::resort_offsets` (624),
`::sidebar_key_order_changed` (652); `crates/ui/src/shell/spaces.rs::render_active_rows`
(1362), `::sidebar_disclosure_header` (153), `::sidebar_disclosure_chevron`
(583), `::begin_sidebar_disclosure_motion` (531), `::render_sidebar_disclosure_body`
(549), `::render_archived_section` (1582), `::compare_sidebar_chats` (25),
`::promote_local_device_group` (133), `::status_dot_color` (511);
`crates/ui/src/edge_fade.rs` (whole file); `crates/proto/src/view.rs::format_time_ago`
(221), `::effective_indicator` (44), `::display_status` (69), `::attention_rank`
(75); `crates/ui/src/change_requests.rs::pull_request_badge` (117).

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/components/chat-list.tsx` | edit | `ChatList`, `ChatListRow`, `StatusGlyph`; add device grouping, the FLIP resort glide, the jump-hint chip, the PR badge slot |
| `web/packages/app/src/components/sidebar-disclosure.tsx` | new | `SidebarDisclosureHeader`, `SidebarDisclosureBody` — the shared open/close component both the device groups and the archived shelf use (desktop: one `sidebar_disclosure_header`/`render_sidebar_disclosure_body` pair for both) |
| `web/packages/app/src/components/archived-section.tsx` | edit | switch its header/body to the new shared disclosure component; fix the 8→10px harness gap; add hover/selected brighten; add selected-row state; add the right-click context menu hookup (component itself is ticket 10's `chat-menu.tsx`, this ticket only wires the `onContextMenu`); sort by the user's `sidebarSort` preference |
| `web/packages/app/src/components/space-filter.tsx` | edit | trigger-only geometry (`SpaceFilter`'s trigger button and the sort button) — **do not** wire `.space-filter-sort`'s `onClick` or build either menu (ticket 10) |
| `web/packages/app/src/components/connection-pill.tsx` | edit | verify against §2.9 (mostly matches already) |
| `web/packages/app/src/components/account-row.tsx` | edit | `AccountRow` — drop the invented "Engines"/"Appearance" rows and separator from the sidebar menu, add the muted identity line, single "Settings" row routes to Devices |
| `web/packages/app/src/components/status-dot.tsx` | edit | `LABELS.awaitingInput`: `"Waiting for input"` → `"Input"` |
| `web/packages/app/src/lib/view.ts` | edit | add `compareSidebarChats`, `promoteLocalDeviceGroup`, `sidebarVisibleOrder`, device-grouping in `chatListRows`; fix the branch source to `chat.sourceContext?.branch`; add `changeRequest` to `ChatRow`; make `archivedRows` sort by the user's `sidebarSort` preference instead of its own fixed recency sort |
| `web/packages/app/src/lib/sidebar-store.ts` (or ticket 03's client-settings store, whichever ticket 03 lands as) | edit | add the five persisted view-option fields (`sidebarOrganization`, `sidebarSort`, `sidebarShowBranch`, `sidebarShowPullRequest`, `sidebarShowHarness`) — ticket 10 builds the menu that writes them, this ticket reads them |
| `web/packages/app/src/state/change-requests-store.ts` | edit, minimal | ticket 22 owns this store; if it has no per-chat subscribe entry point yet, add one here rather than duplicating the Changes pane's subscription logic (see §1) |
| `web/packages/app/src/styles/app.css` | edit | `.sidebar-scroll` (quadratic mask + gating), `.chat-row-status .dot` (6px), `.chat-row-jump` (new), `.arch-row` (gap 10px), `.arch-row-title`/`.arch-row-brand` hover/selected brighten, `.arch-row-item-selected` (new), `.sidebar-group*` (new), `.chat-row-resort`/`.chat-row-in` (new, FLIP), `.user-menu-sub` (line-height 15px, `--rb-text-muted`), delete the dead `.archived-chevron-open` 90° rule |

---

## 1. Context a fresh session needs

- **Vocabulary**: "chat", not "session"; "harness", not "provider"; "space",
  not "project" in code (the UI string is still "project" — see the string
  table below).
- The sidebar's render order (top to bottom) is: space-filter header (ticket
  10 owns its menus; this ticket owns only the trigger row) → scroll region
  (active chat list, grouped or flat, then the archived shelf) → connection
  pill (only during an outage) → sidebar notice (already matches, not touched
  here) → user menu, pinned to the bottom. `web/packages/app/src/components/sidebar-body.tsx`
  already mounts these in this order; it needs no structural change.
- The **outer** sidebar column — its animated width, the `.sidebar`/`.sidebar-inner`
  wrapper, the resize seam, `sidebar_tone`'s full-height background — lives in
  `app-shell.tsx` and is **ticket 06's** (Titlebar and main-column chrome).
  This ticket only owns what renders *inside* that column (the desktop's
  `render_chat_sidebar` and below). `render_sidebar`'s width constants
  (`SIDEBAR_MIN`/`MAX`/`DEFAULT` = 224/400/256) are listed below for context
  only — they are already correct on web (`state/layout.ts:27-29`, gap row
  P1, MATCHES).
- Data comes from `useWatchSnapshot(session)`: `snapshot.chats.rows`,
  `snapshot.spaces.rows`, `snapshot.statuses.rows`, `snapshot.devices.rows`.
  Row derivation for the active list lives in `lib/view.ts::chatListRows` /
  `toChatRow`; for the archived shelf, `lib/view.ts::archivedRows`.
- `useSidebar()` (`state/sidebar.ts`) reads `sidebarStore` (`lib/sidebar-store.ts`):
  currently `{ spaceFilter, lastSpaceId, archivedOpen }`. This ticket adds the
  five view-option fields (§2.1's table) to whatever store ticket 03 settles
  on (extend `sidebar-store.ts` if ticket 03 doesn't centralize it elsewhere).
- **PR-badge subscription (implementation note, not a blocker)**: §2.4's PR
  badge needs a per-chat PR summary. `state/change-requests-store.ts::changeRequestForChat(snapshots, target)`
  exists, but its `WatchCheckoutChangeRequest` subscriptions are currently
  only driven by the Changes pane (`state/changes-store.ts`). Ticket 22 owns
  `change-requests-store.ts`; check whether it already exposes (or can
  cheaply expose) a per-chat subscribe entry point for arbitrary callers —
  if not, this ticket may extend that store minimally (a small
  subscription-registration function), rather than duplicating the Changes
  pane's wiring or blocking on ticket 22.
- Status derivation (`effectiveIndicator`, `chatIndicator`, `displayStatus`,
  `attentionRank`, `timeAgo`) already lives in `lib/view.ts` and **already
  matches** the desktop 1:1 (research 16 §3.6, gap rows N20–N24) — do not
  touch those functions except to reuse them.
- Motion tokens this ticket needs (assumed added by ticket 02 — if any are
  missing, add them there, not here): a 260ms `cubic-bezier(0.22, 1, 0.36, 1)`
  spec (`RESORT`/`EASE_RESORT`), a 180ms ease-out spec (`COLLAPSE`/`EASE_OUT`
  = `cubic-bezier(0, 0, 0.58, 1)`), a 150ms fade-in spec (`fade_quick`), and
  reuse of the existing `--rb-motion-fade-in`/`--rb-ease-ease-out-expo` for
  the connection pill (already correct).
- `prefers-reduced-motion: reduce` snaps every duration in this ticket to 0
  (no FLIP translate, no disclosure height tween, chips appear/disappear
  instantly) — same rule the rest of the app follows.

---

## 2. Spec

### 2.1 The sort/organize/show settings (data model)

Not a component — the persisted preferences everything below reads. From
`settings.rs:507-544`, defaults `:658-665`.

| Setting | Type | Values | Default | Persisted key |
| --- | --- | --- | --- | --- |
| Grouping | `SidebarOrganization` | `ByDevice`, `InOneList` (a legacy `ByProject` is migrated to `InOneList` on load and is not reachable from the UI) | `InOneList` | `sidebarOrganization` |
| Sort | `SidebarSort` | `LastUpdated`, `Created` | `LastUpdated` | `sidebarSort` |
| Show harness mark | `bool` | — | `true` | `sidebarShowHarness` |
| Show branch line | `bool` | — | `true` | `sidebarShowBranch` |
| Show PR badge | `bool` | — | `true` | `sidebarShowPullRequest` |

**Effects** (copied from research 16 §3.1's table):

| Option | Effect |
| --- | --- |
| `sidebarOrganization === "byDevice"` | Chats are bucketed by `chat.deviceId`; each bucket renders as a collapsible disclosure section (§2.5); the LOCAL device's bucket is always promoted to the top (`promoteLocalDeviceGroup`, §3) without disturbing the recency order of the remaining (remote) buckets |
| `sidebarOrganization === "inOneList"` | No bucketing — a flat list, no headers |
| `sidebarSort === "lastUpdated"` | Primary sort key `lastMessageAt ?? createdAt`, **descending** (newest first) |
| `sidebarSort === "created"` | Primary sort key `createdAt` alone, descending |
| (tie-break, always) | ascending chat id, so the sort is total and stable |
| `sidebarShowBranch === false` | Every row's `branch` is cleared before layout — line 3 collapses entirely if there's also no PR badge |
| `sidebarShowPullRequest === false` | Every row's `changeRequest` is cleared before layout |
| `sidebarShowHarness === false` | The harness brand mark is omitted from both active and archived rows |

**Applies to three lists with the SAME comparator** (`compareSidebarChats`):
the active list, the keyboard/jump order (`sidebarVisibleOrder`, consumed by
ticket 12), and the archived shelf. This supersedes the engine-side
`sort_active`/`sortRows` (pure recency) that `chatListRows` currently applies
— the sidebar re-sorts with the user's preference on top of that.

This ticket does **not** build the menu that writes these settings (ticket 10
builds `render_sidebar_view_menu`, spec'd in `05-pickers-popovers.md` §3.21)
— it only reads them. Until ticket 10 lands, default every field to its
desktop default so behavior is correct with no UI to change it yet.

---

### 2.2 `render_chat_sidebar` — the scroll region and edge fade

`shell.rs:4859-5028`. Web: `sidebar-body.tsx` (mount order, no change) +
`chat-list.tsx` + `.sidebar-scroll`/`.sidebar-list` in `app.css`.

**Layout**

| Property | Value | Source |
| --- | --- | --- |
| scroll region wrapper | `relative; flex-1; min-h:0`, wrapped in the edge fade | `shell.rs:4947-4955` |
| fade band | `SIDEBAR_GLASS_FADE_BAND = 24px`, both top and bottom | `shell.rs:668` |
| inner scroll (`#sidebar-lists`) | `size-full; overflow-y:scroll; padding-x Theme::SPACE_SM (8); flex column; padding-top 4` | `shell.rs:4962-4972` |
| list item gap | `SIDEBAR_LIST_GAP = 2px` | `shell.rs:4974-4978` |
| **no "Sessions" header** | deliberate | `shell.rs:4956-4993` |
| after the list | `render_archived_section` | `shell.rs:4989` |

**The edge fade must be a quadratic ramp, gated per-edge from the live scroll
offset with a 1px dead-zone** (`crates/ui/src/edge_fade.rs:155-203`, no
constants — every value is an argument):

```
// gating (per edge)
top_active    = scrollTop > 1.0
bottom_active = scrollTop < (scrollHeight - clientHeight - 1.0)

// ramp math (per edge, band = 24)
ramp  = clamp(distance_from_edge / band, 0, 1)
alpha = ramp * ramp        // SQUARED — not linear, not smoothstep
```

CSS mapping — a `mask-image` with **quadratic** stops (not the linear ramp
the web currently ships):

```css
mask-image: linear-gradient(to bottom,
  rgba(0,0,0,0)      0px,
  rgba(0,0,0,0.0625) calc(24px * 0.25),   /* 0.25² */
  rgba(0,0,0,0.25)   calc(24px * 0.5),    /* 0.5²  */
  rgba(0,0,0,0.5625) calc(24px * 0.75),   /* 0.75² */
  rgba(0,0,0,1)       24px,
  rgba(0,0,0,1)       calc(100% - 24px),
  rgba(0,0,0,0.5625)  calc(100% - 24px * 0.75),
  rgba(0,0,0,0.25)    calc(100% - 24px * 0.5),
  rgba(0,0,0,0.0625)  calc(100% - 24px * 0.25),
  rgba(0,0,0,0)        100%);
```

Toggle each edge's stops on/off (or swap to an un-faded full-opacity mask) via
a scroll listener implementing the `top_active`/`bottom_active` gate above —
this needs either a scroll-position `data-` attribute driving two separate
CSS custom properties, or a small JS-computed inline `mask-image`. **Current
web bug this fixes**: the mask is always on, so the very first row is
permanently dimmed even when there is nothing to scroll past above it.

**Empty state (verbatim)**: `"No sessions yet"` (no period) — but this
project's vocabulary decision keeps "chats" — see gap S62; the copy fix is
"align geometry, keep the vocabulary". Geometry: `padding-x 8; padding-bottom
8; font-size ui_rems(12.0); colour theme.text_faint` (`shell.rs:4981-4987`).

---

### 2.3 Header row trigger chrome (space filter + sort button)

`spaces.rs:978-1183`. Web: `space-filter.tsx`. **This ticket only fixes the
trigger buttons' own geometry — it does not open, build, or wire either
menu** (`render_spaces_menu`/`render_sidebar_view_menu` are ticket 10's;
`05-pickers-popovers.md` §3.19-§3.21).

**Container**: `flex-none; flex row; items-center; gap 4px; padding-x
SPACE_SM(8); padding-top 8; padding-bottom 4`.

**Filter trigger** (`id "spaces-filter"`)

| Property | Value | Source |
| --- | --- | --- |
| flex | `flex-1; min-w:0` | `spaces.rs:1003-1004` |
| height | **29px** | `spaces.rs:1005` |
| gap | `SPACE_SM (8)` | `spaces.rs:1009` |
| radius | `8px` | `spaces.rs:1010` |
| padding-inline | `SPACE_SM (8)` | `spaces.rs:1011` |
| font | `ui_rems(13.0)`, weight `MEDIUM` | `spaces.rs:1012-1013` |
| color | hover-blended `text.opacity(0.8) → text` | `spaces.rs:1014-1018` |
| background | open: `glass_hover()`. closed: hover-blended `glass_hover().opacity(0) → glass_hover()` | `spaces.rs:1019-1027` |
| leading icon | `FOLDER`, 16px, `flex-none`, `text_muted` | `spaces.rs:1044-1047` |
| label | `min-w:0; truncate` | `spaces.rs:1052-1060` |
| **"@ device" tag** | `flex-none; ui_rems(10.0); NORMAL weight; text_muted.opacity(0.45)` | `spaces.rs:1063-1068`, opacity at `:1067` |
| offline glyph | `WIFI_OFF`, 12px, `flex-none`, `warning.opacity(0.8)` | `spaces.rs:1073-1076` |
| caret | `ALT_ARROW_DOWN`, 14px, `flex-none`, `text_muted.opacity(0.6)` | `spaces.rs:1082-1085` |

**Sort/view-options button** (`id "sidebar-view-options"`)

| Property | Value | Source |
| --- | --- | --- |
| size | **`29 × 29px`** square (matches the trigger row's 29px height beside it) | `spaces.rs:1107` |
| radius | `8px` | `spaces.rs:1112` |
| border | `1px solid border.opacity(0.0)` → `border_strong` on `:focus-visible` | `spaces.rs:1113-1115` |
| background | open: `glass_hover()`; closed: `glass_hover().opacity(0.0)`; hover: `glass_hover()` — plain hover, no fade blend | `spaces.rs:1118-1123` |
| color | `text_muted` | `spaces.rs:1117` |
| icon | `SORT`, 16px, `text_muted` | `spaces.rs:1152-1155` |
| a11y | `role=button`, `aria-label "Sidebar view options"`, `aria-expanded` | `spaces.rs:1103-1105` |
| tooltip | card: `px 8; py 6; radius 6; border 1px border_strong; bg surface_raised; shadow_md; ui_rems(11.0); text`, text `"Sidebar view options"`, **350ms show delay** | `spaces.rs:63-80, 1150-1151` |

**Current web values to fix**: `.space-filter-trigger` has `padding: 4px 6px`
with no explicit height (`app.css:820-835`) — set `height: 29px`.
`.space-filter-sort` is `24 × 24px` (`app.css:849-864`) — make it `29 × 29px`.
Neither trigger currently shows an "@ device" tag or offline glyph at all
(the web's `SpaceFilter` only ever shows the plain label) — add both, at
`opacity: 0.45`/`0.8` respectively, matching the table above. Leave
`.space-filter-sort`'s `onClick` absent/inert — wiring it is ticket 10's gap
S54.

---

### 2.4 `render_chat_row` — the active chat card

`shell.rs:4435-4799`. Web: `chat-list.tsx::ChatListRow`. **Three visual
lines; line 3 is structural, not reserved whitespace.**

**Row layout**

| Property | Value | Source |
| --- | --- | --- |
| id | `"chat-{id}"` | 4663 |
| display | `flex column; gap 2` | 4664-4666 |
| radius | `8` | 4667 |
| padding | `padding-x SPACE_SM (8); padding-y 6` | 4668-4669 |
| color | `hover_blend(fade_key, rest_text, theme.text)` | 4670 |
| background | `hover_blend(fade_key, rest_bg, hover_bg)` | 4671 |
| selection ring | **none** — the wash alone marks the active row | 4672-4673 |
| cursor | pointer | 4693 |

```
rest_bg   = selected ? glass_selected_bg()      : wash(0.0)
hover_bg  = selected ? glass_selected_bg()      : theme.glass_hover()
rest_text = selected ? theme.text               : theme.text.opacity(0.8)
fade_key  = "chat-row-{id}"
subline   = theme.text_muted.opacity(0.5)
```

A **selected row must not drift toward the hover wash** — selected rows use
the same color/background for rest and hover (gap S8, already matches on
web via `.chat-row-active:hover` restating the selected background).

**Children (in order)**

1. **Line 1**: `w-full; flex row; items-center; gap SPACE_SM (8)`.
   - Space label — `flex-1; min-w:0; truncate; font-size ui_rems(11.0);
     line-height 14px; colour subline`. Text `"{project} @ {device}"`, or
     just `"{project}"` when the device is unknown; project falls back to
     `"~"` when the chat has no space, `"?"` when its `spaceId` names a
     missing space.
   - **Corner** — see §2.4.1 below.
2. **Line 2**: `w-full; flex row; items-center; gap SIDEBAR_ACTIVE_HARNESS_TITLE_GAP
   (= SPACE_SM = 8)`.
   - Harness brand mark (only when present and `sidebarShowHarness`):
     `size SIDEBAR_ACTIVE_HARNESS_ICON_SIZE (13); flex-none; colour = (brand
     tint ?? subline).opacity(0.8)`.
   - Title — `flex-1; min-w:0; truncate; font-size ui_rems(13.0); line-height
     17px`; colour inherits the row's blended text colour.
3. **Line 3** — only when `shows_metadata = branch !== null || changeRequest
   !== null` (after `sidebarShowBranch`/`sidebarShowPullRequest` have cleared
   the fields per §2.1): `w-full; flex row; items-center; gap 4`.
   - Branch (when present): `GIT_BRANCH` icon, `size 11; flex-none; colour
     subline`; then the branch name — `min-w:0; truncate; font-size
     ui_rems(11.0); line-height 14px; colour subline`.
   - `<div flex-1 min-w-0 />` — an invisible spring pinning the PR badge
     right without moving anything when there's no badge.
   - PR badge (when present, `sidebarShowPullRequest`): the desktop's
     `pull_request_badge(id, summary, Sidebar, theme)` — on web this is
     `<ChangeRequestBadge summary={row.changeRequest} size="sidebar" />`
     (`components/change-request-badge.tsx` — **already built** with a
     `size="sidebar"` preset: height 16px, gap 0, padding-x 4px, radius 4px,
     text 10px, no icon, tone by state — nothing to build there, just wire
     it in).

**Harness geometry note**: `render_chat_row` ALWAYS uses the active pair
(13px icon / 8px gap) regardless of the `archived` flag it's called with —
the archived shelf's larger 14px/10px pair belongs to a **different**
renderer (§2.6), never this one.

#### 2.4.1 The status corner

Three mutually exclusive bodies, in priority order:

**(a) Jump hint** — when a `jumpLabel: string | null` prop is `Some` (passed
down by the shell while the jump modifier is held; **computing that state
and the modifier-detection is ticket 12** — this ticket only renders the
chip given a label). Takes the corner outright, above hover and the status
word:

| Property | Value | Source |
| --- | --- | --- |
| height | `16px`, `flex-none` | 4507-4508 |
| display | `flex row; items-center` | 4509-4511 |
| padding-x | `4px` | 4512 |
| radius | `4px` | 4513 |
| background | `theme.text_muted.opacity(0.08)` | 4514 |
| font | `ui_rems(10.0)`, weight `MEDIUM`, `font_mono` | 4515-4518 |
| color | `theme.text_muted.opacity(0.85)` | 4517 |
| text | `badge_combo(combo)` — macOS `"⌘1"`.."⌘9"`, elsewhere `"Ctrl+1"`.."Ctrl+9"` (ticket 12 supplies the string; this ticket just prints whatever it's given) | `settings.rs:1108-1133` |

New CSS class: `.chat-row-jump` (do not reuse `.identity-badge` — ticket 06
removes that class per gap T13; build this fresh).

**(b) Archive pill** — when the ROW (not the corner) is hovered and there's
no jump hint:

| Property | Value | Source |
| --- | --- | --- |
| display | `flex row; items-center; gap 4` | 4523-4527 |
| height | `18px` | 4528 |
| padding-x | `4px`, `margin-right -4px` (bleeds into the row's own padding so the text right-aligns exactly where the status word sits) | 4535-4536 |
| radius | `5px` | 4537 |
| background | `wash(0.10)`; hover `wash(0.18)` | 4538-4539 |
| icon | `ARCHIVE_UP_MINIMALISTIC` when archived, else `ARCHIVE_MINIMALISTIC`; 11px, `flex-none`, `text_muted` | 4541-4549 |
| label | `ui_rems(10.0)`, `text_muted`; `"Unarchive"` when archived, else `"Archive"` | 4550-4559 |

Already matches on web (gap S16). **Fix needed**: make it a real
`<button type="button" aria-label="…">`, not a `<span role="presentation">`
(gap S17 — keyboard reachability).

**(c) Status / time** — the default:

| Status | Glyph | Label | Colour |
| --- | --- | --- | --- |
| undelivered send | 6px dot | `"Failed"` | `theme.danger` |
| queued send (not undelivered) | 6px dot | `"Queued"` | `theme.warning` |
| `Working` | `mini_glyph_spinner`, speed 2.0, `theme.glyph` | `"Working"` | `status_dot_color` (§3) |
| `AwaitingInput` | 6px dot | `"Input"` | ditto |
| `Errored` | 6px dot | `"Failed"` | ditto |
| `Completed` | `CHECK` icon, 11px, `flex-none` | `"Done"` | ditto |
| `Idle` | none | none — shows `timeAgo` instead | inherited `subline` |

Layout: `flex row; items-center; gap 4`; label `font-size ui_rems(10.0);
weight MEDIUM; colour = status_color`. Idle time-ago: same font/weight, no
explicit colour (inherits `subline`).

**Web note**: the "send undelivered"/"send queued" rows above depend on
optimistic-send state that is ticket 05's (nav history / send ids /
optimistic echo) — if that state isn't wired by the time this ticket lands,
those two rows degrade to whatever `status` the live chat reports; do not
invent a substitute.

**Corner wrapper**: `id "chat-corner-{id}"; flex-none; height 14px; flex;
items-center; cursor pointer`. Pinned to line 1's 14px text height so the
taller archive pill overflows vertically rather than growing the row. A
click handler only while hovered: `stopPropagation` + toggle archived
(already correct on web via `toggleArchive`).

**Row interactions**

| Interaction | Effect | Source |
| --- | --- | --- |
| hover (whole row) | drives both the color/background blend and reveals the archive pill | 4677-4692 |
| click | navigate to the chat, focus composer | 4694-4696 |
| right mouse-down | opens the chat context menu at the pointer (ticket 10's `chat-menu.tsx` — already exists, just needs a `contextmenu` handler instead of/alongside the kebab; gap S25/S26) | 4697-4707 |
| click on corner while hovered | `stopPropagation` + toggle archived | 4634-4639 |

**`chat_row_height` — the FLIP height estimate** (`shell.rs:666-679`), needed
by §2.7's resort glide:

```
metadata_height = 0
if shows_branch:       metadata_height = max(metadata_height, 14)
if shows_pull_request: metadata_height = max(metadata_height, 16)
height = metadata_height == 0 ? 45 : 47 + metadata_height
```

So: **45** compact, **61** branch-only, **63** with a PR badge (with or
without a branch).

**Data**: the sidebar must be able to read a PR summary per visible chat row
via `state/change-requests-store.ts::changeRequestForChat(snapshots, target)`,
target = `{deviceId: chat.deviceId, cwd: chat.sourceContext?.cwd, branch:
chat.sourceContext?.branch, checkoutId: chat.sourceContext?.checkoutId}`.
See §1 for the subscription-wiring note (ticket 22 owns that store; this
ticket may extend it minimally rather than blocking on it).

---

### 2.5 Device/project disclosure groups

**New** — no web equivalent exists today. `spaces.rs:1362-1573` (assembly),
`spaces.rs:153-172` (header), `spaces.rs:549-622` (body + chevron),
`spaces.rs:531-547` (motion begin). Shared component with the archived shelf
(§2.6) — same header/body, different label/click target.

**Per-row derivation**, before any settings filtering (`spaces.rs:1369-1420`):

| Field | Rule |
| --- | --- |
| `project` | The chat's space's display name; `"~"` if the chat has no space; `"?"` if `spaceId` names a missing space |
| `device` | The device's name, or `"Unknown device"` |
| `folder` (line 1 text) | `project` alone when the device is unknown; `"{project} @ {device}"` otherwise |
| `branch` | `chat.sourceContext?.branch`, trimmed, empty → `null`. **Not** the chat's bare `branch: string \| null` scalar (see §4, gap #5) |
| `changeRequest` | the chat's current PR summary, if any (§2.4's Data note) |
| `group` | `(deviceId, deviceName)` under `ByDevice`; `null` otherwise |

Then, in order:
1. `sidebarShowBranch`/`sidebarShowPullRequest`/`sidebarShowHarness` clear
   the corresponding fields (§2.1).
2. Rows are bucketed into groups, preserving first-seen order.
3. Under `ByDevice`, `promoteLocalDeviceGroup` (§3) reorders buckets so the
   local device's bucket is first.
4. A flat slot counter increments per CHAT ROW (not per header) across every
   group — this feeds the jump-hint chip's slot number (ticket 12) and is
   exactly `sidebarVisibleOrder` (§3).
5. Rows with `group === null` are emitted directly, no header. Rows with a
   group are wrapped in a disclosure section (§2.5.1) keyed
   `"g:{organization}:{deviceId}"`, height `SIDEBAR_DISCLOSURE_SECTION_HEIGHT
   (40) + bodyHeight` (or `40` collapsed).

**Group collapse state**: a `Set<string>` of collapse keys, keyed
`"device:{deviceId}"` — **in-memory only, never persisted**; a page
reload/app restart always re-expands every group. Do not add this to
`sidebar-store.ts`'s persisted fields.

#### 2.5.1 `SidebarDisclosureHeader` — shared group/archived header

**Layout**

| Property | Value | Source |
| --- | --- | --- |
| display | `flex row; items-center` | `:155-157` |
| gap | `8px` | `:158` |
| height | `SIDEBAR_DISCLOSURE_HEADER_HEIGHT = 28px` | `:159, :123` |
| padding-x | `SPACE_SM (8)` | `:160` |
| cursor | pointer | `:161` |

**Children (in order)**: label (`flex-none; font-size ui_rems(12.0); weight
MEDIUM; colour text_muted.opacity(0.5)`) → a hairline rule (`flex-1; h 1px;
bg border.opacity(0.6)`) → the chevron (§2.5.2).

**Text**: device group label is the device's display name; collapsed becomes
`"{label} ({row_count})"` — expanded, plain label. The archived shelf follows
the same rule inverted only in starting string: `"Archived"` open,
`"Archived ({total})"` collapsed (already correct on web).

**Section spacing**: each group section (and the archived section) is
preceded by `SIDEBAR_SECTION_GAP = 12px` of top padding — "well over 2× the
intra-list 2px row gap" so groups read as distinct bands. The disclosure body
gets its own `SIDEBAR_DISCLOSURE_BODY_INSET = 4px` top padding before the
first row, open or mid-collapse.

#### 2.5.2 Disclosure open/close motion

Shared by device groups and the archived shelf.

**On every header click, before the state flip** (`begin_sidebar_disclosure_motion`):

```
from  = animating(previous) ? previous.current() : resting_height
to    = was_open ? 0.0 : full_body_height
epoch = previous.map_or(1, |m| m.epoch + 1)
```

Capturing the in-flight value (not the resting height) is what makes a rapid
double-click reverse smoothly instead of snapping.

**`SidebarDisclosureBody`**

| Property | Value | Source |
| --- | --- | --- |
| base frame | `w-full; flex-none; overflow-hidden` | `:557` |
| height (no active tween) | `open ? fullBodyHeight : 0`, set directly | `:563-565` |
| height (active tween) | `lerp(from, to, t)` over the motion below | `:566-579` |
| opacity (during tween) | `0.35 + 0.65 * reveal`, `reveal = clamp(height / fullBodyHeight, 0, 1)` — never pops fully opaque/transparent mid-slide | `:573-575` |
| paint offset (during tween) | `top: -3px * (1 - reveal)` — a 3px upward creep as it opens, settling to 0 at full reveal | `:576-577` |

**Motion**: `COLLAPSE` = **180ms**, `EASE_OUT` = `cubic-bezier(0, 0, 0.58, 1)`.
Height and opacity ride the same progress curve. `SidebarDisclosureMotion::animating()`
stays true for `COLLAPSE.total() + 120ms` after it starts (a grace period
absorbing render-thread lag). **Reduced motion**: duration → 0, snap directly
to the target height/opacity.

#### 2.5.3 `SidebarDisclosureChevron`

| Property | Value | Source |
| --- | --- | --- |
| icon | `ALT_ARROW_RIGHT` | `:585` |
| size | `12px` | `:586, :599, :614` |
| colour | `text_muted.opacity(0.5)` | `:587` |
| rest rotation | `open ? 90° : 0°` — points right collapsed, down open | `:584, :616-618` |
| animated rotation | rides the same `COLLAPSE` tween/epoch as the body, `lerp(from, to, t) * 90°` | `:594-609` |

---

### 2.6 `render_archived_section` — the archived shelf

`spaces.rs:1582-1821`. Web: `archived-section.tsx`. Filters `chats` to
`archived === true`, further filtered by the space filter, sorted by
`compareSidebarChats` using the user's `sidebarSort` (§3 — **not** its own
fixed recency sort as today). Renders nothing when the filtered set is empty
— no "Archived (0)" ever shows.

**Paging**: `INITIAL = 10`, `PAGE = 25` (already correct on web). Clicking
the header resets `archivedShown` back to `INITIAL` on every toggle —
reopening never remembers a previous "show more" expansion (already correct).

**Header**: `SidebarDisclosureHeader` (§2.5.1) with id `"archived-toggle"`.

**Body height formula**:
```
bodyHeight = SIDEBAR_DISCLOSURE_BODY_INSET (4)
           + visibleCount * 36
           + max(visibleCount - 1, 0) * SIDEBAR_LIST_GAP (2)
           + (hasMore ? 36 + SIDEBAR_LIST_GAP : 0)
```

**Archived row** — deliberately NOT `render_chat_row`; a slimmer dedicated
one-liner:

| Property | Value | Source |
| --- | --- | --- |
| height | `36px` fixed | `:1719` |
| display | `flex row; items-center` | `:1720-1722` |
| gap | `SIDEBAR_ARCHIVED_HARNESS_TITLE_GAP = 10px` (vs the active row's 8px — deliberately looser: a one-line shelf can carry a bigger mark with more air) | `:1723`, `shell.rs:688` |
| padding-x | `SPACE_SM (8)` | `:1724` |
| radius | `6px` | `:1725` |
| background | selected → `glass_selected_bg()`; hover → `glass_hover()` | `:1727-1728` |
| cursor | pointer | `:1726` |

**Children (in order)**

1. Harness brand mark (when `sidebarShowHarness` and present): `size
   SIDEBAR_ARCHIVED_HARNESS_ICON_SIZE (14px)` — vs the active row's 13px.
   Colour: `tint ?? text_muted` at FULL strength when `hovered || selected`,
   else the same colour at **opacity 0.4** ("dimmed-until-touched").
2. Title — `flex-1; min-w:0; truncate; font-size ui_rems(13.0)`. Colour
   `text` at `hovered || selected`, else `text.opacity(0.55)` — same dimming
   rule as the brand mark.
3. Right slot (time ↔ Unarchive swap):

| State | Content |
| --- | --- |
| not hovered | `font-size ui_rems(11.0); colour text_muted.opacity(0.55)`, text `timeAgo` |
| hovered | An 18px pill matching the active row's Archive pill EXACTLY: `h 18px; gap 4px; px 4px; margin-right -4px; radius 5px; bg wash(0.10)`, hover `wash(0.18)`; icon `ARCHIVE_UP_MINIMALISTIC` 11px `text_muted`; label `ui_rems(10.0)` `text_muted`, text `"Unarchive"` |

**Row interactions**: hover tracked per-row; click → open the chat; right
mouse-down → the SAME chat context menu active rows use, at the pointer
(currently missing on web — gap #12 in research 16).

**"Show N more" row** (only when `hasMore`):

| Property | Value |
| --- | --- |
| margin-top | `2px` |
| height | `36px` |
| gap | `10px` |
| padding-x | `SPACE_SM (8)` |
| radius | `6px` |
| colour | `text_muted.opacity(0.55)`; hover → `bg glass_hover(); colour text` |
| icon | `PLUS`, 14px |
| text | `"Show {remaining} more"`, `remaining = min(total - shown, PAGE)` |

Click adds `PAGE (25)` to `archivedShown` (clamped up from `INITIAL` first).

**Web fixes needed here**: harness gap 8px→10px (`.arch-row { gap:
var(--rb-space-sm) }` → `10px`); add hover/selected brighten to
`.arch-row-title`/`.arch-row-brand` (currently static `text-muted`); add a
selected-row background class; wire the sort to `sidebarSort`; add the
right-click context menu.

---

### 2.7 The resort glide (FLIP)

`shell.rs:4859-4922`, pure helper at `624-658`. **New — nothing like this
exists on web today.** This is the single most visible sidebar motion the
web is missing (gap S59).

Every render, the row-assembly step (§2.5, item 4) produces
`(key, estimatedHeight, element)[]` for every visible row (both flat rows and
group/archived sections, using `format!("c:{chatId}")`/`format!("g:{org}:{deviceId}")`/
`"archived"` as keys). Diff this list against the previous render's list:

| Step | Rule |
| --- | --- |
| 1 | If the list is unchanged, do nothing |
| 2 | `sidebarKeyOrderChanged(old, new)` — true iff lengths differ or any key at the same index differs. A pure height change (a disclosure animating open) is NOT a reorder — applying FLIP too would double-count the motion |
| 3 | If the order changed: `offsets = resortOffsets(old, new, gap=SIDEBAR_LIST_GAP=2)` (§3) |
| 4 | `newKeys` = keys present now but not before |
| 5 | If `offsets` or `newKeys` is non-empty, bump a `resortEpoch` and store both |
| 6 | First fill (previous order empty) never animates |

**Rendering**:
- key has an offset `dy` (`|dy| > 0.5`) → animate a **paint-only** relative
  inset from `dy` to `0` over **RESORT = 260ms, cubic-bezier(0.22, 1, 0.36, 1)**
  (`EASE_RESORT`/`EASE_OUT_QUINT`) — layout is already at the new position;
  only the paint offset tweens. Element/animation id: `"resort-{epoch}-{key}"`.
- key is new → 150ms opacity fade-in (`fade_quick`), id `"row-in-{epoch}-{key}"`.
- otherwise → bare element, no animation.
- removals just go — no exit animation.

On web: implement as a small hook (e.g. `useSidebarResort(rows)`) that keeps
the previous `{key, top}` map in a ref, computes offsets on each render via
`resortOffsets`, and applies `transform: translateY({dy}px)` transitioning to
`translateY(0)` over the RESORT spec via either a CSS transition triggered
next frame or the Web Animations API — a CSS `transition` on `transform` set
up, then cleared to `0` on the next `requestAnimationFrame`, reproduces the
"paint-only, already at final layout position" trick.

**Reduced motion**: skip the translate/opacity tween entirely; rows land at
their final position/opacity immediately.

---

### 2.8 Jump-hint chip (display only)

See §2.4.1(a) for the corner chip's full geometry. **This ticket only
renders it** given a `jumpLabel: string | null` prop per row. It does NOT:
- detect the jump modifier (`platform`/`ctrl`) being held,
- compute `jumpHintsVisible` (a modifier-triple match against the keymap),
- map a slot index (1-9, from `sidebarVisibleOrder`) to a `badge_combo`
  string,
- clear hints on window blur,
- bind `⌘1`..`⌘9`/session cycling.

All of the above is ticket 12 (Keyboard). This ticket's job is: accept
whatever label ticket 12 passes down, and when it is non-null, replace the
corner's status/time/archive-pill content with the chip — nothing else
changes about the row. Build the component so ticket 12 can pass
`jumpLabel={null}` today with zero visible effect, and a real label later
with no further sidebar changes needed.

---

### 2.9 `render_connection_pill`

`shell.rs:4810-4857`. Web: `connection-pill.tsx`. Returns nothing while
healthy.

| `ConnectivityState` | Rendered? | Glyph | Label |
| --- | --- | --- | --- |
| `Disabled` | no | — | — |
| `Connected` | no | — | — |
| `Offline` | yes | 5px circle, `bg warning` | `"Offline — sends are saved"` |
| `Reconnecting` | yes | `mini_mono_spinner`, speed 2.0, `text_muted` | `"Reconnecting…"` |

**Layout**

| Property | Value |
| --- | --- |
| id | `"connection-pill"` |
| margin-x | `SPACE_SM + 4 = 12px` |
| margin-bottom | `SPACE_SM (8)` |
| display | `flex; items-center; gap 6` |
| **no surface, no border** — deliberate | |
| label | `min-w:0; truncate; font-size ui_rems(11.0); colour text_faint` |

**Motion**: 500ms fade-in (`opacity 0→1`, `top 4px→0`).

**Web status**: already matches structurally (gap S42). The web adds extra
states beyond `Offline`/`Reconnecting` (`"Connecting…"`, `"Engine changed"`,
`"Session revoked"`, `"Disconnected"`, `` `Attempt ${n}` ``) — **these are
legitimate web-transport reality and must stay** (gap S43); just make sure
the two shared states' strings match exactly and that a raw `Attempt N`
string never reaches user-visible copy. The desktop's `mono_mono_spinner`
maps to a CSS spinner (`.mono-spinner`) — verify its visual weight against
the desktop's (gap S44, needs the `loaders.rs` pass from ticket 20 — do not
block this ticket on that; leave the current 5×5-dot spinner if ticket 20
hasn't landed).

---

### 2.10 `render_user_menu` — the account row

`shell.rs:5169-5311`. Web: `account-row.tsx`.

**Trigger layout**

| Property | Value |
| --- | --- |
| id | `"user-menu"` |
| flex | `flex-none` |
| radius | `8px` |
| padding | `padding-x SPACE_SM (8); padding-y SPACE_SM (8)` |
| display | `flex row; items-center; gap 10px` |
| cursor | pointer |
| background (open) | `glass_hover()` |
| background (closed) | hover-blended `glass_hover().opacity(0) → glass_hover().opacity(0.8)` |

**Children (in order)**

1. **Avatar**: `28 × 28px`, `flex-none`, `border-radius: 50%`, background
   `theme.text` (a white circle in dark mode), centred, `ui_rems(12.0)`
   weight `SEMIBOLD`, colour `theme.bg`. Content: first character of the
   identity name, uppercased; `"?"` if empty.
2. **Identity stack**: `flex-1; min-w:0; flex column`.
   - Name — `font-size ui_rems(13.0); line-height 17px; weight MEDIUM;
     colour text; truncate`. Text: `"Roboco"` on the desktop; web keeps its
     existing behaviour of showing the connected device's name (this is a
     legitimate web-only substitution — the desktop has no per-device
     identity concept here — do not force the literal string `"Roboco"`).
   - Subline (only when present) — `ui_rems(11.0); line-height 15px; colour
     text_muted`. **Web fix**: `.user-menu-sub` currently uses
     `line-height: 14px` and `--rb-text-faint` — change to `15px` and
     `--rb-text-muted`.

**No chip on the right.**

**Menu** — opens **upward**, 6px gap above the trigger (web currently
overlaps by 4px via `bottom: calc(100% - var(--rb-space-xs))` — change to a
true 6px gap):

| Property | Value |
| --- | --- |
| width | `sidebarWidth - 2*SPACE_SM` (exactly the trigger row's width) — web's `left/right: var(--rb-space-sm)` is visually equivalent, no change needed |
| entrance | 140ms, `opacity 0.3→1`, `top -2px→0` |
| exit | 100ms, reverse |
| dismiss | outside mouse-down |

**Menu children — exactly two, in order** (`shell.rs:5280-5302`):

1. Identity line — `padding-x 8; padding-top 6; padding-bottom 4; font-size
   ui_rems(11.0); colour text_muted.opacity(0.7); truncate`. Text:
   `"Stored on this device"`. **Missing on web today — add it.**
2. One row: `menu_row` with `SETTINGS_MINIMALISTIC` icon (16px, `text_muted`)
   and label `"Settings"`. Click → open Settings **landing on the Devices
   section** (`SettingsSection::Devices`).

**Web fixes needed**: `account-row.tsx` currently renders THREE rows
(`"Engines"`, `"Appearance"`, a separator, then `"Settings"`) with no
identity line. Per gap S48:
- **Add** the muted `"Stored on this device"` identity line.
- **Remove** the `"Appearance"` shortcut row and its separator — invented,
  no desktop analogue (ticket 04's deletion pass owns removing INVENTED
  items generally, but since this ticket is already rebuilding this
  component's contents, do it here rather than leaving a half-fixed menu).
- **Keep** the `"Engines"` row — the web's device-pairing entry point has no
  desktop analogue to fall back to (open question D1 in research 01 — the
  web needs *some* way to reach pairing, and the user menu is where the
  desktop puts its one settings entry point). Route `"Settings"` to Devices,
  not the settings root.

---

## 3. Pure logic to port

### `compareSidebarChats(sort, left, right): number` — `spaces.rs:25-38`

```
primary = sort === "created"
  ? compareIso(right.createdAt, left.createdAt)                              // desc
  : compareIso(right.lastMessageAt ?? right.createdAt, left.lastMessageAt ?? left.createdAt) // desc
return primary !== 0 ? primary : (left.id < right.id ? -1 : left.id > right.id ? 1 : 0)      // asc tiebreak
```

Test to port: `equal_sidebar_timestamps_sort_by_stable_chat_id` (`:3528-3534`)
— two chats with identical `createdAt`/`lastMessageAt` sort `"alpha"` before
`"beta"` under BOTH sort modes (id tiebreak fires either way).

### `promoteLocalDeviceGroup<T>(groups, localDeviceId): T[]` — `spaces.rs:133-151`

Moves the group whose key matches `localDeviceId` to index 0, WITHOUT
touching the relative order of any other group. No-op if `localDeviceId` is
null, or no group matches it.

Tests to port:
- `current_device_is_promoted_without_resorting_remote_groups` (`:3537-3551`)
  — groups `[recent-remote, local, older-remote]` → promoting `"local"`
  yields `[local, recent-remote, older-remote]` (the two remote groups keep
  their relative order).
- `missing_current_device_leaves_group_order_untouched` (`:3553-3561`) —
  promoting an id that matches nothing leaves the list byte-for-byte equal.

### `sidebarVisibleOrder(cx): string[]` — `spaces.rs:1330-1357`

The flat, top-to-bottom chat-id list *exactly as drawn* — device grouping and
local-device promotion applied, group headers not counted as slots. Export
this from wherever §2.5's row assembly lives (`lib/view.ts` or a new
`lib/sidebar-groups.ts`) so ticket 12 can consume it for jump shortcuts and
session cycling without reimplementing the grouping logic. Keyboard order
must never drift from what is on screen — this function's output IS the
screen order.

### `resortOffsets(old, new, gap): Map<string, number>` — `shell.rs:624-647`

Lays out both lists as `y += height + gap`, and for each surviving key emits
`oldY - newY` when `|dy| > 0.5`.

### `sidebarKeyOrderChanged(old, new): boolean` — `shell.rs:652-658`

`old.length !== new.length || old.some((k, i) => k !== new[i])`.

Tests to port (§2.7's algorithm):
- `keys` helper (`8613`)
- `sidebar_chat_height_tracks_visible_metadata` (`8618`) — `chat_row_height`
  returns 45/61/63 per §2.4's table
- `sidebar_harness_geometry_reflects_row_hierarchy` (`8626-8631`) — asserts
  `SIDEBAR_ACTIVE_HARNESS_TITLE_GAP === 8`, `8 < 10` (archived gap),
  `13 < 14` (archived icon size)
- `sidebar_height_change_is_not_a_reorder` (`8633`) — a height-only change
  (a disclosure opening) does not trigger `sidebarKeyOrderChanged`
- `resort_offsets_empty_when_order_unchanged` (`8643`)
- `resort_offsets_activity_moves_row_to_top` (`8649`)
- `resort_offsets_respect_heights_and_gap` (`8663`)
- `resort_offsets_ignore_added_and_removed_keys` (`8674`)
- `resort_glide_spec_matches_original` (`8688`) — the 260ms/cubic-bezier spec
  itself
- `sidebar_disclosure_motion_lands_exactly_on_its_target` (`8791`) — §2.5.2's
  motion always reaches exactly `to`, never overshoots/undershoots due to
  epoch bumps mid-flight

### `formatTimeAgo` / `timeAgo` — `crates/proto/src/view.rs:221-247`

**Already correct on web** (`lib/view.ts:344`, gap N21 MATCHES) — including
the `"0y"` quirk on days 360-364 (`mo = 12` fails the `< 12` guard, `360/365`
integer-divides to `0`). This is a known bug present on **both** sides;
per the research, leave it as is (do not silently fix only the web side —
that would make the two diverge). No changes needed here beyond reusing it.

### `statusDotColor(status, theme)` — `spaces.rs:511-528`

| `ChatIndicator` | Colour | Note |
| --- | --- | --- |
| `Working` | `busy.opacity(0.55)` | sub-full-strength — "running is routine" |
| `AwaitingInput` | `accent.opacity(0.6)` | reads as "asking a question" |
| `Errored` | `danger.opacity(0.65)` | |
| `Completed` | `success.opacity(0.9)` | full-strength — "finished but unseen" keeps its pop |
| `Idle` | `ink(0.14)` | no live indicator; shows time-ago instead |

**Upstream derivation, already correct on web** (`lib/view.ts`, no changes):
`effectiveIndicator` (`SESSION_STALE_MS = 45_000`, gap N20), `displayStatus`,
`attentionRank` (gap N23), `chatIndicator`.

**Status dot palette** (gap N24, verify only): oklch triples `WORKING
(0.718, 0.202, 349.761)`, `AWAITING (0.673, 0.182, 276.935)`, `ERRORED
(0.704, 0.191, 22.216)`, `COMPLETED (0.765, 0.177, 163.223)` — confirm the
`--rb-activity`/`--rb-accent`/`--rb-danger`/`--rb-success` tokens (from
ticket 02) resolve to these; if not, that's ticket 02's bug, not this
ticket's.

---

## 4. Gaps this ticket closes

Copied verbatim from `01-shell-chrome.md` §5.3 (the full Sidebar table) and
`16-sidebar-body-add-space.md` §5. Rows explicitly owned by another ticket
are marked; this ticket still documents them here per the research's own
table so nothing is lost, but does not implement them.

### From `01-shell-chrome.md` §5.3

| # | Item | Kind | Desktop | Web | Fix | Owner |
| --- | --- | --- | --- | --- | --- | --- |
| S1 | Scroll-edge fade curve | WRONG VALUE | `edge_faded` squared ramp | `.sidebar-scroll` mask is linear | quadratic stops (§2.2) | **this ticket** |
| S2 | Scroll-edge fade gating | WRONG BEHAVIOUR | per-edge, live scroll offset, 1px dead-zone | mask always on | gate on scrollTop (§2.2) | **this ticket** |
| S3 | Sidebar tone spans full window height | WRONG VALUE | `sidebar_tone` painted behind everything, under the titlebar | `.sidebar` background + `.sidebar-inner` padding-top — close already | verify right hairline runs full height | ticket 06 |
| S4 | Sidebar wash value | MATCHES | `wash(0.05)` | `rgb(var(--rb-wash)/0.05)` | ✅ | — |
| S5 | Chat row structure | MATCHES | 3 lines | same | ✅ | — |
| S6 | Chat row line-1 colour | MATCHES | `text_muted.opacity(0.5)` | `color-mix` equivalent | ✅ | — |
| S7 | Chat row rest text | MATCHES | `text.opacity(0.8)`/`text` | equivalent | ✅ | — |
| S8 | Selected row hover | MATCHES | no drift toward hover wash | `.chat-row-active:hover` restates | ✅ | — |
| S9 | Change-request (PR) badge | MISSING | line 3, right-aligned after a spring, `chat_row_height` reserves 16px | `chat-list.tsx` renders only the branch | add (§2.4) | **this ticket** |
| S10 | Show-branch/PR/harness view options | MISSING | `render_active_rows` nulls fields per setting | no view options exist | belongs to ticket 10's menu, but the row must honour them (§2.1, §2.4) | **this ticket** (honouring), ticket 10 (menu) |
| S11 | Harness mark size/gap | MATCHES | `size 13`, gap 8 | `Icon size={13}`, `.chat-row-title-line{gap:var(--rb-space-sm)}` | ✅ | — |
| S12 | Harness mark colour | WRONG VALUE | `(tint ?? subline).opacity(0.8)` | `.chat-row-brand` mixes text-muted 40% then an inline tint override keeps opacity .8 | fallback should be `text_muted@50%` (subline), not 40% | **this ticket** |
| S13 | Status dot size | WRONG VALUE | 6px | `.dot{width:8px;height:8px}` | split a sidebar-scoped 6px class | **this ticket** |
| S14 | Status glyph sizes | MATCHES | `size 11`, spinner speed 2.0 | same | ✅ | — |
| S15 | Status word for `awaitingInput` | WRONG VALUE (internal inconsistency) | `"Input"` | `view.ts` says `"Input"` ✅ but `status-dot.tsx` says `"Waiting for input"` | make `status-dot.tsx` say `"Input"` | **this ticket** |
| S16 | Archive pill | MATCHES on values | height 18, px 4, mr -4, radius 5, wash(0.10)→wash(0.18), icon 11, label ui_rems(10) | `.chat-row-archive` reproduces it | ✅ | — |
| S17 | Archive pill not keyboard-reachable | WRONG BEHAVIOUR | click target on a stateful div | `<span role="presentation" onClick>` | make it a real `<button>` with aria-label | **this ticket** |
| S18 | Jump-hint chips | MISSING | replace corner on first nine rows while modifier held | nothing | display component here (§2.8); modifier detection/label mapping is ticket 12 | **split** |
| S19 | `⌘1..⌘9` jump shortcuts | MISSING | 9 bindings | none | ticket 12 | ticket 12 |
| S20 | `Ctrl+Tab`/`Ctrl+Shift+Tab` cycling | MISSING | `cycle_session` over `sidebarVisibleOrder` | none | ticket 12 (this ticket exports the order, §3) | ticket 12 |
| S21 | `Mod+Shift+A` archive | MISSING | `ArchiveSession` | none | ticket 12 | ticket 12 |
| S22 | `Mod+R` toggle right pane | MISSING | `ToggleChanges` | none | ticket 12 | ticket 12 |
| S23 | `Mod+J` toggle terminal | WRONG BEHAVIOUR | toggles the bottom dock | wired to the right-pane terminal | ticket 12 | ticket 12 |
| S24 | `Mod+K` add-space palette | MISSING | fixed binding | none | ticket 11/12 | ticket 11/12 |
| S25 | Row right-click context menu | WRONG BEHAVIOUR | right mouse-down opens at pointer | hover-revealed kebab only | add a `contextmenu` handler | **this ticket** |
| S26 | Kebab button | INVENTED | none exists | `.chat-row-kebab`, always visible on touch | keep as documented touch-only affordance, or remove — decide in this ticket's Comments | **this ticket** (decision) |
| S27–S41 | Chat menu width/icons/Copy submenu/geometry/dialogs/keyboard nav | various | see `05-pickers-popovers.md` §3.23 | `chat-menu.tsx` partial | ticket 10 | ticket 10 |
| S42 | Connection pill | MATCHES | bare glyph + caption, no surface | `.connection-pill` reproduces it | ✅ | — |
| S43 | Connection pill states | WRONG BEHAVIOUR (extra states legitimate) | only `Offline`/`Reconnecting` render | six states incl. web-transport-only ones | keep extras, match the two shared strings, hide raw `Attempt N` | **this ticket** |
| S44 | Mono spinner | WRONG VALUE | `mini_mono_spinner` | 5×5 dot CSS spinner | needs ticket 20's `loaders.rs` pass | ticket 20 |
| S45 | `sidebar-notice` | MATCHES | margin/padding/radius/border/font/colour | `.sidebar-notice` reproduces it | ✅ | — |
| S46 | Update strip | MISSING | sits between connection pill and user menu | absent | owned by ticket 06 (Titlebar and main-column chrome) | ticket 06 |
| S47 | User menu trigger | MATCHES (mostly) | 28px avatar, radius 8, padding 8, gap 10 | reproduces it | ✅ except S50 | — |
| S48 | User menu contents | WRONG BEHAVIOUR | identity line + one "Settings" row | three rows, no identity line | fix (§2.10) | **this ticket** |
| S49 | User menu width | WRONG VALUE (visually equivalent) | `sidebarWidth - 16` | `left/right: var(--rb-space-sm)` | ✅ equivalent | — |
| S50 | User menu opens upward | MATCHES (off by 2px) | 6px gap | 4px overlap | fix to 6px gap | **this ticket** |
| S51 | Settings-mode sidebar | MISSING | replaces the whole sidebar on Settings route | chat sidebar stays | owned by ticket 28 (Settings shell and Appearance) | ticket 28 |
| S52 | Settings sections | WRONG VALUE | ten sections | three routes | ticket 29 | ticket 29 |
| S53 | Space filter "All projects" | WRONG VALUE (unverified) | — | `"All projects"` | confirm (already correct per this ticket's read of `spaces.rs:1052-1060`'s label rule) | **this ticket** (confirm only) |
| S54 | `.space-filter-sort` inert | INVENTED/broken | opens `SidebarViewMenu` | 24×24 button, no `onClick` | ticket 10 wires the menu; this ticket only fixes the button's own size (§2.3) | **split** |
| S55 | Archived paging | MATCHES | `INITIAL=10, PAGE=25` | same | ✅ | — |
| S56 | Archived header label | MATCHES | `"Archived (N)"`/`"Archived"` | same | ✅ | — |
| S57 | Archived chevron rotation | WRONG VALUE (dead CSS) | — | `.archived-chevron-open` rotates 90° (dead, never applied); `.chevron-open` rotates 180° (live) | delete the dead rule, keep one | **this ticket** |
| S58 | Archived disclosure height tween | MISSING | interruptible 180ms ease-out | instant show/hide | add (§2.5.2) | **this ticket** |
| S59 | Sidebar resort glide (FLIP) | MISSING | 260ms cubic-bezier per-row translate | nothing | add (§2.7) | **this ticket** |
| S60 | Sidebar list gap | MATCHES | `SIDEBAR_LIST_GAP=2` | `.chat-list{gap:2px}` | ✅ | — |
| S61 | Sidebar scroll padding | MATCHES | padding-x 8, padding-top 4 | `.sidebar-list{padding:var(--rb-space-xs) var(--rb-space-sm)}` | ✅ | — |
| S62 | Empty list copy | WRONG VALUE (geometry) | `"No sessions yet"`, ui_rems(12.0), text_faint, padding-x 8/padding-bottom 8 | `"No chats yet."`/etc at `.sidebar-note{margin:12px;font-size:13px}` | keep "chats" vocabulary, align geometry | **this ticket** |
| S63 | Archived-row harness gap | WRONG VALUE | 10px | `.arch-row{gap:var(--rb-space-sm)}` = 8px | fix to 10px (§2.6) | **this ticket** |

### From `16-sidebar-body-add-space.md` §5

| # | Item | Kind | Desktop value | Web value | Fix | Owner |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | Sidebar organize/sort/show settings | MISSING | `SidebarOrganization`/`SidebarSort`/3 Show toggles, persisted | no such state; `chat-list.tsx` always flat recency | add to the settings store (§2.1) | **this ticket** (state), ticket 10 (menu) |
| 2 | Device grouping + disclosure sections | MISSING | `render_active_rows` buckets by device, local promoted first | no grouping concept | build (§2.5) | **this ticket** |
| 3 | Group/archived disclosure open/close animation | MISSING | 180ms ease-out, height+opacity+3px settle, chevron rotates over the same tween | archived chevron rotates over the wrong duration/curve token; no height animation at all | add (§2.5.2) | **this ticket** |
| 4 | Sort button inert | MISSING | opens Organize/Sort/Show, 7 rows | `.space-filter-sort` has no `onClick` | ticket 10 | ticket 10 |
| 5 | Branch field source | WRONG BEHAVIOUR | `chat.sourceContext.branch` (bare `branch` scalar rejected) | `view.ts::toChatRow` reads `chat.branch` (`view.ts:226`) | read `chat.sourceContext?.branch` instead | **this ticket** |
| 6 | Change-request badge on sidebar rows | MISSING | line 3, right-aligned | none | add (§2.4) | **this ticket** |
| 7 | Show-toggle per-row gating | MISSING | clearing a toggle blanks the field, collapsing line 3 if both gone | no toggles exist | depends on #1, #6 | **this ticket** |
| 8 | Archived shelf harness dim/undim | WRONG VALUE | full opacity on hover/selected, 0.4/0.55 at rest | `.arch-row-title`/`.arch-row-brand` never change with hover | add hover/selected variant (§2.6) | **this ticket** |
| 9 | Archived row harness gap | WRONG VALUE | 10px | `.arch-row{gap:var(--rb-space-sm)}` = 8px | fix (dup of S63) | **this ticket** |
| 10 | Archived shelf selection state | MISSING | selected row gets `glass_selected_bg()` | no "selected" concept at all | add (§2.6) | **this ticket** |
| 11 | Archived shelf sort follows preference | WRONG BEHAVIOUR | `compareSidebarChats(sidebarSort, ...)` | always pure recency | swap in the preference (§2.6, §3) | **this ticket** |
| 12 | Archived context menu (right-click) | MISSING | same chat context menu as active rows | no context menu at all | wire the handler (§2.6); menu component itself is ticket 10's | **split** |
| 13 | Add-space palette | MISSING (entire surface) | ⌘K-style modal | does not exist | ticket 11 | ticket 11 |
| 14 | Manual/typed path entry, slash-descend, tab-complete | MISSING | — | N/A | ticket 11 | ticket 11 |
| 15 | Space rename dialog copy | MATCHES (once built) | `"Rename project"`/`"Project name"` | N/A | ticket 10 | ticket 10 |
| 16 | Space delete confirm copy | MATCHES (once built) | exact singular/plural, curly quotes | N/A | ticket 10 | ticket 10 |
| 17 | Group/archived-header collapsed count suffix | MISSING (for groups) | `"{label} ({N})"` only collapsed | archived header already matches; device-group headers don't exist | depends on #2 | **this ticket** |
| 18 | `sidebarVisibleOrder` (keyboard/jump order) | MISSING | grouped, sorted flat order | no jump-hint/slot system at all | this ticket exports the function (§3); ticket 12 consumes it | **split** |

---

## 5. Do not

- Do not build `render_spaces_menu` or `render_sidebar_view_menu` (the
  dropdowns the space-filter/sort triggers open) — that is ticket 10. This
  ticket only fixes the two trigger buttons' own geometry (§2.3) and adds the
  settings fields those menus will eventually write (§2.1).
- Do not build the chat context menu's rows/Copy submenu/dialogs (S27-S41,
  ticket 10's `chat-menu.tsx`) — only wire a `contextmenu` handler that opens
  whatever menu component already exists at the pointer position.
- Do not implement `⌘1`-`⌘9`, `Ctrl+Tab`/`Ctrl+Shift+Tab` cycling, `Mod+Shift+A`,
  `Mod+R`, `Mod+J`, or `Mod+K` (S19-S24) — ticket 12. Do export
  `sidebarVisibleOrder` so ticket 12 has something to consume.
- Do not build the add-space palette (ticket 11) — the "New project…" row
  that opens it lives in ticket 10's spaces menu, not here.
- Do not build the settings-mode sidebar replacement (S51) — ticket 28. Do
  not build the settings sections themselves (S52) — ticket 29.
- Do not build `render_update_strip` (S46) — ticket 06.
- Do not touch `CreateChangeRequestButton` (`change-request-badge.tsx`) — it
  is an INVENTED surface (no desktop analogue) slated for removal by ticket 4
  (Deletion pass). Only consume `ChangeRequestBadge` (the badge itself,
  which does have a desktop analogue).
- Do not "fix" the `format_time_ago`/`timeAgo` `"0y"` quirk on days 360-364 —
  it is a bug present on both the desktop and web today; per gap N21, leave
  both sides as is rather than making them diverge.
- Do not persist the device-group collapse state — it is in-memory only on
  the desktop (no `UiSettings` field holds it) and must stay that way here.
- Do not repurpose `.identity-badge` for the jump-hint chip — that class
  belongs to the titlebar identity badge, an INVENTED element ticket 06
  removes. Build a fresh `.chat-row-jump` class instead.
- Do not implement the modifier-detection or slot→combo-string logic behind
  the jump-hint chip (§2.8) — only the chip's rendering, given a label.

## 6. Acceptance

- [ ] `render_chat_sidebar`'s scroll region fades with a quadratic ramp,
      gated per-edge from live scroll position with a 1px dead-zone (§2.2)
- [ ] Space-filter trigger is 29px tall, sort button is 29×29px, both show
      the correct hover/open backgrounds; neither opens a menu (§2.3)
- [ ] `ChatListRow` renders all three lines exactly per §2.4, including the
      PR badge on line 3 when a chat has a change request
- [ ] The status corner cycles through jump-hint (given a label) → archive
      pill (row hover) → status/time, in that priority, matching §2.4.1
- [ ] Setting `sidebarOrganization` to `"byDevice"` groups the active list by
      device, local device's group first, collapsible via a chevron that
      animates open/close over 180ms ease-out (§2.5)
- [ ] Setting `sidebarSort` to `"created"` re-sorts the active list, the
      archived shelf, and (once ticket 12 wires it) the jump order, all via
      the same `compareSidebarChats` comparator (§2.1, §3)
- [ ] Toggling `sidebarShowBranch`/`sidebarShowPullRequest`/`sidebarShowHarness`
      clears the corresponding field before layout, collapsing line 3
      entirely when both branch and PR are absent (§2.1, §2.4)
- [ ] Archived shelf rows are 36px, 14px harness icon with a 10px gap, dim at
      rest, brighten on hover/selected, and the time label swaps to an
      "Unarchive" pill on hover (§2.6)
- [ ] Reordering the active list (e.g. a chat receives a new message and
      jumps to the top under `LastUpdated`) animates each displaced row with
      a 260ms `cubic-bezier(0.22, 1, 0.36, 1)` paint-only translate; a new
      row fades in over 150ms; a disclosure opening/closing does NOT trigger
      this glide (§2.7)
- [ ] Connection pill and user menu match §2.9/§2.10's strings and geometry,
      including the muted "Stored on this device" identity line and the
      single "Settings" row routing to Devices
- [ ] `prefers-reduced-motion: reduce` collapses every duration in this
      ticket (fade, disclosure tween, resort glide) to an instant snap
- [ ] Unit tests (port into `web/packages/app/tests/` or co-located
      `*.test.ts`, mirroring the desktop names):
      `equal_sidebar_timestamps_sort_by_stable_chat_id`,
      `current_device_is_promoted_without_resorting_remote_groups`,
      `missing_current_device_leaves_group_order_untouched`,
      `sidebar_chat_height_tracks_visible_metadata`,
      `sidebar_harness_geometry_reflects_row_hierarchy`,
      `sidebar_height_change_is_not_a_reorder`,
      `resort_offsets_empty_when_order_unchanged`,
      `resort_offsets_activity_moves_row_to_top`,
      `resort_offsets_respect_heights_and_gap`,
      `resort_offsets_ignore_added_and_removed_keys`
- [ ] Screenshot pairs, desktop vs web: (a) flat list, one chat selected, one
      hovered showing the archive pill; (b) `ByDevice` grouping with the
      local device's group expanded and a remote device's group collapsed;
      (c) archived shelf open with one row hovered (Unarchive pill visible)
      and "Show N more" visible; (d) a chat row carrying a branch AND a PR
      badge; (e) user menu open; (f) mid-resort-glide frame (throttle
      animations to capture it, or a static diagram of before/after
      positions if a live capture isn't feasible)
- [ ] `pnpm -r build` green; package vitest green
- [ ] No new literal hex/px where a `--rb-*` token exists

## Comments

**Landed** (worktree `roboco-wt/08-sidebar`, branch `wp1/08-sidebar`, on top of
`web-parity/wave-1@34aad828` — a partially-completed prior session's
`view.ts`/`sidebar-store.ts`/`sidebar-disclosure.tsx` drafts were completed
and carried forward):

- **§2.1 view options**: the five fields ride `state/ui-settings.ts`
  (ticket 03's store) read-only through `SidebarState`; the sidebar re-sorts
  with `compareSidebarChats` over the engine's recency list; the
  show-toggles clear `branch`/`changeRequest`/`harness` before layout
  (verified live: `sidebarShowBranch=false` collapsed the branch row to 45px).
- **§2.2 scroll region**: `.sidebar-scroll` is now the masked wrapper
  (quadratic stops, `--rb-sidebar-fade-top/bottom` gates), `.sidebar-list`
  the `#sidebar-lists` scroller; a scroll listener + ResizeObserver gate
  per-edge with the 1px dead-zone. Live-verified: gates `0/0` at rest,
  `1/0` scrolled, `0/1` at top-with-overflow. `.chat-list` gained
  `flex: none` (without it the flex column shrank the list instead of
  scrolling — found via the smoke captures).
- **§2.3 trigger chrome**: 29px trigger, folder mark, name+tag bound
  (gap 6), `@ device` tag 10px @ 45% muted, wifi-off glyph, 60% caret —
  all measured live (height 29, tag 10px/45%).
- **§2.4 rows**: three lines with the spring + `ChangeRequestBadge`
  (`size="sidebar"`, px fixed 6→4 per `change_requests.rs:137`), 6px status
  dot (sidebar-scoped), brand fallback at `text_muted@50%` (S12), archive
  pill a real `<button>` (S17), right-mouse-down context menu via
  `useChatMenu` (S25). Row height measured 61 for a branch row — exactly
  `chat_row_height(true, false)`.
- **§2.5/§2.6 disclosures**: shared `sidebar-disclosure.tsx` (epoch'd,
  interruptible 180ms ease-out, in-flight capture, 3px creep, chevron rides
  the same tween) used by both ByDevice groups and the archived shelf;
  collapse state in-memory only. Archived rows: 36px, 10px gap, 14px mark,
  dim/brighten, selected wash, in-flow time↔Unarchive swap, `sidebarSort`,
  context menu. Body height verified exact: 4+10·36+9·2 = 420 and
  4+14·36+13·2 = 534 (px, inline).
- **§2.7 FLIP**: `useSidebarResort` (layout-effect diff, first fill never
  animates, height-only change ≠ reorder) + WAAPI paint-only glide
  (`SIDEBAR_RESORT_MS=260`, `easeOutQuint`). Verified live twice with an
  in-page recorder: `currentTime` 0→258ms over 260ms (2–3 displaced rows in
  lockstep); reduced motion (matchMedia patched to reduce) → **0** frames.
  New rows fade in via `.chat-row-in` (rb-fade-quick).
- **§2.8**: `jumpLabel` prop renders the chip when non-null (null today).
- **§2.9**: verified; added the OS-offline branch (`navigator.onLine`)
  rendering the desktop's exact `"Offline — sends are saved"` + 5px warning
  dot. Raw `Attempt N` stays in `detail`, never the label.
- **§2.10**: menu = "Stored on this device" + Engines (web pairing entry
  point, kept per the ticket) + Settings; Appearance row and separator
  removed; card 6px above the trigger; subline 15px/`--rb-text-muted`.
  Settings routes to `/settings/remote-access` as the web's Devices
  analogue **until ticket 28/29 land the real Devices section** — the route
  target should be repointed then.
- `sidebarVisibleOrder` exported from `lib/view.ts` for ticket 12.

**Deviations / judgment calls a human should review:**

1. **The sort button was NOT rebuilt** (§2.3's `.space-filter-sort`
   29×29). Ticket 04 deliberately removed the inert button ("an inert
   control is worse than no control") and the operator instruction for this
   run forbids re-adding ticket 04's deletions; ticket 10 builds the real
   trigger + `SidebarViewMenu` together. Everything else in §2.3 landed.
2. **`--rb-motion-resort: 260ms`** is declared in `app.css`, not the theme
   artifact: `RESORT` is a UI-level spec living beside the FLIP code in
   `shell.rs:618`, not in the proto motion catalog, and regenerating the
   artifact would need `crates/` changes this web-only ticket cannot make.
   The curve uses the catalog's `--rb-ease-ease-out-quint` (EASE_RESORT's
   alias). If the catalog ever gains the spec, move the token.
3. **The archived section is excluded from the FLIP keyed list** — the
   ticket's §2.7 mentions an `"archived"` key, but `shell.rs:4863`'s
   `render_active_rows` returns only active rows + group sections; the
   shelf is a sibling below (moves instantly when rows are added/removed,
   exactly like the desktop). Followed the Rust.
4. **PR-watch `cwd` is `sourceContext.repoRoot`**, not the ticket's
   `sourceContext?.cwd`: research 07 §427 and `desired_watch_targets`
   (`change_requests.rs:249`) key the watch on the repo root. The chat
   page/changes page still pass `chat.cwd` (pre-existing; not this
   ticket's table).
5. **`sidebar-body.tsx` was restructured** despite §1's "no structural
   change": the filter sat INSIDE the scroller (the fade would dim it) and
   the notice preceded the pill. Now: filter above the scroll region,
   pill → notice → user menu, per `render_chat_sidebar`'s assembly. A
   transient nested-duplicate `<nav class="sidebar-list">` from my own
   restructure was caught and fixed via the smoke captures.
6. **`deviceOnline`**: a missing device row reads ONLINE (state.rs:1395
   resolves unknown ids to `true`), fixing a wrong-side draft from the
   prior session.
7. **Empty state**: new `.sidebar-empty` (12px, faint, px 8/pb 8 per S62)
   for the empty-list copy ("No chats yet." keeps the chats vocabulary);
   `.sidebar-note` stays for the web-only pairing/loading states, which
   have no desktop analogue.
8. **S26 kebab decision**: kept as the touch-only analogue of the desktop's
   right-click (phones have no right-click; the phone layer must keep
   working). Its stuck `data-open` state was dropped.

**Verification:**

- `pnpm -r build` green (typecheck + vite build for all five packages).
- `@roboco/app` vitest: **480/480** across 34 files, including the new
  `tests/sidebar-view.test.ts` (15 tests mirroring the desktop names:
  `equal_sidebar_timestamps_sort_by_stable_chat_id`,
  `current_device_is_promoted_without_resorting_remote_groups`,
  `missing_current_device_leaves_group_order_untouched`,
  `sidebar_chat_height_tracks_visible_metadata`,
  `sidebar_harness_geometry_reflects_row_hierarchy`,
  `sidebar_height_change_is_not_a_reorder`,
  `resort_offsets_empty_when_order_unchanged`,
  `resort_offsets_activity_moves_row_to_top`,
  `resort_offsets_respect_heights_and_gap`,
  `resort_offsets_ignore_added_and_removed_keys`,
  `resort_glide_spec_matches_original`,
  `sidebar_disclosure_motion_lands_exactly_on_its_target`, plus grouping
  and comparator cases).
- Live smoke verification (web_smoke + use-browser, seeded via a throwaway
  WS driver): trigger 29px; tag 10px/45%; sort=created and lastUpdated both
  reorder correctly; ByDevice group promoted/collapses with the tween
  (mid-flight height 237 = 4+5·45+4·2); archived shelf pages 10→14 with
  "Show 4 more"; edge-fade gates per-edge; disclosure heights exact; FLIP
  timeline 0→258ms; reduced-motion 0 frames; user-menu contents.
- The 6px status-dot CSS is unverified live (no Working/Input/Errored row
  existed in the fixture — all rows were Done/Idle); it is
  CSS-only (`.chat-row-status .dot`).

**Screenshots** (`.scratch/web-parity/shots/08/`, web half, 1440×900):

| File | State |
| --- | --- |
| `08-a-flat-selected-hover.png` | flat list, `alpha` selected, `beta` hovered (Archive pill) |
| `08-b-bydevice-expanded.png` | ByDevice, local group expanded |
| `08-b-bydevice-collapsed.png` | ByDevice, group collapsed ("DESKTOP-19EUMEB (5)") |
| `08-c-archived-open-hover.png` | shelf open, row 3 hovered (Unarchive pill), "Show 4 more" |
| `08-d-branch-row.png` | branch row, line 3 = `feat/sidebar-parity` (61px) |
| `08-e-user-menu.png` | user menu: identity line, Engines, Settings |
| `08-f-resort-before/mid/after.png` | FLIP reorder trio |
| `08-extra-filter-picked.png` | picked-space trigger with "@ device" tag |
| `08-extra-edge-fade-scrolled.png` | mid-scroll, both fade gates on |

**Documented skips:**

- **Desktop half of every pair**: no desktop client running; `shot.ps1`
  needs the window foregrounded (ticket 02 documented the same skip when a
  fullscreen game held the machine). Note the seeded smoke state could not
  have been shown on the desktop client anyway — it pairs its own local
  engine, not the smoke fixture.
- **(b)'s remote device's group**: not reproducible — the engine's
  `read_chats` filters to its own device, so a remote group only exists
  once a second engine is paired (fleet, ticket 31). Captured the local
  group expanded + collapsed instead.
- **(d)'s PR badge half**: not reproducible offline — PR resolution is the
  GitHub API over HTTPS (`source_control.rs`); the smoke engine has no
  reachable PR. The branch half is captured; the badge component itself is
  pre-existing (`change-request-badge.tsx`) and wired per §2.4.
- **(f) mid-glide frame**: the shot at ~80ms may have landed after the
  260ms glide (screenshot round-trip); the in-page WAAPI timeline
  (0,0,4,4,8,8,…258) is the motion evidence, per the ticket's
  "static diagram if a live capture isn't feasible" fallback.
- **Connection pill offline/reconnecting captures**: CDP network/media
  emulation had no effect through use-browser (`navigator.onLine` stayed
  true); §2.9 is otherwise verified by inspection.
- **Reduced motion live media capture**: same CDP emulation quirk; the
  matchMedia-patched run (0 glide frames) verifies this ticket's gate.

**Port note for the record**: the 06-titlebar sibling's `web_smoke.exe`
held port 27699 for ~35 minutes (idle CPU, one stale connection) with no
cleanup; after waiting, it was killed to free the port per the runbook's
single-server contention model. If that agent was mid-capture, one of its
shots may need a retake.
