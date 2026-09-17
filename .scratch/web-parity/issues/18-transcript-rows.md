# 18 — Transcript rows

**What to build:** The transcript's non-tool rows, rebuilt to desktop geometry:
a 736px column inside 48px gutters, a user bubble that measures and animates its
own collapse with matched viewport compensation, file-mention chips inside the
bubble, a reserved hover lane with an absolute timestamp and an icon copy
button, the question and error chips, and — the piece a user notices first — the
in-flow **working trailer** under the last row with a spinner, a rotating
flavour word and an elapsed timer. Sending a message parks the new prompt 48px
below the viewport top (the own-turn runway) instead of pinning it to the
bottom, and leaving a chat and coming back restores where you were reading.

**Blocked by:** 01 (Smoke fixture renders a transcript), 02 (Foundation tokens),
05 (State fixes: nav history, send ids, optimistic echo).

**Status:** ready-for-agent

**Research:** `../../web-client/research/02-transcript.md` §3.0, §3.1–§3.8,
§3.12, §3.13, §3.14, §4.1–§4.6, §4.8, §4.15, §4.16, §4.17, §4.19, §5 rows
1–3, 7–18, 53, 54, 62 (veil gate only), 69–79.

**Desktop reference (for lookups only):** `crates/ui/src/transcript.rs::render_row`
(:5335), `::render_user_body` (:4681), `::render_user_expander` (:4817),
`::user_bubble_text` (:6436), `::render_working_trailer` (:5218),
`::input_chip` (:6596), `::error_chip` (:6535), `::rows_for_entry` (:1199),
`::top_gap_for` (:1630), `::parse_for_row` (:1579), `::diff_rows` (:1651),
`::format_timestamp` (:1029), `::selection_scroll_step` (:142),
`::StickSpring` (:285), `::OwnTurnAnchor` (:2269), `::ViewportAnchor` (:2349),
`::SavedViewport` (:2401), `::TranscriptReplayState` (:2426),
`crates/proto/src/layout.rs`, `crates/proto/src/motion.rs`.

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/components/transcript.tsx` | edit | `TranscriptView`, `TranscriptSurface`, `TranscriptScroller`, `captureAnchor`, `estimateRowHeight`, `RowShell`, `RowContent`, `RowMeta`, `UserRow`, `MarkdownRow`, `LiveMarkdownRow`, `InputChipRow`, `ErrorChipRow` |
| `web/packages/app/src/components/working-trailer.tsx` | new | `WorkingTrailer` (+ the failed-send retry branch) |
| `web/packages/app/src/lib/transcript.ts` | edit | `rowsForEntry`, `topGapFor`, `diffRows`, `formatTimestamp`, `userMessageNeedsCollapse`, `assistantCopyText`, `TranscriptRow`, `TranscriptRowKind`, `MD_BLOCK_GAP`; new `userResizeDurationMs`, `userResizeSpec`, `flavourWord`, `flavourSeed`, `formatElapsed`, `sendingBridge`, `selectionScrollStep`, `sentMentionDisplay` consumption |
| `web/packages/app/src/components/stick-controller.ts` | edit | `StickController` — own-turn runway, saved viewports, viewport anchors, jump visibility |
| `web/packages/app/src/lib/stick-spring.ts` | none (cite) | `StickSpring` is already a faithful port (§5 row 72); do not rewrite it |
| `web/packages/app/src/state/transcript-store.ts` | edit | `TranscriptStore`, `TranscriptSnapshot` — replay state (`Pending`/`Empty`/`Populated`) |
| `web/packages/app/src/styles/app.css` | edit | `.transcript-wrap`, `.transcript`, `.transcript-col`, `.trow`, `.transcript-fade`, `.row-user`, `.user-bubble`, `.user-bubble-pending`, `.user-text`, `.user-text-clamped`, `.user-expand`, `.row-meta`, `.row-meta-on`, `.row-meta-copy`, `.row-md`, `.input-chip`, `.input-chip-glyph`, `.input-chip-text`, `.error-chip`, `.error-chip-glyph`, `.error-chip-text`, `.chat-transcript-empty`, `.transcript-error`; new `.user-mention`, `.working-trailer`, `.working-word`, `.working-elapsed`, `.undelivered-retry`, `.engine-offline-strip`; delete `.input-chip-open` |
| `web/packages/app/src/components/subagent-dialog.tsx` | none | owned by ticket 19 |
| `web/packages/app/tests/transcript-model.test.ts` | edit | new cases listed in §6 |

---

## 1. Context a fresh session needs

- The transcript is the scrolling message list inside a chat: user bubbles on
  the right, assistant markdown blocks on the left, folded tool groups drawn as
  a task tree, thought chips, subagent spawn chips, attachment thumbnails, a
  hover-revealed timestamp/copy strip, and an in-flow "working" trailer under
  the last row while a run is live.
- It is virtualized at **block** granularity — one row per top-level markdown
  block, per tool group, per input/error chip — and is driven by a velocity
  spring that keeps the viewport glued to the growing tail.
- Today's web wiring: `state/transcript-store.ts::TranscriptStore` holds the
  entry list; `lib/transcript.ts::rowsForEntry` turns entries into
  `TranscriptRow[]`; `components/transcript.tsx::TranscriptScroller` is a manual
  prefix-sum virtualizer with a `ResizeObserver` height map; `RowShell` renders
  `.trow` and `RowContent` dispatches on `row.kind`; scroll follow lives in
  `components/stick-controller.ts::StickController` over
  `lib/stick-spring.ts::StickSpring`.
- A second, read-only instance of the same component renders a **subagent** doc
  (`Transcript::for_doc` on desktop). It aligns to the TOP instead of the
  bottom, never echoes, never holds an own-turn runway, and never touches the
  global attachment-protection set. Its surface (a right-pane tab) is ticket 19
  + 07; this ticket only keeps the row code parameterized by an
  `alignTop`/`isSubagent` flag so 19 can mount it.
- Colors: every value below is a theme role. `theme.text` → `var(--rb-text)`,
  `text_muted` → `var(--rb-text-muted)`, `text_faint` → `var(--rb-text-faint)`,
  `danger` → `var(--rb-danger)`, `danger_muted` → `var(--rb-danger-muted)`,
  `warning` → `var(--rb-warning)`, `border` → `var(--rb-border)`,
  `surface` → `var(--rb-surface)`. Neutral washes are
  `rgb(var(--rb-ink) / a)` (`theme::ink(a)`),
  `rgb(var(--rb-wash) / a)` (`theme::wash(a)`),
  `rgb(var(--rb-hairline) / a)` (`theme::hairline(a)`). Ticket 02 adds any
  missing token; do not write literal hex.
- Motion is `var(--rb-motion-<spec>)` + `var(--rb-ease-<curve>)` from the shared
  catalog. Where the desktop checks `motion::reduced_motion(cx)`, the web honors
  `@media (prefers-reduced-motion: reduce)` the same way.
- Vocabulary (`CONTEXT.md`): chat (not session/thread), harness (not provider),
  engine, space; Session only for the pairing credential.
- Out of this ticket, on purpose: tool groups and their tree/chips (19), the
  message rail, badges and loaders (20), markdown block rendering itself (21),
  attachment thumbnails and the lightbox (17), the jump-to-bottom pill chrome
  (shell — ticket 06; this ticket owns only the `jumpVisibility` state that
  drives it).

---

## 2. Spec

### 2.0 Shared constants

Copy these into `lib/transcript.ts` as exported consts (the ones already there
are marked); the CSS reads them through `--rb-*` tokens or literal px where no
token exists.

| Const | Value | Source |
|---|---|---|
| `OVERDRAW_PX` | 320.0 | transcript.rs:70 |
| `SCROLL_BUTTON_THRESHOLD_PX` | 320.0 | transcript.rs:72 |
| `MAX_SAVED_VIEWPORTS` | 256 | transcript.rs:85 |
| `MAX_PENDING_QUEUED_TURNS` | 256 | transcript.rs:87 |
| `SELECTION_SCROLL_TICK_MS` | 24 | transcript.rs:91 |
| `SELECTION_SCROLL_EDGE_PX` | 36.0 | transcript.rs:92 |
| `SELECTION_SCROLL_MAX_STEP_PX` | 24.0 | transcript.rs:93 |
| `MAX_CONTENT_WIDTH` | 736.0 (46rem) — already in `transcript.tsx:45` | transcript.rs:95 |
| `CHIPS_TOP_PAD` | 2.0 (tool-group chip stack top pad; **ticket 19 consumes it**, listed here because it is part of the list's analytic height sums) | transcript.rs:166 |
| `USER_COLLAPSED_LINES` | 5 — already exported | transcript.rs:174 |
| `USER_LINE_HEIGHT` | 22.0 | transcript.rs:176 |
| `USER_COLLAPSE_CHARS` | 400 — already exported | transcript.rs:181 |
| `USER_TOGGLE_GAP` | 8.0 | transcript.rs:183 |
| `OWN_SEND_TOP_INSET_PX` | `TITLEBAR_HEIGHT + 10.0` = 48.0 | transcript.rs:205 |
| `OWN_SEND_SCROLL_SLACK_PX` | 2.0 | transcript.rs:213 |
| `OWN_SEND_GLIDE_RETAIN` | 0.85 (per 60fps frame) | transcript.rs:216 |
| `OWN_SEND_GLIDE_SNAP_PX` | 1.0 | transcript.rs:218 |
| `FLAVOUR_ROTATE_SECS` | 7 | transcript.rs:1916 |
| `USER_HOLD_DELAY` | 360ms | transcript.rs:4460 |
| copy-feedback clear | 1200ms | transcript.rs:5689, :5721 |

Theme layout constants (`crates/proto/src/layout.rs`): `SPACE_XS` 4, `SPACE_SM`
8, `SPACE_MD` 12, `SPACE_LG` 16, `CONTROL_RADIUS` 6, `PANEL_RADIUS` 10,
`BUBBLE_RADIUS` 16, `TITLEBAR_HEIGHT` 38, `TRANSCRIPT_FADE_BAND` 24.
`markdown::render::MD_BLOCK_GAP` 12.

Spring constants (`crates/proto/src/motion.rs`, re-exported from
transcript.rs:63) — already ported in `lib/stick-spring.ts`, listed for
reference: `SPRING_DAMPING` 0.7, `SPRING_STIFFNESS` 0.05, `SPRING_MASS` 1.25,
`SPRING_FRAME_MS` 1000/60, `SPRING_MAX_CATCHUP_FRAMES` 8, `SPRING_GROWTH_EMA`
0.12, `SPRING_CHASE_MAX_LEAD` 32, `AT_BOTTOM_PX` 2, `STICK_THRESHOLD_PX` 70,
`SPRING_SETTLE_GRACE_MS` 500, `GLIDE_MAX_VIEWPORTS` 2.5.

---

### 2.1 Transcript root / virtual list

**Layout**

| Property | Value | Source |
|---|---|---|
| position | `relative`, `size_full`, `min_h_0` | :7701-7704 |
| list overdraw | `px(OVERDRAW_PX)` = 320 | :2812 |
| list alignment | `ListAlignment::Bottom` (primary) / `ListAlignment::Top` (subagent override) | :2807-2811 |
| sizing behavior | `ListSizingBehavior::Auto` | :7639 |
| edge fade | primary: NONE here (the shell's outlet wraps it). Override instance: `edge_fade::edge_faded(TRANSCRIPT_FADE_BAND = 24, top_only, false, …)`, top fade gated on `max_offset − distance_from_bottom > 1.0` | :7640-7661 |
| offline strip | `h(24) px(12) text_xs bg(theme.surface) text_color(theme.text_muted) border_b_1 border_color(theme.border)` | :7682-7694 |

**Children (in order)** — `selection_frame_reset()` (desktop-only: the web uses
native DOM selection), then content (list, possibly wrapped in the offline
column), then the rail (ticket 20), then (conditional) the lightbox (ticket 17).

**Interactions**

- `on_mouse_down(Left)` → `on_selection_mouse_down`; `on_mouse_move` →
  `on_selection_mouse_move`; `on_mouse_up(Left)` and `on_mouse_up_out(Left)` →
  `on_selection_mouse_up`. These drive markdown text selection across rows.
- While a selection drag is active and the pointer is within
  `SELECTION_SCROLL_EDGE_PX` (36, capped at viewport/3) of an edge, the list
  auto-scrolls every `SELECTION_SCROLL_TICK_MS` (24ms) by
  `SELECTION_SCROLL_MAX_STEP_PX · t²` where `t = penetration/edge` clamped 0..1,
  signed negative at the top edge (`selection_scroll_step`, :142).
- Scroll handler (`handle_scroll`, :3117) fires ONLY from the list's wheel/touch
  path; programmatic scrolls never re-enter it. On web: guard the scroll
  listener with a "programmatic" flag around every imperative `scrollTop` write.

**Motion**

| What | Trigger | Spec | From → To | Reduced motion |
|---|---|---|---|---|
| Tail follow | pinned + content growth | `StickSpring` (see §3.1), one tick per animation frame | current offset → list end | never schedules; `sync` snaps to the end (:7565-7567, :4256) |
| Jump-to-bottom glide | pill click / restick | teleport to within `GLIDE_MAX_VIEWPORTS·viewport` (2.5 viewports), then spring | — | snap to end (:3785) |
| User-fold viewport compensation | long prompt expand/collapse near bottom | `user_resize_spec(height_delta)` (see §3.4) | `initial_top` → `target_top` | direct scroll-by (:4425) |

**Data** — reads the chat transcript (or the subagent doc), `pending_echoes()`,
`indicator_for(chat_id, now)`, `session_for(chat_id)`, `pending_send_started`,
`chat_delivery_degraded`, `send_undelivered`, `context_usage`. Writes:
`RETRY_DELIVERY` RPC (`{chatId}`), clipboard writes.

**Engine-offline strip (replaces the invented floating error card)** — a 24px
bar ABOVE the list, not an overlay. Exact strings:
`"Engine off. Cached history is read-only."` and
`"Reconnecting… Cached history is read-only."` (§5 row 76).

---

### 2.2 `render_row` — the row shell (:5335)

**Layout**

| Property | Value | Source |
|---|---|---|
| outer | `id(row.id) w_full flex justify_center` | :5637-5666 |
| horizontal gutters | `px(48.0)` ("roboco `px-4 @3xl:px-12`") | :5670 |
| padding-top | `top_gap` (see below) | :5667 |
| padding-bottom | last row only: `bottom_clearance + TRANSCRIPT_FADE_BAND(24) + 8`; else 0 | :5369-5373 |
| inner column | `w_full max_w(MAX_CONTENT_WIDTH = 736) min_w_0` | :5672-5675 |

`top_gap` (:5355-5363):
- `ix == 0` and primary instance → `TITLEBAR_HEIGHT + SPACE_LG + 10.0` = **64.0**
- `ix == 0` and subagent override → `SPACE_LG` = 16
- otherwise → `top_gap_for(prev, row)` (see §3.3)

**Children (in order)** — `inner` (the row kind), `strip` (hover metadata),
`trailer` (working indicator, last row only).

**States**

| State | Condition | Change |
|---|---|---|
| hovered | pointer inside this row | sets `hovered_entry = Some((row_id, entry_id))`; the metadata strip of every row sharing `entry_id` becomes visible |
| hover cleared | leave event from the row that OWNS the reveal | `hovered_entry = None`. A stale leave from a previous row must not clear it (:5652-5662) |

**Bottom clearance.** `bottom_clearance` is pushed into the transcript by the
shell (`set_bottom_clearance`) as the **measured composer + status + terminal
stack height**. The web has no equivalent plumbing today (research §7 q1). Wire
it as: the shell measures its bottom chrome with a `ResizeObserver` and
publishes it (`state/layout.ts` is the existing home for shell geometry); the
transcript reads it and pads the last row by
`clearance + TRANSCRIPT_FADE_BAND(24) + 8`. Do not hard-code a constant.

---

### 2.3 Hover metadata strip (:5558-5634)

Rendered only when `row.timestamp.is_some()` — i.e. on the LAST row of a settled
entry. It is a **reserved lane** (always occupies its height) so the virtualizer
never shifts; only the contents' visibility flips.

**Layout**

| Property | Value | Source |
|---|---|---|
| height | `SPACE_SM + SPACE_MD * 2.0` = 8 + 24 = **32** | :5615 |
| padding-top | `SPACE_SM` = 8 | :5616 |
| width / flex | `w_full flex items_center` | :5617-5619 |
| justify | `justify_end` when the row is a user row; default (start) otherwise | :5627 |
| inner metadata row | `flex flex_row items_center gap(SPACE_SM = 8)` | :5608-5612 |
| timestamp text | size 12px, color `theme.text_muted` at opacity **0.55** | :5573-5574 |
| copy button | `size(SPACE_MD * 2.0 = 24)` square, `rounded(CONTROL_RADIUS = 6)`, `cursor_pointer` | :5581-5586 |
| copy button bg | `hover_blend(key, transparent, theme.ink(0.08))` | :5589-5593 |
| copy icon | `icons::COPY` (or `icons::CHECK` when copied), `size(14)`, `theme.text_muted` | :5599-5605 |

**Children (in order)** — timestamp, then copy action.

**Interactions**
- hover anywhere on any row of the entry → the strip fades in with
  `FADE_QUICK` = 150ms `EASE`, keyed `meta-{row.id}` (:5629-5632).
- copy click → `copy_message`: stop propagation, write `row.copy_text` to the
  clipboard, set `copied_message = entry_id`, clear after **1200ms**
  (:5683-5699). Icon swaps `COPY` → `CHECK` while set.
- copy button hover → background blends to `theme.ink(0.08)` over `HOVER_FADE`
  (150ms `EASE_TAILWIND`).

**Text** — `format_timestamp(ms, local)` = `"%b %-d, %-I:%M %p"`, e.g.
`"Jul 1, 3:45 PM"` (:1029-1040). **No "Copied" text label** — the icon alone
changes.

**Data** — `row.timestamp` (epoch ms), `row.copy_text`.

---

### 2.4 User row (:5380-5452)

The column is a `w_full flex flex_col` with, in order:

1. **Attachment thumbnails** (when `!attachments.is_empty()`) —
   `render_user_attachments`. **Ticket 17** owns the thumbs; this ticket only
   keeps the slot first in the column.

   | Property | Value | Source |
   |---|---|---|
   | appshot card image height | `h(px(126.0))` inside the 128px frame, with `w_full rounded(5) object_fit(Contain)` — the 1px inset that keeps the frame's rounding visible | transcript.rs:4983 |

   (Listed for completeness because the row column owns the slot; the appshot
   card itself is ticket 17's to build.)
2. **Badges strip** (when `!badges.is_empty()`) — `w_full flex flex_row
   flex_wrap justify_end items_center gap(6) pb(6)`, children
   `badges::render("{row.id}#badge{bix}", badge, theme)` (:5405-5423).
   **Ticket 20** owns the pill and its card; this ticket keeps the strip
   container and its ordering.
3. **Bubble** (when `!text.is_empty()`) — wrapper `w_full flex justify_end`.

**Bubble layout**

| Property | Value | Source |
|---|---|---|
| min-width | `min_w_0` (load-bearing: without it long prompts clip off the left) | :5435 |
| max-width | `MAX_CONTENT_WIDTH * 0.8` = **588.8** | :5436 |
| background | `theme::user_bubble_bg()` = dark `wash(0.08)` / light `wash(0.04)` | :5437, theme.rs:1599 |
| radius | `BUBBLE_RADIUS` = 16 | :5438 |
| padding | `px(16) py(10)` | :5439-5440 |
| font size | 14px | :5441 |
| line height | `USER_LINE_HEIGHT` = 22px | :5442 |
| color | `theme.text` | :5443 |
| opacity | `0.65` while `pending` (optimistic echo not yet confirmed) | :5444 |

Image-only sends (empty text) show **no bubble at all** — the thumbnail strip is
the whole row (:5425).

`user_bubble_bg()` is appearance-dependent; ticket 02 must expose it (e.g.
`--rb-user-bubble-bg`) rather than hard-coding `wash(0.08)` in CSS.

---

### 2.5 `render_user_body` (:4681) — collapse / expand

**Derived values**
- `line_height = USER_LINE_HEIGHT` (22) in px
- `collapsed_text_h = USER_COLLAPSED_LINES (5) * line_height` = 110
- `collapsed_h = collapsed_text_h + line_height` (the continuation-ellipsis line
  is inside the resize endpoints so removing it does not jump)
- `measured` — full wrapped text height, measured from the DOM (the desktop
  writes it from the paint canvas in `user_bubble_text`)
- `full_h = max(measured, collapsed_h)`

`collapsible` is true when ANY of (:4705-4707):
- `text.lines().count() > USER_COLLAPSED_LINES`, or
- `measured > 0 && measured > collapsed_text_h + 0.5`, or
- `measured == 0 && user_message_needs_collapse(text)` (first-frame proxy)

**States**

| State | Condition | Rendering |
|---|---|---|
| not collapsible | — | body rendered plain |
| collapsed | `collapsible && !expanded && !animating` | `h(collapsed_text_h) overflow_hidden` around the body, followed by an ellipsis row `h(line_height)` containing `"..."` (:4790-4794) |
| expanded | `fold.open == Some(true)` | body plain, no clip |
| animating | `collapsible && fold.epoch > 0 && fold.toggled_at.elapsed() < duration_ms + 200ms && !reduced_motion` | height tween (below) (:4770-4775) |

**Motion**

| What | Trigger | Spec | From → To | Reduced motion |
|---|---|---|---|---|
| bubble height | expander click / long press | `user_resize_spec(full_h − collapsed_h)` — duration `min(220 + Δ·0.32, 850)ms`, curve `EASE_IN_OUT` when `Δ > 500`, else `EASE_OUT` | `fold.from` → (`full_h` if expanding else `collapsed_h`), minus `line_height` while collapsed (the ellipsis row is outside the clip) (:4777-4789) | no tween; toggle is instant |
| viewport compensation | same | same spec, stepped per frame by `step_user_collapse_scroll` (:4486) | row top `initial_top` → `target_top` | direct scroll-by (:4425) |

The animation element is keyed `"{row_id}-user-resize-{fold.epoch}"`, so a new
toggle restarts it and a remount within the window replays deliberately; past
`duration + 200ms` the fold renders statically (an armed-forever tween replayed
on every scroll-back-into-view — user report, :4770-4774). On web the same rule
means: only apply the height transition while within that window, keyed by
epoch.

`target_top` computation (`toggle_user_fold`, :4405-4420):
- `viewport_top = viewport.top + TRANSCRIPT_FADE_BAND(24) + 28`
- `viewport_bottom = viewport.bottom − target_height − 12`
- `target_top = if viewport_bottom >= viewport_top { initial_top.clamp(viewport_top, viewport_bottom) } else { viewport_top }`
- scroll runs only when `|target_top − initial_top| > 0.5`.

**Interactions**
- **Long press** (`USER_HOLD_DELAY` = 360ms) on the body toggles the fold;
  mouse-up, mouse-up-outside, and ANY mouse-move cancel the pending toggle so a
  drag-select never toggles (:4722-4753, :4451-4484). On fire it clears the text
  selection it owns first (`clear_if_owner("{row_id}:u")`).
- A fold toggle calls `begin_scroll_navigation()` first: discards the pending
  viewport, cancels the hold, clears the collapse-scroll, releases the own-turn
  hold, unpins, and resets the spring.

**Do not** keep `-webkit-line-clamp` (`.user-text-clamped`, app.css:3463): it
clamps by rendered lines with no measured height, so no tween and no
compensation are possible.

---

### 2.6 `render_user_expander` (:4817)

A plain text link, **not** a pill/button wash.

| Property | Value | Source |
|---|---|---|
| wrapper | `mt(USER_TOGGLE_GAP = 8) flex items_start` | :4870-4872 |
| button | `flex items_center gap(5)` | :4844-4846 |
| font size | 14px | :4847 |
| line height | `USER_LINE_HEIGHT` = 22px | :4848 |
| color | `theme.text_muted`; hover `theme.text` | :4849, :4851 |
| icon | `icons::ALT_ARROW_UP` when expanded, `icons::ALT_ARROW_DOWN` when collapsed; `size(12)`, `theme.text_muted`, group-hover → `theme.text` | :4828-4832, :4854-4857 |
| cursor | `cursor_pointer` | :4850 |

**Text** — `"Show less"` when expanded, `"Show more"` when collapsed (:4833).
Accessibility: `role=button`, `aria-label` `"Collapse message"` /
`"Expand message"`, `aria-expanded` (:4837-4843). Group name
`"user-message-toggle"` → on web, a `.user-expand` inside a group-hover scope.

---

### 2.7 `user_bubble_text` (:6436) — mention chips

The bubble's text is one styled text with runs split at **file-mention**
boundaries (`composer::SentMentionSpan`, precomputed once in `rows_for_entry`):

| Run | Font | Color | Source |
|---|---|---|---|
| body | `theme.font_sans` | `theme.text` | :6447-6454 |
| mention chip | `theme.font_mono` | `theme.code_text` | :6455-6462 |

An underlay paints, beneath the glyphs (:6480-6495):
- one quad per mention span rect (`range_rects(layout, range, 0.0, 2.0)` — i.e.
  **0px horizontal overhang, 2px vertical inset**), corner radius `px(5.0)`,
  fill `theme.code_wash`, no border;
- the text selection via `paint_text_selection(window, "{row_id}:u", …)`.

Web shape: wrap each mention span in `<span class="user-mention">` with
`background: var(--rb-code-wash); border-radius: 5px; color: var(--rb-code-text);
font-family: var(--rb-font-mono); padding: 0` and a 2px vertical inset achieved
with `box-decoration-break: clone` + a negative-free `padding`/`line-height`
combination — the wash must not grow the 22px line box.

The desktop canvas also passively records the wrapped height:
`line_count = Σ(wrap_boundaries.len() + 1)`, `next_h = max(line_count,1) ·
line_height`; it writes the shared cell and notifies only when the delta exceeds
0.5px (so idle layout never feeds back). The web equivalent is a
`ResizeObserver` on the text node whose callback ignores deltas ≤ 0.5px.

---

### 2.8 Assistant markdown rows (wiring only)

`RowKind::Markdown` and `RowKind::LiveMarkdown` each carry `(tree, block_ix)`
and render exactly ONE top-level block. The desktop calls
`markdown::render::render_block` with:

```
RenderOptions { row_key, veil, cache, copy, link, workspace_root, code }
```

| Field | What the transcript passes |
|---|---|
| `row_key` | the row id — the selection-registry / cache key |
| `veil` | `Some(RowVeil)` for `LiveMarkdown`, and `None` under reduced motion: `let veil = (!motion::reduced_motion(cx)).then(|| …)` (:5494) |
| `cache` | the cross-frame flatten cache; `None` when `ROBOCO_NO_RENDER_CACHE` (desktop-only — the web uses `memo`) |
| `copy` | the code-fence copy state (1200ms "Copied") owned by the transcript entity |
| `link` | link options incl. `source_session` — set inside the transcript, which is what turns on link-label truncation |
| `workspace_root` | for workspace file-link decoration |
| `code` | the global `code_fences_fit_content` setting |

This ticket owns only the wiring (which block renders, which veil/copy state it
gets, the `.row-md` font-size/line-height context of 14/22). **Ticket 21** owns
every block's appearance, the veil math, links, code-fence chrome, tables,
images and `::selection`.

---

### 2.9 `input_chip` (:6596)

| Property | Value | Source |
|---|---|---|
| wrapper | `py(4) w_full` | :6603-6604 |
| chip | `h(34) w_full flex items_center gap(8) overflow_hidden rounded(10) border_1 border_color(hairline(0.08)) bg(ink(0.045)) px(8) text_size(12)` | :6607-6618 |
| icon tile | `flex_none size(20) rounded(6) bg(ink(0.09)) flex items_center justify_center` | :6620-6627 |
| icon | `icons::CHAT_ROUND_LINE`, `size(12)`, `theme.text_muted` | :6629-6631 |
| label | `flex_none font_weight(MEDIUM) text_color(theme.text_muted)` | :6635-6638 |
| value | `min_w_0 flex_1 truncate text_color(theme.text.opacity(0.9))` | :6642-6646 |

**Text** — label `"Question"`; value is the first question's header when
`resolved`, otherwise `"Awaiting your answer…"` (:6597-6601). Neutral tones
throughout — **resolution never recolors the chip**. Delete `.input-chip-open`
(app.css) and the `?` glyph.

---

### 2.10 `error_chip` (:6535)

| Property | Value | Source |
|---|---|---|
| wrapper | `py(4) w_full` | :6539-6540 |
| chip | `min_h(34)` (**not fixed — the message WRAPS**) `w_full flex items_center gap(8) overflow_hidden rounded(10) border_1 border_color(danger.opacity(0.16)) bg(danger.opacity(0.05)) px(8) py(7) text_size(12)` | :6543-6555 |
| icon tile | `flex_none size(20) rounded(6) bg(danger.opacity(0.12)) flex items_center justify_center` | :6557-6563 |
| icon | `icons::DANGER_TRIANGLE`, `size(12)`, `theme.danger_muted.opacity(0.8)` | :6566-6568 |
| label | `flex_none font_weight(MEDIUM) text_color(theme.danger_muted.opacity(0.8))` | :6572-6576 |
| message | `min_w_0 flex_1 text_color(theme.text.opacity(0.8))` — wraps, no truncate | :6579-6583 |

**Text** — label `"Error"`, then the harness message, already flattened to one
logical line by `single_line` at row-build time but free to wrap visually. The
comment is explicit: a one-line ellipsis made a startup-crash report
undiagnosable, so wrapping is deliberate (:6531-6534). Delete the `!` glyph and
the ellipsis truncation.

---

### 2.11 `render_working_trailer` (:5218)

Appended **under the last row's content**, above its clearance pad (:5374-5378),
so it reads as part of the streaming reply and scrolls away with it.

**Failed-send branch** (primary instance, `send_undelivered(chat_id, now)` true)
— takes precedence over everything else (:5243-5260):
`id("undelivered-retry") flex flex_row items_center gap(SPACE_SM = 8)
pt(SPACE_LG = 16) text_size(12) text_color(theme.danger) cursor_pointer`, text
`"Not delivered — click to retry"`. Click → `retry_send`:
`state.retry_pending_send(chat_id, now)` then the `RETRY_DELIVERY` RPC with
`{"chatId": …}` (skipped entirely when the engine is not connected).

**Normal branch** (:5295-5332):

| Property | Value | Source |
|---|---|---|
| layout | `flex flex_row items_center gap(SPACE_SM = 8)` | :5297-5300 |
| padding-top | `SPACE_LG` = 16 | :5301 |
| base font size | 11px | :5302 |
| spinner | `loaders::gradient_spinner("working-indicator", theme, cell_px = 2.5, …)` → web `MatrixSpinner` at `size ≈ 12` (cell 2.5 × 5) | :5303-5309 |
| word | `text_size(12)`, color `theme.warning` when queued else `theme.text_muted` | :5311-5322 |
| elapsed | `text_color(theme.text_faint)`, hidden while `sending` | :5324-5330 |

**Text**
- `queued` → `"Queued — will send automatically"` (no trailing ellipsis, no timer)
- `sending` → `"Sending…"` (no timer)
- otherwise → `"{flavour_word(seed, elapsed_secs)}…"` + `format_elapsed(elapsed_secs)`

**Gating**
- Primary: only when `state.indicator_for(chat_id, now) == Indicator::Working`.
- `sending = sending_bridge(pending_send_started(chat_id, now), session.started_at)` (§3.6)
- `queued = sending && chat_delivery_degraded(chat_id)`
- `elapsed = now − session.started_at` in seconds, floored at 0, `0` when unknown.
- Subagent override instance (:5220-5238): returns nothing unless `doc_live`;
  then liveness is the doc's own last entry — `status == Streaming || role ==
  User` — and `elapsed = (now_ms − last.created_at)/1000`, seed =
  `flavour_seed(doc_id)`. `sending`/`queued` are always false there.
  **Frozen snapshots never spin.**

The `MatrixSpinner` used here must first be corrected by ticket 20 (phase
denominator and sunrise tints). Render it here; do not patch the spinner in this
ticket.

---

### 2.12 Own-turn send runway

A locally-sent turn reserves the viewport below its prompt: the last row has a
minimum height, so streaming content and the working trailer consume or release
space in the same layout pass. Only changes to the preceding rows require a
post-layout refinement. Wheel input releases the automatic glide/hold while
preserving the reservation; overflow hands off to the ordinary bottom spring.
Chat switches restore the reservation released. (transcript.rs:2260-2300)

**`OwnTurnAnchor`** (transcript.rs:2269) fields:

| Field | Meaning |
|---|---|
| `chat_id` | the chat the runway belongs to |
| `message_id` | the sent prompt's stable id |
| `held` | the step still owns the viewport (glide → hold). Any wheel/touch input releases it — the reservation stays behind as plain scrollable space, and the ordinary escape/restick rules apply from then on |
| `positioned` | the entry glide has landed; the hold now re-asserts the prompt's position absolutely after every layout |
| `seen_prompt` | a fresh send may install the anchor one notification before its echo. Once the prompt has appeared, its later disappearance is terminal (failed echo or removed entry) and the runway must retire |

Methods: `released_for_restore()` → `held = false, positioned = false,
seen_prompt = true`; `observe_prompt(exists)` → sets `seen_prompt` when `exists`,
returns `exists || !seen_prompt` (i.e. keep the runway while the prompt has
never been seen).

**Constants** — `OWN_SEND_TOP_INSET_PX` = `TITLEBAR_HEIGHT + 10.0` = **48**
("the titlebar overlays the full-height list, so its height is part of the
inset; the extra 10px matches the first row's breathing room");
`OWN_SEND_SCROLL_SLACK_PX` = **2.0** ("the runway ends AT the app's bottom —
this is not scroll room; 24px of it read as a janky overshoot-and-fight zone,
user report — it exists only to keep the held layout out of gpui's
shorter-than-viewport regime"); `OWN_SEND_GLIDE_RETAIN` = **0.85** per 60fps
frame (~90% covered in ~230ms, ease-out); `OWN_SEND_GLIDE_SNAP_PX` = **1.0**
(the entry glide snaps to the absolute hold within this error).

**`own_turn_glide_crossed(offset, anchor_ix, inset)`** (transcript.rs:222) — a
bounds-free guard for gliding through rows whose heights are still being
measured; the provisional reservation is never a scroll target:

```
offset.item_ix > anchor_ix
  || (offset.item_ix == anchor_ix && offset.offset_in_item > -inset)
