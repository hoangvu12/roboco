# 20 — Rail, badges, loaders

**What to build:** The three small surfaces that hang off the transcript. A
**message rail**: a fixed-footprint minimap of at most 12 ticks down the left
edge, one per prompt (bucketed when there are more), that highlights the prompt
you are reading, shows a preview card on hover, and glides the list to that
prompt on click — hidden below 768px of transcript-container width. **Badges**:
the pills above a user bubble for context the prompt folded in as text (today,
review comments), with a hover card listing each comment's location and body.
And the **loaders**: every spinner rebuilt to the desktop's geometry, including
two shipped bugs in the matrix spinner, plus the context-usage ring's real
tooltip.

**Blocked by:** 18 (Transcript rows).

**Status:** done

**Research:** `../../web-client/research/02-transcript.md` §3.0, §3.15, §3.17,
§3.18, §3.20, §4.7, §4.18, §5 rows 4, 19, 59, 63–68.

**Desktop reference (for lookups only):** `crates/ui/src/rail.rs::render_rail`
(:411), `::rail_visible` (:23), `::scroll_to_row` (:251), `::GlideTimeline`
(:208); `crates/ui/src/badges.rs::render` (:67), `::split` (:44),
`::EXTRACTORS` (:40), `crates/ui/src/comments.rs::extract_badge` (:158-189),
`::parse_bullets` (:190-238); `crates/ui/src/context_usage.rs::render` (:9),
`::details`; `crates/ui/src/loaders.rs` (`roboco_mark_loader` :30,
`roboco_loader` :74, `gradient_spinner` :115, `mini_glyph_spinner` :152,
`mini_spinner_cells` :243, `upload_progress_ring` :283, `splash_overlay` :341);
`crates/proto/src/motion.rs` (`MINI_RING` :38-43, `MATRIX_SIDE` :23).

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/components/message-rail.tsx` | new | `MessageRail`, `RailTick`, `RailPreviewCard` |
| `web/packages/app/src/components/badges.tsx` | new | `MessageBadges`, `BadgePill`, `BadgeCard` |
| `web/packages/app/src/lib/rail.ts` | new | `railVisible`, `railCapacity`, `railSlots`, `tickBuckets`, `bucketOf`, `activeTick`, `railTicks`, `truncatePreview`, `GlideTimeline` |
| `web/packages/app/src/lib/badges.ts` | new | `MessageBadge`, `BadgeDetail`, `Extractor`, `EXTRACTORS`, `splitBadges`, `extractCommentBadge`, `parseBullets`, `chipLabel` |
| `web/packages/app/src/components/glyph-spinner.tsx` | edit | `GlyphSpinner`, `MatrixSpinner`, `matrixPhase`, `MINI_RING`, `MATRIX_SIDE`; new `MonoSpinner`, `RobocoMarkLoader`, `RobocoLoader`, `UploadProgressRing` |
| `web/packages/app/src/components/context-usage.tsx` | edit | `ContextUsageIndicator`, `usageFraction`; new `usageDetails`, `ContextUsageTooltip` |
| `web/packages/app/src/components/transcript.tsx` | edit | mount `MessageRail` inside `.transcript-wrap`; render `MessageBadges` in the user row's badge strip (the strip container is ticket 18's) |
| `web/packages/app/src/lib/transcript.ts` | edit | call `splitBadges(parsed.text)` inside `rowsForEntry` **before** the mention projection; fill `row.badges` |
| `web/packages/app/src/components/stick-controller.ts` | edit | `scrollToRow(row)` — `beginScrollNavigation()` + the 500ms `SCROLL_GLIDE` driven by `GlideTimeline` |
| `web/packages/app/src/styles/app.css` | edit | `.glyph-spinner*`, `.matrix-spinner*`, `.mono-spinner`, `.context-usage`, `.context-usage-track`, `.context-usage-arc`; new `.message-rail`, `.rail-tick`, `.rail-tick-bar`, `.rail-preview`, `.rail-preview-prompt`, `.rail-preview-reply`, `.rail-preview-count`, `.badge-pill`, `.badge-pill-icon`, `.badge-card`, `.badge-card-row`, `.badge-card-bar`, `.badge-card-location`, `.badge-card-tag`, `.badge-card-body`, `.context-usage-card`, `.mark-loader`, `.upload-ring` |
| `web/packages/app/tests/rail.test.ts` | new | rail pure logic |
| `web/packages/app/tests/badges.test.ts` | new | badge extraction |

---

## 1. Context a fresh session needs

- The **MessageRail** sits to the left of the transcript: "a fixed-footprint
  minimap of at most 12 ticks (one per user prompt, bucketed when there are
  more), hidden below 768px of container width." It is an absolute overlay
  inside the transcript root, painted before the lightbox.
- **Badges** are "the pills rendered above a user bubble for context the prompt
  folded in as text." Ticket 18 owns the strip container in the user row
  (`w_full flex flex_row flex_wrap justify_end items_center gap(6) pb(6)`); this
  ticket owns the pill, the hover card, and the extractor plumbing that produces
  them.
- **Loaders**: "all loaders share a self-parking pulse clock; per-cell offsets
  come from pure `motion` functions; reduced motion snaps every cell to rest
  automatically." On web they are CSS animations (which self-park), with the
  per-cell phase expressed as a negative `animation-delay`.
- The context-usage ring lives in the composer footer
  (`components/composer-footer.tsx` mounts `ContextUsageIndicator`), not in the
  transcript, but it is transcript state made visible, so it belongs here.
- Current web state: `components/glyph-spinner.tsx` has `GlyphSpinner`
  (correct) and `MatrixSpinner` (two bugs); `components/context-usage.tsx` has
  the ring (correct geometry) with a `title` attribute instead of a popover;
  there is no rail and no badge code at all.
- Colors are theme roles; the only exception in this ticket is
  `GSPIN_ROW_TINTS`, which is a **fixed** three-color sunrise gradient, not
  accent-derived — ticket 02 should expose it as three tokens
  (e.g. `--rb-gspin-row-0/1/2`) rather than hex in a component.
- Out of this ticket: the row shell and working trailer (18); tool groups (19);
  the attachment thumbnails that *use* `upload_progress_ring` and
  `mini_glyph_spinner` (17 — this ticket ships the loaders, 17 wires them);
  review comments end to end (23 — this ticket ships only the badge chrome and
  the extractor that reads a sent comment block back out of the prompt).

---

## 2. Spec

### 2.1 MessageRail (`rail.rs::render_rail`, :411)

**Constants**

| Const | Value | Source |
|---|---|---|
| `RAIL_MIN_CONTAINER_WIDTH` | 768.0 (48rem) | rail.rs:21 |
| `PREVIEW_PROMPT_CHARS` / `PREVIEW_REPLY_CHARS` | 160 / 200 | rail.rs:28-29 |
| `TICK_SLOT` / `TICK_GAP` / `RAIL_V_MARGIN` | 10.0 / 3.0 / 24.0 | rail.rs:119-122 |
| `MAX_RAIL_TICKS` | 12 | rail.rs:127 |

**Visibility gates**
- `rail_enabled` — the shell sets it from container width:
  `rail_visible(w) = w >= 768` (rail.rs:23).
- Subagent override instances never get a rail
  (`rail_enabled = doc_override.is_none()`, transcript.rs:2835).
- Fewer than **2** mapped ticks → nothing rendered (rail.rs:433).

The gate must key off the **transcript container's** width, not the viewport's
(research §3.15 "Web implication"): use a `ResizeObserver` on
`.transcript-wrap`, or a CSS container query on it.

**Layout**

| Property | Value | Source |
|---|---|---|
| container | `absolute left(16) top_0 bottom_0 w(26) flex flex_col items_start justify_center gap(TICK_GAP = 3)` | rail.rs:471-481 |
| tick hit row | `id(("rail-tick", ix)) relative h(TICK_SLOT = 10) w_full flex items_center cursor_pointer` | rail.rs:540-547 |
| bar | `h(2) w(bar_width) rounded(1)` | rail.rs:556-560 |
| bar width | **20** when hovered, **12** otherwise (only hover grows it) | rail.rs:494 |
| bar color | `theme.text.opacity(0.8)` when active or hovered, else `theme::ink(0.16)` | rail.rs:495-499 |

**Bucketing** — `rail_capacity(h) = max(1, floor((max(h − 2·24, 10) + 3)/(10+3)))`;
`rail_slots(h) = min(capacity, MAX_RAIL_TICKS = 12)`. Pre-layout (viewport height
0) it assumes **600px**. `tick_buckets(n, cap)` returns `cap` ranges
`[k·n/cap, (k+1)·n/cap)`; with `n <= cap` it is the identity (one tick per
prompt).

**Active tick** — `active_tick(tick_rows, top_row)` = the last tick whose row
index `<= top_row`, or the first tick when above all of them. `top_row` is NOT
the raw clip top: the walk advances past every measured row whose top is at or
above the **reading line** `viewport.top + OWN_SEND_TOP_INSET_PX (48) + 0.5`
(unmeasured rows stop the walk) — because the titlebar overlays the list and the
own-turn hold parks the newest prompt exactly at that inset (rail.rs:437-457).

**Hover preview card** (rail.rs:505-539) — `popover::popover_card(theme)
.w(280).p(SPACE_SM = 8).flex.flex_col.gap(6)`, wrapped in
`frost::frosted(12.0, frost::MENU_BLUR, …)`, mounted through
`deferred(anchored().anchor(LeftCenter).snap_to_window_with_margin(8)
.child(div().pl(26).child(card)))`. Children:
- prompt: `text_size(12) text_color(theme.text)`, truncated to
  `PREVIEW_PROMPT_CHARS = 160`;
- reply (when present): `11px`, `theme.text_muted`, truncated to
  `PREVIEW_REPLY_CHARS = 200`;
- when the bucket holds more than one prompt: `10px`,
  `theme.text_muted.opacity(0.7)`, text `"{bucket_len} prompts"`.

The representative prompt of a bucket is the ACTIVE tick when it falls inside the
range, else the range's first tick (rail.rs:486).

`truncate_preview(text, max)` — `single_line` first (whitespace runs, including
newlines, collapse to single spaces), then `max−1` chars + `…` with the cut
right-trimmed.

**Interactions** — hover sets `rail_hover = Some(ix)`; click →
`scroll_to_row(row, cx)` (rail.rs:251): `begin_scroll_navigation()`, then either
a hard `scroll_to` under reduced motion, or a 500ms `SCROLL_GLIDE`
(`EASE_IN_OUT`) driven by `GlideTimeline` at a 16ms cadence (§3.1).

**Data** — `rail_ticks(state.transcript, state.pending_echoes())`: one tick per
user entry in doc order, then un-deduped user echoes; each tick carries
`first_reply_text` = the first non-blank text part of the first assistant entry
AFTER it. Prompt text runs through `attachments::user_message_rail_text` so
image-only sends read as "Attached image(s)".

**Desktop-only: `ROBOCO_SCROLL_TRACE`.** `rail.rs:224`'s `ENABLED` is *not* a
rail visibility gate — it is a `static OnceLock<bool>` inside
`scroll_trace_enabled()` memoizing one developer tracing env knob:

```rust
fn scroll_trace_enabled() -> bool {
    static ENABLED: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
    *ENABLED.get_or_init(|| {
        std::env::var("ROBOCO_SCROLL_TRACE").is_ok_and(|v| !v.is_empty() && v != "0")
    })
}
```

| Property | Value | Source |
|---|---|---|
| Kind | `static OnceLock<bool>` inside a fn — a process-lifetime memo, read once | rail.rs:224 |
| Env var | `ROBOCO_SCROLL_TRACE` | rail.rs:226 |
| True when | the var is set, non-empty, and not the literal `"0"` | rail.rs:226 |
| Effect | `scroll_to_row`'s per-frame glide logs `ms`, `eased`, `here`, `dist` at `warn` level with the message `"scroll-glide"` | rail.rs:266, :328-336 |
| Cost when off | zero — the flag is read once into `trace` before the frame loop and each frame is a single `bool` test | rail.rs:266, :328 |

Same family as `frame_stats_enabled()` (`ROBOCO_FRAME_STATS`) and
`render_cache_disabled()` (`ROBOCO_NO_RENDER_CACHE`). **Do not port any of the
three.** They carry no user-visible behavior, so a web client that omits them is
still 1:1. What the web *does* owe is the width gate above.

---

### 2.2 Badges (`badges.rs::render`, :67)

| Property | Value | Source |
|---|---|---|
| height | `BADGE_HEIGHT` = 24 | badges.rs:58, :74 |
| layout | `flex flex_row items_center gap(6) px(8)` | :75-79 |
| radius | `PILL_RADIUS` = 8 | :60, :80 |
| background | `theme::ink(0.06)` | :81 |
| font | `TEXT_SIZE` = 12, `FontWeight::MEDIUM`, `theme.text_muted` | :62, :82-84 |
| icon | `badge.icon` at `ICON_SIZE` = 12, `theme.text_muted.opacity(0.7)` | :61, :86-88 |
| tooltip delay | `HOVER_DELAY` = 280ms | :64, :99 |

Hover card (`BadgeCard`): `popover_card w(CARD_WIDTH = 320) p(6) flex flex_col
gap(4)`, frosted at `popover::CARD_RADIUS` / `frost::MENU_BLUR`.
`BadgeCard::row` — each detail row: `flex flex_row gap(8) p(8) rounded(6)
bg(ink(0.05))` with a `w(2) rounded(1) bg(theme.solid.opacity(0.35))` accent bar,
then a column `gap(4)` of
- a location line: `font_mono text_size(10) theme.text_faint`, truncating, with
  an optional tag pill `px(4) rounded(3) bg(ink(0.10))`;
- the body: `text_size(12) line_height(16) theme.text`.

#### 2.2.1 `EXTRACTORS` (badges.rs:40) — the registry the web must reproduce

```rust
pub type Extractor = fn(&str) -> Option<(String, MessageBadge)>;   // badges.rs:38
const EXTRACTORS: &[Extractor] = &[crate::comments::extract_badge]; // badges.rs:40
```

| Property | Value | Source |
|---|---|---|
| Length | **1** — `comments::extract_badge` is the only extractor today | badges.rs:40 |
| Signature | `fn(&str) -> Option<(String, MessageBadge)>` — returns the text with this feature's block REMOVED, plus the pill replacing it; `None` when the message carries nothing of that kind | badges.rs:37-38 |
| Application | `split(text)` (badges.rs:44-54) folds over the slice in order: each extractor sees **what the previous ones left behind**, so two features can ride one prompt. Badges are pushed in extractor order | badges.rs:47-52 |
| Call site | `rows_for_entry` → `badges::split(&parsed.text)` — run BEFORE the file-mention projection, so a comment body's own Markdown never lands in the bubble | transcript.rs:1226 |

Porting note: the web needs the same *fold* shape (ordered, each seeing the prior
remainder), not just the single case — the array is the extension point and a
future extractor must not require re-architecting.

#### 2.2.2 The one extractor: `comments::extract_badge` (comments.rs:158-189)

*Trigger.* Matched only as a whole **trailing block**, so a prompt quoting the
header mid-body is left alone (comments.rs:157). It searches for the LAST
occurrence (`rfind`, then `max_by_key(at)`) of either of two markers, each
written as `"\n\n{HEADER}\n"`:

| Marker constant | Verbatim text | Source |
|---|---|---|
| `COMMENT_BLOCK_HEADER` | `"Comments on the diff (each cites the file and line it belongs to; L = line number in the original file, R = in the changed file):"` | comments.rs:123 |
| `REVIEW_COMMENT_BLOCK_HEADER` | `"Review comments (each cites the workspace file and line it belongs to):"` | comments.rs:124-125 |

Which header the composer wrote is decided at send time: `COMMENT_BLOCK_HEADER`
when **every** comment is a diff comment, `REVIEW_COMMENT_BLOCK_HEADER` as soon
as any is a file comment (comments.rs:150-154). The extractor accepts either.

*Validation — all three must hold, else `None`:*
1. the block after the marker is non-empty;
2. **every** line starts with `"- "` (a bullet) or `"  "` (a continuation);
3. `parse_bullets` yields at least one detail.

*Output.* `(text[..at].to_string(), MessageBadge { … })` — i.e. the block and its
two leading newlines are stripped from the bubble text.

| Badge field | Value | Source |
|---|---|---|
| `icon` | `icons::CHAT_ROUND_LINE` (asset `chat-round-line`) | comments.rs:185 |
| `label` | `chip_label(details.len())` → `"1 comment"` when 1, else `"{n} comments"` | comments.rs:186, :247-253 |
| `details` | one `BadgeDetail` per bullet | comments.rs:187 |

*`parse_bullets` (comments.rs:190-238) — bullet → `BadgeDetail`:*
- A line starting `"  "` that is **not** a bullet appends to the previous
  detail's body as `"{body}\n{indented}"` — that is how a multi-line comment
  survives the round trip (`with_comments` writes bodies as
  `body.trim().replace('\n', "\n  ")`, comments.rs:135).
- For a `"- "` bullet, two candidate splits are computed and the **earliest
  wins**:
  - the **side marker** `" (L): "` or `" (R): "` — `side_marker(side) =
    format!(" ({}): ", side.tag())` where `CommentSide::Old → "L"`,
    `CommentSide::New → "R"` (comments.rs:13-19, :127-129). Earliest-marker-wins
    is load-bearing: a body may itself contain `"(L): "`, and matching that would
    swallow the body into the location.
  - `split_file_bullet` (comments.rs:239-246) — the first `": "` whose preceding
    text ends in `:{digits}` (i.e. `"{path}:{line}"` parses as `u32`).
- Resulting detail:

| Bullet form | `location` | `tag` | `body` |
|---|---|---|---|
| `"- src/main.rs:42 (R): early-return here"` | `"src/main.rs:42"` | `Some("R")` | `"early-return here"` |
| `"- src/lib.rs:7 (L): why was this dropped?"` | `"src/lib.rs:7"` | `Some("L")` | `"why was this dropped?"` |
| `"- notes.md:3: plain file comment"` (no side marker, or the file split is earlier) | `"notes.md:3"` | `None` | `"plain file comment"` |

*Location semantics.* `ReviewComment::location()` = `"{cite_path()}:{line}"`,
where `cite_path()` returns `old_path` for an `Old`-side diff comment and `path`
otherwise (comments.rs:103-120) — an `Old`-side line only exists in the
pre-rename file, so citing `path` there would point at a line that never held it.

*Comment-only sends.* When the prompt text is empty, `with_comments` substitutes
`COMMENT_ONLY_TEXT = "Address the review comments below."` (comments.rs:121,
:145-149), so the bubble still has a body under the pill.

Desktop tests covering this (badges.rs:185-252):
`a_plain_message_carries_no_badges`, `a_sent_comment_block_becomes_one_pill`,
`the_card_carries_one_row_per_comment`,
`a_multiline_body_rejoins_its_continuation_lines`,
`a_path_with_a_colon_still_splits_on_the_side_marker`,
`a_comment_only_send_keeps_its_stand_in_body`.

**Scope note.** The comment *feature* (the composer chip, the diff adder, the
cards, the transcript badge wiring) is **ticket 23**. This ticket ships the badge
chrome, `EXTRACTORS`/`split`, and `extract_badge`/`parse_bullets` so a prompt
that already carries a comment block renders its pill correctly.

---

### 2.3 Context-usage ring (`context_usage.rs::render`, :9)

| Property | Value | Source |
|---|---|---|
| container | `id("context-usage") flex_none flex items_center gap(5) h(24) px(6) rounded(6) text_size(11)` | :57-66 |
| hover | `bg(theme::ink(0.05))` | :68 |
| ring canvas | `size(16)` | :53 |
| stroke width | `px(1.8)` | :31 |
| radius | 6 (centered in the 16px box) | :36-37 |
| arc start | 12 o'clock (`−π/2`), clockwise | :33-34 |
| segments | `ceil(64 · fraction)`, min 2 | :30 |
| track | `theme.text_faint.opacity(0.25)`, full circle | :21, :49 |
| arc + label color | `>= 0.9` → `theme.danger`; `>= 0.75` → `theme.warning`; other `Some` → `theme.text_muted`; `None` → `theme.text_faint` | :15-20 |
| label | `format!("{:.0}%", f·100)`, or `"—"` when unknown | :54-56 |

**Tooltip** — a `popover_card` `w(260) p(12) flex flex_col gap(8)`, wrapped in
`frost::frosted(popover::CARD_RADIUS, frost::MENU_BLUR, …)`, subscribed to
`AppState` so it live-updates. Title `"Context window"` (`text-12`, MEDIUM,
`theme.text`); body `text-12 line_height(19) theme.text_muted` with
`details(usage)`:

| Case | String |
|---|---|
| tokens + window > 0 | `"{tokens} / {window} tokens\n{window − tokens} tokens remaining"` |
| tokens only | `"{tokens} tokens used\nContext limit not reported"` |
| window > 0 only | `"{window} token capacity\nWaiting for context usage"` |
| neither | `"Context usage not reported by this harness yet"` |

Replace the `title={`Context used: ${label}`}` attribute
(`context-usage.tsx:35`) with this card (ticket 09's popover primitive is
available; if this ticket lands first, a plain positioned card is acceptable as
long as the strings and box match).

---

### 2.4 Loaders (`loaders.rs`) — all seven

All loaders share a self-parking pulse clock; per-cell offsets come from pure
`motion` functions; reduced motion snaps every cell to rest automatically.

#### 2.4.1 `gradient_spinner(id, theme, cell_px, view, cx)` (:115) — the WorkingIndicator

`MATRIX_SIDE = 3` × 3 grid, `flex flex_col gap(cell_px/2)`, rows
`flex flex_row gap(cell_px/2)`, cells `size(cell_px) rounded(cell_px/2)
bg(tint)`. Row tints `GSPIN_ROW_TINTS = [0xB6D3EF, 0xEDB185, 0xF888A0]` (sunrise
gradient: cool blue top, amber, pink). Per-cell phase

```
d      = (SIDE−1−row) + |col − centre|
centre = (SIDE−1)/2 = 1
max    = SIDE−1+centre = 3
phase  = d / (max + 1)          // denominator 4 — NOT 3
```

Opacity `gspin_opacity(delta + phase, GSPIN_DIM = 0.1)`. Clock is
`pulse_delta_slow(&GRADIENT_SPIN, …)` — **half speed**, i.e. a 1500ms effective
period. The transcript trailer and the boot splash both use `cell_px = 2.5`.

Two web bugs to fix in `components/glyph-spinner.tsx`: `matrixPhase` (:72)
returns `d / max` = `d/3`, and the cells take their tint from the accent glyph
roles (`--rb-glyph-*`, app.css:1108-1120) instead of the fixed sunrise tints.

#### 2.4.2 `mini_glyph_spinner(key, cell_px, palette, view, cx)` (:152)

2 cols × 3 rows. Ring order `RING = [[0,1],[5,2],[4,3]]` (clockwise from
top-left), `RING_LEN = 6`, per-cell `phase = RING[row][col]/6`, opacity
`gspin_opacity(delta + phase, 0.1)` on the **full-speed** `GRADIENT_SPIN`
(750ms) clock. Gaps `cell_px/2`. Cached element footprint
`w(cell_px · 2.5) h(cell_px · 4.0)`. Row tints = `GlyphPalette::rows()` =
`[light, mid, deep]` derived from the accent (`theme.glyph`). Used at cell **2.0**
in the subagent chip, cell **3.0** on a sending thumbnail.

**`ROWS` (loaders.rs:244) — the mini-spinner geometry table.** Four
function-local `const`s in `mini_spinner_cells` (loaders.rs:243-248) define the
entire 2×3 grid. They are local, not module-level, because nothing else may
reshape this grid:

```rust
const COLS: usize = 2;                              // loaders.rs:243
const ROWS: usize = 3;                              // loaders.rs:244
/// Clockwise ring position of each `(row, col)` cell, top-left first:
/// (0,0) → (0,1) → (1,1) → (2,1) → (2,0) → (1,0).
const RING: [[usize; COLS]; ROWS] = [[0, 1], [5, 2], [4, 3]];  // loaders.rs:247
const RING_LEN: f32 = (COLS * ROWS) as f32;         // = 6.0   // loaders.rs:248
```

| Const | Value | Meaning | Source |
|---|---|---|---|
| `COLS` | `2` | grid columns — the glyph is a tall 2-wide mark, not a square matrix | loaders.rs:243 |
| `ROWS` | `3` | grid rows; also indexes `row_tints`, so `ROWS` must equal the palette arity (`[Hsla; 3]` from `GlyphPalette::rows()`) | loaders.rs:244, :255 |
| `RING` | `[[0,1],[5,2],[4,3]]` | each cell's **clockwise ring position**, indexed `RING[row][col]`. Reading the grid in layout order: `(0,0)=0`, `(0,1)=1`, `(1,0)=5`, `(1,1)=2`, `(2,0)=4`, `(2,1)=3` — i.e. the chase goes down the RIGHT column then up the LEFT | loaders.rs:245-247 |
| `RING_LEN` | `COLS * ROWS` = `6.0` | ring circumference in cells. Every cell of a 2×3 grid is on the perimeter, so brightness chases around it with no interior | loaders.rs:248, :42-43 (proto) |

Derived per-cell geometry (loaders.rs:250-268):

| Property | Expression | Value at `cell_px = 2.0` |
|---|---|---|
| column gap | `cell_px / 2` | 1.0 |
| row gap | `cell_px / 2` | 1.0 |
| cell size | `cell_px` (square) | 2.0 |
| cell radius | `cell_px / 2` (fully round) | 1.0 |
| cell phase | `RING[row][col] / RING_LEN` → one of `0, 1/6, 2/6, 3/6, 4/6, 5/6` | — |
| cell opacity | `motion::gspin_opacity(delta + phase, GSPIN_DIM = 0.1)` | — |
| cell tint | `row_tints[row]` | — |
| total footprint | `w = cell_px · 2.5`, `h = cell_px · 4.0` (3 cells + 2 half-gaps vertically; 2 cells + 1 half-gap horizontally) | 5.0 × 8.0 |

The same table is mirrored gpui-free in `roboco_proto::motion` as
`MINI_RING: [[usize; 2]; 3] = [[0,1],[5,2],[4,3]]` and `MINI_RING_LEN: f32 = 6.0`
(proto/motion.rs:38-43) precisely so the web animates the identical chase; the
proto test `the_mini_ring_visits_every_cell_once` asserts the ring is a
permutation of `0..6`. Not to be confused with `MATRIX_SIDE = 3`
(proto/motion.rs:23), the 3×3 `gradient_spinner` grid, whose phase is a
converging wave (`gspin_cell_phase`), not a ring walk.

**Web status.** `glyph-spinner.tsx:21-26` reproduces `MINI_RING` and
`MINI_RING_LEN` correctly and maps phase to `animationDelay: −(ring/6)·750ms`,
which matches. The gaps, cell radius and `2.5 × 4.0` footprint are expressed as
`--rb-cell` with `cell = size/4` (glyph-spinner.tsx:42) — consistent with
`h = cell·4`. **Leave this component's mini path alone**; only the matrix
diverges.

#### 2.4.3 `mini_mono_spinner`

The same 2×3 grid with one caller-supplied tint in all rows
(`.glyph-spinner-mono` / `.mono-spinner` already exist in app.css:1124-1133 —
confirm they use `currentColor`).

#### 2.4.4 `roboco_mark_loader(id, theme, height_px, view, cx)` (:30)

The full logo grid: `w(820·scale) h(height_px)` with `scale = height_px/940`,
**34 cells** from `MARK_CELLS` at `left(x·scale) top(y·scale) size(100·scale)`,
each cell `rounded(16·scale) bg(theme.text)`, `opacity(pulse_opacity(phase))`,
`size(cell · pulse_scale(phase))`, with

```
phase                 = (delta + mark_cell_stagger(x, y)) mod 1
mark_cell_stagger(x,y) = (1 − (820 − x + y)/1660) · MARK_SPREAD(0.55)
```

`ROBOCO_PULSE` = 2400ms; `pulse_opacity` 0.08→1, `pulse_scale` 0.9→1 on a cosine
wave (`0.5 − 0.5·cos(2πφ)`). `MARK_CELLS` is the 34-entry `(x, y)` table in
`loaders.rs`; copy it verbatim into the web component (it is geometry, not
style).

#### 2.4.5 `roboco_loader` (:74)

The same pulse over `ROBOCO_CELLS = 5` cells in a row, `gap(cell/2)`,
`rounded(cell/4)`, `PULSE_STAGGER = 0.15/2.4` per cell.

#### 2.4.6 `upload_progress_ring(percent, diameter)` (:283)

`relative size(diameter) flex items_center justify_center`. Two stroked
polylines at `RING_STROKE = 2.5`, radius `diameter/2 − 2.5`, clockwise from 12
o'clock, `RING_SEGMENTS = 64` full-circle resolution (`max(ceil(64·sweep), 2)`
steps). Track `hsla(0,0,1, 0.22)`, arc `hsla(0,0,1, 0.95)` — a **fixed
white-on-wash palette** (the caller dims the image behind it). Centered label
`text_size(9) font_weight(SEMIBOLD) hsla(0,0,1,0.95)` reading `"{percent}%"`.
Used at diameter 34 by the sending-attachment overlay (**ticket 17 wires it**).

#### 2.4.7 `splash_overlay(theme, fading, …)` (:341)

Full-window `bg(theme.glass()) flex flex_col items_center justify_center
gap(12)`; `gradient_spinner` at cell 2.5 plus `12px
theme.text_muted.opacity(0.7)` text `"Setting up Roboco environment"`. While
fading it plays `SPLASH_OUT` (500ms `EASE` after a 150ms hold).
**Deferred** — whether the web reproduces the 680ms splash crossfade is an open
product question (research 00 §"Open questions"). Do not build it in this
ticket; the entry exists here because its spinner geometry is shared.

---

## 3. Pure logic to port

### 3.1 `GlideTimeline::step(eased)` (rail.rs:208)

```
eased = clamp(eased, eased_prev, 1.0)
denom = 1 − eased_prev
frac  = denom <= 1e-6 ? 1.0 : (eased − eased_prev)/denom
eased_prev = eased
return clamp(frac, 0, 1)
```

Consuming `frac` of the CURRENT remaining distance telescopes to exactly
`start + e(t)·total` when the distance estimate is stable, and continues the same
timeline when the estimate changes mid-flight. Tests:
`glide_timeline_matches_absolute_eased_interpolation`,
`glide_timeline_survives_remaining_distance_reestimate`,
`glide_timeline_step_clamps`, `glide_first_frame_is_gentle`.

`scroll_to_row` drives it with `motion::SCROLL_GLIDE` = 500ms `EASE_IN_OUT` at a
16ms cadence, after `begin_scroll_navigation()`; reduced motion does a hard
`scroll_to(ListOffset { item_ix, 0 })` instead (rail.rs:251-253).

### 3.2 Rail pure logic

- `rail_visible(w)` = `w >= 768`.
- `rail_capacity(h)` = `max(1, floor((max(h − 48, 10) + 3)/13))`.
- `rail_slots(h)` = `min(rail_capacity(h), 12)`.
- `tick_buckets(n, cap)` — `[]` when `n == 0`; `cap' = clamp(cap, 1, n)`; ranges
  `(k·n/cap', (k+1)·n/cap')` for `k in 0..cap'`.
