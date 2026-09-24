# Spec: Adopt @pierre/diffs and @pierre/trees for the web client's code, diff, and tree surfaces

Status: ready-for-agent
Research: `.scratch/web-pierre-adoption/research.md`
Decision record: `docs/adr/0008-web-pierre-diffs-trees.md`

## Problem Statement

A web client user opening a file gets a bare text viewer with a hand-rolled approximated syntax
tokenizer, no virtualization, and an editing layer built from a transparent textarea overlaid on
colored text. The Changes pane frequently never renders anything at all: when the chat's checkout
isn't a git checkout, or the chat is hosted on another device, the pane shows a "Preparing diff…"
spinner forever with no error and no explanation. The file tree draws indentation guides that
stretch the full pane height and stack darker with every nesting level, so deeply nested folders
look broken. And the maintainer is hand-maintaining a custom editor, a custom virtualized diff
viewer with its own patch parser, and a custom file tree with a ported icon manifest — three
surfaces the ecosystem already solves well.

## Solution

Replace the three hand-rolled surfaces with the open-source Pierre libraries: `@pierre/diffs`
(Shiki-based code and diff rendering, virtualized, React components) for the Changes pane and the
read-only file viewer, and `@pierre/trees` (path-first, virtualized file tree) for the file tree.
The engine, the RPC surface, and the existing stores (diff watch, file documents, workspace file
watches) are untouched — the adoption happens entirely in the web client's rendering layer. The
Changes pane additionally learns to tell the truth when it has nothing to show: an explicit state
for "this chat's checkout isn't a git repository", "this chat's diffs live on its own device", and
genuine loading. File viewing on the web becomes read-only for now; editing returns in a later
ticket through the library's edit mode. The hand-rolled implementations are deleted, not kept in
parallel. Visual parity with the desktop client ends for these three surfaces (ADR 0008).

## User Stories

1. As a web client user, I want the Changes pane to tell me why it is empty — the checkout isn't a
   git repository, the chat's diffs live on its own device, or it is still loading — so that I am
   never stuck staring at a spinner that never ends.
2. As a web client user, I want diffs rendered with real syntax highlighting, so that changes read
   as code, not plain text.
3. As a web client user, I want to switch between unified and split diff layouts, so that I can
   review the way I prefer.
4. As a web client user, I want word-level inline highlighting inside changed lines, so that I can
   spot the exact edit within a rewritten line.
5. As a web client user, I want to expand collapsed unchanged context around a hunk, so that I can
   see the surrounding code without fetching anything extra.
6. As a web client user, I want to fold and unfold individual files and collapse all files, so
   that large diffs stay navigable.
7. As a web client user, I want a wrap-long-lines toggle, so that unwrapped long lines remain
   readable.
8. As a web client user, I want line numbers on both sides of the diff, so that I can reference
   lines in review comments.
9. As a web client user, I want to add a review comment from a diff line, so that I can request a
   change exactly where it applies.
10. As a web client user, I want staged review comments and the open draft to render inline at
    their anchored lines, so that the review reads in context.
11. As a web client user, I want to edit and remove staged review comments, so that the review
    stays accurate.
12. As a web client user, I want the scope selector (working tree, branch changes, latest turn),
    the base picker, and commit-pinned tabs to keep working exactly as before, so that the
    adoption doesn't change how I switch scopes.
13. As a web client user, I want the scope banner with the file count, +N/−N totals, and the
    partial-snapshot chip, so that I know what I'm looking at.
14. As a web client user, I want large diffs to scroll smoothly, so that the pane doesn't lock up
    on big checkouts.
15. As a web client user, I want the diff and code to follow my selected theme family and
    appearance, so that the surfaces don't look pasted in from another app.
16. As a web client user, I want to open a file from the tree and see it highlighted like a code
    editor, so that reading workspace files is comfortable.
17. As a web client user, I want line numbers in the file viewer, so that I can reference lines in
    conversation.
18. As a web client user, I want large files to open quickly and scroll smoothly, so that
    generated and vendored files remain usable.
19. As a web client user, I want read-only reasons surfaced clearly (truncated, binary, unwritable
    encoding), so that I understand why a file can't be edited.
20. As a web client user, I want a clear indication that web file editing is not yet available,
    rather than a silently dead editor, so that I'm not confused by its absence.
21. As a web client user, I want external file changes to still surface the existing banners, so
    that I'm never looking at stale content.
22. As a web client user, I want Markdown files and images to keep their existing viewers, so
    that non-code files are unaffected by the adoption.
