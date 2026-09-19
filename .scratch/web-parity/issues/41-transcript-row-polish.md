# 41 — Transcript row polish: tool-chip icon→label gap, jump-pill elevation + pinned gate

**What to build:** Two small visual fixes in the transcript. A tool chip's
label no longer glues to the rail icon — "Glob web/…" reads with the
desktop's 8px break instead of "Globweb/…". And the scroll-to-bottom pill
stops reading as "a bit overlapping the composer": its elevation drops from
the heavy popover shadow to the desktop's `shadow_md` weight, and it stops
flashing while the bottom spring is settling (a pinned gate the web omits).
Everything else about both surfaces is already 1:1 — this ticket changes
exactly one margin, one box-shadow, and one boolean.

**Blocked by:** None — can start immediately (runs in parallel with 40
(transcript stability); coordinate via the cross-references in §5 — do not
duplicate its anchor/stick work).

**Status:** ready-for-agent

**Research:** `../research-2026-09-19/transcript-tool-calls-scroll.md` S1
(a)–(d), S4 (a)–(d); consolidated gap rows 1, 8, 9, 10.

**Desktop reference (for lookups only):** `crates/ui/src/transcript.rs` —
activity constants (:108-116, :122), card height (:101), `tool_chip`
(:7327-7381), the expandable chip card (:6231-6352), the spawn chip guide
(:7409-7417), `handle_scroll`'s pinned gate (:3198), `jump_button_shown`
(:3743-3745), show thresholds (:72, :74-83); `crates/ui/src/shell.rs` —
`render_jump_to_bottom` (:6161-6179) and the pill (:6192-6254, `shadow_md`
at :6217); the pinned zui fork `hoangvu12/zui` rev `86f2ecef7532970613eaa07966735bf77a0a45b2`
(Cargo.toml) holds gpui's exact `shadow_md` recipe.

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/components/tool-group.tsx` | edit | `ToolChipRow`'s two card variants — the detail-less card's inline style (:417-424) and the expandable card's inline style (:460-467) — add `marginLeft: ACTIVITY_TEXT_GAP` when the rail renders (the import at :41 finally used) |
| `web/packages/app/src/components/stick-controller.ts` | edit | `#onScroll`'s non-own-turn `#setJumpShown` call (:480) — add the `&& !this.#pinned` gate |
| `web/packages/app/src/styles/app.css` | edit | `.jump-pill`'s `box-shadow` (:7472) — md-calibrated elevation; new `--rb-shadow-md` token beside `--rb-shadow-popover` (:4296) |
| `web/packages/app/tests/stick-spring.test.ts` | edit | the `jump_button_shown` gate case (§3) |

---

## 1. Context a fresh session needs

- A tool chip row is `ToolChipRow` → `<div class="tool-chip">` containing, in
  order: `ActivityRail` (a 48px-wide gutter column) and the card
  `div.tool-chip-card` (tool-group.tsx:404-431 for the detail-less chip,
  :447-486 for the expandable card; the spawn chip is :620-647).
- `ActivityRail` renders the tool glyph absolutely at
  `left: ACTIVITY_ICON_LEFT (32)` with size `ACTIVITY_ICON_SIZE (16)`
  (activity-rail.tsx:60-68) — the glyph occupies x 32→48 and ends flush at
  the gutter's right edge (gutter width 48, activity-rail.tsx:54 +
  app.css:8743-8748).
- The card's inline style sets ONLY `height`, `marginTop`, `marginBottom` —
  **no `marginLeft`** — in both variants (tool-group.tsx:417-424 and
  :460-467); `.tool-chip-card` in CSS has no margin either
  (app.css:8534-8541). So the label column (`.tool-chip-head` →
  `.tool-chip-label`, tool-group.tsx:570, app.css:8586-8592) starts at x=48 —
  a 0px gap. `ACTIVITY_TEXT_GAP` is imported at tool-group.tsx:41 and appears
  nowhere else in the file; the spawn chip kept its own `marginLeft: rail ? 12
  : undefined` (tool-group.tsx:633) — that one is correct, do not touch it.
- The scroll-to-bottom pill: `StickController.#setJumpShown` is driven by
  `jumpVisibility(wasShown, distance)` — show beyond 320px, keep until 2px
  (lib/stick-spring.ts:27-37); the surface publishes it up
  (transcript.tsx:1013-1020) and the chat page renders `<JumpPillAnchor>`
  inside `#persistent-composer` when a chat is selected (chat-page.tsx:818,
  :852-861). `JumpPill` itself is `components/transcript.tsx:2000-2011`
  ("↓" + "Scroll to bottom", 13px).
