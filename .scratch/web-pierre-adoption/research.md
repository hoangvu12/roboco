# Research: adopting @pierre/diffs (diffs.com) + @pierre/trees (trees.software) for Roboco web

Status: research only — nothing implemented. Probes kept: `.scratch/diff-watch-probe.mjs`,
`.scratch/diff-resolve-probe.mjs`, `.scratch/read-file-probe.mjs` (run against
`target/debug/examples/web_smoke.exe`; see "Live verification" below).

## 1. What the libraries are

| | `@pierre/diffs` | `@pierre/trees` |
|---|---|---|
| Product | diffs.com | trees.software |
| Source | pierrecomputer/pierre (monorepo, Apache-2.0) | same monorepo, Apache-2.0 |
| Version | 1.4.3 (2026-09-16), very active: 1.3.0→1.4.3 in ~7 weeks | **1.0.0-beta.6 (2026-07-25), still beta** — "expect refinements and small API changes" |
| Peer deps | react/react-dom ^18.3.1 \|\| ^19.0.0 — our React 19.2 is fine | same |
| Runtime deps | shiki ^3\|\|^4, diff 9, @pierre/theme, @pierre/theming, hast-util-to-html, lru_map | none listed |
| Entries | root (vanilla), `/react`, `/edit`, `/ssr`, `/worker` | root, `/react`, `/ssr`, `/web-components` |
| Rendering | Shadow DOM + CSS Grid, string-rendering-first ("browsers are efficient at raw HTML") | Shadow DOM (custom element), path-first model, always-virtualized |
| Vendor | Pierre Computer Company — Jacob Thornton (Bootstrap co-creator, CEO), Ian Ownbey, Mark Otto (CPO); YC-backed, ~22 people, SF | same |

Adoption signals (web research):
- A real migration precedent: zvadaadam/deus-machine PR #64 replaced ~1,200 LOC of custom diff
  rendering with `@pierre/diffs` (Feb 2026).
- `clemg/pierre-github` — a Chrome/Firefox extension that swaps GitHub's own diff + file-tree UI
  for these two libraries; proves they run standalone, not just inside Pierre.
- Syntax.fm #1008 (Alex Sexton, Amadeus DeMarzi): claims Claude, Codex, and Cursor are adopting
  Pierre's diffs library; the episode is all about the perf work (virtualization, progressive
  rendering) behind it. Alex Sexton (SlexAxton) is the trees maintainer and is actively triaging
  trees issues on GitHub.

Both are designed as a pair ("Pairs nicely with @pierre/trees for AUI style experiences") and
share a theming layer (`@pierre/theme`/`@pierre/theming`).

## 2. Diagnosis of the three current pain points (root causes, verified)

### 2.1 "Reading files doesn't work + we're building a custom editor (wtf)"

