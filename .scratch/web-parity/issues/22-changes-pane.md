# 22 — Changes pane

**What to build:** The right pane's "Changes" tab renders and behaves like
the desktop's diff viewer: a header strip with a scope trigger (Working
tree / Branch changes / Latest turn / Commit-pinned), a branch/base ref
selector, split/wrap/fold-all toggles, then a virtualized list of file
headers with sticky-header float-past, hunk headers, unified or split diff
lines with correct column widths and tints, and a change-request badge
wherever the desktop shows one (sidebar row, composer footer — NOT inside
the diff body itself). After this ticket, opening a chat's Changes tab in
the right pane shows real content again (today it renders nothing because
`SURFACES_DISABLED = true`), with geometry, colors, and strings matching
the desktop pixel-for-pixel at desktop widths.

**Blocked by:** 07 (Right pane host and multi-instance tabs) — this ticket
mounts inside whatever tab/pane shell 07 establishes; it does not itself
fix `right-pane.tsx`'s `SURFACES_DISABLED` flag or the tab-cardinality
model. If 07 has not yet generalized `RightSurface` to support a
commit-pinned tab, ship the `DiffScope::Commit` header/scope-menu variant
inert (selectable value, not yet reachable from a real "open commit"
action) and note it in Comments.

**Status:** done

**Research:** `../../web-client/research/07-changes.md` §1, §2, §3.1–§3.17,
§4, §5 (all rows), §6, §7. Cross-reference `../../web-client/research/00-index.md`
finding 1 (`SURFACES_DISABLED`) and finding 4 (right-pane tab cardinality).

**Desktop reference (for lookups only):** `crates/ui/src/changes.rs`
(`impl Render for Changes` ~4759–4920, `render_header_controls` 3637–3843,
`render_scope_menu` 3845–3871, `render_ref_selector`/`render_ref_menu`
3875–4072, `render_file_header` 3348–3482, `render_sticky_file_header`/
`sticky_file_header_paint` 1441–1463/3484–3539, `hunk_header_row`
4157–4172, `notice_row` 4142–4155, `diff_line_row` 4233–4368, `split_row`/
`split_line_cell` 4408–4544, `render_file_body_with_syntax` 4600–4641,
`render_file_body_upto` 4643–4757, `body_height`/`body_height_with`
614–630, `parse_patch` 432–559, `file_notices` 562–578, `gutter_width`
259–262, `split_pairs`/`split_pairs_upto` 648–757), `crates/ui/src/change_requests.rs`
(`pull_request_badge` 117–169, `ChangeRequestTooltip` 57–109,
`change_request_for_chat` 290–318, `desired_watch_targets`/`watch_params`
238–284), `crates/ui/src/surface_chrome.rs` (control constants),
`crates/ui/src/comment_ui.rs::render_comment_adder` (18–46, hook point
only — implementation is ticket 23).

