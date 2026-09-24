# 07 — Edit mode restores web file editing

**What to build:** A web client user can edit workspace files in the viewer again — through the
diff library's edit mode, bridged to the existing file-document machinery. Typing marks the
document dirty and schedules autosave; closing the file accepts the edit session and runs the
final save; a missing completion handler never silently rejects edits. Undo history, selection,
and caret survive leaving and re-entering a file. External-change reconciliation (changed on
disk while dirty) keeps the buffer and shows the existing banner. This ticket is deliberately
last: ADR 0008 suspended web editing until the read-only viewer proved the surface, and the
bridge sketch lives in the research notes under the adoption scratch directory.

**Blocked by:** 05 — Read-only file viewer (the viewer it upgrades).

**Status:** done

- [x] One edit provider is mounted high in the component tree; the viewer becomes editable.
- [x] Edits mark the document dirty and schedule autosave through the unchanged file-document
      machinery (the live change stream feeds it, exactly as the textarea's changes did).
- [x] An edit session ending (tab close, edit disabled) accepts and runs the final save — the
      completion handler is mandatory and always present.
- [x] Undo history, selection, and caret survive leaving and re-entering a file within a session
      (per-file edit state keys).
- [x] External-change reconciliation: a changed-on-disk file while dirty keeps the buffer and
      shows the existing banner; reload flows restore content and edit state.
- [x] Save conflict and deleted-on-disk phases behave exactly as the file-document state machine
      defines.
- [x] Read-only files (truncated, binary, unwritable encoding) never become editable.
- [x] The bridge is covered at the store level: dirty tracking, autosave scheduling, and
      completion-driven final saves are tested against the file-document machinery.

## Comments

**What landed.** Web file editing runs through the library's edit mode, bridged onto the
UNCHANGED FileDocument machinery — `lib/file-document.ts`, `state/file-documents.ts`, and
`lib/files-client.ts` are byte-identical to ticket 05 (their suites pass unmodified). The bridge
is one new module, `lib/file-edit.ts`, and the wiring is four touches:

- `lib/file-edit.ts` (new, the bridge) — exports the whole seam: `createRobocoFileEditor` (the
  shared `EditorFactory`: library defaults + `ownsVerticalViewport: true`, because the viewer's
  editor owns its scroll view — no keymap or feature customization, the seam stays thin);
  `fileEditStateKey(path)` (the per-file key = the document path); `applyFileEditChange(doc,
  event)` (the live change stream → `document.edit`, with a skip-guard for events carrying
  contents the document already holds); `completeFileEdit(doc, event)` (ALWAYS returns
  `'accept'` and runs the final save when `document.canSave()`); and `reconcileFileEditDraft(key,
  buffer)` (a retained draft resumes only when it still matches the mounting document's buffer).
- `components/files/file-viewer.tsx` — `ReadOnlyCodeBody` became `FileCodeBody`: the same
  `Virtualizer` + library `File` host, theme pair, `themeType`, word-wrap `overflow`, fonts, and
  cache-key discipline as ticket 05, plus `edit={snapshot.editable}` (never true for read-only,
  truncated, or binary documents — the phase machine's own `editable` flag is the gate),
  `editStateKey`, `onEditChange`, and the always-present `onEditComplete`. The edit-deferral
  notice (`.files-readonly-note`) is deleted — the surface is a live editor again. Toolbar,
  Mod-S, `SaveStatusPill`, `CloseLifecycleBanner`/`PhaseBanner`, autosave configuration, and the
  markdown/image arms are untouched.
- `components/app-shell.tsx` — one `<EditProvider createEditor={createRobocoFileEditor}>`
  wrapped around `<RightPane>` (the factory is shared context, not per-file; the per-file keys
  live on the surfaces). The phone drawer rides the same provider (one `RightPane` element).
- `lib/file-view.ts` — no functional change (the `FileContents` shape serves both arms); the
  doc comment now documents the cache key's second job as the edit session's file identity:
  edits move the buffer but not the read's hash, so the prop stays "the same file" and the
  session's document remains the source of truth; only a fresh read rotates the hash, landing as
  the library's external document replacement.
- `styles/app.css` — the `.files-readonly-note` rule deleted, replaced by a comment naming what
  replaced it (the library's editor, themed through the same `--diffs-*` variables). No other
  CSS changed; no new literals.

**The bridge's shape (who owns what).** The library owns everything inside the editor
(undo/redo, selection, caret, bracket matching, auto-surround, find-in-file, IME, keymaps) and
retains each keyed session in its own `EditStateManager` LRU across unmount/remount; the
document owns the buffer and every write — `onEditChange` plays exactly the textarea's
`onChange` role (`document.edit`: dirty + autosave scheduling per settings), `onEditComplete`
plays the session-end boundary (accept + the machinery's own capable-save flush, the same call
`prepareClose`'s pending path makes). Close flows are untouched: a dirty tab close still goes
`prepareClose` → "pending" (saving-before-close banner) / "blocked" (Retry/Keep Open/Discard).

**Behavioral notes.**

- The completion handler fires on EVERY session end — including plain tab switches (unmount
  without close) and the loading arm's transient unmount during a reload. It accepts in all of
  them and saves only when `canSave()` — an in-flight write, a reserved phase (conflict,
  externally modified, deleted on disk, read-only, loading), or a clean document all skip the
  flush. With web autosave OFF by default this makes session end the flush point for edits made
  without autosave; with autosave ON it is a no-op on top of the timer.
- The skip-guard in the change stream matters: the library republishes its own
  external-document replacements through `onEditChange`, and re-feeding a synced buffer would
  mark a cleanly reloaded document dirty. The mounted reload test pins the clean outcome.
- A retained draft resumes ONLY when it still matches the mounting document's buffer
  (`reconcileFileEditDraft`, computed before the editor attaches). This covers both divergence
  sources: a clean reload that landed while the tab was hidden (the dormant draft would show
  stale contents over a fresh buffer), and another chat's surface for the same path (the
  registry's per-surface documents have independent buffers). The discarded case loses only the
  stale draft's undo history, never content. Active sessions are never cleared (the manager
  refuses), so the recompute-on-every-text-change is safe.
- `onEditComplete`'s accepted file is NOT re-keyed (the library suggests re-keying when the host
  would re-render with the same external file): our external file prop flows from the document
  snapshot, whose contents equal the accepted contents, so the library's identity comparison
  resolves consistently either way; the post-save hash rotation (a fresh read) drops the
  accepted cache naturally.
- Bundle: the `@pierre/diffs/edit` entry (the editor: commands, find panel, popover manager,
  markers, paste handling) lands on the main bundle now that the factory is a real import —
  it was previously tree-shaken away with editing suspended. Main bundle 2,966.72 → 3,155.73 kB
  raw / 995.42 → 1,055.94 kB gzip (**+189 kB raw / +60.5 kB gzip**); on-disk dist
  17,548,402 → 17,737,242 bytes. The lazy language/wasm chunks are unchanged.

**The dormant editor-comment gap (out of scope, kept intact).** The editor-review-comment
overlays — `components/review-comments/editor-comment-card.tsx` + `editor-comment-draft.tsx`,
the review store's editor-draft methods (`openEditorDraft`/`editEditorComment`/
`setEditorDraftBody`/`cancelEditorDraft`/`commitEditorDraft`/`toggleEditorComment`), and
`lib/review-comments.ts`'s editor overlay geometry — remain unmounted over the library's
editor surface, exactly as ticket 05 left them; nothing was deleted and `review-comments.test.ts`
still covers the store methods and the geometry math. Re-mounting them needs the library's
annotation/gutter affordances over an EDITING session (the anchored line moves as the user
types) — a follow-up of its own, not part of this ticket's bridge.

**Tests.**

- `tests/file-edit.test.ts` (new, 14, node — the ticket's store-level core): the bridge against
  the real `FileDocument` over the fake-caller idiom — dirty tracking, autosave scheduling per
  settings (enabled writes after idle / disabled stays dirty), the no-op guard, read-only never
  editable, completion-driven final saves (capable dirty doc, clean no-write, unseen-contents
  sync, in-flight no-double-write, and the reserved phases accepting without saving), the
  external-change-keeps-buffer + reload flow, the state-key/draft-reconciliation pair (via
  `EditStateManager`'s public API with a real `TextDocument`), and the factory constructing an
  editor of the requested type.
- `tests/file-viewer-edit.test.ts` (new, 7, jsdom mounted — the real `FileSurface` inside a real
  `EditProvider`, the app shell's shape; the editor is driven through its public `applyEdits`
  API, never shadow DOM): session attach + factory-through-context, change stream → dirty →
  completion flush on unmount, autosave-follows-settings end-to-end, truncated documents never
  attach a session, session retention/resume across unmount/remount (the same draft document,
  undo works after re-entry), external change while dirty (buffer + banner + session intact),
  and Reload from Disk (the two-step discard confirm → the fresh read restores contents and a
  continuing session). The suite stubs the editor's canvas 2d metrics (`measureText`) — jsdom
  has no canvas, and without it the library's `Metrics.init` throws and the editor never
  attaches.
- `tests/file-viewer.test.ts` (ticket 05's suite, updated): the deferral-notice assertions
  became absence assertions (the notice is gone), and `mountViewer` now wraps the surface in
  the `EditProvider` — an editable `File` without one throws `File: EditContext is not
  attached` during the ref callback, so the suite must mount the app's real shape.
- `file-document.test.ts` and `files-client.test.ts` pass UNMODIFIED (the machinery is
  untouched). Full app suite: **136 files / 1,987 tests green** (baseline 134/1,966);
  `pnpm -r build` and `tsc --noEmit` green.

**For the merger.** Nothing else pending in the adoption. Two notes for the record: (1) ADR
0008's "Edit mode is beta and deferred; until it lands, web file viewing is read-only" is now
historically superseded by this ticket landing exactly the bridge the ADR sketched — the ADR
was left untouched as a point-in-time record; (2) the editor's canvas-metrics jsdom stub is
local to the new mounted suite (the repo's mounted idiom is self-contained per file), so any
future mounted suite that drives the editor should copy it.
