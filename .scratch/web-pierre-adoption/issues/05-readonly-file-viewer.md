# 05 — Read-only file viewer; transcript code blocks; delete the custom editor and tokenizer

**What to build:** A web client user opening a workspace file sees a real code view —
syntax-highlighted with the registered Roboco theme, line-numbered, virtualized, wrapped per the
setting — fed by the existing file-document machinery (filename, contents, content hash as the
highlight cache key). Read-only reasons, large-file truncation, and external-change banners keep
working; markdown preview and images are untouched. The viewer's chrome states plainly that
editing is coming back (ADR 0008 defers it). Transcript code blocks render headerless through
the library. With every consumer migrated, the textarea-overlay editor and the hand-rolled
tokenizer are deleted.

**Blocked by:** 02 — Changes pane renders diffs through the diff library (the registered theme
is reused); 04 — Transcript tool-diffs via the library (the tokenizer's last consumer goes here,
and its old diff-renderer consumer is gone by then).

**Status:** done

- [x] Opening a file from the tree shows the library's file view: highlighted, line-numbered,
      virtualized, wrapped per the word-wrap setting, themed by the registered Roboco theme.
- [x] File contents map from the file-document read outcome — filename, contents, and the
      content hash as the highlight cache key (reference-stable across re-renders).
- [x] Read-only reasons (truncated, binary, unwritable encoding) surface as before; the
      large-file truncation banner is unchanged.
- [x] External-change banners (changed on disk, save conflict, deleted on disk) keep working
      from the file-document state machine — the read path never shows stale content silently.
- [x] Markdown preview and image viewers are unchanged.
- [x] The viewer's chrome states that editing is deferred (per ADR 0008) instead of presenting a
      dead editor.
- [x] Transcript code blocks render headerless through the library with the registered theme.
- [x] The textarea-overlay editor and the hand-rolled tokenizer are deleted — no remaining
      consumers anywhere.
- [x] The existing file-document and files-client suites keep passing; a mounted jsdom test
      drives the viewer with scripted document states (loaded, read-only reason, external
      change).

## Comments

**What landed.** The file viewer's code body is the diffs library's read-only `File`, and every
remaining consumer of the hand-rolled tokenizer is gone (net ≈ −1,100 lines of app source):

- `components/files/file-viewer.tsx` — the editable arm (the `EditorContextMenu` + `CodeView
  editable` mount with its review overlay wiring) and the read-only arm collapse into ONE
  library arm: a `Virtualizer` scroll container (`.files-code-host`) wrapping
  `<File className="files-code-file">` with the registered theme pair, `themeType` from the
  resolved appearance, `overflow` from the word-wrap setting, `stickyHeader: true`, line numbers
  ON (default), and default `VirtualFileMetrics`. A quiet deferral notice (`.files-readonly-note`,
  "Web file editing isn't available yet — this view is read-only.") renders only for documents
  that WOULD have opened in the editor (`snapshot.editable`) — read-only files state their own
  reason, exactly the old branch order. The truncation banner, breadcrumb toolbar,
  `SaveStatusPill`, `PhaseBanner`/`CloseLifecycleBanner`, Mod-S shortcut, autosave configuration,
  and close-lifecycle wiring are unchanged. `registerRobocoDiffsTheme()` is called at module
  scope (idempotent alongside changes-page and tool-group).
- `lib/file-view.ts` (new, the pure adapter) — `documentFileContents(path, snapshot)`: basename
  (header label + language inference; markdown paths infer `markdown` from the extension, the
  language the old tokenizer forced), the buffer as `contents`, and the read's content hash as
  `cacheKey` (undefined when the hash is missing — contents identity then drives re-renders).
- `components/markdown.tsx` — `CodeBlock` keeps its OWN chrome exactly as the surface shows it
  today (`.md-codehead` verbatim fence label, the global fit toggle, copy) and mounts the
  library's headerless `File` (`.md-code-host`) beneath: `disableFileHeader: true` +
  `disableLineNumbers: true` (the ticket requires the file header off; line numbers follow
  — preserving the compact, gutterless transcript-fence shape the old renderer had), `overflow` from the fit toggle, the
  theme pair + `themeType`. The tokenizer's `splitTokenLines`/`highlightCode` calls,
  `CodeLines`/`VeiledCodeLine`/`SyntaxTokenView`, and the `--rb-code-*` inline vars are gone;
  `--diffs-font-size`/`--diffs-line-height` arrive inline from `codeBlockTextSize`/
  `codeBlockLineHeight`.
- `lib/code-fence.ts` (new, the fence adapter) — `codeFenceLanguage(label)`: a fence's info
  string is freeform text while `FileContents.lang` must name a language the shared Shiki
  highlighter can resolve (an unresolvable id rejects the highlighter promise and leaves the
  block blank). The oracle is the library's own extension table (its values are bundled ids;
  its lookup resolves fence-style aliases — js→javascript, bash→zsh, py→python), plus a
  two-entry alias table (shell→zsh, svg→xml) for labels the old tokenizer covered. Everything
  else — prose, unknown labels — degrades to `"text"`, the same fallback the library itself
  uses for unknown extensions.
- `components/transcript.tsx` — `LiveMarkdownRow` stops passing `chunks`/`onChunkEnd` to
  `CodeBlock` (the props are gone); the veil tracker still drives paragraph/heading fades.