**Web files to touch:**
| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/routes/changes-page.tsx` | edit | `ChangesPage`, `ChangesSurface`, `ChangesBody`, `BasePicker` |
| `web/packages/app/src/components/diff-view.tsx` | edit | `DiffView`, `DiffSurface`, `DiffScroller`, `RowContent`, `FileHeaderRow`, `HunkHeaderRow`, `NoticeRow`, `LineRow`, `UnifiedLineRow`, `SplitLineRow`, `LineText`, `DIFF_METRICS` |
| `web/packages/app/src/lib/diff.ts` | edit | `DiffScope`, `DIFF_SCOPE_LABELS`, `scopeLabel`, `cleanMessage`, `defaultBaseRef`, `gutterWidth`, `parsePatch`, `splitPairs`/`splitPairsUpto`, `flattenFileRows`, `fileCounts`, `resolveDiff`, `diffPhase`, `parseKey`, `scopeMode` |
| `web/packages/app/src/lib/change-requests.ts` | edit | `changeRequestCreateUrl` |
| `web/packages/app/src/components/change-request-badge.tsx` | edit | `ChangeRequestBadge`, `CreateChangeRequestButton`, `toneFor`, `TONE_LABEL` |
| `web/packages/app/src/state/changes-store.ts` | edit | `ChangesStore`, `ChangesSnapshot`, `setScope` (wire the already-present but dead `commitSha` param) |
| `web/packages/app/src/state/change-requests-store.ts` | edit | `ChangeRequestStore`, `changeRequestForChat`, `keyOf`, `checkoutKey`, `watchParams` (omit `targetDeviceId` when local) |
| `web/packages/app/src/components/right-pane.tsx` | edit | mounts `ChangesSurface` once `SURFACES_DISABLED` work from ticket 07 lands — this ticket only needs to confirm the mount point, not remove the flag itself |
| `web/packages/app/src/styles/app.css` | edit | `.changes-*`, `.diff-*`, `.cr-*`, `.tk-*` rule blocks |
| `web/packages/app/tests/diff.test.ts` | new/edit | pure-logic unit tests (see §3) |

## 1. Context a fresh session needs

- The Changes surface has TWO web hosts today: a routed page
  (`/chat/$chatId/changes`, `ChangesPage`) and the right pane's embedded
  surface (`ChangesSurface`, both defined in `routes/changes-page.tsx`).
  Both delegate to the same `ChangesBody` component — keep that shape; do
  not fork the logic.
- `ChangesBody` owns a `ChangesStore` (per chat: checkout id, device id,
  cwd) that subscribes `WatchCheckoutDiffs` for the working-tree diff and
  does a one-shot `GetCheckoutDiff` for branch/turn/commit scopes, plus a
  `ChangeRequestStore` that watches `WatchCheckoutChangeRequest` for the
  chat's `(device, cwd, branch)` tuple.
- `DiffView` (`components/diff-view.tsx`) takes an already-fetched
  `CheckoutDiff`, parses its patch (`lib/diff.ts::parsePatch`, cached by
  `parseKey`), flattens it to rows (`flattenFileRows`), and hand-rolls a
  virtualizer (`DiffScroller`) that measures each row's real height after
  first paint and estimates before that (`estimateRowHeight`).
  `lib/diff.ts` is otherwise a faithful, already-tested port of the
  desktop's pure diff logic (`parse_patch`, `split_pairs`, `gutter_width`,
  `resolve_diff`, `diff_phase`, `default_base_ref`, `scope_label`,
  `clean_message`) — this ticket's job is mostly the RENDERING gaps, not
  re-porting logic that already matches.
- **The right pane never mounts this surface today.**
  `web/packages/app/src/components/right-pane.tsx:39` hard-codes
  `const SURFACES_DISABLED = true`, which nulls out `ChangesSurface` (and
  Files/Terminal/Preview) unconditionally. That flag is ticket 07's to
  remove; this ticket must still build `ChangesSurface` correctly so that
  the moment 07 flips the flag, it renders right. Use the ROUTED page
  (`/chat/$chatId/changes`) to verify your work in the meantime — it is
  unaffected by the flag.
- Tokens: colors are `var(--rb-<role>)` / `rgb(var(--rb-wash) / a)` /
  `rgb(var(--rb-ink) / a)` / `rgb(var(--rb-hairline) / a)`; no literal hex.
  `theme.solid`/`theme.on_solid` map to `--rb-solid`/`--rb-on-solid`;
  `theme.diff_add`/`theme.diff_del` map to `--rb-diff-add`/`--rb-diff-del`
  (already defined per the gap list's citations of `var(--rb-diff-add)`).
- Vocabulary: this is the "Changes" surface, a chat's checkout diff — not
  "Diffs" or "Git changes" anywhere in strings or aria-labels.
- The change-request badge (`ChangeRequestBadge`) is used TWICE on
  desktop — sidebar chat row, composer footer — and NEVER inside the
  Changes tab's own body. The web's `.changes-cr-card` block inside
  `ChangesBody` has no desktop equivalent; it is a documented, intentional
  web-only addition (§4/§5 below), not something to delete, but do not
  expand it or treat it as if it were porting real `changes.rs` chrome.
- Comment adder hook point: this ticket must leave a place for ticket 23's
  hover "+" (see §2.9/§2.10's "Interactions") but must NOT implement the
  adder, draft, or card — those are ticket 23's `comment_ui.rs` port.
- Soft dependency (not a blocker): ticket 24 ("Files tree and search")
  builds the shared `FileIcon` component from the `file_icons` resolver
  spec. This ticket can land with a placeholder glyph and swap in `FileIcon`
  once 24 exists — see §2.5.

## 2. Spec

### 2.1 `Changes` root / `ChangesBody`

**Layout**

| property | value | source |
|---|---|---|
| root | `size_full().flex().flex_col()` | changes.rs:4897-4903 |
| font | `theme.font_sans_fixed` for chrome (mono is per-element override) | changes.rs:4903 |
| error banner padding | `px(Theme::SPACE_MD)` (12) horizontal, `py(4.0)` | changes.rs:4907-4909 |
| error banner border | `border_b_1`, `theme.border` | changes.rs:4910-4911 |
| error text | size 11, `theme.warning` | changes.rs:4912-4914 |
| content | `flex_1` | changes.rs:4917 |

**Children (in order):** optional error banner, then content (one of
Preparing / Clean / scoped-fetch-error / List).

**States**

| state | condition | what changes |
|---|---|---|
| No chat selected | no chat resolved | phase forced to Clean (no spinner on the new-chat canvas) — changes.rs:4772-4777 |
| Watch stream error | `self.error.is_some()` | top warning banner shown, **last content stays visible underneath** — changes.rs:4778, 4904-4916 |
| Scoped fetch error | `scope != WorkingTree && scoped_error.is_some()` | content area replaced entirely by a centered message; friendly remaps: `"no turn recorded"` → *"No turn recorded yet — send a message first"* (not warning color); `"unknown method"` → *"This chat's device is running an older Roboco — update it to view branch and turn diffs"* (not warning color); anything else → raw message in `theme.warning` — changes.rs:4785-4821 |
| History scope | `scope == DiffScope::History` | short-circuits to the History pane — **N/A for web**, ticket 27 owns History as its own tab, not a `Changes` scope value |

**Text (verbatim):** `"Preparing diff…"`, `"Diff stream interrupted — retrying"`,
`"Diff watch unavailable: {err}"`, `"No turn recorded yet — send a message first"`,
`"This chat's device is running an older Roboco — update it to view branch and turn diffs"`,
`"Partial snapshot"`.

**Data:** reads the chat's checkout diff (watch) plus the scoped one-shot
capture. Writes: none from render.

**Virtualizer average-row-height hint**: the desktop's `ListState::new`
seeds the row list with an estimated/average row height of **1024.0px**
(`changes.rs:1665`) — deliberately generous (real rows are 21-38px, so
this over-estimates 25-50×) to keep a deep overdraw window sizing the
list's initial materialized slice before any row has actually been
measured, so a fast wheel flick never outruns measurement. Web
equivalent: `DiffScroller`'s `estimateRowHeight` fallback (used before a
row's real height is measured) should stay generous in the same spirit —
do not tighten it to the real per-kind row heights; the estimate existing
today is fine as long as it errs high, not exact.

**"Partial snapshot" chip** (changes.rs:4114-4126, only rendered when
`parsed.truncated`): `text_size(10.0)`, `px(6.0)`/`py(2.0)`, `rounded(4.0)`,
`bg(theme.warning.opacity(0.08))`, `text_color(theme.warning.opacity(0.75))`.
This is a SEPARATE warning-alpha pairing from the scoped-fetch-error text
above (which is plain `theme.warning`, no opacity). Web: `.changes-banner-warn`
in `changes-page.tsx`.

**Current web gap:** `changes-page.tsx` shows the raw watch-stream `error`
string only (`ChangesStore.error`); `scoped`-fetch failures are not
surfaced distinctly and the two known substrings (`"no turn recorded"`,
`"unknown method"`) are never remapped. Fix both: read a `scopedError`
field off the store's snapshot, remap the two substrings, and render the
non-warning-colored friendly text; only an unrecognized message stays in
warning color.

### 2.2 Header controls (scope trigger, ref selector, split/wrap/fold-all)

Sized to `CONTROL_SIZE` = 24px tall controls with `CONTROL_GAP` = 4px
between them and `CONTROL_RADIUS` = 6px corners, `ICON_SIZE` = 14px icons
(`surface_chrome.rs:7-11`). On desktop this row is rendered by the SHELL
into the titlebar band above the pane (changes.rs:3637-3843, "rendered by
the shell" — `shell.rs:6486`); on web there is no titlebar/drag-strip
concept for the pane, so keeping it in-page (`.changes-header` +
`.changes-banner-tools`, as today) is the correct model — do not try to
hoist it into `titlebar.tsx`.

**Children (in order, non-commit)**: scope trigger (flex_none) → ref
selector (branch scope only) → flex-1 spring → trailing cluster
(split_toggle, wrap_toggle, fold-all button).

**Interactions**

| control | interaction |
|---|---|
| Scope trigger | click opens a scope menu (see 2.3) |
| Ref trigger | click opens a searchable branch menu (see 2.4) — branch scope only |
| Split/Wrap toggles | click toggles layout/wrap, persists (`diffSplit`, `diffWrap` — ticket 03's settings store), resets all files' horizontal scroll |
| Fold-all button | click collapses every file if any is expanded, else expands every file |
| Wrap toggle tooltip | text `"Wrap long lines"`, 350ms show delay |

**Motion**: header buttons use the shared **HOVER_FADE** catalog entry
(150ms, delay 0, `easeTailwind`/`cubic-bezier(0.4,0,0.2,1)` curve) for
every hover background/text blend in this surface (header buttons,
scope/ref triggers). Web: `--rb-motion-hover-fade` / `--rb-ease-tailwind`.

**`header_button`/`header_toggle` wash values** (changes.rs:3544-3597): a
**latched** button (`active == true` — split toggle when split mode is on,
wrap toggle when wrap is on) is a FLAT `bg(wash(0.14))` with NO hover
listener (deliberate — a latched button's hover blend is "neither read
nor driven"). An UNLATCHED button hover-blends from `wash(0.0)` to
`wash(0.14)` over HOVER_FADE. Icon color: `theme.text` when active,
`theme.text_muted.opacity(0.7)` otherwise.

**Scope trigger hover wash**: `hover_blend(wash(0.05), wash(0.14))` — rest
is `wash(0.05)` (not fully transparent, unlike the ref trigger), hover
target `wash(0.14)`.

**History-scope title slot width**: on desktop, when `scope ==
DiffScope::History`, the fixed branch-name slot (`id("history-surface-title")`)
that replaces the scope dropdown is capped at `max_w(px(160.0))`
(changes.rs:3751) — mono, size 11.5, `theme.text_dim`, truncating. N/A
for THIS ticket to build (History is ticket 27's own tab, not a
`Changes` scope on web) — cited here only so ticket 27 can reuse the
same 160px cap for its own branch-name title (see `27-history-pane.md`
§2.1's toolbar title, which already specifies `max-width: 160px`).

**Text**: scope trigger shows the scope's label (§3 `scopeLabel`).
Commit-pinned chip (if 07 has landed multi-instance tabs by the time this
is implemented) shows the short sha (7 chars, monospace, `ink(0.05)` bg)
then the commit subject; otherwise leave the `"commit"` `DiffScope`
selectable-but-unreachable per the Blocked-by note above.

**Current web state**: `.changes-header` renders back-link/heading (routed
page only) + `.changes-scope` (a row of always-visible toggle chips, NOT a
dropdown). This is a documented, ACCEPTABLE deviation for a page-shaped
surface per the research (§5 "Scope control shape" — WRONG BEHAVIOR but
"arguably better for a page... document as an intentional deviation
rather than a fix unless strict 1:1 is mandated"). **Decision for this
ticket: keep the chip row on web** (do not rebuild the dropdown) — but fix
every genuinely-wrong VALUE in the surrounding chrome (missing fold-all
button, missing file-type icons, wrong marker/gutter widths, wrong tints —
all below).

### 2.3 Scope menu

N/A on web per 2.2's decision — chips replace the dropdown. Do not build
`render_scope_menu`'s popover. `DiffScope::ALL` for web purposes is
`[workingTree, branch, turn]` rendered as chips (`DIFF_SCOPE_LABELS`),
plus a `"commit"` value added to the type (see §3) even though no chip
exposes it yet (it is reached only via a commit-diff tab, ticket 27).

### 2.4 Ref selector + ref menu (branch scope)

**Layout** — the `{branch} → {base}` relationship, adapted to a page:

| property | value | source |
|---|---|---|
| branch label | mono, size 11.5, `theme.text_dim` | changes.rs:3894 |
| arrow | `ARROW_RIGHT`, 12px, `theme.text_faint` | changes.rs:3982-3986 |
| base trigger | h `CONTROL_SIZE`, px 6, mono size 11.5 | changes.rs:3896-3948 |
| ref trigger hover wash | `hover_blend(wash(0.0), wash(0.12))` — rest fully transparent, hover target `0.12` (lighter than the scope trigger's `0.14`) | changes.rs:3911-3916 |
| ref menu width | `popover_card`, width **240px** | changes.rs:4057-4058 |
| ref menu list max-height | `flex_col`, gap 2px, `max_h(240)`, `overflow_y_scroll` | changes.rs:4021-4028 |

**Current web state**: `BasePicker` is a plain `<select>` with no
`{branch} →` prefix, no live truncation weighting, no search. Per the
research (§5 "Searchable ref (base) picker" — WRONG BEHAVIOR, "native
select is an acceptable web idiom; if strict parity is wanted, port the
searchable popover"): **decision for this ticket: keep the native
`<select>`** (it is an acceptable idiom and ticket 09/10 own the popover
primitive this would need) but prefix its label with the branch name per
the layout table above, so the "`{branch} → {base}`" relationship is at
least visually present (`.changes-base-label` currently reads "Compare
against" with no branch shown — add the branch name before it).

**Squared-shrink truncation rule** (changes.rs:3886-3895): NOT applicable
to a native `<select>`; do not port this. Keep for reference only if a
future ticket replaces the `<select>` with a real popover.

### 2.5 File header row (`FileHeaderRow`)

**Layout**

| property | value | source |
|---|---|---|
| height | `FILE_HEADER_HEIGHT` = `HEADER_HEIGHT` = `TITLEBAR_HEIGHT` = **38px** | changes.rs:67; surface_chrome.rs:7; proto/layout.rs:45 |
| padding | `px(SPACE_MD)` = 12px horizontal | changes.rs:3425 |
| gap | 8px between chevron/icon/path/counts | changes.rs:3424 |
| top border (row form, not first file) | `border_t_1`, `hairline(0.04)` | changes.rs:3411-3414 |
| background (rest) | `theme.ink(0.025)` | changes.rs:3366-3372 |
| background (hover) | `theme.ink(0.05)` | changes.rs:3366-3372 |
| chevron box | 14×14, icon 13px, `theme.text_muted.opacity(0.7)` | changes.rs:3382-3386 |
| file icon | 14px, `file_icons::icon(...)`, tinted by theme appearance | changes.rs:3434-3441 |
| path text | mono, size 12, `theme.text_dim`, `flex_1 min_w_0 truncate` | changes.rs:3442-3451 |
| BIN tag | size 10, `theme.text_faint`, shown only if `file.binary` | changes.rs:3452-3460 |
| `+N` | mono, size 11, `theme.diff_add` | changes.rs:3461-3470 |
| `−N` | mono, size 11, `theme.diff_del` | changes.rs:3471-3480 |

**Children (in order)**: chevron (animated) → file-type icon → path
(truncating) → `BIN` tag (conditional) → `+N` (conditional: adds>0 or
textual) → `−N` (same condition).

**Interactions**: entire row `cursor_pointer`, click toggles fold. Hover
swaps background.

**Motion**: chevron glyph SWAPS icon asset (no rotation transform in
gpui) with an opacity crossfade `0.25 → 1.0` over the **CHEVRON** catalog
entry (200ms, `ease` curve), armed only while animating (within a 400ms
window of the toggle so a row scrolling back into view doesn't replay).
Formula: `opacity = 0.25 + 0.75 * t` (not a plain lerp call, but same
result). Web: `--rb-motion-chevron` / `--rb-ease-ease`.

**Gaps this component closes** (current `FileHeaderRow` in `diff-view.tsx`):
- **INVENTED** `.diff-file-status` span (`"new"`/`"deleted"`/`"renamed"`/`""`)
  duplicating the notice row — REMOVE. Desktop expresses status only via
  the notice row (§2.8).
- **INVENTED** `.diff-file-index` `#{n}` chip — REMOVE (or keep explicitly
  as a documented web-only addition if the implementer prefers, but the
  default per parity rules is delete).
