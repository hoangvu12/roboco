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

**Status:** ready-for-agent

- [ ] One edit provider is mounted high in the component tree; the viewer becomes editable.
- [ ] Edits mark the document dirty and schedule autosave through the unchanged file-document
      machinery (the live change stream feeds it, exactly as the textarea's changes did).
- [ ] An edit session ending (tab close, edit disabled) accepts and runs the final save — the
      completion handler is mandatory and always present.
- [ ] Undo history, selection, and caret survive leaving and re-entering a file within a session
      (per-file edit state keys).
- [ ] External-change reconciliation: a changed-on-disk file while dirty keeps the buffer and
      shows the existing banner; reload flows restore content and edit state.
- [ ] Save conflict and deleted-on-disk phases behave exactly as the file-document state machine
      defines.
- [ ] Read-only files (truncated, binary, unwritable encoding) never become editable.
- [ ] The bridge is covered at the store level: dirty tracking, autosave scheduling, and
      completion-driven final saves are tested against the file-document machinery.
