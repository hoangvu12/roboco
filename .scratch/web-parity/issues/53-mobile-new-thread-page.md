# 53 — Mobile new-thread canvas

**What to build:** The phone new-chat page stops being "literally nothing":
it gets the desktop canvas treatment — the background hero (artwork
cover-fitted into the top 72% of the viewport, with the feathered cutout
around the composer pill), the device + project selector chips floating
above the pill, the dock re-anchoring the composer to its canvas position,
and the status strip + git footer chips where they already are. After this
ticket, opening "New chat" on a 375px window looks like the desktop's
new-chat canvas, and the first send hands off to the established chat.

**Blocked by:** 33 (New-thread background pixel parity — the paint source),
50 (Mobile layout system — viewport baseline, phone titlebar inset,
safe-area). 49 (Responsive dialog/drawer primitive) is a soft dependency:
49 supplies the canvas chips' phone popover form (they are the same
`DeviceChip`/`ProjectChip` components 49 already branches — "this ticket
supplies the chips' phone form … 53 deletes the `display: none` that hides
the row"); without 49 the chips still open as clamped floating cards.

**Status:** ready-for-agent

**Research:** `../research-2026-09-19/mobile-layer.md` M5(a)–(d) + M9(b)–(d)
(the `.dock-target-selectors` inversion this ticket deletes) + "Current
mobile layer inventory" rows (JS branches: phone flag
`chat-page.tsx:440-443`, hero gate `:565`; CSS blocks: phone layout
`app.css:6738-6851`, hero + selectors hidden `:6803-6809`).

