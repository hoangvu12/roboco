# 64 — New-chat layout update cost

**What to build:** Make sidebar motion on the new-chat page run without resizing the entire React page on each animation frame. Preserve the desktop's geometry and timing. Measure which remaining work costs time before claiming that this removes the user's primary source of lag.

**Blocked by:** None — can start immediately; coordinate overlapping files with the other assigned tickets.

**Status:** ready-for-agent

**Research:** `../research-2026-09-20/followup-layout-motion.md` §3.64, §4.64, §5.64. The relevant tables are inlined below; the research pointer is optional depth.

**Desktop reference (for lookups only):** Exact Rust symbols and source locations appear in the spec and tests tables below. All source positions refer to `37c354ff`; check the symbol if later commits move lines.

**Web files to touch / read:**

| File | Change | Owned component / responsibility |
| --- | --- | --- |
| web/packages/app/src/routes/chat-page.tsx | edit | ConversationPage column-width observer and width publication; commit-time dock measurements |
| web/packages/app/src/components/composer.tsx | edit | evaluateRef and availableWidth feed; preserve live textarea layout |
| web/packages/app/src/components/titlebar.tsx | edit | useIslandTween frame ownership only |
| web/packages/app/src/styles/app.css | edit | island motion declarations only if CSS interpolation is chosen |
| web/packages/app/tests/sidebar-tween.test.ts | edit | width-notification/React publication regression coverage |
| web/packages/app/tests/titlebar-island.test.ts | edit | timing, reversal and reduced-motion regression coverage |
| web/packages/app/tests/dock-glide.test.ts | edit | existing dock discrete-publish contract |

## 1. Context a fresh session needs

- The reported symptom is "the animation on new chat page still laggy asf"; neither the gesture nor a measured bottleneck was supplied.
- ConversationPage is shared by the blank canvas and selected-chat routes. Sidebar motion is a 200ms CSS width transition; the explicit sidebar React frame pump was removed by ticket 57.
- The measured chat-column width still lives in page React state. The ResizeObserver writes setColumnWidth for every changed size, including widths produced by CSS animation.
- Composer receives a clamped availableWidth, has its own textarea observer, and performs layout evaluation. Above the 768px cap its target can remain constant while the parent page continues rendering.
- The de-Reacted dock pump is a separate mechanism. It writes live CSS variables and emits discrete phase changes; preserve its phase transitions and composer continuity.
- The titlebar island retains its own React frame loop. Its dimensions are independently corrected by ticket 66; this ticket owns how the scalar is animated.
- Static source establishes remaining work paths, not their measured cost. A trace of the reported action is required to rank them.

The project vocabulary is chat, space, engine and harness. Session means a pairing credential; old component names such as new-thread/new-session remain source identifiers. No engine RPC, persisted chat data, pairing behavior or user-facing wording changes are part of this ticket.

## 2. Spec

### 2.1 Geometry, behavior, motion and source contract

| Property / channel | Current or desktop contract | Required target | Source at 37c354ff |
| --- | --- | --- | --- |
| Page width ownership | Observer calls setColumnWidth(column.getBoundingClientRect().width) whenever column geometry changes | Keep required live geometry outside page state during known motion; publish stable endpoints/semantic changes; do not freeze user-visible wrapping blindly | web/packages/app/src/routes/chat-page.tsx:724,735-739,802-805 |
| Composer evaluation | availableWidth changes re-run layout effect; textarea observer also evaluates; setLayout creates an object | Preserve live input fit/scroll behavior while avoiding redundant React publication when evaluated layout is unchanged | web/packages/app/src/components/composer.tsx:883,924-948,990-992 |
| Page commit cost | Unscoped layout effect reads geometry and writes transform/bottom clearance | Retain correct anchor/clearance at required changes; observer animation must not force unnecessary full-page commits | web/packages/app/src/routes/chat-page.tsx:756-795 |
| Island frame owner | rAF calls setPainted and setPump each frame | DOM/CSS-owned interpolation with the same endpoints, reversal and settle behavior | web/packages/app/src/components/titlebar.tsx:185-239,298-300 |
| Desktop sidebar motion | Sidebar tween starts at painted width and targets collapsed/open width | Preserve 200ms RESIZE ease-out; immediate reduced-motion endpoint; dragging follows pointer | crates/ui/src/shell.rs:1966-1973; web/packages/app/src/lib/sidebar-tween.ts:29-38 |
| Desktop island motion | Persistent scalar tween reverses from painted value; initial presentation settled | Keep existing target predicate and 28→32px height at center y21; no entrance on initial settled mount | crates/ui/src/shell.rs:829-835,4013-4024 |

