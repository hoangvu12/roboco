# 03 — Client settings store

Status: done

**What to build:** The desktop persists every device-local preference — sidebar
geometry, sidebar organization/sort/visibility toggles, sound/notification
toggles, right-pane and terminal geometry, the keymap, appearance, font,
files/diff prefs, and more — in one `ui-settings.json`, loaded once at boot,
healed/clamped on every load, and written back through two save policies. The
web spreads a small slice of this across four unrelated, ad hoc
`localStorage` keys and has no persistence at all for most of the remaining
~40 fields. After this ticket, one web settings store exposes every
`UiSettings` field with the desktop's default, clamp rule, and JSON key,
backed by a single versioned `localStorage` entry, with the four existing
keys migrated into it (except `roboco.fleet.v1`, which stays separate — it is
pairing/session data, not a `UiSettings` analog).

**Blocked by:** None — can start immediately.

**Status:** done

**Research:** `../../web-client/research/12-settings-shell-appearance.md`
§3.0 (all subsections: 3.0.1 fields, 3.0.2 nested structs, 3.0.3 constants,
3.0.4 load/save rules, 3.0.5 web-mapping gap rows).

**Desktop reference (for lookups only):** `crates/ui/src/settings.rs` (whole
file, 2,418 lines) — `UiSettings` struct + defaults (~L526-750), `current`/
`update` (~L420-451), `load`/`save`/`clamped`/`migrated` (~L1149-1284).

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/state/ui-settings.ts` | new | `UiSettingsStore` class: the single `localStorage`-backed store for every field in §2's table; `useUiSettings()` hook |
| `web/packages/app/src/state/layout.ts` | edit | `SidebarWidthStore` (lines 180-228) — delete its own `localStorage` read/write (`STORAGE_KEY = "roboco.layout.sidebar"`, lines 157-178, 200-210); source `width`/`collapsed` from `UiSettingsStore` instead; keep `clampSidebarWidth`, `sidebarTarget`, and every pure geometry function as-is (they are not persistence, they stay put) |
| `web/packages/app/src/lib/sidebar-store.ts` | edit | `SidebarStore` (lines 52-139) — delete its own `localStorage` read/write (`STORAGE_KEY = "roboco.sidebar.v1"`, lines 34, 95-123); source `spaceFilter`/`lastSpaceId` from `UiSettingsStore`; keep `archivedOpen` in-memory-only exactly as today (never persisted on desktop either) |
| `web/packages/app/src/lib/appearance-store.ts` | edit | `AppearanceStore` (lines 183-280) — delete its own `localStorage` read/write (`STORAGE_KEY = "roboco.appearance.v1"`, lines 60, 243-270); source `mode`/`lightVariant`/`darkVariant`/`accent`/`surface` from `UiSettingsStore` |
| `web/packages/app/src/state/right-pane.ts` | edit | `RightPaneStore` (lines 63-152) — today entirely in-memory per chat (`#byChat` Map, no persistence at all); add persistence of the **global default** `width` field (matching the desktop's one global `rightPaneWidth`, not a per-chat value) through `UiSettingsStore`; per-chat `open`/`expanded`/`active`/`tabs` stay in-memory (session-scoped on desktop too, per `right_pane_open`'s "legacy" status in §3.0.1) |
| `web/packages/app/src/lib/engine-store.ts` | none (verify only) | `roboco.fleet.v1` stays its own separate key — it is pairing/Session data, not a `UiSettings` analog; do not fold it in |
| `web/packages/app/tests/ui-settings.test.ts` | new | unit tests: defaults, clamp rules, migration from the three folded-in legacy keys |

## 1. Context a fresh session needs

