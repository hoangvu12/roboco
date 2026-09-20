# 74 — Composer input geometry during docking and control overlap

**What to build:** The text input retains the desktop's padding, readable line viewport and continuous origin while the composer moves between the new-chat canvas and a selected chat. At rest, the last draft lines remain above the model/attachment/send controls. Establish the reported overlap with actual bounds before changing settled CSS; the source audit already identifies specific route-geometry differences to correct.

**User report (verbatim):** "the composer… no padding on the textarea… text covered by the bottom section (model picker and everything)"

**Blocked by:** None — can start immediately. Coordinate edits in `composer.tsx` with 64 (measurement cadence) and 75 (keyboard handler); these are ownership overlaps, not semantic blockers.

**Status:** ready-for-agent
**Type:** task — confirmed route-parity repair with a diagnosis gate for the reported overlap
**Baseline:** `web-parity/wave-2` @ `37c354ff`.
**Research:** `../research-2026-09-20/followup-composer-input.md` §§1–5. All required tables are copied below; the reader need not load that file.
**Desktop reference (for lookups only):** `crates/ui/src/composer.rs::Composer::render` (:7589-7638,7718-7805), `dock_morph_restores_skinny_height_with_a_continuous_editor_origin` (:8045).

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/components/composer.tsx` | edit | Scoped evaluator region :725-948, live style cleanup :950-966, pill/input/actions JSX :2837-2884; route geometry only |
| `web/packages/app/src/lib/composer-flip.ts` | edit | Pure route-layout calculation alongside existing morph/padding/glide helpers |
| `web/packages/app/src/styles/app.css` | conditional edit | Only composer body/input/actions selectors :3335-3507 if measured DOM evidence demonstrates a residual CSS defect |
| `web/packages/app/tests/composer-flip.test.ts` | edit | Route-clock and viewport-floor numeric regression cases |
| `web/packages/app/tests/dock-glide.test.ts` | conditional edit | Existing DOM-write/publication guard if the new live channels change its contract |
| `.scratch/web-parity/issues/74-composer-input-geometry.md` | append | Reproduction, measurements, validation and remaining limitations in Comments |

## 1. Context a fresh session needs

- Roboco web is `web/packages/app`; native desktop is `crates/ui`. Use **chat**, **space**, **engine**, **harness** in prose. Session means pairing credential in product text.
- One mounted textarea is reused across compact/expanded modes. Losing its identity can drop focus, caret, IME and scroll, so preserve the one-shape DOM.
- `newChat = chat.id === ""` forces expanded rendering. `sessionExpanded` is the composer's own expanded state, not the canvas-forced value (`composer.tsx:461-473`).
- A layout pass measures wrapped content and unwrapped widest-line width, applies existing flip hysteresis, calculates pill/input geometry and writes textarea height (`:725-899`).
- During a live dock glide, the page writes a shared frame ref and calls this evaluator. Heights are written through CSS custom properties, while React `layout` is intentionally stale until settle (`:900-966`).
- The current active writes cover pill height/radius and expanded input-box height. Padding, cluster offset/inset and compact text glide still come from the last published `layout` object in JSX (`:2871-2884`).
- At rest, `.composer-body` already has `flex: 1 1 auto; min-height: 0`, and the actions row is 46px. Ticket 37's fix is present. The textarea's `padding: 0` is intentional; the containing box owns padding.
- Current source proves route parity differences, not the exact user-visible persistent overlap. Browser bounds are necessary to distinguish stale animation channels, wrong containing block, strips, clipping or an old served bundle.
- The engine embeds the built web bundle. Before visual comparison, record the running executable/bundle revision; a source checkout at this commit does not establish what a running app serves.
- Read the named source windows, not the entire ~3000-line composer. The ticket owns geometry; 64 owns eliminating repeated layout/state work, and 75 owns Enter policy.
- Web stays opaque. Existing radii/colors, route choreography, phone gutters and desktop keyboard behavior remain the shared contract.

## 2. Spec

### 2.1 Diagnose the reported overlap before broadening the fix

Use an already running authorized app or coordinator/user capture. Record viewport, DPR, browser, selected/new chat, compact/expanded mode, frame activity/amount, draft length, attachments/comments and keyboard visibility.

Capture the pill, body, input box, textarea and actions `getBoundingClientRect()` values plus computed padding, scrollHeight/clientHeight/scrollTop, CSS dock custom properties and actual clipping ancestors. At rest with no strips, the body bottom and pill content bottom must agree. The input box budget is pill minus borders minus actions; adding textarea padding changes its measured scrollHeight and is not a neutral fix.

Record a before/after state for empty canvas, four-line draft, capped long draft, and compact route transition. If the reported steady-state overlap cannot be reproduced, keep that limitation explicit. The confirmed route mismatches below can still be fixed, but do not label the entire report resolved solely from pure arithmetic.

### 2.2 Desktop geometry contract (verbatim research table)

| Property | Required value / rule | Source at `37c354ff` |
| --- | --- | --- |
| Expanded input box | Horizontal padding 16px; top padding `morphTextPad(layoutProgress)`; bottom padding 4px | `crates/ui/src/composer.rs:7743-7749`; `web/packages/app/src/styles/app.css:3389-3393` |
| Text metrics | 14px font, 22.75px line height; textarea itself has zero padding because its containing box owns it | `crates/ui/src/composer.rs:98-99`; `web/packages/app/src/styles/app.css:3418-3428` |
| Expanded rest height | Input box clamped to 76..260px; pill = box + 46px actions + 2px borders = 124..308px, before strips | `web/packages/app/src/lib/composer-flip.ts:39-55`; `crates/ui/src/composer.rs:46-66` |
| Compact rest | Pill 49px; bottom-justified body 47px; one input line 22.75px | `crates/ui/src/composer.rs:70,7801-7805`; `web/packages/app/src/styles/app.css:3355-3364` |
| Actions placement | Expanded: absolute left/right 0, bottom `-morphClusterDy(layoutProgress)`, 46px high, padding top 4 / bottom 10 / left 12 / morphing right inset 8..12px | `crates/ui/src/composer.rs:7752-7769`; `web/packages/app/src/components/composer.tsx:2880-2884` |
| Actions containing block | Desktop pill; web body stretches to the pill's content bottom using flex 1 and min-height 0 | `crates/ui/src/composer.rs:7734-7757`; `web/packages/app/src/styles/app.css:3367-3372` |
| Route layout clock | While dock frame active and destination chat mode is compact: expanded render uses `1 - dockAmount`, compact render uses `dockAmount`; otherwise local flip progress | `crates/ui/src/composer.rs:7592-7601` |
| Animated expanded box | `max(pillHeight - stripHeight - 2 - 46, routeToSingleLine ? 22.75 + textPad + 4 : 0)`; web strips are attachment + comments, no native appshot strip | `crates/ui/src/composer.rs:7604-7616`; web currently floors only at 0 at `components/composer.tsx:880` |
| Input viewport | Expanded: `max(boxHeight - textPad - 4, 0)`; compact: 22.75px | `crates/ui/src/composer.rs:7617-7622`; `web/packages/app/src/components/composer.tsx:882-883` |
| Settled expanded viewport | `max(baseHeight - 2 - 46 - 20, routeToSingleLine ? 22.75 : 0)`; compact settled viewport is 22.75px | `crates/ui/src/composer.rs:7623-7632`; web currently uses a zero floor at `components/composer.tsx:893` |
| Compact route text glide | During active route morph to/from a compact chat use `collapseTextGlide(dockHeight(0), dockAmount)`; otherwise retain the local flip glide | `crates/ui/src/composer.rs:7793-7800`; web currently uses local flip only at `components/composer.tsx:930-935` |
| Phone boundary / gutter | Phone is <=768px through `useIsPhone()`; outer composer gutter 12px; pill's inner 16px inset is unchanged | `web/packages/app/src/state/media.ts:25-46`; `.scratch/web-parity/issues/51-mobile-composer-transcript-padding.md` |
| Scroll affordance | Native textarea scrolling when content exceeds viewport; native bar hidden; 12px fade ramps only for settled overflow | `crates/ui/src/composer.rs:181-192,2898-2933`; `web/packages/app/src/styles/app.css:3431-3456` |

**Children, in order:** Existing comments chip, attachment strip, composer body containing input box then actions, and absolute measurement mirror. Preserve this order and the textarea element. Expanded actions use the stretched body's bottom as their containing-block anchor.

### 2.3 State contract (verbatim research table)

| State | Source of height / inner geometry | Required behavior | Source |
| --- | --- | --- | --- |
| New-chat canvas settled | Always expanded; measured content height; local typing morph | Preserve 124px empty minimum and grow up to 308px before strips | `composer.tsx:794-830`; `composer.rs:7488-7500` |
| Existing chat settled | Own expanded state, independent of canvas-forced expanded state | Compact single line or expanded draft according to existing hysteresis | `composer.tsx:461-473,785-805`; `lib/composer-flip.ts:112` |
| Active dock, compact destination | Live dock frame supplies amount; route clock supplies all related inner values | Pill, input box, text padding, controls and text glide describe the same frame | `composer.rs:7589-7638,7793-7800` |
| Active dock, expanded destination | Live dock supplies height; preserve desktop local inner-layout rule | Keep a multiline draft visible; do not impose compact route geometry | `composer.rs:7592-7601` |
| Dock settles | One published layout becomes authoritative and temporary CSS values are cleared before paint | No jump back to a stale pre-glide padding/height/inset | `composer.tsx:950-966` |
| Typing while a route morph runs | Latest draft and current frame are both inputs | No stale text measurement; input element, focus, selection and scroll survive | `composer.tsx:725-744,819-830`; `composer.rs:8045-8088` |
| Wizard borrows input | Wizard owns its input height, not the pill evaluator | Preserve existing wizard auto-grow; pill geometry work stands down | `composer.tsx:731-734,1860-1870` |

### 2.4 Motion contract (verbatim research table)

| Motion | Trigger | Existing spec / clock | Required target | Reduced motion |
| --- | --- | --- | --- | --- |
| Local grow / compact flip | Draft measurement changes or flip commits | Existing 180ms COLLAPSE ease-out; existing morph helpers | Current painted height to latest content target, retaining reversal | Snap; preserve existing route-snap rule |
| Route dock / undock | Canvas <-> selected chat | Existing shared dock clock, not a new timer; progress from live frame | Match desktop route layout formulas in the geometry table | Land all inner/outer channels at same endpoint |
| Actions movement | Local flip or compact route handoff | Same layout progress as text padding | Stationary bottom anchor with existing 2.5px center correction and right-inset interpolation | Endpoint directly |
| Scroll fades | Settled content exceeds input viewport | Existing 12px masks, no new animation | Avoid treating transient morph clipping as settled overflow | Unchanged static mask |
Sources: `web/packages/app/src/lib/composer-flip.ts:429-524`, `web/packages/app/src/lib/composer-dock.ts`, `crates/ui/src/composer.rs:7589-7638,7718-7800`.

**Text:** No new user-facing strings or controls. Existing placeholder, picker labels, attachment and send labels remain intact.

### 2.5 Data and ownership (verbatim research table)

| Concern | Contract | Source |
| --- | --- | --- |
| Draft ownership | Controlled textarea plus existing per-chat drafts; no chat/storage migration | `composer.tsx:488-509,2727-2745` |
| Settings | Read existing `ComposerSendBehavior`; phone override is derived in memory, never saved over the user's desktop preference | `composer.tsx:326-328,2256-2263`; `lib/composer-send.ts:181-194` |
| Phone detection | Import existing `useIsPhone` from `state/media.ts`; no UA sniffing or new pointer heuristic | `state/media.ts:25-46` |
| Send operations | Native newline invokes no Run, QueueMessage, queue-edit finish, wizard response, optimistic echo or attachment upload | User report 8; current submission dispatch at `composer.tsx:2411,2424,2435` |
| Layout calculation | Existing content/strip measurement and live dock frame; DOM styling only, no engine RPC | `composer.tsx:725-938` |
| Native input | Reuse the same textarea through compact/expanded and borrowed wizard states; preserve composition and selection | `composer.tsx:2722-2745`; `composer.rs:8045-8088` |

For this ticket, keyboard/settings rows are invariants owned by 75, not an instruction to implement that ticket.

### 2.6 Bounded implementation sequence

1. Record source/bundle identity and the measured failure state. Separate settled overlap from route-only clipping.
2. Introduce one pure calculation for the missing route geometry next to existing flip helpers. Inputs must include rendered expanded mode, destination expanded mode, active dock amount, local flip progress, pill/base heights and strip height. Reuse existing constants/helpers. Output the inner progress, text padding, input-box/input/settled viewport sizes, cluster offset/inset and text glide needed by the renderer.
3. Port the Rust conditions literally: `routeToSingleLine = frame.active && !sessionExpanded`; route progress depends on rendered expanded mode. Preserve local typing-flip and expanded-destination rules.
4. Use the same calculation for active DOM writes and settled React layout. Extend live custom properties only where required (text padding, cluster offset/inset and compact glide), with JSX fallbacks consistent with existing height channels. A single active frame must not mix live heights with old padding.
5. Consume active values in both expanded and compact DOM styles. A mode switch during a glide must not retain an expanded-only variable that distorts compact mode.
6. At settle publish the final layout once, then clear every temporary property in that same pre-paint commit. Teardown, interrupted/reversed glide, reduced-motion change and wizard takeover must leave no stale inline override.
7. Retain the existing single textarea, cursor and IME handling, scrollbar/fade policy, strip budget and destination bottom clearance. Do not add frame-driven React state.
8. Change settled CSS only if measurements from step 1 identify a specific defect. Re-check empty/four-line/capped content with and without strips. Document any remaining discrepancy rather than adding guessed padding.
9. Run the named tests/build and attach browser evidence. If runtime access is unavailable, leave the corresponding acceptance items pending and report code-level progress separately.

## 3. Pure logic to port

Suggested local signature (name is flexible; use one owner):

```ts
type RouteInputGeometry = {
  layoutProgress: number;
  textPad: number;
  boxHeight: number;
  inputHeight: number;
  settledViewport: number;
  clusterDy: number;
  clusterInset: number;
  textGlide: number;
};
// Inputs: renderedExpanded, sessionExpanded, active dock frame/amount,
// local flip progress/glide, pillHeight, baseHeight, stripHeight,
// undocked base height. Existing helpers supply padding/insets/glides.
```

The geometry table gives the formulas. Port only the missing branch; `composerTotalHeight`, `morphTextPad`, `morphClusterDy`, `morphClusterInset` and `collapseTextGlide` already exist. Do not fork their constants.

| Existing desktop test | Existing web guard / new coverage |
| --- | --- |
| `dock_morph_restores_skinny_height_with_a_continuous_editor_origin` (`composer.rs:8045`) | Add route samples 0,.2,.6,.98,1 in both directions to `composer-flip.test.ts`; actual editor origin and same textarea identity require browser evidence |
| `morph_anchoring_holds_controls_and_glides_text` (:9257) | Keep `steady state rests; the commit instant starts from the old geometry` (`composer-flip.test.ts:430`) |
| `route_change_never_arms_the_morph` (:9232) | Keep `a flip inside the route-snap window snaps, and kills anything in flight` (:418) |
| `flip_morph_reverse_hands_off_from_current_height` (:9208) | Keep `a reverse flip mid-flight commits a new morph FROM the animated height` (:399) |

New tests must check output invariants, not source-text occurrence: one-line floor during compact route, no floor imposed on ordinary settled empty calculation, correct route direction, multiline destination using the existing branch, attachment/comment subtraction exactly once, endpoint equality, reversed trajectory and temporary-channel cleanup.

For the no-strip single-line browser scenario the Rust rendered test expects editor y offset approximately `17 - 4*amount` from the surface (1px tolerance), constant input identity and destination footprint, and pill height lerping 124 to 49. Do not claim a jsdom numeric mock proves this rendered result.

## 4. Gaps this ticket closes

| Item | Kind | Desktop value | Web value (file:line) | Fix |
| --- | --- | --- | --- | --- |
| Route inner clock | Confirmed source mismatch | Dock amount drives layout progress for compact route transitions | `composer.tsx:865-881` uses local flip progress only | Derive the desktop route layout progress and consume it for every inner channel |
| Route input floor | Confirmed source mismatch | Keep at least one line plus padding in active compact route transitions | `composer.tsx:880-883,893` floors box/settled viewport at zero | Port desktop conditional floors, preserving existing settled limits |
| Compact route editor origin | Confirmed source mismatch | Route-specific text glide maintains editor origin | `composer.tsx:930-935` handles local flip only | Apply route glide from same live dock amount |
| Animated inner style publication | Confirmed implementation structure; visual failure unmeasured | All geometry comes from current render frame | `composer.tsx:909-921,2871-2884` writes live height but retains published padding/insets | Publish consistent live inner values without React state per frame |
| Reported settled overlap | Unresolved symptom | Input clipped above controls with correct padding | Current CSS already stretches body and reserves 46px (`app.css:3367-3372,3496-3501`) | Measure real bounds before any additional CSS fix; do not claim absent padding from textarea's intentional zero padding |

## 5. Do not

- Do not add arbitrary padding to the textarea; derive spacing from its box and the desktop formulas.
- Do not reintroduce per-frame React updates or remount the textarea to force a layout reset.
- Do not rewrite send/queue/wizard behavior, catalog loading, backdrop treatment, or the route clock.
- Do not change native Rust implementation; it is the reference here.
- Do not treat tests named for pure geometry as browser layout verification.
- Do not start long-running `pnpm dev`, `cargo run`, smoke servers or browser processes from a subagent. Request/use coordinator-managed runtime evidence instead.

## 6. Acceptance

- [ ] Before/after measurements identify whether report 7 is settled overlap or route-only geometry; unobserved cases are explicitly listed.
- [ ] Four-line draft at rest: input and actions have distinct visible regions, 16px horizontal input inset and existing top/bottom padding; last line is readable.
- [ ] Long draft scrolls to its final line without text painting under controls; fades and hidden scrollbar match desktop.
- [ ] Empty canvas remains 124px before strips; compact settled pill remains 49px; expanded cap remains 308px before strips.
- [ ] Route samples in both directions use the desktop clock/floors/glide; the original textarea, focus, caret and selection survive.
- [ ] Active and settled inner style channels agree; no leftover dock override after settle, reversal, wizard takeover or reduced motion.
- [ ] Attachments and review comments consume their existing strip budget exactly once; no new empty band appears below actions.
- [ ] Desktop/web screenshot or frame pairs cover empty canvas, four-line/capped drafts, compact route samples and a draft with strips. Phone checks at 375px and 768px, desktop checks at 769px and a wide viewport; keyboard-open phone capture is included.
- [ ] Pure regression tests pass; browser evidence is recorded separately because Vitest node has no layout engine.
- [ ] From `web/`: `pnpm -r build`. From `web/packages/app/`: `pnpm test`. No Rust build is required unless scope changes to Rust.
- [ ] No new literal colors, per-frame React pump, input remount or phone/desktop send-policy regression.
- [ ] If any visual check is unavailable, its checkbox stays open and the ticket is not represented as fully verified.

## Comments

### 2026-09-20 — agent (wp-fu/74)

**Reproduction status:** NOT reproduced. §2.1's browser measurements could not be gathered (no browser/runtime access from this session; no dev server, engine, or browser was started, per the do-not list). The reported steady-state overlap ("text covered by the bottom section") remains unobserved; the source audit's settled-CSS reading stands (`.composer-body` flex 1/min-height 0, 46px actions row reserved, textarea `padding: 0` intentional — `app.css:3406-3440,3535-3541`), so **no settled CSS was changed** and no guessed padding was added.

**Measurement plan (pending, needs a browser):** on an already-running authorized app, record viewport/DPR/browser, selected vs new chat, compact/expanded mode, frame active/amount, draft length, strips, keyboard visibility; capture `getBoundingClientRect()` for pill/body/input-box/textarea/actions plus computed padding, scrollHeight/clientHeight/scrollTop, the `--rb-dock-*` custom properties, and clipping ancestors — before/after for empty canvas, four-line draft, capped draft, and a compact route transition. Also record the running executable/bundle revision before comparing (the engine embeds the built bundle). Acceptance checkboxes needing this evidence stay unchecked: items 1–5 (partially), 7, 8 and the screenshot-matrix item.

**What landed (source-proven route-parity repairs only):**
- `lib/composer-flip.ts`: new pure `routeInputGeometry()` porting composer.rs:7589-7632/7723/7767/7835/7793-7800 literally — route layout clock (`frame.active && !sessionExpanded` → expanded render reads `1 − dockAmount`, compact render reads `dockAmount`; else local flip progress), compact-route input floors (box ≥ `22.75 + textPad + 4`, settled viewport ≥ one line), cluster dy/inset on the same clock, and the compact route text glide `collapseTextGlide(dockHeight(0), dockAmount)`. Existing helpers/constants reused; none forked.
- `components/composer.tsx`: the evaluator now resolves all inner channels from that one calculation for BOTH the live DOM writes and the published `layout`. Live CSS vars extended (written in both modes so a mid-glide mode switch never catches a channel missing): `--rb-dock-text-pad`, `--rb-dock-text-glide` on the input box, `--rb-dock-cluster-dy`, `--rb-dock-cluster-inset` on the actions row (new `actionsRef`); dy/glide vars carry the pre-negated offsets. The `[layout]`-commit cleanup now removes all seven temporary vars in the same pre-paint commit; teardown/wizard-takeover leave no overrides (elements unmount). JSX pill/input-box/actions consume every channel as `var(--…, fallback)`. No per-frame React state; textarea identity, measurement/publication cadence (64), Enter policy (75) untouched.
- `tests/composer-flip.test.ts`: +8 route-geometry cases — samples 0/.2/.6/.98/1 both directions on the desktop test's own scenario (one-line draft, pill lerping 124→49), one-line floor engagement at .6/.98 vs raw budget at 0/.2, expanded render's reversed clock (`1 − amount`), endpoint equality at both settle instants (dock-endpoint box-height delta is expanded-only and unconsumed by compact JSX), reversed-trajectory frame-purity, expanded destination keeping the local clock, strips subtracted exactly once, no floor on the ordinary settled calculation.
- `tests/dock-glide.test.ts`: +1 source guard — each new inner channel is written, consumed as var-with-fallback, and removed by the settle cleanup.

**Validation:** `pnpm -r build` (web/) passes; `pnpm test` (web/packages/app) passes: 91 files / 1496 tests (composer-flip 37, dock-glide 20). The known registry.test.ts 1ms timing flake did not appear.

**Remaining limitations:** the user-reported settled overlap is neither confirmed nor excluded — the four confirmed route-geometry mismatches are repaired, but per §2.1 the full report is not "resolved" on arithmetic alone. Rust-rendered expectations the jsdom tests cannot prove (editor y ≈ `17 − 4·amount`, same-textarea identity, destination footprint) are pending browser evidence, as are all visual acceptance items.


