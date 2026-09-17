# 16 — Queue panel

**What to build:** While a chat is busy (a live run in progress) and the user
keeps typing, messages they send now land in a **queue** instead of firing a
new run. A frosted tray appears docked directly behind the composer pill,
listing each queued message as a row: its text, up to two attachment
thumbnails, and Send-now / Edit / Discard actions. Rows can be dragged to
reorder. Editing a row moves its text into the composer; saving commits the
edit back to the row (with a live 60-second lease so two devices can't stomp
each other), canceling reverts it. After this ticket, typing while the chat
is working actually queues (today it silently fires a second run), and the
tray's geometry, motion, and copy match the desktop pixel-for-pixel.

**Blocked by:** 13 (Composer core)

**Status:** ready-for-agent

**Research:** `../../web-client/research/06-queue-attachments-comments.md`
§3.1, §3.2, §3.3 (context only — see §5 below), §4, §5 rows for
`Composer never queues while busy`, `Queue edit lease is never renewed`,
`Row shows a persistent "Steer now" button`, `Row shows a "Hold" chip`,
`Delivery-gate text replaces the row body`, `No attachment thumbnails in
queue rows`, `No responsive compact/expanded primary button`, `No
latest-row keyboard-shortcut hint`, `Row background/border is a visible
card`, `Panel geometry doesn't match`, `Queue-wheel isolation missing`,
`Editing UI lives outside the row`, `Editing a queued row drops its
attachments from view`, `No host-capability gating on queue actions`, `No
targetDeviceId routing on queue RPCs`, `Icons available but unused`. Also
`04-composer.md` §3.0 (`QUEUE_SIDE_INSET`, `QUEUE_COMPOSER_OVERLAP`) and
§3.11 gap note 7 (`queue_preview_limit`).

**Desktop reference (for lookups only):** `crates/ui/src/queue.rs::queue_panel_surface`
(:223), `::queue_row` (:382), `Composer::send` (`composer.rs:6032`),
`crates/doc/src/queue.rs` (CRDT model).

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/components/queue-panel.tsx` | edit (near-total rewrite) | `QueuePanel`, `QueueRow`, `QueueThumbnail`, `QueueActionButton`, `QueueActionTooltip` |
| `web/packages/app/src/state/queue-store.ts` | edit | wire `renewEdit()` into a caller-driven interval hook, no shape changes to `QueueSnapshot`/`QueueEditLeaseState` needed |
| `web/packages/app/src/lib/queue-actions.ts` | edit | delete `steerQueuedMessageNow` call site usage from the UI (keep the function — it's still valid engine API, just unused by web UI per settled decision); no signature changes |
| `web/packages/app/src/components/composer.tsx` | edit (narrow) | ONLY the `submit()` branch that must call `queueMessage` when `isWorking && hasContent && !supportsSteering`; ONLY the `activate_latest_queued`-on-empty-modified-submit wiring. Do not touch layout, pickers, send-button chrome — ticket 13 owns those. |
| `web/packages/app/src/routes/chat-page.tsx` | edit (narrow) | ONLY the 20s lease-renewal interval effect (`while (editingRow !== null)`), clearing `editingRow` like the desktop's expiry path on a `"lost"`/`"missing"` renewal outcome. Do not touch the rest of the route. |
| `web/packages/app/src/styles/app.css` | edit | `.queue-panel` (1470-1479), `.queue-panel-list` (1481-1486), `.queue-row*` (1488-1601), phone overrides (5770-5798) |

## 1. Context a fresh session needs

- The queue is a **tray docked directly behind the composer pill** — not a
  separate route, not a modal. It only exists while `chat-page.tsx` has a
  `QueueStore` (created once per open chat, `chat-page.tsx:114-119`) and
  mounts `<QueuePanel>` as a sibling immediately above `<Composer>`
  (`chat-page.tsx:253-269`), both wrapped in `QueueStoreProvider`.
- `QueueStore` (`state/queue-store.ts`) already owns: the `WatchQueue`
  subscription (`#subscribe`, watches `methods.WATCH_QUEUE`), row identity
  stability (`applyRows`/`shallowSame` so unrelated fields don't force a
  remount), the local edit-lease record (`#editLease`), and thin RPC
  wrappers (`beginEdit`, `renewEdit`, `finishEdit`, `sendNow`, `steerNow`,
  `move`, `remove`). None of this needs to change shape — this ticket wires
  the missing call sites and rebuilds the two React components that render
  from it.
- `lib/queue-actions.ts` already has typed wrappers for every RPC verb
  (`queueMessage`, `updateQueuedMessage`, `moveQueuedMessage`,
  `removeQueuedMessage`, `sendQueuedMessageNow`, `steerQueuedMessageNow`,
  `beginQueuedMessageEdit`, `renewQueuedMessageEdit`,
  `finishQueuedMessageEdit`, `mintEditorInstanceId`). **`queueMessage` has
  zero call sites in the app today** — this is the single most important
  fix in this ticket (see §5 first row): without it, typing while the chat
  works silently fires a fresh `Run` against an already-busy chat
  (`composer.tsx:301-330`, the `!hasContent` guard falls through because
  `hasContent` is true).
- The composer's own send-path decision (`composer.tsx:274-330`) already
  distinguishes Stop (empty + working) and Steer (working + text +
  `supportsSteering`). The missing branch is: **working + content (text OR
  attachments) + harness that does NOT support steering → Queue.** This is
  the desktop's `send_button_mode` rule (`composer.rs:573-579`): "live run +
  non-empty content → Queue," unconditionally, with
  `holdForTurnEnd: true` (there is no user-facing toggle for this — see §3
  below).
- Per `spec.md` decision 3 (settled, do not reopen): **"Steer-now does not
  exist on web. The composer offers Send / Queue / Stop exactly as the
  desktop does. The queue panel offers Send now only."** The engine RPC
  `STEER_QUEUED_MESSAGE_NOW` still exists and `lib/queue-actions.ts`'s
  `steerQueuedMessageNow` wrapper is harmless to keep, but no button in the
  UI may call it.
- Attachment thumbnails inside a queue row are NOT the same component as
  the composer's staged-attachment strip (ticket 17 owns that). They are a
  small 40×28 read-only preview of an attachment already on a queued
  message, loaded through the SAME cache the transcript uses
  (`state/attachment-cache.ts` + `lib/attachments.ts::readAttachmentImage`,
  both already exist and need no changes for this ticket — ticket 17 adds
  LRU/budget eviction to that cache, this ticket only reads from it).