```

**`PendingQueuedTurns`** (transcript.rs:2307) — locally-authored queue rows whose
stable ids have not appeared in the transcript yet. Registration is deliberately
inert: adding a queue row must leave the currently-visible turn and its runway
untouched. Once a matching prompt materializes, the newest match becomes the
own-turn anchor.
- `register(chat_id, message_id)` — remove any existing `(chat, id)` pair, push
  to the back, then drop from the front while `len > MAX_PENDING_QUEUED_TURNS`
  (256).
- `take_latest_materialized(chat_id, rows)` — consume every candidate from this
  chat that is now present (a row with `turn_start && entry_id == message_id`)
  and return the **newest** one. "Multiple rows can land in one doc frame; the
  last send owns the runway, matching consecutive immediate sends."

---

### 2.13 Stick / scroll / viewport memory

- **`StickSpring`** — already ported in `lib/stick-spring.ts` and driven by
  `components/stick-controller.ts` (§5 row 72: MATCHES). Cite it; do not
  rewrite. Its stepper is reproduced in §3.1 for reference only.
- **`jump_visibility(was_shown, distance)`** (:74) —
  `distance > (was_shown ? AT_BOTTOM_PX(2) : SCROLL_BUTTON_THRESHOLD_PX(320))`.
  The hysteresis exists because a single 320px threshold made the control vanish
  halfway through a downward gesture.
- **`should_restick(distance, previous)`** (:3113) —
  `distance <= STICK_THRESHOLD_PX(70) && distance < previous`. Direction-aware:
  a small wheel-up notch near the bottom stays inside the band, and re-sticking
  on it would make the pin unbreakable.
- **`should_anchor_live_stream(pinned, distance, streaming)`** (:199) —
  `pinned && streaming && distance <= AT_BOTTOM_PX(2)`. Deliberately narrower
  than `pinned`: users gliding back toward the bottom keep the normal spring.
- **`distance_from_bottom()`** (:3103) — `max(max_offset.y + scroll_px_offset.y, 0)`.
- **`engage_pin`** (:3782) — `pinned = true`, hide the jump button; reduced
  motion snaps to the end; otherwise, when
  `distance > GLIDE_MAX_VIEWPORTS(2.5) · viewport`, teleport by the excess, then
  wake the spring.
- **`jump_to_bottom`** (:3748) — cancel the collapse-scroll and hold, discard
  the pending viewport. If the own-turn prompt is EXPANDED, release the hold and
  `engage_pin`. If an own-turn runway is live, **re-arm** the hold
  (`held = true, positioned = false`) instead of destroying the runway — only
  navigating away and back clears it. Otherwise `engage_pin`.
- **`wake_spring`** (:3802) — if the spring settled more than
  `SPRING_SETTLE_GRACE_MS(500)` ago, reset it; clear `settled_at`; set `kick`.
- **`spring_should_run()`** (:3815) — `spring_kick || StickSpring::needs_frame(distance)`.

**`ViewportAnchor`** (transcript.rs:2349) — "a stable per-chat viewport anchor.
Row identity is preferred over its old index because async replay can insert or
remove rows while a chat is away." Fields: `row_id`, `entry_id`, `fallback_ix`,
`offset_in_row`.
- `capture(rows, scroll_top)` — `fallback_ix = min(scroll_top.item_ix, rows.len()−1)`;
  take that row's `id`/`entry_id` and `scroll_top.offset_in_item`. `None` when
  `rows` is empty.
- `resolve_exact(rows)` — the index of the row whose `id` matches, keeping
  `offset_in_row`.
- `resolve(rows)` — `resolve_exact` first; otherwise "a row can disappear when a
  streaming block is reshaped. Stay in the same message entry, choosing the
  surviving row nearest the old location; the intra-row offset is no longer
  meaningful in that case": among rows with the same `entry_id` pick the one
  minimizing `|ix − fallback_ix|`, else `min(fallback_ix, rows.len()−1)`, with
  `offset_in_item = 0`. `None` when `rows` is empty.

**`SavedViewport`** (transcript.rs:2401) — "session-local viewport state. Chats
that were following their tail keep following it; only user-owned viewports
restore a concrete row anchor."

| Variant | Payload |
|---|---|
| `FollowTail` | — |
| `Anchored` | `anchor: ViewportAnchor`, `distance_from_bottom: f32`, `own_turn: Option<OwnTurnAnchor>` ("preserve the runway that made a short active turn scrollable. Navigation releases its automatic hold, so revisiting restores the viewport without immediately following new output to the bottom") |

Cache: per chat, LRU bounded by `MAX_SAVED_VIEWPORTS` = **256**.

**`TranscriptReplayState`** (transcript.rs:2426) — `Pending` | `Empty` |
`Populated`, with `authoritative_empty()` = `self == Empty` and
`allows_fallback()` = `self == Populated`. A saved viewport is restored **only
after a populated replay**; an `Empty` replay is authoritative (the chat really
has no messages) and `Pending` is neither. `replay_baseline` (the first
populated frame after attach) also clears every tool-group reveal — ticket 19
consumes that.

**`selection_scroll_step(bounds, position)`** (:142) — §3.7 below; the drag-edge
auto-scroll that keeps a cross-row selection extending.

---

### 2.14 Empty and error states

- **Empty:** the desktop transcript renders **NOTHING** when empty — the shell
  shows the new-chat hero. Delete `"No messages yet."` (transcript.tsx:377) and
  both `"Loading…"` paragraphs (transcript.tsx:78, :426) and the
  `.chat-transcript-empty` rule.
- **Error:** delete the floating red `.transcript-error` card with its Retry
  button (app.css:3420, transcript.tsx:447). Replace with the 24px offline strip
  of §2.1 and its two exact strings.
- **`.status-strip`:** the desktop reserves this strip in the SHELL, not the
  transcript. Move it out of `.transcript-wrap` (transcript.tsx:435) — the shell
  ticket (06) owns its content; this ticket only removes it from here.

---

## 3. Pure logic to port

Every function below is pure and gets a unit test named after the desktop test
it mirrors (list in §6).

### 3.1 `StickSpring::step(pos, target, frames) -> f32` (:285) — ALREADY PORTED

Reproduced only so the implementer can confirm `lib/stick-spring.ts` still
matches; **do not rewrite it**.

```
grew = last_target.map_or(0, |l| target − l);  last_target = target
if grew < −1.0 { target_vel = 0 }                         // row collapse/removal
else { observed = max(grew,0)/max(frames,0.25)
       target_vel += SPRING_GROWTH_EMA(0.12) · (observed − target_vel) }
