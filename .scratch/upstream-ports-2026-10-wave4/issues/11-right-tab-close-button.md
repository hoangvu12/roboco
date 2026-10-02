# 11 — Right panel tabs: close button on the right

**What to build:** The ✕ stops replacing the surface icon in the leading
slot: the icon stays put on the left, the title takes the free space, and
the ✕ fades in on hover in a trailing slot at the tab's right edge (same
size, color, hover wash); the unsaved dot moves into that slot and swaps
with the ✕ on hover; chip padding goes symmetric (4px). The trailing close
slot gets a "Close tab" tooltip, a button role and an accessible label,
matching the terminal drawer's "Close terminal".

**Blocked by:** None. (Side-chat tabs shipped in wave 3; nothing further
needed.)

**Status:** ready-for-agent

**Upstream SHAs:** `42926c80` (#587) — 1 file, `crates/ui/src/shell.rs`.
Our tabs live in `crates/ui/src/shell/tabs.rs` (wave-3 split) — carry the
hunk there. Web parity: `web/packages/app/src/state/right-pane.ts` +
tab rendering — verify close-slot placement/labels and extend only if a
gap exists.

**Verification budget:** `cargo check -p roboco-ui -j 3`; targeted nextest
side-chat/tabs tests; web `pnpm -r build` if touched.

- [ ] Close ✕ in trailing slot with tooltip + a11y; unsaved dot swap
- [ ] Symmetric chip padding
- [ ] Tabs tests green; web gap check recorded
- [ ] Port commit records upstream SHA

## Comments