- `bucket_of(buckets, ix)` — index of the range containing `ix`.
- `active_tick(tick_rows, top_row)` — last index with `row <= top_row`, else
  `Some(0)`, `None` when empty.
- `truncate_preview(text, max)` — `single_line`, then `max−1` chars + `…` with
  the cut right-trimmed.

Tests: `capacity_counts_slots_that_fit`, `buckets_are_identity_under_capacity`,
`buckets_partition_evenly_over_capacity`, `bucket_of_maps_ticks_to_their_bucket`,
`ticks_map_user_prompts_with_reply_openings`, `ticks_include_echoes_deduped`,
`tick_without_reply_yet`, `active_tick_tracks_viewport_top`, `rail_width_gate`,
`preview_truncation`.

### 3.3 Badge pure logic

`split(text)` (badges.rs:44-54), `extract_badge` (comments.rs:158-189),
`parse_bullets` (comments.rs:190-238), `chip_label(n)` (comments.rs:247-253),
`side_marker`, `split_file_bullet` — all specified in §2.2. Tests listed there.

### 3.4 Loader math

- `gspin_cell_phase(row, col)` = `((SIDE−1−row) + |col − centre|) / (max + 1)`
  with `centre = (SIDE−1)/2`, `max = SIDE−1+centre`.
