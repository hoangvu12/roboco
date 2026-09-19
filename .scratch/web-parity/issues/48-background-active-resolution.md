# 48 — Background-active resolution

> Desktop-first fix with a web twin, filed with the web-parity batch. The
> bug is primarily desktop (the Settings page and titlebar island that miss
> the default live in `crates/ui`); the web settings page carries the same
> design flaw and gets the same accessor pattern. Cross-refs: 33 (web
> canvas paint), 34 (web titlebar island — keys off the active-background
> resolution this ticket provides).

**What to build:** A user on a fresh install — or any install that never
picked a custom image — sees the bundled Roboco artwork on the new-thread
canvas, and now every part of the app agrees it is there: Settings →
Appearance shows the background row as selected (Roboco thumbnail + name,
"Replace image" / "Remove"), the "Background effect" row is visible and
editable (the effect genuinely renders on the default artwork today), and
the frosted titlebar island appears over the artwork when the sidebar is
collapsed. Nothing changes in `ui-settings.json`, and nothing changes for
installs that already have a custom background. The web settings page gets
the same fix: its selection state resolves the default the way its canvas
painter already does.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

**Research:** `../research-2026-09-19/desktop-background-default-bug.md` (all sections)

**Desktop reference (for lookups only):** `crates/ui/src/settings.rs::default_new_thread_background` (72-84), `::install_new_thread_composer_background` (305-362), `::remove_new_thread_composer_background` (364-386), `::set_new_thread_background_effect` (388-394), `::remove_managed_new_thread_background` (396-409), `UiSettings` background fields (644-648, defaults 706-707), `mod tests` (1311+); `crates/ui/src/shell.rs` `render_main` artwork resolution (5842-5859), titlebar island predicate (3983-3994), `sync_independent_settings` (3184-3189), tests (8251+, 8973+); `crates/ui/src/settings/appearance.rs` background row (1994, 2133-2274), `mod tests` (2569+); `crates/ui/src/new_thread_background_effects.rs::prepare` (294-302); `crates/ui/src/lib.rs` boot (147-148).

**Web files to touch:**
| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/lib/new-thread-background.ts` | edit | new resolved-selection helper (`resolveActiveNewThreadBackground` + its return shape) beside `resolveNewThreadBackground` (:253-260) / `resolveInstalledBackground` (:267+); `resolveNewThreadBackground` becomes a thin wrapper over it so painter and page share one resolution |
| `web/packages/app/src/routes/settings-appearance.tsx` | edit | background row selection state — `backgroundInstalled`/`backgroundAvailable` (:110-111), `backgroundMeta` (:153-160), thumbnail/actions (:243 onward) and the effect-row gate, all keyed off the resolved value instead of the raw field |
| `web/packages/app/src/state/appearance.ts` | edit (minimal) | `useNewThreadBackground` (:86-105) resolves through the same helper — resolution plumbing only; the hero's paint is ticket 33's |
| `web/packages/app/tests/new-thread-background.test.ts` | edit | resolved-selection unit tests — extend the `resolve_new_thread_background` describe (:161-183) |
| `web/packages/app/tests/appearance-store.test.ts` | edit | settings-page availability resolution tests — extend the new-thread background describe (:270-303, imports :18-24) |

## 1. Context a fresh session needs

- Vocabulary: chat (not session/thread), engine, space — per
  `.scratch/web-parity/spec.md` §Vocabulary.
- Fresh-session-complete repro (desktop, fresh data dir): 1) fresh install
  → new-thread canvas shows the Roboco artwork; 2) Settings → Appearance →
  "New thread composer background" row says "Add an image…" with only
  "Choose image", and no "Background effect" row; 3) collapse the sidebar
  (`mod-b`) on the new-thread canvas → no frosted island behind the
  top-left controls. Contrast: install ANY custom image and both (a) and
  (b) flip to the background-aware presentation.
- **The default background chain, verbatim from the research §2:**
  1. **Bundled bytes**: `DEFAULT_NEW_THREAD_BACKGROUND_BYTES` =
     `include_bytes!("../assets/backgrounds/default-new-thread-background.png")`
     (settings.rs:63-64; asset at
     `crates/ui/assets/backgrounds/default-new-thread-background.png`, 1.5 MB, added by
     commit `ba4a3a9e` 2026-09-15 "feat(ui): brand assets + default new-thread background").
  2. **Boot**: `UiSettings::load(&data_dir)` → `settings::init(...)`
     (`crates/ui/src/lib.rs:147-148`). `new_thread_composer_background` defaults to `None`
     (settings.rs:706) and is `#[serde(skip_serializing_if = "Option::is_none")]`
     (settings.rs:645), so a fresh install stores NOTHING for it. The default is never
     materialized at boot.
  3. **Lazy materialization at render time**: `default_new_thread_background(cx)`
     (settings.rs:72-84) writes the bundled bytes to
     `<data_dir>/new-thread-backgrounds/default-new-thread-background.png` on first call and
     returns that path. Doc comment (settings.rs:66-71): "It backs the new-thread canvas
     whenever no user background is installed… The file is never referenced by
     `ui-settings.json`" — deliberately an implicit fallback, not a stored value.
  4. **Resolution (the only consumer that sees the default)**:
     `render_main` reads `ui_settings.new_thread_composer_background`
     (shell.rs:5842-5844), then
     `setting.map(PathBuf).or_else(|| settings::default_new_thread_background(cx))`
     (shell.rs:5848-5851) → `new_thread_background_effects::prepare(effect, theme, path, cx)`
     (settings.rs call at shell.rs:5852-5859; `prepare` at
     `new_thread_background_effects.rs:294-302`).
  5. **Paint**: `new_thread_background(artwork, …)` builds the two-pass masked hero
     (shell.rs:5883-5894, element built at shell.rs:857-914, mounted at shell.rs:6023);
     mask/cutout/reveal in `new_thread_background_mask.rs:12-83`; frost dimming
     `NEW_THREAD_BACKGROUND_FROSTED_OPACITY = 0.84` (shell.rs:697, applied at
     shell.rs:5893 + shell.rs:844-850).
  6. **User install/remove** (the only writers of the settings field):
     `install_new_thread_composer_background` (settings.rs:305-362, staged + decode-gated
     copy into the managed dir, `Immediate` save) and
     `remove_new_thread_composer_background` (settings.rs:364-386, early-`Ok` when
     `previous.is_none()` — i.e. removing "the default" is a no-op by construction).
     Retirement helper deliberately never deletes the default file
     (settings.rs:396-409 with the comment at settings.rs:66-71).