**Desktop reference (for lookups only):** `crates/ui/src/shell.rs::
new_thread_background` (`:857-914`) — absolute, top-left of the conversation
column, full canvas width, height `min(viewport × 0.72, 760)` (`:698-699`,
`:852-855`), the double-pass cutout artwork, 120ms readiness fade;
`crates/ui/src/composer_dock.rs:380-385` — the composer's canvas anchor
`(viewportHeight − height) × 0.5 + 8` ("Anchor by the top of the input
surface"); `crates/ui/src/pickers.rs::render_new_thread_target_selectors`
(`:2426-2498`) and `::render_new_thread_git_selectors` (`:2502-2566`);
`crates/ui/src/composer.rs:7936-7985` (the footer slot's Layer A). All of it
is already ported (ticket 15) — **the paint source is ticket 33's pixel
parity work** (the smoothstep mask ramp, `min` compositing, the frosted
branch, the four effect rasters); the motion/continuity repairs ride on the
sibling canvas tickets 34 (sidebar slide + titlebar island), 35 (route-change
double flash), 36 (transition matrix) — inherited by the phone canvas once
they land, not blockers here per the filing directive (33, 50). This ticket
only flips the phone gates and mounts that hero at 375px.

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/routes/chat-page.tsx` | edit | `heroVisible` (`:565` — drop `&& !phone`), the hero-width sidebar term at phone (`:431`/`:566`), `dockReduced` (`:443` — drop the `\|\| phone` arm per §2.2's recorded choice), the prepaint phone guard (`:509-524` — drop `!phone`) |
| `web/packages/app/src/styles/app.css` | edit | phone block: **delete** `.new-thread-hero { display: none }` (`:6803-6805`) and `.dock-target-selectors { display: none }` (`:6807-6809`) — the latter is M9's fix |
| `web/packages/app/tests/new-thread-background.test.ts` | edit | the phone hero-width case (§3), if the sidebar arm lands as a helper |

Nothing else: `NewThreadCanvas` already mounts at `chat-page.tsx:722-728`
(defined `routes/index-page.tsx:19`), the selector row already mounts at
`composer.tsx:2670-2678` (chips from `components/composer-footer.tsx` via
`components/composer/new-thread-selectors.tsx:110-130`), and the footer's
Layer A git chips already render (`composer.tsx:2843-2852`). The chips'
popover phone form is 49's `PickerCard` branch, not per-site work.

---

## 1. Context a fresh session needs

- The phone media block strips everything the desktop canvas has, and the JS
  refuses to mount the rest (M5(a)):
  - Hero: `@media (max-width: 768px) { .new-thread-hero { display: none; } }`
    (`app.css:6803-6805`, comment: "the new-thread hero and its floating
    selector row are desktop-width chrome (spec decision 5)…") — and the
    component is not even mounted: `const heroVisible = (!hasSelection ||
    dockFrame.active) && !phone;` (`routes/chat-page.tsx:565`, `phone` at
    `:440`).
  - Selectors: `@media (max-width: 768px) { .dock-target-selectors {
    display: none; } }` (`app.css:6807-6809`) — the M9 inversion: the
    desktop's ONLY on-canvas project selector is removed on phone.
  - Composer anchoring: `const dockReduced = reducedMotion || phone;`
    (`routes/chat-page.tsx:443`) — the dock never re-anchors the composer to
    the canvas center: the prepaint branch is `if (wrapper !== null && stack
    !== null && !phone) { … wrapper.style.transform = … }` else clears the
    transform (`routes/chat-page.tsx:509-524`). The composer stays glued in
    the bottom stack.
- What remains at phone today: `--rb-bg` body, the status strip (empty/idle,
  `routes/chat-page.tsx:772`), the composer in the bottom slot, the footer
  Layer A git chips (`composer.tsx:2843-2852`). "it has literally nothing."
- The hero components (all landed, all width-agnostic): the element is
  `components/new-thread-background.tsx` (mount `:173-212`, hero CSS
  `app.css:2495-2601`, readiness fade `:2510-2523`); the cutout mask is
  regenerated from a ResizeObserver on `#composer-surface`
  (`new-thread-background.tsx:109-160`) — works at 375px; the height fn is
  `lib/new-thread-background.ts:62` (`newThreadBackgroundHeight`); the dock
  prepaint is `lib/composer-dock.ts` (`prepaint`, `:475-481`).
- `heroWidth` is `Math.max(viewport - sidebarNow, 0)`
  (`routes/chat-page.tsx:566`) with `sidebarNow = sidebarTarget(sidebar)`
  (`:431`) — the **dragged** width (e.g. 304), so at phone it computes 71px
  instead of the canvas width. With the sidebar-is-out-of-flow correction
  (M3/M2's shared fix, landed for the titlebar by 50) the phone arm must be
  0 → `heroWidth` = the full phone canvas width.
- The selector row's CSS is desktop geometry reused verbatim
  (`app.css:2987-3003`: absolute, 20px tall, 28px above the column's top,
  left/right 26, right-justified, gap 4).
- Root cause (M5(c)): decision 5 froze the phone canvas at its pre-parity
  shape and tickets 13/15 were written with "phone layer out of scope"
  guards (`13-composer-core.md:1271-1272`, `15-new-thread-route.md:666`),
  so the phone never received the parity canvas the desktop got in the same
  wave.
- **Decision-5 amendment (user directive, 2026-09-19):** spec decision 5
  (`../../spec.md:29-31`) declared phone widths out of scope; this ticket
  amends that for this surface — the phone new-thread page gets the desktop
  treatment, and desktop (≥769px) behavior is untouched.
- What 33 provides by the time this runs: the repaired hero paint — the
  smoothstep-anchored mask ramp (G1), `min` compositing (G2), the frosted
  branch (G3/G4), and the four effect rasters (G5–G9). The motion/continuity
  repairs (the animated hero width/mask through a sidebar toggle G13/G14 +
  the titlebar island G15 → ticket 34; route-change hero continuity
  G16–G19 → ticket 35; the transition matrix G20–G24 → ticket 36) are
  inherited by the phone canvas as they land — not blockers here. This
  ticket MOUNTS that hero at phone; it must not re-implement any of it.
- What 50 provides: the `100dvh` viewport (URL-bar-stable hero height),
  `overscroll-behavior` containment, the phone titlebar inset, and the
  composer's safe-area bottom padding. What 49 provides (soft): the
  `PickerCard` phone branch that turns the canvas device/project chips'
  popovers into phone sheets.

## 2. Spec

### 2.1 The hero at phone (research M5(b), verbatim)

Desktop reference being adapted (all already ported; only the phone gates
flip):

- The hero element: `new_thread_background` (`shell.rs:857-914`) — absolute,
  top-left of the conversation column, full canvas width, height
  `min(viewport × 0.72, 760)` (`shell.rs:698-699`, `:852-855`), the
  double-pass cutout artwork, 120ms readiness fade. Web port:
  `components/new-thread-background.tsx` + `.new-thread-hero*`
  (`app.css:2495-2601`); the cutout mask is regenerated from a
  ResizeObserver on `#composer-surface` (`new-thread-background.tsx:108-
  150`) — width-agnostic, works at 375px.

Phone spec item 1 (verbatim):

1. Mount the hero at phone: change `heroVisible` to `(!hasSelection ||
   dockFrame.active)` (`routes/chat-page.tsx:565`) and delete the
   `display: none` (`app.css:6803-6805`). `heroWidth` already computes
   `viewport − sidebarNow` (`routes/chat-page.tsx:566`) — with M3/M2's
   `sidebarForGeometry = 0` fix that is exactly the phone canvas width.
   Hero height reuses the ported `new_thread_background_height` — on a
   667px phone that is 480px (0.72 ratio), which is the correct proportion
   under the centered composer.

Implementation note: `sidebarNow` at `chat-page.tsx:431` is a LOCAL
`sidebarTarget(sidebar)` call, not AppShell's branch — add the phone arm
here (`phone ? 0 : sidebarTarget(sidebar)`) or consume the shared helper if
50/52 exported one.

### 2.2 The composer's canvas position (research M5(b) item 2, verbatim, with the recorded choice)

- The composer's canvas position: `(viewportHeight − height) × 0.5 + 8`
  (`composer_dock.rs:380-385`, "Anchor by the top of the input surface").
  Web port exists (`lib/composer-dock.ts` prepaint, `routes/chat-page.tsx:
  509-524`) but is phone-disabled.
- The research's two mechanisms, verbatim:
  2. Center the composer: EITHER flip the phone arm of `dockReduced` so
     `dock.prepaint` runs at phone (`routes/chat-page.tsx:443`, `:509-524`)
     — the glide then also plays on phone, which the phone layer's "snap"
     principle argues against — OR (recommended, simpler) keep
     `dockReduced` and add a phone CSS rule that visually centers the
     wrapper's slot: `.persistent-composer { margin-block: auto }` inside
     the chat column flex, with the bottom stack at the column bottom. The
     exact choice is an open question (Q3) because the desktop answer is
     the animated dock.

**Choice recorded at filing (user directive, 2026-09-19): option 1 — the
dock re-anchors at phone.** Drop the `|| phone` arm of `dockReduced`
(`routes/chat-page.tsx:443` → `const dockReduced = reducedMotion;`) and the
`!phone` guard on the prepaint branch (`:509`), so `dock.prepaint` anchors
the wrapper at `(vh − h) · 0.5 + 8` on the canvas and the dock choreography
(glide, channels, dissolve) runs at phone exactly as at ≥769; reduced
motion still snaps everything through the existing `reducedMotion` arms.
Consequences to record: M5(b) item 4's "keep the phone snap for the
dissolve/handoff channels" assumed the recommended option 2 and is
superseded by this choice — route changes play the animated dock at phone.
If device testing shows the glide fighting the phone, the fallback is
option 2 (restore both gates, add the centering CSS) — a two-line swap;
record it in Comments.

**States:**

| state | condition | what changes |
| --- | --- | --- |
| hero mounted | `!hasSelection \|\| dockFrame.active` (`chat-page.tsx:565`, gate dropped) | hero paints at the full phone canvas width, height `min(0.72·vh, 760)` |
| composer canvas anchor | `!hasSelection` (undocked) | prepaint y = `(vh − h)·0.5 + 8`, cutout around the pill |
| composer docked | `hasSelection` | prepaint y = the bottom-stack slot (`bounds.top`) — the composer is docked bottom on an established chat |
| dissolving | dock frame active | hero opacity rides the `dissolve` channel on the dock's clock |
| reduced motion | `prefers-reduced-motion` | everything snaps (existing arms) |

**Motion:** the dock's existing choreography (ported by ticket 15, repaired
by 33) — unchanged code, now running at phone. The hero's own readiness
fade (120ms, `app.css:2510-2523`) still plays.

**Text:** none new — the canvas has no strings of its own (chips' labels are
existing: "This device"/"No project" fallbacks, `new-thread-selectors.tsx`).

**Data:** none new — reads the existing canvas target
(`useNewThreadTarget`, `new-thread-selectors.tsx:110-130`) and appearance
settings for the artwork.

### 2.3 The selector rows (research M5(b) item 3, verbatim + M9)

3. Show the selectors: delete `.dock-target-selectors { display: none }`
   (`app.css:6807-6809`). On phone the row sits 28px above the centered
   pill exactly as on desktop; the chips' popovers then need the M8
   responsive treatment (drawer on phone) to be usable.

The popover treatment is 49's `PickerCard` phone branch (the canvas chips
are the same `DeviceChip`/`ProjectChip` components as the footer's,
re-exported through `new-thread-selectors.tsx:110-130` — `PickerCard`
composes `RbPopover` in one place, `components/ui/PickerCard.tsx:100-117`,
so every consumer converts at once). Cross-ref 49; no per-site code here.

**M9 (the "project selector dialog shows on mobile but not on desktop"
inversion), per the research's conclusion — fix by construction:** M5 (this
ticket) re-shows `.dock-target-selectors` at phone and M8 (49) gives the
chips the drawer-at-phone treatment — the project selector then exists on
both widths with the same reachability (chips on the canvas), and the
footer's draft `ProjectChip` keeps its desktop popover contract
(`pickers.rs:2012-2124`) at ≥769. The M9 CSS-only fix (deleting
`app.css:6807-6809`) lands HERE. Residual honesty note from the research:
if the asymmetry persists after this + 49 land, the next suspect is the
popover's `anchorAboveEnd` placement (`composer-footer.tsx:360` +
`positioning.ts:119`) — which 49's phone drawer form also removes.