- `UiSettings` (desktop: `crates/ui/src/settings.rs:526-653`) is ONE
  `#[serde(default, rename_all = "camelCase")]` struct, persisted whole as
  `{data_dir}/ui-settings.json`. Every field's JSON key is the camelCase of
  its Rust name; a key absent from a loaded file (old file, hand-edit)
  silently takes that field's `Default` rather than failing the whole load.
  This ticket's web store must offer the same "any missing/malformed field
  heals to its own default independently" property — not "one malformed
  field discards the whole file" the way desktop's *typed deserialization*
  step does (see §3.0.4 point 3; the web store should be more forgiving
  per-field since `JSON.parse` + manual field access naturally works that way,
  whereas desktop's `serde` typed struct either parses whole or not at all).
- Every key is per-device, never synced — this maps directly to
  browser-origin-scoped `localStorage`, exactly as the existing four ad hoc
  stores already assume.
- The existing four web storage keys and what they cover (do not delete their
  concepts, just consolidate their storage):
  - `roboco.layout.sidebar` (`state/layout.ts:157`) → `{width, collapsed}`.
    Maps to `sidebar_width`/`sidebar_collapsed`. Constants already ported
    exactly: `SIDEBAR_MIN=224`, `SIDEBAR_MAX=400`, `SIDEBAR_DEFAULT=256`
    (byte-for-byte match to `settings.rs:30-32`).
  - `roboco.sidebar.v1` (`lib/sidebar-store.ts:34`) →
    `{version:1, spaceFilter, lastSpaceId}`; `archivedOpen` is kept in-memory
    only, never persisted (matches desktop, which has no persisted
    "archived shelf open" flag either). Maps to `space_filter`/`last_space_id`.
  - `roboco.appearance.v1` (`lib/appearance-store.ts:60`) →
    `{version:1, mode, lightVariant, darkVariant, accent, surface}`. Maps to
    `appearance`/`theme_selection`/`accent`/`surface`.
  - `roboco.fleet.v1` (`lib/engine-store.ts:53`) — **not** a `UiSettings`
    analog. This is the client-side mirror of paired-engine/Session
    credentials (`CONTEXT.md`'s "Session" — the pairing credential). Keep it
    fully separate; do not migrate or merge it.
- Two save-policy concept exists on desktop (`Debounced` 400ms vs
  `Immediate`) — the web store should offer the same shape (a debounced
  write path for geometry drags, an immediate one for discrete choices) even
  though `localStorage` writes are cheap enough that the distinction mostly
  matters for avoiding redundant writes during a drag, not correctness.
- Vocabulary: "space" (not project/workspace-folder in UI copy), "chat" (not
  session/thread) — this store's fields reference chats/spaces by id, never
  "sessions."

## 2. The full `UiSettings` model to port

Copied verbatim from research §3.0.1 (51 fields). Every row is a field the
new `UiSettingsStore` must expose, with its default and clamp/heal rule. The
last column is this ticket's disposition (not the research file's "consuming
surface" column, which named other tickets) — **P** = port now with real
read/write wiring into an existing web store, **S** = store the field and its
default/clamp only (no consumer yet; a later ticket wires it), **N/A** = do
not port (desktop-only concept with no web analog).

| field | JSON key | type | default | clamp/heal rule | disposition |
|---|---|---|---|---|---|
| `composer_send_behavior` | `composerSendBehavior` | `"enter"` \| `"modEnter"` | `"enter"` | none | S |
| `sidebar_width` | `sidebarWidth` | `number` | `256.0` | `clamp(224, 400)`, NaN→default | P — `layout.ts` |
| `sidebar_collapsed` | `sidebarCollapsed` | `boolean` | `false` | none | P — `layout.ts` |
| `sidebar_grouped` | `sidebarGrouped` | `boolean` | `false` | none — legacy, unread | S (store only; never read, matches desktop) |
| `sidebar_organization` | `sidebarOrganization` | `"byProject"` \| `"byDevice"` \| `"inOneList"` | `"inOneList"` | any `"byProject"` value heals to `"inOneList"` on every load | S |
| `sidebar_sort` | `sidebarSort` | `"lastUpdated"` \| `"created"` | `"lastUpdated"` | none | S |
| `sidebar_show_harness` | `sidebarShowHarness` | `boolean` | `true` | none | S |
| `sidebar_show_branch` | `sidebarShowBranch` | `boolean` | `true` | none | S |
| `sidebar_show_pull_request` | `sidebarShowPullRequest` | `boolean` | `true` | none | S |
| `last_space_id` | `lastSpaceId` | `string \| null` | `null` | none | P — `sidebar-store.ts` |
| `open_tabs` | `openTabs` | `string[] \| null` | `null` | none | N/A — web has no tab strip (routes per chat) |
| `space_filter` | `spaceFilter` | `string \| null` | `null` | none | P — `sidebar-store.ts` |
| `tab_order` | `tabOrder` | `Record<string,string[]>` | `{}` | none — legacy, unread | N/A |
| `space_order` | `spaceOrder` | `string[]` | `[]` | none — legacy, unread | N/A |
| `sound_enabled` | `soundEnabled` | `boolean` | `true` | none | S |
| `sound_completion_enabled` | `soundCompletionEnabled` | `boolean` | `true` | none | S |
| `sound_input_enabled` | `soundInputEnabled` | `boolean` | `true` | none | S |
| `sound_attention_enabled` | `soundAttentionEnabled` | `boolean` | `true` | none | S |
| `notifications_enabled` | `notificationsEnabled` | `boolean` | `true` | none | S |
| `notifications_background_only` | `notificationsBackgroundOnly` | `boolean` | `true` | none | S |
| `right_pane_width` | `rightPaneWidth` | `number` | `520.0` | floor only: `max(360, v)`, NaN→default; no persisted ceiling | P — `right-pane.ts` (as the global default; per-chat width stays in-memory as today) |
| `right_pane_open` | `rightPaneOpen` | `boolean` | `false` | none — legacy, unread (panel-open is session-scoped in-memory now) | N/A |
| `terminal_height` | `terminalHeight` | `number` | `280.0` | `clamp(160, 2000)`, NaN→default; the 55%-viewport cap is runtime-only, not persisted | S |
| `terminal_open` | `terminalOpen` | `boolean` | `false` | none — legacy, unread | N/A |
| `keymap` | `keymap` | `KeymapConfig` (nested, §2.1) | see §2.1 | heal jump slots + reserved-shortcut healing every load | S |
| `appshots_enabled` | `appshotsEnabled` | `boolean` | `false` | none; desktop-only field | N/A |
| `appshot_sound_enabled` | `appshotSoundEnabled` | `boolean` | `true` | none; desktop-only | N/A |
| `appshot_destination` | `appshotDestination` | `"automatic"` \| ... | `"automatic"` | none; desktop-only | N/A |
| `escape_stops_active_agent` | `escapeStopsActiveAgent` | `boolean` | `false` | none | S |
| `appearance` | `appearance` | `"system"` \| `"light"` \| `"dark"` | `"system"` | none | P — `appearance-store.ts` (already named `mode` on web) |
| `git_history_columns` | `gitHistoryColumns` | `GitHistoryColumns` (nested, §2.2) | all `true` | none | S |
| `git_history_column_widths` | `gitHistoryColumnWidths` | `GitHistoryColumnWidths` (nested, §2.2) | author 88 / date 88 / sha 74 | `.clamped()` per sub-field every load | S |
| `git_history_column_order` | `gitHistoryColumnOrder` | `GitHistoryColumnOrder` (nested, §2.2) | `["author","date","sha"]` | `.normalized()` — dedup + append missing columns | S |
| `git_history_author_display` | `gitHistoryAuthorDisplay` | `"avatar"` \| `"name"` | `"avatar"` | none | S |
| `ui_font_family` | `uiFontFamily` | `"geist"` \| `"geistMono"` \| `"system"` \| `"installed:{name}"` | `"geist"` | none in clamp — availability checked separately at read time | S |
| `ui_font_size` | `uiFontSize` | `number` | `16` | `.normalized()` — snaps to nearest of `[12,13,14,15,16,18,20]` | S |
| `theme_selection` | `themeSelection` | `{light: string, dark: string}` | `{light:"roboco-light", dark:"roboco-dark"}` | none in clamp — invalid variant ids healed separately | P — `appearance-store.ts` (already split into `lightVariant`/`darkVariant`) |
| `diff_split` | `diffSplit` | `boolean` | `false` | none | S |
| `diff_wrap` | `diffWrap` | `boolean` | `false` | none | S |
| `code_fences_fit_content` | `codeFencesFitContent` | `boolean` | `false` | none (bumps a separate non-persisted generation counter on change) | S |
| `open_web_links_in_roboco` | `openWebLinksInRoboco` | `boolean` | `true` | none | N/A — no embedded browser tabs on web |
| `files_autosave_enabled` | `filesAutosaveEnabled` | `boolean` | `false` | none | S |
| `files_autosave_delay_ms` | `filesAutosaveDelayMs` | `number` | `900` | `clamp(100, 10_000)` | S |
| `files_word_wrap` | `filesWordWrap` | `boolean` | `false` | none | S |
| `files_editor_font_size` | `filesEditorFontSize` | `number` | `13.0` | `clamp(9, 24)`, NaN→default | S |
| `files_show_all` | `filesShowAll` | `boolean` | `false` | none | S |
| `accent` | `accent` | `"themeDefault"` \| 7 preset ids | `"themeDefault"` | none in clamp | P — `appearance-store.ts` |
| `surface` | `surface` | `"themeDefault"` \| `"frosted"` \| `"opaque"` | `"themeDefault"` | none in clamp | P — `appearance-store.ts` |
| `new_thread_composer_background` | `newThreadComposerBackground` | `{path,name} \| null` | `null` | file-existence checked at render time, not healed | S (the web has no filesystem — if ticket 15's new-thread route wants this, it needs its own image-storage design; this ticket only reserves the field shape) |
| `new_thread_background_effect` | `newThreadBackgroundEffect` | `"none"` \| `"dither"` \| `"ascii"` \| `"halftone"` \| `"scanlines"` | `"none"` | none | S |
| `legacy_accent_color` (private) | `accentColor` | `string \| null` | `null` | read-only one-time migration input, cleared after use, never re-serialized | N/A — no legacy web key carries this shape |

### 2.1 `KeymapConfig` (nested)

| field | JSON key | type | default | notes |
|---|---|---|---|---|
| `capture_appshot` | `captureAppshot` | `string` | platform default | desktop-only (appshots) — still reserve the field for shape-completeness, but no web consumer |
| `save_file` | `saveFile` | `string` | `"mod-s"` | |
| `browser_reload` | `browserReload` | `string` | `"mod-shift-r"` | |
| `toggle_sidebar` | `toggleSidebar` | `string` | `"mod-b"` | |
| `toggle_changes` | `toggleChanges` | `string` | `"mod-r"` | |
| `toggle_terminal` | `toggleTerminal` | `string` | `"mod-j"` | |
| `new_session` | `newSession` | `string` | `"mod-n"` | |
| `next_session` | `nextSession` | `string` | `"mod-tab"` (`"ctrl-tab"` on macOS) | |
| `prev_session` | `prevSession` | `string` | `"mod-shift-tab"` (`"ctrl-shift-tab"` on macOS) | |
| `archive_session` | `archiveSession` | `string` | `"mod-shift-a"` | |
| `jump_session` | `jumpSession` | `string[]` | `["mod-1",...,"mod-9"]` (9 entries) | a `Vec` not 9 fixed fields, so one malformed slot never invalidates the whole file; heal by truncating/padding back to exactly 9 slots on every load |

### 2.2 Other nested structs

- `GitHistoryColumns`: `{author: boolean, date: boolean, sha: boolean}`, all
  default `true`.
- `GitHistoryColumnWidths`: `{author: number, date: number, sha: number}`,
  defaults `88/88/74`, clamps `author∈[44,220]`, `date∈[68,180]`,
  `sha∈[58,140]`.
- `GitHistoryColumnOrder`: `string[]` over `"author"|"date"|"sha"`, default
  `["author","date","sha"]`; `.normalized()` dedups and appends any of the 3
  fixed columns missing from a hand-edited list.
- `NewThreadComposerBackground`: `{path: string, name: string}` — both
  required once non-null, no independent per-field defaults.

### 2.3 Constants to port (module-level, `settings.rs`)

| constant | value |
|---|---|
| `SIDEBAR_MIN` | `224.0` |
| `SIDEBAR_MAX` | `400.0` |
| `SIDEBAR_DEFAULT` | `256.0` |
| `RIGHT_PANE_MIN` | `360.0` |
| `RIGHT_PANE_DEFAULT` | `520.0` |
| `CHAT_PANEL_MIN` | `300.0` |
| `TERMINAL_MIN_HEIGHT` | `160.0` |
| `TERMINAL_MAX_VH` | `0.55` (runtime-only ceiling, not persisted) |
| `TERMINAL_ABS_MAX_HEIGHT` | `2000.0` (persisted-file healing cap only) |
| `TERMINAL_DEFAULT_HEIGHT` | `280.0` |
| `SAVE_DEBOUNCE_MS` | `400` |
| `FILES_AUTOSAVE_DELAY_DEFAULT_MS` | `900` |
| `FILES_AUTOSAVE_DELAY_MIN_MS` | `100` |
| `FILES_AUTOSAVE_DELAY_MAX_MS` | `10_000` |
| `FILES_EDITOR_FONT_SIZE_DEFAULT` | `13.0` |
| `FILES_EDITOR_FONT_SIZE_MIN` | `9.0` |
| `FILES_EDITOR_FONT_SIZE_MAX` | `24.0` |
| `JUMP_SLOTS` | `9` |
| `JUMP_DEFAULTS` | `["mod-1",...,"mod-9"]` |
| `GitHistoryColumnWidths::AUTHOR_MIN`/`MAX` | `44.0` / `220.0` |
| `GitHistoryColumnWidths::DATE_MIN`/`MAX` | `68.0` / `180.0` |
| `GitHistoryColumnWidths::SHA_MIN`/`MAX` | `58.0` / `140.0` |

(29 numeric/string constants total above — the research brief's "30
constants" count includes `UiFontSize::ALL`'s 7-entry array as a distinct
constant, which is: `[12, 13, 14, 15, 16, 18, 20]`, default `16`.)

### 2.4 Load/save rules to port

- **In-memory apply, then persist.** A mutation applies to the in-memory
  snapshot immediately, re-runs clamp/heal rules synchronously (an
  out-of-range value never lives in memory even transiently), and is a no-op
  (no write scheduled) if the result is identical to before.
- **Two save policies.** Geometry drags/toggles (`sidebar_width`,
  `right_pane_width`, `terminal_height`) use a **debounced** write — cancel
  any pending timer, start a fresh 400ms timer, flush the latest snapshot
  when it fires (a rapid drag coalesces into one `localStorage.setItem`).
  Durable/discrete choices (theme, accent, keymap, notification toggles,
  sidebar organization, etc.) use an **immediate** write — flush
  synchronously in the same call. Implement both as methods on
  `UiSettingsStore` (`updateDebounced`/`updateImmediate`, or a `policy`
  parameter — implementer's choice of shape) rather than repeating the
  debounce logic per field.
- **Per-field healing on load, not whole-file rejection.** Unlike the
  desktop's single typed `serde` deserialization (which discards the ENTIRE
  file on any field's type error), the web store should parse the JSON once,
  then read and heal each field independently — a corrupt/missing/
  wrong-typed value for one field heals to that field's own default, but a
  sibling field's valid value is NOT lost. This is a deliberate,
  documented DIVERGENCE from the desktop's "one malformed field loses every
  other saved preference" behavior — note it as such in the store's module
  doc comment, since a fresh reader might otherwise "fix" it to match
  desktop exactly.
- **Migration on load, once.** See §3 below.

## 3. Migration from the four existing keys

On first load, `UiSettingsStore` must:

1. Read the new consolidated key (name it `roboco.ui-settings.v1`). If
   present and parses, use it — no migration runs.
2. If absent, check for the three legacy keys and fold them in:
   - `roboco.layout.sidebar` → `{width, collapsed}` maps to
     `sidebar_width`/`sidebar_collapsed`.
   - `roboco.sidebar.v1` → `{spaceFilter, lastSpaceId}` (ignore its
     `version` field, ignore `archivedOpen` if present — it was never
     persisted) maps to `space_filter`/`last_space_id`.
   - `roboco.appearance.v1` → `{mode, lightVariant, darkVariant, accent,
     surface}` maps to `appearance`, `theme_selection.light`/`.dark`,
     `accent`, `surface`.
   - Any field not covered by one of the three legacy keys takes its
     §2-table default.
3. Write the merged result to `roboco.ui-settings.v1` immediately (so the
   migration only ever runs once) and leave the three legacy keys in place,
   untouched (do not delete them — a rollback to a pre-migration build must
   still find its data; this mirrors the desktop's own migration philosophy
   of never destructively rewriting a legacy source until the new shape is
   confirmed working).
4. `roboco.fleet.v1` is never read or written by this migration — it is not
   a `UiSettings` field source.

`layout.ts`/`sidebar-store.ts`/`appearance-store.ts` keep their existing
public APIs (`sidebarLayout.setWidth(...)`, `sidebarStore.setSpaceFilter(...)`,
`appearanceStore.setMode(...)`, etc.) — every existing call site in
`app-shell.tsx`, `sidebar-body.tsx`, `settings-appearance.tsx`, etc. keeps
working unchanged. Internally, each store's `#load`/`#persist` methods
delegate to `UiSettingsStore` instead of touching `localStorage` directly.
This is a refactor of storage, not of the public store APIs — do not rename
existing methods.

## 4. Pure logic to port (tests)

Desktop test names these mirror (per `settings.rs`'s own test module, cited
in research §3.0 — port the shape of each rule, not the Rust test names
literally since no direct desktop test enumeration was in scope for this
surface's research pass beyond the prose description in §3.0.4):

- `clampSidebarWidth` — already exists in `layout.ts`; verify it now reads
  its bounds from `UiSettingsStore`'s constants, not local re-declarations.
- New tests in `ui-settings.test.ts`:
  - `defaults()` — a store with no `localStorage` data returns every field's
    documented default (spot-check at least: `sidebarWidth=256`,
    `rightPaneWidth=520`, `terminalHeight=280`, `uiFontSize=16`,
    `appearance="system"`, `soundEnabled=true`, `jumpSession` has exactly 9
    entries `["mod-1"..."mod-9"]`).
  - `clamp — sidebarWidth` — `50` heals to `224` (min), `9999` heals to `400`
    (max), `NaN`/non-number heals to `256` (default).
  - `clamp — rightPaneWidth` — floor-only: `100` heals to `360`, but `9999`
    is NOT ceiling-clamped (matches desktop's "no persisted ceiling" rule —
    runtime clamps against the live window elsewhere, not here).
  - `clamp — terminalHeight` — `50` heals to `160`, `99999` heals to `2000`.
  - `clamp — uiFontSize` — `19` normalizes to `18` (nearest of
    `[12,13,14,15,16,18,20]`), `250` normalizes to `20`.
  - `clamp — filesAutosaveDelayMs` — `1` heals to `100`, `999999` heals to
    `10000`.
  - `clamp — gitHistoryColumnWidths` — each sub-field clamps independently
    to its own min/max.
  - `heal — sidebarOrganization` — a stored `"byProject"` value heals to
    `"inOneList"` on load (legacy value, no longer selectable).
  - `heal — jumpSession` — a stored array with 5 entries pads to 9 using
    `JUMP_DEFAULTS` for the missing slots; a stored array with 12 entries
    truncates to 9.
  - `migration — from three legacy keys` — seed `localStorage` with
    `roboco.layout.sidebar`, `roboco.sidebar.v1`, `roboco.appearance.v1` (no
    consolidated key present); construct a fresh `UiSettingsStore`; assert
    every migrated field matches the legacy values, every unmapped field
    takes its default, and `roboco.ui-settings.v1` now exists in storage
    with the merged snapshot.
  - `migration — idempotent` — construct `UiSettingsStore` a second time
    after migration; assert it reads `roboco.ui-settings.v1` directly and
    does not re-run the legacy-key merge (e.g. by asserting a value changed
    only in the legacy key after first migration is NOT picked up on second
    construction).
  - `per-field healing — one corrupt field does not lose siblings` — seed
    `roboco.ui-settings.v1` with one field of the wrong type (e.g.
    `sidebarWidth: "banana"`) alongside several valid fields; assert the
    corrupt field heals to its default while every valid sibling field's
    value survives (this is the deliberate divergence from desktop's
    whole-file-rejection rule — see §2.4).

## 5. Gaps this ticket closes

Copied from research §3.0.5's gap-row table (every `UiSettings` field with no
web storage key), scoped to what this ticket adds *storage* for (later
tickets still own building the *UI* that reads/writes many of these):

| item | kind | desktop value | web value | fix |
|---|---|---|---|---|
| `sidebar_organization`, `sidebar_sort`, `sidebar_show_harness/branch/pull_request` | MISSING | persisted per-device sidebar display prefs | none | Add to the new consolidated store (S) |
| `sound_enabled`, `sound_completion_enabled`, `sound_input_enabled`, `sound_attention_enabled` | MISSING | 4 notification-sound toggles | none | Add to the store (S); ticket 30 wires the UI/consumer |
| `notifications_enabled`, `notifications_background_only` | MISSING | 2 desktop-banner toggles | none | Add to the store (S); ticket 30 wires the UI/consumer |
| `right_pane_width` | MISSING | persisted Changes-pane width | `state/right-pane.ts` has no `localStorage` call | Persist the global default via `UiSettingsStore` (P) |
| `terminal_height` | MISSING | persisted terminal panel height | `terminal/store.tsx` has no persistence call | Add to the store (S); ticket 26 wires the UI/consumer |
| `keymap` (all 11 sub-fields) | MISSING | full customizable-shortcut map | none | Add to the store (S); ticket 12/29 wires the UI/consumer |
| `escape_stops_active_agent` | MISSING | opt-in preference | none | Add to the store (S) |
| `ui_font_family`, `ui_font_size` | MISSING | interface font/size picker | none | Add to the store (S); ticket 28 wires the UI |
| `diff_split`, `diff_wrap` | MISSING | Changes-pane diff layout prefs | none | Add to the store (S); ticket 22 wires the UI |
| `code_fences_fit_content` | MISSING | code-fence wrap preference | none | Add to the store (S); ticket 21 wires the UI |
| `files_autosave_enabled`, `files_autosave_delay_ms`, `files_word_wrap`, `files_editor_font_size`, `files_show_all` | MISSING | 5 Files-settings prefs | none | Add to the store (S); ticket 24/25/29 wires the UI |
| `git_history_columns`, `*_column_widths`, `*_column_order`, `git_history_author_display` | MISSING | Changes-page Git History column prefs | none | Add to the store (S); ticket 27 wires the UI |
| `new_thread_composer_background`, `new_thread_background_effect` | MISSING | new-thread background image + effect | none | Reserve field shape (S) — actual image storage design deferred to ticket 15 |
| `open_tabs`, `tab_order` (legacy), `space_order` (legacy) | MISSING | persisted open-tab order / legacy fallbacks | none | N/A — no web tab strip; do not add |
| `open_web_links_in_roboco` | MISSING | in-app browser vs. system browser | none | N/A — no embedded browser on web |
| `appshots_enabled`, `appshot_sound_enabled`, `appshot_destination` | DESKTOP-ONLY | macOS/Linux screen capture prefs | n/a | N/A — not applicable to a browser |

## 6. Do not

- Do not build any settings-page UI in this ticket (no new routes, no new
  components under `web/packages/app/src/routes/settings-*`). This ticket is
  the store only; tickets 28/29/30 (and 12, 22, 24-27) consume it.
- Do not delete the three legacy `localStorage` keys after migration — leave
  them in place, unused, per §3.
- Do not fold `roboco.fleet.v1` into the consolidated store.
- Do not implement `new_thread_composer_background`'s actual file storage
  (there is no filesystem on the web) — reserve the field's shape only.
- Do not implement the desktop's "discard the whole file on one bad field"
  behavior — the web store deliberately heals per-field (§2.4); do not
  "fix" this to match desktop more closely without checking with product
  first, since it is a documented, deliberate improvement.
- Do not wire `terminal_height`/`keymap`/font pickers/etc. into their
  consuming components yet — this ticket only makes the values available and
  correctly defaulted/clamped/persisted; wiring is each owning ticket's job
  (see the "fix" column in §5).

## 7. Acceptance

- [ ] `web/packages/app/src/state/ui-settings.ts` exists, exports
      `UiSettingsStore` (or equivalent name) and a `useUiSettings()` hook,
      covers every field in §2's table with its documented default and
      clamp/heal rule.
- [ ] `layout.ts`, `sidebar-store.ts`, `appearance-store.ts` no longer touch
      `localStorage` directly for the fields they own; their public APIs are
      unchanged (verify by grepping existing call sites — no edits needed
      outside these three files plus `right-pane.ts`).
- [ ] `right-pane.ts` persists a global default `width` via `UiSettingsStore`;
      per-chat `open`/`expanded`/`active`/`tabs` remain in-memory.
- [ ] Migration: a browser with the three legacy keys and no consolidated key
      boots with every migrated field correct and the legacy keys left
      untouched in storage.
- [ ] Unit tests: all tests listed in §4 pass, named per that list (defaults,
      clamp × 6 fields, heal × 2 fields, migration × 2 cases, per-field
      healing × 1 case).
- [ ] `pnpm -r build` green; `web/packages/app` package vitest green.
- [ ] No new literal hex/px where a `--rb-*` token exists (N/A for this
      ticket — no CSS changes).

## Comments

### Implementation note (branch `wp1/03-settings`)

**Landed.** `web/packages/app/src/state/ui-settings.ts` is the single
`localStorage`-backed store: all 41 portable `UiSettings` fields (the §2 table
minus its 10 `N/A` rows), each with the desktop's default, clamp/heal rule and
camelCase JSON key, under `roboco.ui-settings.v1`; every §2.3 constant; the
§2.1 keymap (platform-aware `nextSession`/`prevSession`/`captureAppshot`
defaults, 9-slot `jumpSession` healing, `mod-enter` reserved-combo healing) and
both §2.2 nested structs (`clamped()`/`normalized()` ported); `update(patch,
policy)` plus `updateImmediate`/`updateDebounced`/`flush` over one 400ms
debounce; per-field healing on load, documented in the module comment as the
deliberate divergence from desktop's whole-file rejection; the §3 one-time fold
of the three legacy keys, which are left untouched in storage; and
`useUiSettings()`.

Wired through (public APIs unchanged; no call sites outside these four files
needed edits): `state/layout.ts` (`SidebarWidthStore` is now a cached
projection of `sidebarWidth`/`sidebarCollapsed`; `clampSidebarWidth` stays but
now calls the store's `clampOr` with the store's constants, and
`SIDEBAR_*`/`CHAT_PANEL_MIN` are re-exported from there so a clamp and a load
heal cannot drift), `lib/sidebar-store.ts` (`spaceFilter`/`lastSpaceId` from
the store, `archivedOpen` still in-memory), `lib/appearance-store.ts`
(`appearance`/`themeSelection`/`accent`/`surface` from the store; per-appearance
variant-id validation stays here, since the store deliberately does not clamp
variant ids), `state/right-pane.ts` (the global `rightPaneWidth` persists —
debounced on drag, immediate on seam double-click — while per-chat
`open`/`expanded`/`active`/`tabs`/live width stay in memory).
`lib/engine-store.ts` verified untouched: `roboco.fleet.v1` stays its own key
and the migration never reads it (there is a test for that).

**Skipped / deviated, all deliberate:**

- Every `N/A` row in §2 and the whole §6 "Do not" list: no settings UI, no
  legacy-key deletion, no fleet fold-in, no real image storage for
  `newThreadComposerBackground` (shape only), no consumer wiring for the `S`
  fields.
- `state/chrome.ts` was checked and left alone — it holds no persisted
  geometry, only the route's live titlebar contribution.
- Two pre-existing tests changed shape because the behaviour they asserted was
  the thing this ticket removes: `sidebar-store.test.ts` "drops corrupted
  persisted state" and `appearance-store.test.ts` "drops corrupted or
  wrong-version persisted state" both asserted the store *deleted* its legacy
  key. §3 forbids that now, so they assert the corrupt key survives untouched
  instead, and the appearance file gained a migration test. Their
  "wrong-version" halves went away with them: a legacy key's `version` field is
  ignored by the fold (§3 says so explicitly for `roboco.sidebar.v1`, and the
  appearance fold matches it for symmetry) — per-field type validation is what
  guards a junk payload now.
- One judgement call not spelled out in §3: an *unparseable* consolidated key
  is treated as absent, so the legacy fold runs rather than silently dropping
  the user to defaults. Covered by a test.
- One addition beyond the ticket: a `pagehide` listener flushes a pending
  debounced write, so the last 400ms of a drag survives the tab closing. The
  desktop gets this for free by flushing at quit.

**Verification.** `pnpm -r build` (typecheck + vite build) green across all
five workspace packages. `web/packages/app` vitest: 442 tests in 30 files, all
green, including the new `tests/ui-settings.test.ts` (22 tests — defaults,
clamp × 7 fields, heal × 4 rules, migration × 4 cases, per-field healing × 2,
save policies × 4) and two new `sidebarLayout` projection tests in
`tests/layout.test.ts`. No screenshots: this ticket names no visual state and
changes no CSS.

**For a human.** Nothing blocking. Two things worth knowing: (1) the global
`rightPaneWidth` means a drag in one chat now moves the width every *other*
chat inherits on first open — that is the desktop's model (one global
`rightPaneWidth`), but it is a visible behaviour change from the old web
per-chat-only width; (2) the legacy keys are intentionally left behind, so
storage carries both shapes until someone schedules their removal.