### 2.2 State transitions

| State | Trigger / condition | Required behavior |
| --- | --- | --- |
| Idle canvas | No animation, no typing | No sustained observer/React loop; stable width and composer baseline |
| Sidebar toggle | Desktop-width canvas, sidebar changes state | Layout may animate; full-page state updates must not scale with animation frames |
| Docking/undocking | Blank canvas ↔ selected chat | Preserve discrete chrome mount publishes and live imperative dock channels |
| Rapid reversal | Toggle again before settle | Retarget from currently painted geometry without a restart jump |
| Reduced motion or seam drag | Preference enabled or pointer-resize active | No stale animation hold; publish accurate final/current geometry |
| Viewport/text change | Resize window or edit expanded textarea during motion | Required wrapping and text visibility remain correct |

### 2.3 Render order, data and interactions

The shell lays out sidebar, conversation and right pane; the titlebar overlays them. The conversation owns hero, transcript and persistent composer. Keep this render order and store ownership. Read current geometry and motion signals; publish only the state needed by React consumers. No new RPC or persistent preference is introduced. Existing titlebar labels and keyboard behavior stay unchanged.

### 2.4 Chosen ownership and island driver

| Owner / input | Required implementation rule | Source at 37c354ff |
| --- | --- | --- |
| sidebarTweenSignal | Reuse arm(), settle(), isActive(), subscribe(listener). Sidebar geometry publication may defer only while this signal is active; settle/drag/reduced/phone paths release it. Do not create a second sidebar clock. | web/packages/app/src/lib/sidebar-tween.ts:64-115; routes/chat-page.tsx:538-617 |
| Live column measurement | Every column ResizeObserver tick updates a mutable measured-width ref. Initialize from the measured column, clamp composer target to [0,768], and keep the live target available without a React commit. | web/packages/app/src/routes/chat-page.tsx:724-739,802-805 |
| Page published width | For the blank canvas (!hasSelection), defer setColumnWidth while sidebarTweenSignal is active; publish current final measurement on settle. Outside that case retain responsive publication. This bounded change does not freeze selected-chat queue layout. | web/packages/app/src/routes/chat-page.tsx:735-743,1240 |
| composerWidthTargetRef | The dock pump reads this ref at each frame. Observer updates must reach it immediately; render must not overwrite the live measurement with the older published columnWidth. | web/packages/app/src/routes/chat-page.tsx:803-805,855 |
| Composer live width | If page publication is deferred, add an optional ref-based live available-width input to Composer, read by evaluateRef; use it for the strip width budget as well as the dock target. Existing textarea observer drives local remeasurement; text/attachment changes still publish their real layout changes. | web/packages/app/src/components/composer.tsx:704-705,808,940-948,990-992 |
| QueuePanel consumer | composerWidth={columnWidth} is also a real consumer, despite the nearby comment claiming composer-only. Keep its selected-chat updates unchanged in this canvas-scoped fix. | web/packages/app/src/routes/chat-page.tsx:1234-1240 |
| Dock frame order | Keep observePane → transcriptWidth → tick → liveDockFrame.current=frame → layoutWidth(liveTarget) → dockEvaluateRef → prepaint → writeDockGlideVars. Keep DockMountSequencer crossing and final-settle publications. | web/packages/app/src/routes/chat-page.tsx:846-894 |
| Dock signal | dockGlideSignal has arm(), settle(), isActive() only; no subscribe at baseline. Do not call a nonexistent subscription method. Any added subscription must be shared and tested with ticket 65. | web/packages/app/src/lib/dock-glide.ts:179-205 |
| Unchanged layout | setLayout may return prior state only when every PillLayout field is equal; retain real height/mode/morph changes. Do not claim zero composer renders during actual typing or wrapping changes. | web/packages/app/src/components/composer.tsx:924-937 |