chase = target − min(target_vel · 9.0, SPRING_CHASE_MAX_LEAD(32))
while frames > 0:
    h = min(frames, 1);  frames −= h
    diff = max(chase − pos, 0)
    v += h · ((SPRING_DAMPING(0.7)·v + SPRING_STIFFNESS(0.05)·diff)/SPRING_MASS(1.25) − v)
    pos = min(pos + (v + target_vel)·h, target)
return (target − pos <= 0.5) ? target : pos
```

`is_idle()` = `velocity < 0.05 && target_vel < 0.05`. `needs_frame(distance)` =
`distance > 0.5`. `frames` is the caller's elapsed time in 60fps units, clamped
to `SPRING_MAX_CATCHUP_FRAMES = 8`.

Desktop tests asserting this: `stationary_spring_does_not_keep_requesting_frames`,
`estimated_height_growth_at_the_bottom_cannot_keep_spring_awake`,
`spring_converges_to_a_fixed_target`, `spring_never_overshoots_or_oscillates`,
`spring_feed_forward_tracks_constant_growth`,
`spring_feed_forward_resets_when_target_shrinks`,
`spring_catchup_frames_glide_instead_of_teleporting`.

### 3.2 `rows_for_entry(entry, pending, parse) -> Vec<Row>` (:1199)

This is the core mapping. Every branch:

**User entries (early return, :1208-1249)** — exactly ONE row.
1. Concatenate all `MessagePart::Text` bodies with `"\n\n"`.
2. `attachments::parse_user_message_images(raw)` splits the refs trailer out →
   `{ text, attachments }`.
3. `badges::split(parsed.text)` lifts structured-context blocks out →
   `(body, badges)`. (Done BEFORE the mention projection so a comment body's
   Markdown never lands in the bubble.) **Ticket 20** ports `badges::split`;
   until then keep the call site and pass an empty badge list.
4. `composer::sent_mention_display(body)` → `(display_text, mention_spans)` or
   the body unchanged with an empty span list.
5. `copy_text = Some(text)` when `text.trim()` is non-empty.
6. Row: `id = entry.id`, `version = (raw.len() << 1) | pending`,
   `turn_start = true`, `timestamp = Some(entry.created_at)` — **always**,
   optimistic echo included.

**Assistant / system entries** — parts are walked in order; consecutive tools of
the same *genus* accumulate into a pending group:

| Part | Branch |
|---|---|
| `Tool` | Build a `ToolItem` (`detail = tool_detail(output, diff, diff_stats)`, `invocation = call_block(call)`, plus refs/status/tail, `is_thought = false`). If the pending group's HEAD has a different agent-ness (`is_agent_tool(head) != is_agent_tool(item)`), flush first — so each group is uniform. Push. (:1283-1328) |
| `Reasoning` | Skipped entirely when `text.trim()` is empty. `live = streaming && part_ix == last_part_ix`. Parse with key `"{entry.id}#{part_id}"`, build `thought_item(tree, live)`. If the pending group's head is an agent tool, flush first (agent groups stay pure). Push. (:1331-1356) |
| `Text` | Flush the group. Skip when `text.trim()` is empty. Parse with key `"{entry.id}#{part_id}"`. Emit ONE row per top-level block: `id = "{key}.{block_ix}"`, `version = (fnv1a(block bytes) << 1) \| streaming`, kind `LiveMarkdown` if streaming else `Markdown`. (:1365-1406) |
| `Input` | Flush the group. `header = single_line(questions[0].header)` or `"Question"`. Row id `"{entry.id}#{part_id}"`, `version = fnv1a(header) << 1 \| resolved`. (:1407-1433) |
| `Error` | Flush the group. `message = single_line(message)`. Row id `"{entry.id}#{part_id}"`, `version = message.len()`. (:1434-1450) |

Group flush (`flush_group`, :1259-1279): id `"{entry.id}#g{group_ix}"`,
`version = tool_fingerprint(tools, auto_open)`,
`auto_open = streaming && group_last_part_ix == last_part_ix`,
`turn_start = false`, `timestamp = None`, `copy_text = None`.

After the walk: the first row gets `turn_start = true`; when NOT streaming the
LAST row gets `timestamp = Some(entry.created_at)`,
`copy_text = assistant_copy_text(entry)` and `version ^= 1 << 62` (so the diff
key changes when streaming flips off even for chip kinds whose own version would
not).

`assistant_copy_text` (:1150) — authored `Text` parts only (non-blank after
trim), original bytes preserved, joined with `"\n\n"`; tool traces and every
other structured part are excluded. `None` when empty.

Desktop tests: `reasoning_joins_the_tool_group_accordion`,
`live_thought_streams_open_and_settles_closed`,
`live_entry_splits_per_block_with_id_continuity`,
`live_commit_changes_only_tail_row_versions`,
`consecutive_tools_fold_into_groups_between_text`,
`agent_calls_split_out_of_ordinary_tool_groups`,
`stray_subagent_ref_on_a_run_chip_stays_an_ordinary_tool`,
`lone_completed_agent_stays_uncollapsed`,
`pre_spawn_agent_name_is_enough_to_split`,
`trailing_group_auto_opens_only_while_streaming`,
`user_rows_and_echo_versions`, `user_rows_split_attachment_refs_from_text`,
`user_rows_project_file_mentions_into_chips`,
`timestamp_strip_lands_on_the_last_settled_row`,
`message_copy_keeps_authored_text_and_excludes_tool_traces`,
`empty_text_parts_produce_no_rows`.

### 3.2.1 `RowKind` / `Row` (transcript.rs:955, :1006)

```
enum RowKind {
  User {
    text,            // visible prompt, attachment-ref trailer already stripped;
                     // the PROJECTED display text when the prompt carries file
                     // mentions (chip labels in place of the raw Markdown links)
    mentions,        // file-mention chips over `text`, in display-byte terms.
                     // Computed once per entry change in rows_for_entry (rows
                     // are cached by fingerprint), never per frame. Empty for
                     // ordinary prompts.
    attachments,     // image refs parsed out of the message text; thumbnails
                     // load from the owning device via ReadAttachmentChunk
    badges,          // context the prompt folded in as text, lifted back out
    pending,         // optimistic echo not yet confirmed by a doc frame
  },
  Markdown     { tree, block_ix },   // one top-level block of a completed message
  LiveMarkdown { tree, block_ix },   // one top-level block of a STREAMING message.
                                     // Split per block like completed rows (only
                                     // the tail blocks' versions change per commit,
                                     // so the settled prefix is never respliced or
                                     // re-rendered); rendered with the fade veil
  ToolGroup    { tools, auto_open },
  InputChip    { header, resolved }, // header = first question's header; the
                                     // unresolved chip shows "Awaiting your
                                     // answer…" — which stays TRUE even across a
                                     // run death
  ErrorChip    { message },
}