### 2.4 What stays (research M5(b) item 4 + M5(d) row 4)

- The status strip is unchanged (idle indicator, both routes) — already
  renders (`chat-page.tsx:772`).
- The footer Layer A git chips (checkout + ref) stay in the composer
  column's footer slot (`composer.tsx:2843-2852`).
- The phone bottom stack's anchor and the transcript outlet are untouched.

## 3. Pure logic to port

Almost nothing new ports (research §"Pure logic to port"): the mobile layer
is web-native and the desktop math is already ported.

- **Reuse, already ported:** `newThreadBackgroundHeight`
  (`lib/new-thread-background.ts:62` = `shell.rs:852-855`; the desktop
  asserts `shell.rs:8276-8280`: `400 → 288`, `600 → 432`, `1000 → 720`,
  `1200 → 760`) — the phone hero just mounts the existing port; the dock's
  canvas anchor `(vh − h) · 0.5 + 8` is already `lib/composer-dock.ts`
  (`prepaint`, `:475-481`).
- **The one new arm:** the hero-width sidebar term at phone —
  `sidebarNow` must read 0 at ≤768 (the sidebar is an out-of-flow overlay).
  If it lands as a tiny exported helper (or consumes 50/52's
  `sidebarForGeometry`), add the unit test
  `phone hero width uses the full viewport (sidebar out of flow)` →
  `web/packages/app/tests/new-thread-background.test.ts` (375/304 → 375,
  not 71). If it stays inline in `chat-page.tsx:431`, there is nothing to
  unit test — say so in Comments.