- **Key fact:** the default exists ONLY inside the `or_else` at
  shell.rs:5851. There is no single "resolve the active background"
  accessor; every other reader of "background present" reads the raw
  persisted field instead.
- **Persistence model:** `ui-settings.json` (device-local, engine-local per
  ADR 0004). The field is `Option<NewThreadComposerBackground>` —
  `skip_serializing_if = "Option::is_none"` (settings.rs:645), default
  `None` (settings.rs:706) — so a fresh install's file simply has no key.
  The retirement-guard design (`remove_managed_new_thread_background`,
  settings.rs:396-409) only deletes files whose parent is the managed
  `new-thread-backgrounds` dir AND that are named by the stored setting;
  because the default is never stored, the bundled copy is never retired.
  The effect field `new_thread_background_effect` (settings.rs:648,
  default `None` at settings.rs:707) IS persisted and applies to whatever
  artwork renders, default included (shell.rs:5853-5857).
- **Root cause (research §3):** the default is applied at render time
  (implicit fallback) while every "is a background selected/active?"
  reader compares against the persisted `new_thread_composer_background`
  field, which stays `None`. The invariant "background present ⟺
  `new_thread_composer_background` is Some" broke silently when commit
  `ba4a3a9e` made artwork show on EVERY install via the `or_else`
  fallback without updating the other readers (all background machinery
  landed earlier with upstream port `dd3193ec`, the
  user-installed-background era, where the invariant held).
