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

**Status:** done

- [x] The diff library is installed and pinned to an exact version (no floating ranges).
- [x] The resolved diff's patch string parses through the library's patch parser, memoized per
      checkout + checksum — never re-parsed per render.
- [x] The Changes pane body renders the per-file diff metadata through the library's mixed
      virtualized code/diff list, mounted inside the existing surface chrome.
- [x] The scope selector, base picker, split/unified toggle, wrap toggle, and per-file folds keep
      living in the surface store and map onto library options; commit-pinned diff tabs render
      through the same path.
- [x] A Roboco code theme is registered, generated from the same source that feeds the theme
      artifact freshness gate (derived, not hand-copied); light/dark follows the resolved
      appearance; fonts, sizes, and diff add/delete colors map from web tokens through the
      library's documented variables.
- [x] The theme artifact freshness gate stays green.
- [x] The old diff renderer still works for the transcript's tool-diff blocks (interim state —
      nothing else regresses).
- [x] Mounted jsdom test: the Changes body mounts with scripted store fixtures, and store writes
      (scope switch, fold toggle) drive it — following the repo's mounted idiom.
- [x] Pure adapter suite covers the patch → parsed-metadata mapping including fold/version state.
- [x] CI gates (web build, wire freshness, theme artifact, engine-client suites) stay green.

## Comments

**What landed.** `@pierre/diffs@1.4.3` (exact pin) renders the Changes pane's diffs; the data layer is
untouched (`ChangesStore`, watch/retry/scoped captures, branches):

- `lib/changes-diff.ts` — the pure adapter: `parseDiffFiles` (patch string → `FileDiffMetadata[]`,
  memoized LRU of 8 keyed by `parseKey(checkoutId, checksum, scope, baseRef)` — the old
  `useParsedDiff` discipline, now also folding scope+base into the key, which the old hook keyed
  under `workingTree` regardless), `diffCodeItems` (files + surface-store folds → controlled
  `CodeViewItem[]`: `id` = the file path, `collapsed` from the fold map, `version` from a WeakMap
  ledger that re-versions a file when its parse identity OR fold state changes — the library only
  adopts changed `fileDiff`/`collapsed` through a version bump), and `fileDiffNotices` (the old
  notice copy: New file / Deleted file / Binary file — contents not shown / Mode changed to N).
  Binary detection: the parser keeps a no-hunk, no-lines, no-mode-marker `change` section — that is
  git's binary arm. Renames carry no notice: the library's default header already renders
  `prevName → name` inline.
- `routes/changes-page.tsx` — `ChangesBody` swaps the `DiffView` mount for the library's React
  `CodeView` inside the unchanged chrome: scope banner (+N/−N, partial-snapshot chip), scope
  selector, base picker, split/wrap/fold-all tools, ticket 01's empty states, the CR card, error
  banners. The private `ChangesDiffList` maps the surface store onto library options
  (`diffStyle`/`overflow` from layout/wrap; `stickyHeaders: true` for the old sticky file headers)
  and scrolls to top on `scrollEpoch` via `CodeViewHandle.scrollTo` (scope/base/layout/wrap
  switches). The per-file fold chevron and the notices ride the library header's `renderHeaderPrefix`/
  `renderHeaderMetadata` slots — light-DOM slot content, so the header keeps the library's look with
  our controls. Commit-pinned tabs render through the same path (the surface registry was already
  wired that way).
- `state/changes-surface.ts` — the fold model adapts: `toggleFold`/`toggleCollapseAll` write steady
  `collapsed`+`epoch` (the library renders the collapse; the old tween arming/settle sweep is gone),
  and `setFiles` takes the foldable paths. The legacy `FileFold` tween fields stay on the type for
  `lib/diff.ts`'s row model until ticket 04 deletes it.
- `styles/app.css` — `.changes-code-host` (the library's scroll container): layout, mono/sans
  families from the web font tokens, `--diffs-tab-size: 4`, and the diff add/delete colors through
  the library's documented `--diffs-addition-color-override`/`--diffs-deletion-color-override`
  (`--rb-diff-add`/`--rb-diff-delete`); `--diffs-modified-color-override` maps the selection wash to
  the accent. Font size/line height are runtime settings — inline
  `--diffs-font-size`/`--diffs-line-height` from `diffTextSize`/`diffLineHeight`.

**Theme registration approach.** `lib/pierre-theme.ts` (the shared seam ticket 05 reuses): ONE
registration pair — `roboco-dark`/`roboco-light` — built by `robocoDiffsThemeRegistration(variant)`,
pure. Every token color is `var(--rb-syntax-<kebab>, <artifact literal>)`: the scope table
(`SYNTAX_SCOPE_TO_ROLE`) maps TextMate scopes onto the artifact's 25 syntax roles, so the palette is
resolved at paint time from the tokens `applyThemeVariant` installs — the artifact is the source
(the gate stays authoritative), family/appearance switches recolor live, and nothing is
hand-copied. The fallback literals derive from `DEFAULT_APPEARANCE`'s variants through
`findVariant`. Passed as the library's `{dark, light}` theme pair with `themeType` = the resolved
appearance — a pair theme leaves the shadow tree's `color-scheme` to `themeType`, so every
`light-dark()` in the library's stylesheet follows the app (both arms read the same live tokens, so
the pair's job is appearance-following, not two palettes; this is the library's designed mode and
costs the same double tokenization its default `pierre-dark`/`pierre-light` pair pays). Verified
end-to-end in the theme suite: the registered loaders resolve through the library's shared
highlighter and a pair render emits the `--diffs-token-<arm>: var(--rb-syntax-*)` custom properties
the shadow stylesheet consumes. Registration is idempotent at module scope (changes-page calls it
once per process).

