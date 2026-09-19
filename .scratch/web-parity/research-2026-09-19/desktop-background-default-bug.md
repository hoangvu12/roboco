# Desktop research: the Roboco default new-thread background is invisible to Settings and the titlebar island

Date: 2026-09-19. Branch `web-parity/wave-1`, HEAD `bc3a3945`. Read-only research; every
claim cites `file:line` under repo root `C:\Users\ADMIN\Desktop\nguyenvu\roboco`. Desktop
(Rust GPUI app, `crates/ui`) only — filed in the web-parity research dir because it was
reported in the same batch; the web client carries the SAME design flaw (§7 gap rows).

---

## 1. Symptom + exact repro

Fresh install (no `ui-settings.json`, or one written before any background choice). The
new-thread canvas paints the bundled Roboco artwork (`shell.rs:5848-5859`, default resolved
at `shell.rs:5851`), but:

**(a) Settings → Appearance does not recognize the background.** The page reads only
`ui_settings.new_thread_composer_background` (`crates/ui/src/settings/appearance.rs:1994`),
which is `None` by default (`crates/ui/src/settings.rs:706`). So the page shows:

- generic `icons::FILE_IMAGE` tile instead of an artwork thumbnail
  (`settings/appearance.rs:2136-2155`, the `else` at `2153-2155`);
- meta line "Add an image behind the composer on empty new threads."
  (`settings/appearance.rs:2171-2175`, the `None` arm);
- only a "Choose image" action — no "Replace image" / "Remove"
  (`settings/appearance.rs:2218-2229`, gated on `current_background.is_none()`);
- **the entire "Background effect" row is missing** — it is rendered only
  `if background_available` (`settings/appearance.rs:2233-2274`), and
  `background_available` is `current_background...is_some_and(...)` = false with the
  default (`settings/appearance.rs:2133-2135`).

Consequence: a user on the default background cannot reach the effect pills at all, even
though the persisted `new_thread_background_effect` (settings.rs:648, default `None` at
settings.rs:707) genuinely applies to the default artwork (`shell.rs:5853-5857` passes the
effect into `prepare` for the default path too). Worse sub-case: a user who once set
effect = Ascii, then removed their custom background, keeps seeing the ASCII treatment on
the default artwork with no Settings row to change it back.

**(b) The top-left titlebar "island" never appears.** With sidebar collapsed on the
new-thread canvas, the frosted glass backing behind the window-control cluster (toggle +
back/forward) is gated on `settings::current(cx).new_thread_composer_background
.as_ref().is_some_and(...)` (`shell.rs:3983-3994`) — which is `None` on the default, so
`island_target = 0.0` and the frosted element is skipped (`shell.rs:4025-4035`). The
controls sit directly on the Roboco artwork with no frost island — the exact
"some UI doesn't know we already have a background" report.

Repro (desktop, fresh data dir): 1) fresh install → new-thread canvas shows Roboco
artwork; 2) Settings → Appearance → "New thread composer background" row says "Add an
image…" with only "Choose image", and no "Background effect" row; 3) collapse the sidebar
(`mod-b`) on the new-thread canvas → no frosted island behind the top-left controls.
Contrast: install ANY custom image (appearance.rs:370-395 →
`install_new_thread_composer_background`, settings.rs:305-362) and both (a) and (b) flip
to the background-aware presentation.

## 2. The default background chain (file:line, step list)

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

Key fact: the default exists ONLY inside the `or_else` at `shell.rs:5851`. There is no
single "resolve the active background" accessor; every other reader of
"background present" reads the raw persisted field instead.

## 3. The settings recognition mismatch (root cause, file:line)

Root cause: **the default is applied at render time (implicit fallback) while every
"is a background selected/active?" reader compares against the persisted
`new_thread_composer_background` field, which stays `None`.** The default is not a
distinct selectable entry, is not materialized into `ui-settings.json`, and has no
identity (name/id) the settings row could match — there is no id-kind mismatch (image vs
effect); there is simply nothing to compare.

- Settings page selected-state inputs: `current_background =
  ui_settings.new_thread_composer_background` (appearance.rs:1994) — `None` on default.
- Availability probe: `background_available` (appearance.rs:2133-2135) — false on default.
- Actions row selection: `current_background.is_some()` / `.is_none()`
  (appearance.rs:2194, 2218).
- Effect row presence: `if background_available` (appearance.rs:2233).
- Titlebar island predicate: same field, same probe (shell.rs:3986-3989).

