# Follow-up research — composer geometry and phone Enter

Baseline: `web-parity/wave-2` @ `37c354ff`, inspected 2026-09-20.
Reports: 7 (text covered by composer controls), 8 (phone Enter should insert a newline).
Method: local web and Rust source inspection, prior tickets 13/37/51/57, desktop tests, and primary browser documentation. No runtime reproduction, edits to implementation, builds or tests were performed for this research.

## 1. Findings and limits

The existing settled CSS is not missing its padding: the textarea intentionally has zero padding and the input box supplies it. Ticket 37's full-height body fix is present. A blanket bottom-padding addition would double-count the actions reservation and invalidate measured text heights.

There ARE source-level differences during dock transitions: web uses local flip progress instead of the desktop route layout clock, misses the one-line route floors, and retains local-flip-only compact text glide. Ticket 57's direct writes update heights while several inner values remain in the last published React layout. These are parity discrepancies, not proof of the reported persistent overlap. Ticket 74 requires measurements before calling the user symptom fixed.

Phone Enter has a direct source cause: the same desktop binding is applied at all widths. The target below adopts the existing phone-layer boundary, preserves selected completion/IME handling, and consistently treats the focused borrowed textarea as text entry. This is an implementation interpretation of the user's phone newline directive, not a claim about a nonexistent native phone layout.

Review found a necessary companion change: the existing wizard advance button calls `wizardAdvance` without committing the textarea draft (`composer.tsx:1756-1767,2825`); only `wizardSubmitFromInput` calls `wizard.setTyped` (:1806-1812). Phone explicit advance (button or unfocused panel Enter) must commit current trimmed text before advancing and cancel a pending auto-advance timer. Otherwise replacing Enter submission with newline would discard free-text answers. `lib/wizard.ts:127-138` already preserves internal newlines and gives nonempty typed answers precedence over options.

## 2. Source map and desktop contract