| Island channel | Exact contract / chosen implementation | Source at 37c354ff |
| --- | --- | --- |
| Scalar interpolation | Use one imperative rAF loop with tween ref {from,to,startedAt}; evaluate existing evalWidthTween(from,to,elapsed). RESIZE is 200ms and easeOut cubic-bezier(0,0,0.58,1). No setPainted/setPump per frame. | web/packages/app/src/components/titlebar.tsx:185-239; state/layout.ts:93-100,171-179 |
| Geometry writes | For p=clamp(progress,0,1): height=28+4p; rowTop=21-height/2; cluster-local top=rowTop-4; opacity=p. Write top,height,opacity to islandRef before paint; retain phone safe-area displacement in the cluster. | web/packages/app/src/components/titlebar.tsx:123-129,298-300; styles/app.css:386-392 |
| Mount gate | Keep decorative panel mounted iff painted p>0.001. React may publish only when this boolean crosses, plus discrete target/preference lifecycle changes; do not use the frame scalar as React state. | web/packages/app/src/components/titlebar.tsx:303 |
| Initial mount | Write the target directly before first paint; target 1 starts with p 1 and a mounted panel; target 0 has no panel. No initial entrance animation. | web/packages/app/src/components/titlebar.tsx:186-200 |
| Target/reversal | Read the current tween at current time, use that painted progress as the new from, cancel obsolete rAF, and interpolate toward new target. Preserve target predicate; ticket 66 owns horizontal bounds. | crates/ui/src/shell.rs:4013-4024; web/packages/app/src/components/titlebar.tsx:191-210 |
| Reduced motion/unmount | On reduced-motion activation write endpoint immediately, synchronize mount boolean, cancel rAF and clear tween; teardown cancels any pending callback. Preference change must work even when target is unchanged. | web/packages/app/src/components/titlebar.tsx:159-173,221-235 |

The chosen width change is deliberately scoped to the blank-canvas sidebar glide. Selected-chat QueuePanel width continues publishing as before. This ticket does not promise every observer in the application becomes silent. Composer local renders remain permitted for real content/morph changes; the forbidden cost is repeated whole-page publication solely to relay animation geometry. Read both the current measured ref and published state explicitly so an unrelated React commit cannot replace the live dock target with an older endpoint.

### 2.5 Bounded work sequence

1. Capture a baseline of the exact motion in an authorized existing browser/app. Record viewport, sidebar width, composer text, effects, reduced-motion setting, page commits, observer counts and main-thread layout/paint work.
2. Trace the column-width notification to its consumers. Use the exact ownership table: keep live width in refs, defer blank-canvas page publication during sidebar motion, keep selected-chat QueuePanel publication unchanged.
3. Implement a narrow publication policy and unchanged-layout bailout. Cover initial measurement, real window resize, sidebar reverse, drag takeover, route change and unmount; do not defer all resize handling indiscriminately.
4. Implement the imperative island rAF driver in the table, with one tween ref, direct geometry/opacity writes and boolean-only panel mount crossings. Coordinate changes with ticket 66's geometry patch.
5. Repeat the same baseline interaction and compare commits/observer work. Report remaining time in remasking/layout honestly; ticket 65 owns bitmap cost and coverage.

### 2.6 Runtime / visual matrix

| Scenario | Setup | Evidence required |
| --- | --- | --- |
| Canvas sidebar collapse/expand | 1440px window and a narrower desktop window; empty + multiline composer | Matched desktop/web recording, continuous centers, page commits no longer scale with frames |
| Rapid sidebar reversal and drag takeover | Reverse before 200ms, then seam drag | No stuck width, stale hold or ending jump |
| Canvas ↔ chat | Pane closed and open on destination; composer width above/below 768px cap | Dock phase timing and final geometry remain correct |
| Reduced motion; resize; text input | Preference on/off mid-motion, resize viewport, type during glide | Immediate reduced endpoint; text remains visible and wrapping correct |