- The gate flips (`:565`, `:443`, `:509`) are component-internal one-liners;
  no unit tests, no desktop test maps to them (mobile-native rules — the
  research says so explicitly).
- Desktop tests that apply (rerun, do not duplicate): the
  `new_thread_background_height` table (above) and the dock/handoff suite
  in `tests/composer-dock.test.ts` (33/36 own additions there).

## 4. Gaps this ticket closes

From research M5(d), verbatim:

| item | kind | expected | web value (file:line) | fix |
| --- | --- | --- | --- | --- |
| hero mounted at phone | component | hero renders (min(0.72·vh, 760) tall) | `&& !phone` gate + `display: none` (`chat-page.tsx:565`; `app.css:6803-6805`) | remove both |
| composer position | geometry | centered: `(vh − h)/2 + 8` | bottom-stack slot, transform cleared at phone (`chat-page.tsx:509-524`) | dock prepaint at phone, or centered flex + snapped dock |
| selector rows | component | device+project chips above the pill | `display: none` (`app.css:6807-6809`) | remove; chips get M8 drawer treatment |
| status strip | component | unchanged (idle indicator, both routes) | already renders (`chat-page.tsx:772`) | — |

From research M9(d), verbatim (the CSS half closes here; the popover phone
form is 49's):

| item | kind | expected | web value (file:line) | fix |
| --- | --- | --- | --- | --- |
| canvas project selector, phone | component | chips visible (drawer-form popovers) | `display: none` (`app.css:6807-6809`) | M5 + M8 (delete the rule) |
| which surface the user meets | behavior | same selector both widths | phone: only the uncommitted footer's `role="dialog"` popover (`composer-footer.tsx:141-142`, `:356-368`) | superseded by the above |

Consolidated gap table rows: M5 hero at phone, M5 composer position, M5
selector row, M9 project selector phone — same content.

## 5. Do not

- Do not change desktop (≥769px) behavior: the canvas, hero, selectors, and
  dock already work there (tickets 15/33); only the ≤768 gates and rules
  flip.
- Tickets 33/34/35/36 own the hero paint, sidebar-slide/island, route-change
  continuity, and the transition matrix — mount their work, do not
  re-implement or fork the hero for phone.
- Ticket 49 owns the chips' popover phone form (`PickerCard`'s one-place
  branch) — do not build per-chip phone popovers here.
