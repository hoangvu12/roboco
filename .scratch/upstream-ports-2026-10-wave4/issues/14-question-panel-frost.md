# 14 — Question panel: frost it like the composer pill

**What to build:** The question panel stood in for the composer pill but
was the only input surface painting a translucent fill without the
backdrop blur, so the transcript showed through unblurred. Wrap it in the
frosted surface (our `frost::frosted` + `MENU_BLUR`, upstream's glass
equivalent) and use the pill's composer-surface background so it matches
the pill and queue tray on every material and wallpaper tint; drop the
separate solid-input background token if one was added for it.

**Blocked by:** None.

**Status:** ready-for-human

**Upstream SHAs:** `0fab030f` (#669) — 1 file, `crates/ui/src/composer.rs`.
Our frost module is `crates/ui/src/frost.rs` (upstream names it
`glass.rs` from #471; we already have the frosted helpers — map onto
them, do not create a second glass module).

**Verification budget:** `cargo check -p roboco-ui -j 3`; existing
question-panel/composer tests. Web: the question wizard
(`web/packages/app/src/lib/wizard.ts` + its panel styling) — verify the
panel is opaque/frosted against the transcript and fix the styling only
if it actually bleeds.

- [x] Question panel frosted, matching the pill on all materials
- [x] Token cleanup (no orphaned solid-input token — none ever existed here;
      upstream added and dropped `input_solid_bg` within the same PR, and
      this port carries the final state only)
- [x] Tests green (no new tests demanded — existing question-panel/composer
      tests; execution deferred to the wave-final batched pass — user
      directive)
- [x] Port commit records upstream SHA

## Comments

- Ported `0fab030f` (#669) by intent into `crates/ui/src/composer.rs`
  (`render_wizard`): the panel is built into a `panel` binding, gains
  `.occlude()`, swaps `.bg(theme.input_glass_bg())` for the pill's
  `.bg(theme.composer_surface_bg())`, and is returned wrapped in
  `crate::frost::frosted(COMPOSER_RADIUS, 16.0, panel)`.
- One deliberate deviation from the ticket's literal "our MENU_BLUR":
  upstream's `MENU_BLUR` is 16 — equal to their composer pill's blur, so
  their `frosted(COMPOSER_RADIUS, MENU_BLUR, panel)` IS the pill
  treatment. Our `MENU_BLUR` is 44 (the menu/popover treatment,
  `frost.rs`), while our pill blurs 16; using 44 would make the panel
  heavier-blurred than the pill it stands in for. Ported at the pill's
  16.0 with a code comment recording why.
- Upstream also has `frost.rs` (not `glass.rs` — the ticket's naming was
  anticipatory; #471's rename is outside this wave). Our frost module's
  `frosted()` + `MENU_BLUR` are the equivalents used.
- Web verify-only (no fix needed): `web/packages/app/src/styles/app.css`
  `.wizard-panel` already paints `background: var(--rb-input-plate)` —
  the same opaque flattened token the web composer pill uses (web is
  forced opaque since the 2026-09-17 defrost decision), and
  `web/packages/app/tests/opaque-input-plate.test.ts` already asserts
  the wizard paints the plate. The transcript cannot bleed through —
  the outcome the upstream commit fixes on desktop already holds.
- Exclusions: none — upstream's diff is composer.rs-only.
- Verification: `rustfmt --edition 2024 --check` (no new drift; the 10
  pre-existing drift hunks in composer.rs left untouched);
  `cargo check -p roboco -j 3` at chunk end; test execution deferred to
  the wave-final batched pass (user directive).

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