- `gspin_opacity(t, dim)` — full, then a linear fall to `dim` over the first 45%
  of the cycle, a hold to 92%, and a fast return (already baked into the
  `.glyph-spinner-cell` keyframes; verify the stops).
- `pulse_opacity(φ)` 0.08→1 and `pulse_scale(φ)` 0.9→1 on `0.5 − 0.5·cos(2πφ)`.
- `mark_cell_stagger(x, y)` = `(1 − (820 − x + y)/1660) · 0.55`.

---

## 4. Gaps this ticket closes

Copied verbatim from research 02 §5, filtered to this ticket.

| # | Item | Kind | Desktop value | Web value | Fix |
|---|---|---|---|---|---|
| 4 | MessageRail | **MISSING** | `rail.rs` — 26px column at `left(16)`, ≤12 ticks, 2px bars 12/20px wide, hover preview card `w(280)` | absent | Port `rail_ticks`, `tick_buckets`, `active_tick`, `truncate_preview`, `scroll_to_row` (500ms `SCROLL_GLIDE` + `GlideTimeline`) |
| 19 | Message badges | **MISSING** | `badges::render` pills (h 24, radius 8, `ink(0.06)`, 12px MEDIUM, hover card `w 320` after 280ms) above the bubble | absent | Port `badges::split` + the pill and hover card |
| 59 | Upload progress ring | **MISSING** | `upload_progress_ring(pct, 34)` over a `hsla(0,0,0, 0.38+0.05·pulse)` scrim, or `mini_glyph_spinner(cell 3.0)` when indeterminate | absent | Port — *this ticket ships the ring component; ticket 17 wires it onto the thumbnail and owns the scrim* |
| 63 | Context ring stroke | **MATCHES** | 16px box, r=6, stroke 1.8, clockwise from 12 o'clock, 64-segment resolution | same via `strokeDasharray` (context-usage.tsx) | — |
| 64 | Context ring track | **WRONG VALUE** | `theme.text_faint.opacity(0.25)` | `color-mix(in srgb, --rb-text-faint 25%, transparent)` | Equivalent |
| 65 | Context ring tooltip | **MISSING** | a `w(260) p(12)` frosted popover titled `"Context window"` with the 4 `details()` strings | a plain `title` attribute reading `"Context used: {label}"` | Port the popover and strings |
| 66 | `GlyphSpinner` period | **WRONG VALUE** | mini spinner runs on `GRADIENT_SPIN` at FULL speed (750ms); the 3×3 matrix runs at HALF speed (1500ms) | mini uses 750ms delays (correct); matrix uses 1500ms (correct) | — MATCHES |
| 67 | `MatrixSpinner` phase denominator | **WRONG VALUE** | `phase = d/(max+1)` = `d/4` | `matrixPhase` returns `d/max` = `d/3` (glyph-spinner.tsx:72) | Divide by `max + 1` |
| 68 | `MatrixSpinner` tints | **WRONG VALUE** | fixed sunrise `[#B6D3EF, #EDB185, #F888A0]` (`GSPIN_ROW_TINTS`) | accent glyph roles `--rb-glyph-*` (app.css:1108-1120) | The matrix must use the fixed sunrise tints; only the MINI spinner uses the accent glyph palette |

