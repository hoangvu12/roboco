# 04 — Transcript tool-diffs via the library; delete the old diff renderer and patch parser

**What to build:** The transcript's stacked tool-diff blocks (an inline tool call's file changes)
render through the diff library — always expanded, unified layout, no comment affordances, with
the file notices (new file, deleted file, renamed, binary) preserved. With this last consumer of
the old renderer migrated, the hand-rolled virtualized diff viewer, its row components, and the
hand-rolled patch parser / row model are deleted. The diff logic the store consumes —
resolution, phase classification, scope labels, clean messages, frame upsert — remains.

**Blocked by:** 02 — Changes pane renders diffs through the diff library.

**Status:** done

- [x] The transcript's stacked tool-diff blocks render through the library, always-expanded and
      unified, with the file notices preserved.
- [x] The old virtualized diff viewer, its row components, and the hand-rolled patch parser and
      row model are deleted — no dead exports remain from the deleted modules.
- [x] Diff resolution, phase classification, scope labels, clean messages, and frame upsert
      logic remain, and their existing pure-logic suites keep passing.
- [x] Transcript render-smoke tests are updated for the new rendering path and pass.
- [x] No other surface regresses (the Changes pane and its comments from tickets 02–03 are
      unaffected).

## Comments

**What landed.** The transcript's stacked tool-diff blocks render through the library; the
hand-rolled diff stack is gone (net −3,054 lines of app source + tests):

