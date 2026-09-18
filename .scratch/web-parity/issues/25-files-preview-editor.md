# 25 — Files preview and editor

**What to build:** Opening a file from the tree (ticket 24) now shows a real
code view — gutter, line numbers, syntax-highlighted text at the correct
font/line-height formula, word-wrap toggle, horizontal scroll when wrapped
off — instead of a bare `<textarea>`/`<pre>`; Markdown files render through
the shared block renderer with the files-specific 900px max-width and a
2 MiB client clip; images pan/zoom/fit with wheel/pinch/drag instead of a
static `object-fit: contain` `<img>`. Closing a dirty file, or the disk
changing under an open file, now asks the user what to do (Retry/Keep
Open/Discard, Keep Editing/Reload) through the same banners the desktop
shows, instead of silently discarding edits.

**Blocked by:** 24 (Files tree and search)

**Status:** done

**Research:** `../../web-client/research/10-files-preview.md` §3.1–§3.4,
§4, §5 (rows quoted below); `../../web-client/research/09-files-tree-editor.md`
§3.4–§3.9 (breadcrumb/toolbar, tree-sidebar toggle — documented, not built,
see §5), §4 (`FileDocument` state machine, close lifecycle), §5 (rows
quoted below).

**Desktop reference (for lookups only):**
- `crates/ui/src/files/preview.rs` (4642 lines): `render_preview` (2064),
  `render_editor_header`→`render_breadcrumb` (2281), `render_tree_toggle`
  (2243), `render_document_body` (2648), `ensure_editor` (3062),
  `render_preview_line` (3108), `prepare_markdown_preview` (2533),
  document lifecycle banners (2064-2180), `renamed_document_path`/
  `path_is_same_or_descendant` (608-619), `on_editor_change` (1370),
  `request_editor_highlight` (1397-1467)
- `crates/ui/src/files/document.rs` (592 lines): `FileDocument`,
  `DocumentPhase` (20-31), `read_only_reason` (352-361),
  `writable_encoding`/`writable_line_ending` (363-380)
- `crates/ui/src/files/editor.rs`, `editor_adapter.rs`: `EditorMenuAvailability`
  (editor.rs:16-33), `editor_style` colors (editor_adapter.rs:124-134)
- `crates/ui/src/files/image_preview.rs`, `image_viewer.rs`,
  `image_media.rs`: `ImagePreview`, `ImageView` geometry (image_viewer.rs:21-341)
- `crates/ui/src/files/markdown_preview.rs` (1985 lines),
  `markdown_media.rs`: `MarkdownPreview`, `relative_target` (66-112),
  media limits, lightbox
- `crates/engine/src/workspace_files.rs`: size-limit constants
  (`MAX_EDITABLE_FILE_BYTES`, `MAX_PREVIEW_FILE_BYTES`, binary/encoding/
  line-ending detection)
