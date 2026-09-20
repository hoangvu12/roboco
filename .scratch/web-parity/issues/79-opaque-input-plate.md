# 79 — Opaque input plate: flatten the input-glass family

**What to build:** The web is forced opaque (2026-09-17 defrost decision, ticket 56), but the input-glass family still renders translucent in dark themes: the composer pill, the question wizard, generic form inputs, and the files-preview comment draft. Emit an opaque flattened `--rb-input-plate` token from `@roboco/theme` (the exact web port of the desktop's `Theme::input_glass_bg()` = `flatten(input, bg)`, theme.rs:1785) and use it for those surfaces. This is the only real see-through family left in the web audit — everything else already paints opaque.

**Blocked by:** None.

**Status:** ready-for-agent

## 1. Evidence (web-parity/followup @ c81dd2fe)

- Every dark variant's `input` role carries authored alpha: `#343438b8` (a ≈ 0.72) in all 21 dark variants of the theme artifact; light variants' `input` is opaque `#ffffff`.
- The composer pill and wizard claim the desktop's opaque branch but implement it as `background: color-mix(in srgb, var(--rb-input) 82%, var(--rb-bg))` (`.composer-pill` app.css:3381; `.wizard-panel` app.css:3853). `color-mix` **interpolates alpha** (≈ 0.82·0.72 + 0.18·1 ≈ 0.77) instead of compositing, so the pill is ~23% see-through over the transcript — the live report ("the web version currently the composers and stuff have opacity, the desktop dont do that when on opaque mode").
- Raw `var(--rb-input)` fills: `.input` (app.css:148, used by pair/settings forms) and `.editor-comment-input` (app.css:14119) paint the 0.72-alpha tint directly. They sit on opaque dialog/popover cards, so they are not see-through, but the desktop paints `input_glass_bg()`'s flatten there (composer.rs:6997-6998, files/preview.rs:3014-3025) — a tone deviation.
- Desktop opaque-mode recipes to mirror: `input_glass_bg()` = `flatten(self.input_bg, self.bg)` (crates/ui/src/theme.rs:968-980); the pill assembly's opaque branch is `theme.border` + flatten + `shadow_lg` (crates/ui/src/composer.rs:7690-7717).
- `flatten` (theme.rs:1785): composite `fg` (possibly translucent) over opaque `bg` per channel, return alpha 1.
- Audit verdict for everything else: popovers, modals, queue panel, jump pill, tooltips, right pane, terminal, titlebar island, diff sticky headers, hero, scrims/washes all match desktop-opaque behavior. The sidebar's `rgb(var(--rb-wash) / 0.05)` over `--rb-bg` is an opaque result with an accepted tone deviation vs the desktop's `surface` — NOT part of this ticket.

## 2. Spec

| Change | File | Requirement |
| --- | --- | --- |
| Plate token | `web/packages/theme/src/index.ts` (`variantCssVars`) | Emit `--rb-input-plate` = source-over composite of `variant.colors.input` over `variant.colors.background`, opaque result (port of theme.rs:1785; reuse the module's `rgbaOf`/`hexOf`). Fallback: the raw `input` string when either color fails `isHexColor`. Emitted for every variant, both appearances. |
| Composer pill | `app.css` `.composer-pill` (3374-3383) | `background: var(--rb-input-plate)`; update the stale comment block (3362-3368) — the `color-mix` was an alpha interpolation, not a flatten. |
| Wizard | `app.css` `.wizard-panel` (3848-3857) | Same. |
| Form inputs | `app.css` `.input` (145-153) | `background: var(--rb-input-plate)`. |
| Comment draft | `app.css` `.editor-comment-input` (14112-1424) | `background: var(--rb-input-plate)`. |
| No other surface changes | — | Do not touch any other rule; do not revive backdrop-filter or the dead `--rb-glass-*` tokens; the web stays always-opaque. |

## 3. Tests

- `tests/theme-vars.test.ts` (stub-root idiom): dark `--rb-input-plate` equals the hand-computed composite of `#343438b8` over the dark `background` (compute expected channels a·c + (1−a)·bg, rounded); light plate equals `#ffffff` (input already opaque); plate is always 6-hex (opaque) for both.
- A stylesheet contract test (the `readFileSync(app.css)` idiom from `tests/phone-titlebar-gestures.test.ts`): `.composer-pill` and `.wizard-panel` use `var(--rb-input-plate)`; no `color-mix(in srgb, var(--rb-input) 82%` remains in the file.

## 4. Acceptance

- [ ] Dark-theme composer pill and wizard are fully opaque over the transcript (no see-through while messages scroll under them).
- [ ] Form inputs and the comment draft show the flattened plate.
- [ ] Light mode unchanged (plate = opaque input as before).
- [ ] Tests above pass; full `pnpm test` green; `pnpm -r build` from `web/` green.
- [ ] User visual confirmation on the follow-up build (pending until handed over).

## Comments

Created 2026-09-20 evening from the live round-2 report ("composers and stuff have opacity") and the full web-vs-desktop opaque audit. The see-through family is exactly the input-glass set above; the rest of the web already matches desktop opaque mode.