struct Row {
  id,          // stable id
  version,     // content version (diff key)
  turn_start,  // first row of its message entry (gets the turn gap)
  kind,
  entry_id,    // the owning message entry — hover anywhere on the entry's rows
               // reveals its timestamp strip
  timestamp,   // epoch-ms for the hover strip UNDER this row: set on the LAST row
               // of a completed entry (user rows always; assistant rows only once
               // streaming ends — "the turn isn't at a time yet")
  copy_text,   // text copied by the entry-level hover action. Present only on the
               // last settled row, beside the timestamp; tools and transport-only
               // metadata are deliberately excluded
}
```

The web's `TranscriptRowKind` / `TranscriptRow` (`lib/transcript.ts:675`, `:695`)
already carry most of this; confirm `mentions`, `badges` and `pending` exist and
that `timestamp` is set on user rows **always**.

### 3.3 `top_gap_for(prev, row) -> f32` (:1630)

```
if row.turn_start                          -> SPACE_LG (16)
if prev and row are BOTH markdown kinds
   and part_prefix(prev.id) == part_prefix(row.id)
                                           -> MD_BLOCK_GAP (12)
if row is ToolGroup OR prev is ToolGroup   -> SPACE_MD (12)
otherwise                                  -> SPACE_SM (8)
```

`part_prefix(id)` = everything before the last `'.'` (or the whole id) —
"markdown row ids are `{entry}#{part}.{blockIx}`, so the part prefix is
everything before the block index" (transcript.rs:1618-1622).