- `crates/ui/src/shell.rs` (2788–3004, 7768–7784): `close_right_surface`,
  `on_file_close_ready`, `complete_file_close`, `reveal_unsaved_file`,
  `prepare_exit`, the `SaveFile` action (default `mod-s`)

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/components/files/file-viewer.tsx` | edit | `FileViewer`, `ViewerHeader`, `TextViewer`, `PhaseBanner`, `ReloadFromDisk`; replace the `<textarea>`/`<pre>` pair with the new code view, breadcrumb parity, save-status pill, word-wrap toggle, close/lifecycle banner, mount the tree-sidebar split (§2.5) around the document body |
| `web/packages/app/src/components/files/tree-split-panel.tsx` | new | `TreeSplitPanel` — the sidebar toggle button, split container, and resize handle from §2.5, hosting ticket 24's `FileTreePanel` inside the editor presentation |
| `web/packages/app/src/lib/tree-split.ts` | new | `clampTreeWidth`, `narrowTreeWidth`, `isWide`, the openness sampler (`sample`/`animateTo`) — pure logic for §2.5 |
| `web/packages/app/src/components/files/code-view.tsx` | new | `CodeView` — gutter + line numbers + syntax-highlighted, optionally editable text view (replaces the raw `<textarea>`/`<pre>`) |
| `web/packages/app/src/components/files/editor-context-menu.tsx` | new | `EditorContextMenu` (cut/copy/paste/select-all popover, documented in ticket 24 §2.4, built and mounted here) |
| `web/packages/app/src/components/files/image-view.tsx` | new | `ImageView` — fit/zoom/pan/pinch image viewer, replaces the plain `<img>` in `ImageViewer` |
| `web/packages/app/src/components/files/markdown-view.tsx` | edit | `MarkdownView`; files-specific max-width, code-fence highlighting/copy button, task-checkbox toggling, heading-anchor scroll, 2 MiB client clip banner |
| `web/packages/app/src/lib/file-document.ts` | edit | `FileDocument`; add autosave scheduling, `ExternallyModified`↔`Conflict` "Keep Editing" transition, close-lifecycle hooks (`prepareClose`, `allFileEditsFlushed`) |
| `web/packages/app/src/lib/markdown-doc.ts` | edit | `parseMarkdown`, `resolveWorkspacePath`; align `relative_target` semantics (percent-decoding, `..`/`.` resolution against the document's directory, rejection rules), 2 MiB clip |
| `web/packages/app/src/lib/files.ts` | edit | add the `"Large file preview is truncated and read-only."` truncated-banner condition (`file.truncated && text != null`) alongside existing `readOnlyMessage` |
| `web/packages/app/src/lib/image-geometry.ts` | new | `fitScale`, `resize`, `clampPan`, `zoom`, `panBy`, `imageOrigin` — ported from `image_viewer.rs` |
| `web/packages/app/src/lib/syntax.ts` | edit | extend the transcript's tokenizer for reuse by `CodeView` (or confirm it already generalizes; wire it in either way) |
| `web/packages/app/src/state/shortcuts.ts` | edit | add a `Save` shortcut entry (`mod-s`), scoped to "route is Chat and the right pane shows a Files/File surface" |
| `web/packages/app/src/styles/app.css` | edit | `.files-editor` → `.files-code-view` (gutter/line/highlight classes), `.files-viewer-header` (icon, per-segment tooltip, save-status pill), `.files-banner*` (lifecycle/close banners), `.files-image` → pan/zoom transform classes, `.markdown` files-specific width override, `.files-editor-menu` (new), `.files-tree-toggle`, `.files-split-panel`, `.files-split-handle` (new, §2.5) |

## 1. Context a fresh session needs

- `FileViewer` (`components/files/file-viewer.tsx`) is the pane this
  ticket rebuilds. It already gets `document: FileDocument | null` from
  `FilesBody`/`FilesSurface` (ticket 24's file, unchanged by this ticket)
  and renders one of `ImageViewer` (image paths) or `TextViewer` (all
  else, including Markdown).
- `FileDocument` (`lib/file-document.ts`) is an observable class matching
  the desktop's `document.rs` state machine closely already: phases
  `loading`/`ready`/`saving`/`saveFailed`/`conflict`/
  `externallyModified`/`deletedOnDisk`/`readOnly`/`error`; `isDirty`,
  `isEditable`, `canSave` are already ported verbatim. Do not rewrite the
  state machine — extend it (autosave scheduling, the missing "Keep
  Editing" transition, close-lifecycle hooks).
- There is currently **no code-editor component at all** — `TextViewer`
  renders a plain `<textarea>` when editable, a plain `<pre>` when
  read-only. This ticket's `CodeView` component is new: a gutter + line
  numbers + syntax-highlighted text view that stays editable via a hidden
  `<textarea>` (or a `contentEditable`/overlay approach) — CodeMirror/Monaco
  are explicitly out of scope per the research (§6: "pick a browser-native
  or JS text-editor component... or a plain `<textarea>` as today"); a
  hand-rolled gutter+highlight-overlay-over-`<textarea>` is the intended
  shape here, matching the desktop's font/line-height formula and read-only
  virtualized-list fallback, not a full editor-component replacement.
- `lib/syntax.ts` already exists and is wired into the transcript's code
  fences; check whether its tokenizer is generic enough to run over an
  arbitrary file's text keyed by extension/language, and reuse it here
  rather than writing a second highlighter. If it needs generalizing
  (e.g. it currently only knows fence-declared languages), extend it; do
  not fork a parallel implementation.
- The breadcrumb/toolbar this ticket rebuilds
  (`ViewerHeader`→§2.2 below) is the desktop's `render_breadcrumb`
  (`preview.rs:2281`), which is shared chrome for both the "Files" browser
  tab and the promoted "editor" tab that this ticket now also builds
  (§2.5) — the bar is 1:1 per `spec.md`, so the split is not an accepted
  web simplification.
- `Theme::TITLEBAR_HEIGHT`/`surface_chrome` metrics (38px header, 24px
  controls, 6px radius, 4px gap) are shared with ticket 24's tree header —
  reuse the same CSS custom properties/utility classes rather than
  duplicating magic numbers.
- Vocabulary and token rules from `spec.md` apply throughout: `var(--rb-*)`
  colors, `rgb(var(--rb-wash) / a)` washes, no literal hex/px where a token
  exists.
- The desktop's inline review-comment overlays (gutter add-button, floating
  comment card/draft, `EDITOR_COMMENT_*` constants) are **ticket 23**'s
  concern entirely. This ticket only needs to leave a hook point: wherever
  the gutter is rendered per visible row, make sure a later ticket can slot
  a per-row affordance in without restructuring `CodeView`'s row-rendering
  loop. Do not build any comment UI here.

## 2. Spec

### 2.1 Code preview / editor body (`preview.rs::render_document_body`,
`ensure_editor`, `render_preview_line`)

The same underlying text state renders both the read-only "preview" and
the editable buffer on desktop — there is no separate non-editable
widget; port that as: `CodeView` always renders gutter + lines the same
way, and toggles only whether the text layer accepts input.

**Layout** — editable container:

| Property | Value | Source |
| --- | --- | --- |
| flex | fills the viewer body | preview.rs:2696-2698 |
| font | `theme.font_mono` | preview.rs:2701 |
| text size | `editorFontSize` setting (default 13.0, range 9.0–24.0) | preview.rs:2702; settings.rs:56-58 |
| line height | `max(editorFontSize + 8.5, PREVIEW_LINE_HEIGHT=20.0)` | preview.rs:2703-2705 |
| initial list estimated height | 520.0px (placeholder before first measurement) | preview.rs:178 |
| initial measured-width estimate | 520.0px (placeholder before first layout) | preview.rs:180 |

**Layout** — read-only line list (`render_preview_line`):

| Property | Value | Source |
| --- | --- | --- |
| row min height (wrap off) | `PREVIEW_LINE_HEIGHT = 20.0`px, fixed | preview.rs:34,3147,3153 |
| gutter width | 48.0px, right border `theme.border.opacity(0.55)` | preview.rs:3159-3164 |
| gutter text | mono, 10.0px, `theme.text_faint.opacity(0.7)`, right/bottom-aligned | preview.rs:3166-3171 |
| code cell padding | `pl(12.0)`, `pr(18.0)` | preview.rs:3178-3179 |
| code text | mono, 11.5px, base color `theme.text.opacity(0.93)` with syntax highlight runs | preview.rs:3181-3183,3143 |
| word-wrap on | code cell `flex_1 min_w_0 py(2.0)`, whitespace normal; row `w_full items_stretch` (no fixed height) | preview.rs:3150-3151,3175-3176 |
| word-wrap off | row `h(20.0) min_w_full items_center`; code `whitespace_nowrap` | preview.rs:3151-3156,3180 |

Editor foreground `theme.text.opacity(0.93)`; muted fg `theme.text_faint`;
background transparent; selection `theme.accent.opacity(0.22)`; caret
`theme.caret`; active-line highlight `wash(0.025)`; gutter background
transparent.

**Large-file / binary handling** (from `WorkspaceFileText`, server-side
limits, `crates/engine/src/workspace_files.rs`):

| Limit | Value | Effect |
| --- | --- | --- |
| `MAX_EDITABLE_FILE_BYTES` | 1 MiB | size > this ⇒ `readOnlyReason = tooLarge`, text is still returned — previews but cannot be saved |
| `MAX_PREVIEW_FILE_BYTES` | 8 MiB | size > this ⇒ no text at all, `readOnlyReason: tooLarge`, `truncated: true` |
| binary detection | any `\0` byte | `encoding: binary`, `readOnlyReason: binary`, no text |
| encoding | UTF-8/UTF-8 BOM only | else `readOnlyReason: unsupportedEncoding`, no text |
| line endings | `Mixed` (bare `\r` or inconsistent `\r\n`/`\n`) | `readOnlyReason: mixedLineEndings`; text still returned, read-only |
| symlink / non-regular | filesystem metadata error | `readOnlyReason: symlink`/`notRegularFile`, no text |

Read-only reason → message (already ported verbatim as
`lib/files.ts::readOnlyMessage` — no change needed there):
`binary`→"Binary files cannot be previewed.", `unsupportedEncoding`→"This
file encoding is not supported.", `symlink`→"Symlink targets are
read-only.", `permissionDenied`→"Permission denied.", `tooLarge`→"This
file is too large to preview.", `mixedLineEndings`→"Files with mixed line
endings are read-only.", `notRegularFile`/none→"This file cannot be
previewed."

If `file.truncated && file.text != null` (reachable today only via the
Markdown client-side 2 MiB clip, §2.3 — the server's own `truncated`
always pairs with no text), show a banner above the code scroll: 28px h,
`px(10.0)`, bottom border, bg `theme.warning.opacity(0.045)`, text 10.0px
`theme.warning_muted`, **"Large file preview is truncated and
read-only."**

The two full-width banners shown above the document body (`render_preview`,
distinct from the breadcrumb's save-status pill in §2.2) share one style:

| Banner | Border | Background | Source |
| --- | --- | --- | --- |
| Lifecycle (close-requested / target-change pending, §3 "Close lifecycle") | `theme.warning.opacity(0.25)` | `theme.warning.opacity(0.055)` | preview.rs:2105-2106 |
| External-change / reload-confirmation (`conflict`/`externallyModified`/`deletedOnDisk`, today's `PhaseBanner`) | `theme.warning.opacity(0.25)` | `theme.warning.opacity(0.055)` | preview.rs:2171-2172 |

The existing `.files-banner-warning` class should carry these two values;
do not reuse the truncated-banner's `0.045` bg for either of these.

**States**

| State | Condition | What changes |
| --- | --- | --- |
| Loading | phase `loading` | centered **"Loading file…"**, `theme.text_faint` |
| Error | phase `error` | centered message, `theme.danger_muted` |
| Read-only, has text | not editable, text present | non-editable line list (gutter + lines), still copyable |
| Read-only, no text | text absent | centered `readOnlyMessage(reason)` |
| Editable | editable | real editing surface mounted, receives highlighting + autosave |
| Truncated | `truncated && text != null` | warning banner above the scroller |
| Saving/Save failed/Conflict/Deleted/Externally modified | phase variant | breadcrumb save-status pill + top banner (§2.2) |

**Interactions**
- Typing → marks dirty, re-highlights (120ms debounce is the desktop's
  figure; matching it exactly on web is a nice-to-have, not a hard
  requirement given the JS tokenizer's cost profile differs — pick a
  debounce that keeps typing smooth and note the chosen value in
  Comments), schedules autosave if enabled.
- Horizontal scroll (word-wrap off): native scroll; vertical wheel must
  still work over the horizontally-scrolling area (the desktop's
  `restrict_scroll_to_axis` workaround has no DOM equivalent — a nested
  wrapper with `overflow-x` on one element and `overflow-y` on its parent
  avoids the same problem natively).
- No find-in-file or goto-line — confirmed absent on desktop; do not add.
- Mod-S saves (see §2.2 shortcut wiring).
- Right-click opens `EditorContextMenu` (§2.4 of ticket 24, built here).

**Motion**: none.

**Data**: reads `FileDocument`'s `file`/`text` snapshot fields; per-line
highlight spans from `lib/syntax.ts`. Writes: keystrokes call
`document.edit(text)`, which bumps the revision, and (new) schedules
autosave per §2.5.

### 2.2 Breadcrumb / toolbar (`render_breadcrumb`, preview.rs:2281-2520)

**Layout**: `toolbar` row, `h(38.0)`, `px(8.0)`, `gap(4.0)`, border top +
bottom, background `theme.surface.opacity(0.26)` glass / `theme.surface`
opaque.

**Children (in order)**
1. File-type icon, 14px (`lib/file-icons.ts`, ticket 24).
2. Breadcrumb path: each segment 11.0px, `theme.text_muted` for the final
   segment else `theme.text_faint`, separated by `›` (`mx(4.0)`, 10.5px,
   `theme.text_faint.opacity(0.65)`); tooltip shows the full path after a
   350ms delay.
3. *(if Markdown)* toggle button: icon `FILE_CODE` when showing Markdown
   (tooltip **"Show Markdown code"**) or `EYE` when showing code (tooltip
   **"Preview Markdown"**); active state paints `wash(0.1)` background.
4. *(if a save-status phase)* a pill: `h(24.0) px(6.0) rounded(6.0)`,
   11.0px text, phase-colored:
   - `saveFailed` → **"Save failed"**, `theme.danger_muted`, clickable
     retry (calls `document.save()`), tooltip = the error text.
   - `conflict` → **"Save conflict"**, `theme.warning_muted`, not
     clickable, tooltip **"The file changed on disk. Your editor buffer
     was preserved."**
   - `deletedOnDisk` → **"Deleted on disk"**, `theme.warning_muted`,
     tooltip **"The file was removed on disk. Your editor buffer was
     preserved."**
   - `externallyModified` → **"Changed on disk"**, `theme.warning_muted`,
     tooltip **"The file changed on disk. Review it before saving."**
5. "Reveal file in tree" toolbar button, icon `FOLDER`, tooltip
   **"Reveal file in tree"** — expands ancestors and selects the file in
   the tree pane (ticket 24's `FileTreePanel`; this ticket only needs to
   call whatever "reveal a path" entry point ticket 24 exposes, or emit an
   event ticket 24's tree subscribes to).
6. Word-wrap toggle: icon `LIST`; active (`wordWrap == true`) paints
   `wash(0.1)` background and `theme.text` icon color, tooltip **"Disable
   word wrap"**; inactive tooltip **"Enable word wrap"**, icon
   `theme.text_muted`.

All toolbar buttons: 24×24, radius 6, hover `wash(0.14)`, tooltip after
350ms.

**Interactions**
- Markdown toggle flips `document.showMarkdown`; turning it off focuses
  the code view.
- Save-status pill click (only `saveFailed`, i.e. `retry == true`) →
  `document.save()`.
- "Reveal file in tree" → ticket 24's reveal entry point.
- Word-wrap toggle emits a change persisted to the ticket-03 settings
  store's `filesWordWrap`, reapplied to every open Files/File surface.

**Motion**: none.

**Text** (verbatim): `"Show Markdown code"` / `"Preview Markdown"`;
`"Save failed"` / `"Save conflict"` / `"Deleted on disk"` / `"Changed on
disk"`; their tooltip sentences above; `"Reveal file in tree"`; `"Disable
word wrap"` / `"Enable word wrap"`.

