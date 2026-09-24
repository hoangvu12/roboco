# 06 — File tree on the trees library

**What to build:** A web client user browsing a checkout sees the file tree rendered by the
Pierre trees library: clean per-row indentation guides (the stacking-lines bug class is gone),
lazy folder expansion, live git-status coloring, ignored dimming, recognizable file-type icons,
name search, and keyboard navigation. Data still flows through the existing workspace-file
calls and watches; the hand-rolled tree panel, tree model, search-tree model, and ported icon
manifest are deleted.

**Blocked by:** None — can start immediately (trees doesn't need the registered code theme; its
theming maps from web tokens through the library's variables).

**Status:** done

- [x] The trees library is installed and pinned to the exact beta version.
- [x] The tree renders through the library with clean per-row indentation guides — no
      full-height rails, no alpha-stacking — at the existing row density, themed from web tokens
      through the library's variables and following the resolved appearance.
- [x] Expanding a folder lazily issues the existing directory-listing call and feeds the page
      into the model through its mutation API; loading, empty-folder, error-retry, and
      load-more states render.
- [x] The workspace watch drives resets and updates; the sequence-gap resync behavior is
      preserved.
- [x] Git status from the existing checkout git-status watch maps to the library's status lane
      (added, modified, deleted, renamed, untracked); ignored dimming and the show-all toggle
      are preserved.
- [x] File-type icons come from a built-in set with targeted per-name and per-extension remaps;
      the ported icon manifest is deleted.
- [x] Name search uses the library's built-in search, with the existing debounce and capped
      -results banner semantics preserved.
- [x] Keyboard navigation (arrows, enter/space) works per the library's a11y tree.
- [x] Clicking a file opens it in the right pane exactly as before — the surface-add flow is
      unchanged.
- [x] The hand-rolled tree panel, tree model, search-tree model, and icon manifest are deleted —
      no dead exports remain.
- [x] Pure adapter suites cover the git-status mapping and the directory-page → mutation
      mapping; a mounted jsdom test drives expand, watch update, and status update through the
      model.

## Comments

**What landed.** `@pierre/trees@1.0.0-beta.6` (exact pin) renders the tree; the data layer is
preserved and rewired behind it:

- `lib/workspace-tree.ts` — `WorkspaceTreeModel`, the old `FileTreeModel`'s replacement: owns a
  library `FileTree`, drives per-directory `ListWorkspaceDirectory` loads (fresh listing epochs
  with append-continuation `seen` tracking, so a paginated listing's final page replaces exactly
  what the sequence never carried), the `WATCH_WORKSPACE_FILES` frames with the sequence-gap /
  `resyncRequired` resync, the git-status lane join, ignored dimming, the reveal walk, refresh /
  retry-root, and `setIncludeIgnored` (now `resetPaths`-based). Lazy loading rides the sanctioned
  seam: the public mutation stream carries no expand/collapse events, so every model notification
  re-checks tracked directories — an expanded-but-unloaded (or stale) directory becomes a load
  request; loading and failed directories are skipped so a failure can never loop.
- `lib/tree-adapters.ts` — the pure adapters (git-status frame → lane entries, directory page →
  mutation batch, watch event → invalidation, search matches → the ordered result-tree path list,
  row decorations) with their own suite.
- `lib/tree-icons.ts` — the icon seam: `complete` built-in set, `colored: true`, targeted remaps
  (Cargo.toml/lock files/makefile/license/toml, the load-more ellipsis), the sort comparator with
  the marker-last rule, and the standalone-`FileIcon` resolution through
  `createFileTreeIconResolver` + a document-level sprite built from `getBuiltInSpriteSheet`.
- `components/files/file-tree-panel.tsx` — rewritten around `<FileTree model={model.tree}>`:
  the panel chrome (search input, show-all toggle, watch-error banner, root-error block) is
  unchanged; row interaction happens on the HOST element (composed click events carry the row's
  `data-item-path` out of the shadow DOM) — click-to-open, load-more activation, errored-directory
  retry; Enter/Space activate the focused row (the library's keyset handles arrows/Home/End but
  not plain Enter/Space on files).
- `components/files/file-icon.tsx` — re-based on the trees built-in set; the remaining consumers
  (markdown.tsx, tool-group.tsx, diff-view.tsx, file-viewer.tsx, mention-popup.tsx) keep their
  `wellBg` backdrops and drop the now-meaningless `appearance`/`expanded` props.
- Themed from web tokens: `.files-tree-host` maps every relevant `--trees-*-override` variable
  from `--rb-*` tokens (bg/fg/selection wash/border/git colors/indent-guide tone/font); the tokens
  flip with the resolved appearance so the tree follows it. `flattenEmptyDirectories` is forced
  OFF (upstream #941/#635).

**Deleted.** `lib/file-tree.ts`, `lib/file-search-tree.ts`, `lib/file-icons.ts`,
`components/files/file-tree-panel.tsx`'s hand-rolled rows, the `@roboco/icons` generated
file-icons manifest + `generate-file-icons.mjs` + the `public/file-icons/**` assets (4.3 MB), and
the `.files-row*` / `.files-row-guides` / `.files-row[data-git]` CSS — the stacking-guides bug
class dies with it.

**Search decision (the ticket's latitude).** The ticket text says "the library's built-in search";
the built-in surface only filters paths already loaded into the model and has no cap banner, so
per the DECISION LATITUDE the RPC contract is kept: the toolbar input debounces 200 ms →
`SEARCH_WORKSPACE_FILES` over the whole checkout (cap 200 + "Showing the first 200 matches"
banner). The result SET renders through the library: a second trees model is `resetPaths`'d with
the matched paths (ancestors implied, all expanded) in the old search tree's bestScore order
(ranked comparator); activating a result reveals it in the main tree and opens it. The library's
`model.setSearch` is not driven — the RPC semantics are the contract.

**Deliberate deviations / notes for the merger.**

- Standalone `FileIcon`s (markdown refs, tool badges, diff headers, breadcrumb, mention popup) are
  now single-hue `currentColor` glyphs tinted by context — the library's built-ins are single-hue,
  so the old per-appearance polychrome asset split has no analog (ADR 0008 divergence accepted).
  The tree itself shows the colored complete set.
- Drag-out (`beginRowDrag`, `application/x-roboco-workspace-path`) is dropped — per-item
  interactive row content is unsupported on the beta, and the spec lists composer drag-out as out
  of scope (a pre-existing parity gap).
- Directory folders in the tree render with the library's chevron-only look (no folder glyph
  inside the tree); the standalone FileIcon keeps a folder glyph for directory rows.
- Load-more is a synthetic `Load more…` file row (the library has no trailing-row API); the sort
  comparator pins it after its real siblings, and a real file with that basename wins (no marker).
- Loading/empty/error-retry render as the directory row's trailing decoration
  (`renderRowDecoration`, visual-only per the beta); an errored directory's row click is its
  retry (the wrapper re-expands + re-requests), and "Refresh now" keeps its forced resync.
- Untracked rows keep the warning color but lose the old italics (no `--trees-*` variable for it).

**Tests added.** `tests/tree-adapters.test.ts` (23: status classification, page→batch mapping,
watch invalidation, marker math, search-tree build, sort), `tests/workspace-tree.test.ts` (16: the
old model suite's spirit — lazy load, pagination, error/retry, watch/resync, ignored dimming,
reveal, refresh), `tests/tree-icons.test.ts` (11: resolution order, remaps, has-specific, sprite),
and the mounted `tests/file-tree-panel.test.ts` (7, jsdom: chrome + host mount, lazy expand via a
real shadow-DOM row click, click-to-open, load-more, watch + gap resync, git-status lane, and the
full search flow with debounce/banner/reveal-and-open). Old `file-tree`/`file-search-tree`/
`file-icons` suites deleted with their modules. Full app suite: 123 files / 1894 tests green;
`pnpm -r build` and every package typecheck green; `@roboco/icons` check green.
