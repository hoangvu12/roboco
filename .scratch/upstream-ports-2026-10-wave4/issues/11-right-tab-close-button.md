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
hunk there. **Web parity (deliverable):**
`web/packages/app/src/components/right-tab-strip.tsx` still has the exact
upstream bug — its leading slot "swaps its icon for the close ✕ on chip
hover" (line 29). Move the ✕ to a trailing slot with title/aria-label
"Close tab", keep the unsaved-dot swap, symmetric padding.

**Verification budget:** `cargo check -p roboco-ui -j 3`; targeted nextest
side-chat/tabs tests; web `pnpm -r build`.

- [x] Close ✕ in trailing slot with tooltip + a11y; unsaved dot swap
- [x] Symmetric chip padding
- [ ] Tabs tests green (regression test ported; execution deferred to the
      wave-final batched pass — user directive)
- [x] Web right-tab strip gets the same trailing-slot close + labels
- [x] Port commit records upstream SHA

## Comments

- Ported `42926c80` (#587) by intent into `crates/ui/src/shell.rs`
  (`render_right_tab_strip`): chip padding `.pl(4)/.pr(8)` → `.px(4)`;
  the leading 18px slot is now the surface icon only (spinner/favicon/
  file-type icon/harness icon, unchanged selection logic); the title gains
  `.flex_1()` so it takes the free space; a NEW trailing 18px slot carries
  the `right-surface-close` id, `role(Button)` + `aria_label("Close tab")`
  + `text_tooltip("Close tab")` (the terminal drawer's "Close terminal"
  pattern), the press-claiming mouse-down (drag payload), and the two
  opacity-swapped stacked layers — unsaved dot (visible normally, out on
  chip hover) and ✕ (in on chip hover). Byte-identical to upstream's final
  chip region apart from `px(4.0)` literal style.
- Regression test `right_tab_close_sits_at_the_trailing_edge` ported into
  `mod right_tab_mouse_regressions` (close sits after the title center and
  at `tab.right() - 4px`, the symmetric padding).
- Web parity: `web/packages/app/src/components/right-tab-strip.tsx` —
  leading slot icon only, title `flex: 1`, new `.right-tab-trailing` slot
  stacking the dirty dot and the ✕ (opacity-swapped on chip hover), close
  button `aria-label`/`title` "Close tab" (was `Close {title}`);
  `app.css` — symmetric `padding: 0 var(--rb-space-xs)`, `.right-tab-title`
  `flex: 1`, swap rules moved off `.right-tab-icon`, dirty dot becomes a
  stacked `grid-area` layer. No web tests referenced the old DOM shape.
- Exclusions: none — upstream's diff is shell.rs-only; the web fix is
  roboco-side (upstream's web client did not fix it; ticket mandates it).
- Verification: `rustfmt --edition 2024` region check (upstream-final
  region is byte-identical); `pnpm -r build` from web/ after all four
  tickets' web edits (one build per chunk); `cargo check -p roboco -j 3`
  at chunk end; test execution deferred to the wave-final batched pass
  (user directive).
