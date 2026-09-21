# 23 — Review comments

**What to build:** A user can hover a diff line in the Changes pane (either
column in split mode's right side, any line in unified) and click a "+"
that opens an inline draft to write a note; committing the draft stages a
`ReviewComment` that shows as a small card anchored to that line. The
composer shows a "N comments" pill above the input while any are staged
for the current chat; sending folds every staged comment into the prompt
as plain text (and clears the staged set) or, if the prompt is otherwise
empty, sends a stand-in "Address the review comments below." body. Once
sent, the transcript shows the same "N comments" pill above the user
bubble, with the comment text stripped out of the visible body and
available on hover. The same adder/card/draft affordance also appears in
the Files preview's code editor gutter, for comments anchored to a
workspace file line rather than a diff line.

**Blocked by:** 13 (Composer core), 18 (Transcript rows), 22 (Changes
pane). This ticket needs 22's diff-line row components
(`UnifiedLineRow`/`SplitLineRow`) to already expose the hover-hook point
they were built with, 18's transcript row rendering for the badge, and
13's composer send-path for the chip and the send-time text fold-in.

**Status:** done

**Research:** `../../web-client/research/06-queue-attachments-comments.md`
§1, §2, §3.7, §3.8, §4 (comment-related rows), §5 (comment-related rows),
§6, §7#4. `../../web-client/research/10-files-preview.md` §3.2 "Editor
inline review comments" (full subsection, including
`render_editor_comment_card`/`render_editor_comment_draft`).
`../../web-client/research/02-transcript.md` §3.20 `badges::render`
(full section) and §5 row 19 "Message badges".

**Desktop reference (for lookups only):** `crates/ui/src/comments.rs`
(422 lines, full — the `ReviewComment` model and text codec),
`crates/ui/src/comment_ui.rs` (316 lines, full — the diff-side
adder/draft/card/edit-pen), `crates/ui/src/preview.rs:2784-3060` (editor-side
overlay: gutter icon, `render_editor_comment_card` 2896-2985,
`render_editor_comment_draft` 2987-3060), `crates/ui/src/composer.rs::render_comments_chip`
(4608), `crates/ui/src/badges.rs` (252 lines, full — the extractor
registry and pill), `crates/ui/src/changes.rs` (diff-comment wiring only,
~2580-2820, 3100-3300, 4540-4600).

