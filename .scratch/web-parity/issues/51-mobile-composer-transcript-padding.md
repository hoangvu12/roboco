# 51 — Mobile composer + transcript padding parity

**What to build:** On a phone (≤768px) the composer stops floating in its own
rhythm: the pill's card edge and the status strip's text sit in the same 12px
gutter as the transcript's message rows, replacing today's triple inset (an
8px width shim + a 16px column padding + the pill's own inner padding = 24px
from the window edge to the card, 40px to the typed text, while chat text sits
at 12px). After this ticket a 375px window has one horizontal rhythm from the
first message row down to the composer pill.

**Blocked by:** 50 (Mobile layout system — owns the phone viewport baseline
and the composer's bottom/safe-area padding arm).

**Status:** ready-for-agent

**Research:** `../research-2026-09-19/mobile-layer.md` M1(a)–(d) + "Current
mobile layer inventory" rows (phone layout block `app.css:6738-6851`, composer
shim `:6796-6798`; trow desktop gutters `:7007-7011` / phone `:9050-9058`).

**Desktop reference (for lookups only):** `crates/ui/src/composer.rs::Composer::render`
(`:7347-7355` — the `w_full max-w-3xl mx-auto` column with `px-4`, ported at
`app.css:2965-2977`) and the pill's own inner insets (`composer.rs:7734-7851`).
The desktop has no phone layout — the phone form below is the research's
translation of the desktop's one-shared-axis model onto the transcript's phone
gutter (M1(b)); copy it, do not invent a third rhythm.

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/styles/app.css` | edit | the `@media (max-width: 768px)` phone block (starts `:6738`): replace the `.composer` shim (`:6796-6798`) with the M1(b) phone rules; add the `.status-strip` phone padding-inline override; optionally cap `.persistent-composer` (`:2480-2485`) at phone |

No component, state, or logic file changes. No new tests (CSS-only — see §3).

---

## 1. Context a fresh session needs

- The composer column is the desktop's centered 768px box: `.composer`
  (`app.css:2965-2977`) — `width: 100%; max-width: 768px; margin: 0 auto;
  padding: 0 var(--rb-space-lg) var(--rb-space-lg)` (16px horizontal).
- At phone the column is narrowed instead of its padding:
  `@media (max-width: 768px) { .composer { width: calc(100% - 2 *
  var(--rb-space-sm)); } }` (`app.css:6796-6798`, comment "The pill is
  edge-to-edge on desktop only; a phone needs its gutters"). With
  `margin: 0 auto` this centers a narrower column — 8px per side.
- The pill's compact input box then adds `padding: 0 8px 0 16px`
  (`app.css:3215-3220`) — the desktop's own inner inset, unchanged here.
- The transcript's phone gutter is one rule: `.trow { padding-inline:
  var(--rb-space-md); }` (`app.css:9050-9053`, ≤768) — 12px. The desktop's
  48px version is `@media (min-width: 769px)` (`app.css:7007-7011`).
- Net on a 375px window today: the pill's card edge sits 8 + 16 = **24px**
  from the window edge, the typed text 8 + 16 + 16 = **40px**, chat message
  text **12px**, and the status strip is a third value, `padding-inline: 24px`
  (`app.css:7021-7030`) (M1(a)).
- Root cause (M1(c)): the phone layer predates the parity wave; its 8px shim
  was never reconciled with ticket 18's later phone transcript gutter, which
  landed with a different value.
- The wrapper: `.persistent-composer` (`app.css:2480-2485`, `max-width: 768px;
  margin-inline: auto`) carries an inline `width: composerWidth` px
  (`routes/chat-page.tsx:779`) — harmless at phone (the column measures the
  full viewport), but its phone cap can be `100%` for the same reason as the
  shim's deletion (M1(b) note).
- Tokens: `--rb-space-sm` = 8, `--rb-space-md` = 12, `--rb-space-lg` = 16
  (`web/packages/theme/src/index.ts:289-292` over `theme/src/generated/
  artifact.json` layout.space).
- **Decision-5 amendment (user directive, 2026-09-19):** spec decision 5
  (`../../spec.md:29-31`) declared phone widths out of scope ("do not break
  it, do not extend it"); this ticket amends that for this surface — phone
  work is in scope, and desktop (≥769px) behavior is untouched.
- What 50 provides by the time this runs: the phone viewport baseline
  (`100dvh` + `overscroll-behavior` guards) and the composer's **bottom**
  padding arm incl. `env(safe-area-inset-bottom)` (the phone override of
  `app.css:2970`'s bottom term, per the research's Recommended mobile layout
  system item 3). This ticket touches only the **horizontal** rules in the
  same phone block — keep 50's bottom/safe-area rule intact.

## 2. Spec

### 2.1 The composer's phone gutter (research M1(b), verbatim)

Desktop model being translated: the composer column and the transcript
content column share one centered axis — the composer is `w_full max-w-3xl
mx-auto` with `px-4` (`composer.rs:7347-7355`, ported at `app.css:2965-2977`)
and the transcript's 736px column sits inside the same 768px cap with 48px
gutters (`app.css:6994-7005`, `7007-7011`), so the pill edge (16px inside
the 768 box) and the message gutters read as one rhythm. On the phone the
transcript is full-bleed with a 12px gutter — the composer should adopt that
same gutter:

| property | value | source |
| --- | --- | --- |
| `.composer` phone width | `100%` (delete the `calc(100% - 2 * --rb-space-sm)` shim) | new rule in the 6738 phone block, replacing `app.css:6796-6798` |
| `.composer` phone padding-inline | `var(--rb-space-md)` (12px) | mirrors `.trow` phone (`app.css:9052`) |
| `.status-strip` phone padding-inline | `var(--rb-space-md)` | `app.css:7030` phone override |
| pill inner paddings | unchanged (16px is the desktop's own inner inset, `composer.rs:7734-7851`) | — |

`.persistent-composer`'s inline `width: composerWidth` px
(`routes/chat-page.tsx:779`) is harmless at phone (the column measures the
full viewport), but the wrapper's phone cap can be `100%` for the same
reason as the `.composer` shim.

**States:** none — a static media-block rule; no component state changes.

**Interactions:** none.

**Motion:** none — no transition is added or removed.

**Text:** none (chrome geometry only).

**Data:** none (no store, no RPC).

## 3. Pure logic to port

None. This is a pure-CSS change inside the existing
`@media (max-width: 768px)` block; nothing branches in JS.

- New web unit tests: none — there is no function to test; the geometry is
  asserted optically by the §6 screenshot.
- Desktop tests: none apply (the desktop has no phone layout; the desktop
  numbers stay exactly as ported).

## 4. Gaps this ticket closes

From research M1(d), verbatim:

| item | kind | expected | web value (file:line) | fix |
| --- | --- | --- | --- | --- |
| composer column gutter | geometry | 12px, matching `.trow` phone | 8px shim + 16px padding = 24px card / 40px text (`app.css:6796-6798`, `2970`, `3219`) | phone rule: `width: 100%; padding-inline: var(--rb-space-md)` |
| status strip gutter | geometry | 12px | 24px (`app.css:7030`) | phone override to `--rb-space-md` |

From the research's consolidated gap table, the same two rows (M1 composer
gutter, M1 status strip gutter) — no additional content.

## 5. Do not

- Do not change desktop (≥769px) behavior: the 16px column padding
  (`app.css:2970`), the 24px status-strip gutters (`:7030`), and the 48px
  trow gutters (`:7007-7011`) all stay. Every rule this ticket touches lives
  inside the `@media (max-width: 768px)` block.
- Tickets 40/41 own transcript behavior at all widths — `.trow`'s phone rule
  (`app.css:9050-9053`) is the alignment **target**, not a file to edit.
- Ticket 50 owns the phone composer's **bottom** padding +
  `env(safe-area-inset-bottom)` and the viewport baseline — do not touch or
  duplicate the bottom/safe-area arm of the same rules.
- Ticket 39 owns the send-path — this ticket changes horizontal CSS only; no
  composer behavior.
- The pill's inner paddings (16px, `app.css:3212`, `3215-3220`) are the
  desktop's own inner inset (`composer.rs:7734-7851`) — keep them.
- Do not re-add INVENTED UI: no width transitions on the pill, no new
  gutters, chrome, or wrapper elements beyond the spec table.

## 6. Acceptance

- [ ] At 375px, composer left/right padding optically matches transcript
      rows: the pill's card edge sits at the 12px gutter
      (`var(--rb-space-md)`); the 8px shim and the 16px horizontal column
      inset are gone at phone.
- [ ] At 375px, the status strip's text starts at the same 12px gutter.
- [ ] At ≥769px nothing changes — desktop rules (16px column, 24px strip,
      48px trow) are untouched.
- [ ] No new literal hex/px where a `--rb-*` token exists (the 12px comes
      from `var(--rb-space-md)`).
- [ ] Screenshot: `use-browser` at 375×667 against `web_smoke`, states:
      "established chat, message rows + composer" (pill edge aligns with row
      text) and "new-chat canvas" (pill + status strip); plus one ≥769px
      capture showing no regression. The desktop has no phone analog, so the
      "pair" is phone-before/phone-after plus a desktop-width regression
      shot.
- [ ] `pnpm -r build` green; package vitest green (no new tests — CSS-only).

## Comments

### Implementer note (2026-09-19)

Landed as specified — a pure-CSS edit confined to the `@media (max-width: 768px)`
phone block in `web/packages/app/src/styles/app.css` (the block starting at the
"Phone layout: drawer sidebar, stacked panels" comment):

- `.composer` phone rule (replaces the old shim rule, same slot): the
  `width: calc(100% - 2 * var(--rb-space-sm))` shim is deleted →
  `width: 100%; padding-inline: var(--rb-space-md)`. Ticket 50's bottom arm
  (`padding-bottom: calc(var(--rb-space-lg) + env(safe-area-inset-bottom))`) is
  carried over verbatim, untouched. The phone `padding-inline` longhand
  composes with the desktop `.composer` shorthand and with 37's landed
  pill/body shapes (the pill's own 16px inner insets stay, per §2.1).
- `.status-strip` phone override added in the same block:
  `padding-inline: var(--rb-space-md)` (desktop 24px rule untouched).
- `.persistent-composer` phone cap added (the §2.1 optional row):
  `max-width: 100%` — same reconciliation as the shim's deletion; the wrapper's
  inline px width tracks the full column at phone, and the cap keeps a
  mid-glide dock tick from pushing the pill past the viewport edge.

No component, state, or logic files touched. No new tests (CSS-only, §3).
`.trow`'s phone rule (the 12px alignment target), the ≥769px rules, and the
pill's inner insets are untouched; every added rule lives inside the ≤768px
block. The 12px comes from `var(--rb-space-md)` — no new literal px/hex.

Verification (build + tests only, per the wave's hard rule):

- `pnpm -r build` (web/) — green: proto, engine-client, app (tsc --noEmit +
  vite build, `✓ built in 7.22s`).
- `pnpm test` (web/packages/app) — green: `Test Files 81 passed (81)`,
  `Tests 1276 passed (1276)`.

Screenshot pairs (§6) explicitly waived by the wave instructions — no
dev server / `web_smoke` / browser process was started; geometry follows the
spec table (card edge 12px, typed text 12 + 16 = 28px, chat text 12px, strip
text 12px at a 375px window).

