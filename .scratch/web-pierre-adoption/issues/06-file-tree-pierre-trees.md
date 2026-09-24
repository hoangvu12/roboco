# 06 — File tree on the trees library

**What to build:** A web client user browsing a checkout sees the file tree rendered by the
Pierre trees library: clean per-row indentation guides (the stacking-lines bug class is gone),
lazy folder expansion, live git-status coloring, ignored dimming, recognizable file-type icons,
name search, and keyboard navigation. Data still flows through the existing workspace-file
calls and watches; the hand-rolled tree panel, tree model, search-tree model, and ported icon
manifest are deleted.

**Blocked by:** None — can start immediately (trees doesn't need the registered code theme; its
theming maps from web tokens through the library's variables).

**Status:** ready-for-agent

- [ ] The trees library is installed and pinned to the exact beta version.
- [ ] The tree renders through the library with clean per-row indentation guides — no
      full-height rails, no alpha-stacking — at the existing row density, themed from web tokens
      through the library's variables and following the resolved appearance.
- [ ] Expanding a folder lazily issues the existing directory-listing call and feeds the page
      into the model through its mutation API; loading, empty-folder, error-retry, and
      load-more states render.
- [ ] The workspace watch drives resets and updates; the sequence-gap resync behavior is
      preserved.
- [ ] Git status from the existing checkout git-status watch maps to the library's status lane
      (added, modified, deleted, renamed, untracked); ignored dimming and the show-all toggle
      are preserved.
- [ ] File-type icons come from a built-in set with targeted per-name and per-extension remaps;
      the ported icon manifest is deleted.
- [ ] Name search uses the library's built-in search, with the existing debounce and capped
      -results banner semantics preserved.
- [ ] Keyboard navigation (arrows, enter/space) works per the library's a11y tree.
- [ ] Clicking a file opens it in the right pane exactly as before — the surface-add flow is
      unchanged.
- [ ] The hand-rolled tree panel, tree model, search-tree model, and icon manifest are deleted —
      no dead exports remain.
- [ ] Pure adapter suites cover the git-status mapping and the directory-page → mutation
      mapping; a mounted jsdom test drives expand, watch update, and status update through the
      model.
