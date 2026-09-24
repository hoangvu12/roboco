# 03 — Review comments as diff annotations

**What to build:** A web client user reviewing a diff can add a comment on a line (the hover "+"
adder), see staged comment cards inline at their anchored lines, write and commit a draft, and
edit or remove staged comments — all rendered through the diff library's annotation framework,
on top of the new library-rendered Changes pane. The review-comment store, its anchor semantics
(path, side, line, pre-rename path), and its lifecycle are untouched.

**Blocked by:** 02 — Changes pane renders diffs through the diff library.

**Status:** done

- [x] Hovering an addable diff line shows the "+" adder through the library's built-in gutter
      utility affordance; clicking opens a draft at that (path, side, line) with pre-rename path
      resolution preserved.
- [x] Staged diff-sourced comments render as inline annotation cards at their anchored lines, in
      staged order, with the comment being edited excluded from the cards (its card becomes the
      draft).
- [x] The open draft renders inline at its anchor with its live body; commit and cancel flow
      through the unchanged review-comment store.
- [x] Edit and remove actions work from the card.
- [x] Annotation anchors survive scope switches, folds, and re-renders — stable metadata ids per
      the library's annotation contract; positions are not re-synced per change event.
- [x] Split layout: the adder offers on the additions side only; a deletion-side staged comment
      still renders its card.
- [x] Mounted jsdom test drives the adder → draft → commit path through the store.

## Comments

**What landed.** The Changes pane's review-comment affordances ride the library's own review
surfaces, on top of ticket 02's `ChangesDiffList`:

- `lib/changes-diff.ts` (the pure adapter) gained three additions:
  - `diffCommentAnnotations(files, comments, draft)` — the visible staged diff comments + the
    open draft → per-file `DiffLineAnnotation[]` keyed by the file's (post-change) path. The
    library's annotation side is the line number ON THAT SIDE (`deletions` = old numbering,
    `additions` = new) — matching the comment store's `(path, side, line)` anchors directly, no
    translation. File-sourced comments and comments whose file isn't in the parsed diff are
    skipped (no anchor to render at — same as the old `bodyRows` interleaving); same-anchor
    comments keep staged order; the draft renders after same-anchor cards (the old `pushCards`
    order).
  - The annotation metadata: `{kind: "comment", id}` per staged comment and a `{kind: "draft"}`
    singleton. Metadata identity is the library's annotation equality key
    (`areDiffLineAnnotationsEqual` compares metadata by identity), so comment metadata comes
    from a `WeakMap` keyed by the comment OBJECT — stable across unrelated re-renders, minted
    fresh when the store re-stages the comment (a body update) so the card re-renders. The
    draft's singleton never churns on keystrokes; only its annotation's anchor fields can move.
    The React `renderAnnotation` callback resolves the LIVE comment/draft by id from the current
    store state, so the metadata needs nothing else.
  - `diffCodeItems(files, folds, annotationsByFile)` — the item version ledger now also tracks
    the per-file annotation array identity: the library only adopts changed item annotations
    through a version bump, so a changed comment set re-versions exactly the files it touched.
    Files with no annotations carry `undefined` (no churn).
  - `diffAdderAnchor(fileDiff, layout, range)` — a gutter-utility click → the draft anchor the
    store opens: `side` `deletions`→`old` / `additions`→`new` (the library's side follows the
    hovered gutter line — exactly the old `diffLineAnchor` semantics: del → old; else new),
    `path` = `fileDiff.name` (the current path the store anchors by), and `oldPath` =
    `fileDiff.prevName` for old-side clicks on renamed files (the pre-rename cite path).
- `routes/changes-page.tsx` — `ChangesBody` passes the visible staged set (the edited comment
  and file-sourced comments excluded, exactly as before) and the open draft down to
  `ChangesDiffList`, plus `offerAdder` (the old `renderAdder` gate: commit-pinned tabs don't
  offer the adder — a commit diff is a record, not a review surface; staged cards still render
  there). `ChangesDiffList` derives `annotationsByFile` memoized (same inputs → same arrays, so
  re-renders keep the item versions quiet), mounts `renderAnnotation` — `CommentCard` /
  `CommentDraft` re-mounted verbatim inside the library's annotation slot, with the draft header
  citing `draft_cite_path` (the pre-rename path on the Old side) and every action calling the
  UNCHANGED `reviewCommentStore` — and adds the gutter utility options. No store, anchor, or
  codec change anywhere: `state/review-comments.ts`, `lib/review-comments.ts`, and
  `withComments` are untouched (the model suite passes unmodified).

