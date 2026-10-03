# 23 — Compact model picker and effort controls

**What to build:** An optional compact model picker for the composer (on
by default, General settings): effort slider with fast-mode toggle,
provider button + provider page (Starred first), per-model effort memory,
Cursor effort options on the slider, page-scoped keyboard shortcuts, and
per-edge/per-pixel edge fades on the picker surfaces. Follow-up #721: the
compact panel and composer chip name the selected model through one
resolver (the panel no longer reads "Select model" while the chip names
the pick), plus a regression test that settings toggles survive
navigation saves. Drift fold (post-window, see
`.scratch/upstream-drift/2026-10-02.md`): three same-surface follow-ups
landed upstream after the wave's closing fetch and ride this ticket —
#744 (composer's session branch label uses free width, fades on
overflow), #745 (`F` toggles fast mode in the compact picker), #749 (fix
compact picker browsing across providers).

**Blocked by:** None. (Composer + pickers substrate shipped; the effort
ladder and model context exist on both desktop and web.)

**Status:** ready-for-agent

**Upstream SHAs:** `a73fa8fe` (#471) — 27 files incl. new `crates/ui/src/pickers/compact.rs`, `glass.rs` (mapped onto our `frost.rs` — the plate helpers live there, no parallel module), `edge_fade.rs` `fade_scroll_x` + per-edge bands (zui 667d0aa — landed as prerequisite commit c3a46253: branch `roboco/edge-fade-bands`, pin `1e1da65`), icons (fast-tier-bold), haptics (macOS cfg-gated, compile-only), popover, settings/composer.rs, settings/shortcuts.rs, state.rs, shell.rs, shell/tabs.rs, `examples/compact-picker-fixture.rs` (feature `compact-picker-fixture`), plus screenshot docs (the `docs/screenshots/compact-picker/` artifacts are skipped). `ae4181f5` (#721) — `pickers.rs`, `pickers/compact.rs`, `shell.rs`. Drift additions, all desktop-only (`crates/ui`, no edge/): `c14d4579` (#744) — `pickers.rs` (26+/7-); `542febc1` (#745) — `pickers.rs` + `pickers/compact.rs` (22+/2-); `e94c49ad` (#749) — `pickers.rs` + `pickers/compact.rs` (38+/10-). Port them on top of the #471/#721 surfaces this ticket builds — they are fixes to the picker this ticket creates, not independent work. **Web parity (deliverable):** `web/packages/app/src/components/composer-pickers.tsx` + `lib/model-rows.ts` + `lib/picker-search.ts` — port the compact layout (effort slider + fast toggle on the picker surface, per-model effort memory persisted through `state/ui-settings.ts`/`lib/composer-draft.ts`, starred-first provider page). Page-scoped shortcuts map onto the web keymap.

**Zui pin caveat:** upstream's message cites `zui 667d0aa` for the
per-edge/per-pixel fades. Our pin is `c2d273dc`. Read the `edge_fade.rs`
paint hunks first: if the required gpui APIs are not in our pin, land a
`hoangvu12/zui` branch with the needed change and bump pins + patch revs
together (AGENTS.md rule) — as a separate prerequisite commit, not part
of this ticket's diff.

**Verification budget:** `cargo check -p roboco-ui -j 3`; targeted nextest
pickers/compact + composer tests; `pnpm -r build` for web parity.

- [x] Compact picker with effort slider, fast toggle, per-model memory,
      provider page (starred first), page-scoped shortcuts
- [x] Panel and chip agree on the model name (#721)
- [x] Drift follow-ups: branch label width/fade (#744), F fast-mode
      toggle (#745), provider browsing fix (#749)
- [x] Settings-survive-navigation regression test
- [x] Edge fades follow the picked approach (zui check recorded)
- [ ] Tests green (written + ported; execution deferred — see Comments)
- [x] Web: compact picker layout + effort memory + starred-first page
- [x] Port commit records upstream SHAs

## Comments

**Port mapping (commits 74abedd5 + the drift fold):**

- `crates/ui/src/pickers/compact.rs` (new) — upstream's module carried
  nearly verbatim; `crate::glass::*` → `crate::frost::*` (the plate
  helpers landed in frost.rs, upstream's separate glass.rs not
  duplicated); zeron_proto → roboco_proto.
- `pickers.rs` — ModelRail::All + starred-first scoped rows, chip width
  glide (`model_chip_width`, `.width()` — our pin exposes it as a
  method), `resizing_chip_text` + `fast_mode_values` free fns, compact
  key handling in `on_key_down`, `ModelName` resolver (#721), compact
  fixture behind `compact-picker-fixture`, all upstream tests ported.
- `composer.rs` — model picker moves beside Send; `model_handoff`
  removed; `model_bounds` stays `#[cfg(test)]`; the morph test loops
  over both picker modes.
- `frost.rs` — upstream glass.rs plates (light/accent/thumb + `paint`),
  as `pub(crate)`s in our glass module.
- zui prerequisite: pin `ec16c62` → `1e1da65` (branch
  `roboco/edge-fade-bands`, pushed) — commit c3a46253. The SVG-sprite
  per-pixel fade half of zui 667d0aa was excluded (rides zui #12, which
  our line does not carry; icons keep the CPU-side sampling).
- Drift: #744 `footer_faded_label`/`footer_label_shell` + the session
  branch chip; #745 the `f` arm of `compact_panel_key` (+ test); #749
  `show_compact_models` → ModelRail::All unless harness-locked (+ both
  test updates).
- Web: `CompactCard` in composer-pickers.tsx (panel/models/providers,
  page-scoped keys incl. F/Tab/⌘⇧F), `compactModelPicker` ui-setting +
  General toggle, `reasoningByModel` composer-defaults memory +
  `rememberedReasoningFor`/`rememberReasoningForModel`, `ModelRail
  "all"` + `fastModeValues`/`compactEffort`/`compactHiddenOptions` in
  model-rows.ts, compact CSS, regenerated icons manifest
  (fastTierBold; both fast-tier assets switched to `currentColor` —
  desktop SVGs render as alpha masks, so the change is inert there and
  required for web tinting).

**Exclusions / deviations (recorded):**

- `docs/screenshots/compact-picker/` artifacts skipped; the fixture
  example itself (`examples/compact-picker-fixture.rs`) is ported.
- upstream `setting_groups`' `configured_in_place`/`card_order` hunks
  adapted out: Roboco never ported the hover-card settings those order
  (upstream #498 line); the compact panel lists the selected model's
  options in catalog order — test renamed and asserted accordingly
  (`compact_panel_lists_the_selected_models_settings`).
- Roboco's chip keeps the joined traits summary (upstream #498's
  effort-only chip simplification was never ported here); upstream
  #471's Cursor effort-option suffix fallback is therefore covered by
  the summary's option labels — noted in the Render comment.
- macOS-only haptics (NSHapticFeedback) carried compile-gated; the
  objc2-app-kit feature flag added.

**Verification:** `rustfmt --edition 2024` parse/format pass on every
 touched Rust file (pickers.rs brace-balance issues found and fixed);
`cargo check -p roboco -j 3` (clean; the first run surfaced the lost
frost.rs import list and the compact-row `ambiguous` field — both fixed
in 2c093bf5, re-run green); `pnpm install --frozen-lockfile` +
`pnpm -r build` from web/ (clean after 77d5b7e7's two type fixes; the
amended commit hash is 8086a107). The feature-gated fixture
(`compact-picker-fixture`) and all `#[cfg(test)]` suites are written
but NOT compile-verified here, per the verification economy — they ride
the wave-final batched pass. Test execution
deferred to the wave-final batched pass (user directive): ported tests
are `compact_panel_shortcuts_…`, `compact_panel_lists_…`,
`compact_fast_button_…` (+F arm), `compact_list_hides_…`,
`compact_slider_drives_…`, `switching_provider_restores_…`,
`compact_picker_shares_…`, `compact_all_models_…`,
`every_fast_mode_encoding_…`, `model_name_never_reads_…`,
`canvas_reopens_on_its_own_target_…`,
`new_session_reopens_where_it_was_left_…`,
`shell_saves_never_revert_…`, `open_model_trigger_follows_…`, the
`compact_model_picker_is_default_and_opt_out_persists` settings test,
and the web suites'
`compact model rows`, `per_model_effort_memory`, and the mounted
compact-card describe block.
