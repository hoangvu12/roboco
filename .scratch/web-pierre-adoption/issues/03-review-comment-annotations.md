# 03 — Review comments as diff annotations

**What to build:** A web client user reviewing a diff can add a comment on a line (the hover "+"
adder), see staged comment cards inline at their anchored lines, write and commit a draft, and
edit or remove staged comments — all rendered through the diff library's annotation framework,
on top of the new library-rendered Changes pane. The review-comment store, its anchor semantics
(path, side, line, pre-rename path), and its lifecycle are untouched.

**Blocked by:** 02 — Changes pane renders diffs through the diff library.

**Status:** ready-for-agent

- [ ] Hovering an addable diff line shows the "+" adder through the library's built-in gutter
      utility affordance; clicking opens a draft at that (path, side, line) with pre-rename path
      resolution preserved.
- [ ] Staged diff-sourced comments render as inline annotation cards at their anchored lines, in
      staged order, with the comment being edited excluded from the cards (its card becomes the
      draft).
- [ ] The open draft renders inline at its anchor with its live body; commit and cancel flow
      through the unchanged review-comment store.
- [ ] Edit and remove actions work from the card.
- [ ] Annotation anchors survive scope switches, folds, and re-renders — stable metadata ids per
      the library's annotation contract; positions are not re-synced per change event.
- [ ] Split layout: the adder offers on the additions side only; a deletion-side staged comment
      still renders its card.
- [ ] Mounted jsdom test drives the adder → draft → commit path through the store.