**Web files to touch:**
| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/lib/review-comments.ts` | new | `ReviewComment`, `withComments`, `extractBadge`, `chipLabel`, `parseBullets`, `cardHeight`, `cardBodyLines`, `citePath`, `COMMENT_ONLY_TEXT`, `COMMENT_BLOCK_HEADER`, `REVIEW_COMMENT_BLOCK_HEADER` |
| `web/packages/app/src/state/review-comments.ts` | new | `ReviewCommentStore` (per-chat-key staged list, keyed like `attachment-cache.ts`'s `stagedByChat`) |
| `web/packages/app/src/components/review-comments/comment-adder.tsx` | new | `CommentAdder` (diff-side "+") |
| `web/packages/app/src/components/review-comments/comment-draft.tsx` | new | `CommentDraft` (diff-side inline compose/edit) |
| `web/packages/app/src/components/review-comments/comment-card.tsx` | new | `CommentCard`, `CommentEditButton` (diff-side staged card, shared edit-pen) |
| `web/packages/app/src/components/review-comments/comments-chip.tsx` | new | `CommentsChip` (composer pill) |
| `web/packages/app/src/components/diff-view.tsx` | edit | wire `CommentAdder`/`CommentDraft`/`CommentCard` into `UnifiedLineRow`/`SplitLineRow`'s hover-hook point (from ticket 22) |
| `web/packages/app/src/components/composer.tsx` | edit | mount `CommentsChip` above `AttachmentStrip`; fold staged comments into the send payload |
| `web/packages/app/src/lib/composer-actions.ts` | edit | `sendRun`/send helpers: append `withComments(text, comments)` before dispatch, clear staged comments on successful send |
| `web/packages/app/src/components/files/file-viewer.tsx` | edit | `TextViewer` — add a per-line gutter column hosting the editor-side comment icon/adder (see §1's flagged gap) |
| `web/packages/app/src/components/transcript.tsx` | edit | `UserRow` — run `extractBadge` on the bubble text, render the badge pill above it |
| `web/packages/app/src/styles/app.css` | edit | `.comment-adder`, `.comment-card`, `.comment-draft`, `.comment-accent`, `.comment-action`, `.comments-chip`, `.badge`, `.badge-card` rule blocks |
| `web/packages/app/tests/review-comments.test.ts` | new | pure-logic unit tests (see §3) |

## 1. Context a fresh session needs

- **This feature does not exist on web at all today.** A full-tree grep of
  `web/packages/app/src` for `ReviewComment`/comment-adder/comment-chip/
  comment-draft/badge returns nothing except the change-request badge
  (unrelated — that's ticket 22's `ChangeRequestBadge`, a completely
  different "badge" concept from the message badge this ticket builds).
  Do not confuse the two: `change-request-badge.tsx` is PR/MR pills;
  this ticket's `badges.rs` port is "N comments" pills on chat messages.
- Three surfaces meet here: the Changes pane (diff-side adder/draft/card,
  from `comment_ui.rs`), the composer (a read-only "chip" summarizing the
  staged count), and the transcript (a read-only pill on a SENT message,
  extracted by parsing the message text back out). A comment's lifecycle:
  created in the diff → staged (visible as a card in the diff AND a chip
  in the composer) → sent (folded into the prompt text, staged set
  cleared) → shown forever after as a badge on that historical message.
- The desktop model (`comments.rs::ReviewComment`) is `{id, path, line,
  body, source: Diff{side, old_path} | File}`. A `Diff`-sourced comment
  anchors to a diff line (`side` = Old/New, relative to a rename's
  `old_path` when on the Old side); a `File`-sourced comment anchors to a
  plain workspace file line (used by the Files preview's editor overlay,
  §2.6-2.7 below) — same model, different anchor context.
  `cite_path()` returns `old_path` for an Old-side diff comment (that line
  only ever existed in the pre-rename file) else `path`.
  `location()` = `"{cite_path()}:{line}"`.
- The text codec (`with_comments`/`extract_badge`) is the load-bearing
  part: comments are NEVER sent as structured wire data — they are
  appended to the plain-text prompt as a bullet list under one of two
  verbatim headers, and the transcript recovers them by pattern-matching
  that block back out of the SENT message's text. Get the round-trip
  exactly right (see §3) — every edge case in the desktop's test suite
  exists because a naive splitter breaks on real input (a comment body
  that itself contains `"(L): "`, a renamed file's Old-side citation, a
  path containing a colon).
- Storage keying: comments are staged PER CHAT-KEY, the same key shape
  `attachments.ts`/`attachment-cache.ts` already use for staged
  attachments (see ticket 17) — reuse that keying convention in
  `state/review-comments.ts` (a `Map<chatKey, ReviewComment[]>`) so a
  chat switch doesn't leak one chat's drafts into another's chip.
- **The Files preview editor is currently a plain `<textarea
  className="files-editor">` with NO gutter, NO line numbers, and NO
  per-line addressability** (`components/files/file-viewer.tsx::TextViewer`,
  `showEditor` branch). The desktop's editor-side overlay
  (`preview.rs:2784-3060`) anchors to a real per-line gutter cell that
  does not exist on web yet — building a full line-numbered code editor is
  ticket 25's job ("Files preview and editor"), NOT this ticket's. This
  ticket's file-viewer.tsx work is therefore SCOPED DOWN: add the minimal
  per-line gutter affordance needed to host the comment icon/adder (a
  `<pre>`-based line-numbered display is enough; it does not need to be a
  full editor). If ticket 25 lands a real gutter before this ticket is
  implemented, wire into THAT gutter instead of building a parallel one —
  check `file-viewer.tsx` for a `.files-gutter`/line-cell class before
  adding your own. Flag whichever path you took in Comments.
- Tokens: no literal hex; `theme.solid`/`on_solid` → `--rb-solid`/
  `--rb-on-solid`; the shared HOVER_FADE motion (150ms, `easeTailwind`)
  drives every hover-reveal (edit-pen, remove-×, adder) — use
  `--rb-motion-hover-fade` / `--rb-ease-tailwind`.
- Vocabulary: "comment", never "note" or "annotation"; "chat," never
  "session" or "thread," in any string this ticket adds.

## 2. Spec

### 2.1 `ReviewComment` model and text codec

Not a rendered component — the data model and its serialization, shared
by every piece below.

**Fields**: `{id: string, path: string, line: number, body: string,
source: {kind: "diff", side: "old" | "new", oldPath: string | null} |
{kind: "file"}}`.

**`citePath(comment)`**: `oldPath` when `source.kind === "diff" &&
source.side === "old" && oldPath !== null`, else `path`.

**`location(comment)`** = `` `${citePath(comment)}:${comment.line}` ``.

**Constants (verbatim)**:
- `COMMENT_BLOCK_HEADER` = `"Comments on the diff (each cites the file and line it belongs to; L = line number in the original file, R = in the changed file):"`
- `REVIEW_COMMENT_BLOCK_HEADER` = `"Review comments (each cites the workspace file and line it belongs to):"`
- `COMMENT_ONLY_TEXT` = `"Address the review comments below."`

**`withComments(text, comments) -> string`**: if `comments.length === 0`,
return `text` unchanged. Otherwise: `body = text.trim().length === 0 ?
COMMENT_ONLY_TEXT : text`; pick the header — `COMMENT_BLOCK_HEADER` when
EVERY comment is diff-sourced, `REVIEW_COMMENT_BLOCK_HEADER` as soon as
ANY comment is file-sourced; append `` `\n\n${header}\n` `` then one
bullet per comment: `` `- ${location}${sideMarker}: ${bodyIndented}` ``
where `sideMarker` is `" (L)"`/`" (R)"` for a diff comment (`side ===
"old" ? "L" : "R"`) or empty for a file comment, and `bodyIndented` is
`comment.body.trim().replace(/\n/g, "\n  ")` (continuation lines get a
2-space indent so they round-trip as `"  "`-prefixed lines, not new
bullets).
- Diff-comment bullet form: `"- src/main.rs:42 (R): early-return here"`.
- File-comment bullet form: `"- notes.md:3: plain file comment"` (no side
  marker).

**`extractBadge(text) -> [string, MessageBadge] | null`**: matched ONLY as
a whole TRAILING block (a prompt quoting the header mid-body is left
alone). Search for the LAST occurrence of either header written as
`` `\n\n${HEADER}\n` `` (try both headers, take whichever occurs latest in
the string). If found, validate ALL three:
1. the block after the marker is non-empty;
2. every line in the block starts with `"- "` (a bullet) or `"  "` (a
   continuation);
3. `parseBullets` on the block yields at least one detail.

If any check fails, return `null` (the text is left alone — a false
match would corrupt an otherwise-plain message). On success, return
`[text.slice(0, matchStart), { icon: "chatRoundLine", label:
chipLabel(details.length), details }]` — i.e. the block AND its two
leading newlines are stripped from the bubble text.

**`parseBullets(block) -> BadgeDetail[]`**: walk lines. A line starting
`"  "` that is NOT itself a bullet (`"- "`) appends to the PREVIOUS
detail's body as `` `${body}\n${indentedLineWithPrefixStripped}` `` — this
is how a multi-line comment survives the round trip. For a `"- "` bullet,
compute TWO candidate split points and take whichever occurs EARLIEST in
the string (earliest-wins is load-bearing — a body may itself contain
`"(L): "` text, and matching that first would swallow the body into the
location):
- the **side marker**: `" (L): "` or `" (R): "` (search both, take
  whichever position is found, if any);
- **file-bullet split**: the first `": "` whose PRECEDING text ends in
  `:{digits}` (i.e. the text up to that point parses as `"{path}:{line}"`
  where `{line}` is a valid integer).

Given the winning split point, `location = text before it`, `tag =
"L"|"R"|null` (only set when the side-marker split won), `body = text
after it`.

**`chipLabel(count) -> string`**: `"1 comment"` when `count === 1`, else
`` `${count} comments` ``.

**`cardBodyLines(body) -> number`**: `CARD_WRAP_COLUMNS = 64`. For each
line in `body.split("\n")`, `Math.ceil(line.length / 64)` (min 1 per
source line), summed, then clamped to `[1, CARD_MAX_LINES=8]`.

**`cardHeight(body) -> number`**: `CARD_PAD_V(20) + CARD_HEADER_HEIGHT(22)
+ cardBodyLines(body) * CARD_LINE_HEIGHT(18) + CARD_GAP(6)`.

### 2.2 Comment adder (`CommentAdder`)

**Layout**

| property | value | source |
|---|---|---|
| size | `COMMENT_ADDER_SIZE` = **16px** square | comment_ui.rs:18-46 |
| shape | `rounded(4)`, `bg(theme.solid)` | comment_ui.rs:18-46 |
| icon | `PLUS`, 11px, `theme.on_solid` | comment_ui.rs:18-46 |
| a11y | `role="button"`, `aria-label="Add comment"` | comment_ui.rs:18-46 |
| position | absolute, `left = comment_adder_left(side, gutterPx)` | changes.rs:4346-4357 (call site) |

`comment_adder_left(side, gutterPx) = ACCENT_BAR_WIDTH(3) + (side ===
"new" ? gutterPx : 0) + (gutterPx - COMMENT_ADDER_SIZE) / 2`.

**Children**: none (icon-only).

**States**: rendered ONLY while the owning line is hovered (mount/unmount,
not opacity — matches ticket 22's hover-hook contract).

**Interactions**: `onMouseDown` stops propagation (must not steal the
row's own hover/click handling); click opens a draft at `(path, side,
line)`.

**Text**: aria-label `"Add comment"`.

### 2.3 Comment draft (`CommentDraft`, diff-side)

**Layout**: fixed height `DRAFT_CARD_HEIGHT = 116px` (never grows/shrinks
so the fold tween from ticket 22 never fights it). Same accent-bar/
background shell as the card (§2.4) but `ink(0.08)` background and accent
@ 0.7 opacity.

| element | value |
|---|---|
| accent bar | `ACCENT_BAR_WIDTH` (3px), `theme.solid.opacity(0.7)` |
| header | `"{path}:{line}"` (the RAW `path`, NOT `citePath` — this is deliberate, see §7) |
| input | embedded text input, `h(46px)`, placeholder `"Request a change…"` |
| action row | `h(28px)`, right-aligned, gap 6px |

**Children (in order)**: header (icon + location) → input → action row
(`Cancel`, then `Comment` for a new draft or `Save` when editing).

**Interactions**: Escape (while the draft has focus) → cancel, discard the
draft. Enter in the input → commit. `commit`: empty body discards the
draft silently; non-empty either updates an existing comment's body
(editing an existing one) or creates a new `ReviewComment` staged onto the
chat key the draft was opened against (not necessarily the currently
active chat — a draft opened, then the user navigates away and back,
still targets its original chat).

**Button styling** (`comment_action`, shared with §2.7's editor draft):
22px tall, size 11 medium; primary (`Comment`/`Save`) = `bg(theme.solid)
text_color(theme.on_solid)`; secondary (`Cancel`) hover-blends
`text_muted → text` and `transparent → element_hover` over HOVER_FADE.

**Text (verbatim)**: placeholder `"Request a change…"`; buttons
`"Cancel"`, `"Comment"` (new), `"Save"` (editing).

### 2.4 Comment card (`CommentCard`, diff-side)

**Layout**: height is ANALYTIC (`cardHeight(body)`, §2.1) — never
measured, so it composes with ticket 22's `bodyHeightWith` sum exactly.

| element | value |
|---|---|
| background | `ink(0.05)` |
| accent bar | `ACCENT_BAR_WIDTH` (3px), `theme.solid` @ 0.35 |
| header height | `CARD_HEADER_HEIGHT` = 22px |
| header icon | `chatRoundLine`, 12px, `text_faint` |
| location text | mono, size 11, `text_faint`, `location(comment)` |
| edit button | 16px, pen icon 12px, opacity 0 → 1 on card hover |
| remove button | 16px, close-circle icon 12px, opacity 0 → 1 on card hover |
| body | size 12, line-height 18 (`CARD_LINE_HEIGHT`), `text_dim`,
  `overflow: hidden` (clips at the analytic height rather than growing or
  scrolling) |

**Interactions**: edit → open the draft (§2.3) pre-filled with this
comment's body, anchored at its original `(path, side, line)`, `editingId`
set so the draft's primary button reads "Save". Remove → delete
immediately, no confirmation.

### 2.5 Comment edit button (`CommentEditButton`, shared)

The hover-revealed pen button inside `CommentCard`'s header — SHARED
verbatim with the Files preview's editor-side card (§2.7). Build this as
one component both mount, not two copies.

| property | value | source |
|---|---|---|
| size | 16×16 | comment_ui.rs:158 |
| radius | 4px | comment_ui.rs:162 |
| icon | `pen`, 12px, `theme.text_muted` | comment_ui.rs:174-176 |
| idle opacity | 0.0 (invisible but present) | comment_ui.rs:166 |
| hover opacity | 1.0, via the ENCLOSING card's hover (not the button's own hover) | comment_ui.rs:167 |
| cursor | pointer | comment_ui.rs:163 |

**Interactions**: `onMouseDown` stops propagation; click stops
propagation then invokes the caller-supplied `onEdit(commentId)`. No
keyboard/tab-index binding — pointer-hover-reachable only, matching
desktop.

**Text**: `aria-label="Edit comment"`.

### 2.6 Editor-side gutter cell (Files preview)

Per visible line in the (ticket-25-owned or this-ticket's minimal
stand-in, per §1) line-numbered display: a gutter cell (`width =`
computed gutter width, clamped `24..64px`, `height = line_height`) shows
either:
- an existing-comment icon (`chatRoundLine`, 10.5px, `theme.text_muted`,
  `bg(theme.surface_card)`, hover `bg(wash(0.08))`) if a `File`-sourced
  comment is staged on this line, aria-label `"Open comment on line {N}"`; or
- on hover only (not otherwise visible), an add button (`COMMENT_ADDER_SIZE=16px`,
  `bg(theme.solid)`, `plus` icon 11px `theme.on_solid`), aria-label
  `"Comment on line {N}"`.

Clicking the gutter icon for an already-open comment toggles it closed.

### 2.7 Editor comment card / draft (Files preview)

**`render_editor_comment_card`** — same body/header/edit-pen/remove-×
component as §2.4's `CommentCard`, but positioned as a FLOATING overlay
rather than inline in a row-flow:

| property | value | source |
|---|---|---|
| position | absolute, `left`/`top` from the horizontal/vertical anchor math below | preview.rs:2909-2911 |
| width | `EDITOR_COMMENT_CARD_WIDTH` = **320px** when the viewport allows an anchored column of at least `EDITOR_COMMENT_CARD_MIN_ANCHORED_WIDTH` = **220px** at `gutterWidth - EDITOR_COMMENT_CARD_MARGIN(8px)`; else full width minus 8px margins on both sides | preview.rs:2896-2985 |
| height | `cardHeight(body)` (§2.1) | preview.rs:2912-2913 |
| shell | `border_1 hairline(0.10)`, `rounded(12px)` (`CARD_RADIUS`), `shadow_lg`, bg `glass_overlay()` if frosted else `surface_overlay`, wrapped in a 12px-radius / 44px-blur frost (`MENU_BLUR`) | preview.rs:2909-2983 |
| padding | `px(16)` horizontal, `py(10)` (`CARD_PAD_V/2`) | preview.rs:2917-2918 |
| vertical position | clamped into the viewport | preview.rs (anchor math) |

**Children**: header (icon + `location(comment)` mono 11px `text_faint` +
the SHARED `CommentEditButton` from §2.5 + a remove-× button, same
hover-on-card-group reveal) → body (verbatim text, clipped to
`cardHeight`).

**`render_editor_comment_draft`**: same shell, FIXED height
`EDITOR_COMMENT_DRAFT_HEIGHT` = **92px**. Input row `h(48px)`, `rounded(7px)`,
`border_1(theme.border)`, `bg(theme.input_glass_bg())`, `px(8) py(5)`,
text size 12. Actions row `h(28px)`, gap 6px: Cancel → cancel; primary
(`"Save"` when editing, else `"Comment"`) → commit (empty body on commit
is a NO-OP — re-opens the card view; the comment is neither created nor
deleted).

**Placeholder text**: `"Add a comment…"` for code files, `"Request a
change…"` for Markdown files (matches §2.3's diff-draft placeholder for
consistency, but note the desktop actually varies it by file type here —
port that distinction).