- `styles/app.css` — `.files-code-host`/`.files-code-file` (new) map the web tokens onto the
  library's documented `--diffs-*` variables exactly like `.changes-code-host`; native
  scrollbars (the library's design language — the old floating rails and their
  scrollbar-suppression entries died with the hand-rolled viewer). `.md-code-host` (new) does
  the same for fences. Deleted: the `.files-code` family (~120 lines), the `.files-gutter-*`
  comment affordances, `.files-editor-menu`, `.md-pre`/`.md-codeline`/`.md-codeblock-fit`, the
  `.files-code-scroll` suppression entries, and every `.tk-*` rule — replaced by comments
  naming what replaced them; no orphaned rules survive (delete-and-grep verified).

**Deletions performed.** `components/files/code-view.tsx` (the textarea-overlay editor,
366 lines), `lib/syntax.ts` (the hand-rolled tokenizer, 458 lines),
`components/files/editor-context-menu.tsx` (the editable buffer's right-click menu — its only
consumer was the editable arm and it acts on a textarea that no longer exists; 07's library
edit mode brings the library's own editing surface, so it cannot be revived as-is), and
`sliceTokensForVeil` from `lib/veil.ts` (the code-block half of the streaming veil — no
consumer once fences render in shadow DOM).

**Dormant for 07 (kept intact, unchanged).** The save/autosave/conflict machinery —
`lib/file-document.ts` + `state/file-documents.ts` (the read path stays live; nothing was
touched) — the review-comment store's editor-draft methods (`openEditorDraft`/
`editEditorComment`/`setEditorDraftBody`/`cancelEditorDraft`/`commitEditorDraft`/
`toggleEditorComment`), `components/review-comments/editor-comment-card.tsx` +
`editor-comment-draft.tsx`, and `lib/review-comments.ts`'s editor overlay geometry. They go dark
the same interim way the Changes pane's comments did through 02→03: the store methods and
overlay math keep their suite coverage (`review-comments.test.ts`); `file-viewer.tsx` no longer
mounts the wiring. 07 will re-mount all of it over the library's editor surface.

**Deliberate behavior notes.**

- The markdown preview keeps its ONE write affordance, the task-checkbox toggle
  (`markdown-view.tsx` untouched per the ticket): it flows through the still-live
  `FileDocument.edit`/autosave path, which keeps the save machinery exercised and honest
  between now and 07. The CODE arm is what's suspended.
- The streaming veil no longer fades code-block appends (the library renders the body in
  shadow DOM, where per-line fade spans cannot interleave): a streaming fence appears as it
  settles — highlighted — instead of dissolving. Prose fades are unchanged. Accepted
  divergence (ADR 0008).
- The fence body now paints the theme's `--rb-bg` through the library (an opaque panel, the
  same body the tool-diff and Changes hosts paint); the frame's translucent fill tints the
  header band only. Code insets are the library's gap defaults rather than the old 10/12px
  pre padding. Both are the accepted look-and-feel trade of the adoption.
- The mounted file viewer in jsdom needed an `IntersectionObserver` stub beyond the
  base-tooltip idiom's `ResizeObserver`/`matchMedia` set — the library's `Virtualizer`
  constructs one on setup.
- `TextViewer`'s pre-attach snapshot fallback is now `useMemo`-cached — the old fresh-object
  fallback tripped React's "getSnapshot should be cached" warning on every first mount (a
  pre-existing wart the new mounted suite surfaced).

**Tests.** `tests/file-view.test.ts` (new, 5, node — the pure adapter: name/contents/cacheKey
mapping, deep-path basenames, missing hash, pre-load emptiness), `tests/file-viewer.test.ts`
(new, 5, jsdom mounted — the real `FileSurface` over a scripted `WorkspaceFilesClient` double:
the deferral notice + library host mount, host identity surviving an unrelated re-render
(the cache-key discipline), the binary read-only reason, the truncation banner with no
deferral notice, and the deleted-on-disk pill driven from the watch's removed event).
`tests/markdown.test.ts` ported: the 3 `sliceTokensForVeil` tests and the 2 code-selection tests
died with the code; added 3 `codeFenceLanguage` tests and 2 library-path tests (our chrome
around the headerless host + the CSS token mapping — selection is the library's own
shadow-DOM behavior now). `file-document.test.ts`/`files-client.test.ts`/`files.test.ts` pass
unchanged. Full app suite: **134 files / 1,966 tests green** (baseline 132/1,956);
`tsc --noEmit` and `pnpm -r build` green.

**Bundle.** Main bundle: 2,967.72 → 2,966.72 kB raw / 996.37 → 995.42 kB gzip
(**−1.0 kB raw / −0.95 kB gzip**) — deleting the tokenizer shrank it slightly; the library's
`File`/`Virtualizer` react entries were already on the main bundle via ticket 02's root-barrel
import, so tree-shaking held. On-disk dist: 17,552,790 → 17,548,402 bytes (−4.3 kB).

**For the merger / 07.** Files 07 will edit: `components/files/file-viewer.tsx` (the
`ReadOnlyCodeBody` becomes the editable mount — the `Virtualizer` host and options plumbing
stay), `lib/file-view.ts` (the adapter gains the edit-session file shape), and the
editor-comment components + store methods listed above (re-mount the wiring around the
library's editor). `lib/file-document.ts` is untouched and ready for the `onEditChange` →
`edit` bridge.