**Data**: reads `document.showMarkdown`, the ticket-03 store's
`filesWordWrap`; writes `FilesEvent`-equivalent word-wrap change up to the
store.

**Save shortcut wiring**: register a `Save` entry in
`state/shortcuts.ts` (default `mod-s`), scoped to "route is Chat and the
right pane shows a Files/File surface" — not a bare `window` `keydown`
listener local to `TextViewer` as today. This makes it consistent with
however ticket 12 (Keyboard) wires the rest of the shortcut catalog; if
ticket 12 hasn't landed a generic dispatch mechanism yet, add the listener
at the shortcuts-module level (not component-local) so ticket 12 can pick
it up without moving code.

### 2.3 Markdown preview (`markdown_preview.rs`)

Renders the **live editor buffer**, not the last-saved disk text.

| Aspect | Files Markdown preview | Transcript Markdown |
| --- | --- | --- |
| Max content width | `MAX_PREVIEW_CONTENT_WIDTH = 900.0` | `MAX_CONTENT_WIDTH = 736.0` |
| Source | live editor buffer, re-parsed after each keystroke | streamed/finalized message content |
| Link resolution | workspace-relative paths resolved against the open file's own path; opens sibling files in the same viewer | chat-scoped |
| Images/diagrams | resolved from the workspace via a read-image call (device-relative paths) | resolved from attachments |
| Row virtualization | per top-level block | separate transcript virtualization |

**Layout**: root fills the viewer body, `py(16.0)`, `theme.font_sans`,
`theme.text`. Each row: `pb(MD_BLOCK_GAP=12.0)`, content column centered,
`px(24.0)`, content itself `max-width: 900px` — **give the files Markdown
view its own width, distinct from the transcript's `.markdown{max-width:
46rem}` (736px)**. Initial row-list height estimate placeholder: 400.0px
(informational; not meaningful for a non-virtualized DOM list).

**Children (in order, per row)**: shared block renderer output — headings,
paragraphs, code fences (syntax-highlighted, with a copy button, "Copied"
reset after 1200ms), lists, blockquotes, tables, task-list checkboxes
(toggle writes into the live buffer via a single edit), images/diagrams,
links.

**States**

| State | Condition | What changes |
| --- | --- | --- |
| Loading | tree empty on first parse | `"Loading preview…"`, `theme.text_muted`, `px(24.0)` |
| Truncated | server `truncated` OR the client 2 MiB clip below | `"Large file preview is truncated and read-only."`, `theme.warning_muted` |
| Media loading/blocked | image/diagram not yet resolved or over the per-document limit | `"Loading image…"` / `"Rendering diagram…"` / `"Document image preview limit reached"` / `"Document diagram preview limit reached"` |
| Media error | fetch/decode failed | `{alt} — {error}` |

**Client-side truncation**: `MAX_MARKDOWN_BYTES = 2 MiB`. If the live
buffer exceeds this, clip at the nearest UTF-8 boundary and set
`truncated = true` even if the server never reported it. Port this into
`lib/markdown-doc.ts`'s parse entry point (or a wrapper `FileViewer` calls
before parsing) — currently `parseMarkdown(snapshot.text)` runs on the
full buffer regardless of size.

**Media limits** (`markdown_media.rs`/`markdown_preview.rs`):
- Rendered image/diagram height cap: 480.0px; resize target width is
  `clamp(panelWidth - 48, 1, 900)`.
- `MAX_MEDIA_ENTRIES = 32` distinct sources per document; the rest show
  "…limit reached" forever.
- `MAX_MEDIA_BYTES = 64 MiB` combined decoded-image budget per open
  document; a source that would exceed it errors with **"Document media
  preview memory limit reached"**.
- Images: `https://`/`http://` render directly; anything else resolves as
  a workspace-relative path via `relative_target(currentPath, source)`
  and is fetched via the chunked image RPC (already implemented,
  `WorkspaceFilesClient.readImage`, §Data below); 30s per-image timeout.
- Diagrams: fenced ```mermaid``` blocks render to SVG. **This is
  desktop-only tooling (a server/native render step) — treat Mermaid
  rendering as out of scope for this ticket unless a JS Mermaid renderer
  is trivially available; if skipped, note the gap explicitly in Comments
  rather than silently dropping it.**