- **Where the web twin lives:** the web painter already resolves the
  default — `web/packages/app/src/lib/new-thread-background.ts::resolveNewThreadBackground`
  (:253-260, "the setting's managed blob if it decodes, else the bundled
  default"), consumed by the hero hook
  `web/packages/app/src/state/appearance.ts::useNewThreadBackground`
  (:86-105). The web settings page has the same flaw as desktop: its
  selection state reads the raw field —
  `web/packages/app/src/routes/settings-appearance.tsx:110-111`
  (`backgroundInstalled = settings.newThreadComposerBackground !== null`;
  `backgroundUrl` comes from `resolveInstalledBackground`, which returns
  null on the default) — so the page shows "Add an image…" / "Choose
  image" and hides the effect row while the canvas paints the artwork.
  Web availability is decode-based (`createImageBitmap`), the
  platform-appropriate equivalent of the desktop's `is_file()` probe
  (documented at `lib/new-thread-background.ts:230-236`).

## 2. Spec

### 2.1 The accessor (desktop) — contract

One new public function in `crates/ui/src/settings.rs`, placed beside
`default_new_thread_background` (after settings.rs:84):

```rust
/// The background that actually renders on the new-thread canvas: the
/// user-installed one when its file exists, else the bundled default.
/// Callers gating UI on "is a background active" must use this, never
/// the raw persisted field.
pub fn active_new_thread_background(cx: &App) -> Option<NewThreadComposerBackground>
```

Rules:

- Field `None` → `Some(NewThreadComposerBackground { path:
  <data_dir>/new-thread-backgrounds/default-new-thread-background.png,
  name: "Roboco" })` — the materialized default (reuse
  `default_new_thread_background(cx)` for the path; synthesize the
  display name "Roboco").
- Field `Some` and `Path::new(&background.path).is_file()` →
  `Some(background)` — the user background, unchanged.
- Field `Some` but the file is missing → `None` — NOT active. This
  preserves today's paint and row behavior for the broken-stored state
  exactly: the canvas paints no artwork (`prepare` already fails to
  decode a missing file, so routing render_main through the accessor is
  behavior-identical), the row shows "Image unavailable", the effect row
  stays hidden, the island stays off.
- Materialization failure (field `None` and
  `default_new_thread_background` returns `None`) → `None`.
- The accessor never writes to `ui-settings.json` and never changes the
  field. Zero data migration.
- The `is_file()` probe does synchronous `std::fs` metadata on the call
  path — it already does today for the island predicate
  (shell.rs:3989) and the availability probe (appearance.rs:2133-2135),
  so this is no regression; note it in review, do not "fix" it here.

### 2.2 Routed call sites (desktop) — before/after

Three call sites route through the accessor. Nothing else changes on the
desktop.

**(a) `crates/ui/src/shell.rs:5848-5851` — `render_main` artwork
resolution.**

Before:

```rust
let artwork = new_thread_background_setting
    .as_ref()
    .map(|background| std::path::PathBuf::from(&background.path))
    .or_else(|| crate::settings::default_new_thread_background(cx))
```

After:

```rust
let artwork = crate::settings::active_new_thread_background(cx)
    .map(|background| std::path::PathBuf::from(&background.path))
```

The `.and_then(|path| new_thread_background_effects::prepare(effect,
theme, &path, cx))` chain at shell.rs:5852-5859 is unchanged. The local
`new_thread_background_setting` read at shell.rs:5843 may stay if still
used; the `or_else` fallback disappears from `render_main` — the accessor
is now its only home. Behavior-identical for every state: user → user
path; default → default path; broken-stored → `None` (today the missing
path flows into `prepare` and yields `None` anyway).

**(b) `crates/ui/src/shell.rs:3986-3989` — titlebar island predicate.**

Before (inside the `island_target` conditional, shell.rs:3983-3994):

```rust
&& settings::current(cx)
    .new_thread_composer_background
    .as_ref()
    .is_some_and(|background| std::path::Path::new(&background.path).is_file())
```

After:

```rust
&& settings::active_new_thread_background(cx).is_some()
```

Default → island target `1.0` (the frosted island renders behind the
sidebar-toggle/back/forward cluster over the artwork); user-installed →
unchanged `1.0`; broken-stored → unchanged `0.0`.

**(c) `crates/ui/src/settings/appearance.rs:1994` — background row
selection state.**

Before:

```rust
let current_background = ui_settings.new_thread_composer_background;
```

After — the row needs both values: the resolved one drives
availability/tile/meta-name/actions; the raw stored one only preserves
the "Image unavailable" distinction:

```rust
let stored_background = ui_settings.new_thread_composer_background;
let current_background = crate::settings::active_new_thread_background(cx);
```

`background_available` (appearance.rs:2133-2135) becomes
`current_background.is_some()` — or keep the existing `is_some_and(
is_file)` shape over the resolved value, which is then redundant but
harmless. The full row-state truth table:

| state | condition | tile | meta | actions | effect row |
| --- | --- | --- | --- | --- | --- |
| default active (fresh install / never chosen) | stored `None`, resolved `Some` (default) | artwork thumbnail (img of the default's materialized path) | `"Roboco"` · "Softened automatically on frosted themes." | "Replace image" + "Remove" ("Remove" is a no-op by construction, settings.rs:370-373) | **visible** |
| user background | stored `Some`, file exists | artwork thumbnail (unchanged) | `{name}` · "Softened automatically on frosted themes." (unchanged) | "Replace image" + "Remove" (unchanged) | visible (unchanged) |
| stored but file missing | stored `Some`, resolved `None` | generic `FILE_IMAGE` tile (unchanged) | "Image unavailable" · "Choose a replacement or remove it." (unchanged) | "Replace image" + "Remove" (unchanged) | hidden (unchanged) |
| nothing resolvable | stored `None`, resolved `None` (materialization failed) | generic `FILE_IMAGE` tile | "Add an image behind the composer on empty new threads." | "Choose image" | hidden |

- Tile branch (appearance.rs:2136-2155): key off the resolved value —
  `current_background.as_ref().filter(|_| background_available)` already
  works once `current_background` is resolved (the default's path is a
  real file, so the 34×34 cover-fit img renders the artwork).
- Meta branch (appearance.rs:2156-2176): match on
  `(stored_background.is_some(), current_background.as_ref())` —
  stored-but-unresolved → the "Image unavailable" arm; resolved →
  `{resolved.name}` (which is `"Roboco"` on the default arm) +
  "Softened automatically on frosted themes."; neither → "Add an image…".
- Actions (appearance.rs:2194-2229): show the "Replace image" + "Remove"
  pair when `stored_background.is_some() || current_background.is_some()`
  (default included); "Choose image" only when neither. "Replace image"
  on the default opens the same picker and installs normally
  (`install_new_thread_composer_background`, settings.rs:305-362).
- Effect row (appearance.rs:2233): gate stays `if background_available` —
  now true on the default, so the pills
  (`NewThreadBackgroundEffect::ALL`, writer
  `set_new_thread_background_effect`, settings.rs:388-394) become
  reachable for default-background users. Effect choice persists and
  applies to the default artwork exactly as it already does
  (shell.rs:5853-5857).
- **"Remove"-on-default decision (research §5 leaves it open):** keep
  "Remove" rendered. It is already a no-op-to-default by construction —
  `remove_new_thread_composer_background` early-`Ok`s when the field is
  `None` (settings.rs:370-373) and the resolver keeps resolving the
  default, so nothing visibly changes. The alternative (hide "Remove"
  when the resolved value is the default) was considered and rejected as
  an extra branch with no functional benefit; do not invent a third
  behavior (e.g. "Remove the artwork entirely").
- `sync_independent_settings` (shell.rs:3184-3189) stays a raw field copy
  — it syncs the persisted field between the Shell's save-copy and the
  store, which is exactly right; it is NOT a "background active" reader.

### 2.3 Every background-active branch site (verbatim from research §4)

For orientation: which sites already see the default (YES) and which miss
it (NO). This ticket routes the NO sites that gate user-visible behavior
through the accessor; the YES sites and the artwork-agnostic pipelines are
untouched.

| site | file:line | behavior gated | sees default? |
|---|---|---|---|
| New-thread hero paint (resolution) | `shell.rs:5848-5851` | paints artwork on canvas | **YES** (this is the only `or_else` consumer) |
| Hero layer construction/mount | `shell.rs:5883-5894`, `6023` | hero element, readiness fade, frost dim 0.84 | YES (downstream of artwork) |
| Effect raster on hero | `shell.rs:5852-5859` → `new_thread_background_effects.rs:294-302` | Dither/Ascii/Halftone/Scanlines treatment of artwork | YES (effect applies to default artwork) |
| Composer cutout mask | `shell.rs:5891` (`composer.surface_bounds()`), mask `new_thread_background_mask.rs:12-83` | rounded cutout + bottom fade around composer | YES (geometry-only, artwork-agnostic) |
| Dock dissolve handoff | `shell.rs:5892` (`dock_frame.dissolve()`), `composer_dock.rs:90-92` | hero fade during new-thread↔thread transitions | YES (artwork-agnostic) |
| Titlebar island (top-left chrome frost) | `shell.rs:3983-3994`, element `4025-4035`, geometry `829-835`, field `1225` | frosted glass backing behind sidebar-toggle/back/forward on blank canvas + collapsed sidebar | **NO** — reads `settings::current(cx).new_thread_composer_background` only |
| Settings: background row tile | `appearance.rs:2133-2155` | artwork thumbnail vs generic FILE_IMAGE tile | **NO** — reads `ui_settings.new_thread_composer_background` |
| Settings: background row meta | `appearance.rs:2156-2176` | file name + "Softened automatically…" vs "Add an image…" | **NO** |
| Settings: background row actions | `appearance.rs:2194-2229` | "Replace image"+"Remove" vs "Choose image" | **NO** |
| Settings: background effect row (pills) | `appearance.rs:2233-2274` (pills `2234-2243`, choice chip `584-622`, writer `settings.rs:388-394`) | entire effect picker row | **NO** — hidden on default; effect setter unreachable in prod (only call site is appearance.rs:2239) |
| Remove flow no-op guard | `settings.rs:370-373` | "Remove" is a no-op when field is `None` | N/A (consistent with field semantics; default cannot be "removed") |
| Shell settings cache sync | `shell.rs:3184-3189` | keeps Shell's save-copy from clobbering field | N/A (field-copy only) |

Skipped behaviors on a default-background install, concretely: (1)
titlebar frost island never renders over the artwork; (2) Settings shows
"unset" row state; (3) effect pills row hidden →
`new_thread_background_effect` unchangeable while it silently renders on
the default artwork.

### 2.4 Fix options and trade-offs (from research §5) — A is chosen

| option | approach | trade-offs | verdict |
| --- | --- | --- | --- |
| **A — one resolver, every reader goes through it** | add `active_new_thread_background` in settings.rs that returns the user background when its file exists, else the materialized default (path + display name "Roboco"); factor the `or_else` at `shell.rs:5851` out and route `appearance.rs:1994` and `shell.rs:3986-3989` through it | `background_available`'s `is_file()` probe does synchronous `std::fs` metadata on the render path — it already does today for the island predicate, so no regression, but note it. No data migration at all; existing installs (field absent/None) simply start showing the default as selected. Matches the web's painter pattern (`resolveNewThreadBackground`, `web/packages/app/src/lib/new-thread-background.ts:253-260`) — and the web settings page gets the same treatment to fix parity | **Chosen — this ticket.** Smallest blast radius, zero migration, fixes both (a) and (b) in one place, honors the existing "default is never persisted" design. Does not preclude C later |
| **B — materialize the default into `ui-settings.json` on first run** | seed `new_thread_composer_background = { path: <data_dir>/new-thread-backgrounds/default-new-thread-background.png, name: "Roboco" }` at boot (lib.rs:147-148) when the key is absent; all readers keep working unchanged | (1) contradicts the documented design ("never referenced by `ui-settings.json`", settings.rs:66-71) and the retirement guard in `remove_managed_new_thread_background` (settings.rs:396-409) — the default file's parent IS the managed dir, so a hand-edited or future "reset" flow could delete the bundled copy; the guard would need a filename denylist; (2) "Remove" semantics become ambiguous (removing it must fall back to re-seeding, or the artwork disappears entirely); (3) legacy migration concern: existing installs whose file was already written WITHOUT the key must be detected (missing key ⇒ seed) — same seeding logic anyway; (4) uninstall / `--reset` flows must tolerate a stale pointer | **Rejected.** Do NOT materialize the default into settings |
| **C — distinct "Roboco" preset row in Settings** | make the default a first-class selectable entry (id `"default"`), the field becoming an enum-ish `Option<BackgroundSelection>` (User(path) | Roboco) | most explicit UX (the user can see and deliberately pick the Roboco background), but the largest change: new wire type, serde migration for existing files (None ⇒ Roboco vs None ⇒ unset needs a decision), install/remove flows rewritten, and the web parity ticket reopened. Overkill for the reported bug; good future direction if "choose the Roboco artwork" becomes a feature | **Rejected for now** (future endpoint if product wants the default user-selectable; A does not preclude it). Do NOT invent a preset row |

### 2.5 Web twin — one resolved helper for page state and painter

Mirror the desktop accessor in `web/packages/app/src/lib/new-thread-background.ts`:

```ts
export interface ResolvedNewThreadBackground {
  /** The artwork that will paint. */
  readonly url: string;
  /** Display name: the stored name, or "Roboco" for the default. */
  readonly name: string;
  /** True when nothing is stored and the bundled default resolved. */
  readonly isDefault: boolean;
}

export async function resolveActiveNewThreadBackground(
  setting: NewThreadComposerBackground | null,
  ...: ...,
): Promise<ResolvedNewThreadBackground | null>
```

Semantics (the decode-based platform equivalent of the desktop's
`is_file()` probe):

- `setting === null` → `{ url: DEFAULT_NEW_THREAD_BACKGROUND_URL, name:
  "Roboco", isDefault: true }`.
- stored entry that decodes → `{ url, name: setting.name, isDefault:
  false }`.
- stored entry that no longer resolves (missing/undecodable blob) →
  `null` — the page's "Image unavailable" state, unchanged.

Consumers:

- **Painter:** `resolveNewThreadBackground` (:253-260) becomes a thin
  wrapper — `(await resolveActiveNewThreadBackground(setting))?.url ??
  defaultUrl` — so `useNewThreadBackground`
  (`state/appearance.ts:86-105`) and the hero keep painting exactly as
  today through the SAME resolution. Resolution plumbing only; the hero's
  paint (mask, cutout, transitions) is ticket 33's — do not touch it.
- **Settings page** (`routes/settings-appearance.tsx`): replace the raw
  `backgroundInstalled = settings.newThreadComposerBackground !== null`
  (:110-111) with resolution through the helper (same async
  `useEffect` + state pattern the page already uses for `backgroundUrl`
  at :115-125). Row state mirrors desktop §2.2(c)'s truth table:
  resolved → thumbnail from `url`, meta `[name, "Softened
  automatically on frosted themes."]`, "Replace image" + "Remove"
  actions, effect-row gate true; stored-but-unresolved → "Image
  unavailable" (unchanged); nothing resolvable → "Add an image…" +
  "Choose image". The effect row's gate becomes the resolved
  availability, so default-background users can finally change the
  effect that already renders.
- **Ticket 34** (web titlebar island) keys its gate off this resolved
  value — this ticket only provides the helper; 34 wires the island.

## 3. Pure logic to port

- `active_new_thread_background(cx: &App) -> Option<NewThreadComposerBackground>`
  — the desktop accessor contract in §2.1 (three-valued: user /
  default("Roboco") / None) with the rules in prose there.
- Web: `resolveActiveNewThreadBackground(setting) ->
  Promise<ResolvedNewThreadBackground | null>` — §2.5's contract; the
  decode-based availability reuses `resolveInstalledBackground` as its
  core.
- **Desktop tests to extend (copied verbatim from research §6; no
  existing test covers `default_new_thread_background` at all — zero
  test references, verified by grep):**

  - `crates/ui/src/settings.rs` `mod tests`:
    - `composer_send_behavior_is_opt_in_for_old_and_partial_settings`
      (settings.rs:1311-1337) — currently asserts `new_thread_composer_background.is_none()`
      on default load; a fix under Option A should keep this (storage stays `None`) while a
      new sibling test asserts the new resolver returns the default path.
    - `valid_background_replacement_persists_renderable_image_before_retiring_previous`
      (settings.rs:1479-1522) and
      `invalid_initial_background_import_does_not_create_managed_files_or_settings`
      (settings.rs:1524-1539) — extend to assert install/replace does NOT retire the
      default file and the resolver flips user↔default.
    - `background_cleanup_only_removes_files_owned_by_the_setting`
      (settings.rs:1417-1443) — add a case proving the default file survives retirement
      (guard for Option B especially).
    - `round_trip` (settings.rs:1557-1651) — keep asserting nothing is persisted for the
      default (Option A) / new wire format round-trips (Option C).
  - `crates/ui/src/shell.rs` tests:
    - `new_thread_handoff_is_continuous_and_staged` (shell.rs:8265-8281) — assert the
      resolved-artwork predicate includes the default (via whatever helper is extracted).
    - `island_stays_centered_on_controls_while_expanding` (shell.rs:8251-8263) — geometry
      only; add a `gpui::test` sibling that boots `Shell` with default settings +
      `sidebar_collapsed = true` + blank canvas and asserts the island target is 1.0
      (pattern: the harness in `panel_saves_preserve_settings_selected_outside_the_shell`,
      shell.rs:8973-9038).
  - `crates/ui/src/settings/appearance.rs` `mod tests` (appearance.rs:2569-2655) — has no
    background-row tests today; add one asserting the effect row/`background_available`
    sees the default (e.g. a pure helper test on the extracted availability function, in
    the style of `surface_helper_explains_theme_default_and_global_overrides`,
    appearance.rs:2604-2616).
  - Web (if Option A is mirrored): `web/packages/app/tests/new-thread-background.test.ts`
    (`resolveNewThreadBackground` fallback, :30-42) and
    `web/packages/app/tests/appearance-store.test.ts` (:24 imports) — extend for the
    settings-page availability resolution. [Verified locations at HEAD `bc3a3945`: the
    `resolve_new_thread_background` describe lives at
    `new-thread-background.test.ts:161-183` (fallback test :162-168), and
    `appearance-store.test.ts` imports the background helpers at :18-24 with its
    new-thread background describe at :270-303.]

- **New unit tests to write (this ticket):**
  - Desktop `settings.rs`: an accessor test — fresh/default settings
    (field `None`) → `active_new_thread_background` returns the default
    path with name "Roboco"; stored+file-exists → the user entry;
    stored+missing-file → `None`. (The "new sibling test" research §6
    asks for next to `composer_send_behavior…`.)
  - Desktop `appearance.rs`: the effect-row/availability helper test —
    availability is true when the resolved value is the default, so the
    "Background effect" row renders (pure helper, in the style of
    `surface_helper_explains_theme_default_and_global_overrides`).
  - Desktop `shell.rs`: a `gpui::test` island-gate test — default
    settings (no stored background) + `sidebar_collapsed = true` + blank
    chat canvas → `island_target` is 1.0; broken-stored → 0.0 (pattern:
    the `panel_saves_preserve_settings_selected_outside_the_shell`
    harness, shell.rs:8973-9038).
  - Web `new-thread-background.test.ts`:
    `resolveActiveNewThreadBackground` — nothing stored → default url +
    "Roboco" + `isDefault: true`; stored+decodes → user entry; stored
    broken → `null`; and `resolveNewThreadBackground` still returns the
    default url in every case the painter relies on.
  - Web `appearance-store.test.ts` (or the page-state test home the
    implementer finds most idiomatic): the settings-page availability
    resolution — resolved default ⇒ row "installed/available" ⇒ effect
    gate open.

## 4. Gaps this ticket closes

Copied verbatim from research §7 (the whole table is this bug):

| item | kind | expected | actual | fix sketch |
|---|---|---|---|---|
| Settings bg row state with default active | behavior | recognizes artwork (thumbnail, name, Replace/Remove) | "Add an image…" + "Choose image" only (appearance.rs:1994, 2133-2135, 2156-2176, 2218-2229) | resolve via shared accessor incl. default (Option A) |
| Background effect row with default active | behavior | pills visible + selectable (effect does render on default) | row hidden; setter unreachable (appearance.rs:2233; only prod call site 2239) | gate on resolved-artwork availability |
| Effect changes invisible to default users | data/UX | effect row editable whenever artwork renders | `new_thread_background_effect` renders silently, unchangeable (shell.rs:5853-5857 vs appearance.rs:2233) | same as above |
| Titlebar frost island with default active | behavior | island target 1.0 (blank canvas + collapsed sidebar + artwork) | 0.0 — predicate reads raw field (shell.rs:3983-3994) | replace predicate with resolved-artwork check |
| Single source of truth for "background active" | architecture | one accessor incl. default | `or_else` inline at one call site only (shell.rs:5851); three raw-field readers (appearance.rs:1994, shell.rs:3987, plus sync copy shell.rs:3186) | extract `settings::active_new_thread_background` |
| Fresh-install settings file | data | (Option A) no key needed | absent key ⇒ all readers see "no background" (settings.rs:645, 706) | no migration needed for A; B/C need seeding |
| Web parity: settings page default | bug (web) | page recognizes default like painter does | `backgroundInstalled = settings.newThreadComposerBackground !== null` while painter resolves default (web settings-appearance.tsx:110-111, 153-160 vs lib/new-thread-background.ts:253-260) | mirror the desktop fix; file a web follow-up |
| Test coverage of the default | coverage | resolution + gating tested | zero tests reference `default_new_thread_background` (grep across crates/) | tests listed in §6 |

## 5. Do not

- **Do not write the default into `ui-settings.json`** (Option B) — it
  contradicts the documented "never referenced by `ui-settings.json`"
  design (settings.rs:66-71) and the retirement guard
  (`remove_managed_new_thread_background`, settings.rs:396-409) whose
  denylist would then be needed; "Remove" semantics and reset/uninstall
  flows all get murkier (research §5's B trade-offs).
- **Do not change the artwork/mask/dissolve/effect pipelines** — the
  hero layer (shell.rs:5883-5894, 6023), the cutout mask
  (`new_thread_background_mask.rs`), the dock dissolve handoff
  (`composer_dock.rs:90-92`), and the effect raster
  (`new_thread_background_effects.rs::prepare`) are artwork-agnostic
  and already work with the default (research §4: every one of those
  rows sees the default). This ticket only changes WHO resolves the
  background.
- **Do not touch ticket 33's web canvas paint work** — the hero's paint,
  mask, cutout, and transition code is 33's; this ticket's web change is
  the shared resolution helper and the settings page's selection state.
- **Do not invent a "Roboco" preset row** (Option C) — no new wire type,
  no `BackgroundSelection` enum, no serde migration, no selectable
  default entry. The default becomes *recognized*, not *selectable*.
- **Do not build the web titlebar island** — that is ticket 34, which
  keys off the resolved value this ticket provides.
- **Do not change the web painter's fallback behavior** — on a broken
  stored entry the web canvas paints the default
  (`resolveNewThreadBackground`: "the setting's managed blob if it
  decodes, else the bundled default") while the page shows "Image
  unavailable"; that page-vs-painter split on the broken state is
  existing behavior, and this ticket preserves it.
- Do not alter `sync_independent_settings` (shell.rs:3184-3189) — it is
  a raw field copy, not a background-active reader.
- Do not "fix" the synchronous `is_file()` probe on the render path —
  it predates this ticket (island predicate + availability probe); note
  it in review only.

## 6. Acceptance

Desktop:

- [ ] Fresh profile (no `ui-settings.json`, or one with no background
      key): new-thread canvas paints the Roboco artwork AND Settings →
      Appearance shows the background row selected — Roboco artwork
      thumbnail, meta `"Roboco" · "Softened automatically on frosted
      themes."`, actions "Replace image" + "Remove" — plus the
      "Background effect" row visible with its 5 pills selectable.
- [ ] Fresh profile, sidebar collapsed (`mod-b`) on the blank new-thread
      canvas: the frosted titlebar island renders over the default
      artwork (island target 1.0) — same presentation as an installed
      custom background.
- [ ] Existing installs are unchanged where they were already correct:
      an install with a stored background keeps the same row state,
      island behavior, and paint; an install with a stored-but-missing
      file still shows "Image unavailable", hides the effect row, and
      paints no artwork; `ui-settings.json` for a default-background
      install gains no new key (round_trip keeps asserting nothing is
      persisted for the default).
- [ ] Effect changes on the default actually apply: pick "ASCII" as a
      default-background user → the default artwork renders the ASCII
      treatment; pick "None" → back to plain artwork.
- [ ] `cargo test -p roboco-ui` green, including the extended
      `composer_send_behavior_is_opt_in_for_old_and_partial_settings`,
      `background_cleanup_only_removes_files_owned_by_the_setting`,
      `valid_background_replacement_persists_renderable_image_before_retiring_previous`,
      `invalid_initial_background_import_does_not_create_managed_files_or_settings`,
      `round_trip`, `new_thread_handoff_is_continuous_and_staged`,
      `island_stays_centered_on_controls_while_expanding`, plus the new
      accessor / availability / island-gate tests from §3.

Web:

- [ ] Appearance page (`/settings/appearance`) with no background
      stored: the background row shows the default selected (thumbnail
      from the resolved url, meta `"Roboco" · "Softened automatically on
      frosted themes."`, "Replace image" / "Remove" actions) and the
      Background effect pills row is visible and persists a choice that
      the canvas then renders.
- [ ] The painter still paints the default through the same resolution
      (no visual change to the hero); a stored-then-removed background
      falls back to the default everywhere.
- [ ] Unit tests: `resolveActiveNewThreadBackground` (default /
      user / broken) → `web/packages/app/tests/new-thread-background.test.ts`;
      settings-page availability resolution → alongside the background
      tests in `web/packages/app/tests/appearance-store.test.ts`.
- [ ] Screenshot pair, desktop vs web, states: Appearance page
      background row + effect row on a default-background fresh install.
- [ ] `pnpm -r build` green (web/); app package vitest green.
- [ ] No new literal hex/px where a `--rb-*` token exists (this ticket's
      web changes are logic-only; no new CSS is expected).

## Comments

(empty; appended during implementation)