Two shipped web bugs to fix here (`lib/transcript.ts:962-976`):
1. the markdown clause **requires BOTH sides to be markdown kinds**; the web
   port omits that check;
2. the web's `prev.kind === "toolGroup"` branch returns 8; it must return
   `SPACE_MD` (12).

Test: `split_sibling_gaps_match_live_internal_spacing`.

### 3.4 User collapse helpers

- `user_message_needs_collapse(text)` (:1170) — `lines().count() > 5 ||
  chars().count() > 400`. A conservative first-frame proxy only; the measured
  wrapped-line count supersedes it. (Already ported.)
- `user_resize_duration_ms(Δ)` (:1178) — `round(min(220 + max(Δ,0)·0.32, 850))`.
- `user_resize_spec(Δ)` (:1185) — `MotionSpec::new(user_resize_duration_ms(Δ),
  Δ > 500 ? EASE_IN_OUT : EASE_OUT)`.

Tests: `long_prompts_collapse_and_short_ones_do_not`,
`user_resize_duration_scales_with_distance_and_stays_bounded`,
`expanding_a_prompt_is_not_a_row_change`.

### 3.5 `parse_for_row` / `ParseOutcome` (transcript.rs:1557, :1579)

> **Not covered by research 02** — transcribed from the Rust for this ticket.
> `parse_for_row` is "the transcript's markdown parse wiring, extracted for
> testability: one call per text part per sync."