- Geometry is ALREADY 1:1 (verified): `.jump-pill-anchor` at
  `top: -36px; right: 10px; justify-content: center` (app.css:7446-7454),
  pill `height: 30px; border-radius: 15px; border: 1px; backdrop-filter:
  blur(16px)` (app.css:7462-7476), inner `gap: 6; padding-left: 11px;
  padding-right: 13px` (app.css:7489-7498), 13px glyph/label
  (app.css:7504-7513), entrance `rb-dialog-in` 180ms from
  `translate: 0 2px` (app.css:7515-7524) — the desktop's `DIALOG_IN` does the
  same 2px. Only the shadow and the gate differ (§2.2).
- Tokens: the pill currently uses `--rb-shadow-popover: 0 8px 30px
  rgb(0 0 0 / 0.35)` (app.css:4296, applied at :7472) — a token shared by
  real popovers; do not retune it globally. `--rb-motion-dialog-in` /
  `--rb-ease-ease` carry the entrance.
- Vocabulary: chat, engine, harness, space (CONTEXT.md).
- No icons change here (ticket 42 owns the icon family); no layout constants
  change beyond the one margin.

---

## 2. Spec

### 2.1 The rail chip card margin (the icon→label gap)

**Desktop reference — exact values (verbatim from research S1(b)):**

