# 19 — Tool groups

**What to build:** The transcript's task tree. A run's consecutive tool calls
fold into one group with a shimmering summary header and a chevron; each step is
a row drawn against a canvas-painted activity rail — a trunk that grows down and
a branch that bends out to the tool's glyph — with rows arriving on a staggered
reveal. Every step expands in place to show what was asked and what came back:
wrapped invocation text, capped output, a real diff rendered by the Changes
pane's body renderer, stats rows, or a thought's flattened markdown; an open
chip can offer "Show full output (12 KB)" and fetch the sidecar blob. Agent
spawns render as their own cards that open the subagent as a right-pane tab.

**Blocked by:** 18 (Transcript rows), 22 (Changes pane — the diff detail reuses
its body renderer).

**Status:** ready-for-agent

**Research:** `../../web-client/research/02-transcript.md` §3.0, §3.9 (all of
§3.9.1–§3.9.11), §3.10, §3.11, §4.5, §4.9–§4.14, §4.19, §5 rows 5, 6, 20–52,
plus row 78 (reduced motion, this ticket's share).

**Desktop reference (for lookups only):** `crates/ui/src/transcript.rs::render_tool_group`
(:5837), `::chip_header_row` (:6871), `::chip_header` (:7136), `::tool_chip`
(:7327), `::subagent_chip` (:7392), `::activity_rail` (:7227),
`::activity_ribbon` (:7306), `::activity_branch_points` (:2183),
`::tool_connector_parts` (:2161), `::reveal_tool_row` (:7210),
`::tool_group_title` (:1702), `::detail_body` (:6688), `::tool_detail` (:757),
`::call_block` (:825), `::diff_to_file` (:890), `::blob_detail` (:1851+),
`::thought_lines` (:395), `::subagent_tab_title` (:7188),
`crates/proto/src/view.rs::tool_chip_content` / `::tool_group_summary`,
`crates/ui/src/changes.rs::render_file_body_with_syntax` / `::body_height`.

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/components/transcript.tsx` | edit | `ToolGroupRowView`, `ToolChipView`, `ToolGlyph` (replace with real icons), `formatBytes` (replace with `formatKb`) |
| `web/packages/app/src/components/tool-group.tsx` | new | `ToolGroupRow`, `ToolGroupHeader`, `ToolChipRow`, `ToolDetailPane`, `SubagentChip`, `ChipHeaderRow`, `FileBadge` — move the group out of `transcript.tsx` |
| `web/packages/app/src/components/activity-rail.tsx` | new | `ActivityRail` (SVG trunk + branch + glyph), `activityBranchPoints`, `activityRibbon`, `toolConnectorParts`, `toolConnectorContinuation` |
| `web/packages/app/src/lib/transcript.ts` | edit | `ToolItem`, `ToolDetail`, `toolDetail`, `callBlock`, `wrapCols`, `thoughtDetail`, `thoughtLines`, `wrapStyledRuns`, `thoughtBlockLines`, `toolChipContent`, `toolGroupSummary`, `toolGroupTitle`, `isSubagentSpawn`, `subagentModel`, `toolFingerprint`; new `diffToFile`, `blobDetail`, `formatKb`, `toolIconName`, `fileBadgeName`, `chipsHeight`, `detailHeight`, `subagentTabTitle`, `titleLine`, `stripSpawnPrefix`, `isAgentCall`/`isAgentTool`/`isSpawnLink` (export), `toolGroupCollapses` |
| `web/packages/app/src/lib/tool-motion.ts` | new | `FoldState`, `ToolGroupReveal`, `toolDisclosureProgress`, `toolRowRevealProgress`, `toolConnectorRevealProgress`, `toolTitleShimmerAmount`, `toolTitleShimmerPhase` |
| `web/packages/app/src/components/diff-view.tsx` | edit (read-only reuse) | expose the file-body renderer + `bodyHeight` that ticket 22 lands, so `ToolDetail.diff` can mount it |
| `web/packages/app/src/components/subagent-dialog.tsx` | **delete** | the invented modal |
| `web/packages/app/src/state/right-pane.ts` | edit | a subagent tab instance (`chatId`, `docId`, `title`, `frozen`) — the tab model itself is ticket 07 |
| `web/packages/app/src/styles/app.css` | edit | `.tool-group`, `.tool-group-header`, `.tool-group-chevron`, `.tool-group-title`, `.tool-shimmer`, `@keyframes rb-tool-shimmer`, `.tool-group-fold`, `.tool-group-body`, `.chip-reveal`, `@keyframes rb-chip-reveal`, `.tool-chip`, `.tool-chip-head`, `.tool-chip-head-button`, `.tool-glyph`, `.tool-chip-label`, `.tool-chip-detail`, `.tool-chip-chevron`, `.tool-chip-model`, `.tool-chip-body`, `.tool-invocation`, `.tool-output`, `.tool-thought`, `.tool-output-line`, `.tool-output-more`, `.tool-output-note`, `.tool-stat-row`, `.tool-stat-path`, `.tool-stat-add`, `.tool-stat-del`, `.tool-full-button`, `.tool-agent-link`, `.tool-chip-agent`; **delete** `.chip-pending`, `@keyframes rb-chip-pulse`, `.chip-error-mark`, `.subagent-dot*`, `.subagent-dialog*`; new `.activity-rail`, `.tool-file-badge` |
| `web/packages/app/tests/transcript-model.test.ts` | edit | new cases in §6 |

---

## 1. Context a fresh session needs

- A tool group is one virtualized transcript row (`RowKind::ToolGroup { tools,
  auto_open }`) produced by `rowsForEntry` (ticket 18 owns that mapping).
  Consecutive tool parts of the same *genus* accumulate into one group; an
  agent-spawn chip never shares a group with ordinary tools.
- A group is either **collapsible** (contains at least one non-agent tool →
  `tool_group_collapses`, transcript.rs:380) or a **standalone spawn card row**
  (all agent chips, always open, no header).
- Today's web: `components/transcript.tsx::ToolGroupRowView` (:780) and
  `::ToolChipView` (:836) draw a flat list of chips with Unicode glyphs; CSS
  lives in `styles/app.css` lines 3718–4105. `lib/transcript.ts` already carries
  `ToolItem`, `ToolDetail`, `toolDetail`, `callBlock`, `thoughtLines`,
  `toolChipContent`, `toolGroupSummary`, `toolGroupTitle`, `toolFingerprint`.
- The tool chrome uses `theme.font_sans_fixed` (the tabular/fixed-width sans)
  at 12px — *not* mono. Detail **bodies** override to mono. Research §7 q3 could
  not confirm which web font stack maps to `font_sans_fixed`; pick the same
  stack ticket 02 assigns and use one token (`--rb-font-sans-fixed`).
- Colors are theme roles: `text` → `var(--rb-text)`, `text_muted`, `text_faint`,
  `danger`, `success`; washes are `rgb(var(--rb-ink) / a)` and
  `rgb(var(--rb-hairline) / a)`. No literal hex.
- Icons come from `@roboco/icons` (`Icon name=…`), generated from the same
  `crates/ui/assets/icons/*.svg` the desktop embeds. The names this ticket needs
  all exist: `terminal`, `document`, `documentAdd`, `pen`, `magnifer`,
  `folderWithFiles`, `global`, `checklist`, `bot`, `widget`, `chatRoundLine`,
  `altArrowDown`, `altArrowRight`, `arrowUpRight`.
- The inline diff detail delegates **wholly** to the Changes pane's body
  renderer; ticket 22 must have landed it. No comment layer here — "an inline
  tool diff is a record, not a review surface".
- Out of this ticket: the row shell, gaps and hover strip (18), the rail /
  badges / loaders (20), markdown blocks (21), the Changes pane itself (22),
  the right-pane tab host (07).

---

## 2. Spec

### 2.0 Constants

| Const | Value | Source |
|---|---|---|
| `CHIP_HEIGHT` | 38.0 | transcript.rs:99 |
| `CHIP_GAP` | 0.0 | transcript.rs:100 |
| `CHIP_CARD_HEIGHT` | 30.0 | transcript.rs:101 |
| `CHIP_HEADER_HEIGHT` | `CHIP_CARD_HEIGHT - 2.0` = 28.0 | transcript.rs:106 |
| `ACTIVITY_GUTTER_WIDTH` | 48.0 | transcript.rs:110 |
| `ACTIVITY_TEXT_GAP` | 8.0 | transcript.rs:111 |
| `ACTIVITY_TRUNK_X` | 12.5 | transcript.rs:112 |
| `ACTIVITY_BEND_RADIUS` | 6.0 | transcript.rs:113 |
| `ACTIVITY_BRANCH_END_X` | 28.0 | transcript.rs:114 |
| `ACTIVITY_ICON_LEFT` | 32.0 | transcript.rs:115 |
| `ACTIVITY_ICON_SIZE` | 16.0 | transcript.rs:116 |
| `TOOL_TEXT_SIZE` | 12.0 | transcript.rs:117 |
| `TOOL_LABEL_SIZE` | 12.0 | transcript.rs:118 |
| `TOOL_LABEL_LINE_HEIGHT` | 18.0 | transcript.rs:119 |
| `TOOL_GROUP_HEADER_HEIGHT` | 26.0 | transcript.rs:120 |
| `TOOL_TREE_ROW_HEIGHT` | 32.0 | transcript.rs:122 |
| `TOOL_FOLD` | `MotionSpec::new(140, EASE_OUT)` | transcript.rs:123 |
| `TOOL_GROUP_SHIMMER_DURATION` | 3400ms | transcript.rs:127 |
| `TOOL_GROUP_SHIMMER_HALF_WIDTH` | 0.36 | transcript.rs:128 |
| `TOOL_GROUP_SHIMMER_STRIP_WIDTH` | 2.0 | transcript.rs:129 |
| `TOOL_ROW_REVEAL` | `MotionSpec::new(360, EASE_OUT_EXPO)` | transcript.rs:130 |
| `TOOL_CONNECTOR_REVEAL` | `MotionSpec::new(480, EASE_OUT_QUINT)` | transcript.rs:133 |
| `TOOL_FIRST_ROW_DELAY_MS` | 90 | transcript.rs:135 |
| `TOOL_ROW_STAGGER_MS` | 65 | transcript.rs:136 |
| `CHIPS_TOP_PAD` | 2.0 | transcript.rs:166 |
| `FOLD_TWEEN_WINDOW` | 400ms | transcript.rs:170 |
| `THOUGHT_WRAP_COLS` | 96 | transcript.rs:387 |
| `OUTPUT_DETAIL_MAX_LINES` | 24 | transcript.rs:737 |
| `DIFF_DETAIL_MAX_LINES` | 600 | transcript.rs:742 |
| `OUTPUT_LINE_HEIGHT` | 18.0 | transcript.rs:746 |
| `OUTPUT_BODY_PAD` | 12.0 (py 6 × 2) | transcript.rs:749 |
| `DETAIL_SEPARATOR` | 1.0 | transcript.rs:752 |
| `CALL_WRAP_COLS` | 80 | transcript.rs:806 |
| `BLOB_AFFORDANCE_HEIGHT` | 24.0 | transcript.rs:1838 |
| `FULL_OUTPUT_MAX_LINES` | 400 | transcript.rs:1851 |
| `SUBAGENT_TITLE_MAX` | 40 | transcript.rs:7149 |
| `changes::DIFF_LINE_HEIGHT` | 21 | changes.rs:75 |

---

### 2.1 `render_tool_group` (:5837)

The core of the surface. A group is either **collapsible** (contains at least
one non-agent tool → `tool_group_collapses`, :380) or a **standalone spawn card
row** (all agent chips, always open, no header).

#### 2.1.1 Open/closed resolution (:5845-5872)

```
arrival_pending  = !reduce_motion && any start in reveal.starts is younger than
                   TOOL_CONNECTOR_REVEAL.total() (480ms)
effective_auto_open = auto_open || arrival_pending
open = !collapses || fold.open.unwrap_or(effective_auto_open)
active = collapses && auto_open          // drives the shimmer
```

`auto_open` itself is set at row build time: `streaming && this group is the LAST
part of the entry` (:1265).

When the rendered open-state flips without a user click (auto-open expiring), the
fold's tween is seeded from the last rendered height: `fold.from =
reveal.rendered_height; fold.toggled_at = now; fold.disclosure_at = now`
(:5862-5870).

#### 2.1.2 Header (:6107-6158)

| Property | Value | Source |
|---|---|---|
| id | `"{row_id}-hdr"` | :6108 |
| layout | `relative flex flex_row items_center gap(6) pr(4)` | :6110-6114 |
| height | `TOOL_GROUP_HEADER_HEIGHT` = 26 | :6115 |
| font | `px(TOOL_LABEL_SIZE = 12)` / `line_height px(TOOL_LABEL_LINE_HEIGHT = 18)` | :6117-6118 |
| color | `theme.text_muted`; hover `theme.text`. **Deliberately NOT red when children failed** | :6124-6125 |
| cursor | `cursor_pointer` | :6116 |
| chevron slot | `w(22) h(18) flex_none relative` | :6134-6136 |
| chevron icon | `icons::ALT_ARROW_DOWN`, absolute `left(ACTIVITY_TRUNK_X − 7 = 5.5) top(2) size(14)`, `theme.text_muted` | :6139-6147 |
| chevron rotation | `rotate(−π/2 · (1 − disclosure_progress))` — i.e. −90° closed, 0° open | :6144-6146 |
| title | `min_w_0 h(18) flex items_center truncate` → `tool_group_title(summary, shimmer_phase, theme)` | :6151-6157 |

Click → stop propagation, `toggle_fold(row_id, viewport_height,
effective_auto_open)` (:6126-6130), which sets `from = open_height if currently
open else 0`, flips `open`, bumps `epoch`, and stamps `toggled_at` /
`disclosure_at`.

`disclosure_progress` (:2220-2231): `TOOL_FOLD.curve.eval(elapsed / 140ms)`,
negated (`1 − p`) when closing; when `fold.disclosure_at` is `None` it is
`1.0`/`0.0` by the open flag. Reduced motion → hard `1.0`/`0.0` (:6098-6102).

```
tool_disclosure_progress(open, fold, now):
  if fold.disclosure_at is None -> open ? 1.0 : 0.0
  raw = (now − fold.disclosure_at) / TOOL_FOLD.total()
  p   = TOOL_FOLD.curve.eval(raw)
  return open ? p : 1.0 − p
```

#### 2.1.3 Title shimmer (`tool_group_title`, :1702)

When `shimmer_phase` is `None` (settled group, or reduced motion) the title is
just the text run — it keeps the inherited hover color. When active:

- the intact shaped line is repainted through narrow moving `ContentMask` clips
  (the native equivalent of CSS `background-clip: text`; splitting per character
  would break kerning and make the sweep hop) — **on web use
  `background-clip: text`**;
- strip width `TOOL_GROUP_SHIMMER_STRIP_WIDTH` = 2px; `strip_count =
  ceil(text_width / 2)`;
- per-strip color `motion::mix(theme.text_muted, theme.text, amount)`;
- `amount = tool_title_shimmer_amount(x, phase)` (:2237): the primary highlight
  center is `−2.5 + phase·6.0`, adjacent copies at ±3.0 and ±6.0 in normalized
  title-width units; each contributes
  `clamp(1 − |x − center| / TOOL_GROUP_SHIMMER_HALF_WIDTH(0.36), 0, 1)` and the
  maximum wins;
- `phase = fract(elapsed / TOOL_GROUP_SHIMMER_DURATION)` with
  `TOOL_GROUP_SHIMMER_DURATION` = **3400ms** (:2245-2248). The clock starts with
  the group (`reveal.shimmer_started_at`), **never inherited mid-sweep**.

The Rust doc comment names the CSS recipe this reproduces (transcript.rs:2233):
"a 300%-wide repeating gradient moves from 200% to -100%. Its 38→50→62%
highlight maps to a 36%-of-title shoulder around each peak; adjacent copies sit
three title-widths apart." Retune `.tool-shimmer` (app.css:3760) from its current
`linear-gradient(100deg, muted 42%, text 50%, muted 58%)` / `background-size:
280%` / `140% → −140%` to that recipe.

#### 2.1.4 Chip stack (:6160-6353)

Container: `pt(CHIPS_TOP_PAD = 2) flex flex_col gap(CHIP_GAP = 0)`.

`base_row_height = TOOL_TREE_ROW_HEIGHT (32)` when collapsible, else
`CHIP_HEIGHT (38)`.

Per-chip **target height** (:6006-6042):
```
open  → base_row_height
        + detail_height(invocation)     (if any)
        + detail_height(detail)         (if any)
        + BLOB_AFFORDANCE_HEIGHT (24)   (if an affordance is offered)
closed→ base_row_height
```
While `fold.toggled_at` is set and `!reduce_motion`, the height lerps from
`fold.from + base_row_height − CHIP_CARD_HEIGHT` to `target` over `TOOL_FOLD`
(140ms `EASE_OUT`).

**Group body height** (:6080-6087, :6360-6387):
```
revealed_height = CHIPS_TOP_PAD + Σ (row_height[i] · reveal_progress[i])
target          = open ? revealed_height : 0
body_height     = fold.toggled_at ? lerp(fold.from, target,
                                         TOOL_FOLD.curve.eval(elapsed/140ms))
                                  : target
```
The body is `overflow_hidden` with an explicit `h(body_height)` for collapsible
groups; a non-collapsing (spawn-only) group renders its chips unwrapped.

The whole group is wrapped in `relative flex flex_col
font_family(theme.font_sans_fixed)` (:6390-6396) — tool chrome uses the
fixed-width sans; detail bodies override to mono.

While any tween/reveal is unfinished, the desktop keeps requesting frames with an
invisible canvas (:6405-6416); on web that is a `requestAnimationFrame` loop that
stops when every progress reaches 1.

#### 2.1.5 Row reveal / arrival staggering

`sync` assigns reveal start instants (:4098-4113):
```
reveal.shimmer_started_at ||= now
if is_new_group { reveal.header_started_at ||= now }
first_row_delay = is_new_group ? TOOL_FIRST_ROW_DELAY_MS (90) : 0
for (arrival_ix, tool_ix) in (old_count .. tools.len()).enumerate():
    reveal.starts[tool_ix] = now + (first_row_delay + arrival_ix · TOOL_ROW_STAGGER_MS (65)) ms
```
`replay_baseline` (first populated frame after attach) clears all reveals and
strips `toggled_at`/`disclosure_at` from every fold, so replaying history or
switching chats never re-animates an existing task tree (:4063-4072).

`ToolGroupReveal` (transcript.rs:2123) — "reveal epochs for one live ordinary
tool group. `None` means the row was already present when this transcript
attached (or has finished revealing), so replaying history and scrolling a
virtualized row back into view stay completely still." Fields:
`header_started_at` ("a newly streamed task header participates in the same
height/fade/lift reveal as its steps; replayed headers leave this unset"),
`starts: Vec<Option<Instant>>`, `shimmer_started_at` ("a title sweep begins with
this group instead of inheriting the shared loader clock at an arbitrary point
midway across the label"), `rendered_open`, `rendered_height`.

`tool_row_reveal_progress` (:2135): `TOOL_ROW_REVEAL.curve.eval(elapsed /
360ms)` — returns `1.0` immediately for `None` starts or reduced motion.
Because `starts` are in the FUTURE, the elapsed-since computation saturates at 0
until the delay elapses — **that is how the stagger is implemented**.

`reveal_tool_row(row, height, progress)` (:7210): when `progress >= 1.0` the row
is passed through unchanged; otherwise it is wrapped in
`w_full h(height · progress) flex_none overflow_hidden`. **Only the height
clips** — the fade and 4px lift are applied to the row CONTENT, so the connector
keeps full contrast while it draws.

Content lift/fade (:6346-6350, :7377-7381):
`card.relative().top(px(4.0 · (1 − content_reveal))).opacity(content_reveal)`
where `content_reveal = tool_connector_parts(connector_reveal, ix > 0).1`.

#### 2.1.6 Connector timing (`tool_connector_parts`, :2161)

```
has_predecessor → (incoming_start, incoming_end, branch_start) = (0.45, 0.72, 0.68)
first row       → (0.00, 0.62, 0.58)
incoming = clamp((p − incoming_start)/(incoming_end − incoming_start), 0, 1)
branch   = clamp((p − branch_start)/(1 − branch_start), 0, 1)
```
The branch overlaps the end of the incoming phase so there is no dead frame at
the bend. `p` itself is `tool_connector_reveal_progress` =
`TOOL_CONNECTOR_REVEAL.curve.eval(elapsed / 480ms)` (`EASE_OUT_QUINT`).

`tool_connector_continuation(next_progress)` (:2175): the outgoing trunk of row
*i* is driven by row *i+1*'s progress as `clamp(next_p / 0.45, 0, 1)` — visually
it belongs to the present row, temporally to the next row's arrival.

#### 2.1.7 `activity_rail` (:7227) — the tree geometry

Gutter `relative w(ACTIVITY_GUTTER_WIDTH = 48) flex_none`, with a canvas at
`absolute inset_0` (web: an `<svg>` at `position:absolute; inset:0`) and an
absolutely-positioned glyph.

Colors: ribbon `theme.hairline(0.12)`; glyph tint `theme.danger` when
`tool.is_error`, else `theme.text_muted`.

Canvas paint (:7250-7284):
- `x = bounds.origin.x + ACTIVITY_TRUNK_X (12.5)`
- `branch_y = bounds.origin.y + row_height/2`
- `bend_y = branch_y − ACTIVITY_BEND_RADIUS (6)`
- **incoming trunk** (when `incoming_reveal > 0`): from `(x, top)` down to
  `(x, top + (row_height/2 − 6)·incoming_reveal)`. When fully in AND the group
  continues AND `continuation_reveal > 0`, the bottom extends instead to
  `bend_y + (bounds.height − (row_height/2 − 6)) · continuation_reveal`.
- **branch**: `activity_branch_points(branch_reveal)` mapped to
  `(x + p.x, bend_y + p.y)`.
- Both ribbons are unioned into ONE fill with the non-zero fill rule before
  painting — stroke tessellation would double-blend the intersection (test:
  `connector_intersection_is_tessellated_only_once`). On web: one `<path>` with
  `fill-rule="nonzero"` and both contours in a single `d`, not two strokes.

`activity_branch_points(progress)` (:2183) — the elbow + straight leg:
```
25 samples t = k/24, k in 0..=24:
    p = ( BEND_RADIUS · t² ,  BEND_RADIUS · (2t − t²) )       // quadratic corner
then one final point ( ACTIVITY_BRANCH_END_X − ACTIVITY_TRUNK_X = 15.5 , BEND_RADIUS = 6 )
```
For `progress < 1` the polyline is cut by ARC LENGTH (segment lengths summed,
`remaining = total · progress`, last segment lerped) so changing the branch
length introduces no speed jump at the elbow/leg junction (test:
`tool_branch_reveal_tracks_distance_through_the_bend`).

`activity_ribbon(path, points)` (:7306) — builds a 1px-wide closed contour by
offsetting each point ±0.5 along the local normal (computed from the
neighbouring points), walking the left side forward and the right side backward,
then closing.

**Glyph** (:7289-7301): `icons::CHAT_ROUND_LINE` for thought chips, else
`tool_icon_path(call)`; absolute `left(ACTIVITY_ICON_LEFT = 32)`,
`top(row_height/2 − ACTIVITY_ICON_SIZE/2)`, `size(ACTIVITY_ICON_SIZE = 16)`,
`opacity(branch_reveal)`, `text_color(tint)`.

#### 2.1.8 `chip_header_row` (:6871) — the chip content row

`activity = !is_agent_tool(tool)` — i.e. ordinary tools (rail rows) vs. spawn
cards.

| Property | Value | Source |
|---|---|---|
| group name | `"tool-header"` | :6905 |
| height | `CHIP_CARD_HEIGHT (30)` when activity, `CHIP_HEADER_HEIGHT (28)` otherwise | :6906-6910 |
| layout | `w_full min_w_0 flex flex_row items_center gap(8)` | :6911-6916 |
| padding-x | `0` when activity, `8` otherwise | :6917 |
| font | `px(TOOL_LABEL_SIZE = 12)` / `line_height px(18)` | :6918-6919 |

**Children (in order):**
1. **Icon tile** — ONLY when `!activity` (spawn cards): `size(18) flex_none
   rounded(5) bg(ink(0.08)) flex items_center justify_center`, icon `size(12)
   theme.text_muted` (:6924-6940). Ordinary tools put their icon on the rail
   instead.
2. **Label** — `flex_none h(18) flex items_center`, `font_weight MEDIUM` only
   when `!activity`, `text_color(tint)` where `tint = theme.danger` if failed
   else `theme.text_muted`. Group-hover → `theme.text` when `hover_text`
   (:6943-6963).
3. **Detail slot** — `min_w_0 h(22 if file_path else 18) flex items_center
   truncate`; `flex_1` only when `!activity`; hidden when activity AND the
   detail string is empty (:6965-6984). Color: `theme.danger` if failed,
   `theme.text_muted` if activity, else `theme.text.opacity(0.85)`.
   - For `ReadFile`/`WriteFile`/`EditFile`/`ApplyPatch(Some)` the detail is a
     **file badge** (:6986-7037): `min_w_0 h(22) flex items_center
     overflow_hidden gap(6) rounded(5) bg(theme.ink(0.06)) pl(1) pr(6)` wrapped
     in `frost::frosted(5.0, 16.0, …)` (web: `backdrop-filter: blur(16px)`);
     inside, a `size(20) flex_none rounded(4) bg(file_icons::well_bg(theme))`
     well holding a `size(14)` file-type icon, then the truncating
     `file_badge_name(path)` (final path component, accepts `/` and `\`).
   - Otherwise a plain `min_w_0 truncate` text node.
4. **Subagent model** (when `call.subagent_model()` is `Some`) — `flex_none
   h(18) flex items_center text_size(11) text_color(theme.text_faint)`, bare
   text, **NOT a pill** (:7069-7078).
5. **Running spinner** (when `subagent_ref.is_some() && status == Running`) —
   `loaders::mini_glyph_spinner("subagent-chip-{ref}", cell 2.0, theme.glyph, …)`
   in a `flex_none` slot (:7080-7093) → web `GlyphSpinner size≈8`.
6. **Trailing tile** (`ChipTrail`, :7094-7132) — `size(18) flex_none flex
   items_center justify_center text_color(theme.text_muted.opacity(0.8))`.
   - When `activity`: `opacity(0.0)`, group-hover → `opacity(1.0)` (invisible
     until the row is hovered).
   - When `!activity`: `rounded(5) bg(ink(0.06))`.
   - `ChipTrail::Chevron{open}` → `icons::ALT_ARROW_DOWN` if open else
     `icons::ALT_ARROW_RIGHT`, `size(12)`, `theme.text_faint`; group-hover →
     `theme.danger` if failed else `theme.text`.
   - `ChipTrail::OpenArrow` → `icons::ARROW_UP_RIGHT`, `size(11)`,
     `theme.text_muted.opacity(0.8)`.

Label/detail come from `roboco_proto::view::tool_chip_content(call)`; thought
chips hard-code `("Thought process", "")` (:6878-6882).

`running`/`failed` (:6891-6895):
```
running = subagent_ref.is_some() && status == Running
failed  = tool.is_error || (subagent_ref.is_some() && status == Failed)
hover_text = activity && trail.is_some() && !failed
```

`chip_header` (:7136) is the same row wrapped for the expandable card; it carries
the `ChipTrail::Chevron` and the `cursor_pointer` click target.

#### 2.1.9 `tool_chip` (:7327) — detail-less rows

`h(TOOL_TREE_ROW_HEIGHT 32 if rail else CHIP_HEIGHT 38) w_full flex_none flex
flex_row`. Rail rows prepend `activity_rail(...)`. The card:
`ml(ACTIVITY_TEXT_GAP = 8)` when rail, `my((row_height − CHIP_CARD_HEIGHT)/2)`,
`h(CHIP_CARD_HEIGHT = 30) min_w_0 flex_1 flex items_center overflow_hidden`.
Non-rail cards add `rounded(9) border_1 border_color(hairline(0.07))
bg(ink(0.03))`.

#### 2.1.10 Expandable chip card (inline, :6231-6352)

```
card = div()
  .my((base_row_height − CHIP_CARD_HEIGHT)/2)
  .ml(ACTIVITY_TEXT_GAP = 8)            // only when collapses (rail present)
  .min_w_0 .flex_1 .flex .flex_col .overflow_hidden
  // non-rail: .rounded(9).border_1().border_color(hairline(0.07)).bg(ink(0.03))
  .child(header)                         // h = 30 (rail) / 28 (card), cursor_pointer
  .child(panel)                          // only when open || animating
  .h(row_height − base_row_height + CHIP_CARD_HEIGHT)
```
Header click (:6257-6267): stop propagation; toggles
`tool_details["{row_id}#d{ix}"]` with `from = row_height − base_row_height +
CHIP_CARD_HEIGHT`, flipped `open`, `epoch += 1`, `toggled_at = now`.

Panel (`flex_none min_w_0 flex flex_col overflow_hidden`) children in order:
1. separator `h(DETAIL_SEPARATOR = 1) flex_none` — `bg(hairline(0.06))` only when
   `!collapses` (rail rows use whitespace, not a rule);
2. `detail_body(invocation)` — "what was asked";
3. separator again;
4. `detail_body(detail)` — "what came back";
5. blob affordance row.

`animating` keeps the body mounted while the close tween shrinks over it:
`dfold.epoch > 0 && dfold.toggled_at.elapsed() < FOLD_TWEEN_WINDOW (400ms)`.

**Default-open rule** (:5977-5989): `(detail.is_some() || invocation.is_some())
&& fold.open.unwrap_or(tool.is_thought && !tool.resolved)` — a STREAMING thought
chip defaults open; everything else defaults closed; a user toggle overrides.

Spawn links never expand: `details[ix]` and `invocations[ix]` are forced to
`None` when `is_spawn_link(tool)` (:5884, :5906).

**`FoldState`** (transcript.rs:2094) — one per group and one per chip detail:

| Field | Meaning |
|---|---|
| `open: Option<bool>` | user pin (click); `None` follows the auto-open rule |
| `epoch: usize` | bumped per toggle — keys the height tween |
| `from: f32` | height at the moment of the toggle (the tween's start). The destination is always the *current* target height, so content growth after a toggle snaps instead of replaying a stale tween |
| `toggled_at: Option<Instant>` | when the toggle happened. "The tween is armed only for a short window after the click: gpui replays an element's animation on REMOUNT, and a virtualized row scrolling back into view is a remount — an armed-forever tween made every once-collapsed group flash open→closed on each reappearance (user report)" |
| `disclosure_at: Option<Instant>` | the chevron rotation clock |
| `duration_ms: u64` | per-toggle duration. User bubbles scale this with travel distance; tool folds leave it at zero and keep their catalog constants |
| `user_expansion_height: f32` | ticket 18's concern (Show more), not this one |

#### 2.1.11 Blob affordance row (:6300-6324)

| Property | Value | Source |
|---|---|---|
| id | `"{row_id}#d{ix}-blob"` | :6306 |
| height | `BLOB_AFFORDANCE_HEIGHT` = 24 | :6307 |
| layout | `flex_none flex items_center` | :6308-6310 |
| font | `px(TOOL_TEXT_SIZE = 12)`, `theme.text_faint` | :6311-6312 |
| hover | `theme.text_muted` (only when not loading) | :6317 |

**`ChipAffordance`** (transcript.rs:1844) — "what an open chip's
`BLOB_AFFORDANCE_HEIGHT` row offers: a lazy sidecar fetch ('Show full
output/diff'). **One slot**, so the analytic height sums stay a single `is_some`
check." Fields: `blob_ref`, `label`.

Label selection (:5913-5959), candidates in order `(diff_ref, "diff", None)`
then `(output_ref, "output", output_bytes)`:

| Fetch state | Label |
|---|---|
| not fetched, bytes known | `"Show full {what} ({N B\|N KB})"` |
| not fetched, bytes unknown | `"Show full {what}"` |
| `Loading` | `"Loading full {what}…"` |
| `Failed` | `"Couldn't load full {what} — tap to retry"` |
| `Ready` and currently shown | skipped — the next candidate is offered instead |
| `Ready` and not shown | `"Show full {what}"` (a no-fetch recency toggle) |

`format_kb(bytes)` (:1880): `< 1024` → `"{bytes} B"`, else
`"{ceil(bytes/1024)} KB"`.

Click → `spawn_blob_fetch(blob_ref)` (:4319): bumps the recency counter FIRST
(so clicking an already-Ready ref just re-shows it), then calls
`roboco_rpc::methods::FETCH_TOOL_BLOB` with `{"blobRef": …}` and a **20s**
timeout. `is_diff = blob_ref.ends_with(".diff")`. On success the reply's `text`
goes through `blob_detail` (diff JSON → `ToolDiff` → `tool_detail`; output →
lines trimmed of trailing blanks, capped at `FULL_OUTPUT_MAX_LINES = 400` with a
counted tail).

Which upgrade shows, when both a diff and an output are fetched: the one with the
highest `blob_fetch_order` (:5890-5899).

**`BlobFetch` state / retry ladder** (transcript.rs:2731) — *not covered by
research 02; transcribed from the Rust for this ticket.* "One sidecar blob
fetch's lifecycle":

| State | Meaning |
|---|---|
| `Loading(Task)` | the RPC is in flight (20s timeout) |
| `Failed` | "failed with the affordance re-armed as a retry" |
| `Ready(ToolDetail)` | the upgraded detail |

There is **no automatic backoff ladder** for blob fetches: failure re-arms the
affordance and the *user* retries by clicking (the `"Couldn't load full {what} —
tap to retry"` label). Do not invent one. (The 2s→15s ladder mentioned in
research §7 q8 belongs to attachment chunk reads — ticket 17.)

---

### 2.2 `detail_body` (:6688)

Root: `w_full min_w_0 flex flex_col overflow_hidden`.

**`ToolDetail::Diff`** — delegates to
`changes::render_file_body_with_syntax(file, highlights, theme)` — the real diff
component: hunk headers, dual line-number gutters, accent bars, row washes,
syntax runs. Height = `changes::body_height(file)` (per-line `DIFF_LINE_HEIGHT` =
21). **No comment layer** (an inline tool diff is a record, not a review
surface). On web: mount the file-body renderer ticket 22 lands in
`components/diff-view.tsx` with comments disabled, and use its `bodyHeight` for
the analytic sum.

**`ToolDetail::Stats`** — `py(6) font_family(theme.font_mono) text_size(12)`;
one row per stat: `h(OUTPUT_LINE_HEIGHT = 18) w_full min_w_0 flex items_center
gap(8)` with a `size(14) flex_none` file-type icon, a `min_w_0 flex_1 truncate
text_color(theme.text_faint)` path, then `flex_none theme.success`
`"+{additions}"` and `flex_none theme.danger` `"−{deletions}"` (U+2212 minus).

**`ToolDetail::Output`** — `py(6) font_family(theme.font_mono) text_size(12)`;
one row per line: `h(18) w_full min_w_0 flex items_center
text_color(theme.text_faint)` wrapping a `w_full min_w_0 truncate` line (so
output lines **TRUNCATE, they do not wrap**). Followed by `more_lines_row` when
`truncated_by > 0`.

**`ToolDetail::Thought`** — `py(6) text_size(12)` (**NO mono family** — thoughts
use the flattened styled runs). One row per line: `h(18) w_full min_w_0 flex
items_center`; a blank line renders the empty row (the block separator).
Non-blank lines are `w_full min_w_0 truncate` around the styled runs from
`thought_line_text`.

**`more_lines_row`** (:6799): `h(18) flex items_center text_size(12)
text_color(theme.text_faint)`, text `"… {truncated_by} more lines"` (U+2026
horizontal ellipsis, **single char**).

**`thought_line_text`** (:6812) — per-run styling:

| Style bit | Effect |
|---|---|
| `code` | font `theme.font_mono`, else `theme.font_sans_fixed` |
| `bold` | `FontWeight::SEMIBOLD` |
| `italic` | `FontStyle::Italic` |
| `link` | underline, color `theme.text_faint`, thickness 1px, not wavy — **NOT clickable** |
| `strikethrough` | thickness 1px, color `theme.text_faint` |

All runs are `theme.text_faint`. Returns nothing when the flattened text is
whitespace-only (→ blank separator row).

---

### 2.3 `subagent_chip` (:7392)

`h(CHIP_HEIGHT = 38) w_full flex_none flex flex_row items_center`. When the
group has a rail, a plain vertical guide line precedes the card: `ml(12) h_full
w(1) flex_none bg(ink(0.08))` (:7409-7417) — **NOT the drawn tree**.

Card: `ml(12)` (rail only), `h(CHIP_CARD_HEIGHT = 30) min_w_0 flex_1 flex
items_center overflow_hidden rounded(9) border_1 border_color(hairline(0.07))
bg(ink(0.03)) cursor_pointer`, hover `bg(ink(0.05))`. **The WHOLE card is the
click target**; the header carries `ChipTrail::OpenArrow`.

Click emits `TranscriptEvent::OpenSubagent { chat_id, doc_id, title, frozen }`
(:6185-6192). `frozen = status ∈ {Done, Failed}` — a frozen subagent tries the
`{chat_id}/{doc_id}` blob snapshot before watching the live doc. The Rust event
doc (transcript.rs:2740) says it plainly: "open the subagent's transcript as a
**right-pane tab**".

`subagent_tab_title(call)` (:7188): candidates in order — the tool name, then
`input.description`, then `input.prompt`; each run through `strip_spawn_prefix`
(drops a leading `"agent"`/`"task"` at a real word boundary, with its `:` and
spacing; `"Taskmaster"` is preserved; a bare `"Agent"` strips to `""`) then
`title_line(text, SUBAGENT_TITLE_MAX = 40)` (first non-blank line, trimmed,
capped with a `…`). Fallback `"Subagent"`.

**Agent-ness predicates** — `is_agent_call(call)` (an `Unknown` call whose name
is `"Agent"` or starts `"Agent: "`, plus the spawn-shaped MCP/unknown calls the
web's `isSubagentSpawn` already recognizes), `is_agent_tool(item)` (the item's
call is an agent call **or** it carries a `subagent_ref`), and
`is_spawn_link(tool)` (an agent tool that actually links to a spawned doc — the
ones whose detail/invocation are suppressed). `tool_group_collapses(tools)`
(:380) = the group contains at least one non-agent tool.

**Web surface.** The subagent opens as a right-pane tab (ticket 07 provides the
multi-instance tab host; register a `subagent` instance carrying `chatId`,
`docId`, `title`, `frozen`). The tab body is the transcript component from
ticket 18 in its **subagent** configuration: top-aligned, its own edge fade
(`TRANSCRIPT_FADE_BAND` 24, top only, gated on `max_offset − distance_from_bottom
> 1.0`), no rail, no echoes, no own-turn runway, 16px first-row gap.
`components/subagent-dialog.tsx` and every `.subagent-dialog*` CSS rule are
**INVENTED — delete them**, do not port their sizing.

---

## 3. Pure logic to port

### 3.1 `ToolItem` / `ToolDetail`

`ToolItem` carries: the `call`, `is_error`, `resolved`, `detail`
(`tool_detail(output, diff, diff_stats)`), `invocation` (`call_block(call)`),
`output_ref`/`diff_ref`/`output_bytes`, `subagent_ref`/`status`/tail, and
`is_thought`. `ToolDetail` is one of `Output { lines, truncated_by }`,
`Diff { file }`, `Stats { stats }`, `Thought { lines, truncated_by }`.
(`lib/transcript.ts:229-256` already defines both — extend with `diff`.)

### 3.2 `tool_detail(output, diff, diff_stats)` (:757)

Precedence: inline `diff` wins (→ `ToolDetail::Diff`, hunks built by
`diff_to_file`, truncated to `DIFF_DETAIL_MAX_LINES = 600` lines) → then
non-empty `diff_stats` (→ `ToolDetail::Stats`) → then `output` (→
`ToolDetail::Output`: split on lines, trailing blanks popped, `None` when empty,
capped at 24 with a counted tail).

`diff_to_file` (:890) — `similar::TextDiff::from_lines(old, new)` with
`grouped_ops(3)` (3 lines of context); per group a header
`"@@ -{old_start+1},{old_len} +{new_start+1},{new_len} @@"`, per change a
`DiffLine { kind, old_no (1-based), new_no, text }` with the trailing `\n`
stripped; `status = Added` when `old_text` is `None`, else `Modified`.
Test: `tool_diff_builds_real_hunks_with_context_and_numbers`.

`blob_detail(text, is_diff)` — diff blobs parse the `ToolDiff` JSON through the
same pipeline as inline diffs; output blobs render (near-)uncapped: lines
trimmed of trailing blanks, capped at `FULL_OUTPUT_MAX_LINES = 400` with a
counted tail ("a defensive ceiling, not a doc cap — the harness bounds outputs at
4KiB, so this is rarely reached", transcript.rs:1849-1851).

### 3.3 `call_block(call)` → the full-invocation `ToolDetail::Output` (:825)

Text source per kind: `Exec` → the whole command; `ReadFile` → path;
`WriteFile` → `"{path}\n{content}"` (or just path); `EditFile` → path;
`ApplyPatch` → path or `"workspace"`; `Search` → `"{pattern} in {path}"` or
pattern; `Glob` → pattern; `WebFetch` → `"{url}\n{prompt}"` or url;
`WebSearch` → query; `Todo` → one line per item, `"[x] {text}"` / `"[ ] {text}"`;
`Mcp` → `"{server} · {tool}\n{pretty JSON input}"`; `Unknown` →
`"{name}\n{pretty JSON input}"`.

Then: split on `\n`, each line soft-wrapped at `CALL_WRAP_COLS = 80` chars
(`wrap_cols` chunks by CHARACTER count, not measurement — **heights must stay
analytic**), trailing blank lines popped, `None` when empty, otherwise truncated
to `OUTPUT_DETAIL_MAX_LINES = 24` with `truncated_by` recorded.
Test: `call_block_carries_the_full_invocation`.

### 3.4 `tool_chip_content(call)` → `(label, one-line detail)`

| Call | Label | Detail |
|---|---|---|
| `Exec{command}` | `"Run"` | command |
| `ReadFile{path}` | `"Read"` | path |
| `WriteFile{path}` | `"Write"` | path |
| `EditFile{path}` | `"Edit"` | path |
| `ApplyPatch{path}` | `"Patch"` | path or `"workspace"` |
| `Search{pattern,path}` | `"Search"` | `"{pattern} in {path}"` or pattern |
| `Glob{pattern}` | `"Glob"` | pattern |
| `WebFetch{url}` | `"Fetch"` | url |
| `WebSearch{query}` | `"Web"` | query |
| `Todo{items}` | `"Todo"` | `"{done}/{total} done"` |
| `Mcp{server,tool}` | `"MCP"` | `"{server} · {tool}"` |
| `Unknown{name}` starting `"Agent: "` | `"Agent"` | the description |
| `Unknown{name} == "Agent"` | `"Agent"` | `""` |
| `Unknown{name}` otherwise | `"Tool"` | name |

The detail always passes through `single_line(text)` =
`text.split_whitespace().join(" ")` — ALL whitespace runs (including newlines)
collapse to single spaces. Tests: `tool_chip_labels_per_kind`,
`file_action_badges_show_only_the_file_name`,
`multiline_command_flattens_to_one_chip_line`,
`single_line_collapses_all_whitespace_runs`.

`file_badge_name(path)` — the final path component, accepting `/` **and** `\`.

### 3.5 `tool_icon_path(call)` (:6656) → `@roboco/icons` names

| Call | Icon asset | `@roboco/icons` name |
|---|---|---|
| `Exec` | `terminal` | `terminal` |
| `ReadFile`, `ApplyPatch` | `document` | `document` |
| `WriteFile` | `document-add` | `documentAdd` |
| `EditFile` | `pen` | `pen` |
| `Search` | `magnifer` | `magnifer` |
| `Glob` | `folder-with-files` | `folderWithFiles` |
| `WebFetch`, `WebSearch` | `global` | `global` |
| `Todo` | `checklist` | `checklist` |
| any subagent-spawn call | `bot` | `bot` |
| `Unknown{name == "Wait for agents"}` | `bot` | `bot` |
| `Mcp`, other `Unknown` | `widget` | `widget` |

Thought chips override this with `chat-round-line` (`chatRoundLine`) both on the
rail and in the spawn-card icon tile.

### 3.6 Tool group summary (`proto::view::tool_group_summary` + transcript wrapper)

Counting rules over `(call, is_error)` pairs:
`Exec` → commands; `WriteFile`/`EditFile`/`ApplyPatch` → a de-duplicated `edited`
path list (`ApplyPatch` with no path counts as `"patch"`); `ReadFile` → reads;
`Search`/`Glob`/`WebSearch` → searches; `WebFetch` → fetches; `Todo` → todos;
everything else → other. Every `is_error` increments `failed`.

Segments, in this order, joined with `" · "`:
`"ran N command(s)"`, `"edited N file(s)"`, `"read N file(s)"`,
`"searched N time(s)"`, `"fetched N page(s)"`, `"updated todos"`,
`"called N tool(s)"`; when no segment fired, `"N tool(s)"`; then `"N failed"`
when any failed. Finally the FIRST character is upper-cased.

`transcript::tool_group_summary(tools)` (:1677) wraps it: thought chips are
filtered out of the pairs (they are UI-synthesized) and named separately:

| `base` empty? | thoughts | Result |
|---|---|---|
| — | 0 | `base` |
| yes | 1 | `"Thought process"` |
| yes | n | `"Thought {n} times"` |
| no | 1 | `"Thought · {base}"` |
| no | n | `"Thought {n} times · {base}"` |

Test: `tool_group_summaries`.

### 3.7 `thought_lines(tree)` → `Vec<Vec<InlineRun>>` (:395)

Flatten a thought's markdown into wrapped STYLED lines, so inline markers render
as real styling instead of literal `**`, and every line is one 18px row
(analytic height). One blank separator row between top-level blocks; trailing
blank rows popped.

Per block (`thought_block_lines`, :544):
- **Paragraph** — `wrap_styled_runs(runs, indent)`.
- **Heading** — every run forced `bold = true`, then wrapped. No display sizes
  (an 18px line box cannot host them).
- **CodeBlock** — each source line char-wrapped at
  `max(THOUGHT_WRAP_COLS(96) − indent, 16)`, every chunk styled `code = true`.
- **List** — TIGHT (no blank rows inside). Marker `"{start+ix}. "` for ordered,
  `"• "` for bullets; children rendered at `indent + marker.chars().count()`; an
  empty item still emits its marker row; the item's first line's slot-0 indent
  run is rewritten to `"{indent spaces}{marker}"`.
- **BlockQuote** — children at `indent + 2`, blank row between children; then
  every produced line's `[indent .. indent+2]` slice is REPLACED with `"│ "`
  (replace, not overwrite — nested list markers planted deeper survive).
- **Table** — cells joined with `" · "`, header runs forced bold; no column
  machinery ("a thought is a record, not a layout surface").
- **Rule** — one line containing `"———"` (three U+2014 em dashes).

`wrap_styled_runs` (:452) — budget `max(96 − indent, 16)`; hard breaks (`\n`
inside runs) split into separately-wrapped segments; tokens are maximal
non-whitespace piece lists GLUED across style boundaries (so `**bold**tail` wraps
as one unit); the separator space rides the preceding run; a token longer than
the budget is hard-split at the budget. Every emitted line begins with a slot-0
indent run of `indent` spaces (possibly empty), because list/quote handlers
rewrite it in place.

`thought_item(tree, live)` (:661) — wraps the lines into a
`ToolItem { call: Unknown{ name: "Thought process", input: None }, is_error:
false, resolved: !live, detail: ToolDetail::Thought{..}, is_thought: true, … }`.
Truncation direction depends on liveness: a **live** thought keeps the TAIL
(drain the first `truncated_by` lines, then remove leading blank orphans — the
fresh thinking is the signal); a **settled** thought keeps the head like tool
outputs (truncate to 24).

Tests: `codex_summary_paragraphs_render_as_separate_styled_lines`,
`thought_wrap_is_word_aware_and_bounded`,
`thought_markdown_styles_instead_of_literal_markers`,
`thought_blocks_flatten_structurally`.

### 3.8 Heights (analytic — no measurement)

- `chips_height(n)` (:1803) — `0` when `n == 0`, else
  `CHIPS_TOP_PAD(2) + n·CHIP_HEIGHT(38) + (n−1)·CHIP_GAP(0)`.
  Test: `chips_height_is_analytic`.
- `detail_height(detail)` (:1814) — `DETAIL_SEPARATOR(1) + body` where body is:
  - `Output` / `Thought`: `(lines.len() + (truncated_by > 0)) · OUTPUT_LINE_HEIGHT(18) + OUTPUT_BODY_PAD(12)`
  - `Diff`: `changes::body_height(file)`
  - `Stats`: `stats.len() · 18 + 12`

### 3.9 `tool_fingerprint(tools, auto_open)` (:1051)

FNV-1a over, per tool: the chip label bytes, the detail string LENGTH, the packed
`is_error | resolved<<1` byte, a detail tag byte (`0` none, `1` Output, `2` Diff,
`3` Stats, `4` Thought) plus kind-specific payload (Output: line count,
truncated_by, total byte count; Thought: line count, truncated_by, then EVERY
run's bytes and a packed style byte `bold | italic<<1 | code<<2 | strike<<3 |
link<<4`, `\n` per line; Diff: path, additions, deletions, hunk count; Stats:
each `(path, additions, deletions)`), the invocation's line bytes +
truncated_by, a packed `output_ref.is_some() | diff_ref.is_some()<<1` byte, a
packed `subagent_ref.is_some() | status<<1` byte (`0` none, `1` Running, `2`
Done, `3` Failed), and the subagent tail bytes. Finally the `auto_open` byte.
(`lib/transcript.ts:910` already implements this — extend it for the `Diff`
variant this ticket adds.)

### 3.10 Motion helpers (`lib/tool-motion.ts`)

- `toolDisclosureProgress(open, fold, now)` — §2.1.2.
- `toolRowRevealProgress(start, now, reduceMotion)` — §2.1.5.
- `toolConnectorRevealProgress(start, now, reduceMotion)` — `EASE_OUT_QUINT` over
  480ms.
- `toolConnectorParts(p, hasPredecessor)` → `(incoming, branch)` — §2.1.6.
- `toolConnectorContinuation(nextP)` = `clamp(nextP / 0.45, 0, 1)`.
- `toolTitleShimmerAmount(x, phase)` and `toolTitleShimmerPhase(elapsed)` —
  §2.1.3.
- `activityBranchPoints(progress)` — §2.1.7, with the arc-length cut.

Desktop tests to mirror: `connector_intersection_is_tessellated_only_once`,
`tool_branch_reveal_tracks_distance_through_the_bend`.

---

## 4. Gaps this ticket closes

Copied verbatim from research 02 §5, filtered to this ticket.

| # | Item | Kind | Desktop value | Web value | Fix |
|---|---|---|---|---|---|
| 5 | Activity rail / tree connectors | **MISSING** | `activity_rail` canvas ribbon, `ACTIVITY_*` geometry, `activity_branch_points` arc-length reveal | Chips are flat rows with a text glyph | Port as SVG paths inside a 48px gutter (`ACTIVITY_TRUNK_X 12.5`, `ACTIVITY_BEND_RADIUS 6`, `ACTIVITY_BRANCH_END_X 28`, icon at `left 32`, `size 16`) |
| 6 | Tool glyphs | **INVENTED** | SVG assets: `terminal`, `document`, `document-add`, `pen`, `magnifer`, `folder-with-files`, `global`, `checklist`, `bot`, `widget`, `chat-round-line` at 16px (rail) / 12px (tile) | Unicode characters `❯ ≡ ✎ ⌕ ◍ ☑ ⬡ ⚙ ⧉ ◌` (transcript.tsx:1005-1037) | Ship the icon set; map per `tool_icon_path` |
| 20 | Tool group header | **WRONG VALUE** | `h(26) gap(6) pr(4)`, chevron in a `w(22) h(18)` slot at `left(5.5) top(2) size(14)`, rotation `−90°·(1−p)` over `TOOL_FOLD` 140ms | `.tool-group-header { min-height 26px; padding 0 2px }`, a `▾` char at 9px with `--rb-motion-chevron` (200ms) | Use a 14px icon in the 22px slot; drive rotation with the 140ms `TOOL_FOLD`, not `CHEVRON` |
| 21 | Group title shimmer | **WRONG VALUE** | 3400ms, highlight half-width 0.36 of the title, copies 3 title-widths apart, mixes `text_muted`→`text` | `background: linear-gradient(100deg, muted 42%, text 50%, muted 58%)`, `background-size: 280%`, 3400ms linear, `140% → −140%` (app.css:3760) | Close enough in duration; retune stops to a 36% shoulder and a 300% repeat to match `tool_title_shimmer_amount` |
| 22 | Shimmer gating | **WRONG BEHAVIOR** | `active = collapses && auto_open`; the clock starts with the group (`shimmer_started_at`) | `active = autoOpen && streaming && tools.some(t => !t.resolved)` (transcript.tsx:798) | Drop the `!resolved` condition; anchor the animation start per group |
| 23 | Chip row heights | **WRONG VALUE** | rail rows `TOOL_TREE_ROW_HEIGHT 32`, cards `CHIP_HEIGHT 38`, inner card `CHIP_CARD_HEIGHT 30` / header 28 | `.tool-chip-head { min-height: 30px }` only | Set the three heights and the `my((row − 30)/2)` centering |
| 24 | Chip arrival stagger | **WRONG BEHAVIOR** | staggered by ARRIVAL index within the group (`old_count..len`), 90ms first-row delay only for a NEW group, cleared on replay/attach | `animationDelay: 90 + ix*65` for EVERY chip whenever `active` (transcript.tsx:886) | Track per-group reveal starts; replayed rows must not animate |
| 25 | Connector reveal | **MISSING** | `TOOL_CONNECTOR_REVEAL` 480ms `EASE_OUT_QUINT`, phase split `(0.45,0.72,0.68)` / `(0,0.62,0.58)`, continuation driven by the NEXT row | absent | Port with the rail |
| 26 | Row reveal shape | **WRONG VALUE** | height clip `h(height·progress)` on the row; the 4px lift + opacity apply to the CONTENT only | `@keyframes rb-chip-reveal { opacity 0→1, translateY 4px→0 }` on the whole chip (app.css:3800) | Clip the height separately so the connector keeps contrast |
| 27 | Group fold tween | **WRONG VALUE** | explicit `h(body_height)` lerped over `TOOL_FOLD` 140ms `EASE_OUT`, from the last RENDERED height | `grid-template-rows: 0fr → 1fr` over 140ms ease-out (app.css:3784) | Acceptable shape; ensure the from-height is the rendered one so an interrupted tween does not jump |
| 28 | Chip detail fold | **MISSING** | per-chip `tool_details` `FoldState` with its own 140ms height tween and `FOLD_TWEEN_WINDOW` 400ms mount retention | `{open && …}` hard mount/unmount (transcript.tsx:922) | Add the height tween and keep the body mounted through the close |
| 29 | Chip default-open rule | **MATCHES** | `tool.is_thought && !tool.resolved` | same (transcript.tsx:853) | — |
| 30 | Chip expandability | **WRONG BEHAVIOR** | EVERY non-spawn chip expands (the invocation block always exists) | `tool.invocation !== null \|\| tool.detail !== null \|\| tool.outputRef !== null \|\| tool.isThought` | Same in practice, but spawn links must be excluded explicitly |
| 31 | Chip icon tile | **MISSING** | spawn cards get an 18px `rounded(5) ink(0.08)` tile with a 12px icon; rail rows get NO tile (the icon is on the rail) | every chip renders a 16px glyph span inline | Split the two treatments |
| 32 | File-action badge | **MISSING** | frosted badge: `h(22) rounded(5) bg(ink(0.06)) pl(1) pr(6) gap(6)` with a 20px `rounded(4)` file-icon well and the basename only | plain truncating path text | Port `file_badge_name` + the badge, with `file_icons::well_bg` |
| 33 | Trailing tile hover reveal | **MISSING** | on rail rows the chevron tile is `opacity 0` until `group-hover` | chevron always visible (app.css:3863) | Hide until the chip row is hovered |
| 34 | Chevron icons | **WRONG VALUE** | `alt-arrow-down` (open) / `alt-arrow-right` (closed), 12px, `text_faint` | `▾` char rotated −90° | Use the icon pair |
| 35 | `.tool-chip-label` weight | **WRONG VALUE** | `MEDIUM` only for spawn cards; rail rows are normal weight, `text_muted` | `font-weight: 600` always (app.css:3846) | Split by genus |
| 36 | `.tool-chip-detail` font | **WRONG VALUE** | inherits the chip's `font_sans_fixed` at 12px; `theme.text_muted` (rail) / `text.opacity(0.85)` (card) | Geist Mono at 11.5px, `--rb-text-faint` (app.css:3852) | 12px sans-fixed, correct color per genus |
| 37 | Subagent status | **INVENTED** | a `mini_glyph_spinner` (cell 2.0) while Running; Done is the ordinary quiet chip; Failed takes the danger tint. NO status words, NO dots | coloured `.subagent-dot` for running/done/failed (transcript.tsx:907-912, app.css:4010) | Remove the dots; use `GlyphSpinner size≈8` for running and the danger tint for failed |
| 38 | `.chip-pending` dot | **INVENTED** | no per-chip pending dot exists on the desktop | 6px pulsing `--rb-activity` dot on every unresolved non-spawn chip | Remove |
| 39 | `.chip-error-mark` "failed" | **INVENTED** | no per-chip "failed" text; failure shows as the danger tint plus the summary's `"· N failed"` | literal `failed` text node (transcript.tsx:914) | Remove |
| 40 | Subagent model label | **WRONG VALUE** | bare `flex_none h(18) text-11 theme.text_faint` text (explicitly NOT a pill) | uppercase, letter-spaced, bordered pill (app.css:3875) | Make it bare faint text, no border/transform |
| 41 | Spawn chip interaction | **WRONG BEHAVIOR** | the WHOLE card is the open-subagent click; the trailing tile is `arrow-up-right`; no accordion at all | a wrapper div is clickable but the inner accordion still expands | Suppress `detail`/`invocation` for spawn links; add the open-arrow tile |
| 42 | Spawn guide line | **MISSING** | `ml(12) h_full w(1) bg(ink(0.08))` before a spawn card inside a mixed group | absent | Add |
| 43 | Subagent surface | **WRONG BEHAVIOR** | a right-pane TAB (`TranscriptEvent::OpenSubagent` with `chat_id`, `doc_id`, `title`, `frozen`), `ListAlignment::Top`, its own edge fade, no rail, no echoes | a modal dialog `w min(56rem) h min(44rem,85vh)` (subagent-dialog.tsx) | Move to the right pane; pass `frozen` so a finished subagent reads its blob |
| 44 | Subagent tab title | **MISSING** | `subagent_tab_title` — strip `Agent:`/`Task:`, first line, 40 chars + `…`, fallbacks `description` → `prompt` → `"Subagent"` | dialog header is the literal string `"Subagent"` | Port |
| 45 | Blob affordance | **WRONG VALUE** | `h(24) text-12 theme.text_faint`, hover `text_muted`, labels `"Show full diff"` / `"Show full output (12 KB)"` / `"Loading full {what}…"` / `"Couldn't load full {what} — tap to retry"` | `--rb-accent` link, underline on hover, only handles `output`, notes read `"Loading full output…"` / `"Could not load the full output."` (transcript.tsx:964-969) | Add the diff ref, the recency rule, and the exact strings |
| 46 | `format_kb` | **WRONG VALUE** | `< 1024` → `"{n} B"`, else `"{ceil(n/1024)} KB"` | `(bytes/1024).toFixed(bytes < 10240 ? 1 : 0)` — produces `"1.5 KB"` (transcript.tsx:998) | Use `Math.ceil` with no decimals |
| 47 | Diff detail rendering | **MISSING** | `changes::render_file_body_with_syntax` — real hunks, dual gutters, accent bars, syntax runs; `ToolDetail::Diff` is built from inline diffs | the web never builds `ToolDetail::Diff` from a tool part | Port `diff_to_file` + the changes body |
| 48 | Detail line truncation | **WRONG BEHAVIOR** | output/thought lines TRUNCATE (`truncate`, one 18px row each) — heights must stay analytic | `white-space: pre-wrap; overflow-wrap: anywhere` (app.css:3892, 3913) | Truncate with ellipsis, fixed 18px rows |
| 49 | `"… N more lines"` | **WRONG VALUE** | `"… {n} more lines"` (U+2026) | `". {n} more lines"` — a literal period (transcript.tsx:937, 944) | Use the ellipsis character |
| 50 | Thought detail colors | **WRONG VALUE** | all runs `theme.text_faint`; bold = SEMIBOLD; code = mono; links underlined but NOT clickable | `.tool-thought { color: --rb-text-muted }`, `StyledRun` renders real `<a>` links | Faint, and render links as underlined spans |
| 51 | Detail body separators | **WRONG VALUE** | `h(1)` hairline `hairline(0.06)` only for non-rail cards; rail rows get whitespace | `.tool-chip-body { border-left: 1px solid --rb-border; margin-left 12px; padding-left 12px }` (app.css:3886) | Replace the left rail border with the desktop separators |
| 52 | Stats row | **WRONG VALUE** | `h(18) gap(8)` with a 14px file-type icon, `text_faint` path, `theme.success` / `theme.danger` counts | no icon, `--rb-text-muted` path, `--rb-diff-add/-delete` (app.css:3925) | Add the icon; use success/danger |
| 78 | Reduced motion | **MISSING** | every tween checks `motion::reduced_motion(cx)`: no veil, no reveal, no shimmer, no fold tween, snap scrolls | only the stick controller checks `prefers-reduced-motion` | Gate the veil, chip reveal, shimmer, folds and the glide — *this ticket's share: chip/connector reveal, the title shimmer, the group and chip fold tweens* |

---

## 5. Do not

- Do not keep `.chip-pending` / `@keyframes rb-chip-pulse`, the literal
  `failed` text node (`.chip-error-mark`), or `.subagent-dot*` — all INVENTED
  (§5 rows 37–39). Failure is the danger tint plus the summary's `"· N failed"`.
- Do not keep `components/subagent-dialog.tsx` or `.subagent-dialog*`. The
  subagent opens as a right-pane tab (§5 row 43).
- Do not color the group header red when children failed — the desktop
  deliberately does not (:6124-6125).
- Do not let a spawn chip expand an accordion; suppress its `detail` and
  `invocation` (§2.1.10).
- Do not render a comment layer inside the inline tool diff — ticket 23 owns
  review comments, and an inline tool diff is a record, not a review surface.
- Do not re-implement the diff body: mount ticket 22's renderer.
- Do not build the row shell, top gaps, hover strip or working trailer (18); the
  rail, badges or loaders (20); markdown blocks (21); the right-pane tab host
  (07).
- Do not port desktop-only mechanics: `ContentMask`-clipped shimmer strips (use
  CSS `background-clip: text`), `frost::frosted` scene layering (the blur itself
  ports via `backdrop-filter`), `gpui::deferred`/`anchored`, `motion::pulse_lease`,
  `RenderCache`.
- Do not invent a retry ladder for blob fetches — failure re-arms a manual retry.

---

## 6. Acceptance

- [ ] A group of ordinary tools renders as 32px rail rows with the drawn trunk +
      bend + branch in a 48px gutter, a 16px tool icon at x=32, and the label /
      detail / hover-revealed chevron at the right heights (30px card inside a
      32px row).
- [ ] The group header is 26px with a 14px chevron in a 22px slot at (5.5, 2)
      that rotates −90°→0° over 140ms `EASE_OUT`, and a summary line that
      shimmers over 3400ms only while `collapses && auto_open`.
- [ ] Newly arrived steps stagger in: 90ms first-row delay for a new group, 65ms
      per arrival, 360ms height clip on the row, 480ms connector draw, 4px lift +
      fade on the content only. Switching chats or scrolling a group back into
      view animates nothing.
- [ ] Clicking a chip expands it in place: invocation block, separator, detail
      block, then a blob affordance row when one is offered; the close tween
      keeps the body mounted for 400ms.
- [ ] Output and thought lines are single 18px truncating rows; `"… N more
      lines"` uses U+2026; stats rows show a file icon, a faint path, a success
      `+N` and a danger `−N` (U+2212).
- [ ] A tool with an inline diff renders the Changes body renderer at 21px per
      line with no comment layer.
- [ ] `"Show full output (12 KB)"` / `"Show full diff"` /
      `"Loading full output…"` / `"Couldn't load full output — tap to retry"`
      render verbatim; `format_kb` produces `"512 B"` and `"12 KB"` (never
      `"1.5 KB"`); a click fetches `FETCH_TOOL_BLOB` with a 20s timeout and a
      failure re-arms the row.
- [ ] A spawn chip is a 38px row with a 1px guide line, a 30px bordered card
      whose whole surface opens a subagent right-pane tab titled by
      `subagent_tab_title`, an `arrow-up-right` tile, a bare faint model label,
      and a `GlyphSpinner` while Running.
- [ ] Under `prefers-reduced-motion: reduce` the reveal, shimmer and both folds
      snap.
- [ ] Unit tests in `web/packages/app/tests/transcript-model.test.ts`:
      `tool_group_summaries`; `tool_chip_labels_per_kind`;
      `file_action_badges_show_only_the_file_name`;
      `multiline_command_flattens_to_one_chip_line`;
      `call_block_carries_the_full_invocation`;
      `tool_diff_builds_real_hunks_with_context_and_numbers`;
      `chips_height_is_analytic`;
      `thought_wrap_is_word_aware_and_bounded`;
      `thought_markdown_styles_instead_of_literal_markers`;
      `thought_blocks_flatten_structurally`;
      `codex_summary_paragraphs_render_as_separate_styled_lines`;
      `connector_intersection_is_tessellated_only_once`;
      `tool_branch_reveal_tracks_distance_through_the_bend`;
      plus `subagent_tab_title` fallback cases and `formatKb`.
- [ ] Screenshot pair, desktop vs web, states: (a) a settled collapsed group;
      (b) the same group expanded showing the rail for 4+ steps; (c) one chip
      open with an output detail and a blob affordance; (d) one chip open with a
      diff detail; (e) a streaming group mid-shimmer with a live thought chip
      open; (f) a mixed group with a spawn card and its guide line; (g) a failed
      tool (danger tint, no "failed" word).
- [ ] `pnpm -r build` green; `pnpm --filter @roboco/app test` green.
- [ ] No new literal hex/px where a `--rb-*` token exists.

## Comments

(empty; appended during implementation)