**Interim regression (by design, ticket 03 restores it).** The review-comment adder, inline cards,
and draft rendering are dark in the Changes pane — the old `DiffView` rendered them; the library
path doesn't until ticket 03 maps them onto the library's line annotations. The `reviewCommentStore`
and `changesSurfaceStore.setComments` input are intact (changes-page still stages
`visibleComments`/draft into the store), so nothing authored is lost; only the render wiring
(`renderAdder`/`reviewWiring`/the `CommentAdder` import) was removed from changes-page.tsx. The
transcript's tool-diff blocks still render through `FileBodyUpto`/`FilePlaneScroll` from the
untouched `components/diff-view.tsx` (its imports/exports are unbroken; `useParsedDiff` stays
exported for ticket 04's deletion sweep).

**Deliberate deviations / notes for the merger.**

- **`loadDiffFiles` (hunk-context expansion) is NOT wired.** The library hydrates partial diffs from
  full old/new file contents, but `ReadWorkspaceFile` reads the working tree — the NEW side only.
  A working-tree/branch patch's pre-image lives in the index/base ref, which the existing wire
  cannot read, so a both-sides hydration would silently corrupt the old column's context lines.
  The library renders partial patches natively (inter-hunk gaps show "N unchanged lines"
  separators without expand affordances — git's own patch context is all shown); correct expansion
  needs a ref-read RPC or engine-side hydration, a wire change this ticket's scope forbids. User
  story 5 is served to the patch's context extent.
- **Bundle: +612.97 kB raw / +172.70 kB gzipped on the main bundle** (2,359.07→2,972.04 kB raw;
  825.87→998.57 kB gzip; +20.9%). Above the accepted ~120 KB gzip because research §6 measured
  `File`+`FileDiff`+`parsePatchFiles`, not the mandated `CodeView` mixed list: probing the same
  way, the `CodeView` react entry alone costs ~630 kB raw/~176.5 kB gzip (the coordinator,
  virtualizer, interaction manager, and the unconditional edit-mode machinery it imports), and the
  root-barrel import adds ~0.3 kB on top (tree-shaking holds). Lazy-loading the Changes diff list
  behind a `React.lazy` boundary would move the whole cost off the main bundle — a follow-up
  decision, not made here. On-disk dist: 6.5→17.9 MB — the per-language chunks (lazy, fetched per
  language) plus the 622 KB wasm chunk (fetched only for `preferredHighlighter: 'shiki-wasm'`;
  the default JS regex engine is what ships in main), exactly research's predicted shape.
- Peer warning: `@pierre/trees@1.0.0-beta.6`'s `@pierre/theming@1.0.0` peer wants
  `@pierre/theme@^1.1.0` while diffs' 1.4.3 pins `@pierre/theme@2.0.0` (one copy in the graph —
  theming 1.0.1, diffs' dep, accepts both). `pnpm peers check` flags the beta's stale range;
  nothing else changed.
- Fold keys are now the library's `file.name` (the post-change path — same semantics as the old
  `FileDiff.path`); the surface store's fold map follows.

**Tests added.** `tests/changes-diff.test.ts` (12: parse mapping incl. binary/mode/rename
classification, memoization + scope/base keying, the 8-entry LRU, cacheKey seeding, items
fold/version ledger), `tests/pierre-theme.test.ts` (7: registration derived from the artifact —
every role a `var(--rb-syntax-*)` with the variant literal, all 25 roles covered, fg/bg/ansi
mapping, fallback derivation, idempotent registration, and the end-to-end pair render through the
library's highlighter), and the mounted `tests/changes-code-view.test.ts` (4, jsdom: chrome + host
mount, fold drive through the store flipping our header-slot chevron, the notices slot, and the
scope switch re-rendering through the real scoped capture — assertions stay outside the shadow
DOM). Ticket 01's mounted empty-state suite is unchanged and green. Full app suite:
**129 files / 1940 tests green** (baseline 126/1917). `pnpm -r build` green;
`roboco-theme-export --check` green ("theme artifact is fresh"); nothing touched under
`crates/theme` or `web/packages/theme` (the registration only reads them).