- `relative_target` (`markdown_preview.rs:66-112`) semantics to match in
  `lib/markdown-doc.ts::resolveWorkspacePath`: strips a leading
  `roboco-file:` prefix (recursively), rejects absolute (`/`),
  scheme-qualified (`:`), or backslash paths, percent-decodes both the
  path and the `#anchor`, rejects decoded paths containing `\`, `:`, NUL,
  or a leading `/`, resolves `.`/`..` segments against the *directory* of
  the current document (not the document path itself), returns `null` if
  resolution walks above the workspace root or produces an empty path.
  Empty target (bare `#anchor`) resolves to the current document. Port
  these desktop test cases as unit tests:
  `"../a%20b.md#hello"` from `"docs/readme.md"` → `("a b.md",
  "hello")`; `"../secret"` from `"readme.md"` (nothing to ascend from) →
  `null`; `"%2Fetc/passwd"` → `null` (decodes to a leading `/`);
  `"https://example.com"` → `null`; `"#hello"` → `(document, "hello")`.

**Interactions**
- Click an inline image/diagram → lightbox (reuse `ImageView`, §2.4, sized
  from natural dimensions; Escape closes and restores focus).
- Click a link: same-file `#anchor` scrolls to the heading (slug =
  lower-case + spaces→`-`, de-duplicated with a numeric suffix on
  collision); a different-file workspace path opens that file in this
  same viewer (already the shape of `onOpenPath` today — keep it); mailto:
  always opens externally; anything else does not open an embedded
  browser/preview (that only happens from chat transcript links, out of
  scope here).
- Task checkboxes: clicking toggles `[ ]`/`[x]` directly in the live
  buffer via a single edit, only when the parsed source still matches the
  buffer and the document is editable.
- Code fence: copy button, "Copied" reset after 1200ms.

**Motion**: none beyond the shared code-fence/lightbox transitions.

**Text** (verbatim): **"Loading preview…"**, **"Large file preview is
truncated and read-only."**, **"Document image preview limit reached"**,
**"Document diagram preview limit reached"**, **"Document media preview
memory limit reached"**, **"Loading image…"**, **"Rendering diagram…"**,
**"Workspace image connection unavailable"**, **"Image path is outside
the workspace"**, **"Image preview timed out"**, **"Enlarge image"**
(aria-label), **"Open image link"** (tooltip), **"Mermaid diagram"** (alt
text, if Mermaid is implemented).

### 2.4 Image preview (`image_preview.rs` + `image_viewer.rs`)

**Layout**: fills the viewer body, centered, no checkerboard, no
dimensions label, no zoom-percentage readout — **none of those three
exist on desktop either; do not add them.**

While loading/erroring: centered text, `px(16.0)`, 12.0px,
`theme.text_muted`, either **"Loading image…"** or the error string
(**"This image was removed from the workspace."** on delete, **"Image
exceeds preview memory limit"** if decoded bytes exceed `MAX_MEDIA_BYTES =
64 MiB`, or an RPC error string).

**Fit/zoom/pan geometry** (`lib/image-geometry.ts`, new, ported from
`image_viewer.rs:21-115`):
- `fitScale = min(viewport.w/natural.w, viewport.h/natural.h, 1.0)` —
  fit-to-viewport, never upscale past 1:1 on initial load/resize.
- On resize: if currently "fitted" (the default and the state right after
  a fit), re-fit to the new viewport; otherwise re-clamp pan only (a
  manual zoom survives a resize).
- `zoom(scale, anchor)`: clamp `scale` to `[min(fitScale, 0.01),
  min(131072/max(natural.w,natural.h), 32.0).max(fitScale)]` — minimum
  zoom-out 1% (never below fit for tiny content), maximum zoom-in 32x,
  further capped so panel-space size never exceeds 131072px. Anchor the
  zoom under the cursor: recompute pan so the image point under the anchor
  stays put.
- `panBy(delta)`/`clampPan()`: clamp so the image can be dragged until its
  edge reaches the viewport edge, never further:
  `limit = max(0, (natural*scale - viewport)/2)`.
- `imageOrigin()`: `((viewport - natural*scale)/2) + pan` — always
  centered, then offset by pan.

**Interactions**
- Wheel + Ctrl/Cmd held → zoom anchored at the cursor; factor
  `scale *= exp(clamp(deltaY * 0.0025, -2.0, 2.0))` (wheel-up/forward zooms
  in).
- Wheel without modifier → pan (normalize `deltaMode: "lines"` to 40px/line
  first).
- Trackpad pinch → zoom anchored at the pinch center (use the Pointer
  Events API's multi-touch or a library-free two-finger gesture handler;
  accumulate a start scale/factor pair across the gesture).
- Left-mouse drag → pan; a 4px movement threshold must be crossed before a
  drag counts as a drag (suppresses accidental pans from being treated as
  clicks); a following click is swallowed whenever a real drag happened.
- Plain click (no preceding drag) inside the image does nothing in the
  in-panel viewer (no click-to-zoom); the lightbox variant (§2.3) passes a
  close-on-click-outside callback instead.
- Drag continuation must keep updating even if the pointer leaves the
  image's own hitbox — attach `pointermove`/`pointerup` on `window`, not
  just the image element.

**Data / lifecycle**
- `isImagePath(path)` — already ported verbatim in `lib/files.ts`, no
  change.
- Resolve the checkout id via `readFile` first (already the shape of
  `ImageViewer` today), then `WorkspaceFilesClient.readImage(path,
  checkoutId)` (already implemented, chunked, matches — no change needed
  to `files-client.ts` for this).
- `MAX_WORKSPACE_IMAGE_BYTES = 8 MiB` bounds the wire read (already
  enforced in `readImage`).
- Add a 30s load timeout → **"Image preview timed out"** (currently
  missing — relies on implicit browser/network timeouts only).
- `MAX_MEDIA_BYTES = 64 MiB` bounds the retained decoded image size — add
  a check against the decoded `Blob` size before creating the object URL.
- Suspend/reactivate on tab switch: releasing the object URL
  (`URL.revokeObjectURL`) when the viewer unmounts is already correct
  behavior in the current `ImageViewer` cleanup; keep it.
- No animated-frame flattening (GIF/WEBP→static PNG) and no SVG
  sanitization are required on web — the browser's native `<img>` sandbox
  already prevents script execution for `src=blob:...`, and this ticket
  never renders SVG markup via `dangerouslySetInnerHTML`/`<object>`. Do
  not add either.

### 2.5 Tree sidebar: toggle, split panel, resize handle (editor presentation)

When a file is open, the tree pane does not disappear behind a route
change — it collapses into a resizable sidebar beside the document, and a
toggle button shows/hides it. Build this; it is not an accepted web
simplification (`spec.md`'s bar is 1:1, and its decisions list carries no
exception for this surface).

**`render_tree_toggle`** (`preview.rs:2243`, desktop reference
`09-files-tree-editor.md` §3.5): fixed-width slot `CONTROL_SIZE +
EDGE_INSET` = 32.0px, `pl_0`, containing one 24×24 toolbar button with
icon `SIDEBAR_MINIMALISTIC`, 14px, `theme.text_muted`. Lives in the
breadcrumb/toolbar row (§2.2), as a fixed slot — unlike the Markdown/
word-wrap toggles, it is not animated itself.

**Interactions**: click toggles the sidebar; if this *hides* it, focus
moves to the document body (so a hidden search box in the collapsed tree
can't still receive keystrokes).

**Text** (verbatim): tooltip `"Hide files sidebar"` / `"Show files
sidebar"`.

**Split layout** (`preview.rs:35-39`, desktop reference `09` §3.6 and
`10` §3.5):

| Property | Value | Source |
| --- | --- | --- |
| Default width | `TREE_SPLIT_DEFAULT` = 286.0px | preview.rs:36 |
| Min / max width | `TREE_SPLIT_MIN` = 220.0px / `TREE_SPLIT_MAX` = 360.0px | preview.rs:37-38 |
| Wide-layout breakpoint | surface width ≥ `WIDE_BREAKPOINT` = 680.0px | preview.rs:35, 326-328 |
| Narrow-layout tree width | `(surfaceWidth * 0.44).clamp(152.0, treeWidth)` | preview.rs:475-477 |
| Resize-handle hitbox half-width | `TREE_SPLIT_HITBOX_HALF_WIDTH` = 10.0px (20px total hit target) | preview.rs:39 |
| Resize handle line | 1px, vertical gradient fade in/out around the drag point | preview.rs:3241-3258 |

**What changes at the `WIDE_BREAKPOINT` (680px)**:
- **Wide** (`is_wide()` true, surface width ≥ 680px): the sidebar defaults
  to **visible** unless the user has explicitly dismissed it this session
  (`tree_sidebar_dismissed`); it is resizable by dragging the handle
  within `[220, 360]`.
- **Narrow** (< 680px): the sidebar defaults **hidden**; when shown it is
  an overlay sized by the narrow-layout formula above instead of the
  free-drag width, and must be explicitly opened via the toggle.
- Crossing the breakpoint (a window/pane resize, not a user click) is a
  **layout-driven** visibility change — see Motion below, it is not
  animated.

**`preview_split_handle`** (`preview.rs:3214-3298`) — the draggable
divider between the document body and the tree sidebar:
- Hit target: 20px wide (±10px around the visible 1px divider line, per
  the hitbox half-width above), full panel height, positioned at the
  sidebar/document boundary.
- Drag behavior: dragging moves `tree_width` 1:1 with the pointer,
  clamped to `[TREE_SPLIT_MIN, TREE_SPLIT_MAX]` = `[220, 360]`; dragging
  past a limit triggers a bounce-back visual (`resize_drag_sample`/
  `resize_bounce_offset` in `motion.rs`, duration constant
  `RESIZE_EDGE_BOUNCE_MS`) — port the clamp; the edge-bounce cosmetic can
  use a CSS spring/overshoot approximation rather than the exact GPUI
  sampling function.
  the divider itself has no other required motion.
- Double-click the handle resets `tree_width` to the 286.0 default.
- Hover highlight: the divider's `border_strong` fades in on hover (an
  opacity blend, no fixed duration specified in source).
