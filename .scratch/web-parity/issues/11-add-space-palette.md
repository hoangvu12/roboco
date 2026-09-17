# 11 — Add-space palette

**What to build:** A `⌘K`-style command palette for adding a project
("space" in code): a search bar that doubles as a path/breadcrumb navigator,
a live-filtered folder list, a device-and-drives rail on the right, and a
key-hint footer. Reachable from the spaces menu's trailing "New project…"
row (ticket 10 builds that row; this ticket exposes the hook it calls) and
from the `Mod+K` shortcut (ticket 12 wires the binding; this ticket exposes
the same hook). One surface, no wizard steps — picking a device or a drive
rebrowses the same card in place. **This does not exist on the web today**:
there is no folder browser, no device rail, no manual-path entry, no `⌘K`
surface at all. This ticket builds it from scratch.

**Blocked by:** 09 (Popover primitive — this ticket reuses its
open/closing/closed lifecycle, motion, and outside-press guard)

**Status:** ready-for-agent

**Research:** `../../web-client/research/16-sidebar-body-add-space.md`
§3.7–§3.14, §4 (the `AddSpaceFlow::is_stale` rule and the `pickers.rs`
folder-path helpers), §5 rows #13–#16, #18 (partial). Cross-referenced:
`../../web-client/research/05-pickers-popovers.md` §3.1 (`palette_card`
geometry), §3.3 (motion/scrim/outside-press), §3.4 (`Popup<T>` lifecycle —
ticket 09's primitive), §3.9 (key caps/kbd hints), §3.10 (skeleton/error
rows), §3.12 (dialog primitives, for the rename/delete follow-ups ticket 10
owns).