**Text (verbatim)**: `aria-label="Open comment on line {N}"` (existing),
`"Comment on line {N}"` (add button); location format `"{path}:{line}"`
(e.g. `"README.md:3"`).

**Motion**: none beyond the hover-opacity toggle on the edit/remove
buttons; no open/close transition when the card is replaced by the draft.

### 2.8 Composer comments chip (`CommentsChip`)

**Layout**

| property | value | source |
|---|---|---|
| wrapper padding | `px(16)` (`STRIP_PAD_X`), `pt(12)` (`STRIP_PAD_TOP`) | composer.rs:4617-4618 |
| pill height | `BADGE_HEIGHT` = 24px | badges.rs:58 |
| pill radius / bg / text | 8px radius, `ink(0.06)` bg, 12px text `theme.text_muted`, `font-weight: 500` | badges.rs:60-84 |
| icon | `chatRoundLine`, 12px, `theme.text_muted.opacity(0.7)` | badges.rs:61-88 |
| strip height contribution | `count === 0 ? 0 : STRIP_PAD_TOP + BADGE_HEIGHT = 36px` | composer.rs:306-311 |

**Text**: `chipLabel(count)` (§2.1) — `"1 comment"` / `"{n} comments"`.

**Interactions**: NO hover card on the composer's chip (`details: []`
explicitly — the staged set is already visible in the Changes pane).
Read-only; comments are only removable from the diff's inline card
(§2.4) or implicitly cleared on send.