- Vocabulary: "queue" not "steer-queue"; a queued item is a "row"; the RPC
  is `QueueMessage`, never "SteerMessage."
- Tokens available already: `--rb-radius-panel` (16), `--rb-radius-control`
  (6-ish, check ticket 02 for the actual control radius value used
  elsewhere), `--rb-motion-fade-quick` + `--rb-ease-ease` (150ms
  `cubic-bezier(0.25,0.1,0.25,1)`, already used at `app.css:2099`),
  `--rb-motion-tab-slide` + `--rb-ease-ease-out` (150ms
  `cubic-bezier(0,0,0.58,1)`, already used at `app.css:503`). No
  `input_glass_bg`/frost token exists yet for the panel background — if
  ticket 02 has not added one by the time this ticket runs, fall back to
  `rgb(var(--rb-raised) / 0.92)` + `backdrop-filter: blur(16px)` and note
  the substitution in this ticket's Comments section.

## 2. Spec

### 2.1 Queue panel surface (`queue_panel_surface`, `queue.rs:223`)

**Layout**

| Property | Value | Source |
| --- | --- | --- |
| Corner radius | `rounded_t` only, `PANEL_RADIUS = 16.0` (top corners; bottom is square/overlapped) | `queue.rs:81,226` |
| Background | `theme.input_glass_bg()` — frost-aware tinted input surface; opaque fallback flattens `input_bg` over `bg` when surface treatment is not Frosted | `queue.rs:227`, `theme.rs:968` |
| Border | 1px `theme.border` | `queue.rs:229` |
| Shadow | `shadow_lg()` only when `!theme.is_frost()` (opaque surface mode) | `queue.rs:230` |
| Bottom padding | `QUEUE_COMPOSER_OVERLAP = 18.0` — reserved so only the portion tucked behind the composer gets padding; visible rows sit flush with the tray edge | `composer.rs:77`, `queue.rs:233` |
| Blur / frost | `crate::frost::frosted(16.0 /*radius*/, 16.0 /*blur*/, panel)` wraps the whole panel | `queue.rs:376`, `frost.rs:27` |
| Outer horizontal margin | `QUEUE_SIDE_INSET = 16.0` (narrower than the composer, "emerging from behind it") | `composer.rs:74,7462` |
| Overlap with composer | `margin-bottom: -(Theme::SPACE_SM(8) + QUEUE_COMPOSER_OVERLAP(18)) = -26px`, canceling the column gap and tucking the panel 1 layout pixel behind the composer paint order | `composer.rs:7463-7466` |
| Max row-list height | `window.viewport_size().height * 0.3` (viewport-relative, not row-count-relative) | `queue.rs:320` |
| Scroll edge fade | top+bottom, `Theme::TRANSCRIPT_FADE_BAND`, outset by `QUEUE_TEXT_SIZE` (baseline+font-size sampling) | `queue.rs:242-260` |

Implementation note for the web: the current `.queue-panel` is a full-width
block with square corners, `border-top` only, `background:
var(--rb-shell)` + its own `backdrop-filter`, and NO negative-margin overlap
with the composer (`app.css:1470-1479`). All of that must change per the
table above. `QueuePanel` must render inside the SAME flex column as
`Composer` (already true, `chat-page.tsx:253-269`) with:

```css
.queue-panel {
  margin: 0 16px -26px; /* QUEUE_SIDE_INSET, -(SPACE_SM + QUEUE_COMPOSER_OVERLAP) */
  padding-bottom: 18px; /* QUEUE_COMPOSER_OVERLAP */
  border-radius: 16px 16px 0 0; /* PANEL_RADIUS, top only */
  border: 1px solid rgb(var(--rb-hairline) / 1);
  background: rgb(var(--rb-raised) / 0.92); /* fallback, see §1 note on input_glass_bg */
  backdrop-filter: blur(16px);
}
.queue-panel-list {
  max-height: 30vh; /* window.viewport_size().height * 0.3 */
  overflow-y: auto;
  overscroll-behavior: contain; /* closes "Queue-wheel isolation missing", see §5 */
  mask-image: linear-gradient(to bottom, transparent 0, black 24px, black calc(100% - 24px), transparent 100%);
}
```

**Interactions**
- Mouse wheel over the panel is captured and never bleeds into the
  transcript underneath, even at scroll boundaries (asserted by
  `queue_wheel_does_not_scroll_the_transcript_even_at_its_boundaries`,
  `queue.rs:2004`). Web equivalent: `overscroll-behavior: contain` on
  `.queue-panel-list` (native `overflow-y:auto` scroll chaining bleeds by
  default otherwise).
- The whole surface is a drag/drop target for a `QueueDragPayload`, scoped
  by chat id — drops from a different chat's queue are ignored. Web
  equivalent: HTML5 drag events already scope by DOM subtree per chat page
  mount; no extra chat-id check is needed since only one `QueuePanel` for
  one chat exists in the DOM at a time.
