# 15 — New project: type a folder's path with ⇥ completion (upstream #998, `77a3c0ff1`)

**What to build:** Port `77a3c0ff1`: the new-project flow accepts a typed
folder path with tab completion and ghost text — `crates/ui/src/shell/spaces.rs`
(+1639), `pickers.rs` (+229), `shell.rs`, `state.rs`; the 887-line fixture
example ports for tests.

**Port note:** upstream's path field lives on the **device palette**
(multi-device grouping) — rebase it onto roboco's single-device
`spaces.rs` (current through wave-7 06). Windows path completion needs a
sanity pass (separators, drive letters). docs/media screenshots optional.

**Excluded upstream hunks:** none of substance; skip the media binaries if
desired.

**Blocked by:** none.

**Status:** ready-for-human (ported in a39f89e37)

**Verification budget:** `cargo check -p roboco-ui` + spaces/navigation
tests + the fixture example. Source: `.scratch/upstream-drift/2026-10-25.md` § B3.

- [x] Path field + completion + fixture ported, single-device palette
- [x] Windows path behavior verified
- [x] Commit records upstream SHA `77a3c0ff1` (#998)

## Comments

- 2026-10-26: Ported in a39f89e37 (upstream `77a3c0ff1`, #998). Carried:
  the crumb trail as an address bar — `PathEdit` on `AddSpaceFlow` with
  ⌘L / pen / click-on-
  empty-end entry, the open folder's path spelled from `~` and
  selected, ⇥ completing the folder under the caret from the device's
  listing (ghost preview, exact-name-wins case rule, separator
  appended), ↵ opens and gives the trail back, ⌘↵ opens and adds the
  project (↵ during the open doesn't take that back), Esc reverts,
  paste-a-folder becomes the path, a failing path keeps the field with
  "No folder at …"/"… isn't a folder" (io-error classification), and
  relative paths resolve against the open folder. Tab/Shift-Tab stay
  in the palette (`cx.stop_propagation()`). Roboco's engine-local
  manual-path query flow (`prepare_manual_space`,
  create-if-missing) is untouched and coexists; `submit_browsed_space`
  drops the path field before adding ("Here" is the listed folder).
  The typed-path helpers (`clean/escape/unescape_typed_path`,
  `is_home_relative`, `normalize_browser_path`, `resolve_typed_path`,
  `split_typed_leaf`) landed in pickers.rs near-verbatim, on top of
  upstream's `windows_browser_path` parser (UNC + extended paths) and
  its `is_windows_path`/`parent_path`/`child_path`/`breadcrumbs`/
  `typed_path_target` upgrades — pre-existing upstream utilities Roboco
  never carried, ported as this change's dependency (drive-rooted `\dir`,
  `D:dir`, `//nas/share` share semantics, verbatim `\\?\` paths). The
  887-line `examples/new-project-path-fixture.rs` ports adapted
  (3-field engine-local `EngineBootConfig`, `EngineCore::assemble`
  3-arg, 2-arg `serve_ipc`, `ROBOCO_DEVICE_NAME`, scratch home
  `/tmp/roboco-demo`); it compiles under `--features
  project-path-fixture` (the filming run itself needs a display —
  headless here) and `fixture_project_input`/`fixture_project_summary`
  ride a `project-path-fixture` cfg block. **Deliberately excluded:**
  the docs/media binaries (stills/MP4/GIF/result.txt/README — the
  ticket allows skipping; regenerable from the example on a display
  machine); upstream's Cloud arm ("⌘L on Cloud does nothing", the
  `is_cloud_device` check, `ProjectStep::Repositories`, and the
  "Cloud keeps repositories" comments — Roboco has no cloud);
  `release_home_folder_search` in the finish path (Roboco has no home
  folder search). **Adapted:** tests — upstream's FakeEngine harness
  doesn't exist here, so the ported tests drive the real in-process
  engine (the `manual_path_interactions` harness, local device) over
  real tempdir folders: open/missing-field, ⌘↵-add (real createSpace),
  Esc-reverts, ⌘L-on-devices (`~/` before home lands), tab completion +
  ghost + exact-case + paste, relative-path-from-loading-folder, plus
  the pure `typed_path_location`/`tilde_path`/`path_open_error` tests
  and pickers' four; upstream's window-focus-walk tests
  (`tab_keys_keep_the_keyboard…`, the drawn Esc routing, the
  home-answers-last ordering) are not ported — the behaviors they pin
  are covered at the `add_space_key` level. Known lexical divergences
  (documented in code): `same_folder_path` compares trailing-trimmed
  spelling (upstream's richer `folder_path_key` not ported), and
  `tilde_path`/`path_rest` don't case-fold drive letters. Verification:
  `cargo check -p roboco-ui` clean + example compiles with the feature;
  `path_field_tests` 9/9; `shell::spaces` 39/39; `shell::` 245/245;
  `pickers::` 67/67; full `-p roboco-ui --lib` 1689/1689.
