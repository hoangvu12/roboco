# 12 — Transcript drag-selection fixes: per-surface, popups, tables

**What to build:** Three fixes to the markdown drag-selection machinery:
(1) the selection registry was one thread-local list that every
transcript's frame reset cleared — with a side chat or subagent tab beside
the main chat only the last-painted transcript kept its text; give each
transcript a surface id (its entity id), reset only its own entries, stamp
entries with their surface, resolve a drag only against the surface it
started in; (2) transcript selection leaked through popups — isolate
selection behind modals (dragging inside a modal input must not select
transcript text; copying with no input selection while transcript text is
selected must not leak through), and keep transcript Copy in the
wizard-borrowed message composer by marking that input explicitly instead
of gating on key context; (3) drag selection across markdown table columns
selects the full logical column span.

**Blocked by:** 14 — both touch `composer.rs` (14 is the small one;
question-panel pill lands before the selection gating).

**Status:** ready-for-agent

**Upstream SHAs:** `366b3c9e` (#632) — `markdown/render.rs`,
`markdown/selection.rs`, `transcript.rs`; `92be0f26` (#556) —
`composer.rs`, `composer/modal_selection_tests.rs` (new),
`markdown/render.rs`, `transcript.rs`; `6641095b` (#681) —
`markdown/render.rs`. All under `crates/ui/src/`, all exist here.

**Verification budget:** `cargo check -p roboco-ui -j 3`; targeted nextest
selection + modal_selection tests. Desktop-only: web has no GPUI
drag-selection machinery (native DOM selection) — record the check, no
forced parity.

- [x] Per-surface registry: drag anchored in any transcript resolves
- [x] Modal drag/copy isolation; wizard-borrowed composer keeps Copy
- [x] Table-column selection spans correctly
- [ ] Selection tests green (all ported; execution deferred to the
      wave-final batched pass — user directive)
- [x] Port commit records upstream SHAs

## Comments

- Ported `92be0f26` (#556), `6641095b` (#681), and `366b3c9e` (#632) as
  their COMBINED final state (upstream landed them in that order; the
  intermediate shapes never existed here).
- **#632 per-surface registry** (`markdown/render.rs` +
  `markdown/selection.rs` + `transcript.rs`): `RegEntry` gains a `surface`
  stamp; new thread-locals `PAINTING_SURFACE`/`PAINTED_SURFACES`;
  `selection_frame_reset()` delegates to the new
  `selection_frame_reset_for(surface)`, which clears only its own surface's
  entries, stamps the text painted after it, and drops a closed surface's
  entries once a full cycle of another surface's resets passes without it.
  The transcript root keys its reset by `cx.entity_id().as_u64()`. The
  selection records the surface it began on (`begin_in`/
  `begin_with_span_in`; `MdSelection.surface`), and `wash_range` /
  `registry_point` / `resolve_drag` all resolve only against that surface,
  so a forked pane sharing keys keeps its wash to itself and a second
  transcript can't capture the drag. The selection test module was diffed
  against upstream's final file: byte-identical apart from upstream's
  #606/#633 file-link tests (ticket 13, files lane) and our pre-existing
  roboco-side tests.
- **#556 popup isolation** (`markdown/render.rs` + `transcript.rs` +
  `composer.rs`): every selectable text underlay canvas (flat text,
  `selectable_text_element`, user bubbles) now inserts a Normal hitbox in
  its prepaint and passes it to `paint_text_selection`, whose mouse-down
  listener additionally requires `hitbox.is_hovered(window)` — geometry
  alone included text hidden behind popups. Copy isolation:
  `ComposerInput` gains `copies_transcript_selection` (false by default;
  set true only on the message composer in `Composer::new`) so dialog and
  palette fields copy only their own selection while the wizard-borrowed
  message input keeps the transcript Copy fallback (the flag survives the
  key-context swap). New `crates/ui/src/composer/modal_selection_tests.rs`
  ports both regressions (modal-input drag; modal-input copy) and
  `message_composer_still_copies_transcript_selection` lands in the main
  test module; `CodeSelectionHarness` gains an `occluded` arm and
  `occluded_text_ignores_double_and_triple_clicks`.
- **#681 table columns** (`markdown/render.rs`): `registry_point` picks
  the nearest element by (vertical, horizontal) distance instead of
  vertical alone, so side-by-side table cells and their padding resolve
  to the right cell and a far-right drag stays on its line. Three
  `MarkdownSelectionHarness` regressions ported (per-column tracking
  with a wrapped cell, padding clamps + heading clamp, and
  across-cells-in-document-order joins).
- Desktop-only by design (recorded, no forced parity): web renders
  transcripts through native DOM selection — there is no registry
  machinery to port, and popup isolation is inherent to DOM overlays.
- Exclusions: none — all three upstream diffs are ui-crate only.
- Verification: `rustfmt --edition 2024 --check` clean on every touched
  file (no new drift; pre-existing drift hunks in composer.rs/
  transcript.rs left untouched); ONE `cargo check -p roboco -j 3` after
  touching all edited files → Finished in 45.35s, zero errors, warnings
  all pre-existing dead code on main; `pnpm -r build` from web/ green
  (tickets 11/19's web side). Test execution deferred to the wave-final
  batched pass (user directive).