- `lib/tool-diff.ts` (new, the pure adapter seam) — `toolDiffFileDiff` (a `ToolDiff`'s full
  old/new contents → the library's `FileDiffMetadata` via `parseDiffFromFile`, the library's own
  jsdiff, with `{context: 3}` matching the old Myers walk's `grouped_ops(3)` grouping),
  `truncateFileDiffLines` (the `DIFF_DETAIL_MAX_LINES` = 600 line-row cap, cutting the crossing
  hunk MID-BLOCK so a whole-file rewrite — one giant block — still caps, slicing the line arrays
  to the cut so every kept absolute index stays valid and no phantom trailing context appears),
  and `toolDiffDetailHeight` (the analytic height estimate for ticket 70's shared geometry
  contract, mirroring the library's own height accounting for our fixed option set: default
  header 44, `line-info` separators, collapsed inter-hunk gaps at the library's 1-line threshold,
  8px bottom pad). `oldText: null` → the library's `type: "new"` ("New file" notice); identical
  sides → zero hunks → no detail (the old `diffToFile` semantics); a content-hashed `cacheKey`
  (cap folded in) lets the library's highlight caches survive transcript-row remounts.
- `lib/transcript.ts` — the `ToolDetail` diff arm now carries `{file: FileDiffMetadata,
  notices}`; `toolDetail`/`blobDetail` flow through the adapter; `myersLineOps`, `groupHunks`,
  `splitLines`, `LineOp`, and `diffToFile` (~225 lines) are deleted; `detailHeight`'s diff arm
  uses `toolDiffDetailHeight` (default row = typography's `DIFF_LINE_BASELINE`, the same 21px
  the deleted `DIFF_LINE_HEIGHT` carried); the row-version fingerprint reads
  `file.name`/`cacheKey`/`hunks.length`/`unifiedLineCount`.
- `components/tool-group.tsx` — `ToolDiffBody` mounts the library's non-virtualized React
  `FileDiff` (`fileDiff` + `BaseDiffOptions`: `diffStyle: "unified"`, `overflow: "scroll"`, the
  registered `roboco-dark`/`roboco-light` pair with `themeType` = the resolved appearance —
  `registerRobocoDiffsTheme()` at module scope, idempotent alongside the Changes pane's call).
  The notices ride the header's `renderHeaderMetadata` slot (`.changes-file-notices`, the same
  slot/copy ticket 02 uses — one notice vocabulary across both diff surfaces). `FileBodyUpto` /
  `FilePlaneScroll` / `.diff-body-pad` are gone from the render.
- `styles/app.css` — `.tool-diff-host` maps the web tokens onto the library's documented
  variables exactly like `.changes-code-host` (sans/font families, `--diffs-tab-size: 4`, the
  theme's add/delete roles via the `-override` vars); font size/line height arrive inline from
  the runtime code-size setting. ~450 lines of dead `.diff-*` rules deleted — assert-driven:
  `.diff-file-add`/`.diff-file-del` SURVIVE (the Changes banner chrome still consumes them),
  `.changes-*` untouched, the phone-media diff arms, the reduced-motion fold/chevron arms, and
  the `.diff-view` scrollbar-suppression list entries removed; only two doc comments that name
  what was replaced remain (`.changes-fold-toggle`'s lineage note, the comment-adder's).

**Deletions performed.** `components/diff-view.tsx` (the entire 1,145-line file: `DiffView`,
`useParsedDiff`, `FilePlaneScroll`, `FileBodyUpto`, `DiffScroller`, every row component,
`languageFor`, `DIFF_METRICS`); from `lib/diff.ts` (1,024 → 337 lines): the parser
(`parsePatch` + its helpers), `fileNotices`, `truncateFileLines`, the row model
(`DiffRow`/`bodyRows`/`bodyRowCount`/`bodyHeight`/`bodyHeightWith`/`flattenFiles`/
`estimateRowHeight`/`rowId`), split pairing (`splitPairs`/`splitPairsUpto`/`pairLine`/
`diffLineAnchor`/`pairAnchors`), geometry (`gutterWidth`/`visualColumns`/
`horizontalGeometry`/`unifiedContentWidth`/`splitContentWidth` and every height/width constant
incl. `DIFF_LINE_HEIGHT`/`DIFF_TEXT_SIZE`/`FOLD_TWEEN_MAX_PX`), `commentAdderLeft`/
`splitAdderLeft`, `fileCounts`, and the types `LineKind`/`DiffLine`/`Hunk`/`FileStatus`/`FileDiff`/
`DiffHorizontalGeometry`/`SplitPair`/`DiffRow`. `FileFold` is trimmed to the `{collapsed, epoch}`
pair the library's items consume (the dead tween fields `from`/`to`/`toggledAt`/`folding` died
with the row model); `state/changes-surface.ts` writes the trimmed shape. `changes-page.tsx`
needed zero edits.

**Survivors kept deliberately (lib/diff.ts):** `resolveDiff` + `normalizeCheckoutPath` +
`classifyDiffEmpty`/`diffEmptyMessage` (ticket 01), `diffPhase`, `upsertDiffFrame`, the scope
vocabulary (`DiffScope`/`DIFF_SCOPE_LABELS`/`DIFF_SCOPE_CHIPS`/`scopeMode`/`scopeLabel`/
`cleanMessage`/`defaultBaseRef`), `parseKey` (ticket 02's cache-key fingerprint), and the
surface-state types `DiffMode`/`FileFold`/`DiffDraftAnchor` (the last is ticket 03's annotation
input). `lib/syntax.ts` is untouched — markdown.tsx + code-view.tsx still consume it until
ticket 05.

**Deliberate behavior notes.**

- An `oldText: ""` (empty-string, not null) old side now classifies as `type: "new"` ("New
  file") — the library reads the contents — where the old code marked it "modified". Both read
  as "this file's content starts here"; the notice is the more truthful one.
- Inter-hunk gaps now render the library's native "N unchanged lines" separators (expandable —
  the parse is non-partial, both full contents are in hand, so user story 5's expansion works
  here for free) where the old renderer simply omitted the gaps. More informative,
  library-native, zero wiring.
- The block now shows the library's default file header (path, change icon, +N/−N) with the
  notices in its metadata slot — the old block had no header row. Accepted divergence (ADR 0008)
  and consistent with the Changes pane.
- `toolDiffFileDiff` re-diffs per call (no memo): the old `diffToFile` ran full Myers per call
  too — parity, and the heavy consumers (row rebuilds) hand the result to the height estimator
  and the scroller's measured cache. An LRU on the content hash is a trivial follow-up if
  profiling ever wants it.
- The analytic height estimate is pre-measurement only (mirrors the library's own estimator
  arithmetic, including its 44px header figure); the transcript scroller measures the mounted
  row, so jsdom's lack of layout is not a coverage gap for it.

**Tests.** `tests/tool-diff.test.ts` (new, 14, node env — the pure adapter: 3-context hunks,
new-file/notice mapping, identical-sides null, no-EOF markers, the mid-hunk and across-hunks
caps with the exact notice copy, sliced line arrays, cacheKey identity incl. the cap's effect,
the height arithmetic per shape), `tests/tool-diff-render.test.ts` (new, 3, jsdom mounted — the
real `ToolGroupRow` with a real `ToolGroupMotionStore`: the detail-fold store write opens the
chip, the library's `diffs-container.tool-diff-host` mounts inside `.tool-diff-body`, and the
new-file + truncation notices render through the header slot; the library's FileDiff mounts
cleanly in jsdom, so no adapter-only fallback was needed). `tests/diff.test.ts` trimmed to the
surviving pure logic (16 tests: resolution/normalization, phases, empty-state classification,
scopes, parseKey, frames — the 16 parser/row-model/geometry/split-pair/FilePlaneScroll tests
died with the code). `tests/transcript-model.test.ts`'s tool-detail suite ported onto the
library shape (hunk/line-array assertions, the New-file notice, identical-sides null, the
analytic detail height); `tests/changes-diff.test.ts`'s fold literal follows the trimmed
`FileFold`. Full app suite: **131 files / 1,939 tests green** (baseline 129/1,940 — the
parser/row-model tests died, the adapter + mounted suites more than replaced them in coverage);
`tsc --noEmit` and `pnpm -r build` green; delete-and-grep clean (only comments naming what was
replaced).

**Bundle.** Main bundle: 2,972.04 → 2,967.72 kB raw / 998.57 → 996.37 kB gzip
(**−4.3 kB raw / −2.2 kB gzip**) — deleting the dead renderer shrank it slightly; the library's
`FileDiff` react entry was already on the main bundle via ticket 02's root-barrel import, so
tree-shaking held. On-disk dist: 17,562,418 → 17,552,790 bytes (−9.6 kB).

**Suite-stability note for the merger.** Running the full suite back-to-back on this machine
saturates it and trips PRE-EXISTING timing-sensitive tests — `registry.test.ts`'s reconnect
backoff (±15 ms real-time tolerances) and `pierre-theme.test.ts`'s highlighter render, both
reproduced flaking on the pre-ticket base under the same load (plus an occasional 5 s timeout in
`composer-reasoning.test.ts`). None are touched by this ticket; the suite is green (131/1,939)
on both my branch and the base when the machine isn't saturated — let it settle between runs.