- **Persistence**: the desktop keeps `tree_width` and the sidebar's
  dismissed/visible flag as **purely local UI state on the `FilesSurface`
  — there is no persisted settings key for it.** (`09-files-tree-editor.md`
  §3.6 "Data: purely local UI state; no RPC"; no `tree_width` or
  `tree_sidebar` field appears anywhere in the settings model documented
  in `12-settings-shell-appearance.md` §3.0.) Match that on web: keep the
  split width and dismissed flag as in-memory component state (e.g. a
  `useState`/ref on the viewer), reset on remount — do **not** add it to
  the ticket-03 settings store.

**Motion**

| What animates | Trigger | Spec | From → to | Reduced motion |
| --- | --- | --- | --- | --- |
| Sidebar open/close width fraction | explicit `toggle_tree_sidebar` click | `motion::RESIZE` duration × `speed_scale()`, eased via `RESIZE.progress(t)` | 0.0 ↔ 1.0 openness | skips to target |
| Sidebar open/close on breakpoint crossing | layout change (not a user toggle) | **not animated** — jumps to the end value immediately | jump | n/a (already instant) |
| Resize-handle hover highlight | hover | opacity/border blend | `border_strong.opacity(0)` ↔ `border_strong` | n/a |
| Live drag | pointer move | none (1:1 tracking, clamped) | — | — |

**Data**: none — purely local UI state, as above; no RPC, no persisted
setting.

## 3. Pure logic to port

- **`FileDocument` state machine** — already ported closely. Desktop test
  names to check the port against (these should already pass; add any
  missing as new `lib/file-document.test.ts` cases rather than
  reimplementing the class):
  `programmatic_load_is_clean_and_user_edits_use_revisions`,
  `stale_key_or_generation_is_rejected`,
  `unsupported_and_truncated_files_are_read_only`,
  `save_snapshot_only_cleans_the_revision_it_captured`,
  `save_uses_the_read_checkout_identity_not_the_chat_metadata`,
  `failed_and_conflicting_saves_preserve_dirty_content`,
  `stale_save_result_cannot_clean_a_new_request`,
  `external_reload_is_clean_but_external_dirty_state_blocks_autosave`,
  `deletion_preserves_dirty_revision_and_stops_autosave`,
  `same_content_recreation_restores_disk_state_without_losing_dirty_edits`,
  `explicit_discard_resolves_dirty_state_for_lifecycle_exit`.
- **New: "Keep Editing" transition** — clicking "Keep Editing" on the
  `externallyModified` banner converts the phase to `conflict` (blocking
  a later save until an explicit reload) instead of the only options
  today (Reload only). Add a `FileDocument.keepEditing()` method.
- **New: autosave scheduling** — `autosaveEnabled`/`autosaveDelayMs` from
  the ticket-03 settings store drive a debounced `save()` call after
  edits go idle, only while `canAutosave` (`canSave && phase == 'ready'`,
  i.e. a `saveFailed` document can be manually retried but does not
  silently autosave again until a fresh edit returns it to `ready`).
- **`readOnlyMessage`, `writableEncoding`, `writableLineEnding`,
  `fileReadOnlyReason`** — already ported verbatim in `lib/files.ts`. No
  change.
- **`isImagePath`/`isMarkdownPath`** — already ported verbatim. No change.
- **`relative_target`** — port as described in §2.3 with the five test
  cases listed there, into `lib/markdown-doc.ts`.
- **Image geometry** (`fitScale`/`resize`/`clampPan`/`zoom`/`panBy`/
  `imageOrigin`) — port into `lib/image-geometry.ts`. Desktop test names
  to mirror: `fit_preserves_aspect_ratio_and_never_upscales`,
  `zoom_keeps_the_cursor_over_the_same_image_point`,
  `pan_zoom_and_resize_stay_bounded`,
  `wheel_requires_control_and_normalizes_lines`,
  `pinch_accumulates_native_deltas_and_a_drag_does_not_click`.
- **Tree split/breakpoint math** (new, e.g. `lib/tree-split.ts`, ported
  from `preview.rs:35-39, 94-133, 391-478`): `clampTreeWidth(width) =
  clamp(width, 220, 360)`; `narrowTreeWidth(surfaceWidth, treeWidth) =
  clamp(surfaceWidth * 0.44, 152, treeWidth)`; `isWide(surfaceWidth) =
  surfaceWidth >= 680`; an openness sampler (`sample`/`animateTo`) that
  lerps 0..1 over the resize-motion duration for an explicit toggle, but
  returns the end value immediately (no animation) when the visibility
  change came from a breakpoint crossing rather than a toggle. Desktop
  tests to mirror: `sidebar_layout_changes_are_immediate_without_a_user_toggle`,
  `sidebar_motion_reverses_from_its_current_width`,
  `sidebar_motion_snaps_when_reduced_motion_is_enabled`.
- **Close lifecycle** (new, in `lib/file-document.ts` or a small
  coordinator alongside it): `prepareClose()` returns `"allow"` (no dirty
  docs), `"pending"` (autosave-capable docs are saving — wait for
  completion), or `"blocked"` (a doc is stuck in a phase that can't
  autosave — show Retry/Keep Open/Discard). This is what ticket 07's pane
  host should call before actually closing a file tab; expose it as a
  method the host can call, matching the desktop's `add_file_surface`/
  `on_file_close_ready`/`complete_file_close`/`reveal_unsaved_file`
  contract in shape (not necessarily identical names) — if ticket 07's
  tab-close API doesn't exist yet in a form this can hook into, implement
  `prepareClose`/the lifecycle banner as a self-contained behavior of
  `FileViewer` (closing via its own back/close button) and note the seam
  for ticket 07 to wire up later.

## 4. Gaps this ticket closes