23. As a web client user, I want the file tree to indent cleanly with per-row guides, so that deep
    nesting doesn't look broken or darken with depth.
24. As a web client user, I want folders to load lazily as I expand them, so that large checkouts
    open instantly.
25. As a web client user, I want pagination ("load more") on huge directories, so that listing a
    massive folder doesn't stall.
26. As a web client user, I want git status coloring in the tree (added, modified, deleted,
    renamed, untracked), so that I can see the checkout's state at a glance.
27. As a web client user, I want ignored and hidden files dimmed and the show-all toggle to keep
    working, so that the tree respects my filter.
28. As a web client user, I want file-type icons, so that files are visually distinguishable.
29. As a web client user, I want to search the tree by name, so that I can jump to a file without
    clicking through folders.
30. As a web client user, I want to drive the tree with the keyboard, so that I can navigate
    without a mouse.
31. As a web client user, I want the tree to update live as files change on the engine, so that it
    stays current during a harness run.
32. As a maintainer, I want the library versions pinned exactly, so that beta churn can't change
    behavior between builds.
33. As a maintainer, I want the registered code theme generated from the same source that feeds
    the theme artifact freshness gate, so that the gate keeps proving the theme is fresh.
34. As a maintainer, I want the hand-rolled editor, tokenizer, diff renderer, patch parser, and
    tree implementation deleted, so that there is one implementation to maintain, not two.
35. As a maintainer, I want the CI gates (wire freshness, theme artifact, web builds, engine-client
    suites) to stay green, so that the adoption doesn't regress the build.
36. As a maintainer, I want the wire and the engine untouched, so that paired desktop and older
    web clients keep working unchanged.

## Implementation Decisions

- **Libraries and versions.** `@pierre/diffs` from its stable line and `@pierre/trees` from its
  1.0.0-beta line, both pinned to exact versions (trees beta accepted deliberately — ADR 0008).
  Upgrades are deliberate bumps that re-run the affected suites, never floating ranges.
- **Changes pane keeps its data layer.** The diff store (watch stream, scoped captures, retry,
  branch list) is untouched. A thin adapter turns the resolved diff's patch string into the
  library's parsed diff metadata — parsed once per (checkout, checksum), memoized — and hands the
  per-file metadata to the library's mixed virtualized code/diff list component mounted inside the
  existing Changes surface chrome. Scope toolbar, base picker, split/wrap toggles, and fold state
  keep living in the surface store and map onto library options.
- **Review comments as annotations.** Staged comments and the open draft map to the library's diff
  line annotations with custom annotation rendering; the per-line "+" adder uses the library's
  built-in gutter utility affordance. Anchors keep the (path, side, line) semantics the review
  comment store already uses, including pre-rename paths.