Record build identity, viewport, device/browser, relevant preference state and exact action with each capture. Use matched native/web recordings where native behavior exists; phone-only cases use device evidence and the stated desktop contract. For visual motion, include intermediate frames or a recording, not just two endpoint screenshots.

## 3. Pure logic to port and meaningful tests

| Type | Verified existing file / prospective file | Test or scenario name | Required coverage |
| --- | --- | --- | --- |
| Existing | sidebar-tween.test.ts:82 | HeroRemaskGate — zero remasks during the tween, exactly one on settle | Preserve sidebar signal lifecycle; this isolated test does not prove whole-page zero-render behavior |
| Existing | dock-glide.test.ts:221 | the state-write counter — zero per-frame React writes across a whole glide | Preserve discrete dock writes; add coverage at actual observer/publication integration |
| Existing desktop → web | crates/ui/src/shell.rs:8269; titlebar-island.test.ts:29 | island_stays_centered_on_controls_while_expanding | Retain vertical geometry during animation-owner change |
| New behavior test | web/packages/app/tests/sidebar-tween.test.ts | column geometry ticks do not publish page state during shell motion | Drive repeated changed widths, settle/reverse/drag cancellation, and assert final width plus bounded publications |
| New behavior test | web/packages/app/tests/titlebar-island.test.ts | island reversal starts from painted progress and reduced motion settles immediately | Exercise whichever runtime animation driver is introduced; do not merely grep source |

Use the state/geometry contracts above to test observable behavior. Do not add tests that merely count occurrences of a source token or copy an unconnected arithmetic implementation. Reuse existing motion signals rather than adding conflicting clocks.

## 4. Gaps this ticket closes

| Item | Kind | Desktop / required value | Web value / evidence | Fix or deliverable |
| --- | --- | --- | --- | --- |
| 64a | performance mechanism | Desktop evaluates shell geometry in its render model; browser implementation need not rerender React on CSS frames | Column observer writes page state per geometry tick (chat-page.tsx:735-739) | Separate live geometry from page publication |
| 64b | performance mechanism | Island scalar follows one persistent tween (shell.rs:4013-4024) | React setPainted/setPump per frame (titlebar.tsx:227-229) | Move scalar interpolation to CSS/DOM |
| 64c | evidence | User-visible smoothness must be observed | No trace establishes the largest remaining cost | Capture matched before/after trace and report measured limits |

## 5. Do not

- Do not claim zero layout/paint cost merely because CSS owns animation.
- Do not alter island horizontal bounds in this ticket; ticket 66 owns them.
- Do not change remask algorithms or raster coverage; ticket 65 owns them.
- Do not remove animation, shorten durations, or revive frosted web surfaces.
- Do not modify desktop Rust or unrelated transcript behavior.
- Do not start long-running processes, servers, browsers, `pnpm dev`, `cargo run` or `web_smoke` from a subagent. Use an already authorized running app or coordinator/user captures.
- Do not waive runtime/visual evidence. If access is unavailable, record the relevant acceptance as pending.
- Do not mix unrelated changes or modify sibling tickets' code ownership without coordinator agreement.

## 6. Acceptance

- [ ] Baseline and after traces identify the tested action and distinguish JS, React, layout, paint and canvas costs.
- [ ] Whole-page updates no longer scale with sidebar animation geometry notifications in the controlled scenario; required endpoint and content changes still publish.
- [ ] Island interpolation has no per-frame React state loop and matches its existing timing/reversal/reduced-motion contract.
- [ ] Composer fit, cursor visibility, dock phases and bottom clearance remain correct across the runtime matrix.
- [ ] Each required runtime-matrix case has evidence or an explicit pending entry; untested cases are not represented as passed.
- [ ] Relevant existing and new behavior tests pass; record exact suites and results.
- [ ] From `web/`, `pnpm -r build` passes; from `web/packages/app/`, `pnpm test` passes. Run these only during authorized implementation, not ticket creation.
- [ ] Use shared tokens where available; web remains opaque and reduced motion reaches correct final state.

## Comments

Created from read-only source research at `37c354ff`. Implementation and runtime validation have not run. The current authoring task only writes the ticket and research artifact.