| Item | Kind | Desktop value | Web value (file:line) | Fix |
| --- | --- | --- | --- | --- |
| Line numbers / gutter | MISSING | 48px gutter, mono 10.0px, right-aligned, per line | none — `<textarea>`/`<pre>` has no gutter (file-viewer.tsx:161-169) | Build in `CodeView` |
| Syntax highlighting in file preview | MISSING | tree-sitter-derived highlighting, per-line | none; `lib/syntax.ts` only wired into the transcript | Reuse/extend `lib/syntax.ts` for `CodeView` |
| Word-wrap toggle | MISSING | breadcrumb button, persisted, affects gutter/row layout | `.files-editor`/`.files-text-preview` hardcoded `white-space: pre` | Add toggle + `white-space: pre-wrap` mode |
| Horizontal scroll w/ vertical-wheel passthrough | MISSING | dedicated scroll-axis workaround | native scrolling | Verify manually once wrap toggle exists; nested-wrapper approach should just work |
| Adjustable font size / line height | MISSING | `editorFontSize` setting drives text size and `line_height = max(size+8.5, 20.0)` | hardcoded 13px/18px | Wire to the ticket-03 settings field |
| Large-file/binary/encoding/mixed-line-ending read-only reasons | MATCHES (message text) | table in §2.1 | `readOnlyMessage` ported verbatim | none |
| "Large file preview is truncated" banner | MISSING | shown when `text != null && truncated` | not implemented | Add (reachable via the Markdown 2 MiB clip) |
| Markdown live-buffer preview fidelity | WRONG BEHAVIOR (fidelity) | syntax-highlighted fences, copy button, task toggling, anchor links, Mermaid, workspace image resolution, lightbox zoom | own mini parser: none of the above | Each is addressed in §2.3; Mermaid may be deferred (note in Comments) |
| Markdown truncation at 2 MiB | MISSING | clips + truncated banner | `parseMarkdown` runs unconditionally | Add the clip |
| Markdown max content width | WRONG VALUE | 900px | `.markdown{max-width:46rem}` = 736px (the transcript's value) | Give the files view its own wider max-width |
| Image pan/zoom/fit (wheel+ctrl, pinch, drag, 1%-32x bounds) | MISSING | full gesture set | plain `<img>` with `object-fit: contain`, no interaction | Build `ImageView` from `lib/image-geometry.ts` |
| Image decode/size caps, 30s timeout | MISSING | 64 MiB retained cap, 8 MiB wire cap (already enforced), 30s timeout | no explicit caps/timeout on decode | Add the 30s timeout and decoded-size check |
| Checkerboard / dimensions label / zoom-% readout | N/A | does not exist on desktop either | not implemented | Do not add |
| Find-in-file, Goto-line | N/A | no such UI on desktop | not implemented | Do not invent |
| Breadcrumb byte-size badge | INVENTED | not present on desktop's breadcrumb at all | `<span className="files-viewer-meta">{formatBytes(file.size)}</span>` (file-viewer.tsx:67) | Remove, or explicitly keep as a documented web-only addition — default to removing for strict parity |
| Breadcrumb per-segment tooltip / file-type icon | MISSING | full-path tooltip + file-type icon | plain `title={path}`, no icon | Add both |
| Reveal-in-tree button | MISSING | toolbar button | none | Add, wired to ticket 24's tree |
| Save-status pill with per-phase color/tooltip/retry | WRONG BEHAVIOR (simplified) | dedicated pill, phase-colored, tooltip, inline retry | generic disabled "Save"/"Saving…" button + separate `PhaseBanner` | Build the pill; banner strip can stay as a second, complementary surface if desired, but the pill itself must match |
| Tree/editor split view + animated sidebar + resizable divider | MISSING | `render_tree_toggle` breadcrumb button; split panel with `TREE_SPLIT_DEFAULT`=286/`MIN`=220/`MAX`=360, `WIDE_BREAKPOINT`=680 (visible-by-default above, hidden overlay below); `preview_split_handle` drag/double-click-reset; sidebar open/close animates on an explicit toggle, jumps instantly on a breakpoint crossing; in-memory only, no persisted setting (§2.5) | separate panes/routes, no split at all | Build per §2.5: toggle button, split panel (reusing ticket 24's `FileTreePanel` inside it), resize handle, breakpoint behavior, open/close motion |
| Autosave | MISSING | opt-in, configurable delay | only explicit `save()` | Add scheduling per §3 |
| Close/unsaved-changes prompt | MISSING | inline banner (Retry/Keep Open/Discard); `reveal_unsaved_file` on quit routes to the dirtiest tab | none — changing path or unmounting discards edits silently, no `beforeunload` guard | Add `prepareClose` (§3) + a `beforeunload` guard at minimum |
| Save shortcut wiring | WRONG BEHAVIOR | global, customizable, scoped action | local `window` keydown listener, not customizable, not in the shortcuts catalog | Route through `state/shortcuts.ts` |
| Save-failed retry affordance | WRONG VALUE | the "Save failed" pill itself is the retry target | separate "Try again" button + inline error text | Align to the pill design (§2.2) |
| Conflict/changed-on-disk "Keep Editing" | MISSING | dismisses the banner, converts `externallyModified`→`conflict` | only Reload offered | Add the action (§3) |
| Discard-and-reload confirmation copy | WRONG VALUE | dedicated banner state, `"Discard unsaved changes?"` / `"Cancel"` / `"Discard & Reload"` | button label flips to `"Discard & reload?"` in place, no separate Cancel | Align the two-step UX and copy |
| "Reload from Disk" capitalization | WRONG VALUE | Title Case | `"Reload from disk"` (sentence case) | Match verbatim |
| Truncated-large-file banner | MISSING | `"Large file preview is truncated and read-only."` for `file.truncated` | folds into generic `"This file cannot be previewed."` | Special-case `truncated` in `readOnlyMessage`/the banner logic |
| Image read protocol (chunked, validated) | MATCHES | 8 MiB cap, 384 KiB chunks, identity/hash/offset continuity | ported near verbatim in `files-client.ts::readImage` | none |
| `readOnlyMessage`, `writableEncoding`, `writableLineEnding`, `fileReadOnlyReason` | MATCHES | see §3 | ported verbatim | none |

## 5. Do not

- Do not build inline review-comment overlays (gutter icon, floating
  card/draft) — ticket 23 owns the entire comments feature end to end.
  Only leave the gutter's row-rendering loop able to accept a later
  per-row slot (§1).
- Do not add a checkerboard background, dimensions label, or zoom-%
  readout to the image viewer — confirmed absent on desktop.
- Do not add find-in-file or goto-line — confirmed absent on desktop.
- Do not build SVG script/foreignObject sanitization — moot as long as
  SVGs are only ever rendered via `<img src=blob:...>`, never inlined
  markup.
- Do not implement the document byte-budget cache eviction
  (`MAX_RETAINED_DOCUMENTS`/`MAX_RETAINED_DOCUMENT_BYTES`) — that exists
  to bound memory across many simultaneously open native file tabs; moot
  until/unless ticket 07 gives the web multi-file-tab editing.
- Do not build the Settings → Files page — the settings fields
  (`filesEditorFontSize`, `filesWordWrap`, `filesAutosaveEnabled`,
  `filesAutosaveDelayMs`) live on the ticket-03 store (added by ticket 24);
  their settings-page UI is ticket 29's.
- Do not pick CodeMirror/Monaco or any third-party editor component
  without checking with the team first — the research explicitly frames
  this as an open choice and the ticket assumes a hand-rolled gutter +
  highlight view over a `<textarea>`-like input surface.
- Mermaid diagram rendering may be deferred if no lightweight JS renderer
  is readily available — if deferred, the banner/text states for
  "Rendering diagram…" and diagram errors should still exist so the UI
  doesn't silently omit fenced ```mermaid``` blocks; note the deferral in
  Comments rather than silently shipping without it.

## 6. Acceptance

- [ ] `CodeView` renders a 48px gutter with right-aligned line numbers,
      correct font/line-height formula (`max(fontSize+8.5, 20)`),
      highlighted text via `lib/syntax.ts`, word-wrap toggle switching
      between fixed-height/no-wrap and stretch/wrap rows, horizontal
      scroll with working vertical wheel when wrapped off.
- [ ] Breadcrumb: file icon, per-segment tooltip, Markdown toggle (when
      applicable), save-status pill (phase-colored, retry-on-click for
      `saveFailed`), "Reveal file in tree" button, word-wrap toggle — all
      per §2.2.
- [ ] Mod-S saves, routed through `state/shortcuts.ts`, not a local
      listener.
- [ ] Markdown preview: 900px max-width (distinct from the transcript's
      736px), code-fence highlighting + copy button, task-checkbox
      toggling into the live buffer, heading-anchor scrolling,
      workspace-relative link/image resolution via `relative_target`
      semantics, 2 MiB client clip with the truncated banner.
- [ ] `ImageView`: wheel+ctrl zoom anchored at cursor, unmodified wheel
      pans, drag-to-pan with a 4px threshold, 1%–32x zoom bounds, 30s load
      timeout, 64 MiB decoded-size cap.
- [ ] Close/unsaved-edits: `prepareClose()` exists and returns
      allow/pending/blocked; a blocked/pending close shows the
      Retry/Keep Open/Discard Changes banner; a `beforeunload` guard fires
      when a document is dirty.
- [ ] Externally-modified banner offers "Keep Editing" (→ `conflict`) in
      addition to Reload; discard-and-reload uses the two-step
      `"Discard unsaved changes?"` / `"Cancel"` / `"Discard & Reload"`
      copy; "Reload from Disk" is Title Case.
- [ ] Autosave: enabling it in the (ticket-03) settings store schedules a
      debounced save after idle edits, only while the document can
      autosave.
- [ ] Tree sidebar split (§2.5): `render_tree_toggle` button shows/hides
      the sidebar with the correct tooltip text; default width 286px,
      drag-resizable within [220, 360], double-click resets to 286;
      sidebar defaults visible ≥680px surface width and hidden below it;
      crossing the 680px breakpoint jumps instantly (no tween) while an
      explicit toggle click animates; the split width/dismissed flag is
      in-memory only (not written to the ticket-03 settings store).
- [ ] Unit tests:
      - `FileDocument` desktop test names listed in §3 → `lib/file-document.test.ts`
      - new `keepEditing` transition test
      - new autosave-scheduling test (fires after idle, not while
        `saveFailed`)
      - `fit_preserves_aspect_ratio_and_never_upscales`,
        `zoom_keeps_the_cursor_over_the_same_image_point`,
        `pan_zoom_and_resize_stay_bounded`,
        `wheel_requires_control_and_normalizes_lines`,
        `pinch_accumulates_native_deltas_and_a_drag_does_not_click` →
        `lib/image-geometry.test.ts`
      - `relative_target` cases in §2.3 → `lib/markdown-doc.test.ts`
      - `sidebar_layout_changes_are_immediate_without_a_user_toggle`,
        `sidebar_motion_reverses_from_its_current_width`,
        `sidebar_motion_snaps_when_reduced_motion_is_enabled` →
        `lib/tree-split.test.ts`
- [ ] Screenshot pair, desktop vs web, states: editable code file with
      gutter/highlighting, word-wrap on vs off, save-failed pill, conflict
      banner with Keep Editing, Markdown preview with a code fence and a
      task list, image at fit scale and zoomed in, **file open with the
      tree sidebar visible at its default 286px split width**.
- [ ] `pnpm -r build` green; package vitest green.
- [ ] No new literal hex/px where a `--rb-*` token exists.

## Comments


## Comments

### Implementation (2026-09-18, branch `wp2/25-files-preview`)

**What landed:**

- `components/files/code-view.tsx` (new) — `CodeView`: gutter + line
  numbers + syntax-highlighted rows for both the read-only preview and
  the editable buffer (one component, the input layer toggled — the
  desktop's single text state). The editable layer is a transparent
  `<textarea>` laid over the highlight layer sharing the exact font
  metrics/paddings/wrap mode; caret `--rb-caret`, selection wash
  `accent`/0.22, active line `ink`/0.025; read-only rows use the 20px/
  11.5px preview metrics, editable rows the `filesEditorFontSize` +
  `max(size+8.5, 20)` formula. Editable highlighting debounces at 120ms
  (the desktop's figure) — the plain rows hold the current text while
  tokens catch up. Horizontal scroll is a single `overflow: auto`
  scroller (native vertical wheel works over it) with the gutter
  `position: sticky; left: 0` over an opaque plate so line numbers stay
  pinned while code slides under. The scroller hides native scrollbars
  and mounts the shared floating rails (`MenuScrollbar` /
  `HorizontalScrollbar` from `ui/Scrollbar.tsx` — the code plane's half
  of the "21/25 debt" comment in app.css). Ticket 23's seam: gutter
  cells render through one `renderGutterCell` per visible row.
- `components/files/tree-split-panel.tsx` (new) + `lib/tree-split.ts`
  (new) — the §2.5 split: the breadcrumb toolbar row with the fixed 32px
  tree-toggle slot, the sidebar (ticket 24's whole `FileTreePanel`)
  beside the document body, and the `preview_split_handle` (20px hit
  strip, 1px gradient line, hover blend, drag 1:1 clamped [220, 360] via
  `state/layout.ts`'s `resizeDragSample` + the 5px/220ms edge bounce,
  double-click resets to 286). `TreeSidebarMotion` ports the openness
  sampler: explicit toggles animate on the resize curve and reverse from
  the current openness; breakpoint crossings and reduced motion snap.
  All state is in-memory (no settings key), per the ticket.
- `components/files/editor-context-menu.tsx` (new) — the 170px
  Cut/Copy/Paste/Select All card over `RbContextMenu` (the chat-menu
  pattern), availability from the textarea's selection at open time.
- `components/files/image-view.tsx` (new) — `ImageView` (fit/resize
  re-fit, ctrl+wheel zoom anchored at the cursor with the DOM sign flip,
  unmodified wheel pan, two-pointer pinch, window-riding drag with the
  4px threshold and post-drag click swallow) plus the shared
  `loadWorkspaceImage` pipeline (readFile → chunked readImage → decode,
  30s timeout, 64 MiB decoded-size cap).
- `lib/image-geometry.ts` (new) — the `Geometry`/`ViewState` port of
  `image_viewer.rs` with the five desktop test names mirrored.
- `components/files/file-viewer.tsx` (rebuild) — `FileSurface` (the
  registry body the pane host mounts), the `render_breadcrumb` toolbar
  (file-type icon, per-segment crumbs with the full-path tooltip after
  350ms, markdown toggle, the phase-colored save-status pill with
  clickable `saveFailed` retry + per-phase tooltips, Reveal-in-tree,
  word-wrap toggle), the two write-outcome banners (lifecycle
  close-requested and external-change/reload-confirmation with the
  verbatim desktop copy incl. Title Case "Reload from Disk" and the
  two-step "Discard unsaved changes?" flow), the truncated-preview
  strip, and the Markdown/CodeView/Image document bodies.
- `lib/file-document.ts` — extended (not rewritten): `showMarkdown`
  (`show_markdown` default for markdown paths), `keepEditing()`
  (externallyModified → conflict), `discardChanges()`, `prepareClose()`
  / `blocksLifecycleClose()` / `canAutosave()`, autosave scheduling
  (`configureAutosave`, the reload-confirmation pause, per-edit
  re-arming; timers cleared on save failure/conflict/external/delete).
- `state/file-documents.ts` (new) — the `FileDocument` registry: the
  web peer of the shell's `file_surfaces` slice. The pane host mounts
  one surface at a time, so the document + tree model live HERE keyed by
  surface id, outliving tab switches (edits survive; ticket 22's
  per-surface Changes store is the precedent) and disposed only on the
  real close. It also feeds the tab strip's dirty dots (via the pane
  store's notify) and the `beforeunload` guard.
- `state/right-pane.ts` — the close lifecycle: `closeSurface` consults
  `fileDocuments.prepareClose` (allow ⇒ complete now; pending/blocked ⇒
  keep the tab, reveal it, mark the request); `completeFileClose` /
  `cancelFileClose` / `isCloseRequested` / `filePathOf`; `describe`'s
  file rows now read the live dirty flag.
- `components/app-shell.tsx` — the `save-file` guard now requires the
  pane's resolved active surface to be Files/File (the ticket's Mod-S
  scope), reading the live store at keypress.
- `components/files/markdown-view.tsx` (rebuild) — the §2.3 preview at
  the files-specific metrics (900px max width via `.files-markdown`,
  py16, 12px block gap, centered px24 rows): shared `CodeBlock` fences
  (highlighting + copy + 1200ms reset), interactive task checkboxes
  writing `[x]`/`[ ]]` into the live buffer through a single edit (the
  source-match guard in the parent), heading anchors
  (`buildHeadingAnchors` slug + collision suffix; same-file `#anchor`
  scrolls), workspace-relative links via the `relative_target` port, and
  workspace images under the per-document limits (32 entries, 64 MiB
  combined decoded budget, 480px height cap) opening the lightbox (an
  `ImageView` at natural size, Escape closes + restores focus, "Open
  image link").
- `lib/markdown-doc.ts` — `relativeTarget` (the full
  `markdown_preview.rs:66-112` semantics: recursive `roboco-file:`
  strip, raw-target rejections, percent-decoding of path and anchor,
  `..`/`.` against the document's DIRECTORY, null above the root), task
  markers carrying their source offset, `clipMarkdownBytes` (2 MiB
  UTF-8-boundary clip), `buildHeadingAnchors`/`slugify`, and
  `markdownLinkTarget` now routes bare `#anchor`s (they previously
  rendered as plain text).
- `lib/files.ts` — `truncatedMessage` (the "Large file preview is
  truncated and read-only." condition, `file.truncated && text != null`).
- `styles/app.css` — the §2.1/§2.2/§2.5 classes (`.files-code*`,
  `.files-breadcrumb*`, `.files-toolbar-button`, `.files-save-pill`,
  `.files-banner*` at the warning 0.25/0.055 values, `.files-truncated-banner`,
  `.files-image*`, `.files-markdown*`, `.files-lightbox*`,
  `.files-tree-toggle*`, `.files-split*`, `.files-editor-menu`); removed
  the old `.files-viewer-header`/`.files-back`/`.files-dirty`/
  `.files-banner-danger`/`.files-editor`/`.files-text-preview` and the
  entire `.markdown` block (the files preview now rides the shared
  `md-*` vocabulary).
- Tests: `tests/image-geometry.test.ts` (the five desktop names),
  `tests/tree-split.test.ts` (the three sidebar-motion names + the
  width/breakpoint math), and new `file-document` cases (showMarkdown,
  keepEditing, autosave fires-after-idle + not-while-saveFailed,
  prepareClose allow/pending/blocked, discard) and `markdown-doc` cases
  (task offsets, the five `relative_target` port cases, the 2 MiB clip,
  heading anchors).

**Deviations and judgment calls:**

- **`state/shortcuts.ts` itself needed no edit** — the `saveFile` entry,
  `mod-s` default, and `save-file` bus event already existed from
  earlier tickets; ticket 25's wiring landed as the app-shell surface
  guard + the FileSurface's `onShortcut("save-file")` listener (the old
  component-local window keydown listener is gone).
- **The document registry is a new module the ticket's file table does
  not name** (`state/file-documents.ts`). Without it, the pane host's
  one-surface-at-a-time mounting discarded edits on every tab switch —
  the exact "unmounting discards edits silently" gap this ticket closes.
  It follows ticket 22's per-surface-store precedent.
- **Markdown block rendering**: code fences ride the shared
  `components/markdown.tsx` `CodeBlock` and every other block renders
  with the shared `md-*` class vocabulary (the transcript's own styles),
  but the block-level component tree stays on the files parser
  (`lib/markdown-doc.ts`) rather than the transcript's `Block` model —
  link resolution, task source offsets, and workspace media are
  files-specific and the ticket's table keeps that pipeline.
- **Mermaid fences render as their source code block** — no JS Mermaid
  renderer was pulled in (per the ticket's deferral allowance); the
  fence is never omitted. The "Rendering diagram…"/diagram-error states
  therefore have no live trigger.
- **`roboco-file:` prefixed targets resolve against the workspace ROOT**
  (the desktop's `relative_target("", …)` recursion), not the document's
  directory — verified against the Rust.
- **Read-only-with-text shows no banner** (the old web's banner was
  removed): the desktop renders the plain read-only list there; the
  message only appears centered when there is no text.
- **`paste` availability cannot be probed synchronously** (a clipboard
  read would prompt); it tracks the editable state and the click
  attempts the read, no-oping on denial.
- **Watch rename handling is not wired**: a renamed open file keeps the
  old tab title/path until the surface reopens (`rightPaneStore.
  renameFileSurface` stays ticket 07's unused seam). Noted for a later
  ticket rather than guessed at.
- **`target_change_pending` (the sync-target banner) is not ported**: no
  engine event surfaces a chat checkout change to the web client in a
  form this can hook today (ticket 24 noted the same); the banner's copy
  and styling exist but nothing arms it.
- **The tab-strip file icon stays the monochrome `document` glyph**
  (ticket 24's documented seam — the ticket's table doesn't claim it).
- The truncated strip's border is `var(--rb-border)` (the Rust's
  `theme.border`, full opacity) — the ticket left the border unspecified.
- The `pending` close banner ("Saving changes before closing…") is
  covered by the unit tests and the code path; live it flashes too
  briefly to screenshot (local saves land in well under a frame) — the
  blocked variant is the screenshotted one.

**Verification:**

- `pnpm -r build` green; `@roboco/app` vitest 52 files / 797 tests green.
- web_smoke round (fresh engine, the staging dir registered via
  New project…): live checks for — editable code view with gutter/
  highlighting at the 48px/10px and 13px/21.5 metrics; word-wrap toggle
  both ways; horizontal scroll with the sticky gutter (gutter left
  pinned at the scroller edge while scrolled 300px) and native vertical
  wheel; Mod-S through the shortcut bus (dirty → saved clean); the
  save-failed pill on a read-only-on-disk file; the externally-modified
  banner ("This file changed outside Roboco." + Keep Editing/Reload from
  Disk) staged by editing on disk under a dirty buffer; Keep Editing →
  "Save conflict"; the two-step "Discard unsaved changes?"/Cancel/
  "Discard & Reload" with the buffer reloading; markdown preview (fence
  + copy button, task list with a live toggle into the buffer, workspace
  image through the chunked RPC, lightbox open/Escape, same-file anchor
  link); the image viewer at fit (800×600 at scale 1, centered), two
  ctrl+wheel zooms (×1.82, anchored), and drag-pan (clamped); the editor
  context menu (170px, Cut/Copy/Paste/Select All, availability from the
  selection); the tree split (default 286, drag clamped at 360,
  double-click reset to 286, animated toggle, the narrow formula —
  228.36px on a 519px surface — and a dismissal surviving the
  breakpoint crossing); reveal-in-tree (ancestors + selection);
  **edits surviving a tab switch** (the registry) with the dirty dot
  live on the inactive chip; the close lifecycle — blocked close
  (Retry/Keep Open/Discard Changes), Keep Open keeping the tab dirty,
  Discard completing the close, and the pending path saving then
  completing; autosave live (900ms after idle, clean after).
- Screenshots (web halves) in `.scratch/web-parity/shots/25/`:
  `web-00-boot-check.png` (final state, no error boundary),
  `web-01-code-editable-gutter-highlight.png` (the 286px sidebar state),
  `web-02-word-wrap-on.png`, `web-03-word-wrap-off-hscroll.png`,
  `web-04-save-failed-pill.png`, `web-05a-externally-modified.png`,
  `web-05b-discard-reload-confirm.png`, `web-06-markdown-lightbox.png`,
  `web-07-markdown-preview-fence-tasks.png`, `web-08-image-fit.png`,
  `web-09-image-zoomed.png`, `web-10-close-lifecycle-blocked.png`,
  `web-11-editor-context-menu.png`.
  - **Desktop halves of all pairs: skipped, documented per the runbook**
    — no `roboco` desktop process is running and none was started (the
    same skip as tickets 09/10/24).
  - The "conflict banner with Keep Editing" pair is covered by
    `web-05a` (the externally-modified banner WITH the Keep Editing
    action — its clicked outcome, the conflict pill, is verified
    behaviorally above); no separate post-click shot.
- Boot check: the app renders with no error boundary after the full
  round (`web-00-boot-check.png`).
