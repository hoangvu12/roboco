# 03 — Model chip spec: padding and the fast glyph

**What to build:** Two verified drift items on the composer's identity chip
(the model picker's trigger):

1. **Padding.** `.identity-chip` uses `padding-inline: 10px`
   (app.css:4749) where the desktop's model chip uses **px 6**
   (pickers.rs:2931-2937). Everything else already matches: h 32 / max-w 248 /
   gap 6 / radius 8 / 12px medium (app.css:4741-4755) and the suffix tones
   70%→85% (app.css:4787-4799 = pickers.rs:5485-5492).
2. **Fast glyph.** The desktop appends a `FAST_TIER_BOLD` 13px glyph in the
   accent color after the suffix when the fast tier is on
   (pickers.rs:5523-5530); the web carries "Fast" only as suffix text
   (`lib/traits-summary.ts:82-101`). Port the glyph so the chip reads like
   the desktop at a glance.

**Blocked by:** None.

**Status:** ready-for-human

**Research:** `../research.md` §2 (chip spec notes).

**Desktop reference (for lookups only):**
`crates/ui/src/pickers.rs:2931-2937` (chip padding), `:5523-5530` (fast
glyph), `:2874-3010` (the whole `trigger_chip` — geometry, truncation,
loading states).

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/styles/app.css` | edit | `.identity-chip { padding-inline: 6px }` (:4749) |
| `web/packages/app/src/lib/traits-summary.ts` | edit | expose the fast-tier flag alongside the summary text |
| `web/packages/app/src/components/composer-pickers.tsx` | edit | render the fast glyph after the suffix |
| `web/packages/app/src/styles/app.css` | edit | `.identity-chip-fast` rule (13px glyph, `var(--rb-accent)`) |
| `web/packages/app/tests/` | edit | wherever traits-summary is pinned — search for `traits-summary` in tests/ and extend |

## 1. Notes

- The 8px narrower padding shrinks the hit area slightly — desktop parity
  wins; nothing else consumes `.identity-chip` padding (verified).
- **Deliberately deferred (record in the commit message, do not build):** the
  desktop's measured/eased chip width with edge-fade truncation
  (`resizing_chip_text`, pickers.rs:5211-5238, `model_chip_width`
  :2830-2873). The web's static max-width + ellipsis is a sane stand-in;
  porting the width animation is motion work with its own ticket if it ever
  matters.

## 2. Tests

- CSS-contract idiom: `readFileSync` + `topLevelRule` (pattern:
  `tests/markdown.test.ts:798-802`) — assert `.identity-chip` padding and the
  new `.identity-chip-fast` rule.
- Extend the traits-summary test: fast tier on → both the "Fast" summary
  text and the glyph flag; off → neither.

## 3. Acceptance checklist

- [x] Chip padding 6px; visual check against the desktop's chip
- [x] Fast tier renders the accent glyph after the suffix
- [x] Width-animation deferral recorded in the commit message
- [x] Tests green; full app suite green

## Comments

**Branch:** `ticket/mp-03-chip-spec` (base `a485d5ce`). Commit `8ecb121b`
(implementation) + review-pass commit (citation fix :5490-5510 →
:5493-5503, and the mounted glyph test the review demanded). Verification
(all from `web/packages/app`): focused suites (composer-reasoning,
traits-summary, model-rows, composer-draft, harnesses) → 129/129; full
app suite `pnpm exec vitest run` → **148 files, 2235 tests, all passed**;
`pnpm exec tsc --noEmit` clean. Both named seams ran red→green during
implementation; the mounted glyph test is mutation-checked (disabling the
glyph render fails it).

**"Visual check" reading:** no live visual was possible under the
session's machine-safety rules (no dev server/engine spawn) — the padding
is verified against the desktop's source (`pickers.rs:2931-2937`,
`px(6.0)` for `PickerKind::HarnessModel`, px 10 for every other trigger)
by the CSS-contract test plus the citation walk, and the glyph's geometry
(FAST_TIER_BOLD, 13px, `theme.accent`, flex-none, appended after the
suffix — pickers.rs:5493-5503, :5525-5536) matches value-for-value.

**Judgment calls:** (1) `fastModeValues` moved from `model-rows.ts` to
`traits-summary.ts` (verbatim, its every-encoding test moved with it) so
the ticket-named file owns the flag without a `model-rows ↔
traits-summary` import cycle — the desktop's own shape, where
compact.rs consumes `fast_mode_values` from the pickers module. (2) The
glyph is ported STATIC: the desktop wraps it in `motion::fast_tier`
(a 700 ms activation sheen, motion.rs:1288) — motion work joins the
deferred width-animation item. (3) The glyph is gated `!noAgents` like
the suffix. (4) mp-02 preserved: an absent explicit pick leaves
`selectedModel` undefined → no glyph (the desktop's `fast` local is
`selected_model.is_some_and`, same shape); the mounted absent-pick test
still passes.

**Confirmed adjacent drift, out of scope (for a future ticket):** the
desktop's `traits_summary` SKIPS the `serviceTier` part when its
effective choice is `default`/`standard` (pickers.rs:246-249) — an off
tier reads as absent on the desktop's suffix, while the web spells the
off label ("Standard · Standard" on Codex defaults). This ticket's two
verified drift items (padding, glyph) are done; the suffix-text drift
was not in scope and is recorded here, not built.

**2026-10-03 — CLOSED (maintainer: "do like upstream"):** the suffix-text
drift is fixed in a follow-up commit — `traitsSummary` now skips the
serviceTier part at its quiet default exactly like pickers.rs:246-249,
pinned by `traits_summary_omits_the_service_tier_at_its_quiet_default`.
**Quirk 1 (the palette reveal off-by-one) was explicitly SKIPPED by the
maintainer (pre-existing web code).**
