# 14 — Completions and wizard

**What to build:** Three features the web composer does not have at all.
Typing `@` opens a file-search popup above the pill; picking a result inserts a
**mention chip** — a monospace, code-washed, atomic token that the caret steps
over in one press and that serialises into the prompt as strict local Markdown
(`[name](roboco-file:path)`). Typing `/` at the very start of the prompt opens a
locally filtered slash-command list. And when a run asks the user a question,
the whole pill is replaced in place by a **question wizard** — a paged panel
with numbered options, a typed-answer override, Back/Next, and auto-advance.
After this ticket, a user can `@`-reference a file without typing its path,
run `/compact` from the composer, and answer an agent's question without
leaving the composer.

**Blocked by:** 09 (Popover primitive), 13 (Composer core).

**Status:** done

**Research:** `../../web-client/research/04-composer.md` §3.12 (key contexts,
placeholders), §3.13, §3.16, §3.17, §3.18, §3.19, §4 (`mention_*`, `slash_*`,
`file_mention_links`, `Wizard`, `enter_outcome`, `pending_input_request`,
`input_request_resolved`, `escape_dismisses_completion`,
`wizard_escape_goes_back`, `message_input_context`, `TextProjection`,
`display_row_segments`, `popover::menu_step`, `popover::filter_indices`),
§5.3 rows "`@` file mentions", "`/` slash commands", "Question wizard",
"Key-binding contexts", §5.4.

