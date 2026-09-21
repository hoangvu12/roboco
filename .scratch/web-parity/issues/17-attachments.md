# 17 — Attachments

**What to build:** Users can attach images to a message — via the paperclip
button, drag-and-drop onto the chat column, or clipboard paste — and see
them as bare 56px thumbnails in a strip above the composer's text box before
sending. On send, the strip clears instantly and the just-sent bubble shows
112×80 thumbnails with a live upload-progress ring; once the run completes,
those same thumbnails become clickable, opening a full pan/zoom lightbox.
After this ticket, the staged strip has no filenames or always-visible
remove buttons (desktop has neither), the transcript thumbnails are the
correct size and gap, uploads carry the desktop's exact timeout/retry
ladder and error copy, and the decoded-image cache evicts under a byte
budget instead of growing forever.

**Blocked by:** 13 (Composer core)

**Status:** done

**Research:** `../../web-client/research/06-queue-attachments-comments.md`
§3.4, §3.5, §3.6 (build these); §3.7 is transcribed below for
CONTEXT ONLY — it belongs to ticket 23, see §5. Also §4, and §5 rows for
`Staged-attachment strip UI differs`, `Whole-send progress bar in composer
strip`, `Transcript thumbnail size mismatch`, `Transcript thumbnail gap
mismatch`, `No sending/uploading overlay on transcript thumbnails`, `No
LRU/byte-budget eviction on the attachment cache`. Also `04-composer.md`
§3.0 (`STRIP_THUMB`, `STRIP_GAP`, `STRIP_PAD_TOP`, `STRIP_PAD_X`,
`attachments::MAX_ATTACHMENT_BYTES`), §3.11 (full attachment-strip spec),
§3.14 (attach button), and its §5 gap rows for the strip/drop/paste. Also
`02-transcript.md` §3.7 (`render_user_attachments`), §3.19 (lightbox +
`image_viewer.rs` pan/zoom geometry), and its §5 rows 57 and 61.