---

## 5. Do not

- Do not port `ROBOCO_SCROLL_TRACE` / `ENABLED`, `ROBOCO_FRAME_STATS` or
  `ROBOCO_NO_RENDER_CACHE` — desktop-only instrumentation with no user-visible
  behavior.
- Do not gate the rail on the **viewport** width: the gate is the transcript
  container's width (`>= 768`), and subagent instances never get a rail at all.
- Do not change the mini `GlyphSpinner`'s ring, delays or footprint — it already
  matches (§5 row 66).
- Do not give the matrix spinner accent-derived tints; only the mini spinner uses
  the accent glyph palette (§5 row 68).
- Do not build the splash overlay (deferred, open product question).
- Do not build the review-comment feature (composer chip, diff adder, comment
  cards) — **ticket 23**. This ticket ships only the badge chrome plus the
  extractor that reads a sent block back out.
- Do not wire the upload ring onto attachment thumbnails or build the scrim —
  **ticket 17**.
- Do not touch the working trailer, the row shell or the tool tree — tickets 18
  and 19.
- Do not use `frost::frosted` scene layering; the blur ports as
  `backdrop-filter`, the layering is a CSS stacking context.

---

## 6. Acceptance

- [ ] With ≥2 prompts and a transcript container ≥768px wide, a 26px rail column
      sits at `left: 16px`, vertically centered, with 10px tick slots, 3px gaps,
      2px bars 12px wide (20px on hover), colored `text @ 80%` when active or
      hovered and `ink(0.16)` otherwise. Below 768px, and in a subagent surface,
      it does not render.
