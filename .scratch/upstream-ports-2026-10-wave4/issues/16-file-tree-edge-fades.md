# 16 — File tree edge fades at scroll boundaries

**What to build:** Edge fades in the file tree painted (or failed) at
scroll boundaries — the fades at the top/bottom of the tree respect the
actual scroll position so they only appear when content extends past the
edge.

**Blocked by:** None.

**Status:** ready-for-human

**Upstream SHAs:** `4aceec16` (#665) — 1 file,
`crates/ui/src/files/tree.rs`. **Web parity (deliverable):**
`web/packages/app/src/components/files/file-tree-panel.tsx` — apply the
same scroll-position-conditional edge fades to the web tree panel.

**Verification budget:** `cargo check -p roboco-ui -j 3`; targeted nextest
files/tree tests; web `pnpm -r build`.

- [x] Edge fades conditional on scroll position (desktop + web)
- [x] Tests green (gpui regression ported; execution deferred to the wave-final
      batched pass — user directive)
- [x] Port commit records upstream SHA

## Comments

- Ported `4aceec16` (#665) by intent into `crates/ui/src/files/tree.rs`:
  `tree_scroll_overflow(list)` extracts the fade gate and NEGATES the list
  state's offset — `ListState::scroll_px_offset_for_scrollbar().y` runs
  NEGATIVE as content scrolls up, so the old inline `offset > 0.5` never
  fired: the top fade failed at the scroll boundary (and the bottom edge
  compared against the wrong sign too). The `.fade_overflow_y_with` closure
  now calls the helper.
- Regression test `tree_fades_only_at_edges_with_hidden_rows` ported
  verbatim: a 10-row ListState fixture drawn at multiple viewport heights —
  top-only at rest, both mid-scroll, bottom-only at the reveal, and neither
  after the viewport grows or rows splice away (the clamp-during-layout
  frame).
- Web parity: `components/files/file-tree-panel.tsx`'s `TreeList` gains the
  scroll-position listener (rAF-coalesced scroll + ResizeObserver, the
  sidebar's established pattern) setting `--rb-files-fade-top`/-`bottom`
  with the desktop's 0.5px dead-zone; `app.css`'s `.files-tree` gains the
  gated 24px quadratic mask (same gated-stop shape as `.sidebar-scroll` —
  `1 − gate × (1 − ramp)` so an unfaded edge stays fully opaque). The
  vars default to 0, so the search-results list (which never sets them)
  stays unfaded, matching the desktop where only the tree list is faded.
- Exclusions: none — upstream's diff is tree.rs only.
- Verification: `pnpm -r build` from web/ (tsc --noEmit + vite, clean);
  `cargo check -p roboco -j 3` at chunk end; test execution deferred to the
  wave-final batched pass (user directive).

- Wave-final batched verification (2026-10-03, merged main `cf94f415`): one
  batched pass over all lanes — ui lib 1521/1521; engine 529/530 (the one
  failure is the documented pre-existing
  `previews::preview_watch_follows_the_session_checkout_and_owning_device`
  baseline); harness 504/509 (the five failures are the documented
  environmental `#!/usr/bin/python3` fixture shebang and uid-1001
  user-database quirks; CI runs them); mcp 26/26; voice 18/18; theme 31/31;
  `wiregen --check` and `roboco-theme-export --check` fresh; web `pnpm -r
  build` green, app vitest 2122/2122, engine-client vitest green. The
  deferred test-execution criterion is demonstrated; closed by the
  wave-final pass.