**Desktop reference (for lookups only):**
`crates/ui/src/composer.rs::render_file_mention_popup` (5192),
`::render_slash_popup` (5513), `::popup_scrollbar` (5665), `::render_wizard`
(6903), `::on_wizard_key` (6863), `::Wizard` (676), `::mention_token` (3866),
`::slash_token` (3905), `crates/ui/src/popover.rs::{menu_row, menu_step,
filter_indices, skeleton_rows, full_width_menu_above, MenuScrollbarState,
btn_ghost, btn_primary, tracked_upper}`.

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/components/composer/mention-popup.tsx` | new | `MentionPopup`, `MentionRow`, the four card states, the popup scrollbar rail |
| `web/packages/app/src/components/composer/slash-popup.tsx` | new | `SlashPopup`, `SlashRow`, the four card states |
| `web/packages/app/src/components/composer/wizard.tsx` | new | `ComposerWizard`, `WizardOptionRow`, the header/counter/footer rows |
| `web/packages/app/src/lib/mentions.ts` | new | `mentionToken`, `percentEncodePath`, `escapeMentionLabel`, `localFileLink`, `localPathIsSafe`, `fileMentionLinks`, `droppedFileMention`, `mentionDisplayLabels`, `sentMentionDisplay`, `TextProjection`, `displayRowSegments`, `mentionTooltipReduce`, `mentionTooltipPromote`, `mentionTooltipContains`, `mentionResponseIsCurrent`, `mentionErrorMessage` |
| `web/packages/app/src/lib/slash.ts` | new | `slashToken`, `slashErrorMessage`, the slash cache shape, `refilterSlash` |
| `web/packages/app/src/lib/wizard.ts` | new | `Wizard` (counter/select/pressNumber/setTyped/advance/back/answers), `WizardStep`, `pendingInputRequest`, `inputRequestResolved`, `enterOutcome`, `escapeDismissesCompletion`, `wizardEscapeGoesBack`, `messageInputContext` |
| `web/packages/app/src/components/composer.tsx` | edit | mounts the two popups inside the pill surface and swaps the pill for the wizard; the textarea's two Enter/Escape modes |
| `web/packages/app/src/components/transcript.tsx` | edit | renders `sentMentionDisplay` chips on sent user prompts |
| `web/packages/app/src/styles/app.css` | edit | new `.mention-popup*`, `.slash-popup*`, `.mention-chip`, `.mention-tooltip`, `.wizard*` classes; reuses ticket 09's `.popover-card` / `.menu-row` / `.menu-scrollbar*` |
| `web/packages/app/tests/mentions.test.ts` | new | the mention/projection/tooltip unit tests |
| `web/packages/app/tests/slash.test.ts` | new | the slash-token unit tests |
| `web/packages/app/tests/wizard.test.ts` | new | the wizard + pending-input unit tests |

---

## 1. Context a fresh session needs

- The composer pill (ticket 13) is a `position:relative` box. Both completion
  popups are absolutely positioned children of it, anchored **above** it.
- The two popups are **mutually exclusive by token shape** (`/` at offset 0 vs
  `@` at a token boundary), so at most one is ever mounted.
- Ticket 09 supplies the popover primitive: the card shell, the entrance/exit
  motion, `menuStep`, `filterIndices`, `menuRow`, `skeletonRows`, and
  `MenuScrollbarState`. Reuse them; this ticket only composes them.
- The wizard is **not** a modal. It replaces the pill in place inside the
  composer column — same 26px radius, same width. While it is mounted, the
  composer's textarea is re-parented into the wizard panel as its free-text
  override field, and its Enter policy changes.
- The desktop expresses "which keyboard applies" as a named gpui key context.
  The browser has none, so the same thing is expressed as *which element owns
  `keydown` and what it `preventDefault()`s*. That mapping is spelled out
  below and is the single most error-prone part of this ticket.
- Icons needed, all present in `@roboco/icons`: `command`. File-type icons for
  mention rows come from the file-icon mapping (ticket 24 owns the full
  manifest; use the basename/extension mapping that exists today in
  `lib/file-tree.ts` if ticket 24 has not landed).
- Vocabulary: **chat**, **harness**, **engine**, **space**.

---

## 2. Spec

### 2.1 `mention_token(text, cursor)` — when the `@` popup opens

`composer.rs:3866`. The `@` must **begin a token**:

- `token_start` = after the last whitespace before the cursor (or 0)
- find the last `@` in `text[token_start..cursor]`
- reject if `text[at+1..cursor]` contains another `@`
- the boundary before `@` must be start-of-text, whitespace, or one of
  `(`, `[`, `{`
- `range` = `at .. (next whitespace after cursor, or end)` — the **whole**
  token, not just up to the cursor
- `query` = `text[at+1..cursor]`

Tests: `mention_token("Fix @src/com", 12)` → range `4..12`, query `"src/com"`;
`"mail@example.com"` → `None`; `"word@file"` → `None`; `"path/@file"` → `None`;
`"See (@lib"` at 9 → range `5..9`.

Constants: `MENTION_PREFIX` = `'@'` (composer.rs:864), `MENTION_SIDE_PAD` =
`"\u{00A0}"` (NBSP, :867), `FILE_MENTION_SCHEME` = `"roboco-file:"` (:870),
`MENTION_TOOLTIP_DELAY` = 420 ms (:865), `MENTION_TOOLTIP_HEIGHT` = 24 (:866).

---

### 2.2 File-mention Markdown (`composer.rs:929–1051`)

A file mention is stored in the prompt as **strict local Markdown**:
`[{escaped basename}](roboco-file:{percent-encoded path}{"/" if dir})`.

- `percent_encode_path`: keeps `[A-Za-z0-9-._~/]`, everything else `%XX`
  **uppercase** hex
- `escape_mention_label`: `\` → `\\`, `[` → `\[`, `]` → `\]`
- `local_file_link(path, is_dir)` =
  `"[{escaped basename}](roboco-file:{percent_encode(path + (is_dir ? "/" : ""))})"`
- `local_path_is_safe(path)`: non-empty, **not absolute** (`/`), no `\`, no
  control chars, and no path component that is empty, `.` or `..`
- `file_mention_links(text)` re-parses only **canonical** links: the target must
  strip the scheme, decode, **round-trip** (`percent_encode(decoded) == encoded`),
  be a safe local path, and its basename must equal the (escaped) label.
  Anything else is skipped.

Tests: `local_file_link("src/a file#[x].rs", false)` ==
`"[a file#\\[x\\].rs](roboco-file:src/a%20file%23%5Bx%5D.rs)"`;
`local_file_link("src/components", true)` ==
`"[components](roboco-file:src/components/)"`.
Rejected by `file_mention_links`: external URLs, `../a.rs`, unencoded spaces,
mismatched label, missing scheme, `%5C` (backslash), `%0A` (control).

**`dropped_file_mention(content, range, path, is_dir) -> (inserted, cursor_advance) | null`**
(composer.rs:947). Rejects `range.start > range.end`, unsafe paths, and
non-char-boundary starts. Prepends `" "` when the character before the
insertion point is non-whitespace. Appends `" "` unless the next character is
already a non-newline whitespace (in which case the cursor advances past it).
Tests: `("fixnow", 3..3, "src/lib.rs")` →
`" [lib.rs](roboco-file:src/lib.rs) "`, advance = len;
`("fix now", 3..3, "src/components", dir)` →
`" [components](roboco-file:src/components/)"`, advance = len + 1.
`/tmp/file.rs` and `../file.rs` → `null`.

This is the insertion path used by the **file-tree / file-tab drag** onto the
conversation column (`WorkspacePathDrag`), which inserts a **mention**, not an
attachment.

---

### 2.3 Mention chips — projection, atomic caret, wash

The editor *projects* a stored link for display as
`NBSP + "@" + label + NBSP`, shaped in `theme.font_mono` at `theme.code_text`,
over a rounded `theme.code_wash` quad — the same recipe the markdown renderer
uses for inline `code` spans.

**Chip quad** (composer.rs:3492–3507): for each visual row segment of the
projected range, a quad inset by `+2px` at the top and `−4px` in height
relative to the text row, `rounded` **5**, filled with `theme.code_wash`, **no
border**.

**Label choice** — `mention_display_labels` (1267): the basename when unique
among the draft's mentions; otherwise the shortest unique **path-component**
suffix; falling back to the full path. Test
`mention_suffixes_compare_path_components` asserts `foo/mod.rs` +
`bar/oomod.rs` stay `mod.rs` + `oomod.rs` (suffix comparison is per component,
**never substring**).

**Atomic behavior** — `TextProjection::normalize_range`:
- a collapsed caret inside a link snaps to the **nearer end** (link start if
  before the midpoint, else link end)
- any selection overlapping a link expands to swallow the whole link
- `previous_boundary(raw)` / `next_boundary(raw)` let Left/Right and word motion
  step over a whole chip in one press
- Backspace/Delete at a chip boundary therefore removes the **entire** mention

`TextProjection::{raw_to_display, display_to_raw, normalize_range,
previous_boundary, next_boundary}` (1158–1262). `display_to_raw` inside a chip
snaps to the link start when in the first half, the link end otherwise.
`normalize_range` on an empty range snaps to the nearer chip edge; on a
non-empty range expands to swallow every overlapping chip.

**`display_row_segments(range, row_ends)`** (1131) splits a display range at
every soft-wrap boundary, returning `(row_ix, row_start, segment)`. A range
crossing a wrap gets a **fresh segment starting at x = 0** on the new row rather
than inheriting the old row's end caret.

**Web implementation note.** A `<textarea>` cannot render a wash behind a
sub-range. Draw the chips in a mirror layer: an absolutely positioned,
`aria-hidden`, pointer-events-none div sitting exactly under the textarea with
identical font, line-height, padding, width and `white-space: pre-wrap`,
containing the projected text with `<span class="mention-chip">` around each
projected range. The textarea's own text is transparent (`color: transparent;
caret-color: var(--rb-caret)`) while any mention exists, and the mirror scrolls
with it. `displayRowSegments` is then only needed for the tooltip anchor and
the unit test; CSS line-wrapping handles the visual split. Atomic caret motion
is enforced in `onKeyDown`/`onSelect` by normalising `selectionStart`/`End`
through `normalizeRange`.

**Path tooltip**
- `MentionTooltipPhase` = `Hidden | Waiting { target, generation } | Visible { target, generation }`
- `mention_tooltip_reduce(phase, pointer_target, pointer_in_popup, generation)`:
  - pointer over the SAME chip → phase unchanged (jitter cannot restart the
    delay or flicker a visible tooltip)
  - pointer over a different chip → `Waiting`
  - pointer over nothing but inside the popup while `Visible` → phase unchanged
  - otherwise → `Hidden`
- `mention_tooltip_promote(phase, generation, target_is_live)`: `Waiting` with a
  matching generation becomes `Visible` if the target chip still exists, else
  `Hidden`.
- Delay: `MENTION_TOOLTIP_DELAY` = **420 ms**.
- Any edit, scroll, mouse-down or drag calls `invalidate_mention_tooltip`
  (bumps the generation, clears everything).
- Anchor: `chip.top − 24 − 1` when that is ≥ 0, else `chip.bottom − 1` (GPUI
  positions the popup at anchor + 1px, so this yields conventional
  above-target placement with a flush below-fallback the pointer can enter).
- Tooltip view (`MentionPathTooltip`, 3369): `motion::fade_quick` wrapper
  (150 ms `EASE`); `h` **24**, `max_w` **480**, flex `items_center`, `px` 8,
  `rounded` 5, 1px `theme.border_strong`, `bg theme.surface_raised`,
  `font_family theme.font_mono`, `text_size` 11, `text_color
  theme.text_muted`; content = the full workspace-relative path (with a
  trailing `/` for directories).

**Sent messages** — `sent_mention_display(raw) -> (String, SentMentionSpan[]) | null`
(1316) projects a sent prompt's mentions the same way for the transcript.
Returns `null` (zero-allocation fast path) when the raw text does not contain
`"roboco-file:"` or when no valid mention parses. Test: a two-mention prompt
yields spans whose display slices are `"\u{00A0}@composer.rs\u{00A0}"` and whose
paths are `"src/composer.rs"` / `"src/components/"`.

---

### 2.4 File-mention popup (`render_file_mention_popup`, 5192)

Rendered only while `self.mention.token.is_some()`.

**Frame** — `popover::full_width_menu_above("file-mention-popup", card, None)`
(popover.rs:564): absolutely positioned `bottom_full left_0 right_0`, deferred
with priority 1, an `occlude()` wrapper with `pb` **6**, and `menu_motion` =
`MENU_IN` (**140 ms** `EASE` = `cubic-bezier(0.25, 0.1, 0.25, 1)`, opacity
0.3→1 + translateY −2→0), over a frosted backdrop (`frost::MENU_BLUR` = **44**).

**Card** — `popover::popover_card(theme)` then
`.w_full().max_h(320).overflow_hidden()`:

| property | value |
|---|---|
| border | 1px `theme::hairline(0.10)` |
| radius | `CARD_RADIUS` (12) |
| shadow | `shadow_lg` |
| padding | 4 (`p-1`) |
| background | frosted: `theme.glass_overlay()`; opaque: `theme.surface_overlay` |
| font | `ui_rems(13)`, `theme.text` |

**Card interactions**
- `on_mouse_down(Left)` → focus the input (completion choices belong to the
  input; keep it focused until mouse-up can accept a choice, or while dragging
  the scrollbar)
- `on_drag_move` → the popup-scrollbar drag stream (gpui dispatches this
  captured stream even when the pointer has left the popup)
- `on_mouse_down_out` → `dismiss_mention`

**Card content — four mutually exclusive states**

| state | condition | content |
|---|---|---|
| loading | `mention.loading && results.is_empty()` | `popover::skeleton_rows(3)`: flex column, `gap` 6, `py` 4, each row `h` 28, `rounded(CONTROL_RADIUS=6)`, `bg ink(0.04)`, opacity `0.35 + 0.4 * pulse_wave(phase)` on the 2400 ms `ROBOCO_PULSE`, staggered 0.08 per row |
| error | `mention.error.is_some()` | `px` 12, `py` 10, `ui_rems(12)`, `theme.danger_muted`, the message |
| empty | `results.is_empty()` | `px` 12, `py` 10, `ui_rems(12)`, `theme.text_muted`, **`"No files available"`** when the query is empty, else **`"No matching files"`** |
| rows | otherwise | the scroll host below |

**Error messages (verbatim, `mention_error_message`, 3969)**
- `RpcError::UnknownMethod` → `"The session's device runs an older roboco — update it to search its files"`
- `RpcError::Transport` / `Closed` → `"The session's device is unreachable"`
- `RpcError::BadParams` / `Failed` → `"File search failed"`

A failure must **never** render as "No matching files".

**Scroll host** — `id "mention-scroll-host"`, `relative`, `on_hover` →
scrollbar list-hover. Inner list `id "mention-list"`, `max_h` **312**, flex
column, `overflow_y_scroll`, tracked by `mention_scroll`. Plus the floating rail
(§2.6).

**Row** — `popover::menu_row(theme, selected, "file-mention-result-{ix}")`:
flex row `items_center`, `gap` 10, `px` 8, `py` 6, `rounded` 8, `ui_rems(13)`,
`cursor_pointer`.
- selected: `bg theme::card_selected_bg()`, `text_color theme.text`
- otherwise: text blends `theme.text.opacity(0.9)` → `theme.text` and background
  blends `wash(0.0)` → `card_selected_bg()` over `HOVER_FADE` (150 ms
  `EASE_TAILWIND`)

Row content: a `w_full` flex row `items_center` `gap` 8 with
1. the file icon (`file` or `directory` identity for the path) at 14px, `flex_none`
2. the **basename** — `flex_none`, `text_size` 13, `theme.text`
3. the **directory** (only when non-empty) — `min_w_0 flex_1 overflow_hidden
   truncate`, `text_size` **12.5**, `theme.text_muted`

Path is split at the **last** `/`: `(directory, name)`.

**Click** → sets `mention.active = ix`, then `accept_mention`.

**Search RPC** (`on_input_edited`, 5007)
- debounce **80 ms** before calling `methods::SEARCH_FILES`
- one retry after a further **250 ms** on `Transport`/`Closed`
- params: `{ query, chatId? | spaceId?, path? (selected worktree, space case
  only), targetDeviceId }`
- responses are dropped unless `mention_response_is_current(state, request)`
  (matching **generation** AND a token still open)
- refining an already-open menu keeps the **stale rows visible** until the new
  response lands (no skeleton bounce per keystroke); a **fresh open** clears
  rows, resets `active`, and resets the scroll offset to the top
- on success: `active = 0` when non-empty, else `null`; scroll resets to top
- no target device resolvable → `loading = false`, no request

**Keyboard**
- `ArrowUp` / `ArrowDown` → `popover::menu_step(active, count, delta)`: wraps at
  both ends via `rem_euclid`; `None` enters at index 0 for `delta >= 0`, else at
  `count − 1`; an empty list stays `None`.
- `Tab` → accepts the selection when one exists, otherwise propagates
  (`MentionTab`).
- `Enter` → accepts (see §2.7 `enter_outcome`).
- `Escape` → emits `MentionDismiss` and **stops propagation**
  (`escape_dismisses_completion`).

**Dismiss** — dismissal records `(token.range, token text)`; while the caret
moves **within that same unchanged token** the popup stays closed; any edit to
the token re-enables completion. Also force-closed on every render when the
wizard is active or the input is not focused (7176–7180).

**Accept** — replaces the token range with `local_file_link(path, is_dir)` and
places the caret after the trailing space (the same separator rules as
`dropped_file_mention`).

---

### 2.5 Slash-command popup (`render_slash_popup`, 5513)

**`slash_token(text, cursor)`** (3905). Slash commands are **whole-prompt
prefixes**, so only the first token triggers:
- the text must start with `/`
- `end` = first whitespace (or text end); reject when `cursor == 0` or
  `cursor > end`
- reject when `text[1..cursor]` contains another `/` (a typed path)
- `range = 0..end`, `query = text[1..cursor]`

Tests: `"/comp"@5` → `0..5`, `"comp"`; `"/compact now"@3` → range `0..8` (the
whole command word) query `"co"`; `"run /compact"@12` → `None`;
`"/goal ship it"@10` → `None` (cursor in the argument); `"/usr/bin"@8` →
`None`; `"/"@0` → `None`; `"/"@1` → query `""` (open-all).

Same frame, card, scroll host, scrollbar and `max_h` **320** / list `max_h`
**312** as §2.4 — `full_width_menu_above("slash-popup", …)`. Differences:

**Content states**

| state | condition | content |
|---|---|---|
| loading | `slash.loading && commands.is_empty()` | `skeleton_rows("slash-loading", 3)` |
| error | `slash.error.is_some()` | `px` 12, `py` 10, `ui_rems(12)`, `theme.danger_muted` |
| empty | `slash.filtered.is_empty()` | **`"This agent has no slash commands"`** when the cache is empty, else **`"No matching commands"`** |
| rows | otherwise | list |

**Error messages (verbatim, `slash_error_message`, 3980)**
- `UnknownMethod` → `"The session's device runs an older roboco — update it to list commands"`
- `Transport`/`Closed` → `"The session's device is unreachable"`
- `BadParams`/`Failed` → `"Couldn't load this agent's commands"`

**Row content** — `menu_row(theme, selected, "slash-result-{row_ix}")` with an
inner flex row `items_center gap 8`:
1. `command` icon 14px, `theme.text_muted`
2. name — `flex_none`, `ui_rems(12.5)`, weight **MEDIUM**, `theme.text`,
   formatted `"/{name}"`
3. description — `min_w_0 flex_1 overflow_hidden truncate`, `ui_rems(12)`,
   `theme.text_muted`

Description composition: `command.description`; if `command.input_hint` is
present, it becomes `"<{hint}>"` when the description is empty, else
`"{description} · <{hint}>"`.

**Data** — **one** `methods::LIST_COMMANDS` per harness per composer lifetime,
cached in `slash_cache: Map<HarnessId, SlashCommand[]>`; params
`{ harness, targetDeviceId? }`. Filtering is **local per keystroke** via
`popover::filter_indices` (rank 0 = case-insensitive prefix match, rank 1 =
substring; sorted by `(rank, input index)`; an empty/whitespace query matches
everything at rank 1 preserving input order). **No RPC, no debounce, no
skeleton churn while typing.**

`update_slash` opens/refreshes the popup from the token and fires the one
cache-filling RPC; `refilter_slash` recomputes `filtered` from the cache on
every keystroke. `active` resets to `0` on every re-filter; the row stack resets
to the top.

**Accept** → `input.replace_plain_token(token.range, "/{name}")` — **ordinary
text, no link and no chip projection**. A trailing space is inserted unless the
character after the token is already a non-newline whitespace.

**Dismiss** — identical to the mention popup (Escape, outside mouse-down, and
the "unchanged dismissed token" rule).

---

### 2.6 Popup scrollbar rail (`popup_scrollbar`, 5665 + `popover::MenuScrollbarState`)

Both popups share **one** `MenuScrollbarState` (they are never open at once).

| property | value | source |
|---|---|---|
| rail | absolute, `top 0 bottom 0 right 0`, `w` `MENU_SCROLLBAR_HIT_WIDTH` (**10**) | popover.rs:1379–1382 |
| thumb | absolute, `top` `MENU_SCROLLBAR_TRACK_INSET (4) + thumb_top`, `right` 2, `h` `thumb_height`, `rounded(width/2)` | popover.rs:1386–1394 |
| thumb width | **3** (`MENU_SCROLLBAR_THUMB_WIDTH`), **5** when active (`MENU_SCROLLBAR_HOVER_THUMB_WIDTH`) | popover.rs:1174–1176 |
| thumb colour | `theme.text_faint.opacity(0.5)`, **0.68** when active | popover.rs:1394 |
| min thumb | **24** (`MENU_SCROLLBAR_MIN_THUMB`) | popover.rs:1178 |
| track height | `viewport_height − 2 × 4` | popover.rs:1207 |

Hidden when the content fits **or** the list is not hovered. Listeners in
order: `.id(…)`, `.on_hover`, `.on_mouse_down`, `.on_drag(MenuScrollbarDrag, …)`,
`.on_mouse_up_out`, `.on_mouse_up`. Thumb hover expansion never reflows rows
(the thumb is absolute inside a fixed-width hit rail).

If ticket 09 already shipped this rail, reuse it and only wire the shared state.

---

### 2.7 Enter precedence (`enter_outcome`, 1407)

`enter_outcome(has_completion, fallback)`: **a live completion selection always
wins** — returns `AcceptCompletion`; otherwise `fallback`. The fallback is
whatever the `ComposerSendBehavior` setting produced (ticket 13): `Submit` or
`NewlineOrAccept`.

So the web textarea's `onKeyDown` order for `Enter` is:
1. `event.isComposing` → do nothing (IME).
2. A mention or slash popup is open **with a selected row** → accept it,
   `preventDefault()`.
3. Wizard mounted → `Submit` (bare Enter always submits the page, see §2.8).
4. Otherwise → the `ComposerSendBehavior` policy from ticket 13.

And for `Escape`:
1. `escape_dismisses_completion(key, completion_open)` =
   `key === "escape" && completion_open` → dismiss the popup,
   `stopPropagation()`.
2. Wizard mounted → `wizard_escape_goes_back(key, input_focused, input_empty)` =
   `key === "escape" && (!input_focused || input_empty)` → page back. Either
   way the Escape is **swallowed**.
3. A queue edit is open → cancel the edit (ticket 13 §2.1 / ticket 16).
4. Otherwise → let it bubble.

Desktop test: `escape_consumers_keep_completion_and_wizard_priority` (9433).

---

### 2.8 Question wizard (`render_wizard`, 6903)

Replaces the whole pill in place when `pending_input_request` finds an
unresolved input part. Wrapped in `motion::fade_quick("composer-wizard", …)`
(**150 ms** `EASE`).

**Panel**

| property | value | source |
|---|---|---|
| id | `"question-panel"`, focus-tracked | 6989–6990 |
| radius | `COMPOSER_RADIUS` (**26**) | 6994 |
| border | 1px `theme.border` | 6995–6996 |
| background | `theme.input_glass_bg()` | 6997 |
| shadow | `shadow_lg` when **not** frosted | 6998 |
| direction | flex column | 6999–7000 |

**Upper block** — `px` 16, `pt` 16, flex column:

1. **Header row** — flex row `items_center`, `gap` 10:
   - header label: `ui_rems(10.5)`, weight MEDIUM,
     `theme.text_muted.opacity(0.6)`, text = `popover::tracked_upper(question.header)`
     (uppercase with a **U+200A hair space** between every character ≈ 0.1em
     tracking)
   - counter chip (only when `questions.len() > 1`): `h` **20**, `px` 6, flex
     `items_center`, `rounded` 6, `bg theme::ink(0.06)`, `ui_rems(10)`, weight
     MEDIUM, `theme.text_muted.opacity(0.6)`, text = `Wizard::counter()` =
     `"{page+1}/{len}"`
2. **Question** — `mt` 6, `ui_rems(15)`, line-height 20, weight MEDIUM,
   `theme.text`
3. **Multi-select hint** (only when `question.multi_select`) — `mt` 4,
   `ui_rems(12)`, `theme.text_muted.opacity(0.65)`, text
   **`"Select one or more options."`**
4. **Option list** — `mt` 12, flex column, `gap` 4
5. **Free-text override** — `mt` 12, `border_t_1` `theme::hairline(0.06)`,
   `pt` 12, `pb` 4, `px` 4, child = the **shared composer input** (the same
   textarea instance — do not mount a second one)

**Option row** (`("wizard-option", ix)`)

| property | value |
|---|---|
| layout | flex row `items_center`, `gap` 12 |
| padding | `px` 14, `py` 10 |
| radius | 12 |
| border | 1px — `theme::ink(0.16)` when picked, transparent otherwise |
| background (picked) | `theme::ink(0.09)` |
| background (unpicked) | `motion::hover_blend("wizard-option-{ix}", ink(0.025), ink(0.06))` over `HOVER_FADE` (150 ms `EASE_TAILWIND`) |
| cursor | pointer |
| label | `flex_1 min_w_0`, `ui_rems(13.5)`, weight MEDIUM; `theme.text` when picked, `theme.text.opacity(0.9)` otherwise |
| number chip (only `ix < 9`) | `flex_none`, `size(22)`, centred, `rounded` 6; `bg ink(0.16)` picked / `ink(0.05)` not; `ui_rems(11)`; colour `theme.text` picked / `theme.text_muted.opacity(0.6)` not; label `"{ix+1}"` |

`picked` on the row is `wizard.is_picked(ix) && typed_empty` — **a typed
override wins and visually deselects every option.**

**Footer row** — flex row `justify_between items_center`, `px` 16, `pb` 16,
`pt` 4:
- left: `popover::btn_ghost(theme, "Back", "wizard-back")` when `page > 0`, else
  nothing. `btn_ghost`: `px` 12, `py` 6, `rounded` 8, `ui_rems(13)`, colour
  blends `theme.text_muted → theme.text`, background blends
  `wash(0.0) → ink(0.06)`.
- right: `popover::btn_primary(theme, last ? "Submit" : "Next")` with `.px(16)`
  override and `opacity(0.4)` when `!can_advance`. `btn_primary`: `py` 6,
  `rounded` 8, `bg theme.text`, `ui_rems(13)`, weight MEDIUM, `text_color
  theme.on_solid`, hover `opacity(0.9)`.

`can_advance = wizard.page_has_pick() || !typed_empty`. **Note the disabled look
is opacity only — the click handler still fires.**

**Text (verbatim)** — `"Back"`, `"Next"`, `"Submit"`,
`"Select one or more options."`, the counter `"{page+1}/{len}"`, and the two
wizard placeholders on the shared input:
- nothing picked: `"Type your own answer, or pick an option above"` (5897, 6756)
- an option picked: `"Type your own answer, or leave this blank to use the selected option"` (6754)
- restored on finish: `"Do anything…"` (6810)

**Keyboard (`on_wizard_key`, 6863)**

| key | condition | effect |
|---|---|---|
| `1`–`9`, **no modifier** | `!input_focused \|\| input_empty` | `wizard_select(digit − 1)`, `stop_propagation` (so the digit is not also typed) |
| `1`–`9` with any modifier | — | ignored (⌘1..⌘9 belong to the sidebar) |
| `enter` | `!input_focused` | `wizard_advance`, `stop_propagation` |
| `enter` | input focused | handled by the input's own `Submit` action → `on_submit` → sets the typed answer, then `wizard_advance` |
| `escape` | `!input_focused \|\| input_empty` (`wizard_escape_goes_back`) | `wizard_back` |
| `escape` | input focused and non-empty | swallowed, no page change |

**Web mapping.** The wizard panel's own `onKeyDown` sits on the **panel
wrapper** and must guard against double handling exactly as `on_wizard_key`
does. While the wizard is mounted, the textarea's Enter policy becomes "bare
Enter always submits the page" and `ModifiedSubmit` is dropped — this is the
`GENERIC_COMPOSER_CONTEXT` (`"Composer"`) behaviour;
`message_input_context(wizard_active)` returns `"Composer"` while active, else
`"MessageComposer"`. Desktop test:
`wizard_borrows_the_generic_enter_context_only_while_active` (8404).

**Lifecycle**
- opened by `on_state_changed` when `pending_input_request(&transcript)` returns
  a request id not already in `answered_requests`; a **different** request id
  rebuilds the `Wizard` and resets `advance_task` and the input placeholder.
- **latch**: once open it is released only when the transcript shows the request
  explicitly resolved (`input_request_resolved`), or when a non-empty transcript
  shows it superseded. **Never on run death** — a question stays answerable
  until answered, because the engine delivers a dead run's answer as a resumed
  turn.
- a pending question must **not** take over an active queue edit (5880–5883).
- **auto-advance**: a single-select pick schedules `wizard_advance` after
  **220 ms** (`AUTO_ADVANCE_MS`).
- on advance to the next page the shared input is **cleared**.
- `wizard_finish` submits `SessionCommandPayload::RespondInput { request_id,
  answers }` via `methods::QUEUE_COMMAND` on `action_task` (**never**
  `send_task`), inserts the request into `answered_requests`, clears the input,
  restores the `"Do anything…"` placeholder and the `MessageComposer` key
  context.
- **safety net**: **2 s** after a successful queue, if the same request is still
  the live pending input, the request is removed from `answered_requests` and
  the panel reappears (the host may have rejected the command).
- on failure: `failure = "Answer failed: {err}"`, `failure_key = chat`, and the
  request is removed from `answered_requests` so the panel comes back.

---

## 3. Pure logic to port

### `mention_token(text, cursor)` (3866) — §2.1.
### `slash_token(text, cursor)` (3905) — §2.5.
### File-mention Markdown helpers (929–1051) — §2.2.
### `dropped_file_mention(content, range, path, is_dir)` (947) — §2.2.
### `mention_display_labels(links)` (1267) — §2.3.
### `sent_mention_display(raw)` (1316) — §2.3.
### `TextProjection::{raw_to_display, display_to_raw, normalize_range, previous_boundary, next_boundary}` (1158–1262) — §2.3.
### `mention_tooltip_reduce` / `mention_tooltip_promote` / `mention_tooltip_contains` (1092–1129) — §2.3.
### `display_row_segments(range, row_ends)` (1131) — §2.3.
### `mention_response_is_current(state, request)` — matching generation AND a token still open.
### `popover::menu_step(active, count, delta)` (popover.rs:214)
Wraps at both ends via `rem_euclid`; `None` enters at index 0 for `delta >= 0`,
else at `count − 1`; an empty list stays `None`.
### `popover::filter_indices(query, labels)` (popover.rs:252)
Rank 0 = case-insensitive prefix match, rank 1 = substring; sorted by
`(rank, input index)`; an empty/whitespace query matches everything at rank 1
preserving input order.
### `enter_outcome(has_completion, fallback)` (1407) — §2.7.
### `escape_dismisses_completion(key, completion_open)` (602)
`key === "escape" && completion_open`.
### `wizard_escape_goes_back(key, input_focused, input_empty)` (606)
`key === "escape" && (!input_focused || input_empty)`.
### `message_input_context(wizard_active)` (1392)
`"Composer"` while the wizard is active, else `"MessageComposer"`. The only
caller is `on_state_changed` (5925–5927); `wizard_finish` restores
`"MessageComposer"` directly (6811). `"PaletteSearch"` is never produced here —
palette fields declare it at construction.
### `pending_input_request(transcript) -> (request_id, questions) | null` (622)
The **last assistant entry**'s first unresolved `MessagePart::Input`,
**regardless of that entry's run status**. Assistant-entry-scoped, *not*
last-entry-scoped: a steer prompt sent while the agent waits appends a USER
entry after the streaming assistant entry, and a last-entry read would make the
panel vanish exactly when the user typed. A dead/aborted entry still yields the
panel.
### `input_request_resolved(transcript, request_id)` (644)
Any entry with an `Input` part whose `request_id` matches and `resolved: true`.
### `Wizard` (676) and `WizardStep`
- `counter()` → `"{page+1}/{max(len,1)}"`
- `select(ix)`: out-of-range → `Stay`. Multi-select toggles membership → `Stay`.
  Single-select replaces the pick → `AutoAdvance`.
- `press_number(n)`: `n == 0` → `Stay`; else `select(n − 1)` (so 9 on a
  2-option question is `Stay`).
- `set_typed(text)` stores per-page free text.
- `advance()`: `page + 1 < len` → `page += 1`, `Stay`; else `Done(answers())`.
- `back()`: `page > 0` → decrement, `true`; else `false`.
- `answers()`: per question, **trimmed typed text wins as a single label**;
  otherwise the picked option labels **in pick order**.
- `page_has_pick()` / `is_picked(ix)` back `can_advance` and the row's picked
  state.

### Desktop tests that become web unit tests

| desktop test | what it pins |
|---|---|
| `mention_token_requires_a_token_boundary_and_tracks_full_token` (8499) | `mention_token` |
| `slash_token_only_opens_the_prompt` (8517) | `slash_token` |
| `file_mentions_serialize_to_strict_local_markdown` (8559) | `local_file_link` + encoding |
| `file_mentions_reject_external_or_noncanonical_markdown` (8598) | `file_mention_links` round-trip rule |
| `dropped_mentions_are_separated_from_surrounding_text` (8579) | `dropped_file_mention` spacing |
| `dropped_mentions_reject_paths_outside_the_workspace` (8592) | `local_path_is_safe` |
| `duplicate_mention_basenames_use_unique_suffixes` (8609) | `mention_display_labels` |
| `mention_suffixes_compare_path_components` (8621) | per-component suffix comparison |
| `projection_maps_and_expands_atomic_chip_ranges` (8643) | `TextProjection::normalize_range` |
| `sent_mention_display_projects_chips_for_the_transcript` (8668) | `sent_mention_display` |
| `sent_mention_display_leaves_plain_prompts_untouched` (8692) | the `null` fast path |
| `mention_wash_moves_wholly_to_the_next_visual_row_at_a_wrap` (8487) | `display_row_segments` |
| `mention_tooltip_wait_survives_pointer_jitter_and_promotes_once` (8425) | `mention_tooltip_reduce` + `_promote` |
| `mention_tooltip_changes_target_and_cancels_disappeared_target` (8455) | target change + dead target |
| `mention_tooltip_stays_visible_over_chip_or_popup_only` (8480) | `mention_tooltip_contains` |
| `dismissed_mentions_reject_stale_responses` (8545) | `mention_response_is_current` |
| `enter_accepts_a_completion_before_submit_or_newline` (8384) | `enter_outcome` |
| `escape_consumers_keep_completion_and_wizard_priority` (9433) | `escape_dismisses_completion` + `wizard_escape_goes_back` |
| `wizard_borrows_the_generic_enter_context_only_while_active` (8404) | `message_input_context` |
| `wizard_single_select_auto_advances_and_completes` (9445) | `select` → `AutoAdvance`, `advance` → `Done` |
| `wizard_multi_select_toggles_and_stays` (9468) | multi toggle-off |
| `wizard_number_keys_and_bounds` (9483) | `press_number` |
| `wizard_typed_answer_overrides_and_back_pages` (9493) | typed override trimmed (`"  custom answer  "` → `"custom answer"`), back paging bounds |
| `pending_input_detection` (9521) | `pending_input_request` + `input_request_resolved` |

---

## 4. Gaps this ticket closes

From `04-composer.md` §5.3, verbatim.

| item | kind | desktop | web | fix |
|---|---|---|---|---|
| `@` file mentions (popup, chips, tooltip, projection, atomic caret) | MISSING | §3.13, §3.16 | nothing | Port the whole feature |
| `/` slash commands | MISSING | §3.17 | nothing | Port |
| Question wizard | MISSING | §3.19 | nothing | Port |
| Key-binding **contexts** | MISSING | three named contexts — `MESSAGE_COMPOSER_CONTEXT`, `GENERIC_COMPOSER_CONTEXT` (wizard), `PALETTE_SEARCH_CONTEXT` — each with a different Enter/navigation-key policy (§3.12) | one `onKeyDown` on the textarea with a single fixed policy (composer.tsx:381–397); picker search inputs have no let-through rule | Give the textarea two modes (message vs wizard) and make every picker/palette search input let bare arrows, Enter and Tab bubble to its list frame |
| Drag-and-drop | WRONG SCOPE | the drop target is the **whole conversation column** (`shell.rs#chat-dropzone`), and it also accepts `WorkspacePathDrag` (file tree / file tab) which inserts a file-**mention**, not an attachment | the drop target is only `.composer-attachments` (attachment-strip.tsx:184–191) | this ticket owns only the **workspace-path branch** (`dropped_file_mention`); widening the drop zone is ticket 17 |

Also closed from §5.4: the `tab` → `MentionTab` and `escape` → dismiss-completion
rows, and the "Enter is context-dependent" row's completion half.

---

## 5. Do not

- **Do not** re-implement the popover card, the entrance/exit motion,
  `menuStep`, `filterIndices`, `menuRow` or the scrollbar rail — ticket 09 owns
  them. If a value here disagrees with ticket 09, ticket 09 wins and you note it
  in Comments.
- **Do not** render a slash accept as a chip. `/name` is **plain text** — no
  link, no projection, no wash.
- **Do not** render "No matching files" for an RPC failure. Each failure has its
  own verbatim string.
- **Do not** fire an RPC per slash keystroke. `LIST_COMMANDS` is once per
  harness per composer lifetime; filtering is local.
- **Do not** close the wizard when the run dies. The latch releases only on
  `input_request_resolved` or an explicit supersede.
- **Do not** mount a second textarea for the wizard's free-text field — it is
  the **same** composer input, re-parented.
- **Do not** let the wizard take over an active queue edit.
- **Do not** disable the Next/Submit button. `!can_advance` is `opacity: 0.4`
  only; the click handler still fires.
- **Do not** bind `⌘1`–`⌘9` in the wizard — those belong to the sidebar
  (ticket 12). Only **bare** digits select.
- **Do not** build the pill geometry, the flip morph, the send path or the
  footer — ticket 13.
- **Do not** build the new-thread canvas or the dock — ticket 15.
- **Do not** widen the drop zone to the conversation column — ticket 17 owns
  that; this ticket supplies only the mention-insertion function it calls.
- **Do not** attempt IME marked-text underlines or the gpui
  `EntityInputHandler` — desktop-only (`04-composer.md` §6).

---

## 6. Acceptance

- [ ] Typing `@` after a whitespace, `(`, `[`, `{` or at text start opens the
      popup above the pill with the `MENU_IN` motion; `mail@example.com`,
      `word@file` and `path/@file` do not.
- [ ] The popup card is `max-height: 320px`, radius 12, `padding: 4px`, 1px
      `rgb(var(--rb-hairline) / 0.10)`, `shadow_lg`, with the list at
      `max-height: 312px`.
- [ ] All four card states render: 3 skeleton rows while loading, the three
      verbatim error strings, `"No files available"` / `"No matching files"`,
      and result rows (icon 14px, basename 13px, directory 12.5px truncated).
- [ ] Search debounces 80 ms, retries once after 250 ms on transport failure,
      drops stale responses, and keeps stale rows visible while refining.
- [ ] Accepting inserts `[name](roboco-file:encoded/path)` and the chip renders
      as `NBSP @label NBSP` in mono over a `code_wash` quad at radius 5.
- [ ] Left/Right and Backspace/Delete treat a chip as one unit; a click inside a
      chip snaps the caret to the nearer edge; a selection overlapping a chip
      swallows it whole.
- [ ] Hovering a chip for 420 ms shows the 24px-tall, 480px-max, mono, 11px path
      tooltip above the chip (below when there is no room); jitter over the same
      chip does not restart or flicker it; any edit or scroll clears it.
- [ ] A sent prompt with mentions renders the same chips in the transcript.
- [ ] Typing `/` at offset 0 opens the slash popup; `run /compact` and
      `/usr/bin` do not. Filtering is local with prefix-before-substring rank;
      `LIST_COMMANDS` fires once per harness.
- [ ] Accepting a slash command inserts plain `/name` plus a trailing space
      (unless one follows), with no chip.
- [ ] Both popups share one scrollbar rail: 10px hit width, 3px thumb (5px
      active), `text_faint` at 0.5 / 0.68, 24px minimum, hidden unless hovered
      **and** overflowing.
- [ ] Enter accepts a selected completion before it submits or inserts a
      newline; Escape dismisses the popup and stops there; re-opening requires
      editing the token.
- [ ] When a run asks a question, the pill is replaced in place by the 26px-radius
      wizard panel over a 150 ms fade; the header is hair-spaced uppercase; the
      counter chip only appears with more than one question.
- [ ] Option rows: 22px number chips for indices 0–8, picked state at
      `ink(0.16)` border + `ink(0.09)` fill, hover blend from `ink(0.025)` to
      `ink(0.06)` over 150 ms.
- [ ] A single-select pick auto-advances after 220 ms; multi-select toggles and
      stays; typing an answer visually deselects every option and wins on submit
      (trimmed).
- [ ] Bare digits 1–9 select only when the input is unfocused or empty and are
      not also typed; modified digits are ignored; Enter advances when the input
      is unfocused; Escape pages back and is always swallowed.
- [ ] The panel stays up when the run dies, and only closes on an explicit
      resolve — and reappears 2 s later if the host rejected the answer, with
      `"Answer failed: {err}"` on an RPC error.
- [ ] Unit tests (each named after the desktop test it mirrors, across
      `tests/mentions.test.ts`, `tests/slash.test.ts`, `tests/wizard.test.ts`):
      `mention_token_requires_a_token_boundary_and_tracks_full_token`,
      `slash_token_only_opens_the_prompt`,
      `file_mentions_serialize_to_strict_local_markdown`,
      `file_mentions_reject_external_or_noncanonical_markdown`,
      `dropped_mentions_are_separated_from_surrounding_text`,
      `dropped_mentions_reject_paths_outside_the_workspace`,
      `duplicate_mention_basenames_use_unique_suffixes`,
      `mention_suffixes_compare_path_components`,
      `projection_maps_and_expands_atomic_chip_ranges`,
      `sent_mention_display_projects_chips_for_the_transcript`,
      `sent_mention_display_leaves_plain_prompts_untouched`,
      `mention_wash_moves_wholly_to_the_next_visual_row_at_a_wrap`,
      `mention_tooltip_wait_survives_pointer_jitter_and_promotes_once`,
      `mention_tooltip_changes_target_and_cancels_disappeared_target`,
      `mention_tooltip_stays_visible_over_chip_or_popup_only`,
      `dismissed_mentions_reject_stale_responses`,
      `enter_accepts_a_completion_before_submit_or_newline`,
      `escape_consumers_keep_completion_and_wizard_priority`,
      `wizard_borrows_the_generic_enter_context_only_while_active`,
      `wizard_single_select_auto_advances_and_completes`,
      `wizard_multi_select_toggles_and_stays`,
      `wizard_number_keys_and_bounds`,
      `wizard_typed_answer_overrides_and_back_pages`,
      `pending_input_detection`.
- [ ] Screenshot pair, desktop vs web, states: (a) `@` popup open with results,
      second row selected; (b) `@` popup open showing "No matching files";
      (c) a draft containing two mention chips, one hovered with its path
      tooltip; (d) `/` popup open with a filtered list; (e) wizard page 1 of 2,
      single-select, option 2 picked; (f) wizard last page with a typed answer
      (all options visually deselected).
- [ ] `pnpm -r build` green; `web/packages/app` vitest green.
- [ ] No new literal hex/px where a `--rb-*` token exists.

## Comments

### What landed (2026-09-18)

All three features, web-only, no `crates/` or `apps/` changes:

- **`lib/mentions.ts`** — `mentionToken`, the strict local Markdown
  transport (`percentEncodePath`, `escapeMentionLabel`, `localFileLink`,
  `localPathIsSafe`, `fileMentionLinks`, `droppedFileMention` — exported for
  ticket 17's drop zone to call), `mentionDisplayLabels`, `TextProjection`
  (raw↔display, `normalizeRange`, boundary helpers), `displayRowSegments`,
  `sentMentionDisplay`, the tooltip phase reducers, `mentionResponseIsCurrent`,
  `mentionErrorMessage`, and the constants. Offsets are UTF-16 code units —
  the direct equivalent of the desktop's byte indices, since every caret
  value comes from `selectionStart`/`selectionEnd`.
- **`lib/slash.ts`** — `slashToken`, `slashErrorMessage`, the cache shape,
  `refilterSlash` (local per-keystroke, prefix-before-substring), the row
  description composer, and a tolerant reply parser.
- **`lib/wizard.ts`** — `Wizard`, `pendingInputRequest`,
  `inputRequestResolved`, `enterOutcome`, `escapeDismissesCompletion`,
  `wizardEscapeGoesBack`, `messageInputContext`, the placeholders, and the
  220ms/2s timers.
- **`components/composer/mention-popup.tsx`** — the `full_width_menu_above`
  frame (absolute child of `.composer-surface`, `pb 6`, `MENU_IN` entrance,
  outside-press dismissal, focus-preserving card mouse-down) plus
  `MentionPopup` and its four states; rows ride `ui/MenuRow`, the card is
  `ui/PopoverCard`, the rail is `ui/Scrollbar`, skeletons `ui/Skeleton`.
  `CompletionPopup` is exported and shared by the slash popup.
- **`components/composer/slash-popup.tsx`** — the same frame with command
  rows (command icon 14, `/{name}` 12.5/500, description 12 truncated).
- **`components/composer/wizard.tsx`** — `ComposerWizard` (panel, header,
  counter, options, free-text slot, ghost/primary footer) with
  `on_wizard_key` on the focusable panel wrapper; `!can_advance` is opacity
  0.4 only and the click handler still fires.
- **`composer.tsx`** — the token machines (`on_input_edited`/`update_slash`
  ports: 80ms debounce, one 250ms transport retry, generation-guarded
  replies, refining keeps stale rows, dismissed-token memory), the chip
  mirror (transparent textarea text/caret + projected display layer with
  chip washes, selection wash, and a custom blinking caret at the display
  offset), atomic caret enforcement (Left/Right/Backspace/Delete at chip
  boundaries + `normalizeRange` on every selection change), the tooltip
  (420ms phase machine, above-chip anchor with the flush-below fallback),
  the wizard lifecycle (latch, auto-advance, finish → `QueueCommand
  respondInput`, 2s safety net, `Answer failed:` notice), and the two
  Enter/Escape modes. The popups mount inside `.composer-surface` and the
  pill swaps for the wizard panel in place; the flip machinery stands down
  while the wizard is mounted.
- **`transcript.tsx` / `chat-page.tsx`** — the chat page now owns ONE
  `TranscriptStore` per chat and hands it to both `TranscriptView` (new
  optional `store` prop; the subagent dialog's own-store path is unchanged)
  and the composer, so the wizard's latch reads the same stream the
  transcript renders — no second `WatchDocMessages` per chat.
- **`lib/transcript.ts`** — ticket 18 had already ported the sent-mention
  projection privately for the transcript; the projection now lives once in
  `lib/mentions.ts` and transcript.ts re-exports the surface it always
  exposed. Ticket 18's tests are untouched and green. The transcript-side
  chip rendering itself (`.user-mention`) was already ticket 18's — nothing
  new was needed there.
- **`engine-client/methods.ts`** — added `SEARCH_FILES` and `LIST_COMMANDS`
  (wire names per `crates/rpc/src/lib.rs:42,131`).

### Deviations & judgment calls

- **The wizard textarea remounts.** The desktop re-parents the gpui entity,
  preserving caret and undo. React cannot move a DOM node between parents
  without remounting it, so the swap re-mounts the SAME controlled element
  (one JSX node, one draft state, one placeholder machinery — never a second
  input). Caret/undo do not survive the swap; undo already does not survive
  programmatic value writes (ticket 13's accepted divergence). On open,
  focus lands where the desktop keeps it (the input if it held focus, else
  the panel).
- **The mention path tooltip is a fixed div, not `ui/Tooltip`.** Its anchor
  is chip geometry under a pointer-events-none mirror (hit-tested through
  the transparent textarea), its lifecycle is the ported 420ms phase
  machine, and its geometry is the ticket's spec — it cannot ride a
  trigger-element tooltip. All pure logic (reduce/promote/contains) lives
  in `lib/mentions.ts` as the ticket demands.
- **The chip caret and selection wash are custom.** While mentions exist
  the native caret/selection highlight are hidden (`color: transparent`,
  `caret-color: transparent`, `::selection` transparent) because their
  raw-offset geometry no longer matches the display text; the mirror paints
  the caret (keyed remounts keep it solid while typing) and the selection
  wash. IME compositions temporarily flip back to raw text (the mirror
  would hide marked text) and pause the token machines.
- **Chip wash geometry**: the +2/−4 vertical inset is painted via a
  `background-size: 100% calc(100% - 4px)` shift inside the 5px-radius span
  (per-fragment via `box-decoration-break: clone`, matching the desktop's
  per-row-segment quads at wraps).
- **Error-kind mapping**: the web client's `timeout`/`parked` kinds map to
  "unreachable" (the desktop has no client timeout; both mean the reply
  never came back over the transport).
- **The wizard header's tracking** uses real CSS `letter-spacing: 0.1em`
  (the repo's `MenuHeading` convention — the desktop's U+200A workaround
  would break copy/paste).
- **Offsets are UTF-16 code units** throughout the ported pure logic (see
  the lib header); boundary checks port `is_char_boundary` as "not inside a
  surrogate pair".

### Verification

- `pnpm -r build` green (web workspace).
- `web/packages/app` vitest: **53 files / 821 tests green**, including the
  new `tests/mentions.test.ts` (22), `tests/slash.test.ts` (10),
  `tests/wizard.test.ts` (14) — every acceptance-named desktop test has a
  web mirror. `tests/transcript-model.test.ts` (ticket 18's, against the
  deduped projection) still green.
- `web/packages/engine-client` unit suites green (codec, fake-server,
  watch-cache). The conformance/web-smoke suites spawn Rust engines and are
  CI-only here (they also need the machine-global port 27699, which the
  screenshot round was holding).
- Boot check + live exercise against `web_smoke`: app renders with no
  error boundary before and after; the `@` search round-trip (80ms debounce
  → results), Enter-accept, Escape-dismiss + caret-move-stays-closed +
  edit-reopens, atomic Left (two presses crossed a 37-char link to its
  start), atomic Backspace (one press removed a whole 46-char mention),
  the mirror's chips/transparent raw text, the 420ms hover tooltip (24px
  tall, above-chip anchor), and a full send + mock-harness reply all
  verified in-DOM. Screenshots taken for the stageable states.

### Screenshots (web; `shots/14/`)

- `web-a-mention-popup-results.png` — (a) `@` popup with 8 real results,
  second row selected. Staged by creating a real space + chat through the
  app's own UI (the seeded smoke chat has no space, so its chatId-targeted
  search cannot resolve a workspace root).
- `web-b-mention-popup-no-matches.png` — (b) "No matching files".
- `web-c-draft-chips-tooltip.png` — (c) draft with two mention chips
  (both accepted through the popup), one hovered with its 24px path
  tooltip.
- `web-d-slash-popup.png` — (d) the slash popup on the smoke engine. The
  mock harness advertises no commands, so this shows the verbatim
  "This agent has no slash commands" empty state; the FILTERED-list
  variant needs a harness with commands, which the smoke fixture cannot
  stage without `crates/` changes (out of scope per the ticket rules).
- `boot.png` / `web-boot-after-exercise.png` — boot checks.
- **Skipped**: (e)/(f) wizard pages — the mock script never emits
  `InputRequested`, and the transcript is engine-owned, so the panel
  cannot be staged by the smoke fixture. The wizard's logic is fully
  unit-tested (`wizard.test.ts`, `pending_input_detection`).
- **Skipped**: desktop-side captures — same as ticket 13's precedent (no
  desktop app running in this environment; every number was transcribed
  from the cited `composer.rs`/`popover.rs` lines).



### Shared components addendum (2026-09-18)

Build on components/ui/ + components/base/ (see components/README.md)
— do not hand-roll card shells, cursor lists, menu rows, chips, or
tooltips.
