# 04 — Transcript tool-diffs via the library; delete the old diff renderer and patch parser

**What to build:** The transcript's stacked tool-diff blocks (an inline tool call's file changes)
render through the diff library — always expanded, unified layout, no comment affordances, with
the file notices (new file, deleted file, renamed, binary) preserved. With this last consumer of
the old renderer migrated, the hand-rolled virtualized diff viewer, its row components, and the
hand-rolled patch parser / row model are deleted. The diff logic the store consumes —
resolution, phase classification, scope labels, clean messages, frame upsert — remains.

**Blocked by:** 02 — Changes pane renders diffs through the diff library.

**Status:** ready-for-agent

- [ ] The transcript's stacked tool-diff blocks render through the library, always-expanded and
      unified, with the file notices preserved.
- [ ] The old virtualized diff viewer, its row components, and the hand-rolled patch parser and
      row model are deleted — no dead exports remain from the deleted modules.
- [ ] Diff resolution, phase classification, scope labels, clean messages, and frame upsert
      logic remain, and their existing pure-logic suites keep passing.
- [ ] Transcript render-smoke tests are updated for the new rendering path and pass.
- [ ] No other surface regresses (the Changes pane and its comments from tickets 02–03 are
      unaffected).