| Item | Value | Source |
| --- | --- | --- |
| `ACTIVITY_GUTTER_WIDTH` | 48.0 | transcript.rs:110 |
| `ACTIVITY_TEXT_GAP` (card's left margin off the rail) | **8.0** | transcript.rs:111 |
| `ACTIVITY_TRUNK_X` | 12.5 | transcript.rs:112 |
| `ACTIVITY_BEND_RADIUS` | 6.0 | transcript.rs:113 |
| `ACTIVITY_BRANCH_END_X` | 28.0 | transcript.rs:114 |
| `ACTIVITY_ICON_LEFT` | 32.0 | transcript.rs:115 |
| `ACTIVITY_ICON_SIZE` | 16.0 | transcript.rs:116 |
| Rail row height (`TOOL_TREE_ROW_HEIGHT`) | 32 | transcript.rs:122 |
| Card height (`CHIP_CARD_HEIGHT`) | 30 | transcript.rs:101 |

- The gutter's own doc comment reserves "a 4px break before the icon, and an
  **8px icon-to-text gap**" (transcript.rs:108-109).
- `tool_chip` applies `.when(rail, |el| el.ml(px(ACTIVITY_TEXT_GAP)))` on the
  card (transcript.rs:7363); the expandable chip card applies
  `.when(collapses, |el| el.ml(px(ACTIVITY_TEXT_GAP)))` (transcript.rs:6233).
  So on the desktop the card — and therefore the label text — starts at
  x = 48 + 8 = 56, and the icon-to-label gap is exactly **8px**.
- Ordinary (rail) rows place NO icon inside the header row — the icon tile
  exists only for spawn cards (transcript.rs:6924-6940, per ticket 19
  §2.1.8); the rail glyph is the only icon. The web matches this
  (tool-group.tsx:565-569 renders the tile only when `!activity`).
- The spawn chip guide line uses `ml(12)` (transcript.rs:7409-7417 per ticket
  19 §2.3) — the web kept that one (tool-group.tsx:633).

**The fix (web).** Add `marginLeft: ACTIVITY_TEXT_GAP` (the imported
constant) to the inline style of BOTH card variants in `ToolChipRow`, exactly
when the rail renders (`collapses`): the detail-less card
(tool-group.tsx:417-424) and the expandable card (tool-group.tsx:460-467) —
mirroring the desktop's `.when(rail, …)` / `.when(collapses, …)`. The label
column then starts at x=56. Do NOT add the margin to the spawn card (its
`marginLeft: 12` at :633 is the guide-line margin and already correct), and
do not touch `.tool-chip-card` in CSS (app.css:8534-8541) — the condition is
JS-owned, matching the `.when(rail, …)` form.

### 2.2 The jump pill: elevation + visibility gate

**Desktop reference (verbatim from research S4(b)), the rows that differ:**

| Property | Value | Source |
| --- | --- | --- |
| shadow | `shadow_md()` | shell.rs:6217 |
| hidden while | `jump_button_shown()` = shown && !pinned (own-turn held also suppresses) | transcript.rs:3743-3745, :3198, :3174-3175 |

Primary instance doc comment: *"horizontally centered over the transcript
column and floating six pixels above the composer"* (shell.rs:6158-6160).
`#onScroll` computes `let show = jump_visibility(this.show_jump_button,
distance) && !this.pinned;` (transcript.rs:3198).

**(a) The gate (web).** `#onScroll`'s non-own-turn branch calls
`#setJumpShown(jumpVisibility(this.#jumpShown, distance))` with no pinned
gate (stick-controller.ts:480), which can flash the pill during spring
settle. Add `&& !this.#pinned` there. The own-turn branch already suppresses
while held (`&& this.#ownTurn?.held !== true`, stick-controller.ts:459) —
leave it as is; together they are the desktop's `jump_button_shown()`.

**(b) The shadow (web).** The pill reuses the popover token
`--rb-shadow-popover: 0 8px 30px rgb(0 0 0 / 0.35)` (app.css:4296, applied at
:7472) — an 8px downward offset with a 30px blur whose wash extends ~38px
below the pill, over the composer's frosted top edge, in the 6px gap above
the composer: the "a bit overlapping" read. The desktop takes `shadow_md()`
(shell.rs:6217) — a subtle md elevation. Calibrate a dedicated
`--rb-shadow-md` token (or a small inline y/blur on `.jump-pill` if no token
fits) in the same `:root` block as `--rb-shadow-popover`; look up gpui's
exact `shadow_md` numbers in the pinned zui fork (hoangvu12/zui rev
`86f2ecef…`, see Cargo.toml `[patch]`) before inventing values. Only
`.jump-pill` switches to it — popover surfaces keep the popover token.

**(c) Geometry — verify, do not change (verbatim gap row):**

| item | kind | desktop value | web value | fix sketch |
| --- | --- | --- | --- | --- |
| Geometry (anchor/pill/entrance/texts) | MATCHES | top −36, h 30, right 10, gap 6, DIALOG_IN 180ms, "↓"/"Scroll to bottom" 13px (shell.rs:6166-6254) | identical (app.css:7446-7524; transcript.tsx:2000-2011) | none — if the product wants more clearance, change `top: -36px` deliberately on BOTH clients, not as a "parity fix" |

State in the PR/Comments that the anchor's `top: -36px` with a 30px pill
leaves the 6px gap above the composer card on BOTH clients — the perceived
overlap was the shadow weight, and only the gate + shadow change.

---

## 3. Pure logic to port

- **`jump_button_shown`** (transcript.rs:3743-3745, applied at :3198):
  `shown && !pinned`, with the own-turn hold also suppressing (the web's
  own-turn branch already encodes that half, stick-controller.ts:459). The
  pure half is `jumpVisibility` — already tested
  (`jump_button_stays_available_when_scrolling_down_until_near_bottom`,
  transcript.rs:7748; web: stick-spring.test.ts:82-87, transcript-model.test.ts:894-899).
  Port the gate as a small pure helper (e.g.
  `jumpButtonShown(jumpShown, distance, pinned, ownTurnHeld)` next to
  `jumpVisibility` in lib/stick-spring.ts) so both branches call it and the
  unit test can pin it; the desktop test it mirrors is
  `jump_button_stays_available_when_scrolling_down_until_near_bottom`
  (transcript.rs:7748) plus the pinned-suppression case.
- **The shadow lookup** is not logic: read gpui's `shadow_md` from the pinned
  zui fork and calibrate `--rb-shadow-md` to it (or to a visually matched
  small y/blur). Do not copy the popover numbers.

---

## 4. Gaps this ticket closes

Copied verbatim from research S1(d) and S4(d) (rows 1, 8, 9, 10 of the
consolidated table; the S4(d) subagent-pane row is excluded — see §5):

| item | kind | desktop value | web value | fix sketch |
| --- | --- | --- | --- | --- |
| Icon→label gap on rail chip rows | MISSING | card `ml(8)` (`ACTIVITY_TEXT_GAP`), label starts at x=56 (transcript.rs:111, :7363) | card `marginLeft` absent; label starts at x=48, 0px gap (tool-group.tsx:417-424, :460-467; app.css:8534-8541) | add `marginLeft: ACTIVITY_TEXT_GAP` (or CSS `margin-left: 8px` on `.tool-chip`'s card when the rail renders) to BOTH card variants in tool-group.tsx |
| Icon→label gap on expandable cards | MISSING | `ml(8)` when `collapses` (transcript.rs:6233) | same omission (tool-group.tsx:460-467) | same one-liner |
| Unused import | code smell | n/a | `ACTIVITY_TEXT_GAP` imported, never used (tool-group.tsx:41) | use it (above) or remove |
| Pill elevation | WRONG VALUE | `shadow_md()` (shell.rs:6217) — subtle md elevation | `--rb-shadow-popover: 0 8px 30px rgb(0 0 0 / 0.35)` (app.css:7472, token :4296) | calibrate a `--rb-shadow-md` token (or inline a small y/blur) for `.jump-pill`; verify gpui `shadow_md` numbers in the pinned zui fork |
| Pinned gate on visibility | MISSING | `&& !this.pinned` (transcript.rs:3198) | no gate (stick-controller.ts:480) | add the gate in the non-own-turn branch |
| Geometry (anchor/pill/entrance/texts) | MATCHES | top −36, h 30, right 10, gap 6, DIALOG_IN 180ms, "↓"/"Scroll to bottom" 13px (shell.rs:6166-6254) | identical (app.css:7446-7524; transcript.tsx:2000-2011) | none — if the product wants more clearance, change `top: -36px` deliberately on BOTH clients, not as a "parity fix" |

---

## 5. Do not

- **This ticket owns ONLY the chip gap + the pill gate/shadow.** Ticket 40
  (transcript stability) owns the reveal baseline, store reset, height
  estimates, reservation, and every anchor/stick rule — it edits
  `transcript.tsx`/`stick-controller.ts` in the same areas (the per-commit
  compensation sits a few lines above the pill publish effect); coordinate by
  cross-reference and do not duplicate or revert its work. In particular: add
  the `&& !pinned` gate ONLY in the non-own-turn `#setJumpShown` call
  (stick-controller.ts:480) — 40 may restructure neighboring code.
- Do not move the pill higher than −36 or change any other geometry —
  parity holds; more clearance is a deliberate two-client product change
  (S4(d) geometry row).
- Do not retune `--rb-shadow-popover` (app.css:4296) — real popovers share
  it; the pill gets its own md-calibrated value.
- Do not wire the subagent pane's second pill instance
  (`bottom(16); left/right 0`, shell.rs:6519-6536 — consolidated row 13):
  `JumpPill` is reusable but the wiring is a follow-up, not this ticket.
- Do not touch the spawn chip's `marginLeft: 12` guide (tool-group.tsx:633) —
  it matches transcript.rs:7409-7417.
- Do not hand-draw or regenerate any icon — ticket 42 owns the icon family
  (including the file-* stroke fix that makes some glyphs visible).
- Desktop-only, do not port: `frost::frosted` scene layering (the web pill's
  `backdrop-filter: blur(16px)` is the standing equivalent, tickets 06/19);
  gpui's `shadow_md` exact pixel recipe lives in the pinned zui fork
  (hoangvu12/zui rev `86f2ecef…`) — look it up before inventing numbers.

---

## 6. Acceptance

- [ ] A settled tool chip row reads "Glob web/…" with a visible 8px
      icon→label gap: in the browser, the `.tool-chip-label` box of a rail
      chip starts at x=56 within the row (48px gutter + 8px margin) — both
      card variants (detail-less and expandable).
- [ ] The expandable card variant shows the same 8px gap; the spawn card's
      12px guide margin is unchanged.
- [ ] The jump pill's elevation is md-weight: no heavy wash over the
      composer's frosted top edge in the 6px gap (screenshot with the pill
      shown, 1440px width).
- [ ] The pill never flashes during a spring settle near the bottom (pinned ⇒
      hidden); it still appears the moment the user scrolls ≥320px up and
      stays until 2px (hysteresis unchanged).
- [ ] Geometry verified unchanged: anchor `top: -36px; right: 10px`, pill
      height 30 / radius 15 / border 1px / blur 16, inner gap 6 + paddings
      11/13, `rb-dialog-in` 180ms from `translate: 0 2px`, texts "↓" +
      "Scroll to bottom" at 13px — state in Comments that only gate + shadow
      changed.
- [ ] Unit test: the `jump_button_shown` gate — hidden while pinned, hidden
      while the own-turn hold is live, otherwise `jumpVisibility`'s
      hysteresis (port of transcript.rs:3198/:3743-3745; the existing
      `jumpVisibility` suite stays green).
- [ ] Screenshot pair, desktop vs web, states: (a) a settled chat with a
      collapsed tool group — chip label offset from the rail icon;
      (b) scrolled-up chat with the pill visible over the composer.
- [ ] `pnpm -r build` green; `pnpm --filter @roboco/app test` green.
- [ ] No new literal hex/px where a `--rb-*` token exists (the md shadow is
      a token value or a calibrated one-off in the token block).

## Comments

### Implementer note (2026-09-19)

Landed on top of ticket 40's restructure (base 24d0e23c); the ticket's line
numbers were relocated by 40 and all edits were located by symbol.

- **Chip gap (§2.1):** `marginLeft: collapses ? ACTIVITY_TEXT_GAP : undefined`
  added to the inline style of BOTH `ToolChipRow` card variants in
  `tool-group.tsx` (the detail-less card and the expandable card), mirroring
  the desktop's `.when(rail, …)`/`.when(collapses, …)` (transcript.rs:7363,
  :6233). The label column starts at x = 48 + 8 = 56. The spawn chip's
  `marginLeft: 12` guide (tool-group.tsx:633, SubagentChip) is untouched;
  `.tool-chip-card` in CSS is untouched.