**Desktop reference (for lookups only):**
`crates/ui/src/composer.rs::render_attachment_strip` (:4636),
`crates/ui/src/attachments.rs` (full — staging, upload, cache, lightbox),
`crates/ui/src/transcript.rs::render_user_attachments` (:4878),
`crates/ui/src/image_viewer.rs` (pan/zoom), `crates/ui/src/shell.rs`
(`#chat-dropzone`, `#attachment-drop-overlay`, :5990-6154).

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/components/attachments/attachment-strip.tsx` | edit (near-total rewrite) | `AttachmentStrip`, `StagedAttachmentRow` |
| `web/packages/app/src/components/attachments/user-attachments.tsx` | edit | `UserAttachments`, `UserAttachmentThumb`, `ThumbContent` — remove the inline lightbox, delegate to the new shared one |
| `web/packages/app/src/components/lightbox.tsx` | new | `Lightbox` — the shared pan/zoom viewer, used by both the staged strip and the transcript |
| `web/packages/app/src/lib/attachments.ts` | edit | add `attachmentStripHeight(count, innerWidth)`, retain everything else; no changes to the wire codec (`withAttachments`/`parseUserMessageImages`) — those already match |
| `web/packages/app/src/state/attachment-cache.ts` | edit | add LRU/byte-budget eviction (`IMAGE_CACHE_BUDGET_BYTES`) and a protected-keys set, mirroring `ImageCache`/`protect_attachments` |
| `web/packages/app/src/components/composer.tsx` | edit (narrow) | ONLY: clear the staged strip synchronously on submit (already does, keep it) AND publish an optimistic transcript echo immediately instead of waiting for the whole upload+run (needs a small hook into whatever ticket 18/transcript-store exposes for optimistic rows — coordinate in Comments if that hook doesn't exist yet; this ticket must not block on it, land the strip-side fixes regardless) |
| `web/packages/app/src/styles/app.css` | edit | `.composer-attachments*` (2071-2237), `.composer-staged-*` (2151-2201), `.user-attachments*` (2239-2337), remove `.composer-attachments-overlay`/`.composer-attach-button`/`.composer-attachments-progress*`, add `.lightbox*` |

## 1. Context a fresh session needs

- Attachments are **images only** — desktop accepts exactly `png`, `jpg`/
  `jpeg`, `gif`, `webp`, `svg`, `bmp`, `tif`/`tiff` and nothing else,
  anywhere. There is no generic-file attachment type and no file-type icon
  system to build. `lib/attachments.ts` already encodes this correctly
  (`AttachmentFormat`, `formatByName`, `formatByBytes`, `EXT_TO_FORMAT`) —
  do not add new formats.
- The staged strip (composer-side, before send) and the transcript strip
  (after send) are TWO DIFFERENT SIZES on purpose: 56px staged thumbnails
  vs 112×80 sent thumbnails. Do not unify them into one component with a
  size prop that risks drifting — desktop doesn't share the render
  function either (`render_attachment_strip` vs
  `render_user_attachments` are separate `composer.rs`/`transcript.rs`
  functions).
- `StagedAttachment` (`lib/attachments.ts:185-203`) already carries
  `{id, name, format, bytes, previewUrl, naturalSize}` — no shape changes
  needed for the strip's own rendering. `stageFile`/`stageBytes` already
  enforce the 24 MiB cap and format detection.
- `uploadAttachments` (`lib/attachments.ts:465`) and `readAttachmentImage`
  (`:680`) already exist and already implement the timeout/chunk/retry
  constants correctly per the desktop's `attachments.rs` (verify against
  §3 below; this ticket's job is mostly the RENDERING gaps, not the
  transport, but audit the transport against the tables below since no
  research pass has independently verified it line-by-line).
- `state/attachment-cache.ts` is the SAME cache used by both the queue
  panel (ticket 16, read-only) and the transcript (this ticket). It is
  currently an unbounded `Map` — ticket 16 does not touch it, this ticket
  adds the eviction the desktop's `ImageCache` has.
- The composer today clears the staged strip only via `setStagedByChat`
  deleting the chat's entry AFTER `sendRun` resolves (`composer.tsx:347-
  355`) — the desktop clears it INSTANTLY on pressing Send, before the
  upload even starts (`composer.rs:6114-6117`, "snapshot-and-clear NOW"),
  and instead shows upload progress as a ring drawn ON the transcript's
  optimistic echo. This ticket's `composer.tsx` touch point is narrow: it
  is fine (and correct per desktop) if the strip visually empties
  immediately on `submit()` rather than waiting for the async upload to
  finish — check whether ticket 13 already made `submit()` clear
  synchronously; if not, this is this ticket's fix (see §4).
- Vocabulary: "attachment", "staged" (before send), "thumbnail". Never
  "file" (these are always images) outside of the underlying `File`/`Blob`
  browser API.
- No new `--rb-*` tokens are strictly required for this ticket's numbers
  (56/8/12/16 for the strip, 112/80/8/8 for the transcript, all plain px);
  colors should still route through existing roles (`--rb-hairline`,
  `--rb-ink` washes, `--rb-text-muted`) — check `app.css`'s existing
  `.composer-staged-thumb`/`.user-attachments-thumb` rules for the current
  token usage before introducing new hex.

## 2. Spec

### 2.1 Staged attachment strip (`render_attachment_strip`, `composer.rs:4636`)

Rendered when `staged()` is non-empty. This is a **wrap grid of bare 56px
thumbnails** — no names, no attach button, no progress bar.

**Layout**

| Property | Value | Source |
| --- | --- | --- |
| strip | `w_full flex_none`, flex row, `flex_wrap` | `composer.rs:4641-4646` |
| gap | `STRIP_GAP` (8) | `composer.rs:4647`, `composer.rs:289` |
| padding | `px(STRIP_PAD_X)` 16, `pt(STRIP_PAD_TOP)` 12 | `composer.rs:4648-4649`, `composer.rs:290-291` |
| thumb size | `STRIP_THUMB = 56` | `composer.rs:288` |

**Per-thumbnail** (each wrapped in a `group("composer-att-{id}")`, `flex_none`, `relative`):

| element | spec | source |
| --- | --- | --- |
| frame | `size(56)`, `rounded` 8, `overflow_hidden`, 1px border `theme::hairline(0.10)`, `cursor_pointer` | `composer.rs:4661-4667` |
| image | explicit `w`/`h` = `56 − 2` = 54, `rounded` 7 (8 − border), `object_fit: Cover` | `composer.rs:4675-4689` |
| remove button | own layer (`frost::layered`): absolute `top` −6, `right` −6, `size(18)`, `rounded_full`, `bg theme.bg`, centered, `shadow_sm`, `opacity 0` → `1` on **group hover**; icon `CLOSE_CIRCLE` 14px `theme.text_muted` | `composer.rs:4695-4723` |

**Children (in order)**: one child per staged attachment, each = thumbnail
(click → opens the lightbox) + hover-revealed remove button. **No filename
is ever shown in this strip.**

**Interactions**
- Click thumbnail → opens the shared lightbox (see §2.4) at this staged
  attachment's bytes/`previewUrl`.
- Click remove (×) → `stopPropagation` first (so it doesn't also open the
  preview) → removes the attachment.
- Drop / paste / picker: filters by supported image extension (silently
  skips non-images, matching a browser's `image/*` filter), stages,
  oversize/read failures surface as a failure notice (`self.failure`
  desktop-side; web already has `onError`/`sidebarNotice`).

**States**: no staged-time upload progress indicator exists on the strip
at all — the strip is emptied INSTANTLY when Send is pressed
(`self.attachments.remove(&self.current_key)`, `composer.rs:6114-6117`,
"snapshot-and-clear NOW"); any per-attachment progress is drawn later, in
the TRANSCRIPT, as a ring overlay on the optimistic echo's thumbnail
(§2.3).

**Text**: none (no labels/filenames anywhere in this component).

**Data**: writes local staged state; nothing is sent to the engine until
the surrounding send/queue-edit flow calls the upload.

**Height contribution** — `attachment_strip_height(count, inner_width)`
(`composer.rs:296`), pure function, add to `lib/attachments.ts`:

```
if count == 0 → 0
usable  = max(inner_width − 2*16, 56)
per_row = max(floor((usable + 8) / (56 + 8)), 1)
rows    = ceil(count / per_row)
height  = 12 + rows*56 + (rows − 1)*8
```

`inner_width` is the pill's content width in BOTH compact and expanded
modes (`last_available_width.unwrap_or(768) − 2*Theme::SPACE_LG − 2`,
`composer.rs:7511-7512`). Read the composer's own measured width (ticket
13's flip-morph measurement) rather than re-measuring.

**Staging sources (all feed `add_staged` / `add_paths`)**
- paperclip → native path prompt. Web equivalent: a hidden `<input
  type="file" accept="image/*" multiple>` — already present
  (`attachment-strip.tsx:192-202`), keep it but see §4 for the accept-list
  and error-toast gaps.
- clipboard paste of image data → stage clipboard image.
- clipboard paste of file paths → same staging path as picker/drop.
- **OS file drop anywhere on the conversation column** (`shell.rs
  #chat-dropzone`, `on_drop::<ExternalPaths>`) → stage. Desktop's drop
  target is the WHOLE chat column, not just the strip — see §4 "Drag-and-
  drop" gap; this ticket widens the web's drop target from
  `.composer-attachments` to the chat column (`.chat-column` or
  `.chat-body`, whichever `shell.rs`'s `#chat-dropzone` most closely
  maps to on web — check `chat-page.tsx`'s DOM structure) and shows the
  desktop's `#attachment-drop-overlay` veil, verbatim text **"Drop to
  attach"** (not the web's current invented "Drop images to attach"),
  background `theme.scrim().opacity(0.4)` in light / `0.6` in dark
  (`shell.rs:1202-1208`).
- `add_paths` silently skips files whose extension is not a supported
  image format; read failures / oversize files (>24 MiB) set `failure`.

### 2.2 Accepted formats, limits, upload pipeline (`attachments.rs`)

**Accepted formats** (`format_by_extension`, `attachments.rs:203-214`) —
images ONLY, no other file type is ever accepted anywhere in the desktop
client: `png`, `jpg`/`jpeg`, `gif`, `webp`, `svg`, `bmp`, `tif`/`tiff`.
There is no file-type icon system for non-images because non-images cannot
be staged. **Already matched** by `lib/attachments.ts`'s `EXT_TO_FORMAT`.

**Limits**

| Constant | Value | Source |
| --- | --- | --- |
| `MAX_ATTACHMENT_BYTES` | 24 MiB (`24*1024*1024`) | `attachments.rs:31` |
| `UPLOAD_CHUNK_B64_CHARS` | 680,000 base64 chars/chunk (≈510 KB binary; sized to stay under Cloudflare's 1 MiB WS frame with headroom) | `attachments.rs:39` |
| `MAX_READ_CHUNKS` | 1,000 (bounds the read-back loop) | `attachments.rs:41` |
| `UPLOAD_CONCURRENCY` | 3 chunks in flight per file | `attachments.rs:317` |
| `FIRST_CHUNK_TIMEOUT` | `90s` — bounds each `UploadChunk` call in the FIRST concurrency window (`seq < UPLOAD_CONCURRENCY`), i.e. a cold dial to a possibly-sleeping remote device | `attachments.rs:291` |
| `CHUNK_TIMEOUT` | `30s` — bounds every `UploadChunk` call after the first window (the link is presumed warm by then) | `attachments.rs:292` |
| `COMMIT_TIMEOUT` | `150s` — bounds the single trailing `UploadCommit` call; must outlast the engine's cross-device byte-assemble step | `attachments.rs:293` |
| `READ_CHUNK_TIMEOUT` | `20s` — bounds each `ReadAttachmentChunk` call in the transcript/queue read-back loop | `attachments.rs:294` |
| Whole-attachment deadline | `min(120 + 15*n_chunks, 900)` seconds | `attachments.rs:324-326` |
| Chunk retry | up to 2 retries per chunk, staggered `50ms * attempt * (seq+1)` | `attachments.rs:388-417` |
| `IMAGE_CACHE_BUDGET_BYTES` | `64 MiB` (`64*1024*1024`) — the retained-encoded-bytes ceiling for the transcript/queue image cache | `attachments.rs:585` |
| Retry backoff (read failures) | `2s << min(attempts-1,3)`, capped `15s` (2s→4s→8s→15s) | `attachments.rs:578-580` |

`lib/attachments.ts` (`:29-55`) already defines
`MAX_ATTACHMENT_BYTES`/`UPLOAD_CHUNK_B64_CHARS`/`MAX_READ_CHUNKS`/`UPLOAD_CONCURRENCY`/
`FIRST_CHUNK_TIMEOUT_MS`/`CHUNK_TIMEOUT_MS`/`COMMIT_TIMEOUT_MS`/`READ_CHUNK_TIMEOUT_MS`/
`attachmentDeadlineMs` matching every value above — **audit these against
the table as part of this ticket** (no research pass has independently
verified them; if any drift, fix the constant). `attachment-cache.ts`
does NOT yet have `IMAGE_CACHE_BUDGET_BYTES` or the retry backoff ladder
as a scheduled retry (it computes `retryIn` but nothing currently re-
triggers a load when `retryIn` elapses without a re-render — verify a
consumer actually schedules the retry; the transcript row must re-attempt
`loadAttachment` when its own `retryIn` countdown reaches 0).

**What happens on a timeout, per phase** (`call_with_timeout`,
`attachments.rs:298-312`, races the RPC future against
`executor.timer(timeout)` and returns `Err("{method} timed out")` if the
timer wins first):
- **`UploadChunk`** (bounded by `FIRST_CHUNK_TIMEOUT`/`CHUNK_TIMEOUT`): a
  timeout is just another `Err` fed into the per-chunk retry loop inside
  `upload_attachment` (`attachments.rs:391-417`) — up to 2 retries (3
  attempts total), staggered `50ms * attempt * (seq+1)`; only once a chunk
  has exhausted its retries does the error propagate out of
  `try_for_each_concurrent` and fail the whole upload.
- **`UploadCommit`** (bounded by `COMMIT_TIMEOUT`): NOT wrapped in the
  chunk retry loop — a single timeout/`Err` here propagates immediately
  via `?` (`attachments.rs:435-447`) and fails the send. The composer
  surfaces this as one of two fixed strings depending on which upload
  path failed, **verbatim**:
  - `"Couldn't stage the attachment locally."` for the local
    (`target_device_id: None`) pending-attachment stage
    (`composer.rs:6323-6335`).
  - `"Couldn't upload the attachment — the device may be offline."` for
    the remote-host upload (`composer.rs:6358-6374`).
  - The queue-edit commit path (`finish_queue_edit`, `composer.rs:1546-
    1577`) instead collapses ANY upload/RPC error into
    `"Couldn't reach the chat host; your edit is still in the editor"`.
- **`ReadAttachmentChunk`** (bounded by `READ_CHUNK_TIMEOUT`,
  transcript/queue read-back): no retry inside `read_attachment_image`
  itself — a timeout makes the function return `None` for that whole read
  (`attachments.rs:483-491`, the `.ok()?` short-circuit). The CALLER
  (`transcript.rs::spawn_attachment_load`, `queue.rs::prepare_queue_previews`)
  treats `None` as `store_error`, which is what actually drives the
  retry: the cache's Retry backoff ladder above (2s→4s→8s→15s) schedules
  the NEXT attempt at the cache level, not inside the read function.
- The whole-attachment deadline (`attachment_deadline`) races ABOVE all of
  this (`futures::future::select`) and produces `"attachment upload
  exceeded {N}s"` if the per-chunk retries are technically succeeding but
  too slowly to ever finish.

Audit `lib/attachments.ts`'s `uploadAttachments` against this state
machine; port the two verbatim error strings above into whatever surfaces
upload failures in `composer.tsx` (today it's a single generic
`describeSendError`-driven notice — split it per the local-vs-remote-host
path if that distinction exists in the current upload call, or note in
Comments if the web's single-engine model collapses that distinction and
only one string applies).

**`CACHE`** (`attachments.rs:635`): the module-level cache backing
`attachment_snapshot`/`store_loaded`/`store_error`/`begin_load` — one
process-wide decoded-image cache keyed by `(device_id, path)`, shared by
the transcript AND the queue-thumbnail previews (ticket 16 reads this same
cache). `ImageCache::insert_loaded` (`attachments.rs:598-631`) evicts the
globally oldest (`last_used` LRU tick) entry whose key is neither the one
just inserted nor in the protected set, repeating until `loaded_bytes <=
IMAGE_CACHE_BUDGET_BYTES`.

**`PROTECTED`** (`attachments.rs:646`): a second set — the set of
`(device_id, path)` keys the LRU eviction in `CACHE` must never touch,
regardless of how stale their `last_used` tick is. `protect_attachments(keys)`
(`attachments.rs:651-654`) REPLACES this set wholesale; the transcript
calls it on every row-sync pass with exactly the attachments currently on
screen, so a visible thumbnail can never be evicted out from under the
user even under budget pressure — only off-screen images in other chats
remain evictable.

Port both to `state/attachment-cache.ts`:
- Track approximate byte size per cached entry (`image.bytes.byteLength`)
  and a `lastUsed` tick, bumped on every `useAttachmentImage` read.
- On every `cache.set(... state: "loaded" ...)`, if the sum of all loaded
  entries' bytes exceeds `IMAGE_CACHE_BUDGET_BYTES` (64 MiB), evict the
  globally-oldest-by-`lastUsed` entry not in the protected set, repeat
  until under budget.
- Export a `protectAttachments(keys: ReadonlySet<string>)` that replaces
  the protected-set wholesale; have the transcript's row-sync pass (or
  `UserAttachments`' mount/unmount) call it with exactly what's on screen.

**Text transport**: `with_attachments(text, paths)` appends `"\n\nAttached
images (local files — open them to view):\n- {path}\n..."`; an empty
prompt becomes `ATTACHMENT_ONLY_TEXT = "See the attached image(s)."`
first. `parse_user_message_images` reverses this (case-insensitive marker
match, requires the trailer line to end `"):"`). **Already ported** as
`withAttachments`/`parseUserMessageImages` in `lib/attachments.ts:342-424`
— verify the case-insensitive match and the trailer-format requirement
against the desktop's edge cases in §3 below.

**Naming**: `ensure_extension(name, format)` appends the format's
extension unless the name already carries a plausible 2-5 char
alphanumeric extension — handles pasted screenshots that arrive as a bare
`"image"`. **Already ported** as `ensureExtension`
(`lib/attachments.ts:156`).

### 2.3 Attachment rendering in the transcript (`render_user_attachments`, `transcript.rs:4878`)

**Layout**

| Property | Value | Source |
| --- | --- | --- |
| Thumb size | `ATT_THUMB_W × ATT_THUMB_H = 112 × 80` | `transcript.rs:187-188` |
| Frame radius / inner img radius | `8px` / `7px` | `transcript.rs:5079,5115` |
| Gap | `8px`, `flex_wrap`, `justify_end` (thumbnails right-align under the user bubble) | `transcript.rs:4893-4900` |
| Border (loaded) | `1px` `hairline(0.11)`, bg `ink(0.035)` | `transcript.rs:5090-5092` |
| Border (loading) | `1px` `hairline(0.08)`, bg `ink(0.055)`, pulsing `opacity(0.35 + 0.4*pulse)` on the `ROBOCO_PULSE` (2400ms) wave | `transcript.rs:5160-5172` |
| Border (error/missing) | `1px` **dashed** `hairline(0.14)`, bg `ink(0.025)` — the "missing" thumb | `transcript.rs:5153-5158` |

Full strip layout: `w_full min_w_0 flex_none flex flex_row flex_wrap
justify_end items_start gap(8) px(4) pt(4) pb(6)` (`transcript.rs:4888-4900`).
Ordinary thumbnail frame: `flex_none w(112) h(80) rounded(8)
overflow_hidden` (`transcript.rs:5075-5080`); the loaded image inside is
explicit `w(110) h(78) rounded(7) object_fit(Cover)` (NOT `size_full` —
gpui honors intrinsic aspect over percent height,
`transcript.rs:5087-5117`).

**States**

| State | Condition | Rendering |
| --- | --- | --- |
| Loaded | `AttachmentSnapshot::Loaded` | `border_1 border_color(hairline(0.11)) bg(ink(0.035)) cursor_pointer`; image `w(110) h(78) rounded(7) object_fit(Cover)` |
| Error | `AttachmentSnapshot::Error` | `border_1 border_dashed border_color(hairline(0.14)) bg(ink(0.025))` — the dashed "missing" thumb |
| Loading | `AttachmentSnapshot::Loading` | `border_1 border_color(hairline(0.08)) bg(ink(0.055))`, opacity `0.35 + 0.4 · pulse_wave(ROBOCO_PULSE delta)` — the pulsing skeleton |
| Sending overlay | path starts with `pending://` or `pending/` AND state is Loaded | absolute `inset_0 rounded(7) flex items_center justify_center`, bg `hsla(0,0,0, 0.38 + 0.05·pulse)`; child is `loaders::upload_progress_ring(pct, 34.0)` when a percent is known, else `loaders::mini_glyph_spinner(key, 3.0, theme.glyph, …)` |

Upload percent sources, in order (`transcript.rs:5917-5929`): this
attachment's own relay transfer by the `uploadId` inside its
`pending://<uploadId>/…` ref, then the send-wide `upload_progress_percent()`,
else indeterminate (spinner).

**Motion**: upload progress ring — `RING_STROKE = 2.5px`, `RING_SEGMENTS =
64` steps/turn, background track `hsla(0,0,1,0.22)`, foreground arc
`hsla(0,0,1,0.95)`, clockwise from 12 o'clock, filled proportional to
`percent/100` (`loaders.rs:283-323`); pulsing loading skeleton uses
`motion::pulse_wave` on `ROBOCO_PULSE` (2400ms period cosine wave, 0→1→0).

**Data**: `attachment_state(device_ids, path)` tries the chat's host
device id then the local device id, in order; a Loaded source from EITHER
wins. Loads are deduped via `begin_load`/`store_loaded`/`store_error` on
the shared module-level cache, shared with the queue-thumbnail cache
(ticket 16). Web: `useAttachmentImage` already does the dedup via
`beginAttachmentLoad`; the "try host then local" fallback needs auditing
against `UserAttachmentThumb`'s current single-`deviceId` lookup
(`user-attachments.tsx:59` only tries one `deviceId` — add the fallback if
the web ever has two candidate device ids for a chat; if the web's
single-engine model only ever has one relevant device id, note that in
Comments as a documented simplification rather than silently diverging).

**Interactions** — clicking a Loaded thumbnail stores the current focus,
opens the shared `Lightbox` (§2.4), and focuses it so Escape reaches it.

### 2.4 Attachment lightbox (`attachments.rs::lightbox_with_size` + `image_viewer.rs`)

New shared component: `web/packages/app/src/components/lightbox.tsx`. Both
the staged strip (§2.1) and the transcript (§2.3) open this same
component — do not keep the transcript's current inline
`.user-attachments-lightbox` `<div>` as a separate implementation once
this lands.

**Scrim / frame**

| Property | Value | Source |
| --- | --- | --- |
| mount | `gpui::deferred(gpui::anchored().position((0,0)))`, `.priority(3)` | `attachments.rs` |
| frame | `id("attachment-lightbox") occlude() track_focus(focus) w(viewport.width) h(viewport.height)` | — |
| background | `popover::scrim_alpha(0.7)` | — |
| layout | `flex flex_col items_center justify_center gap(12)` | — |
| image box | `w(viewport.width · 0.9) h(viewport.height · 0.85)` | — |
| filename | `max_w(0.9·w) overflow_hidden text_size(ui_rems(11.0)) text_color(ink(0.45))` | — |
| loading state | `text_color(ink(0.6))`, text `"Loading image…"` | — |

**Interactions**
- Escape (`on_key_down`, key `"escape"`) → close, `stop_propagation`.
- Any left mouse-down calls `viewer.begin_click()` (clears drag state).
- Click closes ONLY when `!viewer.dragged()` — a pan that ends on the
  scrim must not close.
- Scroll wheel is swallowed (`stop_propagation`) so the transcript never
  scrolls underneath.

**Pan/zoom geometry** (`image_viewer.rs`)
- `fit_scale = min(viewport.w/natural.w, viewport.h/natural.h, 1.0)` —
  **never upscales**.
- `zoom(scale, anchor)`: max = `min(131072/max(natural.w, natural.h),
  32.0)` and at least `fit_scale`; min = `min(fit_scale, 0.01)`. Pan is
  adjusted so the anchor point stays over the same image pixel, then
  clamped.
- `clamp_pan` limit per axis = `max((natural·scale − viewport)/2, 0)`.
- Wheel: plain wheel **pans**; with `ctrl` held it zooms by `scale ·
  exp(clamp(dy·0.0025, −2, 2))`. `ScrollDelta::Lines` → ×40 px.
- Pinch: accumulates native deltas from the gesture start scale (web:
  `wheel` with `event.ctrlKey` is the browser's synthesized pinch
  gesture — no separate pinch handler needed on trackpads/most browsers;
  note in Comments if a target browser needs a real `gesturechange`
  listener).
- Drag: a click becomes a drag past **4px** of movement (`hypot >= 4.0`).
- Image element: absolute at the computed origin, `w(natural.w·scale)
  h(natural.h·scale)`, `ObjectFit::Contain`.
- Double-click: not documented with a distinct behavior beyond the
  desktop's standard click-drag/wheel-zoom in the sources read for this
  ticket — implement double-click as a fit↔2x zoom toggle centered on the
  click point ONLY if it doesn't conflict with the click-to-close rule
  above (double-click's first click must not close the lightbox); if in
  doubt, skip double-click and note the omission in Comments rather than
  inventing new behavior.

Gap row 61 (research §5): today's `.user-attachments-lightbox` is a plain
`<img>` overlay with none of the above — no pan, no zoom, no drag. This
ticket replaces it with the full `ImageView` geometry.

## 3. Pure logic to port

- `attachment_strip_height(count, inner_width) -> f32` (`composer.rs:296`)
  — see §2.1 formula. Add to `lib/attachments.ts` as
  `attachmentStripHeight`. Desktop test values to reproduce as unit tests
  (derive from the formula; the research does not list literal test
  fixtures for this function beyond the formula itself — construct at
  least: `count=0→0`; `count=1,width=768→12+56=68`; enough attachments to
  wrap to 2 rows at a given width → `12+2*56+8=132`).
- `with_attachments`/`parse_user_message_images`/`user_message_rail_text`
  (`attachments.rs:54-177`): the wire text-transport codec. Edge cases
  from `#[cfg(test)]` to reproduce as web unit tests: case-insensitive
  marker match; a marker with no valid `"- path"` lines is left as plain
  text; duplicate/invalid Appshot metadata degrades to an ordinary
  (non-Appshot) attachment (skip this specific case — Appshots are
  desktop-only, but confirm `parseUserMessageImages` doesn't choke on an
  Appshot-shaped marker, just treat it as an ordinary path list); round-
  trips through `ensure_extension`.
- `chunk_ranges(b64_len)` (`attachments.rs:330-344`): tiles a base64
  string into `UPLOAD_CHUNK_B64_CHARS`-sized, contiguous, non-overlapping
  ranges; an empty file still yields one empty range (the commit RPC
  needs the `uploadId` staged). Audit `uploadAttachments`'s chunking
  against this — add a unit test for the empty-file edge case
  specifically.
- `retry_delay(attempts)` (`attachments.rs:578-580`): `2s << min(attempts-1,3)`,
  capped 15s. **Already ported** as `retryDelayMs` in
  `attachment-cache.ts:29-31` — add the desktop's implied test cases:
  `retryDelayMs(1)=2000`, `retryDelayMs(2)=4000`, `retryDelayMs(3)=8000`,
  `retryDelayMs(4)=15000`, `retryDelayMs(10)=15000` (capped).
- `ensure_extension(name, format)`: appends the format's extension unless
  the name already carries a plausible 2-5 char alphanumeric extension.
  Test the boundary: a 1-char "extension" gets the real one appended; a
  6-char one also gets it appended (only 2-5 chars counts as plausible).

## 4. Gaps this ticket closes

| Item | Kind | Desktop value | Web value (file:line) | Fix |
| --- | --- | --- | --- | --- |
| Staged-attachment strip UI differs | WRONG VALUE | 56px thumbnails only, no filename shown, remove button HOVER-revealed (`opacity 0→1` on `group-hover`) (`composer.rs:4636-4727`) | 56px thumbnails (matches) but ALWAYS shows a filename label under each (`composer-staged-name`) and an ALWAYS-VISIBLE remove button (`attachment-strip.tsx:249-276`, `app.css:2151-2201`) | Hide the filename row; hover-reveal the remove button (`opacity:0` → `:hover{opacity:1}` scoped to `.composer-staged-row:hover`) |
| Remove button chrome | WRONG VALUE | `bg theme.bg`, `shadow_sm`, **no border**, icon `CLOSE_CIRCLE` 14px `text_muted` | `bg var(--rb-raised)`, 1px `--rb-border-strong`, a literal `×` glyph at 12px (`app.css:2171-2192`, `attachment-strip.tsx:271`) | Match desktop: no border, `theme.bg` background, `shadow_sm`, `closeCircle` icon at 14px |
| Filename caption under each thumb | INVENTED | desktop shows no name in the strip | `.composer-staged-name` (`app.css:2194-2201`) | Remove |
| Inline "📎 Attach" button | INVENTED | desktop has exactly one attach affordance (the paperclip in the actions cluster) | `.composer-attach-button` (`attachment-strip.tsx:210-220`) — only rendered when `pickerRef` is absent, but `chat-page.tsx`/`composer.tsx` always passes one, so it is dead in practice | Remove the button entirely (it's already unreachable — delete the dead branch too) |
| Drop overlay text and scope | WRONG VALUE / WRONG SCOPE | Desktop's drop target is the WHOLE conversation column (`shell.rs#chat-dropzone`), veil text **"Drop to attach"**, `theme.scrim().opacity(0.4/0.6)` | `.composer-attachments-overlay` targets only `.composer-attachments`, text "Drop images to attach" (`attachment-strip.tsx:203-207`) | Widen the drop target to the chat column; fix the veil text to "Drop to attach" verbatim; use the scrim alpha values |
| Upload progress bar in composer strip | INVENTED | desktop publishes progress into the app's state and renders it in the TRANSCRIPT — the strip itself has no bar | `.composer-attachments-progress` (`attachment-strip.tsx:234-244`) | Remove from the strip; the strip clears instantly on submit (see next row) |
| Whole-send progress bar delays the optimistic echo | WRONG BEHAVIOR | No progress UI in the composer at all — attachments vanish from the strip the instant Send is pressed (`composer.rs:6114-6117`); progress instead renders as a per-thumbnail RING overlay on the transcript's optimistic echo (`transcript.rs:5118-5149`) | `attachment-strip.tsx:234-244` keeps the staged rows visible and renders one aggregate `Uploading… N%` bar under them until the send resolves (`composer.tsx:308-356`); no optimistic transcript echo appears until the whole upload+run completes | Clear the strip immediately on submit; publish an optimistic transcript row right away; move progress onto that row's thumbnails as a ring (needs the transcript layer's optimistic-echo support — coordinate with ticket 18/05 if it isn't landed yet; this ticket's strip-clearing half does not depend on it) |
| Transcript thumbnail size mismatch | WRONG VALUE | `112×80` (`transcript.rs:187-188`) | `88×64` (`.user-attachments-thumb`, `app.css:2249-2250`, research row 57) | Change to `112×80`, inner image `110×78` |
| Transcript thumbnail gap mismatch | WRONG VALUE | `8px` gap (`transcript.rs:4897`) | `6px` gap (`.user-attachments`, `app.css:2242`) | Change to `8px` |
| No sending/uploading overlay on transcript thumbnails | MISSING | Dark scrim + progress ring/spinner drawn over a `pending://`-referenced thumbnail while the upload streams (`transcript.rs:4901-4930,5118-5149`) | `user-attachments.tsx` only has `loading`/`loaded`/`error` states; there is no "sending, with a known percent" state because nothing publishes an optimistic echo before upload completes (same root cause as the progress-bar row above) | Depends on the optimistic-echo fix above; add the `pending://` overlay state to `ThumbContent` once that lands |
| No LRU/byte-budget eviction on the attachment cache | MISSING | 64 MiB encoded-byte budget with LRU eviction + a "protected" (currently-visible) shield set (`attachments.rs:582-654`) | `state/attachment-cache.ts` is an unbounded `Map` with no eviction at all | Add the byte-budget + LRU eviction pass and `protectAttachments`, per §2.2 |
| Lightbox has no pan/zoom | WRONG BEHAVIOR | full-viewport scrim `scrim_alpha(0.7)`, image box `90%w × 85%h`, pan/zoom (ctrl+wheel exp zoom, pinch, drag past 4px), filename at `ink(0.45)` 11px, Escape + non-drag click close | a plain `<img>` overlay (`app.css:2296`, research row 61) | Build the new shared `Lightbox` component per §2.4 |
| Undo coalescing / caret blink / paste forwarding | PARTIAL, composer-input concerns | N/A — cross-reference only | `onPaste` lives on the strip wrapper (`attachment-strip.tsx:190`) — never reached from the textarea, so pasting an image while the CARET is in the textarea does nothing today | Forward the textarea's paste event into the strip's paste handler (or hoist the paste listener to the composer pill's container so both text-paste and image-paste land regardless of focus) |
| Accepted formats enforcement | VERIFY | `attachments::format_by_extension`; non-images skipped **silently** | an `accept=` list + an `onError` toast per rejected file (`attachment-strip.tsx:68-71,195`) | Skip silently — remove the `onError` toast for a plain "wrong format" rejection (keep it for genuine failures: oversize, unreadable bytes) |

## 5. Do not

- Do not build Appshots (`render_appshot_strip`, OS window/screen capture,
  `AppshotPresentation`, `stage_png_bytes`) — no browser equivalent exists
  for capturing arbitrary application windows; this is permanently
  desktop-only.
- Do not build the review-comments chip (`render_comments_chip`,
  `comments.rs`, `comment_ui.rs`) even though it sits in the exact same
  composer body right next to the attachment strip. For reference only
  (transcribed here so this ticket's implementer recognizes it and leaves
  it alone — it is ticket 23's scope):
  - **Comments chip** (`render_comments_chip`, `composer.rs:4608`): shown
    when `state.review_comments(&current_key)` is non-empty. Wrapper
    padding `px(STRIP_PAD_X=16), pt(STRIP_PAD_TOP=12)`; pill height
    `badges::BADGE_HEIGHT = 24`; `8px` radius, `ink(0.06)` bg, `12px` text
    `theme.text_muted`, `FontWeight::MEDIUM`; icon `CHAT_ROUND_LINE` 12px
    `theme.text_muted.opacity(0.7)`. Text: `chip_label(count)` =
    `"1 comment"` / `"{n} comments"`. No hover card (`details: Vec::new()`
    — the staged set is visible in the Changes pane). Strip height
    contribution: `comment_strip_height(count)` = `0` or `36`.
  - **Diff-line adder/draft/card** (`comment_ui.rs`, wired from
    `changes.rs`): a `16×16` "+" button on diff-line hover, a `116px`-tall
    inline draft card with a `ComposerInput`, and a staged-comment card
    inline in the diff. None of this exists on web today; it belongs to
    the Changes surface (ticket 22) plus ticket 23's chip/badge half.
  - **Transcript badge** (`badges::render` + `comments::extract_badge`):
    turns a sent message's trailing comment block back into a pill. Also
    ticket 23.
- Do not build queue-row thumbnails, drag reorder, or edit-lease UI —
  ticket 16's scope. This ticket only adds the eviction infrastructure to
  the SAME cache ticket 16 reads from.
- Do not touch `composer.tsx`'s layout, pickers, compact/expanded flip, or
  send-button chrome beyond the narrow strip-clear-timing fix named in the
  file table — ticket 13's scope.
- Do not invent a generic non-image file-attachment type, a file-type icon
  resolver, or a MIME allowlist broader than the seven formats listed
  above.
- Do not add double-click-to-zoom or pinch-specific JS gesture handling
  beyond what §2.4 asks for if it isn't clearly specified — prefer
  documenting the gap in Comments over inventing exact numbers the
  research doesn't give.

## 6. Acceptance

- [ ] Staged strip: 56px thumbnails, no filename caption, remove button
      hidden until hover (`opacity:0` → `1`), no attach button inside the
      strip, no progress bar inside the strip.
- [ ] `attachmentStripHeight(count, innerWidth)` implemented in
      `lib/attachments.ts` and used to reserve the composer's layout space
      (matches the formula in §2.1/§3 exactly, including the `per_row`
      floor-division and `usable = max(innerWidth - 32, 56)` clamp).
- [ ] Dropping a file anywhere on the chat column (not just the strip)
      shows the "Drop to attach" veil and stages the file on drop.
- [ ] Pasting an image while the textarea has focus stages it (paste
      forwarded from the textarea, not just the strip wrapper).
- [ ] Submitting a message with staged attachments clears the strip
      immediately (not after the upload/run resolves).
- [ ] Transcript thumbnails render at `112×80` (inner image `110×78`),
      `8px` gap, `justify-end` wrap — verified against a chat with 3+
      attachments on one user turn (wraps to a second row).
- [ ] Transcript thumbnail states: loaded (solid hairline border),
      loading (pulsing skeleton, `ROBOCO_PULSE` 2400ms wave), error
      (dashed border).
- [ ] Clicking a loaded staged thumbnail or a loaded transcript thumbnail
      opens the SAME `Lightbox` component.
- [ ] Lightbox: scrim opacity 0.7, image box 90%×85% of viewport, never
      upscales past natural size, ctrl+wheel zooms (bounded
      `min(131072/max(w,h), 32)` and down to `min(fit_scale, 0.01)`),
      plain wheel pans, drag past 4px pans, click on the scrim (not a
      drag-end) closes, Escape closes.
- [ ] `state/attachment-cache.ts` evicts the globally-oldest non-protected
      entry once total loaded bytes exceed 64 MiB; `protectAttachments`
      shields on-screen keys from eviction.
- [ ] Upload timeout constants audited and correct: `FIRST_CHUNK_TIMEOUT`
      90s, `CHUNK_TIMEOUT` 30s, `COMMIT_TIMEOUT` 150s,
      `READ_CHUNK_TIMEOUT` 20s, whole-attachment deadline `min(120 +
      15*chunks, 900)`s.
- [ ] The two verbatim upload-failure strings appear where they belong:
      `"Couldn't stage the attachment locally."` and `"Couldn't upload the
      attachment — the device may be offline."` (or documented in
      Comments if the web's single-engine model only ever hits one path).
- [ ] Unit tests: `attachment_strip_height` → `attachments.test.ts`
      (new cases per §3), `retry_delay` → same file (5 cases per §3),
      `ensure_extension` boundary case, a `chunk_ranges` empty-file case,
      the `with_attachments`/`parse_user_message_images` round-trip edge
      cases from §3.
- [ ] Screenshot pair, desktop vs web, states: staged strip with 2
      thumbnails (one hovered showing the remove button), drop-veil
      active, a sent user turn with 3 attachments (loaded), a sent turn
      with one attachment mid-upload (progress ring), the lightbox open
      and zoomed in.
- [ ] `pnpm -r build` green; package vitest green.
- [ ] No new literal hex/px where a `--rb-*` token exists.

## Comments

**Landed** (branch `wp2/17-attachments`):

- **Staged strip** (`attachment-strip.tsx`): wrap grid of bare 56px thumbs
  (gap 8, px 16, pt 12), frame 56/r8/1px `hairline(0.10)` + explicit 54×54
  r7 cover img, hover-revealed remove (18px circle at −6/−6, `bg var(--rb-bg)`,
  `shadow_sm`, no border, `closeCircle` 14px `text_muted`), thumb click opens
  the shared Lightbox, remove `stopPropagation`s first. No filename, no
  attach button, no progress bar. Non-images skip silently; oversize/read
  failures still surface via `onError`.
- **`attachmentStripHeight`** moved into `lib/attachments.ts` (with the
  STRIP_* constants); `lib/composer-flip.ts` re-exports it so ticket 13's
  importers and its test keep one address. Live-DOM check: 12 staged thumbs
  measure exactly 132px (12 + 2·56 + 8) at the 768px column.
- **Drop**: the veil was NOT rebuilt — ticket 06 had already landed the
  shell's `#attachment-drop-overlay` (app-shell.tsx, with a comment deferring
  the file handling to this ticket). This ticket wires the STAGING: a native
  `drop` listener on the strip's `.chat-column` ancestor (the web's
  `#chat-dropzone`) feeds `ingest`; the shell's `main.panel` listener still
  swallows the browser default. Also fixed the shell veil's scrim alpha to
  the code-faithful net values — see the deviation note below.
- **Send path** (`composer.tsx`, narrow): the echo now publishes
  `pending/{id}/{name}` refs from the first frame, the staged bytes are
  seeded under those refs (echo thumbs render loaded instantly), the
  whole-send percent lives in the new upload-progress store
  (`beginUploadProgress`/`setUploadProgress`/`endUploadProgress` /
  `uploadProgressPercent`, port of `AppState.begin_upload_progress`), and
  upload failures surface the verbatim string. The strip clears
  synchronously on submit (ticket 13 already did; verified live).
- **Transcript thumbs** (`user-attachments.tsx`): 112×80 frame (110×78 r7
  cover img), 8px gap, `w_full justify_end wrap` with `px(4) pt(4) pb(6)`;
  states loaded (solid `hairline(0.11)` / `ink(0.035)`), loading (pulsing
  skeleton on the ROBOCO_PULSE 2400ms wave), error (dashed `hairline(0.14)`
  empty thumb — the old "!" caption is gone); `pending://`/`pending/` refs
  draw the sending overlay (pulsing `0.38+0.05·pulse` scrim + 34px
  `upload_progress_ring` (2.5px stroke, white 0.22 track / 0.95 arc,
  clockwise from 12, percent label) or the glyph spinner when no percent is
  known). Row-level retry: an errored thumb schedules its own re-attempt
  when `retryIn` lands. Errors' snapshots are second-bucket-cached so the
  countdown re-renders at 1Hz, not per paint.
- **Cache** (`state/attachment-cache.ts`): 64 MiB byte budget, LRU by
  last-used tick (bumped on every snapshot read), eviction of the globally
  oldest non-protected entry on every loaded insert, `protectAttachments`
  (wholesale replace) fed by a ref-counted mounted-strip registry — the web
  peer of the desktop's per-row-sync call, tracking the virtualizer's
  mounted rows.
- **Lightbox** (`components/lightbox.tsx`, new): full-viewport scrim
  (`scrim_alpha(0.7)` → `calc(var(--rb-scrim-alpha) · 0.7 / 0.6)`), 90%w ×
  85%h viewport, 12px gap, filename 11px `ink(0.45)` with ellipsis,
  "Loading image…" while decoding; pan/zoom ported whole from
  `image_viewer.rs` (fit never upscales; zoom bounded
  `min(131072/max(w,h), 32)` / `min(fit, 0.01)` with anchor preservation;
  clamp `max((natural·scale − viewport)/2, 0)`; plain wheel pans, ctrl+wheel
  zooms `exp(clamp(±dy·0.0025))`; drag arms past 4px; non-drag click closes
  anywhere; Escape closes with focus returned to the caller; wheel is
  swallowed via a non-passive native listener). Mounted through a portal to
  `document.body` — the composer pill's `backdrop-filter` forms a containing
  block that would otherwise clip a fixed child. Both the staged strip and
  the transcript open this one component; the old inline
  `.user-attachments-lightbox` is deleted.
- **Transport audit** (`lib/attachments.ts`): chunk retry ladder FIXED —
  3 attempts per chunk (was 2) staggered `50ms · attempt · (seq+1)` (was
  `50 · (seq+1)`), matching `attachments.rs:388-417`; the whole-attachment
  deadline message is now `"attachment upload exceeded {N}s"`; all timeout
  constants (90/30/150/20s, `min(120+15n, 900)`) audited correct.
  `chunkRanges` exported for tests.

**Deviations / judgment calls:**

- **Drop-veil alphas**: the ticket transcribes "0.4 light / 0.6 dark", but
  the cited line (`shell.rs:6132`) actually computes
  `theme.scrim().opacity(0.4 / 0.6)` — black at 0.4 in dark and at
  `0.32 · (2/3) ≈ 0.213` in light (light scrim base is 0.32). Implemented
  the code-faithful values as `calc(var(--rb-scrim-alpha) · 2 / 3)` in
  ticket 06's existing veil rule.
- **Upload failure strings**: only `"Couldn't upload the attachment — the
  device may be offline."` is reachable — the web's single-engine model
  always targets the paired (possibly remote) engine, so the desktop's
  local-stage path (`"Couldn't stage the attachment locally."`) has no web
  equivalent (documented on `AttachmentUploadError`). The queue-edit commit
  string is not applicable either: the web's queue-edit lease carries no
  attachments today (ticket 16 owns that path). One existing
  `composer-actions` test asserted the old invented name-bearing message;
  updated to assert the verbatim string.
- **Per-attachment transfer percent**: the desktop's first percent source
  (the attachment's own relay transfer via `WatchTransfers` + the
  `pending://<uploadId>` ref) has no web stream; the overlay reads only the
  send-wide percent, then the spinner — the legacy-flow order the desktop
  itself falls back to. Large files also base64-encode on the main thread,
  so the spinner (not the ring) can show for the first ~1s of a huge send.
- **UserAttachmentThumb device fallback**: the desktop tries the chat's
  host device then local; the web's single-engine model only ever has one
  device id — documented simplification in the component.
- **Pinch**: no `gesturechange` listener — the browser's synthesized
  ctrl+wheel pinch is handled by the ctrl+wheel path, as the spec's own note
  anticipates. Double-click zoom skipped per §2.4's "if in doubt, skip".
- **Latent crash fixed** (found by this ticket's smoke run, pre-existing):
  `getAttachmentSnapshot` returned a fresh object per call for every state,
  which `useSyncExternalStore` turns into React error #185 (max update
  depth). It was unreachable before this ticket because no code path ever
  rendered attachment rows from a cold cache. Snapshots are now
  identity-cached: one shared loading singleton, per-image loaded cache,
  per-second-bucket error cache. Verified by reload-with-persisted-
  attachments on the production bundle.

**Verification:**

- `pnpm -r build` green (final bundle); package vitest **799/799** (51
  files) — including the new cases: `attachmentStripHeight` (5),
  `retryDelayMs` (5), `ensureExtension` 6-char boundary, `chunkRanges`
  empty-file + tiling, `AttachmentUploadError` verbatim message, appshot-
  shaped marker, codec round-trip via `stageBytes`, cache eviction (4) +
  wholesale protection, upload-progress percent, error-snapshot stability,
  and a `lightbox.test.ts` port of `image_viewer.rs`'s geometry suite
  (fit/anchor/bounds fixtures).
- web_smoke boot check (production bundle, fresh load): app renders with a
  persisted attachment-bearing transcript, no error boundary, thumbnails
  load to `data-state=loaded` via the ReadAttachmentChunk read-back path.
- Live DOM verification (desktop-width window, dark theme): staged thumbs
  56×56/r8/54×54/r7/cover; strip padding `12px 16px 0` gap 8 wrap; remove
  18×18/r50%/no-border/`-6,-6` with opacity 0→1 on hover; transcript thumbs
  112×80/r8/border+bg exact, img 110×78/r7, strip pad `4 4 6` gap 8
  justify-end; 12-thumb wrap = 178px (4 + 2·80 + 8 + 6) over two rows; the
  ring captured mid-upload at 73%; lightbox frame z70/scrim 0.7/gap 12,
  image box 1728×803 of 1920×945, fit at natural 320×220 (no upscale),
  ctrl+wheel zoom to 787×541 (e^0.9), pan clamped, Escape closes and focus
  returns to the caller thumb; strip clears synchronously on submit.
  Drag-to-pan and click-to-close in the lightbox are code-reviewed ports
  (geometry unit-tested) but not live-clicked.

**Screenshots** (web, `C:\Users\ADMIN\Desktop\nguyenvu\roboco\.scratch\web-parity\shots\17\`):

- `01-web-staged-2.png` — staged strip, 2 thumbnails
- `02-web-staged-hover-remove.png` — same, one hovered (remove revealed)
- `03-web-drop-veil.png` — drop veil active over the chat column
- `04-web-mid-upload-ring.png` — sent turn mid-upload, ring at 73%
- `05-web-loaded-4-thumbs.png` — sent turn loaded, 4 attachments
- `06-web-staged-wrap-12.png` — staged strip wrapping 12 thumbs (132px)
- `07-web-transcript-wrap-12.png` — transcript strip wrapping 12 (2 rows)
- `08-web-lightbox-fit.png` — lightbox open at fit
- `09-web-lightbox-zoomed.png` — lightbox zoomed (2.46×) and panned

Desktop pairs **skipped**: `parity/shot.ps1` captures a running desktop
window, and no desktop session with staged attachments was available to
drive (staging there needs the native picker). The web sides above carry
the geometry numbers verified against the Rust citations.

