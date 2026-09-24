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

**Status:** ready-for-agent

- [ ] Opening a file from the tree shows the library's file view: highlighted, line-numbered,
      virtualized, wrapped per the word-wrap setting, themed by the registered Roboco theme.
- [ ] File contents map from the file-document read outcome — filename, contents, and the
      content hash as the highlight cache key (reference-stable across re-renders).
- [ ] Read-only reasons (truncated, binary, unwritable encoding) surface as before; the
      large-file truncation banner is unchanged.
- [ ] External-change banners (changed on disk, save conflict, deleted on disk) keep working
      from the file-document state machine — the read path never shows stale content silently.
- [ ] Markdown preview and image viewers are unchanged.
- [ ] The viewer's chrome states that editing is deferred (per ADR 0008) instead of presenting a
      dead editor.
- [ ] Transcript code blocks render headerless through the library with the registered theme.
- [ ] The textarea-overlay editor and the hand-rolled tokenizer are deleted — no remaining
      consumers anywhere.
- [ ] The existing file-document and files-client suites keep passing; a mounted jsdom test
      drives the viewer with scripted document states (loaded, read-only reason, external
      change).