**Desktop reference (for lookups only):**
`crates/ui/src/shell/spaces.rs::open_add_space` (1825),
`::AddSpaceFlow` (186), `::AddSpaceFlow::is_stale` (234),
`::add_space_slash_descend` (2080), `::add_space_filtered` (2011),
`::add_space_completion`/`::add_space_accept_completion` (2131),
`::add_space_pick_device` (1907), `::load_space_drives` (1960),
`::load_space_folders` (2180), `::manual_path_query` (254),
`::prepare_manual_space` (2256), `::submit_browsed_space` (2326),
`::submit_add_space` (referenced near 2504), `::add_space_key` (2460),
`::render_add_space_overlay` (2526), `::close_space_menu` (3278),
`::open_rename_space`/`::submit_rename_space` (3285), `::delete_space`
(render at 3437); `crates/ui/src/pickers.rs::parent_path` (296),
`::child_path` (309), `::completion_prefix_len` (321), `::segment_target`
(338), `::typed_path_target` (361), `::breadcrumbs` (387), `::browser_rows`
(399); `crates/engine/src/space_paths.rs::prepare`; `crates/rpc/src/lib.rs:90-129`
(`LIST_FOLDERS`, `LIST_DRIVES`, `PREPARE_SPACE_PATH`, `MUTATE`);
`crates/ui/src/popover.rs::palette_card` (820), `::modal_glass` (657-705),
`::dialog_card` (978).

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/components/add-space-palette.tsx` | new | `AddSpacePalette` — the whole card (input row, crumbs+list+rail body, footer) |
| `web/packages/app/src/lib/add-space.ts` | new | pure logic: `parentPath`, `childPath`, `completionPrefixLen`, `segmentTarget`, `typedPathTarget`, `breadcrumbs`, `browserRows`, `manualPathQuery`, `pathUnder`, filter/rank helpers |
| `web/packages/app/src/state/add-space.ts` | new | `AddSpaceFlow` state machine: open/close, device pick, browse/load, search-edit decision tree, keyboard, submit; exposes `addSpaceStore.open(startDeviceId?)`/`.close()` for ticket 10's menu row and ticket 12's `Mod+K` binding to call |
| `web/packages/app/src/components/sidebar-body.tsx` | edit | mount `<AddSpacePalette />` as a sibling (like `SidebarNotice`/`ConnectionPill`) — renders nothing while closed |
| `web/packages/engine-client/src/methods.ts` | edit | add `LIST_FOLDERS = "ListFolders"`, `LIST_DRIVES = "ListDrives"`, `PREPARE_SPACE_PATH = "PrepareSpacePath"` (none of the three exist today — confirmed by grep, zero call sites anywhere in `web/`) |
| `web/packages/proto/src/shims.ts` | edit | fix `PrepareSpacePathReply` — currently `export type PrepareSpacePathReply = string;` (`shims.ts:79-80`), but the wire reply is an object `{path, exists, gitDetected}` per `crates/engine/src/space_paths.rs::SpacePath`; replace with a named interface (see §3.5's RPC table) |
| `web/packages/app/src/styles/app.css` | new classes | `.add-space-*` (card, input row, key caps, crumbs, folder rows, rail, footer) — none of this exists today |

---

## 1. Context a fresh session needs

- **Vocabulary**: "space" in code == "project" in every user-facing string
  on this surface (`"New project…"`, `"Rename project"`, `"Project name"`,
  `"Remove project?"`). Do not use "space" in any string the user sees.
- This is a genuinely new surface — there is no existing web component to
  extend. It leans entirely on ticket 09's `Popup<T>`-equivalent lifecycle
  (open → closing → closed, a 100ms unmount delay, `pointer-events: none`
  while closing) and its outside-press guard (outside `pointerdown` closes
  and swallows that press; the following click reaches whatever is behind
  normally). If ticket 09 hasn't landed a reusable hook/component yet, build
  the three-state machine locally in `state/add-space.ts` rather than
  duplicating ad hoc logic — this surface's `is_stale` guard (§3.4 below)
  already needs similar bookkeeping.
- **RPC**: none of `ListFolders`/`ListDrives`/`PrepareSpacePath` exist in
  `web/packages/engine-client/src/methods.ts` today, and there are zero call
  sites anywhere under `web/`. The wire TYPES already exist and match the
  Rust exactly: `FolderEntry`/`FolderListing`/`DriveEntry`/`DriveListing` are
  generated (`web/packages/proto/src/generated/{FolderEntry,FolderListing,DriveEntry,DriveListing}.ts`)
  and need no changes. The one mismatch: `PrepareSpacePathReply` is
  hand-shimmed as a bare `string` (`proto/src/shims.ts:79-80`) but the actual
  reply is `SpacePath { path: string, exists: boolean, gitDetected: boolean }`
  — fix the shim (§0's table).
- `Mutate` with `op: "createSpace"`/`"renameSpace"`/`"deleteSpace"` already
  has generated params (`web/packages/proto/src/generated/MutateParams.ts`)
  — no shim work needed there, just call it.
- **Device/drive rail data**: `Device.platform: string` is already on the
  wire (`web/packages/proto/src/generated/Device.ts`). There is currently no
  web helper mapping a platform string to an icon or computing "is this
  device online" — build both locally in this ticket (§2.4's rail spec) since
  no shared helper exists yet to reuse; check `state/fleet.ts`/`snapshot.devices`
  first in case an online/offline signal already exists before inventing one.
- This surface is a single `AddSpaceFlow`-shaped state object, not a wizard —
  picking a device or a drive re-browses the SAME card in place. Model it as
  one state object in `state/add-space.ts`, not a multi-step form.
- Motion/tokens this ticket needs from ticket 02: `MENU_IN`/`MENU_OUT` (140ms/100ms,
  `EASE`), a 0.35 scrim alpha for `modal_glass` (vs the standard 0.6 `modal`),
  `CARD_RADIUS`/`--rb-radius-panel`-equivalent constants are NOT reused here —
  this card has its own `corner_radius = 14px`, distinct from the generic
  popover card's 12px.
- `prefers-reduced-motion: reduce`: entrance/exit tweens snap to their
  endpoints; the skeleton pulse (§2.4) freezes at a static frame (already the
  app-wide rule, per `motion.rs:118`).

---

## 2. Spec

### 2.0 `AddSpaceFlow` — the state shape

Not rendered — the single state object everything below reads/writes. Port
of `spaces.rs:186-227`.

```ts
interface AddSpaceFlow {
  readonly identity: string;              // uuid stamped once per open
  readonly revision: number;               // bumped on every browse/device-switch
  readonly deviceId: string | null;        // currently browsed device
  readonly browserPath: string | null;     // current listing path (null = "home not yet loaded")
  readonly home: string | null;            // resolved home path for the current device, once known
  readonly query: string;                  // the search input's text
  readonly hiddenQuery: boolean;           // query starts with "."  -> show dotfiles
  readonly listing: "idle" | "loading" | { error: string } | { entries: FolderEntry[] };
  readonly drives: DriveEntry[];           // best-effort; empty on failure, no error UI
  readonly active: number;                 // keyboard-highlighted row index into the FILTERED rows
  readonly manualPath: { path: string; exists: boolean; gitDetected: boolean } | null;
  readonly submitBusy: boolean;
  readonly error: string | null;           // footer error line
}
```

**On open** (`open_add_space`, `:1825-1904`): pick the local device (else the
first registered device) as the starting tab; create a fresh search input in
a keybinding context that leaves arrows/Enter/Tab unbound so they bubble to
the card's key handler (§2.5) instead of moving the caret or inserting a
newline; if a device exists, kick off `loadSpaceFolders(null)` (browse home)
and `loadSpaceDrives()` concurrently. Mint a fresh `identity` (any UUID
source) every open.

#### `AddSpaceFlow.isStale(identity, revision, deviceId): boolean` — `spaces.rs:234-243`

Every async response (`ListFolders`, `ListDrives`, `PrepareSpacePath`) is
guarded by three checks before it's allowed to mutate state:

1. `identity` — a value stamped once per palette OPEN; any response from a
   prior open (already closed) is dropped.
2. `revision` (only checked when the caller passes one) — bumped on every
   browse/device-switch; a superseded in-flight browse is dropped even
   within the same open.
3. `deviceId` — dropped if the flow's browsed device changed under the
   in-flight call.

This has nothing to do with chat/session staleness (`SESSION_STALE_MS`,
ticket 08) despite the name overlap — it is strictly this palette's
async-response guard. Port it as a plain function taking a snapshot of
`(identity, revision, deviceId)` captured at request time and the current
flow state, returning whether the response should be dropped.

---

### 2.1 Search input behavior — typing, slash-descend, tab-complete

`spaces.rs:1839-1873`. Runs this decision tree on every keystroke, in order:

1. **Slash-descend** (`addSpaceSlashDescend`, port of `:2080-2129`): if the
   query ends in `/`:
   - If it also starts with `/` or `~` (a full path), resolve it via
     `typedPathTarget` (§3) and descend directly.
   - Else, treat the text before the `/` as a folder-name segment: resolve it
     against the current listing's folder names via `segmentTarget` (§3) —
     exact case match → exact case-insensitive match → unique prefix;
     ambiguous ⇒ `null`, slash stays in the query as an honest "no match"
     signal — and descend into it.
   - If either resolves, clear the query and return `true` immediately
     (skip the rest of the tree).
2. **Manual path query** (`manualPathQuery`, §3): if the (post slash-check)
   text looks like an absolute/home/Windows-drive path, route to
   `prepareManualSpace(create=false, submit=false)` instead of browsing —
   this validates existence/git-detection on the ENGINE (not locally; the
   engine may be remote) without creating anything yet.
3. Otherwise, it's a plain filter query: `hiddenQuery = text.startsWith(".")`
   — typing a leading `.` reveals dotfiles hidden by default; changing this
   triggers a RELOAD of the current folder (the engine's `ListFolders`
   response omits hidden entries unless asked).

**Filtering** (`addSpaceFiltered`, port of `:2011-2029`): directories only
(`browserRows` — files never appear), hidden-name filter applied
client-side too (redundant with the reload above, cheap insurance against a
stale listing), then ranked via `popover::filterIndices` (ticket 09/10's
substring-rank, prefix-first helper — reuse it, do not reimplement).

**Tab completion** (`addSpaceCompletion`/`addSpaceAcceptCompletion`, port of
`:2131-2166`): the ghost suffix shown in the input is computed from the
highlighted row if its name is a case-insensitive prefix of the query, else
the FIRST filtered row that is. `Tab` replaces the query text with the full
folder name (does not descend — only `/` or Enter does that).

---

### 2.2 Device pick + drives rail (data)

**`addSpacePickDevice(deviceId)`** (port of `:1907-1936`): a no-op if the
same device is re-clicked or a submit is in flight. Otherwise: bump
`revision`, clear the manual-path state, reset browser/drives to idle, clear
the search text, and re-issue both `loadSpaceFolders(null)` (home) and
`loadSpaceDrives()` for the new device.

**`loadSpaceDrives()`** (port of `:1960-2007`) — calls `ListDrives`; only
adds `targetDeviceId` to the params when the flow's device is NOT the local
device (so local calls skip the relay hop entirely). Failures are silent —
the Locations section just stays at "Home" only; there's no dedicated
drives-error UI (the folder browser's own error row already covers "device
didn't respond").

**`loadSpaceFolders(path)`** (port of `:2180-2253`) — calls `ListFolders`,
same `targetDeviceId` relay rule. On success, if the browse was pathless
(`path: null`, i.e. "go home"), the resolved `listing.path` is remembered as
`flow.home` — this is what lets the breadcrumbs fold everything up to home
into the device crumb (§2.3). Guards against a stale response with THREE
extra checks beyond `isStale`: the flow's `browserPath` must still match the
path this response answers, `hiddenQuery` must not have changed underneath
it, and the search text must not have since become a manual path query.

---

### 2.3 Folder list, breadcrumbs, and manual path entry

**Folder rows** (port of `:2949-3000`) — a nav-style menu row per filtered
entry:

| Element | Value |
| --- | --- |
| icon | `FOLDER`, `15px`, `text_muted.opacity(0.8)` |
| name | `flex-1; min-w:0; truncate` |
| trailing icon | `GIT_BRANCH`, `13px`, `text_muted.opacity(0.5)`, shown ONLY when `entry.isRepo` — "the row you're usually hunting for announces itself" |
| keyboard-highlighted row | also gets `card_selected_shadows()` (05's inset-ring recipe: `box-shadow: inset 0 0 0 1px rgb(var(--rb-hairline)/0.09)`) layered on top of the row wash |
| list wrapper | `flex-1; min-h:0; py 6px` OUTER gutter (deliberately outside the scroll viewport — in-content padding can't survive wheel-scroll clamping or scroll-to-item edge-pinning) |
| inner scroll region | `px 8px; flex column; gap 2px` (the app-wide list rhythm) |

**Empty/loading/error states** (verbatim, port of `:2886-2943`):

| State | Content |
| --- | --- |
| loading | `skeleton_rows(6)` (05 §3.10: flex column, gap 6px, py 4px; each row h 28px, radius 6px, `bg ink(0.04)`, pulsing opacity `0.35 + 0.4*pulse`, staggered 0.08 per row) in a `px 8px; py 6px` frame |
| folder-level error | `error_row(message)` (flex column, gap 6px, `p 8px`, `ui_rems(12.0)`, `color theme.danger`) + a bordered "Retry" chip. If the engine's error string contains the word `"folder"`, show it AS-IS; otherwise replace it with `"{device.name} didn't respond — is it online?"` |
| empty, no query | `"No folders here"` |
| empty, with query | `"No folders match"` |

**Breadcrumbs** (`breadcrumbs(path)`, §3): rendered `font-family: monospace,
ui_rems(11.0)`. Folding rules, in priority order:

1. The device-name crumb ALWAYS leads, and stands in for the segments up to
   `home` — clicking it re-browses home (`loadSpaceFolders(null)`).
2. If the browsed path is under a DRIVE mount (not the bare `/` system
   root), a second crumb named after the drive folds everything up to the
   mount — clicking it browses that mount.
3. Whichever of (1)/(2) applies, its segment count is skipped from the
   generic path-segment crumbs that follow; the LAST crumb (current folder)
   is never clickable, styled `text.opacity(0.85)`; ancestors are
   `text_muted.opacity(0.55)`, hover → `text`.
4. `/` separators between crumbs, `text_faint.opacity(0.7)`.

**Manual path entry** — `manualPathQuery` (§3) recognizes absolute (`/…`),
home-relative (`~…`), Windows drive-letter (`C:\…`), or bare
backslash-leading paths as a "not a browse, a typed target" query.
`prepareManualSpace(create, submit)` (port of `:2256-2306`) calls
`PrepareSpacePath` with `{path, createIfMissing, targetDeviceId}`; on success
it stores the returned `{path, exists, gitDetected}` on the flow — if
`submit && path.exists`, it immediately proceeds to `submitBrowsedSpace`.
**A missing folder is never silently created on plain Enter** — `Enter`
alone (`addSpaceOpenActive` → `prepareManualSpace(false, true)`) leaves
`exists: false` and the submit chip switches to "Create and add"; only
`⌘Enter` (`submitAddSpace` → `create = !path.exists`) actually creates it.

**Submitting a browsed folder** (`submitBrowsedSpace`, port of `:2326-2437`):
if a space already exists for `(deviceId, path)`, no new space is minted —
close the palette and navigate into the existing space. Otherwise
optimistically push a synthetic `Space` row (a client-generated id) into
local state before the RPC round-trip lands, and roll it back (remove by id)
if `Mutate{op:"createSpace", …}` fails, showing the engine's error string
inline in the footer.

---

### 2.4 The palette card layout

Port of `render_add_space_overlay`, `spaces.rs:2526-3274`. Built on the
`palette_card` recipe (`popover.rs:820`, cited from `05-pickers-popovers.md`
§3.1): explicit width, caller's radius, `border 1px hairline(0.10)`, glass
tint on frost platforms / `theme.surface_overlay` on opaque, `shadow_lg`,
`overflow: hidden`, flex column, `color: theme.text`, **no padding**.

**Card**: `width 680px`, `corner_radius 14px` (local to this surface — do NOT
reuse the generic 12px `CARD_RADIUS`), wrapped in `modal_glass` (05 §3.3: a
viewport-sized `occlude()`d scrim centred on this card, **scrim alpha 0.35**
— lighter than the standard `modal`'s 0.6 — frost radius matching the card's
14px, `dialog_in` 180ms entrance).

**Card children (in order)**: input row → body (crumbs+list beside the rail)
→ footer.

#### Input row (46px)

| Property | Value |
| --- | --- |
| height | `46px`, flex-none |
| border-radius | top corners = `14px` (matches the card) |
| padding | `pl 12px; pr 10px` |
| gap | `10px` |
| background | `band()` (05 §3.0: dark `hsla(0,0,0,0.16)`, light `hsla(0,0,0,0.045)`) — the recessed translucent-black header/footer tone |
| border-bottom | `1px solid hairline(0.06)` |

Children, in order:
1. `⌘K` key-cap chip: `COMMAND` icon 11px + `"K"`, using the `key_chip`
   recipe (05 §3.9's `key_cap`: `h 22px; px 6px; radius 5px; gap 2px; bg
   ink(0.05)`), text `ui_rems(11.0); font_mono; text_muted.opacity(0.7)`.
2. The search input — `flex-1; min-w:0; ui_rems(14.0)`.
3. The submit chip (below).
4. An `"esc"` key-cap chip (same recipe, clickable, closes the palette).

**Submit chip** (`btn_primary` base, port of `:2644-2688`): `h 22px; px 8px;
py 0; radius 5px` (overriding `btn_primary`'s own 8px radius to match the
key-caps beside it), `gap 4px; ui_rems(12.0)`. Content:
- `COMMAND` icon 11px + `"Enter"` — normal state
- `"Create and add"` — when the current manual path doesn't exist yet
- `"Adding…"` — while `submitBusy` (icon hidden)
- `opacity(0.6)` — when there's nothing submittable yet (no listing AND no
  resolved manual path)

#### Body (fixed 330px)

`h 330px; flex row; items-stretch` — FIXED height so sparse folders, loading
skeletons, and device switches never resize the card. Left:
`flex-1; min-w:0; flex column` holding `crumbs` then `list` (§2.3). Right:
the rail (below).

**Rail**: `w 196px; flex-none; border-left hairline(0.06); px 8px; py 8px;
flex column; gap 2px; overflow-y: scroll`.

1. "Devices" section label — `px 8px; pt 2px; pb 4px; ui_rems(11.0); MEDIUM;
   text_muted.opacity(0.6)`.
2. One row per device — `h 28px; px 8px; radius 8px; gap 8px; ui_rems(12.5)`.
   Platform icon (14px, `text_muted.opacity(0.8)`): `LAPTOP` for
   macos/darwin, `GLOBAL` for web, `SMARTPHONE` for ios/android, `MONITOR`
   otherwise. Name (`flex-1; truncate`). Trailing presence dot — `5px`
   circle, online = `success.opacity(0.9)` + a 6px soft glow shadow, offline
   = `ink(0.22)`. Selected row: `card_selected_bg()` + `card_selected_shadows()`
   + `theme.text`; unselected: `text_muted.opacity(0.7)`, hover →
   `theme.element_hover`.
3. A `1px` hairline divider (`mx 2px; my 6px`), then "Locations" section
   (same header style) — only rendered when at least one location row
   exists (i.e. a device is selected): `Home` (icon `HOME`) always first,
   then one row per loaded drive (icon `HARD_DRIVE`). Same row
   recipe/selection style as devices; the active row is whichever root the
   CURRENT browsed path falls under (`activeLocation`, longest-mount-prefix
   wins, home outranks a covering drive — use `pathUnder`, §3).
4. A second hairline divider, then an info line: `INFO_CIRCLE` icon 12px +
   `"Showing folders from {device_name} only"`, `ui_rems(11.0); line-height
   15px; text_muted.opacity(0.5)`.

#### Footer

`flex-none; border-radius-bottom 14px; bg band(); border-top hairline(0.06);
px 12px; py 8px; flex row; items-center; gap 12px`. Children (05 §3.9's key
hint primitives, geometry cited there — do not rebuild them, reuse whatever
ticket 09/10 exposes):
`key_hint_pair(ARROW_UP, ARROW_DOWN, "Navigate")`,
`key_hint(ARROW_LEFT, "Up")`,
`key_hint(ARROW_RIGHT, "Open")`,
`key_hint_text("tab", "Complete")`,
then, only when `flow.error` is set: a truncating `ui_rems(11.0); color
theme.danger` message.

**Dismiss**: outside mouse-down on the card closes the palette (clicking the
scrim dismisses, same as Escape).

---

### 2.5 Keyboard — `add_space_key`

Port of `spaces.rs:2460-2522`. Bubbles from the focused search input (a
keybinding context that leaves these keys unbound for text editing, so they
reach this handler instead of moving the caret).

| Key | Handler | Note |
| --- | --- | --- |
| `→` (Right) | `addSpaceOpenActive` | Opens the highlighted (filtered) folder; NOT the text caret |
| `←` (Left) | `addSpaceGoUp` | Ascends to `parentPath` of the current listing |
| `Tab` | `addSpaceAcceptCompletion` | Fills the query with the previewed folder name; does not descend |
| `Esc` | close the palette | stop propagation |
| `↑`/`↓` | move `active` via `menuStep` over the FILTERED row count, scroll to item | wraps |
| `Enter` | `addSpaceOpenActive` | alias of `→` — opens the highlighted folder (or, if the query is a manual path, resolves that path) |
| `⌘Enter` / `Ctrl+Enter` | `submitAddSpace` | Adds the folder the browser is CURRENTLY STANDING IN (not the highlighted subfolder) — "the usual target is the folder you're standing in, full of subfolders" |
| `Backspace` (query empty only) | `addSpaceGoUp` | Backspace on non-empty text edits the text normally |

Typing `/` is handled OUTSIDE this function, in the search-edit decision tree
(§2.1) — it is not a keydown special case.

---

### 2.6 Mounting and the open/close hook

**Where it mounts**: `<AddSpacePalette />` renders as a sibling inside
`sidebar-body.tsx` (alongside `SidebarNotice`/`ConnectionPill`/`AccountRow`)
— it renders `null` while closed, matching the desktop's `render_overlays`
appending it at the shell level regardless of what's currently selected.

**The hook other tickets call**: `state/add-space.ts` exports an
`addSpaceStore` (module-level singleton, same shape as `sidebarStore`/
`fleet`'s stores) with:

```ts
addSpaceStore.open(startDeviceId?: string): void   // mints identity, kicks off the initial browse
addSpaceStore.close(): void
useAddSpace(): AddSpaceFlow | null                 // null when closed
```

- Ticket 10's spaces-menu "New project…" row calls `addSpaceStore.open()`
  (no arg — defaults to the local device per §2.0) and closes the spaces
  menu first (matching the desktop's `close_space_menu` always running
  before this overlay opens).
- Ticket 12's `Mod+K` binding calls `addSpaceStore.open()` directly (the
  desktop's is a fixed, non-rebindable binding — `shell.rs:362-364`).

---

## 3. Pure logic to port

All of these are pure string/slice functions with no engine dependency —
port verbatim into `lib/add-space.ts`.

### `parentPath(path): string | null` — `pickers.rs:296-306`

Trims a trailing `/`; `null` for `"/"` or `""` (root has no parent); a
`/` found at index 0 returns `"/"`; otherwise the substring before the last
`/`.

Test cases (`folder_paths_and_breadcrumbs`, `pickers.rs:4884-4888`):
`parentPath("/home/w/dev") === "/home/w"`,
`parentPath("/home") === parentPath("/home/") === "/"`,
`parentPath("/") === null`, `parentPath("") === null`.

### `childPath(base, name): string` — `pickers.rs:309-315`

`base + "/" + name` unless `base` already ends in `/`, in which case just
`base + name`. `childPath("/home", "w") === "/home/w"`;
`childPath("/", "home") === "/home"`.

### `completionPrefixLen(name, query): number | null` — `pickers.rs:321-332`

Case-insensitive char-by-char prefix match; returns the length into `name`
(not `query`) so slicing `name.slice(len)` yields the real-cased remainder.
`null` if `query` isn't a prefix of `name` (including when `query` is longer
than `name`). Handles multibyte names by only ever returning a length at a
code-point boundary: `completionPrefixLen("héllo", "hé") === 2` (JS string
indices are UTF-16 code units, not bytes — port the INTENT, "slice at a
valid boundary", not the Rust byte-length verbatim), and
`"héllo".slice(2) === "llo"`. Empty query → `0`.

### `segmentTarget(names, query): number | null` — `pickers.rs:338-354`

Resolves a slash-terminated typed segment against a folder-name list, in
priority order: (1) exact case-sensitive match; (2) exact case-INsensitive
match (`completionPrefixLen(n, query) === n.length`); (3) a UNIQUE
case-insensitive prefix match (ambiguous → `null`, i.e. more than one
candidate prefix-matches).

Test (`segment_target_resolution`, `pickers.rs:4914-4925`) with
`names = ["github", "GitHub", "worktree"]`:
`segmentTarget(names, "GitHub") === 1` (exact case wins over the
case-insensitive sibling `"github"`), `segmentTarget(names, "github") === 0`,
`segmentTarget(names, "WORKTREE") === 2` (no exact-cased hit,
case-insensitive exact still resolves), `segmentTarget(names, "work") === 2`
(unique prefix), `segmentTarget(names, "g") === null` (`"github"` and
`"GitHub"` both prefix-match — ambiguous), `segmentTarget(names, "x") ===
null` (no match).

### `typedPathTarget(query, home): string | null` — `pickers.rs:361-384`

Interprets a query as an absolute or home-relative path JUMP (not a folder
search). `~` alone (or `~/`) → `home`; `~/rest` → `` `${home}/${rest}` ``
(trailing slash trimmed); requires `home` to be known (`null` before the
first home browse lands) — `~foo` (no slash after `~`) is NOT a path, it's a
folder name search, so returns `null`. A leading `/` → the trimmed absolute
path as-is (`/` alone stays `/`). Anything else (a bare relative name like
`"src"`) → `null`.

Test (`typed_path_target_expands_absolute_and_home_paths`, `pickers.rs:4928-4948`)
covers all these branches including `typedPathTarget("~/github", null) ===
null` (home not yet known) vs `typedPathTarget("/disk2", null) === "/disk2"`
(absolute paths don't need home at all).

### `breadcrumbs(path): Array<[label: string, fullPath: string]>` — `pickers.rs:387-396`

Always starts with `["/", "/"]`, then one entry per non-empty `/`-split
segment, accumulating the full path as it goes. `breadcrumbs("/home/w/dev")`
labels = `["/", "home", "w", "dev"]`, and the THIRD entry's full path is
`"/home/w"`. `breadcrumbs("/").length === 1`.

### `browserRows(listing): FolderEntry[]` — `pickers.rs:399-401`

Filters a `FolderListing`'s entries to `isDir` only — files never appear in
the folder browser.

### `pathUnder(path, base): boolean` — `spaces.rs:497-500`

Segment-aware "is `path` at or under `base`": `/media/a` is under `/media`
but NOT under `/media/ab` (naive prefix matching would wrongly say yes). An
empty/root base (`""`) covers everything. Used to pick which rail Location
row (`Home` vs a `Drive`) owns the currently browsed path — longest matching
base wins.

### `manualPathQuery(text): boolean` — `spaces.rs:254-260`

`true` when the TRIMMED text starts with `/`, `~`, `\`, or has `:` as its
second character (Windows drive letter, e.g. `C:`). Governs the fork between
"browse the folder list" and "resolve/prepare a typed path" everywhere in
the palette (search-edit routing §2.1, Enter/`⌘Enter` routing §2.3/§2.5).

### `AddSpaceFlow.isStale(identity, revision, deviceId): boolean` — `spaces.rs:234-243`

See §2.0 above for the full three-part rule.

---

## 4. Data — RPC methods

All three folder/drive/path methods are relay-forwardable: a
`targetDeviceId` param is added ONLY when the browsed device differs from
the local device, so calls to the local engine skip the relay hop.

| Method | When | Params | Response | Notes |
| --- | --- | --- | --- | --- |
| `ListFolders` | every browse (home, descend, ascend, drive/location jump, typed-path jump) | `{ query: string, path?: string, targetDeviceId?: string }` — `path` omitted means "browse home" | `FolderListing { path: string, entries: FolderEntry[], truncated: boolean }`, `FolderEntry { name, isDir, isRepo }` — **already generated, no shim work** | `query` is forwarded so the engine could in principle pre-filter (the client also re-filters locally via `filterIndices`) |
| `ListDrives` | once per device pick, alongside the initial folder browse | `{ targetDeviceId?: string }` | `DriveListing { drives: DriveEntry[] }`, `DriveEntry { name, path }` — **already generated, no shim work** | Best-effort; failure just leaves the rail at Home only |
| `PrepareSpacePath` | on every edit of a manual (typed-path) query, and again on submit | `{ path: string, createIfMissing: boolean, targetDeviceId: string }` | `{ path: string, exists: boolean, gitDetected: boolean }` — **fix the shim** (§0) | `targetDeviceId` is REQUIRED here (unlike the two above) — engine-side path syntax (`~`, drive letters) is resolved on the OWNING device, never assumed local. Rules (`space_paths.rs::prepare`): `~`/`~/x` expand against that engine's home dir; Windows-style paths (`\`, `C:`) are rejected on non-Windows engines; the path must be absolute; a file path errors (must be a directory); `gitDetected = path.join(".git").exists()` |
| `Mutate` (`op: "createSpace"`) | submit | `{ op: "createSpace", spaceId, deviceId, path, gitDetected }` | — | `spaceId` is a CLIENT-minted id; the engine dedupes an existing `(deviceId, path)` pair rather than erroring |
| `Mutate` (`op: "renameSpace"`) | rename dialog submit (ticket 10 builds the dialog; this ticket's `close_space_menu`/rename-open plumbing is described below for completeness) | `{ op: "renameSpace", spaceId, name }` | — | empty trimmed name is a no-op |
| `Mutate` (`op: "deleteSpace"`) | delete confirm | `{ op: "deleteSpace", spaceId }` | — | |

### `close_space_menu` / rename / delete overlays (context only — ticket 10 builds the UI)

`close_space_menu` (`spaces.rs:3278-3283`) is the single close path for the
space context menu (05's §3.22 documents its rows/geometry) — it always
funnels through the Popup close→reap exit-animation sequence, never an
instant unmount. Called from this palette's outside-mouse-down and from the
"Rename…"/"Remove…" row clicks (the menu always closes before its follow-up
dialog opens). This ticket does not build the context menu or its dialogs
(ticket 10) — it only needs to know that opening the add-space palette from
the spaces menu closes that menu first, same as everything else that opens
on top of it.

**Rename dialog** — verbatim text: title `"Rename project"`, input
placeholder `"Project name"` prefilled with the space's current display
name. Buttons: `"Cancel"` (`btn_ghost`) / `"Rename"` (`btn_primary`). Escape
cancels; Enter submits.

**Delete confirm dialog** — verbatim text: title `"Remove project?"`. Body
(singular vs plural, curly quotes, transcribed exactly):

```
"Removing "{name}" permanently deletes its 1 session on {device}. This can't be undone."
"Removing "{name}" permanently deletes its {count} sessions on {device}. This can't be undone."
```

`name` = the space's display name or `"this project"` if the row is already
gone; `device` = the owning device's name or `"its device"`; `count` = the
number of chats in that space. Buttons: `"Cancel"` (`btn_ghost`) / `"Remove"`
(`btn_danger`).

These two dialogs are ticket 10's to build (they hang off the space context
menu, not this palette) — copied here only so the exact strings exist
somewhere the next ticket can find them without re-reading the Rust.

---

## 5. Gaps this ticket closes

From `16-sidebar-body-add-space.md` §5:

| # | Item | Kind | Desktop value | Web value | Fix |
| --- | --- | --- | --- | --- | --- |
| 13 | Add-space palette | MISSING (entire surface) | `⌘K`-style modal: search+breadcrumbs+folder list+device/drives rail+footer (§2.0–§2.4) | Does not exist. "New project…" and the shortcut have no target on the web at all | Build per this ticket |
| 14 | Manual/typed path entry, slash-descend, tab-complete | MISSING | `manualPathQuery`, `addSpaceSlashDescend`, `addSpaceCompletion` (§2.1, §3) | N/A (no palette) | Part of #13 |
| 15 | Space rename dialog copy | MATCHES (once built) | `"Rename project"` / `"Project name"` (§4) | N/A — ticket 10 builds the dialog | Use these exact strings when ticket 10 builds it |
| 16 | Space delete confirm copy | MATCHES (once built) | Exact singular/plural body, curly quotes (§4) | N/A — ticket 10 builds the dialog | Use the exact interpolated strings, singular/plural branch included |
| 18 | `sidebarVisibleOrder` (keyboard/jump order) | MISSING (cross-ref) | — | — | Not this ticket — ticket 08 exports it, ticket 12 consumes it. Listed here only because 16's own gap table cites it; irrelevant to the add-space surface itself |

Also relevant, not in 16's numbered list but load-bearing: the
`PrepareSpacePathReply` shim mismatch (§0/§4) — this is a **shipped bug
independent of parity**: any code that called `PrepareSpacePath` today (none
does yet) would get a bare string where an object is expected. Fixing it is
part of this ticket's RPC wiring, not a pre-existing regression to avoid
touching.

---

## 6. Do not

- Do not build the space context menu (Rename…/Remove… rows) or its two
  dialogs — ticket 10 (`05-pickers-popovers.md` §3.22). This ticket only
  documents their exact strings (§4) for ticket 10 to use.
- Do not build the spaces-menu "New project…" row itself — ticket 10. This
  ticket only exposes `addSpaceStore.open()` for that row to call.
- Do not bind `Mod+K` — ticket 12. This ticket only exposes
  `addSpaceStore.open()` for that binding to call.
- Do not invent a wizard/multi-step flow. Picking a device or a drive
  re-browses the same card in place — there is exactly one `AddSpaceFlow`
  state object, never a stack of steps.
- Do not silently create a folder on plain `Enter`. Only `⌘Enter`/`Ctrl+Enter`
  (`submitAddSpace`) creates a missing folder; `Enter` alone
  (`addSpaceOpenActive`) only resolves/previews it (§2.3, §2.5).
- Do not reuse the generic 12px `CARD_RADIUS`/`--rb-radius-panel` for this
  card — it uses its own 14px `corner_radius`, and its scrim is 0.35 alpha
  (`modal_glass`), not the standard 0.6 (`modal`).
- Do not send `targetDeviceId` on `ListFolders`/`ListDrives` when the
  browsed device IS the local device — only `PrepareSpacePath` requires
  `targetDeviceId` unconditionally (§4's table). This relay-forwarding rule
  is NOT desktop-only — the web is just as capable of pairing with a remote
  engine and browsing another device's folders, so implement it for real,
  not as a stub.
- Do not treat the Windows drive-letter branch of `manualPathQuery`
  (`text[1] === ":"`) as a "runs on Windows" check — it matters because the
  ENGINE being browsed might be a Windows machine even if the browser/client
  is not. Keep it unconditionally.

## 7. Acceptance

- [ ] `addSpaceStore.open()` opens the card centered on a 0.35-alpha scrim,
      starting on the local device (or the first registered device),
      browsing home, and loading drives concurrently (§2.0, §2.2)
- [ ] Typing filters the folder list via substring/prefix ranking; a leading
      `.` reveals dotfiles and reloads the folder; a trailing `/` on a
      resolvable segment or full path descends immediately and clears the
      query (§2.1)
- [ ] `Tab` fills the query with the previewed folder's full name without
      descending; `→`/`Enter` opens the highlighted folder; `←`/`Backspace`
      (on an empty query) ascends one level (§2.3, §2.5)
- [ ] Typing an absolute (`/…`), home-relative (`~…`), or Windows
      drive-letter (`C:\…`) path switches to manual-path mode: the submit
      chip reads "Create and add" when the path doesn't exist yet; plain
      `Enter` never creates it; `⌘Enter` does (§2.3)
- [ ] Breadcrumbs fold the device name over everything up to home, fold a
      drive-mount crumb when applicable, and the last crumb is never
      clickable (§2.3)
- [ ] The rail lists every device with the correct platform icon and an
      online/offline presence dot, and Home + loaded drives under
      "Locations", with the currently-browsed root highlighted (§2.4)
- [ ] Picking a different device re-browses home and reloads drives for that
      device in place — no navigation to a different screen (§2.2)
- [ ] Submitting a folder that already has a space for `(deviceId, path)`
      closes the palette and lands in the existing space without a new RPC
      call; submitting a new one shows it optimistically, then rolls back
      with an inline error if `createSpace` fails (§2.3)
- [ ] A stale `ListFolders`/`ListDrives`/`PrepareSpacePath` response (device
      switched, palette closed and reopened, or a newer browse superseded
      it) never mutates state (§2.0's `isStale` + the three extra
      `loadSpaceFolders` guards)
- [ ] Outside `pointerdown` and `Escape` both close the palette; `Escape`
      does not fire twice from a single keystroke reaching two handlers
- [ ] Unit tests (port into `web/packages/app/tests/` or a co-located
      `add-space.test.ts`, mirroring the desktop names):
      `folder_paths_and_breadcrumbs` (parentPath/breadcrumbs cases),
      `segment_target_resolution`,
      `typed_path_target_expands_absolute_and_home_paths`
- [ ] Screenshot pairs, desktop vs web: (a) palette open on home, empty
      query; (b) typed manual path that doesn't exist yet (submit chip reads
      "Create and add"); (c) a folder highlighted with the git-branch repo
      indicator visible; (d) the rail with two devices, one offline; (e)
      folder-level error state with the "Retry" chip
- [ ] `pnpm -r build` green; package vitest green
- [ ] No new literal hex/px where a `--rb-*` token exists

## Comments

(empty; appended during implementation)
