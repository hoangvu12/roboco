# 26 — Wallpaper shuffle with preloading, adaptive colours, positioning/zoom

**What to build:** Wallpaper shuffling on the new-thread background:
candidates decode once per preload (the validated image is reused, not
decoded twice), preloaded managed copies queue without leaking orphaned
full-size copies at exit (the first preload of a session retires managed
files other than the active background), adaptive colours follow the
artwork, and motion/glass states align with the existing UI conventions;
US spelling in the new Appearance strings. Appearance gains background
positioning and zoom, with the preload queue keyed on the managed path
(not the whole background setting) so applying a crop or zoom doesn't
flush and re-decode three decoded wallpapers. New settings surfaces:
`settings/wallpaper.rs` + `settings/wallpaper_colors.rs` with Appearance
integration and shortcut wiring; the fixture example gains wallpaper
coverage.

**Blocked by:** None. (New-thread backgrounds + effects shipped with the
#341-era ports: `new_thread_background_{effects,image,mask}.rs` exist.)

**Status:** ready-for-agent

**Upstream SHAs:** `09d04b29` (#598) — 11 files:
`ui/src/settings/wallpaper.rs` (new), `ui/src/settings/wallpaper_colors.rs`
(new), `appearance.rs`, `motion.rs`, `new_thread_background_effects.rs`,
`settings.rs`, `settings/appearance.rs`, `settings/shortcuts.rs`,
`shell.rs`, `theme.rs`, `examples/new-project-fixture.rs`.
`626bccc8` (#660) — 8 files: same surfaces +
`new_thread_background_mask.rs`, with the managed-path preload key and
warm-switch test. **Web parity (deliverable):**
`web/packages/app/src/routes/settings-appearance.tsx` +
`lib/new-thread-background.ts` +
`lib/new-thread-background-effects.ts` + `components/new-thread-background.tsx`
— port the shuffle setting (with preloading through the existing effects
worker/IndexedDB blob store), adaptive colour derivation, and the
positioning/zoom setting applied to the web background renderer.

**Verification budget:** `cargo check -p roboco-ui -j 3`; targeted nextest
appearance/wallpaper/background tests (decode-once, orphan retirement,
warm switch on framing change); `pnpm -r build` if web touched.

- [ ] Shuffle with preloading: decode-once, orphan retirement, adaptive
      colours
- [ ] Positioning + zoom; preload queue keyed on managed path (warm
      switch test)
- [ ] Appearance strings + shortcut wiring; fixture coverage
- [ ] Tests green
- [ ] Web: shuffle + preloading + positioning/zoom in settings-appearance
      and the background renderer
- [ ] Port commit records upstream SHAs

## Comments