- **Pinned gate (§2.2(a)):** the non-own-turn `#setJumpShown` call in
  `#onScroll` (stick-controller.ts, the `this.#prevDistance = distance;` tail
  — :486 post-40) now goes through the new pure helper
  `jumpButtonShown(jumpShown, distance, pinned, ownTurnHeld)` in
  `lib/stick-spring.ts` (§3), passing `false` for `ownTurnHeld` (that branch
  only runs with `#ownTurn === null`). The own-turn branch
  (`&& this.#ownTurn?.held !== true`) is untouched, exactly as §2.2(a)/§5
  direct — the desktop's own-turn path (transcript.rs:3174-3175) has no
  pinned gate and early-returns before :3198, so both halves together are
  `jump_button_shown()`. One §3 reconciliation: "so both branches call it"
  was read as the helper being the shared, test-pinnable encoding of the
  gate; only the non-own-turn call site was rewritten, since changing the
  own-turn expression would alter ticket 40's landed behavior (forbidden by
  §5) and deviate from the desktop.
- **Shadow (§2.2(b)):** new `--rb-shadow-md: 0 4px 6px -1px rgb(0 0 0 / 0.1),
  0 2px 4px -2px rgb(0 0 0 / 0.1)` in the `:root` block beside
  `--rb-shadow-popover` — gpui's `shadow_md` copied VERBATIM from the pinned
  zui fork (hoangvu12/zui @ 86f2ecef…, `crates/gpui_macros/src/styles.rs:441-449`,
  the Tailwind shadow-md port; verified in the local cargo checkout). Only
  `.jump-pill` switched to it; `--rb-shadow-popover` is unchanged for real
  popovers (`.jump-pill` was dropped from the family list in its comment).
