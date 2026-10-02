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

**Blocked by:** None.

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

- [ ] Per-surface registry: drag anchored in any transcript resolves
- [ ] Modal drag/copy isolation; wizard-borrowed composer keeps Copy
- [ ] Table-column selection spans correctly
- [ ] Selection tests green
- [ ] Port commit records upstream SHAs

## Comments