**Mount point**: render above `AttachmentStrip` in `composer.tsx`, only
when the current chat has ≥1 staged comment. Reads
`reviewCommentStore.get(chatKey)`.

### 2.9 Transcript badge (`badges::render`, message pill)

**Layout**

| property | value | source |
|---|---|---|
| height | `BADGE_HEIGHT` = 24 | badges.rs:58,74 |
| layout | `flex flex-row items-center gap(6) px(8)` | badges.rs:75-79 |
| radius | `PILL_RADIUS` = 8 | badges.rs:60,80 |
| background | `ink(0.06)` | badges.rs:81 |
| font | 12px, medium, `theme.text_muted` | badges.rs:62,82-84 |
| icon | badge's icon at 12px, `theme.text_muted.opacity(0.7)` | badges.rs:61,86-88 |
| tooltip delay | 280ms | badges.rs:64,99 |

**Hover card** (`BadgeCard`): `w(320)`, `p(6)`, `flex-col gap(4)`, frosted
(same radius/blur family as the comment card). Each detail row: `flex-row
gap(8) p(8) rounded(6) bg(ink(0.05))` with a `w(2) rounded(1)
bg(theme.solid.opacity(0.35))` accent bar, then a column (`gap(4)`) of:
- location line: mono, size 10, `theme.text_faint`, truncating, with an
  optional tag pill (`px(4) rounded(3) bg(ink(0.10))`) showing `"L"`/`"R"`
  when the detail has a side tag;
