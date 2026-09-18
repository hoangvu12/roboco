# 24 — Files tree and search

**What to build:** In the right pane's Files surface and the routed `/files`
page, the user gets a lazy directory tree with real VS Code-style file-type
icons, working expand/collapse chevrons, keyboard navigation, drag-out onto
the composer, and a fuzzy search that groups results into a synthetic
ancestor tree instead of a flat list — matching the desktop tree pixel for
pixel (row height, indent, dimming, selection wash) instead of today's
accent-tinted, icon-less, keyboard-dead list. Opening a file from the tree or
search asks the right-pane host (ticket 07) for that file's own tab, and the
pane's Files surface now targets the **active chat's checkout**, not a
space picked independently of which chat is open.

**Blocked by:** 03 (Client settings store), 07 (Right pane host and
multi-instance tabs)

**Status:** done

**Research:** `../../web-client/research/09-files-tree-editor.md` §3.1
(header/toolbar), §3.2 (tree), §3.3 (search), §3.8 (editor context menu,
documented here for completeness — see §5 "Do not"), §3.10 (Settings →
Files, documented here for completeness — see §5 "Do not"), §3.11 (file
icons, build in full here), §4 (pure logic), §5 (gap rows quoted below).
`../../web-client/research/00-index.md` for cross-cutting context only.

**Desktop reference (for lookups only):**
- `crates/ui/src/files/mod.rs::render_header` (973), watch-error banner
  (268–309), root-load error + Retry (213–247), empty placeholder
  (248–254), `render_editor_context_menu` (626)
- `crates/ui/src/files/model.rs`: `FileTreeModel`, `TreeNode`,
  `VisibleTreeRow`, `entry_rank`/`compare_paths` (569–584), `parent_path`/
  `is_descendant`/`is_direct_child` (586–603), `apply_page` (210–296),
  `rebuild_visible_rows`/`append_directory_rows` (458–554), `move_selection`
  (419–442)
- `crates/ui/src/files/tree.rs`: `render_tree` (68), `render_tree_row` (95),
  `on_tree_key_down`, `sync_list_rows` (21–65), row/indent constants (17–18)
- `crates/ui/src/files/search.rs`: `FileSearchState`, `SearchTreeModel`,
  `render_search_results` (466), `render_search_row` (516),
  `SearchTreeModel::rebuild` (75), `sort_search_paths` (203), `toggle`
- `crates/ui/src/files/client.rs`: `WorkspaceFilesClient`, RPC wire shapes
  (`ListWorkspaceDirectory`, `SearchWorkspaceFiles`)
- `crates/ui/src/files/watch.rs`: `sequence_needs_resync` (152–154)
- `crates/ui/src/file_icons.rs` (513 lines, build this ticket's icon
  resolver from it in full) + `crates/ui/src/file-icons.json` (manifest)
- `crates/ui/src/settings/files.rs::FilesSettingsPage::render` (65) —
  documented here, not built here (see §5)
- `crates/ui/src/surface_chrome.rs` — shared toolbar/input metrics
- `crates/ui/src/shell.rs` (2477–2543, 2578–2596) — `add_file_surface`,
  `rename_file_surface`, `workspace_file_title` (tab creation/titling only;
  close lifecycle is ticket 25)