- `web/packages/app/src/components/composer.tsx:725-948`: measurement, flips, dock ownership, height writes and state publication.
- `composer.tsx:950-993`: clearing dock CSS values and textarea ResizeObserver.
- `composer.tsx:2341-2440`: IME/completion/wizard/send keyboard ordering.
- `composer.tsx:2722-2745,2837-2884`: shared textarea and rendered geometry.
- `web/packages/app/src/styles/app.css:3335-3507`: pill/body/box/actions CSS.
- `crates/ui/src/composer.rs:7589-7638,7718-7805`: current Rust layout formulas.
- `crates/ui/src/composer.rs:8045-8088`: rendered editor-origin regression, not just a math test.
- `crates/ui/src/composer.rs:1364-1389,8330-8407`: native Enter and completion contracts.
- `web/packages/app/src/state/media.ts:25-46`: one shared phone boundary.
- [MDN enterkeyhint](https://developer.mozilla.org/en-US/docs/Web/HTML/Reference/Global_attributes/enterkeyhint): controls keyboard label/icon; it does not override the app's keydown submission handler.

## 3. Contracts to copy into tickets

### 3.1 Geometry
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

### 3.2 Geometry states
| State | Source of height / inner geometry | Required behavior | Source |
| --- | --- | --- | --- |
| New-chat canvas settled | Always expanded; measured content height; local typing morph | Preserve 124px empty minimum and grow up to 308px before strips | `composer.tsx:794-830`; `composer.rs:7488-7500` |
| Existing chat settled | Own expanded state, independent of canvas-forced expanded state | Compact single line or expanded draft according to existing hysteresis | `composer.tsx:461-473,785-805`; `lib/composer-flip.ts:112` |
| Active dock, compact destination | Live dock frame supplies amount; route clock supplies all related inner values | Pill, input box, text padding, controls and text glide describe the same frame | `composer.rs:7589-7638,7793-7800` |
| Active dock, expanded destination | Live dock supplies height; preserve desktop local inner-layout rule | Keep a multiline draft visible; do not impose compact route geometry | `composer.rs:7592-7601` |
| Dock settles | One published layout becomes authoritative and temporary CSS values are cleared before paint | No jump back to a stale pre-glide padding/height/inset | `composer.tsx:950-966` |
| Typing while a route morph runs | Latest draft and current frame are both inputs | No stale text measurement; input element, focus, selection and scroll survive | `composer.tsx:725-744,819-830`; `composer.rs:8045-8088` |
| Wizard borrows input | Wizard owns its input height, not the pill evaluator | Preserve existing wizard auto-grow; pill geometry work stands down | `composer.tsx:731-734,1860-1870` |

### 3.3 Motion
| Motion | Trigger | Existing spec / clock | Required target | Reduced motion |
| --- | --- | --- | --- | --- |
| Local grow / compact flip | Draft measurement changes or flip commits | Existing 180ms COLLAPSE ease-out; existing morph helpers | Current painted height to latest content target, retaining reversal | Snap; preserve existing route-snap rule |
| Route dock / undock | Canvas <-> selected chat | Existing shared dock clock, not a new timer; progress from live frame | Match desktop route layout formulas in the geometry table | Land all inner/outer channels at same endpoint |
| Actions movement | Local flip or compact route handoff | Same layout progress as text padding | Stationary bottom anchor with existing 2.5px center correction and right-inset interpolation | Endpoint directly |
| Scroll fades | Settled content exceeds input viewport | Existing 12px masks, no new animation | Avoid treating transient morph clipping as settled overflow | Unchanged static mask |
Sources: `web/packages/app/src/lib/composer-flip.ts:429-524`, `web/packages/app/src/lib/composer-dock.ts`, `crates/ui/src/composer.rs:7589-7638,7718-7800`.

### 3.4 Keyboard states
| Focus / condition | Phone layer (<=768px) target | Desktop-width target | Evidence / basis |
| --- | --- | --- | --- |
| IME composing | Leave event to IME; never submit | Existing behavior | `composer.tsx:2341-2343`; user report 8 |
| Enter with selected completion | Accept completion once; never submit as a second action | Existing behavior | `composer.tsx:2391-2398`; `composer.rs:1407,8384` |
| Bare Enter in ordinary textarea, no completion | Native newline, regardless of saved Enter-to-send preference | Saved `enter` submits; saved `modEnter` inserts newline | User report 8; existing policy `composer.tsx:2415-2438`, `lib/composer-send.ts:181-194` |
| Bare Enter while editing a queued draft | Native newline; leave edit open | Existing desktop policy | Same shared textarea and user report 8 |
| Bare Enter in focused wizard borrowed textarea, no completion | Native newline; do not advance wizard | Existing wizard submit policy | Shared input at `composer.tsx:2722-2745,2820-2827`; current wizard Enter at `2400-2413` |
| Shift+Enter / Alt+Enter, no completion | Native newline | Existing native newline | `composer.tsx:2437-2438` |
| Ctrl/Cmd+Enter in message textarea, no completion | Preserve ModifiedSubmit: send content, or activate latest queued row when truly empty | Same | `composer.tsx:2418-2428`; `lib/composer-send.ts:83-88` |
| Ctrl/Cmd+Enter in wizard input | Preserve existing suppression | Same | `composer.tsx:2403-2406` |
| Explicit phone wizard advance (tap or unfocused panel Enter) | Commit current trimmed text to wizard before advancing; cancel pending auto-advance timer so the action occurs once | Preserve current desktop behavior | `composer.tsx:1756-1767,1806-1812,2471-2475,2825`; current button path otherwise discards uncommitted text |
| Enter on other focused buttons / outside textarea | Existing button behavior | Same | No global keyboard interception |
| Explicit Send / Queue / Stop button | Existing action, eligibility and RPC path | Same | `lib/composer-send.ts:25-30,64-71`; `components/composer.tsx:2897-2920` |
| Phone media query changes while input remains mounted | Recompute policy immediately, retain draft/focus/selection and saved preference | Resume saved desktop policy above 768px | `state/media.ts:25-46` |
| Virtual keyboard action label | `enterKeyHint="enter"` on phone textarea | Preserve current desktop attribute behavior | MDN enterkeyhint is a label hint, not an event-policy replacement |

### 3.5 Data / ownership
| Concern | Contract | Source |
| --- | --- | --- |
| Draft ownership | Controlled textarea plus existing per-chat drafts; no chat/storage migration | `composer.tsx:488-509,2727-2745` |
| Settings | Read existing `ComposerSendBehavior`; phone override is derived in memory, never saved over the user's desktop preference | `composer.tsx:326-328,2256-2263`; `lib/composer-send.ts:181-194` |
| Phone detection | Import existing `useIsPhone` from `state/media.ts`; no UA sniffing or new pointer heuristic | `state/media.ts:25-46` |
| Send operations | Native newline invokes no Run, QueueMessage, queue-edit finish, wizard response, optimistic echo or attachment upload | User report 8; current submission dispatch at `composer.tsx:2411,2424,2435` |
| Layout calculation | Existing content/strip measurement and live dock frame; DOM styling only, no engine RPC | `composer.tsx:725-938` |
| Native input | Reuse the same textarea through compact/expanded and borrowed wizard states; preserve composition and selection | `composer.tsx:2722-2745`; `composer.rs:8045-8088` |

## 4. Tests and verification

Existing app suites use Vitest node (`web/packages/app/vitest.config.ts:4-5`), pattern `tests/*.test.ts`; no DOM layout engine is configured. Pure tests cannot establish actual textarea/control bounds, browser newline defaults or a virtual keyboard's behavior.

Ticket 74: mirror the intent of Rust `dock_morph_restores_skinny_height_with_a_continuous_editor_origin` (:8045). Its amount samples in both directions are 0, .2, .6, .98, 1. It checks same input entity, editor y offset from surface approximately `17 - 4*amount` (1px tolerance), continuous pill height and destination clearance. Web math tests belong beside `composer-flip.test.ts`; actual origin/bounds require browser evidence. Existing guards: `morph_anchoring_holds_controls_and_glides_text` (Rust :9257) maps to web test `steady state rests; the commit instant starts from the old geometry` (:430); Rust `route_change_never_arms_the_morph` (:9232) maps to `a flip inside the route-snap window snaps, and kills anything in flight` (:418).

Ticket 75: existing `composer-send.test.ts:108,127` tests `the setting picks the bare-Enter policy; the modifier combo is verbatim` and `exactly two bindings, no shift/alt variants`. Keep these desktop tests intact; add a phone policy matrix that drives the actual handler branch. Rust names: `message_enter_bindings_cover_both_platform_modifiers` (:8330), `message_enter_never_adds_extra_modifier_bindings` (:8373), `enter_accepts_a_completion_before_submit_or_newline` (:8384). Test phones with real soft keyboards as well as desktop emulation; synthetic keydown in jsdom does not perform native textarea newline insertion.

No visual/keyboard result is currently recorded. Future checks use an already authorized running app or coordinator-provided captures; subagents must not start servers, browsers, `cargo run` or smoke processes. Build/test checks may run during implementation, not this documentation task. A missing runtime check remains pending, never silently waived.

## 5. Gaps

### 5.1 Ticket 74
| Item | Kind | Desktop value | Web value (file:line) | Fix |
| --- | --- | --- | --- | --- |
| Route inner clock | Confirmed source mismatch | Dock amount drives layout progress for compact route transitions | `composer.tsx:865-881` uses local flip progress only | Derive the desktop route layout progress and consume it for every inner channel |
| Route input floor | Confirmed source mismatch | Keep at least one line plus padding in active compact route transitions | `composer.tsx:880-883,893` floors box/settled viewport at zero | Port desktop conditional floors, preserving existing settled limits |
| Compact route editor origin | Confirmed source mismatch | Route-specific text glide maintains editor origin | `composer.tsx:930-935` handles local flip only | Apply route glide from same live dock amount |
| Animated inner style publication | Confirmed implementation structure; visual failure unmeasured | All geometry comes from current render frame | `composer.tsx:909-921,2871-2884` writes live height but retains published padding/insets | Publish consistent live inner values without React state per frame |
| Reported settled overlap | Unresolved symptom | Input clipped above controls with correct padding | Current CSS already stretches body and reserves 46px (`app.css:3367-3372,3496-3501`) | Measure real bounds before any additional CSS fix; do not claim absent padding from textarea's intentional zero padding |

### 5.2 Ticket 75
| Item | Kind | Desktop value | Web value (file:line) | Fix |
| --- | --- | --- | --- | --- |
| Phone Enter | Confirmed behavior mismatch with user directive | Desktop uses configured Enter binding; no native phone counterpart | `composer.tsx:2430-2435` submits bare Enter at every width | Phone-only native-newline branch before submit/wizard dispatch, retaining composition/completion precedence |
| Phone virtual-key label | Browser hint | Not applicable to native desktop | Textarea at `composer.tsx:2727-2745` has no enterKeyHint | Add phone `enter` hint alongside actual event-policy change |
| Shared borrowed textarea | Required consistency | Desktop wizard Enter advances | `composer.tsx:2400-2413` advances on bare Enter even on phone | Focused phone textarea inserts newline; explicit wizard controls still advance |
| Wizard explicit advance after newline change | Confirmed companion requirement | Existing desktop Enter commits typed input | `composer.tsx:1756-1767,2825` button path advances without committing text | Phone button/unfocused panel Enter must commit text before advance; preserve option fallback and cancel duplicate auto-advance |