- [ ] More than `rail_slots(height)` prompts bucket evenly; the preview card
      shows the bucket's representative prompt, its first reply, and
      `"{n} prompts"` when the bucket holds more than one.
- [ ] The active tick tracks the reading line at `viewport.top + 48 + 0.5`.
- [ ] Clicking a tick glides the list over 500ms `EASE_IN_OUT` via
      `GlideTimeline`; reduced motion jumps.
- [ ] A prompt that carried a review-comment block renders one 24px pill reading
      `"3 comments"` with a 12px `chat-round-line` icon, and the block is gone
      from the bubble text; hovering for 280ms opens a 320px card with one row
      per comment (accent bar, mono location, optional `L`/`R` tag pill, body).
- [ ] The context ring's tooltip is a 260px card titled `"Context window"` with
      the four `details()` strings verbatim; no `title` attribute.
- [ ] The matrix spinner's phase divides by `max + 1` and its three rows use the
      fixed sunrise tints; the mini spinner is unchanged.
- [ ] `RobocoMarkLoader`, `RobocoLoader` and `UploadProgressRing` exist with the
      geometry in §2.4 and are exercised by a story/fixture.
- [ ] Unit tests in `web/packages/app/tests/rail.test.ts`:
      `capacity_counts_slots_that_fit`, `buckets_are_identity_under_capacity`,
      `buckets_partition_evenly_over_capacity`,
      `bucket_of_maps_ticks_to_their_bucket`,
      `ticks_map_user_prompts_with_reply_openings`,
      `ticks_include_echoes_deduped`, `tick_without_reply_yet`,
      `active_tick_tracks_viewport_top`, `rail_width_gate`,
      `preview_truncation`, `glide_timeline_matches_absolute_eased_interpolation`,
      `glide_timeline_survives_remaining_distance_reestimate`,
      `glide_timeline_step_clamps`, `glide_first_frame_is_gentle`.