**The adder approach: the library's built-in gutter utility.** `enableGutterUtility: true` +
`onGutterUtilityClick(range, context)` in the CodeView options — the library renders and
positions its own `+` on hovered lines (in the shadow DOM's number gutter) and calls the option
with the clicked side + line; `context.item.fileDiff` hands us the clicked file directly (the
old `files.find(path)` lookup is gone). One gate: split layout keeps the old right-half-only
rule — a deletions-side range in split resolves no anchor (`diffAdderAnchor` returns null), so
drafts open from the additions side only; unified offers both sides exactly as before. A
multi-line drag anchors at the range's start (the line whose `+` was pressed).

**Documented deviation — the split affordance's visual presence.** The library's hover
placement shows the `+` on BOTH columns in split (it places the utility on whichever column's
line is hovered; there is no per-side placement option, and the custom `renderGutterUtility`
path — the only way to hide it — is mutually exclusive with `onGutterUtilityClick` and needs
its own hover-tracking React content to gate per side). So in split the deletions-column `+` is
visually present but inert: the click opens no draft, preserving the authored behavior
("the adder offers on the additions side only"). If the inert affordance reads worse than a
permissive one, the one-line flip is to let deletions-side clicks open old-side drafts (the
store handles them fine — unified already stages old-side comments); noted for the merger.

**Tests.**

- `tests/changes-review-comments.test.ts` (new, jsdom, 6 tests) — the real `ChangesSurface`
  mounts over the scripted watch (the ticket-02 mounted idiom) and every mutation flows through
  the real store: adder → draft at (path, side, line) → typed body → commit stages the comment
  and renders the card; cancel stages nothing; edit (the card's pen) re-opens the draft
  pre-filled with the card excluded, save updates the body, remove drops it; a deletions-side
  click on a renamed file opens the draft anchored old-side citing `old.txt` (draft header AND
  the staged card's location); comments staged BEFORE the pane mounts render on first mount (the
  re-opened-pane path); split: a deletion-side staged card survives the layout switch, the
  deletions-side click opens nothing, the additions-side click does, and back on unified the
  old side is offered again. The anchor the library places each card at is asserted through the
  portal wrapper's `slot` attribute (`annotation-additions-2`) — light DOM, not shadow
  internals. The gutter-utility click itself can't be exercised in jsdom (the built-in button
  materializes only on a real hover), so the suite drives the mounted option's handler
  directly via the library's own `window.__INSTANCE` debug handle — the option IS our handler
  closed over the live chat id; the pointer plumbing from the button to the option is
  upstream's contract. Waits poll adaptively (the library's render is rAF-scheduled; fixed
  sleeps undershoot on a loaded machine).
- `tests/changes-diff.test.ts` (+11 pure tests) — the annotation mapping (side/lineNumber per
  anchor, staged order per anchor, draft last, file-sourced + foreign-file comments skipped,
  the draft's file check), the metadata identity contract (stable per comment object across
  recomputes, fresh on re-stage, the draft singleton), the item version ledger (bump on
  annotation identity change, hold on stable identity, drop back to `undefined`), and the
  adder anchor resolution (additions → new side, deletions → old side with `prevName` as
  oldPath, unrenamed → null oldPath, split gate, drag-anchors-at-start, sideless → null). The
  12 ticket-02 tests are unchanged apart from the `LAnnotation` generic in the `asDiffItem`
  helper.

**Verification.** `pnpm -r build` from `web/` green; full app suite
**130 files / 1957 tests green** (baseline 129/1940; +1 file, +16 tests); `tests/review-comments.test.ts`
passes UNMODIFIED. The full suite on this machine has a pre-existing, load-sensitive flake in
`tests/registry.test.ts`'s wall-clock backoff timing test (and occasionally
`pierre-theme`/`composer-reasoning`/`sidebar-view-menu`) — it fails on the clean 129-file tree
under the same load and passes in isolation; my suites are unaffected (they wait adaptively).
Bundle: main +3.7 kB raw on ticket 02's number (the `CommentCard`/`CommentDraft` components were
already in the bundle through `diff-view.tsx`; the delta is the adapter + wiring).

**Not done / notes for ticket 04.** The `DiffReviewWiring` type and the comment-row arms of
`bodyRows`/`estimateRowHeight` in `lib/diff.ts` + `components/diff-view.tsx` are now
unreferenced by the Changes pane (the transcript's `FileBodyUpto` passes no comments) — ticket
04's deletion sweep takes them. `changesSurfaceStore.setComments` has no remaining consumer
after the fold-tween removal; it stays as the documented analytic input until that sweep.
