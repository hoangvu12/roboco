# 07 — Recorded decisions: picker deviations to keep

**What to build:** Nothing to implement — this ticket records the
model-picker deviations the deep research verified as either
desktop-stale, web-correct, or not worth their port cost, so they stop
resurfacing in audits. Each entry below needs a maintainer confirmation
(check the box or override with a `## Comments` note), then the decision is
copied into the web code's own doc comments where the divergence lives.

**Blocked by:** None.

**Status:** done

**Research:** `../research.md` §2 (the full divergence table).

## The decisions

1. **No-agents copy stays "Settings → Agents".** Web:
   "Settings → Agents" (composer-pickers.tsx:991-993, :1626-1628); desktop:
   "Settings → Providers" (pickers.rs:3872-3875). The web's settings page is
   actually named Agents (picker-catalog.ts:9) — the web copy is
   navigation-accurate; matching the desktop verbatim would point users at a
   page that doesn't exist under that name. **Recommend: keep web copy,
   record the divergence** in the composer-pickers.tsx header comment.
2. **The fixed 216px list band stays.** With abundant room the desktop's
   `model_menu_budgets` math yields exactly 216 (pickers.rs:5178-5203); the
   web pins that resting value (model-picker-geometry.ts:26, pinned by
   model-picker-geometry.test.ts). Porting buys only short-viewport shrink
   (list→30) and the above/below flip (min-180) at the cost of dynamic
   `listHeight` through the virtualizer and both arms.
   **Recommend: record the deviation; revisit only on short-viewport overlap
   reports.**
3. **The window-level keyboard listener stays.** Desktop routes keys
   card-owned (pickers.rs:2596-2746); the web listens on the window in the
   capture phase (composer-pickers.tsx:884-937, :1382-1530) — the documented
   rationale holds (takeover states have no input to own keys;
   `overlaySource` gates session jumps). **Recommend: keep; the rationale is
   already documented in-file.**
4. **The desktop's search-scope docstring is stale, not the web.** Desktop
   pickers.rs:1962 claims the query spans every harness; the actual behavior
   (scoped_model_rows :4901-4989) scopes per tab — identical to the web's
   `scopedModelRows` (model-rows.ts:115-125, pinned). **No action.**
5. **Chip width animation stays deferred.** The measured/eased chip width
   with edge-fade truncation (resizing_chip_text, pickers.rs:5211-5238) is
   motion work; the web's static max-width + ellipsis stands until it
   matters. **Recommend: record (already noted in ticket 03).**

## Acceptance checklist

- [x] Each decision confirmed or overridden in `## Comments`
- [x] Confirmed decisions copied into the owning files' doc comments
- [x] `../research.md` §2 marked with the outcomes

## Comments

**2026-10-03 — maintainer triage: all five recommendations confirmed**
("keep our" — the web shapes stand). Recordings landed:

1. No-agents copy: divergence comment at the takeover site
   (composer-pickers.tsx, the `model-no-agents-body` arm).
2. Fixed 216 band: recorded-deviation note in
   `lib/model-picker-geometry.ts`'s header (revisit only on
   short-viewport overlap reports).
3. Window keyboard listener: rationale already documented in-file
   (composer-pickers.tsx:920, :1553) — verified, no edit needed.
4. Search-scope docstring: no action (verified at parity).
5. Chip width animation: deferral already recorded in ticket 03's
   commit message and file.