- [ ] Unit tests in `web/packages/app/tests/badges.test.ts`:
      `a_plain_message_carries_no_badges`,
      `a_sent_comment_block_becomes_one_pill`,
      `the_card_carries_one_row_per_comment`,
      `a_multiline_body_rejoins_its_continuation_lines`,
      `a_path_with_a_colon_still_splits_on_the_side_marker`,
      `a_comment_only_send_keeps_its_stand_in_body`,
      plus `the_mini_ring_visits_every_cell_once` for the loader table.
- [ ] Screenshot pair, desktop vs web, states: (a) transcript with 5 prompts,
      rail visible, third tick active; (b) rail tick hovered with its preview
      card; (c) transcript narrowed below 768px (no rail); (d) a user bubble with
      a `"3 comments"` badge, and the same with its hover card open; (e) the
      context ring at <75%, at 80% (warning) and at 95% (danger) with the tooltip
      open; (f) the working trailer's matrix spinner beside the desktop's.
- [ ] `pnpm -r build` green; `pnpm --filter @roboco/app test` green.
- [ ] No new literal hex/px where a `--rb-*` token exists (the three
      `GSPIN_ROW_TINTS` are the documented exception and become tokens in
      ticket 02).

## Comments

### What landed (2026-09-18)

- **Rail** — `lib/rail.ts` (railVisible/capacity/slots/tickBuckets/bucketOf/
  activeTick/railTicks/truncatePreview/GlideTimeline, constants verbatim from
  rail.rs), `components/message-rail.tsx` (MessageRail/RailTickView/
  RailPreviewCard), mounted inside `.transcript-wrap` outside the subagent
  surface; the width gate reads the wrap via a ResizeObserver (railBox
  state), never the viewport. `StickController.scrollToRow(row)` drives the
  500ms `SCROLL_GLIDE` (EASE_IN_OUT, 16ms cadence, per-frame target re-read
  from the live prefix sums); `beginScrollNavigation` now also yields any
  running glide (the scroll-task slot). The reading-line walk
  (`readingTopRow` in transcript.tsx) advances past measured rows at or above
  `top + OWN_SEND_TOP_INSET_PX + 0.5`, unmeasured rows stop it.