- body: size 12, line-height 16, `theme.text`.

**Extractor registry**: `EXTRACTORS = [extractBadge]` (length 1 today —
`comments::extract_badge` is the only extractor). Application: fold over
the extractor list in order, each seeing what the PREVIOUS one left
behind, so a future second extractor can ride the same prompt without
re-architecting this. Call site: run `badges.split(parsedText)` BEFORE any
file-mention/markdown projection on the bubble text, so a comment body's
own Markdown never lands in the rendered bubble.

**Mount point**: `components/transcript.tsx::UserRow` — run the extractor
fold on `text` before rendering `.user-text`; render the resulting badges
in a `flex-wrap justify-end gap(6) pb(6)` strip ABOVE the bubble (mirrors
the attachment strip's position, per the existing `UserAttachments` →
`.user-bubble` ordering already in `UserRow`).

## 3. Pure logic to port

- **`withComments(text, comments) -> string`** — §2.1. Desktop tests to
  port: a body containing `"(L): "` text must not be mis-split when later
  re-parsed by `extractBadge` (round-trip test, not a `withComments`-only
  test, but exercised through it); a renamed file cites the OLD path only
  on the Old side; mixed file+diff comments share ONE
  `REVIEW_COMMENT_BLOCK_HEADER` block (not two separate blocks).
