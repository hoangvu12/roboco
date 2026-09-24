# 02 — Changes pane renders diffs through the diff library, with the registered Roboco code theme

**What to build:** A web client user opens a chat with working-tree changes and sees a real,
virtualized, syntax-highlighted diff inside the existing Changes chrome — scope banner with
+N/−N and the partial-snapshot chip, scope selector, base picker, split/wrap toggles, per-file
folds — all preserved, now rendered by the Pierre diff library instead of the hand-rolled viewer.
A Roboco code theme is registered with the library, generated from the same compiled
theme-variant source that feeds the theme artifact, so code colors follow the selected theme
family and appearance. The old diff renderer stays alive only for the transcript's tool-diff
blocks until a later ticket migrates them.

**Blocked by:** 01 — Diff empty states + checkout-folder normalization (shared component
territory; lands the empty-state branches before swapping the render path — a sequencing edge,
not a hard data dependency).

**Status:** ready-for-agent

- [ ] The diff library is installed and pinned to an exact version (no floating ranges).
- [ ] The resolved diff's patch string parses through the library's patch parser, memoized per
      checkout + checksum — never re-parsed per render.
- [ ] The Changes pane body renders the per-file diff metadata through the library's mixed
      virtualized code/diff list, mounted inside the existing surface chrome.
- [ ] The scope selector, base picker, split/unified toggle, wrap toggle, and per-file folds keep
      living in the surface store and map onto library options; commit-pinned diff tabs render
      through the same path.
- [ ] A Roboco code theme is registered, generated from the same source that feeds the theme
      artifact freshness gate (derived, not hand-copied); light/dark follows the resolved
      appearance; fonts, sizes, and diff add/delete colors map from web tokens through the
      library's documented variables.
- [ ] The theme artifact freshness gate stays green.
- [ ] The old diff renderer still works for the transcript's tool-diff blocks (interim state —
      nothing else regresses).
- [ ] Mounted jsdom test: the Changes body mounts with scripted store fixtures, and store writes
      (scope switch, fold toggle) drive it — following the repo's mounted idiom.
- [ ] Pure adapter suite covers the patch → parsed-metadata mapping including fold/version state.
- [ ] CI gates (web build, wire freshness, theme artifact, engine-client suites) stay green.
