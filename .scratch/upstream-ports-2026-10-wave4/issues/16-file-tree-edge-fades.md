# 16 — File tree edge fades at scroll boundaries

**What to build:** Edge fades in the file tree painted (or failed) at
scroll boundaries — the fades at the top/bottom of the tree respect the
actual scroll position so they only appear when content extends past the
edge.

**Blocked by:** None.

**Status:** ready-for-agent

**Upstream SHAs:** `4aceec16` (#665) — 1 file,
`crates/ui/src/files/tree.rs`. Web parity: `files/file-tree-panel.tsx`
uses the shared edge-fade logic — verify and extend only if a gap exists.

**Verification budget:** `cargo check -p roboco-ui -j 3`; targeted nextest
files/tree tests.

- [ ] Edge fades conditional on scroll position
- [ ] Tests green; web gap check recorded
- [ ] Port commit records upstream SHA

## Comments