- `crates/proto/src/entities.rs`, `crates/rpc/src/lib.rs`,
  `crates/engine/src/workspace_files.rs` — wire types, method names,
  server-side pagination/limits

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/routes/files-page.tsx` | edit | `FilesSurface`, `FilesBody`, `SpacePicker`; chat-checkout scoping fix for the pane surface |
| `web/packages/app/src/components/files/file-tree-panel.tsx` | edit | `FileTreePanel`, `TreeRowView`, `SearchResults`; icons, keyboard nav, drag source, ignored-row opacity, search grouping/keyboard/banner |
| `web/packages/app/src/lib/file-tree.ts` | edit | `FileTreeModel`; add `moveSelection`, expose selectable-row order for keyboard nav |
| `web/packages/app/src/lib/files.ts` | edit | remove the invented `formatBytes` row usage from tree rows (keep the helper — the viewer header still wants it per ticket 25) |
| `web/packages/app/src/lib/files-client.ts` | edit | widen `FilesTarget` to the `WorkspaceTarget` shape (`chatId \| spaceId \| checkoutPath`) so the pane surface can target a chat |
| `web/packages/app/src/lib/file-search-tree.ts` | new | `SearchTreeModel`-equivalent: `buildSearchTree`, `sortSearchPaths`, `toggleSearchNode` |
| `web/packages/app/src/lib/file-icons.ts` | new | `resolveFileIcon`, `resolveDirectoryIcon`, `hasSpecificFileIcon`, `wellBg`, `fileIconAssetPath` |
| `web/packages/app/src/components/files/file-icon.tsx` | new | `FileIcon` component (renders the resolved manifest asset as an `<img>`, dark-variant path swap) |
| `web/packages/icons/scripts/generate-file-icons.mjs` | new | generator mirroring `scripts/generate.mjs`, reading `crates/ui/src/file-icons.json` + `crates/ui/assets/file-icons/**/*.svg` |
| `web/packages/icons/src/generated/file-icons-manifest.ts` | new (generated) | `fileIconManifest` (the parsed `Manifest` shape) |
| `web/packages/app/public/file-icons/**` | new (generated) | copied SVG assets, `dark/` variant tree |
| `web/packages/app/src/styles/app.css` | edit | `.files-row`, `.files-chevron`, `.files-row-ignored`, `.files-row-active`, `.files-row-icon` (new), `.files-search`, `.files-toggle-ignored` (rename of `.files-toggle-on`), `.files-search-banner` (new), `.files-row-error`, `.files-drag-ghost` (new) |

## 1. Context a fresh session needs

- The Files surface exists in two shells today: a routed page
  (`/files?space=&path=`, `routes/files-page.tsx`) and the right pane's
  Files tab (`FilesSurface()` in the same file, mounted from
  `components/right-pane.tsx`). Both compose the same `FileTreePanel` +
  `FileViewer` pair; this ticket only touches the tree/search half
  (`FileTreePanel` and the model/client layer it reads). `FileViewer` is
  ticket 25.
- `FileTreeModel` (`lib/file-tree.ts`) is an observable class:
  `subscribe`/`getSnapshot` (React reads it via `useSyncExternalStore`, see
  `FileTreePanel`). It already ports `entryRank`/`compareEntries`/
  `parentPath`/`isDirectChild` (`lib/files.ts`), `applyPage` pruning
  semantics, and `sequenceNeedsResync` verbatim — do not re-port those, only
  extend the model with the keyboard-nav helper below.
  `useSyncExternalStore` re-renders the full row list on every change; there
  is no scroll-anchor preservation (desktop's `sync_list_rows`) and porting
  it is optional here — see §3.
- `WorkspaceFilesClient` (`lib/files-client.ts`) wraps `EngineClient.call`;
  its `FilesTarget` interface is `{ spaceId: string }` today. The wire type
  it should widen to already exists: `WorkspaceTarget = { chatId?: string |
  null; spaceId?: string | null; checkoutPath?: string | null }`
  (`@roboco/proto`'s generated `WorkspaceTarget.ts`, and every files RPC
  request type already has all three fields as optional). Every method
  (`listDirectory`, `search`, `readFile`, `writeFile`, `watchParams`,
  `readImage`) spreads `...this.#target` into its request, so widening the
  field type is the only client-side change needed to target a chat instead
  of a space.
- `ChangesSurface({ chatId })` (`routes/changes-page.tsx:70`) is the pattern
  to copy for chat-scoping: the pane already passes `chatId` into that
  surface. `FilesSurface()` (the pane variant, `files-page.tsx:45`) takes no
  props today and resolves `owned[0]?.id` — an arbitrary space, unrelated to
  the open chat. Change its signature to `FilesSurface({ chatId }: { chatId:
  string })` and build its `WorkspaceFilesClient` from `{ chatId }` instead
  of resolving a space. The routed `/files` page keeps using `{ spaceId }`
  (it has no chat context) — do not change `FilesBody`'s existing space
  resolution path, only give it a second entry mode.
- Every visual/tokens rule in `spec.md` applies: colors are `var(--rb-*)`;
  washes are `rgb(var(--rb-wash) / a)`; no literal hex.
- The desktop's `wash(a)` role used throughout this surface is
  `rgb(var(--rb-wash) / a)` per ticket 02's foundations; use it instead of
  the accent-tinted `color-mix(...)` currently in `.files-row-active`.
- File icons are a **new, separate asset pipeline** from `@roboco/icons`
  (which only mirrors `crates/ui/assets/icons/*.svg`, the monochrome
  `currentColor` control glyphs used elsewhere in the app). File-type icons
  are polychrome VS Code-derived SVGs from `crates/ui/assets/file-icons/`
  plus the manifest `crates/ui/src/file-icons.json`; they render as `<img>`
  (or `<image>`), never tinted, exactly like the desktop's `gpui::img`
  choice (§3.11 explains why: to preserve authored multi-color fills).
- Vocabulary: chat (not session/thread), engine, space, checkout. The right
  pane surface is chat-scoped chrome; the routed page is space-scoped.

## 2. Spec

### 2.1 Header / toolbar (`mod.rs::render_header`, `mod.rs:973`)

**Layout**

| Property | Value | Source |
| --- | --- | --- |
| height | `Theme::TITLEBAR_HEIGHT` = 38.0 (`surface_chrome::HEADER_HEIGHT`) | `surface_chrome.rs:7`, `proto/layout.rs:45` |
| padding-x | `EDGE_INSET` = 8.0 | `surface_chrome.rs:12` |
| gap between controls | `CONTROL_GAP` = 4.0 | `surface_chrome.rs:11` |
| border | 1px top + bottom, `theme.border` | `surface_chrome.rs:38–40` |
| background | `theme.surface.opacity(0.26)` if glass, else `theme.surface` | `surface_chrome.rs:41–45` |
| search input height | `CONTROL_SIZE` = 24.0 | `surface_chrome.rs:8, 17` |
| search input radius | `CONTROL_RADIUS` = 6.0 | `surface_chrome.rs:9, 21` |
| search input bg | `Theme::ink(0.035)` | `surface_chrome.rs:22` |
| search input text size | 11.5px | `surface_chrome.rs:26` |
| toggle-ignored button size | `CONTROL_SIZE` = 24.0 square, radius `CONTROL_RADIUS` = 6.0 | `mod.rs:46,48` |
| toggle button icon size | `ICON_SIZE` = 14.0 | `mod.rs:1005` |
| watch-error banner border-bottom | 1px, `theme.warning.opacity(0.22)` | `mod.rs:275` |
| watch-error banner background | `theme.warning.opacity(0.045)` | `mod.rs:276` |

**Children (in order)**
- search field: magnifier icon (`icons::MAGNIFER`, 12px, `theme.text_faint`)
  + text input (placeholder `"Search files"`, 11px/16px metrics)
- toggle-ignored button (eye / eye-closed icon)

**States**

| State | Condition | What changes |
| --- | --- | --- |
| ignored-visible | `tree.include_ignored()` true | button bg `wash(0.1)`, icon `EYE`, icon color `theme.text` |
| ignored-hidden | default | icon `EYE_CLOSED`, icon color `theme.text_muted` |
| toolbar-button hover | any `toolbar_button` | bg `wash(0.14)` |
| root-load error "Retry" button (sibling of the header; shown instead of the tree when the root directory listing fails) | rest / hover | bg `wash(0.04)` / hover bg `wash(0.09)` |
| watch-error banner's "Refresh now" button (shown when the change-watch stream is degraded) | hover | bg `wash(0.07)` |

**Interactions**
- Click toggle → flips `include_ignored`; persist to the ticket-03 settings
  store's `filesShowAll` field and re-apply it to every open Files/File
  surface (today it is per-model-instance only and lost on remount).
- Tooltip on the toggle button: dynamic text below, 350ms show delay.
- Typing in the search field: see §2.3.

**Motion**: none — background/opacity changes are instant, not tweened.

**Text** (verbatim)
- Search placeholder: `"Search files"` (no ellipsis — the web currently
  says `"Search files…"`, fix it)
- Toggle tooltip: `"Hide hidden and ignored files"` (when showing) /
  `"Show all files (even hidden)"` (when hidden)

**Data**: reads `tree.includeIgnored`; writes to the ticket-03 settings
store's `filesShowAll` (default `false`).

### 2.2 Tree (`tree.rs`)

**Layout**

| Property | Value | Source |
| --- | --- | --- |
| row height | `TREE_ROW_HEIGHT` = 27.0 | `tree.rs:17` |
| indent per depth | `TREE_INDENT` = 14.0 | `tree.rs:18` |
| row left padding | `8.0 + depth * 14.0` | `tree.rs:105` |
| row right padding | 8.0 | `tree.rs:142` |
| row gap (icon/chevron/label) | 4.0 | `tree.rs:145` |
| chevron/expander box | 14×14, icon 11px, `theme.text_faint` | `tree.rs:165–181` |
| file/folder icon | 14px (`file_icons::icon`) | `tree.rs:184–186` |
| label | `theme.font_sans`, 11.5px, `theme.text` (selected) / `theme.text_muted` | `tree.rs:117–121,192–195` |
| ignored-entry opacity | 0.52 (whole row) | `tree.rs:147` |
| selected row bg | `wash(0.12)` focused / `wash(0.08)` unfocused | `tree.rs:149` |
| unselected hover bg | `wash(0.055)` | `tree.rs:152` |
| `tree_list` initial height estimate (before first measurement) | 560.0 | `mod.rs:545` |

**Children (in order, per `Entry` row)**: expand/collapse chevron slot
(directories only) → file-type icon → truncated name label.

**States** (row-level, from `VisibleRowKind`)

| Kind | Condition | Rendering |
| --- | --- | --- |
| `Entry` | a real file/dir/symlink | icon + name, selectable, draggable |
| `Loading` | directory mid-fetch | `"Loading…"`, `theme.text_faint`, at `depth+1` indent |
| `Empty` | directory loaded with 0 children | `"Empty folder"`, `theme.text_faint.opacity(0.7)` |
| `Error` | last page fetch failed | `"{message} — Retry"`, `theme.danger.opacity(0.82)`, clickable |
| `LoadMore` | `next_cursor` present | `"Load more…"`, `theme.text_muted`, clickable |

**Interactions**
- Click a directory row → expand/collapse; loads if unloaded/stale.
- Click a file row → opens it: emit an open-file request that the ticket-07
  pane host resolves into that file's own tab (or, on the routed page,
  navigate `?path=`).
- Click Error row → retries with the last cursor.
- Click LoadMore row → loads the next page with that cursor.
- Drag a row (file or directory): payload is a workspace-relative path +
  `isDirectory` flag; ghost is a small pill (24px h, ≤220px w,
  `theme.surface_raised` bg, `theme.border_strong` border, 11.5px text,
  opacity 0.85) that the composer's drop target (owned by the
  attachments/composer ticket) turns into a file mention. Use the HTML5
  Drag and Drop API (`draggable`, `dragstart` sets a custom ghost via
  `setDragImage`); the desktop's native drag payload/ghost class is GPUI
  chrome with no direct port.
- Keyboard (new — currently entirely missing on web):
  - `ArrowUp`/`ArrowDown` — move selection among selectable rows
    (`Entry`/`LoadMore`); wraps to the last item on Up with no prior
    selection, first item on Down.
  - `ArrowLeft` — collapse if expanded, else select the parent.
  - `ArrowRight` — expand if collapsed, else select the first child.
  - `Enter`/`Space` — activate the selected row (open file / toggle
    directory / load-more).
  - All handled keys call `preventDefault()` and stop propagation, then
    scroll the selection into view (`scrollIntoView({ block: "nearest" })`
    is an acceptable web substitute for the desktop's manual scroll math).

**Motion**: none — expand/collapse swaps the chevron icon and re-splices
the row list instantly. Do not add a chevron-rotation CSS transition (see
§5 — it is an invented divergence today).

**Text**: `"Loading…"`, `"Empty folder"`, `"{message} — Retry"`,
`"Load more…"`.

**Data**: reads `FileTreeModel`'s snapshot rows; writes via
`listDirectory` RPC (`ListWorkspaceDirectory`) and local-only
`toggleExpanded`/selection state (no RPC).

### 2.3 Search (`search.rs`)

**Layout**

| Property | Value | Source |
| --- | --- | --- |
| row height | `SEARCH_ROW_HEIGHT` = 27.0 | `search.rs:24` |
| indent per depth | `SEARCH_TREE_INDENT` = 14.0 | `search.rs:25` |
| result cap | `SEARCH_RESULT_LIMIT` = 200 | `search.rs:26` |
| debounce | 200ms after last keystroke | `search.rs:317–318` |
| "showing first N" banner height | 24.0, text 10.0px `theme.text_faint` | `search.rs:490–501` |
| `search_list` initial height estimate | 420.0 | `mod.rs:551` |

**Children**: same visual shape as tree rows (chevron slot for directory
nodes with children, file-type icon, name), built from a synthetic
ancestor tree over the flat match list.

**States**

| State | Condition | Behavior |
| --- | --- | --- |
| loading | loading and no results yet | centered `"Searching…"`, `theme.text_faint` |
| empty | not loading, results empty | centered `"No files found."` |
| error | RPC failed | centered error text, `theme.danger.opacity(0.82)` |
| capped | `results.length >= 200` | banner `"Showing the first 200 matches"` |
| row selected (keyboard) | active index matches | bg `wash(0.1)` |
| row hover (mouse) | not selected | bg `wash(0.055)` |

**Interactions**
- Type in the search field → 200ms debounce (currently 250ms — fix) → RPC
  `SearchWorkspaceFiles`.
- Arrow keys while the input has focus move the active row and scroll it
  into view.
- Enter activates: if the active row is a collapsed directory with
  children, toggle it in place; otherwise reveal the match in the real
  tree — expand ancestors (one `ListWorkspaceDirectory` call per ancestor),
  select it, clear the search box, and open the file (or just reveal a
  directory).
- Escape clears the search box.
- Click a row → same as Enter on that row.
- Drag a row → same drag payload/ghost as the tree.

**Motion**: none.

**Text**: `"Searching…"`, `"No files found."` (currently `"No matches."` —
fix), `"Showing the first 200 matches"` (currently missing entirely — add).

**Data**: reads via `SearchWorkspaceFiles` (`{ target, query,
includeIgnored, limit: 200 }`); on activation, reads via
`ListWorkspaceDirectory` per ancestor to reveal the row.

**Pure logic — search grouping** (new file `lib/file-search-tree.ts`, see
§3): groups flat search matches into a synthetic ancestor tree keyed by
**path components**, not name prefixes. Ordering: descendant-propagated
best-score descending, then directories before files at equal score, then
case-insensitive name, then raw path as a final tiebreak. Toggling a
directory node collapses/expands only its own subtree.

### 2.4 Editor context menu (`mod.rs::render_editor_context_menu`, `626`)

Documented here for completeness (it is part of `09`'s transcription this
ticket is responsible for), but **not implemented by this ticket** — see
§5. Kept as reference for whoever builds it in ticket 25, where the
editable buffer actually lives.

**Layout**: popover card 170px wide, rows via `popover::menu_row` with a
separator before "Select All".

**Children (in order)**: Cut, Copy, Paste, — separator —, Select All.

**States**: each row's `enabled` comes from `cut = editable &&
has_selection`; `copy = has_selection`; `paste = editable &&
clipboard_has_text`. Disabled rows: `opacity(0.38)`, `cursor: default`, no
click handler.

**Interactions**: right-click in the editor opens it at the cursor
position; clicking outside closes it; clicking an enabled row dispatches
the corresponding editing action and closes the menu.

**Text**: `"Cut"`, `"Copy"`, `"Paste"`, `"Select All"`.

**Data**: none — local clipboard/selection only.

### 2.5 File icons (`file_icons.rs`)

Not a UI component per se — a resolver used by every row above (tree
rows, search rows, the drag ghost, and ticket 25's editor breadcrumb).
Build the whole resolver in this ticket since the tree is its first (and
most visible) consumer.

**Asset addressing.** Every resolved icon is a path under a fixed prefix
`ASSET_PREFIX = "file-icons/"`. The resolved path is
`"{ASSET_PREFIX}{variant}{asset}"`, where `variant` is `"dark/"` in dark
appearance and empty in light — i.e. **dark mode is a different asset
path** (`file-icons/dark/files/rust.svg` vs `file-icons/files/rust.svg`),
which forces a fresh image load on theme switch by construction (no
per-icon runtime recolor needed on the client side once the dark variants
are pre-generated — see the generator note below).

**Manifest shape** (`crates/ui/src/file-icons.json`, VS Code icon-theme
format):
```jsonc
{
  "iconDefinitions": { "<definition-name>": { "iconPath": "./icons/<asset>.svg" }, ... },
  "fileExtensions": { "<ext>": "<definition-name>", ... },
  "fileNames":      { "<lowercased-basename>": "<definition-name>", ... },
  "folderNames":    { "<lowercased-dirname>": "<definition-name>", ... },
  "languageIds":    { "<vscode-language-id>": "<definition-name>", ... },
  "file": "<definition-name>",
  "folder": "<definition-name>",
  "rootFolder": "<definition-name>"  // present in the JSON but unused — no distinct root-folder icon exists
}
```
`fileExtensions`/`fileNames`/`folderNames` are lowercased-key-normalized at
load time so lookups are case-insensitive; `iconDefinitions`/`languageIds`
are left as authored. Resolving a definition name to its asset path strips
the `"./icons/"` prefix, e.g. `"rust"` → `"files/rust.svg"`. Two aliases
referenced by the manifest but missing their own `iconDefinitions` entry
are special-cased: `"less"` → `"brackets-sky"`, `"yml"` → `"yaml"`.

**Resolution order** — directories (`resolveDirectoryIcon`) vs files
(`resolveFileIcon`):
1. **Directories only:** exact lowercased directory-name match in
   `folderNames`, else the manifest's generic `folder` definition, else the
   hardcoded fallback `"folders/folder.svg"`. **There is no separate
   open/closed icon** — an `expanded` flag may be threaded through the API
   for the caller's own chevron-glyph choice, but the folder image itself
   is identical whether expanded or collapsed.
2. **Files/symlinks:** exact lowercased basename match in `fileNames`
   (e.g. `package.json` → the `node` definition; case-insensitive).
3. Longest-first compound-extension match in `fileExtensions`: for
   `"component.test.tsx"` try `"test.tsx"` before `"tsx"` (every suffix
   after each `.`, in the order the dots appear, longest suffix first), so
   `component.test.tsx` → `react-test.svg`, and `model.schema.json` → the
   JSON-specific icon.
4. A syntax-language hint mapped through a fixed table (`Rust→"rust"`,
   `JavaScript→"javascript"`, `Jsx→"javascriptreact"`,
   `TypeScript→"typescript"`, `Tsx→"typescriptreact"`, `Python→"python"`,
   `Go→"go"`, `Json`/`Jsonc→"json"`, `Bash→"shellscript"`, `Toml→"gear"`
   directly, `Markdown→"markdown"`, `Html→"html"`, `Css→"css"`,
   `Yaml→"yaml"`, `C→"c"`, `Cpp→"cpp"`, `CSharp→"csharp"`, `Java→"java"`,
   `Kotlin→"kotlin"` directly, `Swift→"swift"`, `Ruby→"ruby"`,
   `Php→"php"`, `Sql→"sql"`, `Lua→"lua"`, `Dockerfile→"dockerfile"`,
   `Nix→"nix"`, `Make→"makefile"`), then looked up in `languageIds`. Not
   needed by the tree (no language hint available there); this step exists
   for ticket 25's editor breadcrumb icon.
5. A MIME-type hint: lowercase, strip the `;...` parameter; prefix match
   `image/*`→`"image"`, `audio/*`→`"audio"`, `video/*`→`"video"`; exact
   match `application/json`/`application/ld+json`→`"brackets-yellow"`,
   `application/pdf`→`"pdf"`, `application/zip`/`gzip`/`x-tar`→
   `"compressed"`, `text/markdown`→`"markdown"`, `text/css`→
   `"brackets-sky"`, `text/html`→`"brackets-orange"`. Not needed by the
   tree either — kept for parity/future consumers.
6. Generic fallback: the manifest's `file`/`folder` top-level definition,
   else the hardcoded `"files/document.svg"` / `"folders/folder.svg"`.

`hasSpecificFileIcon(path)` is a stricter check (used elsewhere in the app
to decide whether a bare filename mention deserves a decorative icon):
true only when `resolveFileIcon` would return something other than the
generic document icon — it does not consult language/MIME hints.

**Dark-mode recoloring.** The desktop string-replaces a fixed palette of
light accent hex codes with brighter equivalents directly in the SVG
source, at asset-load time, for any `dark/…` path:
`#64748B→#CBD5E1`, `#71717A→#D4D4D8`, `#2563EB→#60A5FA`,
`#EA580C→#FB923C`, `#16A34A→#4ADE80`, `#8B5CF6→#A78BFA`,
`#A855F7→#C084FC`. Everything else in the SVG is untouched. On web,
perform this rewrite **once, at generate time**, producing a real
`dark/…` copy of every SVG in `public/file-icons/` — do not implement a
runtime string-replace-on-fetch; the desktop only does it lazily because
GPUI's asset loader is a natural interception point that the web doesn't
have. `web/packages/icons/scripts/generate-file-icons.mjs` should: (1)
copy every SVG from `crates/ui/assets/file-icons/**` into
`web/packages/app/public/file-icons/**`, (2) apply the seven hex
substitutions to produce a `dark/` mirror tree, (3) parse
`crates/ui/src/file-icons.json` into a manifest module
(`web/packages/icons/src/generated/file-icons-manifest.ts`, mirroring the
existing `--check` CI-gate pattern in `scripts/generate.mjs`). Bundle has
≥350 icons (desktop-asserted) — the generator should assert the same
count so a partial copy fails loudly.

**Well background** (`well_bg`): a neutral backdrop a caller can place
behind a polychrome icon so it stays legible regardless of the
surrounding surface color. `alpha = 0.32` if the surface is frosted else
`0.16`; color is near-black in dark appearance, near-white in light, at
that opacity. Not used by the tree rows in this ticket (icons render
directly on the row background, like the desktop); expose it as an
exported helper for later call sites.

### 2.6 Settings → Files page (`settings/files.rs`)

Documented here (its data model, `filesShowAll`, is this ticket's own
concern) but **the settings-page UI itself is not built by this ticket**
— see §5. Reference for whoever builds the Settings shell in ticket 29.

**Layout**: one section card with four rows, each an icon + title/subtitle
+ a control.

| Row | Icon | Title | Subtitle | Control |
| --- | --- | --- | --- | --- |
| Autosave | `FOLDER` | "Autosave" | "Save edited workspace files to disk automatically." | toggle switch |
| Autosave delay (only when autosave on) | `FOLDER` | "Autosave delay" | "Save files after editing has been idle for this long." | 5 pill options: 300ms/600ms/900ms/1.5 s/3 s |
| Editor font size | `TUNING` | "Editor font size" | "Set the text size in workspace file editors." | 5 pill options: 10px/11.5px/13px/15px/17px |
| Word wrap | `LIST` | "Word wrap" | "Wrap long lines in every workspace file." | toggle switch |
| Show all files | `EYE` | "Show all files" | "Include hidden and ignored files in every file tree." | toggle switch |

Pill styling: 28px h, px 10, radius 7; active = `theme.accent.opacity(0.7)`
border + `theme.accent.opacity(0.11)` bg + `theme.text`; inactive =
`theme.border` border + `wash(0.025)` bg + `theme.text_muted`; hover
`wash(0.08)`.

**Pure ranges** (wider than the quick-pick pills): autosave delay clamps
to **100–10,000 ms** (default **900 ms**); editor font size clamps to
**9.0–24.0 px** (default **13.0 px**).

**Text** (verbatim page header/subtitle): `"Files"` /
`"Control how workspace files are displayed and saved while you edit."`

**Data**: `filesAutosaveEnabled` (default `false`), `filesAutosaveDelayMs`,
`filesEditorFontSize`, `filesWordWrap` (default `false`), `filesShowAll`
(default `false`) — all device-local fields on the ticket-03 settings
store. This ticket only needs `filesShowAll`; the other four are read by
ticket 25 and given a settings-page UI by ticket 29.

## 3. Pure logic to port

- **`entryRank`/`compareEntries`/`parentPath`/`isDirectChild`** — already
  ported verbatim in `lib/files.ts`. No changes needed; verify while
  touching the file.
- **`FileTreeModel::apply_page`** — a page's children are only pruned on
  the page with `nextCursor == null` (the last page); an in-progress
  paginated refresh must not treat not-yet-revisited children as deleted.
  A directory that becomes a file (or vice versa) drops its old subtree.
  `ignored` propagates from parent to every descendant unconditionally.
  Self/ancestor/non-direct-child entries from a page are rejected. Already
  ported in `lib/file-tree.ts::#applyPage` — verify, do not re-port.
- **`rebuildVisibleRows`** — depth-first walk from root; cycle-safe via a
  visited set. One synthetic row per collapsed-state directory for
  `Empty`/`Loading`/`Error`/`LoadMore`. Already ported (`#buildRows`,
  using a `"{directory} {kind}"` synthetic key instead of the desktop's
  NUL-separated one — functionally equivalent, not byte-identical, leave
  as is).
- **`moveSelection`** (new): arrow-key navigation only considers
  selectable rows (`entry`/`loadMore`); wraps to the last item on Up with
  no prior selection, first item on Down. Add this to `FileTreeModel` (or
  a pure helper it exposes) and wire it to the new keyboard handler in
  `FileTreePanel`. Desktop test to port: none named explicitly beyond
  `move_selection` itself — assert wrap-around and selectable-only
  filtering directly.
- **`sync_list_rows`** (tree.rs:21-65): after a row-list diff, keeps the
  scroll viewport anchored to the *path* under the old scroll offset (not
  the index). **Optional for this ticket** — `useSyncExternalStore`
  re-renders the full row list with no scroll-anchor preservation today;
  porting this is a nice-to-have polish item, not required for
  acceptance. If skipped, note it in Comments so it isn't silently lost.
- **`sequenceNeedsResync`** — already ported verbatim in `lib/file-tree.ts`
  including the stale-mark + reload-expanded + `resync` forward-event
  behavior. No changes needed.
- **`SearchTreeModel::rebuild`/sort** (new file `lib/file-search-tree.ts`):
  groups flat search matches into a synthetic ancestor tree keyed by path
  components (not name prefixes — port the desktop test
  `search_tree_uses_path_components_instead_of_name_prefixes` as
  `groupsByPathComponentsNotNamePrefixes`). Ordering
  (`sortSearchPaths`): descendant-propagated `bestScore` descending, then
  directories before files at equal score, then case-insensitive name,
  then raw path as a final tiebreak. `toggleSearchNode` collapses/expands
  only its own subtree.
- **File icon resolution** (new file `lib/file-icons.ts`): port the
  6-step resolution order in §2.5 exactly, including the compound-extension
  longest-match rule and the two hardcoded aliases (`less`→`brackets-sky`,
  `yml`→`yaml`). Port `folders_resolve_name_and_expansion_state` as a test
  asserting `resolveDirectoryIcon("src", false)` and
  `resolveDirectoryIcon("src", true)` return the same asset.

## 4. Gaps this ticket closes

| Item | Kind | Desktop value | Web value (file:line) | Fix |
| --- | --- | --- | --- | --- |
| Files right-pane target is Space, not Chat | WRONG BEHAVIOR | `FilesRequestContext::for_chat` scopes to the active chat's checkout | `files-client.ts` `FilesTarget = {spaceId}` only; `FilesSurface()` (files-page.tsx:73-78) picks `requestedSpace ?? owned[0]` unrelated to the active chat | Widen `FilesTarget`; give the pane's `FilesSurface` a `chatId` prop and target `{ chatId }` |
| Show-all-files toggle persistence | WRONG BEHAVIOR | persisted via settings, applied to every open Files surface | `FileTreeModel` constructed once per (session, space) with local-only `includeIgnored` | Persist to the ticket-03 settings store's `filesShowAll`; seed the model from it |
| Show-all-files toggle label/tooltip | WRONG VALUE | icon-only button, dynamic tooltip `"Hide hidden and ignored files"`/`"Show all files (even hidden)"` | plain text button labeled `"Ignored"`, `title="Show ignored files"` (`file-tree-panel.tsx:46-54`) | Icon + verbatim tooltip text |
| Manual "Refresh now" | MISSING | shown in the watch-error banner; forces a full tree reload + document reconcile | web's watch-error note has no action | Add the button, wire to a forced resync |
| Tree row keyboard navigation | MISSING | up/down/left/right/enter/space | none | Add keyboard handling to `FileTreePanel` |
| Tree row drag-out (composer file mention) | MISSING | drag payload + ghost preview | none | HTML5 drag source + composer drop target (composer side may already be tracked elsewhere) |
| Tree row file-type icons | MISSING | ~350 VS Code-derived SVG icons resolved by name/extension/language/MIME | rows show name text only | Port `file-icons.json` + resolver — the single largest visual-fidelity gap in the tree |
| Ignored-row dimming | WRONG VALUE | `opacity(0.52)` on the whole row | `.files-row-ignored { color: var(--rb-text-faint); }` — color only, full opacity | Use opacity to match |
| Selected-row color | WRONG VALUE | neutral `wash(...)` tint | `color-mix(in srgb, var(--rb-accent) 12%, transparent)` — accent-tinted | Use a neutral wash token |
| Chevron rotation animation | INVENTED | icon swaps instantly, no tween | CSS transition rotating a `▸` glyph 90° | Remove the transition |
| File size shown in tree rows | INVENTED | tree rows never show size | `<span className="files-row-size">{formatBytes(entry.size)}</span>` | Remove |
| Symlink name suffix | INVENTED | icon changes, no text glyph appended | appends `" ↪"` to the name | Remove (icons now carry the distinction) |
| Search debounce | WRONG VALUE | 200ms | `SEARCH_DEBOUNCE_MS = 250` (`file-tree-panel.tsx:8`) | Change to 200 |
| Search empty-state text | WRONG VALUE | `"No files found."` | `"No matches."` (`file-tree-panel.tsx:194`) | Match verbatim |
| Search input placeholder | WRONG VALUE | `"Search files"` | `"Search files…"` (`file-tree-panel.tsx:39`) | Match verbatim |
| Search "capped results" banner | MISSING | `"Showing the first 200 matches"` when `results.length >= 200` | no such banner | Add it |
| Search result grouping into an ancestor tree | MISSING | `SearchTreeModel` groups matches by real ancestor directories with expand/collapse and best-score propagation | flat list of exact matches only (`file-tree-panel.tsx:196-213`) | Port `SearchTreeModel` |
| Search result keyboard navigation | MISSING | arrow keys move active, Enter activates, Escape dismisses | click-only | Add keyboard handling |
| Tree row height / indent | MATCHES | 27px row, 8+depth×14 padding | `.files-row{min-height:27px}`, `paddingLeft: 8+depth*14` | none |
| `entryRank`/`compareEntries`/`parentPath`/`isDirectChild` | MATCHES | see §3 | ported verbatim | none |
| `sequenceNeedsResync` and resync handling | MATCHES | see §3 | ported | none |
| Tree pagination shape | MATCHES | 500-entry engine page size, cursor-based "Load more" row | mirrors the cursor/`nextCursor` contract | none |
| New file / new folder creation | N/A | does not exist on desktop either | does not exist on web either | Nothing to port |
| Tree row context menu (rename/delete/copy path) | N/A | does not exist on desktop | does not exist on web | Nothing to port |

## 5. Do not

- Do not implement or mount the editor context menu (§2.4) — the editable
  buffer lives in ticket 25's `file-viewer.tsx`. It is documented here only
  because it is part of `09`'s transcription; ticket 25 owns building and
  wiring `EditorContextMenu`.
- Do not build the Settings → Files page route/UI (§2.6) — that page needs
  a settings shell that doesn't exist until ticket 29. Only implement the
  underlying `filesShowAll` field on the ticket-03 store here; leave
  `filesAutosaveEnabled`/`filesAutosaveDelayMs`/`filesEditorFontSize`/
  `filesWordWrap` as store fields for tickets 25/29 to read and to give a
  page to, respectively.
- Do not build the tree-sidebar split/resize mechanism (`TREE_SPLIT_*`,
  `preview_split_handle`) — that only exists in the desktop's promoted
  editor presentation and is ticket 25's concern (and even there, is
  flagged as a likely-intentional web layout difference, not a hard
  requirement).
- Do not invent a find-in-file, goto-line, new-file, new-folder, or
  tree-row context-menu affordance — none exist on desktop (confirmed by
  research); do not add them under an assumption that they should.
- Do not add a tab-size setting — no such concept exists in
  `crates/ui/src/files` on desktop; the web's `tab-size: 4` CSS default on
  the (ticket-25-owned) editor is a sane browser default, not a ported
  preference.
- Do not touch `FileViewer`, `FileDocument`, or any markdown/image
  rendering — that is ticket 25.
- Do not build the multi-file-tabs-in-the-right-pane architecture itself
  (owned by ticket 07) — this ticket only needs to *call into* whatever
  "open a file as its own tab" API ticket 07 exposes when a tree/search row
  is opened. If ticket 07's API isn't file-shaped yet, keep the current
  one-file-at-a-time behavior in the pane and note the seam in Comments
  rather than guessing ticket 07's surface.

## 6. Acceptance

- [ ] Header/toolbar: search field, toggle-ignored button (icon + dynamic
      tooltip), watch-error banner with a working "Refresh now", root-load
      error state with Retry — all matching §2.1.
- [ ] Tree: 27px rows, 14px indent, file-type icons on every row (folders,
      files, symlinks — resolved via `lib/file-icons.ts`), 0.52 opacity on
      ignored rows, neutral (non-accent) selection wash, no chevron
      rotation tween, `Loading…`/`Empty folder`/`{message} — Retry`/`Load
      more…` states rendering correctly.
- [ ] Tree keyboard navigation: Up/Down/Left/Right/Enter/Space all behave
      per §2.2, scroll the selection into view.
- [ ] Tree row drag-out produces a workspace-path payload with a pill
      ghost.
- [ ] Search: 200ms debounce, `"Search files"` placeholder (no ellipsis),
      `"No files found."` empty state, `"Showing the first 200 matches"`
      banner at the cap, ancestor-tree grouping (not a flat list), keyboard
      nav (arrows/Enter/Escape).
- [ ] The pane's `FilesSurface` targets the active chat's checkout
      (`{ chatId }`), not an arbitrary space; the routed `/files` page still
      targets `{ spaceId }`.
- [ ] File icon assets exist under `public/file-icons/**` with a `dark/`
      mirror generated by `scripts/generate-file-icons.mjs`; `pnpm --filter
      @roboco/icons check` (or the new script's own `--check`) fails on
      drift from `crates/ui/assets/file-icons` / `file-icons.json`.
- [ ] Unit tests:
      - `entry_rank`/`compare_paths` → existing `lib/files.test.ts` (verify
        still green, no port needed)
      - `directory_pages_reject_self_parents_and_ancestors`,
        `directory_pages_reject_non_direct_descendants`,
        `paginated_refresh_prunes_missing_children_only_after_the_last_page`,
        `refresh_removes_deleted_subtrees_and_handles_directory_becoming_file`,
        `ignored_directories_propagate_ignored_state_to_all_descendants` →
        verify existing `lib/file-tree.test.ts` coverage, no re-port needed
      - new: `moveSelection` wrap-around + selectable-only filtering →
        `lib/file-tree.test.ts`
      - `search_tree_uses_path_components_instead_of_name_prefixes` →
        `lib/file-search-tree.test.ts`
      - `folders_resolve_name_and_expansion_state` →
        `lib/file-icons.test.ts`
- [ ] Screenshot pair, desktop vs web, states: empty tree loading, tree
      with mixed files/folders/ignored entries expanded, a row selected via
      keyboard, search with grouped results and the capped-results banner,
      watch-error banner.
- [ ] `pnpm -r build` green; package vitest green.
- [ ] No new literal hex/px where a `--rb-*` token exists.

## Comments

### Implementation (2026-09-18, branch `wp2/24-files-tree-search`)

**What landed:**

- `web/packages/icons/scripts/generate-file-icons.mjs` + generated outputs —
  copies every SVG from `crates/ui/assets/file-icons/` into
  `web/packages/app/public/file-icons/` (354 icons), bakes the `dark/` mirror
  with the seven `dark_icon_svg` accent lifts at generate time, and emits
  `web/packages/icons/src/generated/file-icons-manifest.ts` (the parsed
  manifest; unknown JSON keys — `rootFolderNames`, `hidesExplorerArrows` —
  dropped like serde does, `rootFolder` kept documented-unused). Own
  `--check` gate; `pnpm --filter @roboco/icons check` now runs both
  generators' checks. Asserts the ≥350 floor.
- `lib/file-icons.ts` — the full 6-step resolver (`resolveFileIcon` with
  optional language/MIME hints, `resolveDirectoryIcon`,
  `hasSpecificFileIcon`, `fileIconAssetPath`, `wellBg`), the
  lowercase-key-normalized lookup maps, the compound-extension
  longest-suffix-first walk, and the `less`→`brackets-sky` / `yml`→`yaml`
  alias special cases.
- `components/files/file-icon.tsx` — `FileIcon`, the polychrome `<img>`
  (dark-variant path swap by appearance; `useResolvedAppearance()` added to
  `state/appearance.ts`).
- `lib/file-search-tree.ts` — `buildSearchTree` / `sortSearchPaths` /
  `toggleSearchNode`: the synthetic ancestor tree keyed by path components
  with best-score propagation, dirs-before-files, name, and path
  tiebreaks; own-subtree-only collapse.
- `lib/file-tree.ts` — selection (`select`/`selectNext`/`selectPrevious`
  via `move_selection`, `selectParent`, `selectFirstChild`), the
  toggle/remove/rebuild selection fixups, `expand` (force, no load),
  `expandedDirectories`, `refresh` (the "Refresh now" resync),
  `retryRoot`, `revealInTree` (ancestor listing + page-by-page apply +
  select, error surfaced to the caller), and the snapshot's
  `selected`/`rootLoaded`/`rootError` fields.
- `lib/files-client.ts` — `FilesTarget` widened to the wire
  `WorkspaceTarget` shape (`chatId | spaceId | checkoutPath`, all optional;
  engine requires exactly one of chat/space).
- `routes/files-page.tsx` — `FilesSurface({ chatId })` targets
  `{ chatId }`, seeds the model from `uiSettings.filesShowAll`, and opens
  files through `rightPaneStore.addFileSurface(chatId, path)` (ticket 07's
  file-shaped API). `FilesBody`/`SpacePicker`/`forwardFileEvent` deleted —
  they were the routed-page/space-scoped halves, dead since ticket 04
  removed the route (see Deviations).
- `components/files/file-tree-panel.tsx` — rebuilt: `surface-toolbar` +
  `surface-input` header (magnifier, "Search files" placeholder, 11/16
  metrics), the eye toggle with the verbatim dynamic tooltip (350ms via the
  shared `Tooltip`), the watch-error banner with a working "Refresh now",
  the root-error block with Retry, the blank first-load placeholder, the
  tree (icons, 27px rows, drag-out pill ghost with
  `application/x-roboco-workspace-path` + `text/plain` payloads), full
  keyboard nav (arrows/left-right/enter/space, preventDefault +
  stopPropagation + scrollIntoView), and the search results (200ms
  debounce, grouped ancestor tree, in-place directory toggle, reveal +
  open on Enter/click, arrows/Enter/Escape on the input, capped-results
  banner, drag-out).
- `styles/app.css` — the §2.1/§2.2/§2.3 classes: `.files-row` (27px, gap 4,
  11.5px, square), neutral wash selection (0.12 focused / 0.08 unfocused /
  0.1 search), `.files-row-ignored` opacity 0.52, `.files-chevron`
  (instant icon swap — rotation tween deleted), `.files-row-icon`,
  `.files-toggle-ignored(+-on)`, the watch banner, root-error block,
  search banner/empty states, `.files-drag-ghost`, `.files-search input`;
  removed `.files-toolbar`, `.files-space-picker`, `.files-toggle-on`,
  `.files-body`, `.files-tree-pane`, `.files-page-open`, `.files-row-path`,
  `.files-row-size`, `.files-chevron-open` and the dead right-pane/phone
  viewer-swap rules.
- Tests: `tests/file-icons.test.ts` (10), `tests/file-search-tree.test.ts`
  (7), and 12 new cases in `tests/file-tree.test.ts` (moveSelection
  wrap-around + selectable-only filtering, select/reject, collapse/rebuild
  selection fixups, revealInTree success/failure, refresh, retryRoot,
  failed-root-refresh row error).

**Deviations and judgment calls:**

- **The routed `/files` page no longer exists** (ticket 04 removed it), so
  this ticket's routed-page clauses are N/A: the pane's Files surface is
  the only host, `FilesBody`/`SpacePicker` were deleted, and the
  "routed page still targets `{ spaceId }`" acceptance item is moot.
- **The in-pane `FileViewer` mount went with it.** Ticket 07's host is
  file-shaped, so opening a tree/search row mints that file's own tab
  (`addFileSurface`) exactly as the ticket's "What to build" directs; the
  file tab's body is ticket 07's stub until ticket 25 mounts `FileViewer`
  there. `file-viewer.tsx`/`file-document.tsx` and their CSS are untouched
  for ticket 25. Phone-layer viewer-swap rules (`.files-page-open`) were
  dead after this and removed; the pane host's own surface swap covers
  phone widths.
- **The tab strip's `file` icon stays the monochrome `document` glyph**:
  `RightSurfaceEntry.icon` returns an `IconName`, and the polychrome
  file-type icon needs the surface's path — only ticket 25's file-surface
  body knows it (comment updated in `surface-registry.tsx`).
- **`sync_list_rows` (scroll-anchor preservation) not ported** — the
  ticket marks it optional; `useSyncExternalStore` still re-renders the
  full row list (no virtualization: the 210-file fixture scrolls 220 DOM
  rows fine).
- **`formatBytes` usage**: already absent from tree rows (ticket 04's trim
  removed it); the helper stays exported in `lib/files.ts` for ticket 25.
  `lib/files.ts` needed no other change.
- **Header chrome reuses ticket 07's `.surface-toolbar`/`.surface-input`**
  (the desktop's own `surface_chrome::toolbar`/`input`) instead of
  re-encoding the 38px/24px/6px metrics per-surface; the input's inner
  reset (`.surface-input input`) is added to the shared frame.
- **The engine's listing semantics decide "hidden"**: with
  includeIgnored=false the engine hides gitignore-ignored entries but
  still returns dotfiles (`.env`, `.gitignore`); the smoke fixture stages
  ignored rows via a real `.gitignore`. The client renders whatever the
  RPC returns, matching the desktop.
- **Chat target-change pending (`sync_target` with unsaved docs)** is not
  ported — it only matters with dirty editors (ticket 25); a checkout
  change mid-watch already flows through the watch resync path.

**Verification:**

- `pnpm -r build` green; `@roboco/app` vitest 48 files / 711 tests green;
  `pnpm --filter @roboco/icons check` green (both generators fresh).
- web_smoke round (fresh engine, chat-scoped pane): the pane's Files
  surface on a chat in a staged git space lists the live tree; a file row
  click and a search Enter both mint the file's own pane tab
  (`addFileSurface` verified end-to-end, tabs: Files / component.test.tsx /
  lib.rs); icons resolve live (`dark/files/react-test.svg`,
  `dark/files/rust.svg`, `dark/folders/folder-assets.svg`); ignored rows
  at opacity 0.52 with `filesShowAll` persisted to localStorage; keyboard
  selection moves among selectable rows; Escape clears the search.
- Screenshots (web halves) in `.scratch/web-parity/shots/24/`:
  `web-01-tree-collapsed.png`, `web-02-tree-mixed-expanded.png`,
  `web-03-tree-keyboard-selected.png`, `web-04-tree-ignored-rows.png`,
  `web-05-search-grouped.png`, `web-06-search-capped-banner.png`,
  `web-07-root-error-retry.png`, `web-08-watch-error-banner.png`,
  `web-09-tree-many-expanded.png`, `web-10-boot-live-tree.png`.
  - **Desktop halves of all pairs: skipped, documented per the runbook** —
    no `roboco` desktop process is running and none was started (same skip
    as tickets 09/10).
  - **"Empty tree loading" state skipped**: the local smoke engine answers
    the first listing in well under a frame; staging it would need an
    artificial engine delay, and no `crates/` changes are allowed here.
    The `Loading…`/`Empty folder` rows are covered by the model tests.
  - **Watch-error banner staged** via the engine's protocol-level watch
    failure (a chat with no space: the watch subscribe fails while the
    connection stays up). Killing the engine does NOT stage it — the
    engine-client holds the watch across reconnects (a network drop never
    ends it, by design).
- Boot check: after a full re-pair on a restarted smoke engine the app
  renders with no error boundary and a live chat-scoped tree
  (`web-10-boot-live-tree.png`).

### Shared components addendum (2026-09-18)

Build on components/ui/ + components/base/ (see components/README.md)
— do not hand-roll card shells, cursor lists, menu rows, chips, or
tooltips.