- Releasing the pointer outside the panel mid-drag must not leave the
  source row as a stale gap (`cancel_queue_drag`, GPUI's `on_mouse_up_out`).
  Web: on `dragend` (fires even without a matching `drop`), clear any local
  `dragOver` state.

**Motion**

| What animates | Trigger | Spec | From → to |
| --- | --- | --- | --- |
| Whole panel enter/exit | Queue becomes non-empty / empty | `motion::fade_quick` = `FADE_QUICK` (150ms, `EASE` = `cubic-bezier(0.25,0.1,0.25,1)`) — CSS: `var(--rb-motion-fade-quick)` / `var(--rb-ease-ease)` | opacity 0→1 (`composer.rs:7459-7467`) |

Web: add an enter/exit transition on `.queue-panel` (`opacity`,
`var(--rb-motion-fade-quick)` `var(--rb-ease-ease)`) — today the panel just
appears/disappears with `rows.length === 0` returning `null`
(`queue-panel.tsx:170-172`), no transition at all.

### 2.2 Queue row (`Composer::queue_row`, `queue.rs:382`)

**Layout**

| Property | Value | Source |
| --- | --- | --- |
| Height | `ROW_HEIGHT = 36.0` | `queue.rs:75` |
| Row gap | `ROW_GAP = 0.0` (rows are flush; only hover wash separates them) | `queue.rs:77` |
| Horizontal padding | `ROW_PAD_X = 8.0` | `queue.rs:79` |
| Radius | `ROW_RADIUS = 8.0` | `queue.rs:80` |
| Row gap (internal flex gap between marker/text/actions) | `4.0` when `queue_preview_limit()==1` (narrow composer), else `8.0` | `queue.rs:508-512` |
| Background (idle) | none (transparent; the row is not a card) | `queue.rs:513` |
| Background (hover, not editing/removing) | `crate::theme::ink(0.04)` | `queue.rs:515-517` |
| Background (being edited) | `crate::theme::ink(0.06)` | `queue.rs:514` |
| Opacity (being removed) | `0.55` | `queue.rs:518` |
| Text size / line height / color | `QUEUE_TEXT_SIZE = 12.5px` / `16px` / `theme.text.opacity(0.9)` | `queue.rs:76,561-564` |
| Attachment-summary line (2nd line, when not image-only) | `11px` / `13px` / `theme.text_muted` | `queue.rs:566-575` |
| Drag marker | `14×22`, rounded 4, `QUEUE_DRAG_HANDLE` icon at `QUEUE_ICON_SIZE = 13.0`, `theme.text_muted.opacity(0.5)`; `opacity(0.35)` + arrow cursor when interaction-blocked | `queue.rs:481-498` |
| Attachment thumbnail | `40×28` frame, `5px` radius, `1px` border `hairline(0.1)`, `ink(0.035)` bg; inner `img` `38×26`, radius `4px`, `object-fit: cover` | `queue.rs:841-874` |
| "+N more" overflow chip | `28×28`, radius `5px`, `ink(0.06)` bg, `11px` text `theme.text_muted`, `aria-label="{N} more attachments; edit message to view all"` | `queue.rs:584-602` |
| Trailing action button (edit/discard) | `28×28`, radius `5px`, `opacity(0.72)` idle → `1.0` + `ink(0.07)` bg on hover; disabled → `Arrow` cursor + `opacity(0.45)` | `queue.rs:908-959` |
| Trailing action button — icon idle color | glyph `text_color(theme.text_muted.opacity(0.8))` (group-hover brightens it to plain `theme.text`) | `queue.rs:956` |
| Trailing action button — keyboard focus ring | `focus_visible`: `bg(accent.opacity(0.18))` + `text_color(accent)` | `queue.rs:936` |
| Primary action button (Send now) | width `28` (icon-only, `queue_preview_limit()==1`) or `72` (icon+label / text), height `28`, radius `5px`, text `11.5px` `theme.text_muted` | `queue.rs:979-1033` |
| Primary action button — keyboard focus ring | `focus_visible`: `bg(accent.opacity(0.18))` + `text_color(accent)` (same ring recipe as the trailing action buttons) | `queue.rs:998` |

`queue_preview_limit()` (composer state, `composer.rs`, gap note 7 in
`04-composer.md`): **1 preview when the composer's available width is below
520px, else 2.** This drives both the row's internal gap (4 vs 8) and the
primary button's width (28 icon-only vs 72 icon+label). Read the
composer's own measured width (already tracked for the compact/expanded
flip in ticket 13) to compute this; do not hard-code `2`.

**Children (in order, not-editing state)**
1. Drag marker (draggable when `!interaction_blocked`)
2. Attachment thumbnails (0..`queue_preview_limit()`) + optional "+N" chip
3. Text column: single-line summary (truncated) + optional attachment-summary line
4. Trailing action cluster: discard (trash icon) → edit (pen icon) → primary (Send now)

**Children (in order, editing state)**
1. Empty 14px spacer (keeps text alignment; drag glyph removed)
2. Text column replaced by "Editing in composer" (or "Saving…" while `queue_edit_finishing`), `theme.text_muted`
3. Trailing cluster: Save (`QUEUE_CHECK` icon, tooltip "Save to queue") → Cancel (`QUEUE_CLOSE` icon, tooltip "Cancel")