- Ticket 50 owns the viewport baseline (100dvh, safe-area, containment) and
  the phone titlebar inset — consume; do not duplicate.
- Ticket 39 owns the send-path — the first send from the phone canvas must
  keep working; do not touch send logic. (M10's broken-send flow is owned by
  the spaces-sidebar-mirroring work — its display site
  `chat-page.tsx:702-710` is the symptom, not this ticket's to fix.)
- Tickets 40/41 own transcript behavior at all widths (the departing
  transcript, geometry) — do not duplicate.
- Ticket 15 owns the canvas implementation — this ticket only flips phone
  gates; do not restructure `NewThreadCanvas`/`Composer` for phone.
- The footer's draft `ProjectChip` keeps its desktop popover contract at
  ≥769 (`pickers.rs:2012-2124`) — no phone-only popover variant.
- Do not re-add INVENTED UI: no phone-only artwork variants, no new captions
  or chrome beyond the spec; the hero's effect rasters and appearance
  settings are 33's / ticket-15's deviation records.
- Reduced motion: everything snaps — the existing arms cover the dock,
  readiness, and handoff; do not add phone-specific reduced-motion rules.

## 6. Acceptance

- [ ] At 375px, new chat on phone shows background + hero + composer docked
      bottom — precisely: the background hero paints (min(0.72·vh, 760)
      tall — 480px at 667), the composer is anchored by the dock at the
      canvas position `(vh − h)·0.5 + 8` with the cutout around the pill,
      and on an established chat the composer is docked at the bottom slot
      exactly as today.
- [ ] The device + project selector chips render 28px above the pill and
      open usable popovers (49's phone sheet form; at minimum, clamped
      floating cards if 49 has not landed).
- [ ] The status strip and git footer chips still render (both routes).
- [ ] Sending from the phone canvas hands off to the established chat (the
      dock choreography; snapped under reduced motion); the hero dissolves.
- [ ] At ≥769px nothing changes.
- [ ] Unit tests: `phone hero width uses the full viewport (sidebar out of
      flow)` → `tests/new-thread-background.test.ts` (if helper-ized);
      existing `new-thread-background` + `composer-dock` suites stay green.
- [ ] Screenshot: `use-browser` at 375×667 against `web_smoke`, states:
      "new-chat canvas with a background image installed" (hero + cutout +
      selectors + composer), "new-chat canvas, no background" (the plain
      `--rb-bg` form), "established chat after first send"; plus a ≥769px
      capture (desktop pair) unchanged.
- [ ] `pnpm -r build` green; package vitest green.
- [ ] No new literal hex/px where a `--rb-*` token exists (the hero
      geometry reuses the ported constants; no new numbers).

## Comments

(empty; appended during implementation)