- **`extractBadge(text) -> [string, MessageBadge] | null`** — §2.1. Test
  names to port (from `badges.rs:185-252`): `a_plain_message_carries_no_badges`,
  `a_sent_comment_block_becomes_one_pill`, `the_card_carries_one_row_per_comment`,
  `a_multiline_body_rejoins_its_continuation_lines`,
  `a_path_with_a_colon_still_splits_on_the_side_marker`,
  `a_comment_only_send_keeps_its_stand_in_body`.
- **`parseBullets(block) -> BadgeDetail[]`** — §2.1's earliest-marker-wins
  rule is the load-bearing edge case; test explicitly with a body
  containing literal `"(L): "` text to prove it doesn't get swallowed as a
  location.
- **`chipLabel(count)`** — trivial pluralization; test `1` vs `2`+.
- **`cardBodyLines(body)`/`cardHeight(body)`** — the 64-column wrap
  estimate, clamped to 8 lines; test a body with >8 wrapped lines clamps
  at 8 (card clips, does not grow).
- **`citePath(comment)`/`location(comment)`** — test an Old-side diff
  comment on a renamed file cites `oldPath`, not `path`; a New-side or
  File comment cites `path`.
- **`comment_ui.rs`'s `a_body_quoting_a_side_marker_survives_the_round_trip`**
  equivalent — an end-to-end `withComments` → `extractBadge` round trip on
  a body that itself contains `"(L): "` must reproduce the original body
  verbatim.

## 4. Gaps this ticket closes

| item | kind | desktop value | web value | fix |
|---|---|---|---|---|
| Review comments feature entirely absent | MISSING (critical, whole feature) | composer chip, diff-line hover adder, inline draft, inline staged card, transcript badge extraction — `comments.rs` + `comment_ui.rs` + wiring in `composer.rs`/`changes.rs` | zero occurrences anywhere in `web/packages/app/src` | This ticket in full |
| Comment-only send stand-in text | MISSING | empty prompt + staged comments → `COMMENT_ONLY_TEXT` | N/A (feature absent) | §2.1 `withComments` |
| Message badges (transcript pill) | MISSING | `badges::render` pills (h24, radius 8, `ink(0.06)`, 12px medium, hover card w320 after 280ms) above the user bubble | absent | §2.9 |
| Editor inline review comments | MISSING (files preview) | gutter icon + floating card/draft, `EDITOR_COMMENT_CARD_WIDTH`=320, `_MARGIN`=8, `_MIN_ANCHORED_WIDTH`=220, `_DRAFT_HEIGHT`=92 | no comments feature in `components/files` at all | §2.6/§2.7 — scoped to the minimal gutter stand-in per §1 if ticket 25 hasn't landed a real editor yet |

## 5. Do not

- Do not build a second, divergent comment model for the editor-side vs.
  diff-side comments — one `ReviewComment` type, one store, two anchor
  contexts (`source.kind: "diff" | "file"`).
- Do not add a hover card to the COMPOSER's chip — only the TRANSCRIPT's
  post-send badge gets one (§2.8 vs §2.9 — this is an explicit desktop
  asymmetry, not an oversight to "fix" by adding parity in both places).
- Do not build a full line-numbered code editor for the Files preview as
  part of this ticket — that is ticket 25's "Files preview and editor."
  Build only the minimal per-line addressability §2.6 needs, and prefer
  wiring into ticket 25's real gutter if it has landed first.
- Do not add a create/CI-linked comment-resolution flow, comment
  threading/replies, or any comment feature beyond create/edit/remove —
  none of that exists on desktop.
- Do not send comments as structured wire data — the ONLY transport is
  the plain-text fold-in (`withComments`) at send time; there is no
  separate `ReviewComment[]` field on the send RPC.
- Do not gate comment-only sends behind "must have text" — an
  empty-prompt, comments-only send is valid and uses `COMMENT_ONLY_TEXT`.
- Note (not a "do not," a call-out): per spec.md decision #3, the composer
  offers Send/Queue/Stop, not Steer — if ticket 13 has not yet landed that
  fix by the time you implement this ticket, still route the comment
  fold-in through whichever send path is live (`sendRun`/`sendSteer`/
  `queueMessage`), and flag in Comments if the queue path needs its own
  comment fold-in wiring (queue rows currently show no comment summary at
  all — out of scope for this ticket unless trivial).

## 6. Acceptance

- [ ] Hovering a diff line (unified, or the right column in split) shows
      a "+" adder; clicking it opens a fixed-116px draft; Enter commits,
      Escape cancels.
- [ ] A committed comment renders as a card anchored to its line, with
      hover-revealed edit (pen) and remove (×) buttons; edit reopens the
      draft pre-filled, remove deletes with no confirmation.
- [ ] The composer shows a "N comment(s)" chip above the attachment strip
      whenever ≥1 comment is staged for the active chat; the chip has no
      hover card.
- [ ] Sending a message folds every staged comment into the prompt text
      per `withComments`, using the correct header (diff-only vs. mixed/
      file), and clears the staged set on success; an empty prompt with
      only comments sends `"Address the review comments below."`.