- **MISSING** file-type icon (14px, tinted by theme appearance) next to
  the chevron — ADD. Use the shared `FileIcon` component ticket 24
  ("Files tree and search") builds from the `file_icons` resolver spec
  (research 09 §3.11 — resolution order, manifest shape, `ASSET_PREFIX`,
  generated icon manifest), so the glyph is exact per-extension, not a
  guess. If ticket 24 has not landed yet when this ticket is implemented,
  land with a placeholder glyph (e.g. a generic file icon for every
  extension) and swap in `FileIcon` once it exists — see §1.
- **WRONG VALUE** `+N -4` rendered as ONE `text-muted`-colored span
  (`fileCounts(file)`) — SPLIT into two spans, `+N` in `theme.diff_add`
  and `−N` in `theme.diff_del`, per the layout table.
- Chevron swap has no transition today — ADD the 200ms CHEVRON opacity
  crossfade (low priority per research but required for full parity;
  implement with reduced-motion fallback to an instant swap).

### 2.6 Sticky file header

**Layout**: `position: absolute`, `top: sticky_header_push_offset(next_header_y)`,
`left: 0`, `w_full`. `sticky_header_push_offset(next_header_y) =
(next_header_y - FILE_HEADER_HEIGHT).min(0.0)` — the sticky header slides
up and out as the NEXT real file header scrolls into its slot underneath,
instead of the two headers overlapping.

**Paint** (`StickyFileHeaderPaint`):

| theme mode | rest_bg | hover_bg | border | frost tint |
|---|---|---|---|---|
| Frosted | `ink(0.025)` | `glass_hover()` | `theme.border` | `theme.bg.opacity(alpha)` — dark **0.40**, light **0.85** |
| Opaque | `flatten(ink(0.025), theme.bg)` | `flatten(element_hover, theme.bg)` | `theme.border` | none |

`STICKY_FILE_HEADER_BLUR` = 16px, wrapped in `frosted(0.0, 16.0, header)`
— a PASS-THROUGH when the resolved surface treatment is opaque. Light
needs far more tint coverage than dark (dark text ghosting through a
blurred wash is much less visible than light text on it — verbatim
rationale, changes.rs:69-73).

**Behavior**: resolves which file's header should float from the
virtualizer's logical scroll position against each file's row-range;
returns nothing during a reset frame (diff swap never shows a stale
sticky header for one frame).