- **Empty states replace the eternal spinner.** When no diff resolves for a chat, the pane
  classifies why: the chat's checkout isn't a git repository (or the chat has no checkout folder),
  the chat is hosted on another device (diffs for a chat are tracked only by that chat's own
  device's engine), or the watch hasn't delivered yet. Each renders as a distinct message, not a
  spinner. Additionally, checkout folder matching normalizes Windows verbatim path prefixes before
  comparing a diff frame's folder to a chat's folder, so the fallback match works when the chat row
  lacks a checkout id.
- **Read-only file viewer.** The file document machinery (bounded reads, content-hash identity,
  external-change banners, read-only reasons) stays and feeds the library's file component:
  filename, contents, and the content hash as the highlight cache key. Editing is off; the
  textarea-overlay editor and the hand-rolled tokenizer are deleted. The viewer states the
  edit-mode deferral plainly in its chrome. Markdown and image viewers are unchanged.
- **Transcript code blocks go through the same library.** Code blocks in the transcript render via
  the library's headerless file rendering, so the hand-rolled tokenizer has no remaining consumers
  and can be deleted outright; if a transcript block turns out impractical, the ticket may retain
  a minimal tokenizer for that surface alone, but the default is library everywhere.
- **File tree on the trees library.** The library's React model and component replace the
  hand-rolled tree panel, tree model, search-tree model, and ported icon manifest. Lazy loading:
  expanding a folder issues the existing directory-listing call and feeds the returned page into
  the model through its mutation API; the workspace watch drives resets and updates; directory
  pagination surfaces through the model's row-decoration or a plain trailing row. Git status from
  the existing checkout git-status watch maps onto the model's built-in status lane; ignored
  dimming and the show-all toggle are preserved. Icons come from a built-in set with targeted
  per-name/per-extension remaps. Search uses the library's built-in search surface.
- **Theme from one source.** One Roboco code theme is registered with the diff library, generated
  from the same compiled theme-variant source that feeds the web client's theme tokens — not
  hand-copied — so the theme artifact freshness gate remains authoritative for it. The libraries'
  CSS variables map from web tokens for fonts, sizes, line heights, and diff add/delete colors;
  appearance switching follows the resolved appearance. The libraries render in shadow DOM: the
  global stylesheet does not reach inside, and unsafe CSS is reserved for narrow cases with
  awareness that it carries no version-compatibility guarantee.
- **Bundle.** Accepted as measured: roughly +120 KB gzipped on the main bundle, with syntax
  language files loading lazily per language. Restricting the language set to shrink the on-disk
  dist is a possible later spike, out of scope here.
- **No wire changes.** No new or changed RPC methods, no proto changes, no engine changes. Older
  engines and paired desktop clients are unaffected.

## Testing Decisions

A good test here asserts external behavior only — our wrappers, adapters, and store snapshots —
and never reaches inside the libraries' shadow DOM, which is tested upstream.

- **Mounted seam (highest, one per surface).** The Changes body, the read-only file viewer, and
  the file tree mount in jsdom with scripted store fixtures, following the repo's existing mounted
  idiom (per-file jsdom environment, React root, stubs for matchMedia/ResizeObserver). Assertions
  cover: the library host elements mount, our chrome renders around them, and store writes drive
  the components (scope switch, fold toggle, git-status update, document load).
- **Pure adapter seams (the only new seams, one thin module each).** Patch-string → parsed diff
  metadata (+ fold/version state); theme-variant source → registered code theme;
  git-status frame → tree status entries; file-document state → file contents + read-only
  reason; directory page → tree mutation batch. Each is a pure-function suite like the existing
  diff/files/tree model suites.
- **Existing store/logic seam.** The diff resolution/phase logic gains the path normalization and
  the empty-state classification, tested as pure functions; the diff store's behavior against a
  scripted fake caller follows the engine-client's fake-server pattern.
- **Untouched.** The engine's Rust integration tests and the engine-client conformance suites —
  the wire doesn't change, so they must stay green as-is. The web build, wire-freshness, and theme
  artifact CI gates must pass unchanged.

Prior art in the repo: the render-smoke suite for the Changes surface, the mounted jsdom popover
suites, the pure-logic suites for the diff/files/tree models, and the engine-client scripted fake
server.

## Out of Scope

- **File editing / edit mode.** Deferred to its own follow-up ticket (the `EditProvider` → file
  document bridge sketched in the research doc). Web file editing is suspended until then.
- **Remote-device diff forwarding.** Only the classification lands; making an engine serve diffs
  for chats hosted on other devices is engine work, not adopted here.
- **Tree drag-out to the composer** (file mentions) — a parity gap that predates this feature.
- **Restricting the syntax language set** to shrink the on-disk dist — possible spike later.
- **Desktop client changes** of any kind; **restoring pixel parity** with the desktop on these
  surfaces (divergence accepted, ADR 0008).
- **A CodeMirror fallback** for the viewer — the read-only library component is the viewer.
- **Performance tuning beyond library defaults** (worker pool, preloading) — wire later if
  profiling justifies it.

## Further Notes

- Upstream trees bugs to watch while on the beta: deeply nested single-folder chains render poorly
  (upstream issue, fix in flight), and flattened folders ignore gitignore dimming (only if
  flattening were enabled — it isn't). Per-item interactive row content is not supported yet and
  is not needed by this feature.
- The browser smoke harness seeds a chat whose folder is a plain tempdir (not a git checkout), so
  end-to-end diff rendering cannot be exercised there — the mounted tests plus the engine-level
  probes kept in the research scratch cover it, and the new empty states make that harness's
  behavior legible ("checkout isn't a git repository") instead of a forever-spinner.
- The `\\?\`-verbatim folder prefix on Windows was observed live in diff frames; the chat row
  normally carries a stamped checkout id that matches first, so normalization only rescues the
  fallback path — it is nonetheless a real correctness fix.
- Ticket split (blockers-first): (1) empty states + path normalization — independent; (2) diff
  library in the Changes pane (theme registration lands here, reused after); (3) read-only file
  viewer (deletes the custom editor; suspended editing per ADR); (4) trees library file tree;
  (5) edit mode, blocked by (3).