- **Badges** — `lib/badges.ts` (splitBadges fold over the EXTRACTORS
  extension point, extractCommentBadge with both headers + last-occurrence
  matching, parseBullets with earliest-split-wins, chipLabel),
  `components/badges.tsx` (MessageBadges/BadgePill/BadgeCard over
  `PickerCard`'s hover-open shape, 280ms delay); `rowsForEntry` splits
  badges BEFORE the mention projection and the row's `badges` are
  `MessageBadge[]` now.
- **Loaders** — `matrixPhase` fixed to `d/(max+1)`; matrix rows take the
  fixed sunrise tints via new `--rb-gspin-row-0/1/2` tokens (added in
  `theme.ts`; ticket 02 never added them); `.glyph-spinner-row` keeps the
  accent glyph roles (mini only). New `MonoSpinner`, `RobocoMarkLoader`
  (34 MARK_CELLS verbatim, markCellStagger), `RobocoLoader` (5 cells,
  +0.15s stagger), `UploadProgressRing` (fixed white-on-wash palette per
  loaders.rs:313-314 — documented exception); reduced-motion rules snap
  every cell to rest. Geometry call sites corrected: working-trailer
  spinner 12→12.5 (cell 2.5), status-strip 16→12.5 (shell.rs:6427,
  ticket 06's deferred call), changes-page 16→15 (cell 3.0, changes.rs:4831).
- **Context ring** — ticket 13 had already replaced the `title` attribute
  with the PickerCard tooltip; this ticket renamed the details fn to
  `usageDetails`, extracted `ContextUsageTooltip`, and re-verified the four
  verbatim strings + 260px card live.
- Tests: `tests/rail.test.ts` (14) + `tests/badges.test.ts` (13, including
  `the_mini_ring_visits_every_cell_once` and a mark-stagger mirror of
  loaders.rs's `mark_stagger_follows_flight_axis`).

### Deviations / judgment calls for a human

1. **Preview card inline, not portaled** — the desktop mounts it through
   `deferred(anchored())` with no entrance motion and it vanishes the moment
   the pointer leaves the tick; the web card is an absolute,
   `pointer-events: none` card beside the rail column (left = rail left +
   26, LeftCenter-anchored, clamped to 8px margins), centered on the tick and
   clamped via a measure pass. The blueprint's "RbPopover openOnHover" option
   was considered; the desktop's tick-swallow behavior (the card is a peek,
   not a surface) made the inline card the faithful port.
2. **Badge pill triggers open on click** (PickerCard/Base UI trigger
   semantics) though the desktop's pill is hover-only — the same accepted
   deviation as ticket 13's context-meter card.
3. **`--rb-gspin-row-*` tokens added in `app/theme.ts`** rather than the
   generated `@roboco/theme` artifact — the ticket said "become tokens in
   ticket 02" but 02 never added them; app-level tokens keep the artifact
   gate untouched (no Rust changes allowed here).
4. **`UploadProgressRing` label uses the raw percent** (loaders.rs:331 uses
   `percent`, only the arc clamps to 100) — kept verbatim.
5. **Spinner sizes at chat-list (11) and the identity chip (16) left
   alone** — those are tickets 08/13's per-surface geometry (GlyphSpinner
   size semantics = slot height); only the three matrix call sites named
   above were corrected.
6. **Mid-session tab corruption note (verification only, no code impact):**
   during smoke captures the long-lived automation tab stopped delivering
   scroll events entirely (verified world-clean: programmatic writes fired
   zero scroll events, fresh tabs fired them) — mid-session "view stopped
   following" observations were that tab's renderer, not the app; all
   functional verification above was re-confirmed in a fresh tab. A
   speculative hardening of the scroller ref callback was reverted after the
   real cause was identified (React does not remount the scroller node when
   the offline strip toggles — verified with a node marker).

### Verification

- `pnpm -r build` green; `pnpm --filter @roboco/app test` green (52 files /
  803 tests).
- Browser (`web_smoke`, 1440×900 emulated, `ROBOCO_MOCK_DELAY_MS=1200` —
  ticket 18's documented pacing addition): boot with no error boundary
  (checked on every instance incl. the final bundle); rail geometry
  measured computed — left 16 within the container, 26px column, 10px
  slots, 3px gaps, 12×2 bar at `ink(0.16)` → 20px and `text @ 80%` on
  hover/active; preview card 280px wide at rail-left+26, vertically centered
  on the tick (card center 436.75 vs tick center 437), prompt+reply content
  verified; 5-prompt chat — third tick active after gliding to it
  (active=["0","0","1","0","0"]); reduced-motion emulation: tick click
  lands hard (no 500ms glide); narrowed viewport 1000 (container 744):
  rail unmounts, returns at 1440; badge pill 24px/"3 comments" with the
  block stripped from the bubble ("Review these points before merging.");
  badge hover card 320px, 3 rows (mono locations, R/L tag pills, bodies);
  context ring "—" with the 260px tooltip (title + not-reported string);
  phone-width sanity at 480 (composer present, no rail, no boundary).
- Screenshots in `.scratch/web-parity/shots/20/`: `web-a-rail-third-active.png`,
  `web-b-rail-preview-card.png`, `web-c-narrow-no-rail.png`,
  `web-d-badge-pill.png`, `web-d-badge-hover-card.png`,
  `web-e-context-ring-tooltip.png`, `web-f-working-trailer-spinner.png`,
  `web-phone-sanity.png`. (a), (b), (d) are from the final bundle; (c), (e),
  (f) from the first build — identical code for those surfaces (only the
  rail-preview positioning CSS changed in between).
- **Skipped captures:** desktop halves of every pair (no desktop client
  running, consistent with tickets 08/10/12/13/18); (e)'s <75%/80%/95%
  ring states — the mock harness never reports context usage (ticket 13
  hit the same limit; the "—" ring + open tooltip is the stageable half,
  and the four `details` strings are unit-covered via the shared logic).

### Shared components addendum (2026-09-18)

Build on components/ui/ + components/base/ (see components/README.md)
— do not hand-roll card shells, cursor lists, menu rows, chips, or
tooltips.