**Gap this component closes**: current web is plain CSS `position: sticky`
on `.diff-file-header` (`app.css:5406-5410`) with `backdrop-filter: blur(16px)`
applied UNCONDITIONALLY, no opaque-mode fallback. Fix: gate the
`backdrop-filter` behind the app's existing surface-preference check
(the same one the rest of the theme system uses — CONTEXT.md "Resolved
surface treatment"); when opaque, flatten the tint into a solid
background instead of blurring. CSS `position: sticky` is an acceptable
substitute for the JS push-offset math (the browser gives you the "next
header pushes the sticky one out" behavior for free when the sticky
element's containing block is the scroll list) — do not port the manual
offset calculation unless `position: sticky` demonstrably fails to match.

### 2.7 Hunk header row (`HunkHeaderRow`)

| property | value |
|---|---|
| height | `HUNK_HEADER_HEIGHT` = **28px** |
| background | `theme.diff_hunk_bg` (bluish-grey wash) |
| text | mono, size 11, `theme.text_faint`, the raw `@@ -a,b +c,d @@ …` string verbatim |
| padding | `px(SPACE_LG)` = 16px horizontal |

Current web `.diff-hunk-header` — verify padding/height/text match; add
`--rb-diff-hunk-bg` background if missing.

### 2.8 Notice row (`NoticeRow`)

| property | value |
|---|---|
| height | `NOTICE_HEIGHT` = **24px** |
| text | size 11, `theme.text_faint`, one of the `file_notices()` strings (§3) |
| padding | `px(SPACE_LG)` = 16px horizontal |

This is the ONLY place file status (new/deleted/renamed/binary/mode-change)
is expressed — see 2.5's "REMOVE `.diff-file-status`" note.

### 2.9 Unified diff line (`UnifiedLineRow`)

**Layout**

| element | value | source |
|---|---|---|
| row height | `DIFF_LINE_HEIGHT` = **21px** fixed (unwrapped) / `min_h(21)` (wrapped) | changes.rs:4307-4314 |
| accent bar | `ACCENT_BAR_WIDTH` = **3px**, self-stretch, filled for +/− only | changes.rs:4323-4329 |
| old gutter | width = `gutter_width(file)` (analytic, §3), size 11, right-aligned, `pr(8)` | changes.rs:4278-4292 |
| new gutter | same width, second column | changes.rs:4338-4345 |
| marker column | `MARKER_WIDTH` = **28px**, centered, size 12 (`DIFF_TEXT_SIZE`) | changes.rs:4346-4357 |
| code viewport | `flex_1 min_w_0 overflow_hidden`, mono, size 12, line-height 21 | changes.rs:4177-4227 |
| code left padding | `UNIFIED_CODE_PADDING_LEFT` = **12px** | changes.rs:91 |
| code right padding (scroll extent only) | `CODE_PADDING_RIGHT` = **24px** | changes.rs:94 |

**Colors by `LineKind`**

| kind | marker glyph | marker color | row bg tint | accent color | number color (own side) |
|---|---|---|---|---|---|
| Add | `"+"` | `add_color` (full) | `add_color` @ **0.055** | `add_color` @ 0.55 | `add_color` @ 0.9 |
| Del | `"−"` | `del_color` (full) | `del_color` @ **0.055** | `del_color` @ 0.55 | `del_color` @ 0.9 |
| Context | `"·"` | `text_faint` @ 0.5 | none | none | — |
| (non-own side, any kind) | — | — | — | — | `text_faint` @ 0.8 |

`add_color`/`del_color` = `theme.diff_add`/`theme.diff_del`.

**Base code-text color**: PLAIN text (no syntax span covering it) is
`theme.text.opacity(0.92)` — the same 0.92 tone unified AND split share.
Only characters covered by an actual syntax span get a role-specific
color.

`LineKind::Meta` (`\ No newline at end of file`) renders italic, size
10.5, `theme.text_faint`, indented past all four columns
(`ACCENT_BAR_WIDTH + 2×gutter + MARKER_WIDTH + 12`), never tinted.

**Interactions**: on hover, a comment-adder hook renders at
`comment_adder_left(side, gutter_px) = ACCENT_BAR_WIDTH + (side==New ?
gutter_px : 0) + (gutter_px - COMMENT_ADDER_SIZE)/2`. **This ticket only
needs to expose the hover state and the anchor position as a prop/callback
(e.g. `onLineHover(path, side, lineNo)` + a `renderAdder?` slot) — do NOT
render an actual "+" button or draft UI; that is ticket 23.**

**Gaps this component closes**:
- **WRONG VALUE** row background tint: unified currently `color-mix(...
  14% ...)` (`app.css:5509,5514`) vs desktop's **5.5%**. Fix to ~5.5%.
- **WRONG VALUE** accent bar: currently full-opacity
  `border-left-color: var(--rb-diff-add)` — apply the 0.55 opacity.
- **MISSING** gutter number color by line kind: currently uniform
  `text-faint` (`app.css:5526-5534`) regardless of kind — add
  `.diff-line-add .diff-line-new` / `.diff-line-del .diff-line-old` color
  rules at 0.9 opacity of the respective diff color; keep the other
  gutter / Context lines at `text_faint` @ 0.8.
- **MISSING** marker glyph color: currently uniform `text-faint`
  (`app.css:5540`) — color per line kind; the glyph itself also differs
  (desktop uses `"·"` for context, web currently uses `" "` in
  `markerFor`) — change the context glyph to `"·"`.
- **WRONG VALUE** marker column width: `UNIFIED_MARKER_WIDTH` is 24px in
  `diff-view.tsx:40` and the CSS grid literal at `app.css:5486` — change
  to **28px** (`MARKER_WIDTH`).
- **WRONG VALUE** gutter number column width: CSS grid uses
  `minmax(28px, max-content)` (floor 28px, not desktop's 36px minimum) and
  never actually applies `gutterWidth(file)` to the number columns (only
  to the code column's padding offset). Apply `gutterWidth(file)` as an
  explicit inline width on both number spans (raise the effective floor
  to 36px).
- **WRONG BEHAVIOR** independent per-file horizontal scroll: desktop keeps
  gutters/markers/accent bar FIXED and scrolls only the code plane, with
  each file tracking its own scroll offset (reset on scope/mode/wrap
  change). Web's `.diff-view { overflow: auto }` scrolls the WHOLE row.
  Give the code cell its own `overflow-x: auto` region distinct from the
  gutter/marker columns, with per-file scroll state (a `Map<path, number>`
  is sufficient; reset entries on scope/layout/wrap change).
- **MISSING** `BodyPad` trailing 8px spacer after each file's last row —
  add an 8px bottom margin to each file's row group.

### 2.10 Split diff row (`SplitLineRow`)

**Layout**

| element | value |
|---|---|
| split marker column | `SPLIT_MARKER_WIDTH` = **18px** |
| split code left padding | `SPLIT_CODE_PADDING_LEFT` = **6px** |
| divider | `SPLIT_DIVIDER_WIDTH` = **1px**, `hairline(0.06)` |
| filler (one-sided row's empty half) | flat `ink(0.03)` wash |

Each half is otherwise the same accent-bar/gutter/marker/code structure as
unified, minus the second gutter.

**Pairing rule** (`splitPairs`, already ported — see §3): a mirrored
context row shares ONE set of syntax runs across both columns. Only the
RIGHT column ever offers a `+` (comment-adder hook, ticket 23) — the left
(old) column is inert since a deletion cannot be edited; a comment
already staged against the old side still renders its card (pushed by the
ROW, not the column) — this ticket needs the row-level layout to
accommodate that (leave room; do not hide the old column when a comment
targets it), even though the card itself is ticket 23's.

**Interactions**: identical hover behavior to unified, restricted to the
right column's anchor for the comment-adder hook.

**Row tint gap**: split currently uses `color-mix(... 8% ...)`
(`app.css:5575,5579`) — standardize to the same ~5.5% as unified.

### 2.11 Code viewport horizontal scroll

Per-file independent horizontal `ScrollHandle` on desktop
(`DiffCodeScroll`/`FileHorizontalState`), reset on scope/mode/wrap change.
Web substitute: ordinary DOM `scrollLeft` state per file (see 2.9's gap
entry) — do not attempt to port the GPUI `ScrollHandle` API shape itself,
only the observable behavior (each file's code plane scrolls
independently; gutters/markers/accent stay put).

### 2.12 Fold / expand motion

**180ms `COLLAPSE`-eased height tween** on a clipped stand-in row when a
file's fold state toggles, capped at `FOLD_TWEEN_MAX_PX` = **2400px** of
built content (`render_file_body_upto`, §3). Today `diff-view.tsx` toggles
a `collapsed` Set synchronously with no transition — this ticket should
add a CSS height transition (`grid-template-rows: 0fr → 1fr` or a JS
height tween driven by the analytic `bodyHeight` sum, §3) capped the same
way so a 50k-line file's fold never builds more than ~2400px of content
mid-animation. Reduced motion: snap instantly (no tween), matching the
desktop's `cx.reduce_motion()` behavior elsewhere in this app.

### 2.13 Collapse-all / expand-all

**MISSING** on web today. Add a `FOLD_VERTICAL`-icon header button
(`.changes-fold-all`) next to Split/Wrap in `.changes-banner-tools` that
collapses every file if any is expanded, else expands every file (mirror
`toggle_collapse_all`'s all-or-nothing rule exactly).

### 2.14 Word-level highlight / syntax highlighting

Desktop highlights the RECONSTRUCTED WHOLE-FILE old/new source
(`excerpt_highlights`/`full_highlights`, background-thread, capped at
`MAX_EXCERPT_SOURCE_LINES` = 200,000 — beyond that, falls back to
`DiffHighlightState::Plain`, no syntax color, rather than allocating a
200k+-slot buffer for what's almost always a lockfile). Web's `tokenize()`
in `diff-view.tsx` highlights each line INDEPENDENTLY via
`highlightCode(line.text, language)`, no surrounding file context. This
ticket does NOT need to port the excerpt/full-file reconstruction — the
research explicitly calls the per-line approach "an acceptable
simplification for v1... reduced fidelity, not a blocking bug." Leave
`tokenize()` as-is; do not regress it.

### 2.15 Stacked full-file diff body (shared with transcript)

`render_file_body_with_syntax` (changes.rs:4600-4641) is NOT part of the
Changes pane's own virtualized list — it draws one `FileDiff` always fully
expanded, unified-only, no fold, no comment rows, `Clipped` code width (no
independent horizontal scroll), shared with the transcript's inline
tool-diff blocks (ticket 19, "Tool groups," owns the call site). This
ticket's job is to make the ROW-LEVEL components (`HunkHeaderRow`,
`NoticeRow`, the unified line row) reusable enough that ticket 19 can
compose them into that stacked, unvirtualized, unified-only view without
duplicating the row markup. Concretely: keep `FileHeaderRow`/`HunkHeaderRow`/
`NoticeRow`/`UnifiedLineRow` as free-standing, prop-driven components (no
internal dependency on the `DiffScroller` virtualizer or the `collapsed`
Set) so ticket 19 can import them directly. Do not build the stacked body
container itself here — that's ticket 19's.

**`BODY_BOTTOM_PAD`** = 8px trailing pad — same constant as 2.9's
`BodyPad` gap fix.

### 2.16 Fold-tween bounded body builder / analytic `body_height`

`render_file_body_upto` (changes.rs:4643-4757) is the renderer behind a
mid-fold-tween stand-in row: it walks notices → hunks → lines (or split
pairs, budgeted via `split_pairs_upto`) and stops the INSTANT the running
height reaches `max_px` (the larger of the tween's two endpoint heights,
capped at `FOLD_TWEEN_MAX_PX` = 2400px) — so a 50k-line file's fold
animation never builds more than ~2400px worth of rows. `body_height`/
`body_height_with` (changes.rs:614-630) are the pure analytic height
sums this needs (§3) — implement those FIRST (pure, testable), then use
them to drive whatever height-tween mechanism 2.12 lands on. Port the
BOUNDING STRATEGY (never build past what the animation's clip can reveal)
even if the concrete implementation differs from gpui's labeled-block
early-exit; a straightforward approach is: compute the target height via
`bodyHeightWith`, then when animating, only render rows up to that
capped pixel budget and let CSS `overflow: hidden` clip the rest.

### 2.17 Change-request badge (`ChangeRequestBadge`)

**Layout** — two size presets:

| property | Sidebar | Composer |
|---|---|---|
| height | 16px | 20px |
| gap | 0 | 5px |
| horizontal padding | 4px | 7px |
| radius | 4px | 6px |
| text size | 10px | 11px |
| icon | none | `PULL_REQUEST`, 11px |

Common: `bg(tone.opacity(0.08))`, hover `bg(tone.opacity(0.16))
text_color(tone)`, rest `text_color(tone.opacity(0.85))`,
`font_weight: MEDIUM`, number in `font_mono` (tabular digits).

**Tones**: Open → `theme.success`; Merged → `theme.code_text`;
Closed → `theme.danger`.

**Interactions**: click → open `summary.url` in a new tab/window
(`window.open`/`<a target="_blank">`), `stopPropagation`. Hover (350ms
delay) → tooltip (§2.18).

**Text**: number = `"#{summary.number}"`; state label `"Open"`/`"Merged"`/`"Closed"`;
title with `\r`/`\n` collapsed to spaces.

**Current web state**: `ChangeRequestBadge` in `change-request-badge.tsx`
already implements the pill and its two size props (`composer`/`sidebar`);
verify the exact px/opacity values against the table above (`.cr-badge`,
`.cr-badge-{tone}`, `.cr-badge-{size}` in `app.css`) and correct any
drift. **This ticket must ALSO verify the badge is actually used at BOTH
desktop call sites** — the sidebar chat row (`components/chat-list.tsx` or
wherever a chat row renders) and the composer footer
(`components/composer-footer.tsx`) — not only inside `.changes-cr-card`.
If either site is missing the badge, add it (reading the chat's
`(deviceId, cwd, branch)` the same way `ChangesBody` does today).

### 2.18 Change-request tooltip

**Layout**: `max_w(320)`, px 9/py 7, gap 3, `rounded(6)`,
`border_1(border_strong)`, bg `glass_overlay()` (frosted) or
`surface_raised` (opaque), `shadow_md`, wrapped in `frosted(6.0, 44.0, …)`.

**Children**: line 1 — `"PR {number} · {state_label}"`, size 11 medium,
colored by tone; line 2 — title, truncated single line, size 11,
`text_muted`.

**Current web state**: `.cr-tooltip` is inline markup, CSS-hover-shown
(`change-request-badge.tsx`) rather than a real tooltip primitive — the
research (§6) explicitly calls this "the appropriate native substitute";
KEEP the CSS-hover approach, just verify the layout numbers above match.

### 2.19 Provider derivation / conversation branch / open-URL only

`change_request_for_chat` resolves the CR shown for one chat: requires a
NON-empty `conversation_branch` (only `chat.source_context.branch`
counts — the legacy scalar `chat.branch` is deliberately never trusted,
since it can't prove a worktree hasn't since switched branches); then
finds a snapshot matching `device_id`, `cwd`, `branch`, and — when the
chat has a `checkout_id` — a matching, non-empty `snapshot.checkout_id`.
Web's `changeRequestForChat` (`state/change-requests-store.ts`) is a
simpler keyed-map lookup (`deviceId\0cwd\0branch`) — functionally
equivalent for current call sites since the key already encodes
device+cwd+branch and the store never stores a target without a checkout
precondition. **No change required** unless a future multi-target call
site needs the stronger re-validation (flag in Comments if you hit one).

`desired_watch_targets`/`watch_params`: one watch target per UNIQUE
`(device_id, cwd, branch)` across non-archived chats with a non-empty
branch, on devices not marked unsupported; `watch_params` OMITS
`targetDeviceId` when the target IS the local device. **Fix the web's
`watchParams` to omit `targetDeviceId` when local**, matching desktop
byte-for-byte (currently always includes it — harmless but not 1:1).

**No create-CR RPC exists on desktop anywhere** — the badge only ever
opens the EXISTING PR's URL via `open_url`. Web's `CreateChangeRequestButton`
+ `changeRequestCreateUrl` (best-effort provider compare-URL builder) is a
DOCUMENTED, INTENTIONAL web-only forward-deviation for a real wire gap —
see §5 "Do not" below. Do not attempt to build a real create-RPC path; do
not remove the existing fallback button either (product has accepted it as
a v1 stand-in).

## 3. Pure logic to port

- **`parsePatch(patch) -> FileDiff[]`** (`lib/diff.ts::parsePatch`,
  changes.rs:432-559 desktop) — already a faithful, field-verified port.
  No changes needed; keep as the source of truth for the rendering fixes
  above.
- **`fileNotices(file) -> string[]`** — order: status notice (`"New file"`/
  `"Deleted file"`/`"Renamed from {old}"`) → `"Binary file — contents not
  shown"` if binary → any parser-collected notices (mode changes).
  Modified files with no binary/notices emit nothing. Verify
  `lib/diff.ts` has this exact function (add if the current file inlines
  the logic differently) since 2.5/2.8's fixes depend on it being callable
  standalone.
- **`visualColumns`/tab-size estimator** — desktop's `DIFF_TAB_SIZE = 4`
  feeds a horizontal-scroll-extent width estimate only (raw text with
  literal `\t` is still drawn verbatim; the tab size only widens the
  scrollable content so a tab-containing line doesn't clip). **Not ported
  today; port it now** since 2.11's independent-scroll fix needs an
  accurate content-width estimate per file. Formula: a `'\t'` advances to
  the next multiple-of-4 column; any other character advances by its
  Unicode display width (wide/CJK count as 2, zero-width combining marks
  count as 0). Test name to port: `horizontal_geometry_counts_tabs_and_unicode_columns`.
- **`excerptSide`/`MAX_EXCERPT_SOURCE_LINES`** — explicitly NOT ported
  (§2.14); the web's per-line `tokenize()` has no file-reconstruction step
  and none is required for this ticket.
- **`gutterWidth(file) -> number`** (`lib/diff.ts::gutterWidth`,
  changes.rs:259-262) — `digits = floor(log10(max(maxLine,1))) + 1;
  width = max(36, digits*6.6 + 8.0 + 6.0)`. Already ported; fix its
  CONSUMPTION (2.9's gap: apply it to the number columns, not just the
  code padding offset).
- **`splitPairs(lines)`/`splitPairsUpto(lines, maxRows)`** — already a
  verified line-for-line port (`lib/diff.ts`). No changes needed.
- **`bodyHeight(file)`/`bodyHeightWith(file, comments, draft, mode)`**
  (changes.rs:614-630) — NEW, port now for 2.16: `bodyHeightWith` = sum of
  every row's analytic height (`NOTICE_HEIGHT`=24, `HUNK_HEADER_HEIGHT`=28,
  `DIFF_LINE_HEIGHT`=21 per line/split-row, a comment card's
  `cardHeight(body)` — ticket 23 supplies this, treat as 0 until then,
  `DRAFT_CARD_HEIGHT`=116 for an open draft — same caveat,
  `BODY_BOTTOM_PAD`=8), computed by calling `bodyRows` (below) and
  reducing — NEVER by measuring a mounted element. `bodyHeight(file)` =
  `bodyHeightWith(file, [], null, "unified")`.
- **`bodyRowCount`/`bodyRows`/`flattenFileRows`** — `flattenFileRows`
  already exists (`lib/diff.ts`); extend it (or add a sibling `bodyRows`)
  to match the desktop shape: notices → (hunk header, then either unified
  lines or split pairs, each followed by any comment cards/draft anchored
  to it — ticket 23 supplies those rows, this ticket just needs to leave
  room in the row-list shape for them) → trailing `BodyPad`. A collapsed
  file contributes ONLY its header row.
- **`resolveDiff`/`diffPhase`/`defaultBaseRef`/`scopeLabel`/`cleanMessage`/
  `parseKey`** — already verified matches; no changes.
- **`applyDiffFrame`** (`ChangesStore` upsert logic) — already matches
  the upsert semantics (accepts either a full list, replace-if-different,
  or one item, upsert-by-checkout-id, no-op if unchanged); no changes.
- **`changeRequestForChat`/`watchParams`** — see §2.19; only `watchParams`
  needs a fix (omit `targetDeviceId` when local).

**Desktop test checklist to port as unit tests** (name the web test after
the desktop name, per the acceptance rule): `parses_files_hunks_and_lines`,
`detects_new_deleted_binary_and_renamed`, `empty_and_garbage_patches_parse_to_nothing`,
`quoted_and_spaced_paths`, `hunk_headers_parse_with_and_without_counts`,
`rows_flatten_to_line_granularity`, `sticky_header_tracks_the_logical_top_row`
(if you port any offset math beyond CSS `position: sticky`),
`split_pairs_align_edits_and_strand_the_rest`, `no_newline_markers_keep_their_edit_paired`,
`split_flattening_pairs_rows_and_keeps_heights_analytic`,
`capped_pairing_agrees_with_the_full_pairing_and_stays_bounded`,
`truncate_caps_lines_and_appends_notice`, `gutters_fit_the_largest_line_number`,
`horizontal_geometry_counts_tabs_and_unicode_columns`,
`horizontal_content_width_compensates_for_local_gutters`,
`horizontal_scroll_and_width_are_independent_per_file`,
`horizontal_scroll_reset_returns_to_origin`, `body_height_is_analytic`,
`diff_resolution_prefers_checkout_id_then_cwd`, `phases`,
`header_label_pluralizes`, `scope_labels_and_clean_messages`,
`base_ref_defaults_to_repo_default_then_main`, `scope_modes_are_wire_stable`,
`diff_frames_replace_lists_and_upsert_singles`. (Skip the ones that are
purely gpui/highlight-pipeline internals with no web analog: `sticky_header_is_pushed_by_the_next_file`,
`sticky_header_uses_the_content_theme_in_dark_and_light`,
`a_split_row_offers_each_column_its_own_anchor`,
`split_rows_carry_the_comments_of_both_columns`,
`a_split_rows_right_column_is_never_a_deletion`,
`diff_scope_menu_keeps_history_as_a_separate_surface`,
`full_diff_highlights_map_old_new_and_context_by_source_line`,
`split_line_runs_use_affected_old_and_new_documents`,
`excerpt_parses_old_and_new_hunks_as_separate_documents`,
`mismatched_full_sources_are_rejected_atomically`,
`editing_staged_diff_comments_preserves_identity_and_cancellation` — these
either require ticket 23's comment model or gpui-specific plumbing this
ticket doesn't touch.)

## 4. Gaps this ticket closes

| item | kind | desktop value | web value (file:line) | fix |
|---|---|---|---|---|
| Right pane never mounts Changes | MISSING | live whenever pane open on "changes" tab | `right-pane.tsx:39` `SURFACES_DISABLED = true` | Owned by ticket 07; this ticket just ensures `ChangesSurface` is correct once unflagged |
| Fold/unfold animation | MISSING | 180ms `COLLAPSE`-eased height tween, capped `FOLD_TWEEN_MAX_PX`=2400px | synchronous `collapsed` Set toggle, no transition | §2.12 |
| Collapse-all / expand-all | MISSING | `FOLD_VERTICAL` header button, `toggle_collapse_all` | no such control | §2.13 |
| Chevron swap animation | MISSING | 200ms `CHEVRON` opacity crossfade | instant glyph swap | §2.5 |
| Searchable ref (base) picker | WRONG BEHAVIOR | dropdown w/ filter input, ranked substring, ↑↓/⏎/Esc | plain `<select>` | Kept as native `<select>` per decision in §2.4; add branch-name prefix |
| Scope control shape | WRONG BEHAVIOR | dropdown menu | toggle-chip row | Kept as chips per decision in §2.2 (documented deviation) |
| Diff-known-but-parsing spinner state | MISSING | separate spinner while background parse runs | N/A — web parses synchronously | No fix needed (different, valid strategy) |
| Scoped-fetch error remapping | WRONG BEHAVIOR / MISSING | friendly remaps for two known substrings, non-warning color | raw watch error only, `scopedError` unsurfaced | §2.1 |
| Watch-error banner semantics | WRONG BEHAVIOR | persists under last-good content, auto-retries every 2s | manual "Retry" button, no auto-retry loop | Add the 2s auto-retry loop to `ChangesStore` |
| Gutter number column width | WRONG VALUE | analytic `gutterWidth(file)`, floor 36px, applied to both number columns | CSS `minmax(28px, max-content)`, `gutterWidth` only offsets code padding | §2.9 |
| Marker column width (unified) | WRONG VALUE | `MARKER_WIDTH` = 28px | `UNIFIED_MARKER_WIDTH` = 24px | §2.9 |
| Row background tint intensity | WRONG VALUE | 0.055 alpha both unified/split | unified 14%, split 8% (both via `color-mix`) | §2.9/§2.10 |
| Accent bar color | WRONG VALUE | `add_color`/`del_color` @ 0.55 | full opacity | §2.9 |
| Gutter number color by line kind | MISSING | own-side gutter tinted 0.9, other/context stay `text_faint` @ 0.8 | uniform `text_faint` | §2.9 |
| Marker glyph color | MISSING | colored per line kind; context glyph `"·"` | uniform `text_faint`; context glyph `" "` | §2.9 |
| File header: status word | INVENTED | none (notice row only) | `.diff-file-status` span | §2.5 remove |
| File header: file index chip | INVENTED | none | `.diff-file-index` `#{n}` | §2.5 remove |
| File header: file-type icon | MISSING | 14px, theme-tinted | none | §2.5 add |
| File header +/− counts coloring | WRONG VALUE | two colored spans | one `text-muted` span | §2.5 |
| Sticky header blur / frost gating | WRONG BEHAVIOR | blur only when frosted; opaque = flat composite | unconditional `backdrop-filter: blur(16px)` | §2.6 |
| Independent per-file horizontal scroll | WRONG BEHAVIOR | gutters fixed, code plane scrolls, per-file offset | whole row scrolls as one block | §2.9/§2.11 |
| Diff mode / wrap persistence | MISSING | `diffSplit`/`diffWrap` persisted immediately | local `useState`, reset on navigation | Persist via ticket 03's settings store |
| Change-request card inside Changes | INVENTED (relative to Changes tab) | none inside `changes.rs`; badge only in sidebar/composer footer | `.changes-cr-card` | Keep as documented page-level addition; ensure badge ALSO appears at the two real desktop call sites (§2.17) |
| Create-change-request affordance | INVENTED | no create flow exists anywhere on desktop | `CreateChangeRequestButton` + `changeRequestCreateUrl` | Keep as documented, intentional v1 fallback — do not remove, do not present as ported |
| `watch_params` always includes `targetDeviceId` | WRONG VALUE (minor) | omits when target is local | always includes | §2.19 |
| BodyPad trailing spacer | MISSING (cosmetic) | 8px after each file's last row | none | §2.9 |
| Stacked full-file diff body reuse | MISSING | shared row components for transcript's inline tool-diff | no shared exports | §2.15 — keep row components prop-driven for ticket 19 |

## 5. Do not

- Do not remove `right-pane.tsx`'s `SURFACES_DISABLED` flag — that is
  ticket 07's.
- Do not build `DiffScope::History` or a History pane/tab here — ticket
  27 owns History as its own right-pane surface, not a `Changes` scope.
- Do not implement the comment adder, draft, or card — leave only the
  hover-hook point described in §2.9/§2.10. Ticket 23 owns
  `comment_ui.rs`'s port.
- Do not remove the web's `CreateChangeRequestButton`/create-URL fallback —
  it has no desktop equivalent but product has accepted it as a
  documented v1 stand-in for a real wire gap. Do not expand it either.
- Do not remove `.changes-cr-card` — it is a documented, intentional
  page-level addition (no 1:1 desktop source), not a bug, but make sure
  the REAL desktop call sites (sidebar row, composer footer) also show
  the badge per §2.17.
- Do not port the desktop's `container_query`/native drag-ghost/canvas
  APIs (`DiffCodeScroll`, `FileHorizontalState`'s exact GPUI shape,
  `ensure_fold_settle`) — reach the same OBSERVABLE behavior with
  `ResizeObserver`/DOM `scrollLeft`/CSS transitions per §2's notes.
- Do not port `excerpt_side`/`full_highlights`'s whole-file syntax
  reconstruction (§2.14) — the current per-line `tokenize()` stays.
- Do not touch `terminal-dock.tsx`, `file-viewer.tsx`, or anything under
  `components/files/` — those are tickets 24-26.

## 6. Acceptance

- [ ] `ChangesSurface`/`ChangesBody` render identically whether reached via
      the routed page or (once ticket 07 unflags it) the right pane.
- [ ] Scope chips show Working tree / Branch changes / Latest turn; a
      `"commit"` `DiffScope` value exists in `lib/diff.ts` even if
      unreachable from the UI yet.
- [ ] Split/Wrap/Fold-all toggles work, persist via ticket 03's settings
      store, and reset all files' horizontal scroll on toggle.
- [ ] File header shows chevron (with 200ms crossfade), file-type icon,
      truncating path, `BIN` tag when binary, separately-colored `+N`/`−N`;
      no `.diff-file-status` or `.diff-file-index` remain.
- [ ] Sticky file header blurs only in frosted mode; flattens to a solid
      tint in opaque mode.
- [ ] Unified and split diff lines show correct column widths (28px
      unified marker, 18px split marker, `gutterWidth`-driven gutter
      widths on both number columns), correct 5.5% row tints, 0.55-opacity
      accent bars, per-kind gutter number and marker colors, `"·"` context
      glyph.
- [ ] Each file's code plane scrolls horizontally independent of its
      gutter/marker columns; scroll resets on scope/layout/wrap change.
- [ ] Fold/unfold animates over 180ms (COLLAPSE curve), capped so a huge
      file never over-builds; reduced motion snaps instantly.
- [ ] Change-request badge renders at both real desktop call sites
      (sidebar chat row, composer footer) in addition to the page's own
      card; tooltip and tone table match §2.17/§2.18.
- [ ] `watchParams` omits `targetDeviceId` when the target is the local
      device.
- [ ] Unit tests: the desktop test names listed in §3 (adapted names OK,
      e.g. `parsesFilesHunksAndLines`) pass in `web/packages/app/tests/diff.test.ts`.
- [ ] Screenshot pair, desktop vs web: (a) Changes tab, working-tree scope,
      one added/one deleted/one renamed file, unified layout, one file
      collapsed; (b) same diff, split layout, one line hovered (adder hook
      visible as a bare hover state, no "+" button yet); (c) branch scope
      with the base picker open; (d) a chat with an open PR showing the
      badge in the sidebar row AND the composer footer.
- [ ] `pnpm -r build` green; package vitest green.
- [ ] No new literal hex/px where a `--rb-*` token exists.

## Comments

**Landed** (branch `wp2/22-changes-pane`, worktree `roboco-wt/22-changes`):

- `lib/diff.ts`: `FileDiff.notices` now carries parser-collected notices only,
  with `fileNotices()` the derived display list (status → binary → parser,
  changes.rs:562) — the only place file status is expressed. `DiffScope` gains
  the `"commit"` value (`DIFF_SCOPE_LABELS.Commit`, `scopeMode` → `"commit"`,
  scope label "N Changed files in this commit", clean "No changes in this
  commit"); `DIFF_SCOPE_CHIPS = [workingTree, branch, turn]` is what the
  toolbar exposes (commit is minted only by a commit-diff tab, ticket 27).
  New pure ports: `visualColumns` (tab stops, wide CJK = 2, combining = 0),
  `horizontalGeometry`, `unifiedContentWidth`/`splitContentWidth` (the
  gutter-compensation extents), `truncateFileLines`, `bodyRows`/
  `bodyRowCount`/`bodyHeight(With)` (analytic, no measurement),
  `upsertDiffFrame` (apply_diff_frame's single-frame arm), and the desktop
  row model — `flattenFiles` emits `fileHeader`/`notice`/`hunkHeader`/
  `line`|`splitLine`/`bodyPad`/`foldingBody` per file, a collapsed file
  contributing only its header. The row/height constants (38/28/24/21/8,
  MARKER 28, SPLIT_MARKER 18, ACCENT 3, FOLD_TWEEN_MAX_PX 2400,
  FOLD_TWEEN_WINDOW 400ms) are exported from here.
- `state/changes-surface.ts` (new): the per-(chat, diff tab) state — scope,
  base, layout, wrap, the fold map, a `scrollEpoch`. Ticket 07's host renders
  a surface's toolbar and body as separate element trees and every diff tab
  keeps its own scope for its life, so this state cannot live in either
  component's React tree; it lives here, read through
  `useSyncExternalStore`. Fold toggles arm the desktop's tween (analytic
  from/to, 400ms settle sweep, chevron-anim window); wrap-on and
  reduced-motion write steady state directly. `layout`/`wrap` persist
  immediately through ticket 03's store (`diffSplit`/`diffWrap`); scope/base
  switch and layout/wrap toggles bump `scrollEpoch`. The body mirrors the
  chat's branch + branch list into the store so the toolbar's base picker
  needs no fetch of its own. `closeSurface` drops a closed tab's state.
- `components/diff-view.tsx`: rows rewritten to the desktop contract.
  FileHeaderRow: 14×14 chevron box with 13px ALT_ARROW glyphs and the 200ms
  CHEVRON opacity crossfade (armed only while the fold animates), a
  placeholder `document` file glyph (ticket 24's `FileIcon` swaps the one
  seam), mono-12 text_dim truncating path, `BIN` at 10 faint, `+N`/`−N` as
  two spans in diff_add/diff_del (condition `adds > 0 || !binary`), ink(0.025)
  rest / ink(0.05) hover. HunkHeaderRow 28px on `--rb-diff-hunk`, mono 11
  faint, 16px pad. NoticeRow 24px/11px/16px pad. Unified rows: 3px accent bar
  at 0.55, both number columns at the analytic `gutterWidth` (36px floor) —
  the inline-width fix — own-side number tinted 0.9, other/context
  text_faint @0.8, 28px marker column with per-kind colors and the `"·"`
  context glyph, 5.5% row tints, meta rows italic 10.5 indented past the
  columns. Split rows: per-half accent/gutter/18px-marker/6px-code-pad
  structure, 1px hairline(0.06) divider, flat ink(0.03) filler, meta
  spanning both halves. The code plane is the only horizontally scrollable
  part of a row: `FilePlaneScroll` holds one offset per file, syncs every
  mounted viewport of that file, and resets on `scrollEpoch`; the content's
  intrinsic width is `columns×1ch + paddings + gutter compensation`. The fold
  tween renders `FoldingBodyRow` — one clipped, height-animated stand-in
  (180ms COLLAPSE/easeOut) whose content is `FileBodyUpto`, bounded to 2400px
  — and a ResizeObserver keeps the virtualizer's positions tracking the
  animation. `useParsedDiff` is the shared parse memo. The comment-adder hook:
  `onLineHover(path, side, lineNo)` + a `renderAdder` slot positioned at the
  anchor, plus the `.diff-line-can-add` bare hover state (ink 0.02) on
  anchorable rows — no button (ticket 23's).
- `routes/changes-page.tsx`: `ChangesSurface` takes the surface id (the
  registry mounts it per diff tab); `ChangesToolbar` is the registry's
  toolbar row — scope chips, the `{branch} →` base picker (native select,
  mono 11.5 label, 12px arrow), the spring, then 24×24 icon toggles
  (splitColumns/wrapText/foldVertical) on the CONTROL contract (latched
  wash(0.14) flat with no hover blend, unlatched 0→0.14 over HOVER_FADE,
  14px icons, wrap carrying the 350ms "Wrap long lines" tooltip via
  `ui/Tooltip`). `ChangesBody`: the watch-error banner (11px warning,
  py4/px12, auto-retries — no button), the scoped-error content replacement
  with the two friendly remaps (faint) vs raw warning @0.85, the header strip
  (38px, gap 10, 16px pad, hairline 0.06: scope label, mono +N/−N,
  "Partial snapshot" chip), the CR card (documented web-only, kept), and the
  preparing state (MatrixSpinner + "Preparing diff…", gap 8). Phase follows
  `diffPhase(activeDiff)` where active = watch-resolved or scoped capture.
- `state/changes-store.ts`: `scopedError` is a separate channel (scoped
  failures no longer clobber the watch banner); `setScope` wires the dead
  `commitSha` param into the fetch key + `commitSha` wire param (a
  commit-pinned pane without its pin never fetches); a context change
  (scope/base/commit) clears the stale capture so the pane shows the
  spinner while a checksum-only refresh keeps the old diff visible — and the
  watch checksum now rides the scoped key, so a working-tree change (or a
  commit) re-captures (ensure_scoped, changes.rs:1983-1984). The watch
  retries itself: a failed/ended stream sets "Diff watch unavailable: …" /
  "Diff stream interrupted — retrying" and re-subscribes after a flat 2s,
  last content staying visible; `resubscribe()` remains for session swaps.
- `state/change-requests-store.ts`: `watchParams(target, localDeviceId)`
  omits `targetDeviceId` when the target is the local device (exported
  pure; the store learns the local id via options/`setLocalDevice` and
  re-arms its watches when it changes — snapshotting the map first: deleting
  and re-adding the same key mid-iteration is an infinite loop). The
  Changes pane and the sidebar's `useChatChangeRequests` both pass the
  paired engine's device id.
- `components/change-request-badge.tsx`: now 1:1 with `pull_request_badge`
  (change_requests.rs:117-169) — the badge shows only the PR glyph (composer,
  11px) and the mono `#N`; the state word never appears in the badge, only
  in the tooltip ("PR #N · State", 11px medium, tone-colored; title 11px
  muted, truncated). Sizes: composer h20/gap5/px7/radius6/11px; sidebar
  h16/0/4/4/10. Tones: open success, merged `code_text` → `--rb-accent`,
  closed danger; bg 8% → 16% on hover, text 0.85 → full. Tooltip: 320 max,
  9/7 pad, gap 3, radius 6, border-strong, raised under the forced-opaque
  resolution (frosted branch gated on `data-surface`), popover shadow, 350ms
  show delay. The two real call sites (chat-list sidebar row,
  composer-footer) were already wired and render the new shape unchanged.
- `components/surface-registry.tsx` + `state/right-pane.ts`: the diff entry's
  toolbar is `ChangesToolbar` (the host's 38px row, controls from the shared
  store) and `render` passes the surface id; `closeSurface` disposes the
  closed tab's surface state (one line).
- `state/hooks.ts`: `useEngineStatus`/`useWatchSnapshot` pass a server
  snapshot to `useSyncExternalStore` (additive, client behavior unchanged) —
  required to server-render the surface trees in the render smoke.
- `styles/app.css`: the `.changes-*`, `.diff-*`, `.cr-*` blocks reworked to
  the ticket's numbers (all values token-driven: 5.5% tints via color-mix on
  `--rb-diff-add`/`--rb-diff-delete`, accent 0.55, gutter/marker widths,
  28px unified / 18px split markers, 0.92 base code text, sticky header flat
  composite under opaque + blur 16 gated on `html[data-surface="frosted"]`,
  banner/toolbar geometry, badge/tooltip values, 350ms tooltip delay); the
  fold tween (180ms collapse/easeOut), chevron keyframes (200ms), hidden
  code-plane scrollbars, phone rules (gutters/markers collapse, code wraps,
  toolbar/banner wrap), and reduced-motion snapping for every new tween.

**Deviations / judgment calls:**

- Scope chips and the native `<select>` base picker stay (the ticket's
  documented decisions, §2.2/§2.4); the `{branch} →` prefix makes the
  relationship visible.
- `CreateChangeRequestButton`/`changeRequestCreateUrl`: ticket 04 already
  deleted both as INVENTED (the ticket's "do not remove" predates that
  deletion); not re-added, `.changes-cr-card` kept as the documented
  page-level addition.
- Sticky header: CSS `position: sticky` (the ticket's sanctioned
  substitute). Because the header is permanently sticky in the web model,
  the sticky presentation's bottom border renders at all times and the
  row-form's top hairline (0.04, non-first file) is not drawn — the
  virtualizer's slice remounts make `:nth-file` selectors unreliable.
  Opaque-mode rest/hover are the flat composites (ink 0.025 / ink 0.05 over
  `--rb-bg`), the row-form values.
- Frosted branches exist behind `html[data-surface="frosted"]`; the web
  resolves opaque (product decision), so the flat composite is the live
  path — the day the policy flips, blur 16 (header) / glass overlay
  (tooltip) engage.
- The file-type glyph is a generic `document` placeholder in one seam
  (`FileGlyph`) until ticket 24's `FileIcon` lands its per-extension
  manifest, per §1.
- `ChangesStore`'s branch-scope auto-pick takes the engine's first branch
  (the repo default `ListBranches` puts first) rather than running
  `defaultBaseRef`'s full fallback chain — the pure function is ported and
  tested; wiring the fallback needs the chat's branch inside the store's
  target, left for ticket 23/27's store touches. Only matters for repos
  with no origin/HEAD, where the engine's first entry IS the current
  branch.
- `estimateRowHeight` stays analytic-exact per kind (the ticket blessed the
  existing estimate); it errs high only for wrapped rows.
- The routed Changes page has been gone since ticket 04 — the pane is the
  only host, so the "identical via routed page or pane" acceptance holds by
  construction; `ChangesSurface` is the single body.
- Files touched beyond the ticket's table (each a one-line additive, with
  reason): `surface-registry.tsx` (the toolbar seam the ticket's §2.2
  deferred to "ticket 22's controls"), `state/right-pane.ts` (dispose the
  closed tab's surface state), `state/hooks.ts` (SSR snapshot arg),
  `chat-list.tsx` (pass the local device id to the CR watches).

**Verification:** `pnpm -r build` green; `pnpm --filter @roboco/app test`
686/686 across 47 files — `tests/diff.test.ts` rewritten to the ported
desktop names (parsesFilesHunksAndLines, detectsNewDeletedBinaryAndRenamed,
emptyAndGarbagePatchesParseToNothing, quotedAndSpacedPaths,
hunkHeadersParseWithAndWithoutCounts, rowsFlattenToLineGranularity,
splitPairsAlignEditsAndStrandTheRest, noNewlineMarkersKeepTheirEditPaired,
splitFlatteningPairsRowsAndKeepsHeightsAnalytic,
cappedPairingAgreesWithTheFullPairingAndStaysBounded,
truncateCapsLinesAndAppendsNotice, guttersFitTheLargestLineNumber,
horizontalGeometryCountsTabsAndUnicodeColumns,
horizontalContentWidthCompensatesForLocalGutters,
horizontalScrollAndWidthAreIndependentPerFile,
horizontalScrollResetReturnsToOrigin, bodyHeightIsAnalytic,
diffResolutionPrefersCheckoutIdThenCwd, phases, headerLabelPluralizes,
scopeLabelsAndCleanMessages, baseRefDefaultsToRepoDefaultThenMain,
scopeModesAreWireStable, diffFramesReplaceListsAndUpsertSingles) plus a
`tests/changes-surface.test.ts` render smoke (the fixture cannot mint a
Diffs tab, so the two trees are server-rendered to exercise the mount path)
and `watchParams` tests in the change-requests suite.

Browser (web_smoke, per the runbook): the fixture's tempdir has no git
checkout — the git-gated Diffs row is unreachable as shipped (ticket 07's
documented limitation). Worked around for verification only, with no
fixture/code changes: the tempdir was turned into a real git repo (added +
deleted + rename-detected working changes) and the chat's branch stamped
through the engine's own `Mutate` RPC (`setChatBranch`) from the page, which
makes the picker's git gate pass. With a live Changes tab: boot check clean
(no error boundary); computed-style audit — add-row bg exactly
diff_add @0.055, accent 3px @0.55, gutter columns 36px (the fixed inline
width), marker 28px in full add color, code text @0.92, code plane
`overflow-x: auto` with the row chrome fixed, file header 38px/sticky/flat
composite with `backdrop-filter: none` under `data-surface="opaque"`, hunk
28px on the diff-hunk wash, notice 24px, bodyPad 8px, toolbar 38px/gap 4,
tool buttons 24×24/radius 6/text_muted @0.7, banner 38px/gap 10/px 16,
chevron 14×14 @0.7, file glyph present. Behavior: fold toggle arms the
folding stand-in (`.diff-folding` + `.diff-chevron-anim`, 0.18s transition)
and settles to steady rows; fold-all collapses all then expands all;
per-file horizontal scroll — a long line scrolls its file's plane
(scrollWidth 1391 vs client 129), the same file's other rows follow, other
files stay 0, and a wrap toggle resets every offset to 0; `diffSplit`/
`diffWrap` persist to `localStorage`; split layout pairs rows with fillers
and the right-half hover registers the bare state (ink 0.02 wash); phone
width (390px) stacks the shell, collapses the gutters, wraps the code, and
wraps the toolbar to 66px.

**Screenshots** (web halves, `.scratch/web-parity/shots/22/`):
`web-a-changes-unified-one-collapsed.png` (working tree; added new_module.rs,
deleted notes.md, renamed src_main.rs → src/bin/main.rs; unified; notes.md
collapsed), `web-b-changes-split-line-hovered.png` (split; a right-half line
hovered — the bare adder-hook state), `web-c-branch-scope-base-picker.png`
(branch scope; the `{branch} → base` picker row), `web-boot-check.png`.

**Documented skips:**

- **Desktop halves of all pairs**: no desktop client is running and driving
  it unattended is not possible (ticket 07's precedent — `shot.ps1` steals
  foreground focus).
- **(d) open-PR badge in sidebar + composer footer**: the fixture repo has
  no remote, so the engine resolves no change request and neither call site
  renders a badge to capture. The badge's values, tones, and both call sites
  are code-verified (`chat-list.tsx` renders `size="sidebar"`,
  `composer-footer.tsx` the composer preset); unit tests cover the tone
  table and `watchParams`.
- **(c) "base picker open"**: a native `<select>`'s dropdown is OS-rendered
  and not capturable via CDP; captured closed with the branch → base
  relationship and both options present.
- The live-row workaround above is a verification harness only — nothing in
  the fixture or the app changed to make it possible beyond what any
  engine-authorized client can do over the wire.