Icons already vendored in `@roboco/icons` and ready to use (see §5 "Icons
available but unused"): `queueDragHandle`, `pen`, `trashBinMinimalistic`,
`queueSend`, `queueCheck`, `queueClose`, `closeCircle`.

**States**

| State | Condition | What changes |
| --- | --- | --- |
| `delivery_blocked` | `item.delivery_gate.is_some()` | Text replaced by `"Editing on {owner_device_id}"` (Editing gate) or `"Needs review"` (ReviewRequired gate); primary action becomes unavailable |
| `being_removed` | id ∈ `self.queue_removing` | Opacity 0.55, interactions blocked, discard label → "Removing…" |
| `being_edited` | `editing.as_deref() == Some(id)` | Row switches to the editing children set (above); background `ink(0.06)` |
| `interaction_blocked` | `delivery_blocked \|\| being_removed` | Drag disabled, edit/discard disabled, primary computed unavailable |
| Primary action available | `!interaction_blocked && host_supports_actions` (`MESSAGE_QUEUE_ACTIONS_V1`) | Button enabled; else tooltip becomes "Waiting for provider capabilities", `opacity(0.45)` |
| Latest-row shortcut reveal | `queue_latest_shortcut_visible(ix, count, show_latest_shortcut, action_available)` — only the LAST row, only when `show_latest_shortcut` (composer non-empty-hint suppressed) and action available | Primary button shows `⌘↵`/`⌃↵` (compact) or the platform combo label (`modifier_send_label`) instead of the icon/"Send now" text |

This is a behavior change from today's web row, which keeps the raw text
always visible and adds a SEPARATE chip for lock/review state
(`queue-panel.tsx:266-268`, `.queue-row-chip-locked`/`-review`). Per §5
below, match the desktop: **replace the row's text with the gate string**,
do not keep a chip.

**Interactions**
- **Click edit (pen):** `begin_queue_edit(id)` → `BEGIN_QUEUED_MESSAGE_EDIT`
  RPC (host-routed). On `"acquired"`, loads the row's attachments/appshots
  via `read_attachment_image`, moves them into
  `composer.attachments`/`composer.appshots` for the current chat key,
  stashes the composer's PRIOR draft text+attachments+appshots into
  `queue_edit_draft` (restored verbatim on cancel/commit), sets the input
  text, and starts a 20s renewal heartbeat (`start_queue_edit_renewal`). On
  `"locked"` → failure toast "That queued message is being edited on
  another device". On any other outcome/error → failure toast, no state
  change.
- **Click discard (trash):** `remove_queued(id)` → `REMOVE_QUEUED_MESSAGE`
  (host-routed, gated on `MESSAGE_QUEUE_ACTIONS_V1`); row optimistically
  marked `being_removed`; on ack, spliced out of `state.queue`; on a
  `removed:false`/error reply, re-syncs the queue from the doc and shows
  "That message had already left the queue" / "Couldn't remove the
  message".
- **Click primary (Send now):** `activate_queued_primary` →
  `send_queued_now(id)` → `SEND_QUEUED_MESSAGE_NOW`. NOT optimistic: the
  row only leaves the queue once the host acknowledges `sent:true`; a
  failed dispatch puts the row back at index 0 on the host
  (`doc_host.rs:1391-1393`), never demoted to the tail.
- **Drag:** the ENTIRE row (not just the marker) is the drag source when
  `!being_edited && !interaction_blocked` (`on_drag`, payload `{chat,
  from: ix}`); the drag ghost is an invisible `QueueGhost` (`gpui::Empty`)
  — the REAL row slides between slots instead of a pointer-following
  tooltip. Today's web already drags the whole row (`queue-panel.tsx:223`)
  — keep that, but add the slide tween (§2.4 below) instead of the instant
  reflow the code comment currently documents as an "approximation."
- **Drop:** `queue_drop_index(panel_y, count)` maps the pointer's y
  (relative to the panel, minus `PANEL_PAD_TOP`) to a clamped row slot via
  `drop_index(_, ROW_SLOT, count)`; on drop, `move_queued(from, to)`
  optimistically splices `state.queue` locally, then fires
  `MOVE_QUEUED_MESSAGE`.
- **Escape (while editing this row):** bound on the composer container,
  not the input, so mention/slash popups (which also bind Escape) win
  first; if neither is open, `cancel_queue_edit()` fires and stops
  propagation (`composer.rs:7470-7483`).
- **Save/Cancel (editing state):** Save → `commit_queue_edit()` → if
  composer text+staged attachments+appshots are ALL empty,
  `finish_queue_edit("discard", ...)`; else `finish_queue_edit("commit",
  Some(text))` — re-uploads any newly staged attachments/appshots, folds
  appshot XML back into the text, then calls `FINISH_QUEUED_MESSAGE_EDIT`
  with `expectedTextHash` for optimistic-concurrency (a text drift on the
  host returns `"conflict"`, kept locally with a toast rather than
  silently overwritten). Cancel → `finish_queue_edit("cancel", None)`, row
  reverts to its pre-edit text, no attachments re-upload.

  This is a behavior change from today's web, which puts the ONLY editing
  affordance ("Cancel edit") in a separate `.chat-edit-toolbar` bar below
  the whole composer (`chat-page.tsx:274-280`) and has no inline Save —
  saving today happens by pressing the composer's own Send button. Per §5
  below, move Save/Cancel back INLINE in the row itself, with the row's own
  icon buttons (`queueCheck`/`queueClose`), distinct from "send/run." The
  `.chat-edit-toolbar` bar and its "Cancel edit" button should be removed
  once the inline Cancel exists (composer.tsx/chat-page.tsx are ticket 13's
  file, but the removal of the toolbar's sole child is a direct
  consequence of this ticket's row spec — coordinate in Comments if ticket
  13 lands the toolbar first).
- **Hover on trailing action buttons:** tooltip after `350ms` show-delay,
  in a `QueueActionTooltip` (`queue.rs:54-72`): `8×5` padding, `6px`
  radius, `1px` border `theme.border`, bg `theme.surface_overlay`, text
  `10.5px` `theme.text_muted`.

**Motion**

| What animates | Trigger | Spec | From → to | Reduced motion |
| --- | --- | --- | --- | --- |
| Row reorder slide | Any drag-over index change (own row AND every displaced row in its path) | `motion::TAB_SLIDE` = 150ms `EASE_OUT` (`cubic-bezier(0,0,0.58,1)`) — CSS: `var(--rb-motion-tab-slide)` / `var(--rb-ease-ease-out)` | `relative().top(px(lerp(start, target, t)))`, per-row offset = `slide_offset(ix, from, over) * ROW_SLOT` (`ROW_SLOT = ROW_HEIGHT + ROW_GAP = 36.0`) | Snaps directly to `target` with no tween (`queue.rs:650-656`) |

**Text (verbatim)**
- `"Editing on {owner_device_id}"`, `"Needs review"`, `"Editing in
  composer"`, `"Saving…"`, `"Removing…"`, `"Send now"`, `"Send now
  (interrupt)"` (tooltip), `"Edit"`, `"Remove"`, `"Save to queue"`,
  `"Cancel"`, `"Waiting for provider capabilities"`, `"{label.len()} more
  attachments; edit message to view all"`.
- `one_line(text)`: collapses a multi-line message to a single visual line
  by `split_whitespace().join(" ")` — every newline and run of whitespace
  collapses to one space (`queue.rs:174-177`). The web already has this as
  `collapseWhitespace` (`queue-panel.tsx:314-319`) — keep it, just rename
  to match the desktop's `one_line` naming in a comment for future
  grepping.
- Attachment-only rows show `crate::attachments::ATTACHMENT_ONLY_TEXT` =
  `"See the attached image(s)."` as both the row title and (via
  `queue_visible_text`) the fallback when text is empty but attachments
  exist. (Already correct on web, `queue-panel.tsx:216`.)
- Attachment labels (`queue_attachment_labels`, `queue.rs:208-221`): each
  path becomes either `"{app_name} Appshot"` (when it matches a captured
  Appshot presentation) or its bare filename; multiple labels join with
  `" · "`, prefixed `"{N} attachments · "` when N>1. Appshots are
  desktop-only (see §5 "Do not") — on web every attachment label is just
  its bare filename.

**Data**
- Reads: `state.queue: Vec<QueuedMessage>` (web: `QueueStore` rows via
  `WATCH_QUEUE`), `state.chat_host_supports(chat_id,
  MESSAGE_QUEUE_ACTIONS_V1)`, `state.chat_host_supports(chat_id,
  MESSAGE_QUEUE_EDIT_LEASE_V1)` (gated additionally on the LOCAL engine's
  own capability), `self.editing_queued`, `self.queue_removing`,
  `self.queue_drag`.
- Writes (RPC methods, `roboco_rpc::methods` — all already exported from
  `@roboco/engine-client`'s `methods` and wrapped in `lib/queue-actions.ts`):
  - `QUEUE_MESSAGE {chatId, text, attachments?, holdForTurnEnd?}` →
    `{id}` — **the composer's send path, see §3 below; not called from any
    row.**
  - `UPDATE_QUEUED_MESSAGE {chatId, id, text}` → ack; empty text also
    removes the row (single RPC handles both).
  - `MOVE_QUEUED_MESSAGE {chatId,id,toIndex}` → `{changed}`.
  - `REMOVE_QUEUED_MESSAGE {chatId,id,targetDeviceId}` → `{removed}`.
  - `SEND_QUEUED_MESSAGE_NOW {chatId,id,targetDeviceId}` → `{sent}`.
  - `BEGIN_QUEUED_MESSAGE_EDIT {chatId,id,editorDeviceId,editorInstanceId,targetDeviceId}`
    → `{outcome, leaseId, baseTextHash, text, attachments}`.
  - `RENEW_QUEUED_MESSAGE_EDIT {chatId,id,leaseId,targetDeviceId}` →
    `{outcome, expiresAtMs}`, on a **20-second interval** for as long as
    `editing_queued` is set (`start_queue_edit_renewal`,
    `queue.rs:1584-1633`; a 60s server-side lease implied by the interval,
    confirmed by the engine's `arm_queue_edit_expiry`).
  - `FINISH_QUEUED_MESSAGE_EDIT
    {chatId,id,leaseId,action,text?,expectedTextHash?,attachments?,targetDeviceId}`
    → `{outcome}` (`committed|cancelled|discarded|released|conflict|missing`).
- `queue_action_needs_host(method)` (`queue.rs:29-39`):
  `SendQueuedMessageNow`, `SteerQueuedMessageNow`, `RemoveQueuedMessage`,
  `BeginQueuedMessageEdit`, `RenewQueuedMessageEdit`,
  `FinishQueuedMessageEdit` all route to the chat's **host device**
  (`targetDeviceId`) because they race the drain lock; plain mutations
  (`QueueMessage`, `UpdateQueuedMessage`, `MoveQueuedMessage`) are ordinary
  CRDT writes and land on the local engine. Per §7 in the research (open
  question), the web client's single-engine connection model may make
  `targetDeviceId` moot — see §5 row "No targetDeviceId routing" for the
  decision to defer, not silently drop.
- `queue_mutation_acknowledged(method, reply)` (`queue.rs:44-52`): explicit
  ack fields per method (`changed` / `removed` / `sent`); anything else
  defaults to `true` (fire-and-forget CRDT writes). Already matched by the
  web wrappers' boolean returns in `lib/queue-actions.ts`.
- `GATE` (`queue.rs:263`, inside `preview_load_gate()`, `queue.rs:262-265`):
  a process-wide `static OnceLock<futures::lock::Mutex<()>>`. Every spawned
  queue-thumbnail load in `prepare_queue_previews` (`queue.rs:718-719`)
  does `let _permit = preview_load_gate().lock().await;` before checking
  the cache or issuing `ReadAttachmentChunk`, so only ONE queue-row
  attachment fetch is ever in flight at a time across the whole app. This
  throttles the burst of concurrent reads that would otherwise fire when
  many queue rows with attachments become visible at once (chat switch,
  fast scroll, or a big queue arriving from another device) — it is a
  concurrency limiter, not a cache. Web: add a tiny module-level mutex (a
  `Promise` chain is sufficient — `let gate: Promise<void> = Promise.resolve();
  function withGate<T>(fn: () => Promise<T>): Promise<T> { const next = gate.then(fn, fn); gate = next.then(() => undefined, () => undefined); return next; }`)
  in `queue-panel.tsx` (or a tiny new `lib/queue-thumbnail-gate.ts` if that
  reads cleaner) and wrap every queue-thumbnail's `loadAttachment` call in
  it.

### 2.3 What actually pushes a message into the queue (the composer's send path)

`Composer::send(text, queue: bool, cx)` (`composer.rs:6032`) computes
`queue` from `button_mode(cx) == SendButtonMode::Queue`, which is
`send_button_mode(run_live, has_text)` (`composer.rs:573-579`): **live run
+ non-empty content (text, attachments, or comments) → Queue.** When
`queue && !is_new`, the send path calls `QUEUE_MESSAGE {chatId, text:
queue_text, attachments: attachment_paths, holdForTurnEnd: true}`
(`composer.rs:6561-6568`) — **`holdForTurnEnd` is unconditionally `true`**
for any message queued this way (there is no user-facing toggle; "hold"
here just means "don't auto-steer, wait for the turn to end or an explicit
action"). The reply's `id` becomes the queue row id; a
`ComposerEvent::Queued` fires so the UI can react. This is the ONLY call
site of `QUEUE_MESSAGE` in the desktop composer.

**Web fix (the critical gap):** in `composer.tsx`'s `submit()`
(`composer.tsx:232-376`), the existing branches are:
1. `editing !== null` → commit/release the queue edit, then Run or Steer.
2. `isWorking && !hasContent` → Interrupt (Stop).
3. `isWorking && hasContent && supportsSteering && trimmed.length > 0` → Steer.
4. (falls through) → Run.

Branch 4 is wrong whenever `isWorking && hasContent && !supportsSteering`
(or `supportsSteering` is true but the message is attachment-only, so
`trimmed.length > 0` is false) — it fires a fresh `Run` against a chat
that's already working. Insert a new branch between 3 and 4:

```
if (isWorking && hasContent && !(supportsSteering && trimmed.length > 0)) {
  // live run + content the harness can't steer with right now → Queue.
  setBusy(true);
  try {
    const paths = staged.length > 0
      ? (await uploadAttachments(session.client, staged, /* progress */ undefined)).map((u) => u.path)
      : [];
    await queueMessage(session.client, chat.id, trimmed.length > 0 ? trimmed : ATTACHMENT_ONLY_TEXT, {
      attachments: paths,
      holdForTurnEnd: true,
    });
    setText("");
    setStagedByChat((current) => { const next = { ...current }; delete next[chat.id]; return next; });
  } catch (error) {
    sidebarNotice.set(`Could not queue: ${describeSendError(error)}`);
  } finally {
    setBusy(false);
  }
  return;
}
```

(Pseudocode — match the existing branch style, error handling, and
`uploadAttachments`/`withAttachments` usage already present in the Run
branch just below it. `ATTACHMENT_ONLY_TEXT` = `"See the attached
image(s)."`, matching `crate::attachments::ATTACHMENT_ONLY_TEXT`.) Update
the `sendLabel`/button title logic (`composer.tsx:399-421`) so this state
shows "Queue" (or reuses "Send" — the desktop's send button never says
"Queue" either, it stays "Send" and the mode is invisible to the user
except via the tray appearing) rather than silently falling through.

**`activate_latest_queued` on modified-submit with empty composer:** when
the composer is EMPTY and the user presses the modifier-send combo (the
desktop's `on_modified_submit`), instead of doing nothing, the desktop
activates (Sends now) the LAST row in the queue. Port this as: in
`composer.tsx`'s `onKeyDown`, when `event.key === "Enter" &&
(event.metaKey || event.ctrlKey) && text.trim().length === 0 && staged.length === 0`
and the queue (read via the `QueueStoreContext`, already available where
`Composer` is mounted through `chat-page.tsx`) has at least one row, call
`queueStore.sendNow(lastRow.id)` instead of falling through to Interrupt.
Order of checks in `onKeyDown` matters: Escape-as-interrupt and this new
rule must not both fire for the same keystroke.

## 3. Pure logic to port

- `one_line(text: &str) -> SharedString` (`queue.rs:174`):
  `text.split_whitespace().join(" ")`. Test: `"fix the test\n\nthen ship
  it"` → `"fix the test then ship it"`; `"  spaced   out  "` → `"spaced
  out"`. **Already ported** as `collapseWhitespace` in
  `queue-panel.tsx:314-319` — write a unit test for it under this ticket
  (`one_line` → `queue-panel.test.ts`).
- `queue_visible_text(text, attachments)` (`queue.rs:182-205`): strips
  Appshot context markers (skip — Appshots are desktop-only), and hides the
  legacy attachment-refs trailer ONLY when its parsed paths exactly match
  the row's `attachments` field (protects against a rolling-upgrade
  mismatch); falls back to `ATTACHMENT_ONLY_TEXT` when the remaining text
  is empty but attachments exist. Port the attachment-refs-trailer-hiding
  half only (drop the Appshot-marker half).
- `queue_attachment_labels(text, paths)` (`queue.rs:208-221`): per path,
  `"{app_name} Appshot"` if it matches a captured Appshot presentation by
  path (skip — desktop-only), else the bare filename (`Path::file_name`).
  Web: always the bare filename.
- `available_queue_primary_action(delivery_blocked, host_supports_actions)
  -> Option<SendNow>` (`queue.rs:103-108`): `Some` iff `!delivery_blocked
  && host_supports_actions`. Tests: `(false,true)→Some`, `(true,true)→None`,
  `(false,false)→None`. Port as `availableQueuePrimaryAction` in a small
  pure-logic module (e.g. inline in `queue-panel.tsx` or a new
  `lib/queue-row-logic.ts` if `queue-panel.tsx` gets crowded) with the same
  three test cases.
- `queue_latest_shortcut_visible(index, count, reveal_requested,
  action_available) -> bool` (`queue.rs:110-117`): true only for
  `index+1==count` (the LAST row) AND `reveal_requested` AND
  `action_available`. Test asserts it is false for `count==0` too (no
  out-of-range).
- `queue_drop_index(panel_y, count) -> usize` (`queue.rs:125-127`):
  `drop_index((panel_y - PANEL_PAD_TOP).max_relevant, ROW_SLOT, count)`,
  clamped `0..count`. Test: `queue_drop_index(0.0,2)=0`;
  `queue_drop_index(ROW_SLOT-0.1,2)=0`; `queue_drop_index(ROW_SLOT,2)=1`;
  `queue_drop_index(10000.0,2)=1`. `ROW_SLOT = ROW_HEIGHT + ROW_GAP = 36.0`.
  `PANEL_PAD_TOP = 0.0` (`queue.rs:82`, declared immediately after
  `PANEL_RADIUS = 16.0` at `queue.rs:81`) — the subtraction is a no-op;
  `panel_y` is already relative to the row list's top edge.
- `queue_drag_offsets(ix, from, prev_over, over) -> (f32,f32)`
  (`queue.rs:132-144`): for the dragged row itself, offsets are
  `(prev_over-from)*ROW_SLOT → (over-from)*ROW_SLOT`; for every OTHER row,
  `slide_offset(ix,from,prev_over)*ROW_SLOT → slide_offset(ix,from,over)*ROW_SLOT`
  (the displaced-row slide amount is `±1` slot depending on whether the row
  sits between `from` and `over`). This is the function that drives the
  §2.2 "Row reorder slide" motion — port it so the CURRENT React
  implementation's instant reflow (see the doc-comment at
  `queue-panel.tsx:14-18` admitting the approximation) becomes a real
  150ms tween.
- `visible_queue_rows(offset, height, count) -> Range<usize>`
  (`queue.rs:274-278`): the row-index range whose thumbnails are worth
  decoding, given the scroll offset and viewport height — drives
  `prepare_queue_previews`'s cache-key set (capped at 64 keys) so
  off-screen thumbnails are evicted rather than retained forever. Port as
  a windowing helper for `QueueThumbnail` mounts (only fetch thumbnails for
  rows within/near the scrolled viewport of `.queue-panel-list`).
- `queue_action_needs_host(method) -> bool` / `queue_mutation_acknowledged(method, reply) -> bool`
  (`queue.rs:29-52`): the host-routing and explicit-ack tables, see §2.2
  Data above.

## 4. Gaps this ticket closes

| Item | Kind | Desktop value | Web value (file:line) | Fix |
| --- | --- | --- | --- | --- |
| **Composer never queues while busy** | **MISSING (critical)** | `send_button_mode(run_live,has_text)==Queue` → `QUEUE_MESSAGE {..., holdForTurnEnd:true}` (`composer.rs:573-579,6561-6568`) | `lib/queue-actions.ts::queueMessage` exists but is **never called** anywhere in the app; `components/composer.tsx:232-376`'s `submit()` has no branch for "working, has text, harness can't steer" — it falls through to `sendRun`, issuing a fresh `Run` against an already-busy chat | Add the branch in `Composer.submit()` per §2.3 above |
| Queue edit lease is never renewed | MISSING (reliability) | 20s heartbeat via `RENEW_QUEUED_MESSAGE_EDIT` for the lifetime of an open edit; lease is 60s (`queue.rs:1584-1633`) | `QueueStore.renewEdit()` (`state/queue-store.ts:241`) exists but has zero call sites in `chat-page.tsx`/`composer.tsx` | Start a `setInterval(20_000)` calling `queueStore.renewEdit()` while `editingRow !== null` in `chat-page.tsx`; on `"lost"`/`"missing"`, clear `editingRow` like the desktop's expiry path |
| Row shows a persistent "Steer now" button | INVENTED | Desktop's queue row has exactly ONE primary action, Send now (`available_queue_primary_action`, `queue.rs:87-108`; comment: "All providers use Send now") — Steer is an engine RPC (`STEER_QUEUED_MESSAGE_NOW`) with no UI entry point anywhere in `queue.rs` | `components/queue-panel.tsx:280-288` renders a permanent "Steer now" button calling `store.steerNow` | Remove the Steer now button per `spec.md` decision 3 (settled — do not reopen); keep only Send now |
| Row shows a "Hold" chip | INVENTED | `hold_for_turn_end` is never surfaced in the desktop row UI (only used engine-side to gate auto-drain) | `queue-panel.tsx:265`, `.queue-row-hold` in `app.css:1508` | Remove the Hold chip; do not surface `holdForTurnEnd` in row UI |
| Delivery-gate text replaces the row body | WRONG BEHAVIOR | On `Editing`/`ReviewRequired`, the row's TEXT itself becomes `"Editing on {device}"` / `"Needs review"` (`queue.rs:400-408`) | Web keeps the raw message text always visible and adds a separate chip (`queue-row-chip-locked`/`-review`) alongside it (`queue-panel.tsx:266-268`) | Replace the text when gated, matching desktop exactly (§2.2 States) |
| No attachment thumbnails in queue rows | MISSING | Up to `queue_preview_limit()` (1 or 2, by composer width) 40×28 thumbnails + "+N" overflow chip (`queue.rs:576-603,818-904`) | `queue-panel.tsx` renders text only; `hasAttachments` is only used to pick the placeholder summary string (line 215-216) | Add `QueueThumbnail` using the existing `state/attachment-cache.ts` infra (already built for `user-attachments.tsx`), gated by the `GATE` concurrency limiter (§2.2 Data) |
| No responsive compact/expanded primary button | MISSING | `queue_preview_limit()==1` below 520px composer width → icon-only 28px button; else 72px "Send now" text (`composer.rs:4392-4398`, `queue.rs:979-1033`) | Fixed-width text buttons regardless of viewport (`queue-panel.tsx:270-297`) | Read the composer's measured width and switch icon-only ↔ icon+label at the 520px threshold |
| No latest-row keyboard-shortcut hint | MISSING | Last row's primary button shows `⌘↵`/`⌃↵` or the full combo label when `Cmd/Ctrl+Enter` on an empty composer would activate it (`queue_latest_shortcut_visible`, `queue.rs:110-117,1013-1032`) | Not implemented | Port `queue_latest_shortcut_visible`; show the shortcut cap on the last row's primary button when it would fire `activate_latest_queued` |
| Row background/border is a visible card | WRONG VALUE | Rows are borderless, inside ONE frosted panel; only hover/active state paints a wash (`queue.rs:513-518`) | `.queue-row` (`app.css:1488-1502`) has its own `1px border` + `background: color-mix(rb-raised 28%, transparent)` per row, i.e., a bordered card per row | Drop the per-row border/background; only `:hover`/editing/locked states paint a wash; the PANEL carries the frost/border |
| Panel geometry doesn't match | WRONG VALUE | 16px top-radius frosted glass surface, `input_glass_bg()`, 18px self-overlap absorbed by composer's `-26px` margin, `QUEUE_SIDE_INSET=16` narrower than the composer (`queue.rs:81,223-236`, `composer.rs:74,77,7462-7466`) | `.queue-panel` (`app.css:1470-1479`) is a full-width block, square corners, `border-top` only, own `background: var(--rb-shell)` + `backdrop-filter: blur(16px)`, no negative-margin overlap with the composer, `ROW_GAP_PX=4` between rows (desktop = 0) | Rebuild the panel per §2.1 |
| Fixed 5-row scroll cap, no edge fade | WRONG VALUE | Max height = `30%` of the VIEWPORT height (`queue.rs:320`), with a top+bottom edge fade over the scrollable list | `MAX_VISIBLE_ROWS = 5` (`queue-panel.tsx:32`), plain `overflow-y: auto`, no fade | Compute max-height as `30vh` and add a CSS `mask-image` edge fade |
| Queue-wheel isolation missing | MISSING | Wheel events over the panel never bleed into the transcript, even past scroll bounds (asserted test) | Not addressed (native `overflow-y:auto` scroll chaining is the browser default and WILL bleed) | Add `overscroll-behavior: contain` to `.queue-panel-list` |
| Editing UI lives outside the row | WRONG BEHAVIOR | Save/Cancel render INLINE in the editing row itself (`queue.rs:606-631`) | Web moves the sole affordance ("Cancel edit") to a separate `.chat-edit-toolbar` bar below the whole composer (`chat-page.tsx:274-280`); there is no inline "Save" — saving happens by pressing the composer's own Send button | Move Save/Cancel back inline per §2.2 Interactions |
| Editing a queued row drops its attachments from view | MISSING | `BEGIN_QUEUED_MESSAGE_EDIT`'s reply attachments are downloaded and staged into the composer's attachment strip so the user can see/add/remove them while editing (`composer.rs:1296-1337`) | `QueueStore.beginEdit` (`queue-store.ts:216-238`) and `Composer`'s `editingMessage` prop (`components/composer.tsx:51-52`) carry `{id, text}` only — no attachments are fetched, staged, or editable; `finishEdit`'s `attachments` option is never populated by the call site (`chat-page.tsx:161-164`), so a commit always preserves the OLD attachments unseen and unchangeable | Extend `BeginLeaseOutcome`/`editingMessage` to carry attachments (the `outcome.attachments` field is already returned by the RPC per the wire contract in `state/queue-store.ts`'s types — check `@roboco/proto`), stage them via ticket 17's `attachment-cache`/`stageBytes` pipeline, and pass `attachments` through `finishEdit` on commit. Coordinate with ticket 17 if its staging primitives aren't ready yet — this row can land the RPC plumbing first and the visual staging second. |
| No host-capability gating on queue actions | Open question in research, resolved here | Every host-routed queue RPC is gated on `state.chat_host_supports(chatId, MESSAGE_QUEUE_ACTIONS_V1)` (and `..._EDIT_LEASE_V1` additionally on the local engine for edit) before firing, with a friendly failure toast otherwise (`queue.rs:1115-1125,1267-1276`) | `queue-actions.ts`/`queue-store.ts` fire the RPCs unconditionally; no capability check anywhere | The web client drives a single engine per `CONTEXT.md`; there is no multi-engine capability registry to check against today. Leave unconditional for this ticket — note in Comments that this is deferred pending the fleet ticket (31), not silently dropped. |
| No `targetDeviceId` routing on queue RPCs | Open question in research, resolved here | Host-authoritative queue RPCs always carry `targetDeviceId: host_device_id` when the chat's host differs from the caller's device (`queue.rs:1671-1684,1103-1120`) | `queue-actions.ts` never sets `targetDeviceId` on any call | Same as above — the web's single-engine model has no other-device host to target today. Leave unset; revisit in ticket 31 (Fleet). |
| Icons available but unused | Cosmetic | Icon-glyph buttons throughout (`icons::PEN`, `TRASH_BIN_MINIMALISTIC`, `QUEUE_SEND`, `QUEUE_CHECK`, `QUEUE_CLOSE`, `CLOSE_CIRCLE`, `PLUS`) | Text-label buttons ("Send now", "Steer now", "Edit", "×") in `queue-panel.tsx`; `@roboco/icons` package (vendored under `web/packages/icons`) is unused here | Swap text buttons for `pen`, `trashBinMinimalistic`, `queueSend`, `queueCheck`, `queueClose`, `closeCircle` (all confirmed present in `web/packages/icons/src/generated/index.ts`) |

## 5. Do not

- Do not add a "Steer now" button anywhere in the queue row — `spec.md`
  decision 3 is settled: the queue panel offers Send now only.
- Do not surface `holdForTurnEnd` as a chip or any other UI element; it is
  engine-internal state (always `true` when queued from a busy composer).
- Do not build Appshot support (`{app_name} Appshot"` labels, Appshot
  context-marker stripping) — Appshots are desktop-only (native
  window-capture, no browser equivalent). Every attachment label on web is
  the bare filename.
- Do not build the staged-attachment strip, upload pipeline, transcript
  thumbnails, or lightbox — those are ticket 17. This ticket only READS
  from `state/attachment-cache.ts` for the small 40×28 queue-row preview.
- Do not build review-comment chips, diff-line adders, or comment cards —
  those are ticket 23. If a queued row's text happens to contain a
  comment block, render it as plain text like any other queued text; no
  badge extraction here.
- Do not touch `composer.tsx`'s layout, pickers, compact/expanded flip, or
  send-button chrome beyond the two narrow additions named in the file
  table — that surface belongs to ticket 13.
- Do not add multi-device/fleet routing (`targetDeviceId`, host-capability
  gating) beyond what's noted as deferred in §4 — that is ticket 31's
  scope once the fleet model exists.
- Do not implement `MESSAGE_QUEUE_ACTIONS_V1`/`MESSAGE_QUEUE_EDIT_LEASE_V1`
  capability checks against a registry that doesn't exist yet on web.

## 6. Acceptance

- [ ] Typing while a chat is `working` and the picked harness does not
      support steering appends a row to the queue tray instead of firing a
      new run (manually verify against a non-steering harness fixture).
- [ ] The queue tray is visually docked behind the composer pill: top
      corners rounded 16px, bottom flush/overlapped, frosted background,
      1px border, `-26px` overlap margin, `16px` side inset — matches
      `composer.rs:74,77,7462-7466` and `queue.rs:81,223-236`.
- [ ] Panel enter/exit fades over 150ms (`--rb-motion-fade-quick` /
      `--rb-ease-ease`).
- [ ] Max row-list height is `30vh`, with a top+bottom mask-image edge
      fade; `overscroll-behavior: contain` stops wheel bleed into the
      transcript.
- [ ] A row is borderless/transparent at idle; `ink(0.04)` wash on hover;
      `ink(0.06)` while being edited; `opacity 0.55` while being removed.
- [ ] A row shows up to `queue_preview_limit()` (1 below 520px composer
      width, else 2) 40×28 attachment thumbnails plus a "+N more" chip
      when it has more.
- [ ] Clicking Send now on a row calls `SEND_QUEUED_MESSAGE_NOW` and the
      row leaves the tray only after `sent:true`.
- [ ] Clicking Edit (pen) calls `BEGIN_QUEUED_MESSAGE_EDIT`, feeds text
      into the composer, and switches the row into its editing children
      (spacer, "Editing in composer" text, inline Save/Cancel icons).
- [ ] While a row is being edited, `RENEW_QUEUED_MESSAGE_EDIT` fires every
      20 seconds; a `"lost"`/`"missing"` outcome clears the local edit
      state exactly like the desktop's lease-expiry path.
- [ ] Inline Save commits via `FINISH_QUEUED_MESSAGE_EDIT` with
      `action: "commit"` and `expectedTextHash`; inline Cancel commits
      `action: "cancel"` and reverts the row's displayed text.
- [ ] Dragging a row reorders via `MOVE_QUEUED_MESSAGE`, with a 150ms
      `EASE_OUT` slide tween on the dragged row and every displaced row
      (no instant snap except under `prefers-reduced-motion: reduce`).
- [ ] `Editing`/`ReviewRequired` delivery gates replace the row's TEXT
      (not an added chip) with `"Editing on {device}"` / `"Needs review"`,
      and disable the primary action.
- [ ] No "Steer now" button, no "Hold" chip anywhere in the panel.
- [ ] Empty composer + Mod+Enter with a non-empty queue sends the last
      queued row now (`activate_latest_queued`).
- [ ] Unit tests: `one_line` → `queue-panel.test.ts` (or wherever
      `collapseWhitespace` lives), `available_queue_primary_action` →
      new `queue-row-logic.test.ts`, `queue_latest_shortcut_visible` →
      same file, `queue_drop_index` → same file (all four desktop test
      cases per function reproduced).
- [ ] Screenshot pair, desktop vs web, states: empty chat (no tray), one
      queued text-only row (idle), one queued row with 2 attachment
      thumbnails, a row mid-edit (inline Save/Cancel visible), a row with
      `Editing on {device}` gate text, a row mid-drag (slide tween
      captured at t≈0.5).
- [ ] `pnpm -r build` green; package vitest green.
- [ ] No new literal hex/px where a `--rb-*` token exists.

## Comments

(empty; appended during implementation)