```
parse_for_row(streaming, key, text, live_parsers, tree_cache) -> (tree, ParseOutcome)

streaming:
  parser = live_parsers.entry(key).or_default()
  parser.set_text(text)                 // O(tail) append path for prefix extensions
  tree = parser.display_tree()          // hanging inline markers MENDED, so closers
                                        // arriving later never reflow painted text
  outcome = Incremental { parsed_bytes: parser.last_parse_bytes(),
                          stable_prefix_blocks: parser.stable_prefix_blocks() }

not streaming:
  if tree_cache[key] is (len, tree) and len == text.len()  -> (tree, Cached)
  if live_parsers.remove(key) is a parser with source() == text
                                                           -> (parser.tree(), Handoff)
  else                                                     -> (parse_full(text), Full)
  tree_cache[key] = (text.len(), tree)
```

`ParseOutcome` variants and why they exist ("carries the incremental parser's
work counters so callers and tests can see that per-append parse work is bounded
by the reparsed tail, never the whole accumulated reply"):

| Variant | Meaning |
|---|---|
| `Incremental { parsed_bytes, stable_prefix_blocks }` | streaming row: the live parser advanced by one commit. `parsed_bytes` = bytes fed through `parse_full` for this commit (the reparse tail); `stable_prefix_blocks` = leading top-level blocks left untouched (render caches stay valid) |
| `Cached` | completed row served from the settled tree cache (no parse at all) |
| `Handoff` | live→complete handoff: the live parser's exact tree was adopted — "the split rows then share the exact tree the unsplit row painted, guaranteeing a flicker-free handoff" |
| `Full` | completed row parsed from scratch |

Web mapping: `lib/markdown.ts::MarkdownCache` + `parseMarkdown(source, mendTail)`
already provide the two trees (mend the tail while streaming; canonical when
settled). Wrap them in a `parseForRow` with the same four outcomes and assert
the streaming path mends and the settle path reuses the live tree when the
sources match. Desktop tests: `ParseOutcome::Incremental` assertions at
transcript.rs:8085, `Handoff` at :8117, `Cached` at :8120.

### 3.6 Working-trailer helpers

- `flavour_word(seed, elapsed_secs)` (:1919) —
  `FLAVOUR_WORDS[(seed + max(elapsed,0)/FLAVOUR_ROTATE_SECS(7)) % 21]`.
  The 21 words, **in order**: `"Robocoing"`, `"Thinking"`, `"Pondering"`,
  `"Scheming"`, `"Brewing"`, `"Weaving"`, `"Tinkering"`, `"Musing"`,
  `"Composing"`, `"Sifting"`, `"Untangling"`, `"Distilling"`, `"Sketching"`,
  `"Plotting"`, `"Riffing"`, `"Combobulating"`, `"Percolating"`, `"Marinating"`,
  `"Noodling"`, `"Puzzling"`, `"Conjuring"`.
- `flavour_seed(chat_id)` (:1925) — `fnv1a(chat_id.as_bytes())`.
- `format_elapsed(secs)` (:1945) — `secs < 60` → `"{s}s"`, else `"{m}m {s}s"`.
- `sending_bridge(send_started, turn_started)` (:1933) —
  `(Some(send), Some(turn))` → `turn <= send`; `(Some(_), None)` → `true`;
  `(None, _)` → `false`.

Tests: `flavour_words_rotate_every_seven_seconds`,
`sending_bridge_holds_until_the_turn_outdates_the_send`.

### 3.7 `selection_scroll_step(bounds, position)` (:142)

```
height = bounds.height; if height <= 0 -> 0
edge   = min(SELECTION_SCROLL_EDGE_PX(36), height/3); if edge <= 0 -> 0
scaled(pen) = SELECTION_SCROLL_MAX_STEP_PX(24) · t²   where t = clamp(pen/edge, 0, 1)
y < top + edge     -> −scaled(top + edge − y)
y > bottom − edge  -> +scaled(y − (bottom − edge))
otherwise          -> 0
```

Positive offsets move toward the document bottom; the tick cadence is
`SELECTION_SCROLL_TICK_MS` = 24ms. Test:
`selection_scroll_ramps_at_viewport_edges`.

### 3.8 `diff_rows(old, new)` (:1651)

Common prefix and suffix by `(id, version)` equality; `None` when identical;
otherwise `Some((prefix .. old.len()−suffix, new.len()−suffix−prefix))`.
`sync` applies it as `remeasure_items(range)` when `old_range.len() == count`
(in-place content change — notably the live→complete flip where every row's
version changes but ids are identical; a splice would reset heights to
Unmeasured and clobber the scroll anchor) and `splice(range, count)` otherwise
(:4197-4213). The web virtualizer's equivalent: on an in-place change keep the
measured heights and the anchor; on a splice invalidate only the spliced range.

Tests: `diff_rows_appends_and_middle_edits`, `diff_handles_live_to_split_growth`.

### 3.9 `format_timestamp(ms, tz)` (:1029)

`chrono` format `"%b %-d, %-I:%M %p"` — short month, numeric day with NO leading
zero, 12-hour clock with NO leading zero, 2-digit minutes, `AM`/`PM`. Empty
string for an invalid timestamp. Rendered in local time. (Already ported —
confirm the no-leading-zero rules.)

### 3.10 `fnv1a` and fingerprints

`fnv1a` (:1042) — 64-bit, offset basis `0xcbf29ce484222325`, prime
`0x100000001b3`, wrapping multiply, over BYTES. (Already ported at
`lib/transcript.ts:657`.)

`entry_fingerprint(entry, pending)` (:7446) — FNV-1a over the entry id, a status
byte (`0` none, `1` Streaming, `2` Complete, `3` Aborted), the `pending` byte,
and per part: the part id, its `byte_len` as u64, plus for tools the
`is_error|resolved<<1` byte, the `subagent_ref/status` byte and the tail bytes,
and for inputs `0x10 | resolved`. **Streaming entries bypass the cache entirely**
(fingerprint 0, always rebuilt).

`tool_fingerprint` is ticket 19's.

---

## 4. Gaps this ticket closes

Copied verbatim from research 02 §5, filtered to this ticket.

| # | Item | Kind | Desktop value | Web value | Fix |
|---|---|---|---|---|---|
| 1 | Working-indicator trailer | **MISSING** | `render_working_trailer` under the last row: `gradient_spinner(cell 2.5)` + rotating flavour word + `format_elapsed` timer, `pt(16) gap(8)` | Nothing — the web transcript never renders a working row | Port `render_working_trailer`, `flavour_word`, `flavour_seed`, `format_elapsed`, `sending_bridge`; render `MatrixSpinner size≈12` (cell 2.5) |
| 2 | "Not delivered — click to retry" | **MISSING** | `theme.danger`, `pt(16)`, click → `RETRY_DELIVERY` | absent | Add the failed-send branch of the trailer |
| 3 | `"Queued — will send automatically"` / `"Sending…"` | **MISSING** | strings at transcript.rs:5287-5292 | absent | Add with `theme.warning` for queued |
| 7 | Row horizontal gutters | **WRONG VALUE** | `px(48)` around the 736px column | `.transcript-col { padding: 8px var(--rb-space-lg) }` = 16px (app.css:3275) | Set 48px gutters, `max-width: 736px`, `padding-top: 8px` removed |
| 8 | First row top gap | **WRONG VALUE** | `TITLEBAR_HEIGHT + SPACE_LG + 10` = **64** | 8px top padding on the column | Apply 64px to row index 0 (16px in the subagent surface) |
| 9 | Last row bottom pad | **MISSING** | `bottom_clearance + TRANSCRIPT_FADE_BAND(24) + 8` | fixed `--rb-space-lg` bottom padding | Measure the composer/status stack height and pad the last row by it |
| 10 | `topGapFor` markdown clause | **WRONG BEHAVIOR** | requires BOTH rows to be markdown kinds AND share the part prefix | only checks the part prefix (lib/transcript.ts:964) | Add the kind check |
| 11 | `topGapFor` tool-group clause | **MATCHES** (by luck) | `row is ToolGroup \|\| prev is ToolGroup` → 12 | separate branches; the `prev is ToolGroup` branch returns 8, not 12 (lib/transcript.ts:973-976) | Make `prev.kind === "toolGroup"` return `SPACE_MD` (12) |
| 12 | Hover strip height | **WRONG VALUE** | reserved lane `h(32) pt(8)` | `.row-meta { height: 16px; margin-top: 2px }` (app.css:3486) | 32px tall, 8px top padding, always reserved |
| 13 | Hover strip alignment | **MISSING** | `justify_end` for user rows, start for assistant | always start | Add the user-row branch |
| 14 | Copy action | **WRONG BEHAVIOR** | 24px icon button, `rounded(6)`, `ink(0.08)` hover wash, `COPY`→`CHECK` icon swap, clears after 1200ms | a text button reading `"Copy"` / `"Copied"` (transcript.tsx:556) | Use the icon button + icon swap |
| 15 | Timestamp color | **WRONG VALUE** | `theme.text_muted.opacity(0.55)`, size 12px | `--rb-text-faint`, 11px | Use `text-muted @ 55%`, 12px |
| 16 | User-bubble collapse | **WRONG BEHAVIOR** | measured wrapped-line height, animated `user_resize_spec` height tween + matched viewport compensation, long-press toggle | CSS `-webkit-line-clamp: 5`, instant toggle, no viewport compensation, no long-press | Port `render_user_body` height logic, `user_resize_duration_ms`, `step_user_collapse_scroll`, 360ms hold-to-toggle |
| 17 | Expander label | **WRONG VALUE** | `"Show more"` / `"Show less"` at 14px with a 12px `alt-arrow-down/up` icon at `gap(5)`, `mt(8)` | `"▾ Show more"` / `"▴ Show less"` at 12px, `--rb-text-faint` (transcript.tsx:596, app.css:3470) | 14px, `text-muted` → `text` on hover, real icons |
| 18 | File-mention chips in the bubble | **MISSING** | mono runs at `theme.code_text` over a `rounded(5)` `theme.code_wash` underlay | plain text (the projection happens in `rowsForEntry` but nothing styles it) | Wrap the spans in `<code>` with `code_wash` background |
| 53 | Input chip | **WRONG VALUE** | `py(4)` wrapper, `h(34) rounded(10) border hairline(0.08) bg(ink(0.045)) px(8) gap(8)`, 20px `ink(0.09)` tile with a 12px `chat-round-line`, MEDIUM `"Question"` label, value `text.opacity(0.9)`. **Resolution never recolors it** | `min-height 30px; padding 0 10px; radius --rb-radius-control`, `?` glyph, no label, and `.input-chip-open` recolors the border to the accent (app.css:4049-4076) | Rebuild exactly; drop the accent recolor |
| 54 | Error chip | **WRONG VALUE** | `py(4)` wrapper, `min_h(34) rounded(10) border danger@16% bg danger@5% px(8) py(7) gap(8)`, 20px `danger@12%` tile with a 12px `danger-triangle` at `danger_muted@80%`, MEDIUM `"Error"` label, message WRAPS at `text.opacity(0.8)` | `min-height 30px`, `!` glyph, no `"Error"` label, message truncates with ellipsis (app.css:4078-4100) | Rebuild; the message must wrap |
| 69 | `StatusDot` | **INVENTED (in this surface)** | the transcript has no status dots | `status-dot.tsx` — used by the chat list, not the transcript | Keep out of the transcript |
| 70 | Row virtualization anchor | **WRONG BEHAVIOR** | `ViewportAnchor` by row id with entry/index fallbacks, `SavedViewport::{FollowTail, Anchored}` per chat (LRU 256), restored only after a populated replay | a single `anchorRef` captured on scroll; no per-chat memory | Port `ViewportAnchor`/`SavedViewport`/`SavedViewportCache` |
| 71 | Own-turn runway | **MISSING** | `OwnTurnAnchor` — a locally-sent prompt is held at `OWN_SEND_TOP_INSET_PX` (48) from the viewport top with a reserved minimum last-row height; entry glide retains `OWN_SEND_GLIDE_RETAIN` 0.85/frame and snaps within 1px; wheel releases the hold but keeps the reservation | absent | Port; it is what makes a send feel anchored rather than bottom-pinned |
| 72 | Spring driver | **MATCHES** | `StickSpring` + `handle_scroll`/`step_spring` | `stick-spring.ts` + `stick-controller.ts` are a faithful port | — |
| 73 | `shouldRestick` / `jumpVisibility` | **MATCHES** | as §4.8 | identical | — |
| 74 | Initial open | **MATCHES** | first fill lands at the bottom instantly (`scroll_to_end`) | `snapToEnd()` on first `loaded` | — |
| 75 | Empty state | **INVENTED** | the desktop transcript renders NOTHING when empty (the shell shows the new-chat hero) | `"No messages yet."` / `"Loading…"` paragraphs (transcript.tsx:78, 377, 426) | Remove; let the shell own the empty case |
| 76 | `.transcript-error` banner | **INVENTED** | no such banner; the desktop shows the 24px `"Engine off. Cached history is read-only."` / `"Reconnecting… Cached history is read-only."` strip above the list | a floating red card with a Retry button (app.css:3420) | Replace with the 24px strip and the two exact strings |
| 77 | `.status-strip` | **INVENTED here** | the desktop reserves the strip in the SHELL, not the transcript | rendered inside `.transcript-wrap` | Move to the shell |
| 78 | Reduced motion | **MISSING** | every tween checks `motion::reduced_motion(cx)`: no veil, no reveal, no shimmer, no fold tween, snap scrolls | only the stick controller checks `prefers-reduced-motion` | Gate the veil, chip reveal, shimmer, folds and the glide — *this ticket's share: the user-fold tween, the jump/rail glide and every programmatic scroll snap; chip reveal + shimmer are ticket 19, the veil is ticket 21* |
| 79 | Text selection across rows | **MISSING** | a frame-ordered selection registry with edge auto-scroll (`selection_scroll_step`) | native DOM selection only | Native selection is acceptable, but edge auto-scroll during a drag is still needed |

---

## 5. Do not

- Do not re-add `"No messages yet."`, `"Loading…"` inside the transcript, the
  floating `.transcript-error` card with its Retry button, or `.status-strip`
  inside `.transcript-wrap` (§5 rows 75–77).
- Do not use `status-dot.tsx` anywhere in the transcript (§5 row 69). It belongs
  to the chat list.
- Do not recolor the input chip on resolution (`.input-chip-open`) or truncate
  the error chip's message.
- Do not build the tool group, its activity tree, chips, details, thought rows
  or subagent chips — **ticket 19**. Leave `ToolGroupRowView`/`ToolChipView`
  alone except where `topGapFor` and the row shell touch them.
- Do not build the message rail, the badge pill/card, the context-usage tooltip
  or fix the spinners — **ticket 20**. Render `MatrixSpinner` as it exists; 20
  corrects its phase and tints.
- Do not restyle markdown blocks, links, code fences, tables, images or the veil
  math — **ticket 21**. This ticket only wires `RenderOptions`-equivalent state
  (veil on/off, copy state, row key).
- Do not build attachment thumbnails, their loading/error states, the upload
  ring, appshot cards or the lightbox — **ticket 17**. Keep the slot.
- Do not build the jump-to-bottom pill's chrome — the desktop renders it in the
  SHELL over the conversation region so it paints above the bottom fade
  (transcript.rs:7632-7635); **ticket 06** owns it. This ticket owns
  `jumpVisibility`/`jumpToBottom` state only.
- Do not port desktop-only instrumentation: `ROBOCO_FRAME_STATS`,
  `ROBOCO_NO_RENDER_CACHE`, `ROBOCO_SCROLL_TRACE`, `RenderCache`,
  `SyntaxHighlightCache`, `frost::frosted` scene layering, `gpui::deferred`,
  `motion::pulse_lease`, `image_media.rs` decode.
- Do not add a "Steer" affordance or any composer behavior — settled: the web
  has Send / Queue / Stop only.
- Do not extend the phone layer; desktop widths (≥768px) only.

---

## 6. Acceptance

- [ ] Rows sit in a 736px column with 48px gutters; row 0 has a 64px top gap
      (16px in the subagent instance); the last row's bottom pad is the measured
      shell clearance + 24 + 8.
- [ ] `topGapFor` returns 16 / 12 / 12 / 8 per §3.3, including the both-markdown
      guard and `prev.kind === "toolGroup"` → 12.
- [ ] The hover lane is 32px tall with 8px top padding, always reserved;
      right-aligned on user rows; timestamp is 12px `text-muted @ 55%` in the
      `"Jul 1, 3:45 PM"` shape; the copy affordance is a 24px icon button that
      swaps COPY→CHECK for 1200ms with no text label.
- [ ] A >5-line prompt collapses to 110px + an ellipsis row, expands on click or
      on a 360ms long press, tweens over `user_resize_spec`, and the row's top
      stays put via the compensation scroll; a drag-select over the bubble never
      toggles it.
- [ ] "Show more"/"Show less" render at 14px `text-muted` with the 12px
      alt-arrow icons and an 8px top gap.
- [ ] File mentions inside a bubble render as mono `code_text` runs over a 5px
      `code_wash` chip.
- [ ] Question and error chips match §2.9/§2.10 exactly; the error message wraps.
- [ ] While a run is live the working trailer shows the matrix spinner, a flavour
      word that changes every 7s, and an elapsed timer; queued shows
      `"Queued — will send automatically"` in `warning`; sending shows
      `"Sending…"` with no timer; an undelivered send shows
      `"Not delivered — click to retry"` in `danger` and fires `RETRY_DELIVERY`.
- [ ] Sending a message parks the new prompt 48px below the viewport top and
      keeps it there while the reply streams; a wheel gesture releases the hold
      but keeps the reservation; leaving and returning to the chat restores the
      viewport (or keeps following the tail).
- [ ] The engine-offline strip is a 24px bar above the list with the two exact
      strings; no floating error card, no empty-state paragraphs.
- [ ] Dragging a selection to the top/bottom edge auto-scrolls with the `t²` ramp
      at a 24ms cadence.
- [ ] Under `prefers-reduced-motion: reduce` the user fold and every programmatic
      scroll snap instead of animating.
- [ ] Unit tests in `web/packages/app/tests/transcript-model.test.ts`:
      `split_sibling_gaps_match_live_internal_spacing` →
      "topGapFor requires both rows to be markdown and 12 after a tool group";
      `user_resize_duration_scales_with_distance_and_stays_bounded`;
      `long_prompts_collapse_and_short_ones_do_not`;
      `flavour_words_rotate_every_seven_seconds`;
      `sending_bridge_holds_until_the_turn_outdates_the_send`;
      `selection_scroll_ramps_at_viewport_edges`;
      `jump_button_stays_available_when_scrolling_down_until_near_bottom`;
      `restick_is_direction_aware`;
      `only_a_stream_at_the_bottom_gets_a_hard_end_anchor`;
      `diff_rows_appends_and_middle_edits`;
      `diff_handles_live_to_split_growth`;
      `timestamp_strip_lands_on_the_last_settled_row`;
      `user_rows_project_file_mentions_into_chips`;
      `message_copy_keeps_authored_text_and_excludes_tool_traces`;
      plus `ViewportAnchor::resolve` fallback and
      `PendingQueuedTurns::take_latest_materialized` cases.
- [ ] Screenshot pair, desktop vs web, states: (a) settled chat, last assistant
      row hovered (timestamp + copy visible); (b) long user prompt collapsed,
      then expanded; (c) live run with the working trailer showing spinner +
      flavour word + timer; (d) a send just dispatched, prompt parked 48px from
      the top; (e) question chip and error chip in one transcript; (f) engine
      offline strip.
- [ ] `pnpm -r build` green; `pnpm --filter @roboco/app test` green.
- [ ] No new literal hex/px where a `--rb-*` token exists.

## Comments

(empty; appended during implementation)