History: all background machinery (including the island gate and the Settings gating)
came in with the upstream port `dd3193ec` "feat(ui): add new-thread backgrounds, composer
handoff, and UI polish (#341)" — the user-installed-background era, where "field is
Some" == "artwork is showing" was true. Commit `ba4a3a9e` (2026-09-15) then made artwork
show on EVERY install via the `or_else` fallback (see its shell.rs diff: only
`render_main`'s resolution changed) without updating the other readers. The invariant
"background present ⟺ `new_thread_composer_background` is Some" broke silently.

## 4. Every background-active branch site and whether it sees the default

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

Skipped behaviors on a default-background install, concretely: (1) titlebar frost island
never renders over the artwork; (2) Settings shows "unset" row state; (3) effect pills
row hidden → `new_thread_background_effect` unchangeable while it silently renders on
the default artwork.

## 5. Fix directions (options with trade-offs, recommendation)

**Option A — one resolver, every reader goes through it (recommended).** Add a
`pub fn active_new_thread_background(cx: &App) -> Option<NewThreadComposerBackground>`-ish
accessor in settings.rs that returns the user background when its file exists, else the
materialized default (path + a display name, e.g. `"Roboco"`) — i.e. factor the
`or_else` at `shell.rs:5851` out and make `appearance.rs:1994` and `shell.rs:3986-3989`
use it. The Settings row then shows the Roboco thumbnail/name + "Replace image" +
"Remove", and the effect row shows whenever ANY artwork will render; "Remove" on the
default can either be hidden (indistinguishable from field `None`) or reset-to-default.
Trade-offs: `background_available`'s `is_file()` probe (appearance.rs:2133-2135, also
shell.rs:3989) does synchronous `std::fs` metadata on the render path — it already does
today for the island predicate, so no regression, but note it. No data migration at all:
`ui-settings.json` stays untouched; existing installs (field absent/None) simply start
showing the default as selected. Matches the web's painter pattern
(`resolveNewThreadBackground`, `web/packages/app/src/lib/new-thread-background.ts:253-260`)
— and ideally the web settings page gets the same treatment to fix parity (gap row below).

**Option B — materialize the default into `ui-settings.json` on first run.** Seed
`new_thread_composer_background = { path: <data_dir>/new-thread-backgrounds/default-new-thread-background.png, name: "Roboco" }`
at boot (lib.rs:147-148) when the key is absent. All readers keep working unchanged.
Trade-offs: (1) it contradicts the documented design ("never referenced by
ui-settings.json", settings.rs:66-71) and the retirement guard in
`remove_managed_new_thread_background` (settings.rs:396-409) — the default file's parent
IS the managed dir, so a hand-edited or future "reset" flow could delete the bundled
copy; the guard would need a filename denylist; (2) "Remove" semantics become ambiguous
(removing it must fall back to re-seeding, or the artwork disappears entirely); (3) a
legacy migration concern: existing installs whose file was already written WITHOUT the
key must be detected (missing key ⇒ seed) — same seeding logic anyway; (4) uninstall /
`--reset` flows must tolerate a stale pointer.

**Option C — distinct "Roboco" preset row in Settings.** Make the default a first-class
selectable entry (id `"default"`), with the field becoming an enum-ish
`Option<BackgroundSelection>` (User(path) | Roboco). Most explicit UX (the user can see
and deliberately pick the Roboco background), but the largest change: new wire type,
serde migration for existing files (None ⇒ Roboco vs None ⇒ unset needs a decision),
install/remove flows rewritten, and the web parity ticket reopened. Overkill for the
reported bug; good future direction if "choose the Roboco artwork" becomes a feature.

**Recommendation: Option A.** Smallest blast radius, zero migration, fixes both (a) and
(b) in one place, and honors the existing "default is never persisted" design. If
product wants the default to be explicitly user-selectable later, C is the endpoint; A
does not preclude it. Whichever option ships, also decide the "Remove"-on-default
behavior (Option A: keep "Remove" but make it a no-op-to-default, or hide it).

## 6. Tests to extend (names, file:line)

No existing test covers `default_new_thread_background` at all (only definition
settings.rs:72 and call shell.rs:5851; zero test references — verified by grep). Extend:

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
  settings-page availability resolution.

## 7. Gap rows: item | kind | expected | actual (file:line) | fix sketch

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
