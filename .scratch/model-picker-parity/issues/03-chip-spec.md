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

**Status:** ready-for-agent

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

- [ ] Chip padding 6px; visual check against the desktop's chip
- [ ] Fast tier renders the accent glyph after the suffix
- [ ] Width-animation deferral recorded in the commit message
- [ ] Tests green; full app suite green
