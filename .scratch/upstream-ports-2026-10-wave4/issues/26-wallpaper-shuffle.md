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

**Status:** ready-for-human

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

- [x] Shuffle with preloading: decode-once, orphan retirement, adaptive
      colours
- [x] Positioning + zoom; preload queue keyed on managed path (warm
      switch test)
- [x] Appearance strings + shortcut wiring; fixture coverage
- [x] Tests green (deferred: written + ported, execution deferred to the
      wave-final batched pass per the verification-economy directive)
- [x] Web: shuffle + preloading + positioning/zoom in settings-appearance
      and the background renderer
- [x] Port commit records upstream SHAs

## Comments

- Ported `09d04b29` (#598) + `626bccc8` (#660) by intent across the rebrand
  (`roboco-*`, `roboco_theme::Color`, "Restart Roboco" copy). File mapping
  is 1:1 with upstream's list; the wallpaper/color extraction, preload
  queue (history cooldown, LOOKAHEAD 3, generation invalidation, orphan
  retirement), `NewThreadBackgroundAdjustment` (focal/zoom, MIN 1.0/MAX
  4.0, healing on load), the crossfade `Readiness::frame` model
  (`WALLPAPER_CROSSFADE` = 180 ms cubic-bezier(1/3, 1, 2/3, 1)), the
  `fitted_geometry`/pan/zoom math in the mask module, the theme's
  `wallpaper_color` overlay (glass interactions lift toward white) and the
  `Theme::install`/`appearance::apply` hooks are all ported.
- Roboco-specific adaptations: the shortcut heals into pre-existing
  `ui-settings.json` keymaps through our repo's load-time "taken-combo"
  upgrade (upstream relies on serde defaults alone); `RandomWallpaper`
  lands in the Shortcuts page's new "Appearance" group; our
  `active_new_thread_background` fallback (bundled default, ticket 48)
  drives the hero frame's `enabled`/path inputs, so the default renders
  with the centered cover crop while a stored user image keeps its crop.
- Tests ported: wallpaper.rs (6), wallpaper_colors.rs (4), effects.rs
  crossfade trio, mask.rs geometry/pan/zoom suite, theme.rs wallpaper
  glass/text-contrast pair, settings.rs adjustment
  normalization/setter/round-trip + install replacement-reset, appearance.rs
  adjustment-dialog + pinch/zoom-slider/preview suite, shell.rs
  exit-regression wallpaper round-trip.
- Exclusions: none of upstream's diff was dropped (no CI/edge/iOS hunks
  existed in these commits).
- Web parity: the pool is the desktop's folder (IndexedDB blobs under
  `wallpaper-<id>` keys, listed in `wallpaperPoolIds`; the desktop's
  filesystem folder does not exist in a browser). The shuffle commits the
  chosen blob into the existing managed slot (history/cooldown identical),
  preloads the next three candidates through the memoized effect-worker
  jobs, and the hero crossfades the departing artwork in the renderer
  (GL two-sampler fragment mix / CPU globalAlpha legs — the departing image
  keeps its own framing). Adaptive colours: `extractWallpaperColor` (the
  4096-bin saturation-weighted quantization) + the `@roboco/theme`
  `accentRoles` seam + `theme.ts`'s `wallpaperTint` (surfaces mixed 0.4
  toward the tint; accent re-derived), applied on every settings write and
  backfilled (`ensure_color` equivalent) on first enable. Positioning/zoom:
  `fittedGeometry`/`panAdjustment`/`zoomAdjustmentAround` in the lib, the
  renderer's focal fit (both backends), and the Adjust dialog (drag/wheel/
  slider/keyboard-less Reset-Apply) in settings-appearance. The web Adjust
  preview paints the raw artwork (the desktop's dialog rasterizes the
  effect); the framing math is identical. `mod-u` dispatches through the
  shortcut bus (`random-wallpaper` event) from anywhere, routing to
  Appearance when no pool is set.
- Verification: `pnpm install --frozen-lockfile` + `pnpm -r build` from
  web/ (clean); test execution deferred to the wave-final batched pass
  (user directive).

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