- **Geometry (§2.2(c)) — verified unchanged, no edit:** anchor
  `top: -36px; left: 0; right: 10px` + centered, pill height 30 / radius 15 /
  border 1px / blur 16, inner gap 6 + paddings 11/13, `rb-dialog-in` 180ms
  from `translate: 0 2px`, texts "↓" + "Scroll to bottom" at 13px
  (app.css:7437-7524; transcript.tsx JumpPill). The anchor's `top: -36px`
  with the 30px pill leaves the 6px gap above the composer card on BOTH
  clients — the perceived overlap was the shadow weight, and only the gate +
  shadow changed.
- **Unit test (§6):** `jumpButtonShown` suite added to
  `tests/stick-spring.test.ts` — the desktop's
  `jump_button_stays_available_when_scrolling_down_until_near_bottom`
  (transcript.rs:7748) loop plus the pinned-suppression and own-turn-hold
  cases; the existing `jumpVisibility` suite stays green.
- **Verification:** `pnpm -r build` green (web, 5/5 projects);
  `pnpm test` in `packages/app` → 1206 passed (1205 at base + 1 new), 0
  failures. The screenshot pair (§6) was not produced — headless environment,
  no running engine/browser; geometry and values are pinned by the
  unit/CSS-token checks above.

No deviations beyond the §3 reading noted above.