- **The read pipeline works.** Live probe against a fresh smoke engine: `ReadWorkspaceFile`
  returns correct text/size/hash/encoding, `ListWorkspaceDirectory` returns entries. All 54
  files-related unit tests pass (`files-client`, `file-document`, `files`, `file-icons`).
  If reading is broken in the app, the suspect is the **UI target resolution**, not the engine
  path: the original parity research flagged the web Files surface as targeting a *space*
  (`requestedSpace ?? owned[0]`) instead of the active chat's checkout
  (`.scratch/web-client/research/09-files-tree-editor.md` §5, "Files right-pane target is Space,
  not Chat — WRONG BEHAVIOR"). Chat-target reads additionally require the chat to have a space
  whose folder is a git checkout of the cwd (`workspace_files.rs::resolve_target`).
- **The custom editor is deliberate, not an accident** — the web client was built as a
  desktop-parity port of the GPUI app. `code-view.tsx` header: "Editing rides a transparent
  `<textarea>` laid over the highlight layer (the research's sanctioned shape — no
  CodeMirror/Monaco)". The original research note itself said real highlighting "would need a
  code-editor component (CodeMirror/Monaco) to match" — i.e. the sanctioned shape deferred the
  problem, and a hand-rolled tokenizer (`lib/syntax.ts`, keyword sets + special-cased
  JSON/YAML/HTML) grew in instead. No virtualization in the viewer; large files are guarded by
  truncation banners. This is the "wtf" the user is reacting to, and it's the weakest hand-rolled
  surface: ~1,000-line `code-view.tsx` + `lib/syntax.ts` + `lib/file-document.ts` plumbing.

### 2.2 "The diff just doesn't load at all"

- **The pipeline is complete and unit-tested** (61 tests pass: `diff.test.ts`,
  `changes-surface.test.ts`, `file-tree.test.ts`). Live probe: `WatchCheckoutDiffs` emits `[]`
  then the full frame (correct patch, +3/−1) immediately after a chat's checkout is tracked;
  `GetCheckoutDiff` (working tree) returns the patch. No stubs, no dead code.
- **The bug is that "no diff frame" renders as an eternal spinner with no error.**
  `ChangesBody` shows `Preparing diff…` whenever `resolveDiff` finds no frame — there is no
  empty state, no timeout, no hint. `resolveDiff` finds no frame when:
  1. **The chat's cwd isn't a git checkout.** The engine's `resolve_identity` returns None for
     plain folders, so the checkout is never tracked. **The web smoke server seeds exactly this**
     (a tempdir, not a repo) — verifying the Changes pane against the smoke engine = diff never
     loads, forever, silently.
  2. **The chat is homed on another device.** `diff_sync.rs:345`
     (`if chat.device_id != inner.device_id { continue; }`) — the engine only tracks *its own*
     device's chats. A fleet/remote-device chat shows `Preparing diff…` forever on this engine.
  3. **(Fallback path) Windows path-format mismatch.** Diff frames carry verbatim
     `\\?\C:\...` cwds while chat rows carry plain paths; `resolveDiff`'s `d.cwd === chat.cwd`
     comparisons fail when `chat.checkoutId` is null. Verified live: frame cwd
     `\\?\C:\Users\...\probe-repo` vs chat cwd `C:/Users/.../probe-repo`. The checkoutId-first
     branch saves the normal case (chat rows get `checkoutId` stamped by the engine), so this
     bites only when checkoutId is missing.
- Fix directions (independent of adoption): a real empty state for non-checkout/remote-device
  chats; cwd normalization (`\\?\` stripping) in `resolveDiff`; if remote-device diffs matter,
  engine-side forwarding.

### 2.3 "File tree: weird left border stacking per nesting level"

- **Exact CSS bug, confirmed in `app.css`.** Rows are static-positioned; the guide spans are
  absolutely positioned:
  - `.files-row` (app.css:6990) — `display:flex; height:27px;` — **no `position`**.
  - `.files-row-guides` (app.css:16267) — `position:absolute; top:0; bottom:0; left:0` per row.
  - Nearest positioned ancestor is `.files-pane-column` (`position:relative`, app.css:16225).
  Consequences: (a) `top:0;bottom:0` stretches each guide span the **full pane height** (through
  the search toolbar), not the 27px row; (b) `left:0` anchors rails at pane-x 0/14/28…, not the
  row; (c) the guides' containing block sits *above* the tree's scroll container, so rails float
  fixed while rows scroll; (d) every row of depth ≥ k paints a span at the same x, so the
  55%-alpha `border-left`s **overlap exactly and stack — the rail darkens as nesting deepens**.
  That is precisely "stacking up for each level and it looks weird asf".
- The desktop reference (`crates/ui/src/files/tree.rs::with_indent_guides`) wraps each row in a
  `relative` div and draws 1px hairlines at `8 + 7 + level*14` — the web port lost both the
  relative wrapper and the x-offset, and used 14px border-left boxes instead of 1px lines.
- Minimal fix (if not adopting trees): `position: relative` on `.files-row` (+ optionally
  1px-wide guides at the desktop offsets). Adopting `@pierre/trees` eliminates the bug class.

## 3. What adoption would replace

| Surface today | Files/LOC | @pierre replacement |
|---|---|---|
| `components/files/code-view.tsx` (textarea-over-highlight editor) | ~1,000 | `<File>` + `EditProvider` edit mode (undo history, find-in-file, bracket matching, auto-surround, markers, ghost-text prediction hook) |
| `lib/syntax.ts` (hand-rolled tokenizer, shared by viewer + diff + transcript) | ~? | Shiki via the shared highlighter (worker pool optional) |
| `components/diff-view.tsx` (custom virtualized diff viewer, fold tweens, comment rows) | 1,145 | `<CodeView>` (mixed virtualized file+diff list w/ sticky headers, scrollTo) or `<FileDiff>`×N in `<Virtualizer>` |
| `lib/diff.ts` (parsePatch port, splitPairs, gutters…) | 1,024 | `parsePatchFiles()` + `FileDiffMetadata`; `loadDiffFiles` hydration → expand-unchanged via existing `ReadWorkspaceFile` |
| Review comments interleaving (ticket 23 adder/cards/drafts) | ~ | `DiffLineAnnotation` + `renderAnnotation` + `onGutterUtilityClick` (the "+" adder is a built-in) |
| `components/files/file-tree-panel.tsx` + `lib/file-tree.ts` + search-tree model | ~1,100 | `useFileTree` + `<FileTree model>` (virtualized, search 3 modes, keyboard nav, a11y ARIA tree) |
| `lib/file-icons.ts` + VS Code icon manifest port | ~? | built-in icon sets (`minimal`/`standard`/`complete`, VS Code-derived) + `byFileName`/`byFileExtension` remaps + sprite sheet |
| Git-status coloring/ignored dimming in the tree | ~ | `gitStatus` option + `setGitStatus` (added/modified/deleted/ignored/renamed/untracked) — maps 1:1 to `WATCH_WORKSPACE_GIT_STATUS` |

The `FileDocument` save/autosave/conflict machinery (hash-guarded `WriteWorkspaceFile`) is
engine-side value that stays; edit mode's `onEditChange`/`onEditComplete` plug into it.
The `ChangesStore` (watch/retry/scoped captures) also stays — it feeds
`parsePatchFiles(activeDiff.patch)`.

## 4. Integration seams & caveats

**diffs**
- Theme: components render in Shadow DOM; Roboco's `app.css` cannot style them. Theming =
  `--diffs-*` CSS vars (font/size/line-height/tab-size, add/del color overrides, selection) +
  a registered custom Shiki theme (`registerCustomTheme`) generated from our `--rb-syntax-*`
  tokens, or use `pierre-dark`/`pierre-light` with `theme: {dark, light}` auto-switching.
- Perf: `<Virtualizer>` + `WorkerPoolContextProvider` (off-main-thread Shiki); `preloadHighlighter`
  to warm langs; `cacheKey` (we have content hashes already) for highlight caching.
- `unsafeCSS` escape hatch has **no backward-compat guarantee across versions**.
- Edit mode is still labeled **Beta** on diffs.com. CodeMirror 6 remains the mature alternative
  for the editor specifically — but Pierre edit mode keeps one theming/rendering stack across
  viewer + editor + diff, which is the cohesive play.
- Bundle: 7.4MB unpacked (Shiki langs/themes; on-demand loading mitigates), deps add shiki/diff/
  @pierre/theme/theming. The engine serves the web dist in release artifacts — measure impact
  in a spike before committing.

**trees**
- **Beta, slower cadence** (beta.6 = Jul 25; diffs shipped 8 releases since). Known open bugs:
  #941 deeply-nested single-folder paths render badly (fix PR referenced), #635 flattened
  folders ignore gitignore dimming (only if `flattenEmptyDirectories` — we wouldn't enable it),
  #498 per-item interactive row content not yet supported (maintainer is rewriting the core for
  it). We don't need interactive row content today; visual-only `renderRowDecoration` covers
  badges.
- Styling: shadow root; `--trees-*` vars + `themeToTreeStyles` (maps a VS Code/Shiki-style theme
  object) + density presets for the 27px rows; host element takes our panel CSS.
- **Lazy loading is the main integration question.** Trees' happy path is `paths`/
  `preparedInput` up front (shaped/sorted outside the UI). Our tree is lazy-paged per directory
  (`ListWorkspaceDirectory` + watch). Trees has a mutation vocabulary (add/remove events,
  `resetPaths`) so incremental child loading is possible, but it's the less-trodden path —
  needs a spike. Alternative: eager recursive listing on expand for small repos, prepared input
  for big ones.
- Drag-out to composer (file mentions): trees has internal `dragAndDrop`; drag-out to a custom
  drop target needs custom wiring (current web tree has no drag either — parity gap already).

**Strategic**
- The whole web client is a desktop-parity port; Pierre components bring their own (excellent)
  look-and-feel. Adopting them trades pixel parity for leverage — a product decision. The
  original research notes already sanctioned deviations where the web idiom is better
  (e.g. native `<select>` base picker).
- Vendor risk is single-company, but Apache-2.0 + active external adoption + the company's core
  product depends on these libs. Forking is a viable exit.

## 5. Recommended sequence (when we implement)

1. **Spike @pierre/diffs for the Changes pane** (highest value/lowest risk — stable, replaces
   the most hand-rolled code, feeds from the existing `patch` string): `parsePatchFiles` →
   `<FileDiff>`/`<PatchDiff>` in `<Virtualizer>`, annotations for ticket-23 comments.
2. **Spike `<File>` for the file viewer** with a registered Roboco Shiki theme; keep
   `FileDocument` for saves. Decide edit mode (beta) vs read-only-`<File>`+CodeMirror.
3. **Spike @pierre/trees last** (beta; lazy-load integration question) — or fix the 2-property
   CSS bug now and adopt trees when it hits 1.0.
4. Independently of adoption: fix the diff empty states (non-checkout/remote-device chats) and
   the `\\?\` cwd normalization — those are real bugs today.

## Sources

- diffs.com (homepage + /docs + /llms-full.txt), trees.software (homepage + /docs + /llms-full.txt)
- npm: @pierre/diffs, @pierre/trees (versions, peer deps, licenses, dist sizes, release dates)
- github.com/pierrecomputer/pierre (README, packages/trees, packages/diffs, issues #941/#635/#670/#498/#691)
- zvadaadam/deus-machine PR #64 (custom-diff → @pierre/diffs migration, ~1,200 LOC removed)
- clemg/pierre-github (extension using both libs on github.com)
- syntax.fm #1008 (Pierre Computer on perf; AI-tool adoption claims)
- YC/LinkedIn/Pierre Computer pages (company background)
- Repo: `.scratch/web-client/research/07-changes.md`, `09-files-tree-editor.md`,
  `web/packages/app/src/**`, `crates/engine/src/{diff_sync,rpc,workspace_files,listener}.rs`

---

## 6. Bundle measurements (2026-09-24, throwaway esbuild test — `entry.tsx` importing `File` + `FileDiff` + `parsePatchFiles`)

| | minified | gzipped |
|---|---|---|
| Current app main bundle (`dist/assets/main-*.js`) | 2.16 MB | 765 KB |
| Pierre test bundle incl. React | 570 KB | 160 KB |
| **Marginal main-bundle cost (React already shipped)** | **~430 KB** | **~120 KB (+16%)** |

- Language chunks are lazy (`import()`): ~12 MB of on-disk chunks, one chunk
  (~100–800 KB raw) fetched per language on first view, cached after.
  The 622 KB wasm chunk only loads if `preferredHighlighter: 'shiki-wasm'`
  (default is the JS regex engine — not loaded).
- Dist size caveat: Vite emits every lazy chunk into the dist (~+12 MB files on
  disk) even though they're fetched on demand. If that matters for the engine's
  embedded web assets, restrict languages via the highlighter `langs` option /
  `registerCustomLanguage` — needs a spike to confirm tree-shaking behavior.

## 7. Edit mode integration difficulty (assessment)

Small wiring, one careful bridge:

- Mount one `EditProvider` high in the tree; `<File edit editStateKey={path}
  editorOptions onEditChange onEditComplete />`. Each open file gets an
  independent editor (undo history, selections, caret survive virtualization and
  re-entry via the edit state key).
- The bridge is `FileDocument` (existing save machinery stays): `onEditChange`
  carries the live file → mark dirty + schedule autosave (same role the
  textarea's onChange plays today). `onEditComplete` is the accept/reject
  boundary (fires when editing ends — unmount/disable edit; scrolling
  out-of-view only *suspends* a session) → return `'accept'` and run the final
  save. **A missing completion handler rejects edits** — must handle it.
- Hard cases: (a) external-change reconciliation mid-session (disk changed
  while dirty: replace `file` prop / remount with the edit-state key, keep
  banners from `FileDocument`'s state machine — needs the most care);
  (b) read-only/truncated files: just don't pass `edit` (read-only `File`
  handles those fine); (c) theme: register a Roboco Shiki theme once, shared
  with the diff view; (d) fonts/wrap: `--diffs-*` CSS vars ← editor settings.
- We get for free (vs hand-rolled): undo history, find-in-file, bracket
  matching, auto-indent, auto-surround, comment toggling, markers, keymaps,
  optional ghost-text edit prediction. We delete `code-view.tsx` (~1,000 lines)
  and most of `lib/syntax.ts` (keep or replace its transcript usage).
- Risk: edit mode is labeled **Beta**; pin the exact version, expect API shifts
  (the changelogs show active edit-mode fixes, e.g. newline-insert and IME
  composition fixes in 1.3.6).

---

## 8. Decisions (2026-09-24, grill session) — recorded in docs/adr/0008-web-pierre-diffs-trees.md

1. **Adopt both libraries.** diffs at stable (pin exact version); trees at 1.0.0-beta.6 (pin, thin seam).
2. **Trees: take the beta now** — do not wait for 1.0.
3. **Edit mode: deferred.** Ship the read-only `<File>` viewer; file editing on web is suspended
   until a follow-up ticket adopts `EditProvider` (bridge: `onEditChange` → `FileDocument`).
   The hand-rolled editor is deleted, not kept in parallel.
4. **Parity mandate ends for editor/diff/tree** (web becomes its own client on these surfaces);
   parity remains the rule elsewhere. Recorded as ADR 0008.
5. **Bundle cost accepted** (~+120 KB gzipped main + lazy language chunks in the dist).