- [ ] The sent message's transcript bubble shows a "N comment(s)" pill
      above it (via `extractBadge`), with the comment block stripped from
      the visible bubble text; hovering the pill (280ms delay) shows one
      row per comment with location + tag + body.
- [ ] The Files preview's code view shows a gutter icon on lines with a
      staged file comment, and a hover-revealed add button on lines
      without one; clicking either opens the card/draft overlay
      positioned per §2.7's width/margin rules.
- [ ] Unit tests: `withComments`, `extractBadge` (all six desktop test
      names from §3), `parseBullets`'s side-marker-vs-body edge case,
      `cardBodyLines`/`cardHeight` clamping, `citePath` rename handling —
      all pass in `web/packages/app/tests/review-comments.test.ts`.
- [ ] Screenshot pair, desktop vs web: (a) a diff line hovered showing the
      adder; (b) an open draft; (c) a staged card with edit/remove
      revealed; (d) the composer chip with 2 staged comments; (e) a sent
      message showing the transcript badge, hover card open; (f) the
      Files preview gutter with one existing comment icon and one hover
      add button.
- [ ] `pnpm -r build` green; package vitest green.
- [ ] No new literal hex/px where a `--rb-*` token exists.

## Comments

### What landed (2026-09-19)

Everything in the ticket's "Web files to touch" table plus the row-model
plumbing it rides on:

- `lib/review-comments.ts` — the `ReviewComment` model, `withComments`
  (the codec's writer half), `citePath`/`location`/`diffAnchor`, the card
  geometry (`cardHeight`/`cardBodyLines` + constants), the editor overlay
  anchor math (`editorCommentOverlayHorizontal`/`Top`), and
  `commentStripHeight`. Re-exports the reader half
  (`extractBadge`-equivalent, `parseBullets`, `chipLabel`, the three
  constants) from `lib/badges.ts` — ticket 20 already landed the entire
  reader + registry; one codec, composed, not duplicated.
- `state/review-comments.ts` — the module store: staged per-chat list
  (chat-id keying, "" canvas included, `take_comments` snapshot-and-clear
  for the send path, restore-on-failure, `purge_chat` wired into the chat
  delete dialog), the diff-side draft state machine, and the editor-side
  draft/active-card state machine (empty-commit = re-open the card, toggle,
  pre-filled edit).
- `components/review-comments/` — `comment-adder`, `comment-draft`
  (fixed 116px), `comment-card` + the shared `CommentEditButton`,
  `comments-chip` (reuses ticket 20's `BadgePill`, `details: []` so no
  hover card), `editor-comment-card` (`.popover-card` chrome) and
  `editor-comment-draft` (fixed 92px).
- `lib/diff.ts` — `DiffRow` gained `commentCard`/`commentDraft` rows
  interleaved after their anchor lines (`body_rows`, changes.rs:1266-1337
  port), with `diffLineAnchor`/`pairAnchors`; `flattenFiles`/`bodyRows`/
  `bodyHeightWith`/`estimateRowHeight` take the staged set + draft anchor.
- `components/diff-view.tsx` — the `review` wiring prop renders the
  card/draft rows; the adder slots now position through ticket 22's landed
  `commentAdderLeft`/`splitAdderLeft` (centered in the gutter, §2.2).
- `routes/changes-page.tsx` — hosts the store wiring (visible comments
  exclude the one being edited), `renderAdder` (carries the file's
  `oldPath` for Old-side anchors), and registers the comment set with
  `changesSurfaceStore.setComments` so fold heights stay analytic.
- `components/composer.tsx` + `lib/composer-actions.ts` — `CommentsChip`
  above `AttachmentStrip` with its 36px arithmetic strip contribution
  (evaluate pass, box height, dock correction); `composerHasContent` now
  takes the live comment count (comment-only sends legal); `sendRun` folds
  `withComments(withAttachments-trimmed-text)` in the desktop's order
  (comment block before the attachment trailer, composer.rs:6137→6393) and
  accepts `stagedReviewComments`; the queue path folds comments into the
  queued text (desktop `queue_body`); failure restores the taken set.
- `components/files/code-view.tsx` + `file-viewer.tsx` — the editor-side
  gutter affordances (icon/add-button overlay per line, riding the sticky
  gutter) and the floating card/draft overlays positioned by the ported
  anchor math, clamped into the scroll viewport and repositioned on
  scroll; only mounted over a live editor (editable + code view), matching
  the desktop's `render_editor_comment_overlays` call site. Placeholder
  split: "Request a change…" (Markdown) / "Add a comment…" (code).
- `styles/app.css` — the `.comment-*`, `.comments-chip`,
  `.editor-comment-*`, `.files-gutter-*` blocks, tokens-only colors.
- `tests/review-comments.test.ts` — 22 tests: the comments.rs suite port
  (bullets, stand-in body, multiline indent, file comments, mixed block,
  rename citation, card geometry + clamping) plus §3's six desktop badge
  names run through the REAL `withComments`→`splitBadges` round trip.

§2.9's transcript badge half needed no new code: tickets 18/20 already
landed the extractor fold (`lib/transcript.ts:1510`), the `MessageBadges`
mount in `UserRow`, and the pill + 280ms hover card. Verified live.

### Deviations from the ticket text (judgment calls)

1. **§2.3 draft header path**: the ticket says "the RAW `path`, NOT
   `citePath` — this is deliberate, see §7" (no §7 exists). The desktop's
   call site passes `draft_cite_path` with the comment "Header cites the
   same path the staged card and the prompt bullet will"
   (changes.rs:3285-3294). Implemented the desktop behavior: an Old-side
   draft on a renamed file cites `oldPath`.
2. **Card/draft placement**: implemented as interleaved rows in the
   flattened diff row list (the desktop's `DiffRow::CommentCard`/
   `CommentDraft` model) rather than inside `UnifiedLineRow`/
   `SplitLineRow` — the ticket's file table said "wire … into the
   hover-hook point", but analytic heights that "compose with bodyHeightWith
   exactly" (§2.4) require the row list to carry them; the adder still goes
   through the hover hook. `FileBodyUpto` (tool diffs) stays comment-free,
   matching `render_file_body_upto`.
3. **withComments empty check**: ticket says `text.trim().length === 0`;
   desktop checks raw `is_empty` (comments.rs:146). Took the ticket's
   spelling — every caller passes already-trimmed text, so behavior is
   identical.
4. **Editor flush machinery not ported**: the desktop's
   `review_comment_flushes`/`begin/finish_review_comment_flush` (the
   document-save handshake behind `send_blocked` condition 3) has no web
   counterpart in the file-document save model; `reviewCommentFlushPending`
   stays `false`. Also skipped (out of ticket scope, no desktop-equivalent
   trigger on web): `update_review_comment_line` (edit-driven line
   shifts) and `rename_review_comment_path`.
5. **Failure restore key**: comments restore under the minted/existing
   `chatId` — the web's existing send-failure path keeps the minted chat
   row alive (the desktop restores to the "" canvas because it deletes the
   row); attachments already behaved this way.
6. **Gutter wiring**: ticket 25 landed the real line-numbered gutter, so
   per §1's rule the editor-side affordances wire into CodeView's
   `.files-code-gutter`/`renderGutterCell` seam — no parallel gutter was
   built, and `file-viewer.tsx` only passes the wiring through.

### Verification

- `pnpm -r build` green (proto, engine-client, app); app vitest
  1037/1037 (63 files) including the 22 new.
- `web_smoke` runbook, followed as written. Port 27699 was held by
  sibling wave-2 agents (26-terminal, then 27-history) rotating capture
  rounds for ~70 minutes; I never killed a sibling (none was a merged
  leftover) and took a free window at 01:12. Staging, all runtime-only
  from the authorized client side: the smoke tempdir was turned into a
  real git repo (modified `src/app.rs`, untracked `new_module.rs`, deleted
  `notes.md`); the chat's branch stamped through the engine's `Mutate`
  RPC (`setChatBranch`) — the one-time pair code had been consumed by the
  browser, so a temporary vitest harness (deleted before commit) drove
  the RPCs with the browser session's own stored credential; a
  `createSpace` + `createChat` pair minted a "Smoke files chat" because
  the seeded smoke-chat has no space and the Files pane requires one.
- DOM-verified behaviors: adder 16px at `commentAdderLeft` (606 = 3+36+10)
  on hover only; draft fixed 116px, header `src/app.rs:2`, Enter commits /
  Escape cancels; card analytic 66px with hover-revealed pen/×; edit
  re-opens pre-filled with "Save", the edited card hidden while editing;
  remove deletes immediately; composer chip "1 comment"/"2 comments" with
  NO hover card; comment-only send → chip cleared, bubble
  "Address the review comments below.", "2 comments" pill, 280ms hover
  card with one row per comment (locations + L/R tags + bodies); reload →
  the badge re-extracts from the engine-stored text (boot check clean);
  file-comment send on the files chat → `REVIEW_COMMENT_BLOCK_HEADER`
  variant with no tag pill; Files preview: gutter icon on the commented
  line, hover-revealed add button on others, floating card 320px wide at
  left 40 (= gutter 48 − 8) with analytic height, draft fixed 92px with
  the "Add a comment…"/"Request a change…" placeholder split, empty
  commit re-opens the card, pen pre-fills the draft.

### Screenshots

Web halves in `.scratch/web-parity/shots/23/`:
`web-a-diff-line-hovered-adder.png`, `web-b-open-draft.png`,
`web-c-staged-card-edit-remove-revealed.png`,
`web-d-composer-chip-2-comments.png`,
`web-e-sent-message-badge-hover-card.png` (diff-comment pill + card),
`web-e2-file-comment-badge-hover-card.png` (file-comment variant),
`web-f-files-gutter-icon-and-adder.png`, `web-boot-check.png`.

Desktop halves of all pairs skipped: no desktop client is running on this
machine and driving it unattended steals foreground focus (tickets 07 and
22's documented precedent). All geometry/colors above were asserted from
computed styles instead.

### Shared components addendum (2026-09-18)

Build on components/ui/ + components/base/ (see components/README.md)
— do not hand-roll card shells, cursor lists, menu rows, chips, or
tooltips.
