# Follow-up research: layout, background motion, pane close and phone chrome

Baseline: `web-parity/wave-2 @ 37c354ff`, researched 2026-09-20.
Scope: user reports 1, 2, 5 and 9 from the handoff.
The handoff and prior research/tickets were read before checking the current sources.
This artifact accompanies tickets 64, 65, 66, 72, 73 and 76.
The current task creates research and tickets only; it does not implement their prospective work.

## 1. Evidence and confidence

- **Proven geometry error:** earlier island research confused content-box bounds with padding-box bounds. Actual desktop no-plus island is [6,102], width 96; web is [16,92], width 76.
- **Proven residual work:** CSS-driven geometry still feeds page React state; island still has a React frame loop; the remask gate consults only sidebar motion, leaving a dock-only route to full CPU masks.
- **Proven visual mechanism:** the fixed old-width raster exposes background bands when the hero grows. Prior ticket 57 line90 explicitly recorded that compromise.
- **Proven phone close mechanism:** clearing logical expanded immediately changes the phone drawer from 100vw to its normal width while only transform transitions.
- **Unproven ordinary desktop close defect:** same-chat close appears to follow the native tween/held-content contract. Files-family early blanking exists on both clients.
- **Proven gesture-contract mistake:** touch-action:manipulation permits panning. Exact reported scroll source and device behavior remain unmeasured.
- No profiling, browser/device capture, build or tests were performed for this research. Static mechanisms must not be reported as measured bottlenecks or successful visual verification.

## 2. Authority, corrections and implementation boundaries

Desktop Rust is the reference for desktop geometry and behavior. The web stays opaque and preserves reduced-motion handling. Phone behavior uses the existing phone-layer amendment; native titlebar window dragging and OS status-bar gestures are not app DOM behavior.

The earlier `nav-picker-diff-subagent.md` S1 incorrectly called [10,92] a padding box. In a 102px desktop cluster with 10px padding on each side, that is the content box. The local dependency confirms absolute offsets subtract border, not padding:

- `C:/Users/ADMIN/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/taffy-0.12.2/src/compute/flexbox.rs:2164-2167`
- The same file `:2336-2340` places an explicit left inset at inset+border.
- `crates/ui/src/shell.rs:4025-4041` therefore produces [6,102], not [16,92]. Ticket 66 supersedes the prior horizontal table and assertions.

Gesture semantics were checked against the primary [W3C Pointer Events 3 specification, §8.2 and §8.3](https://www.w3.org/TR/pointerevents3/#details-of-touch-action-values). Its manipulation value permits panning and continuous zooming; none prevents direct-manipulation panning/zooming initiated in the constrained region. Control activation remains a separate behavior. This corrects the false no-pan claim in ticket 50 and current CSS comments.

Prospective implementation agents must not start long-running servers, browser processes, cargo run, web_smoke or pnpm dev. Visual evidence must come from an already authorized running app or coordinator/user captures. Missing evidence stays pending; source tests and screenshots of final endpoints do not prove transition continuity. Only the coordinator may arrange additional runtime access outside an agent's assigned authorization.

## 3. Authoritative spec tables

The following tables are copied verbatim into the corresponding tickets. Current and target are distinct; proposed targets are requirements for future work, not claims that implementation or runtime verification occurred.

### 3.64 New-chat layout update cost

| Property / channel | Current or desktop contract | Required target | Source at 37c354ff |
| --- | --- | --- | --- |
| Page width ownership | Observer calls setColumnWidth(column.getBoundingClientRect().width) whenever column geometry changes | Keep required live geometry outside page state during known motion; publish stable endpoints/semantic changes; do not freeze user-visible wrapping blindly | web/packages/app/src/routes/chat-page.tsx:724,735-739,802-805 |
| Composer evaluation | availableWidth changes re-run layout effect; textarea observer also evaluates; setLayout creates an object | Preserve live input fit/scroll behavior while avoiding redundant React publication when evaluated layout is unchanged | web/packages/app/src/components/composer.tsx:883,924-948,990-992 |
| Page commit cost | Unscoped layout effect reads geometry and writes transform/bottom clearance | Retain correct anchor/clearance at required changes; observer animation must not force unnecessary full-page commits | web/packages/app/src/routes/chat-page.tsx:756-795 |
| Island frame owner | rAF calls setPainted and setPump each frame | DOM/CSS-owned interpolation with the same endpoints, reversal and settle behavior | web/packages/app/src/components/titlebar.tsx:185-239,298-300 |
| Desktop sidebar motion | Sidebar tween starts at painted width and targets collapsed/open width | Preserve 200ms RESIZE ease-out; immediate reduced-motion endpoint; dragging follows pointer | crates/ui/src/shell.rs:1966-1973; web/packages/app/src/lib/sidebar-tween.ts:29-38 |
| Desktop island motion | Persistent scalar tween reverses from painted value; initial presentation settled | Keep existing target predicate and 28→32px height at center y21; no entrance on initial settled mount | crates/ui/src/shell.rs:829-835,4013-4024 |

| State | Trigger / condition | Required behavior |
| --- | --- | --- |
| Idle canvas | No animation, no typing | No sustained observer/React loop; stable width and composer baseline |
| Sidebar toggle | Desktop-width canvas, sidebar changes state | Layout may animate; full-page state updates must not scale with animation frames |
| Docking/undocking | Blank canvas ↔ selected chat | Preserve discrete chrome mount publishes and live imperative dock channels |
| Rapid reversal | Toggle again before settle | Retarget from currently painted geometry without a restart jump |
| Reduced motion or seam drag | Preference enabled or pointer-resize active | No stale animation hold; publish accurate final/current geometry |
| Viewport/text change | Resize window or edit expanded textarea during motion | Required wrapping and text visibility remain correct |

#### 3.64a Chosen width ownership

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

#### 3.64b Chosen island driver

| Island channel | Exact contract / chosen implementation | Source at 37c354ff |
| --- | --- | --- |
| Scalar interpolation | Use one imperative rAF loop with tween ref {from,to,startedAt}; evaluate existing evalWidthTween(from,to,elapsed). RESIZE is 200ms and easeOut cubic-bezier(0,0,0.58,1). No setPainted/setPump per frame. | web/packages/app/src/components/titlebar.tsx:185-239; state/layout.ts:93-100,171-179 |
| Geometry writes | For p=clamp(progress,0,1): height=28+4p; rowTop=21-height/2; cluster-local top=rowTop-4; opacity=p. Write top,height,opacity to islandRef before paint; retain phone safe-area displacement in the cluster. | web/packages/app/src/components/titlebar.tsx:123-129,298-300; styles/app.css:386-392 |
| Mount gate | Keep decorative panel mounted iff painted p>0.001. React may publish only when this boolean crosses, plus discrete target/preference lifecycle changes; do not use the frame scalar as React state. | web/packages/app/src/components/titlebar.tsx:303 |
| Initial mount | Write the target directly before first paint; target 1 starts with p 1 and a mounted panel; target 0 has no panel. No initial entrance animation. | web/packages/app/src/components/titlebar.tsx:186-200 |
| Target/reversal | Read the current tween at current time, use that painted progress as the new from, cancel obsolete rAF, and interpolate toward new target. Preserve target predicate; ticket 66 owns horizontal bounds. | crates/ui/src/shell.rs:4013-4024; web/packages/app/src/components/titlebar.tsx:191-210 |
| Reduced motion/unmount | On reduced-motion activation write endpoint immediately, synchronize mount boolean, cancel rAF and clear tween; teardown cancels any pending callback. Preference change must work even when target is unchanged. | web/packages/app/src/components/titlebar.tsx:159-173,221-235 |

### 3.65 New-chat background raster continuity

| Property / channel | Current or desktop contract | Required target | Source at 37c354ff |
| --- | --- | --- | --- |
| Desktop cover fit | Scale=max(heroWidth/sourceWidth, heroHeight/sourceHeight), centered at current hero bounds | Preserve cover fit over the complete visible hero throughout motion and at settle | crates/ui/src/new_thread_background_mask.rs:59-81 |
| Desktop mask coordinates | Paint consumes measured current composer bounds after prepaint | Keep the cutout aligned to current composer; preserve hard clearance, feather and shared bottom fade | crates/ui/src/new_thread_background_mask.rs:12-44,49-81 |
| Web two passes | Reveal and cutout both repaint; each allocates ImageData and fills every mask pixel | Preserve appearance while eliminating unbounded duplicate work during known motion; measure the selected approach | web/packages/app/src/components/new-thread-background.tsx:353-367 |
| Remask gate | Geometry requests are suppressed only by sidebarTweenSignal | Coordinate sidebar and dock motion, with explicit final repaint and interruption handling; do not freeze the hole at a stale position | web/packages/app/src/lib/sidebar-tween.ts:51-55,132-134; components/new-thread-background.tsx:377-393 |
| Raster coverage | Fixed pre-tween raster centered using left:50% and old width | Backing coverage must include every exposed hero region during growth, with no page-background bands or settle crop jump | web/packages/app/src/styles/app.css:2737-2741; .scratch/web-parity/issues/57-no-per-frame-react.md:90 |
| Motion and preference | Sidebar uses 200ms RESIZE; dock has its existing phase clock; reduced motion snaps | Retain clocks and endpoint geometry; no new product transition or fade | web/packages/app/src/lib/sidebar-tween.ts:29-38; routes/chat-page.tsx:807-816 |

| State | Trigger / condition | Required behavior |
| --- | --- | --- |
| Sidebar shrinks hero | Sidebar opens | All visible pixels covered; hole tracks composer; settle does not recrop visibly |
| Sidebar grows hero | Sidebar collapses | No exposed edge bands at intermediate widths |
| Dock glide | Canvas ↔ selected chat; sidebar not moving | Composer-surface notifications must not trigger uncontrolled full-mask work every frame |
| Combined/reversed motion | Sidebar and dock signals overlap or reverse | Final paint waits for appropriate geometry; no signal clears another's active protection |
| Artwork/effect arrives | Cold decode or effect changes during motion | Display coherent resolved artwork and geometry; preserve existing readiness contract |
| Reduced/drag/unmount interruption | Motion ends without normal transitionend | Release retained buffers/holds, repaint final geometry if mounted, no stale callbacks |

#### 3.65a Exact mask and image formulas

| Mask / image channel | Exact required formula or behavior | Source at 37c354ff |
| --- | --- | --- |
| Hero dimensions | Keep existing hero geometry: height=min(viewportHeight×0.72,760); width follows existing viewport/sidebar geometry. No new crop policy. | web/packages/app/src/lib/new-thread-background.ts:41-44; routes/chat-page.tsx:911-913 |
| Cover fit | s=max(heroWidth/sourceWidth,heroHeight/sourceHeight); fittedSize=sourceSize×s; fittedOrigin=heroCenter-fittedSize/2. Evaluate from CURRENT hero geometry; allocating a wider bitmap alone does not preserve this crop. | crates/ui/src/new_thread_background_mask.rs:59-81; components/new-thread-background.tsx:321-333 |
| Cutout bounds | x=composer.x,y=composer.y,width=composer.width,height=max(composer.bottom,hero.bottom)-composer.y. Read current window-space composer geometry after placement. | web/packages/app/src/lib/new-thread-background.ts:130-138 |
| Cutout radius/feather/clearance | radius 26 clamped to half each mask dimension; feather=clamp(hero.height×0.52,120,280); clearance 8. These remain CSS-pixel quantities at any DPR. | web/packages/app/src/lib/new-thread-background.ts:48-50,147-149,188-192 |
| Hole alpha | q=abs(pixel-maskCenter)-maskHalfSize+radius; d=length(max(q,0))+min(max(q.x,q.y),0)-radius; t=clamp((d-8)/feather,0,1); hole=t²(3-2t). | web/packages/app/src/lib/new-thread-background.ts:167-198 |
| Bottom fade and combination | u=clamp((hero.bottom-pixel.y)/max(hero.height,1),0,1); fade=u²(3-2u); cutoutAlpha=min(hole,fade), not hole×fade. | web/packages/app/src/lib/new-thread-background.ts:153,206-226 |
| Reveal pass | Mask exclusion parked at(hero.x,hero.bottom+1), size=hero.size,radius 0,feather1,clearance 0; same bottom fade; render opacity 0.5. Main cutout pass opacity 1. | crates/ui/src/new_thread_background_mask.rs:8,23-44; web/packages/app/src/styles/app.css:2764-2769 |
| Source and raster scale | effect=none uses decoded image; installed effect uses its effect raster and stays empty while cold. Current image backing DPR=max(1,devicePixelRatio), mask grid CSS resolution; preserve source identity/readiness and document any intentional quality trade-off. | web/packages/app/src/components/new-thread-background.tsx:252-255,295-315,334-364 |
| Existing pure helpers | Reuse heroMaskGeometry(hero,composer,cutout), cutoutHoleAlpha(mask,x,y), cutoutBottomFadeAlpha(mask,y), cutoutMaskAlpha(mask,x,y), cutoutMaskRaster(hero,composer,cutout,width,height,scale=1) as numeric reference or implementation. | web/packages/app/src/lib/new-thread-background.ts:130,183,206,222,239-246 |

#### 3.65b Bounded renderer scheduling

| Scheduling edge / owner | Mandatory bounded rule for the design prototype | Source / rationale |
| --- | --- | --- |
| Geometry notification | Record dirty/latest geometry; coalesce both ResizeObservers into at most one scheduled renderer update per animation frame. Sample hero and composer together after live dock placement; do not execute independent full remasks synchronously per observer. | web/packages/app/src/components/new-thread-background.tsx:377-393; routes/chat-page.tsx:854-878 |
| Active motion | Treat motionActive=sidebarTweenSignal.isActive() OR dockGlideSignal.isActive(). This selects the dynamic renderer; it MUST NOT simply mean skip all geometry painting. | web/packages/app/src/lib/sidebar-tween.ts:132-134; lib/dock-glide.ts:179-205 |
| Dock placement-only frames | ResizeObserver does not observe transform-only movement. Dynamic sampling/rendering must continue from the shared animation cadence while dock placement moves, even if width/height notifications stop. | web/packages/app/src/routes/chat-page.tsx:868-870 |
| Signal interface | Sidebar signal has subscribe; DockGlideSignal currently does not. Either add shared arm/settle subscription with cancellation tests, or explicitly connect renderer dirty/frame hooks to the existing dock pump. No invented dockGlideSignal.subscribe call against baseline. | web/packages/app/src/lib/sidebar-tween.ts:93-98; lib/dock-glide.ts:179-205 |
| Overlap and reversal | Use both current signal states/generation ownership. One motion settling cannot release the other's renderer/geometry tracking; reversal retargets from actual current bounds. | Existing two-signal lifecycle; prospective coordinator rule |
| Final paint | After both motions stop, measure current hero/composer in the final placement and paint once; coalesce settle+observer requests. Cancel stale-generation callbacks on source replacement/unmount. Reduced motion uses immediate correct endpoint. | web/packages/app/src/routes/chat-page.tsx:886-903; lib/sidebar-tween.ts:74-78 |
| Coverage and crop | Render source over every exposed hero pixel using current cover-fit formula. Overscan may cover bounds, but cannot justify frozen crop or scaling a pre-masked image that changes radius/feather. | Mask/image contract above; old app.css:2737-2741 fails growth |
| Resource bound | At most one queued frame and one renderer update per frame; reuse backing buffers/resources when dimensions/source unchanged. CPU mask invocations, bytes allocated and frame times must be measured. A one-update/frame cap alone does not prove acceptable performance. | Prospective scheduling contract; current allocation at new-thread-background.tsx:353 |

Ticket 65 is ready for the bounded Stage-A design/prototype task, not an already specified production renderer. Its required artifact is `.scratch/web-parity/research-2026-09-20/background-raster-design.md`. A candidate must separate cached artwork from current mask/cover-fit geometry, obey the tables, compare against the existing pure-mask numeric oracle and supply a matched visual/performance recording. Only a concrete algorithm with numeric equivalence, moving-geometry continuity and measured improvement passes the technical integration gate. Runtime access missing means pending evidence and no production renderer integration. Simply suppressing remasks during both motion signals is invalid: transforms can move the composer without ResizeObserver notifications, and a frozen mask then becomes stale.

### 3.66 Titlebar island: restore the desktop padding-box geometry

| Property / channel | Current or desktop contract | Required target | Source at 37c354ff |
| --- | --- | --- | --- |
| Control row without plus | Three 24px controls +8px group gap +2px nav gap =82px; visible buttons span [10,92] | Unchanged | crates/ui/src/shell.rs:230-249,4065-4091; web/packages/app/src/state/layout.ts:274-280 |
| Desktop cluster without plus | x0; 10px left/right padding; total width 102px | Web must reproduce the same containing-block geometry or equivalent island offsets | crates/ui/src/shell.rs:4025-4034 |
| Island without plus | Desktop [6,102], width 96, center 54; web [16,92], width 76, center 54 | Restore 10px coverage at each edge; preserve button positions | crates/ui/src/shell.rs:4036-4041; web/packages/app/src/styles/app.css:347-356,410-414 |
| Absolute offset semantics | Containing size=container minus borders; offset=inset+border, not inset+padding | Use this rule rather than prior research's content-box arithmetic | taffy-0.12.2/src/compute/flexbox.rs:2164-2167,2336-2340 (local Cargo registry) |
| Plus present | Adds 8px gap +24px control =32px | Desktop cluster width 134; island [6,134], width 128; buttons remain [10,124] | crates/ui/src/shell.rs:4093-4104; web/packages/app/src/state/layout.ts:282 |
| Vertical geometry | Height 28+4×progress; center(38+4)/2=21; full top 5 height 32 | Unchanged, including phone safe-area offset | crates/ui/src/shell.rs:829-835; web/packages/app/src/styles/app.css:386-392 |
| Visibility and motion | Canvas route, no selected chat, collapsed sidebar, resolved background; persistent 200ms RESIZE tween | Preserve gate, first-mount settle, reversal and reduced-motion snap; plus remains conditionally mounted | crates/ui/src/shell.rs:4005-4024; web/packages/app/src/components/titlebar.tsx:260,321-325 |
| Surface | Desktop frosted island; web deliberately opaque | Keep --rb-overlay, shared radius/shadow; never restore blur | web/packages/app/src/styles/app.css:428-433; web-parity/spec.md and ticket 56 directive |

| State | Trigger / condition | Required behavior |
| --- | --- | --- |
| Canvas, sidebar collapsed, background resolves | Island target 1; plus absent | Island [6,102]; buttons fully contained including hover boxes |
| Canvas, sidebar open | Island target 0 | Controls retain identical x positions while island fades |
| Chat selected | Plus shown; island target 0 | Controls span [10,124]; residual fading island extends to 134 |
| No background | Island target 0 | No invented plate or changed control layout |
| Phone safe area | Phone viewport with nonzero top inset | Only existing vertical safe-area displacement; horizontal bounds same |
| Reduced motion | Preference enabled | Immediately correct final geometry and visibility |

### 3.72 Phone pane close: preserve expanded width until the slide ends

| Property / channel | Current or desktop contract | Required target | Source at 37c354ff |
| --- | --- | --- | --- |
| Normal phone width | min(30rem,88vw) | Unchanged open and closing presentation | web/packages/app/src/styles/app.css:6901 |
| Expanded phone width | 100vw while .right-pane-expanded is present | Retain the expanded visible width through closing; release only after its slide settles | web/packages/app/src/styles/app.css:6919-6920 |
| Close state | toggle/close immediately clear open and expanded | Logical state still clears immediately; presentation remembers prior expanded width until close completes | web/packages/app/src/state/right-pane.ts:250-254,303-304 |
| Slide | Open translateX(0); shut translateX(100%); 140ms menu-in ease-out | Unchanged curve/duration; translate against retained presentation width; reduced motion settles immediately | web/packages/app/src/styles/app.css:6885-6904,6911-6913 |
| Desktop comparison | Closing resets takeover; outer width tweens while inner holds larger endpoint | Preserve desktop behavior; phone's retained width is the corresponding presentation-continuity contract | crates/ui/src/shell.rs:1987-1995,3850-3875 |
| Content and tabs | Phone header contains RightTabStrip; inner width 100%; Files-family drops content on close | Preserve tab placement and existing resource policy | web/packages/app/src/components/right-pane.tsx:73-79,160-175; styles/app.css:6929-6955 |

| State | Trigger / condition | Required behavior |
| --- | --- | --- |
| Expanded open→closed | Close via pane toggle, Escape or backdrop | Immediately clear logical flags; hold expanded presentation width during slide |
| Normal open→closed | Any close entry point | Keep normal width; no unnecessary expanded hold |
| Reopen before close finishes | Same chat toggled open quickly | Release/retarget presentation deterministically to current open mode; no stale close completion hides it |
| Chat/owner switch | Different chat or route during close | Cancel old presentation ownership; honor destination pane state |
| Phone→desktop breakpoint | Viewport crosses 768px during close | Clear phone hold and restore desktop width/glide rules |
| Reduced motion or canceled transition | Preference enabled or transition cannot complete | Release hold at the correct immediate/fallback settle; no permanent 100vw closed state |

### 3.73 Diagnose the remaining desktop right-pane close snap

| Property / channel | Current or desktop contract | Required target | Source at 37c354ff |
| --- | --- | --- | --- |
| Ordinary desktop close | Same chat; pane.open true→false; chatId unchanged | Expected 200ms width transition with larger endpoint inner width; establish whether observation matches | web/packages/app/src/components/right-pane.tsx:137-150,227-255; styles/app.css:1167-1188 |
| Desktop native close | Capture painted width, reset takeover on close, arm right tween, no takeover content tween | Comparison baseline; do not assume a slide must translate the whole surface | crates/ui/src/shell.rs:1976-1995,3850-3875 |
| Owner switch | data-pane-snap kills transition when chatId changes | Intentional destination-state snap; classify separately from same-chat close | web/packages/app/src/components/right-pane.tsx:129-146; styles/app.css:1181-1182; crates/ui/src/shell.rs:1837-1862 |
| Files-family close | Content suppressed as soon as closed, even while pane animation remains | Desktop parity; changing visible resource-retention policy requires explicit product scope | web/packages/app/src/components/right-pane.tsx:73-79; crates/ui/src/shell.rs:6472-6475 |
| Reduced motion/drag | CSS can intentionally disable transitions | Record preference and resize flag before treating no animation as regression | web/packages/app/src/styles/app.css:1289,13203-13210 |
| Evidence deliverable | No confirmed ordinary-close root cause yet | Capture trigger, source path, actual widths/transforms/mount timing, matched native behavior and smallest justified next step | This research; baseline 37c354ff |

| State | Trigger / condition | Required behavior |
| --- | --- | --- |
| Normal pane, same chat | Close Changes or Terminal via titlebar toggle | Measure outer width each frame and inner width/content lifetime |
| Files or file tab | Same close action | Separate content blanking from container motion |
| Expanded desktop pane | Close takeover | Measure conversation reveal, held inner geometry and expanded reset |
| Chat switch | Destination pane open/closed | Classify intentional snap and any lingering transition suppression |
| Rapid reversal | Close then reopen during 200ms | Check retargeting and stale timer cleanup |
| Reduced motion/resize | Preference on/off; resize immediately before close | Identify intentional transition suppression and whether flags clear |

### 3.76 Phone titlebar: prevent touch panning from app chrome

| Property / channel | Current or desktop contract | Required target | Source at 37c354ff |
| --- | --- | --- | --- |
| Phone titlebar gesture policy | touch-action:manipulation allows panning | Apply touch-action:none to the app titlebar at phone widths; preserve ordinary click activation and selection suppression | web/packages/app/src/styles/app.css:323-328; W3C Pointer Events 3§8.3 |
| Button descendants | Controls separately declare manipulation | Ancestor titlebar none constrains gestures initiated on descendant controls; verify the actual hit-tested region | web/packages/app/src/styles/app.css:461,504; W3C Pointer Events 3§8.2 |
| Viewport and safe area | Phone100dvh chain; titlebar grows/shifts by safe-area inset | Keep existing layout; guard includes app chrome within the inset, not OS-owned status bar | web/packages/app/src/styles/app.css:373-392 |
| Document containment | html/body overscroll-behavior:none; body overflow:hidden | Preserve existing containment; do not assume it alone proves no visual-viewport motion | web/packages/app/src/styles/app.css:26-28,43 |
| Desktop behavior | Native titlebar hands drag to window compositor | No web native-window drag port and no desktop-width gesture/layout changes | crates/ui/src/shell.rs:3941-3985 |
| Control activation | Window/nav controls invoke onClick | Taps continue toggling/navigating; no long-press scroll behavior is added | web/packages/app/src/components/titlebar.tsx:399-408,431-439 |

| State | Trigger / condition | Required behavior |
| --- | --- | --- |
| Hold title text/background | Phone app titlebar, no deliberate movement | No new document/transcript scroll initiated by chrome interaction |
| Slight vertical/horizontal drag | Gesture begins on app titlebar | Browser panning disallowed for that region; regular content scrolling unaffected |
| Tap titlebar controls | Sidebar toggle, navigation, pane toggle | Normal click action works once |
| Drawer open | Left or right phone drawer visible | App titlebar gesture guard still applies; drawer content scrolls normally |
| Keyboard visible | Composer focused; touch app bar | Record visual viewport and transcript changes separately; no unsupported promise about OS keyboard behavior |
| OS status bar touch | Gesture outside app DOM | Classify separately; do not claim app CSS can disable native OS scroll-to-top |

## 4. Existing tests and required new evidence

Named existing suites/tests were checked in the repository; rows marked new are proposed and do not yet exist. Native tests without a browser counterpart are identified as references, not invented ports.

### 4.64 New-chat layout update cost

| Type | Verified existing file / prospective file | Test or scenario name | Required coverage |
| --- | --- | --- | --- |
| Existing | sidebar-tween.test.ts:82 | HeroRemaskGate — zero remasks during the tween, exactly one on settle | Preserve sidebar signal lifecycle; this isolated test does not prove whole-page zero-render behavior |
| Existing | dock-glide.test.ts:221 | the state-write counter — zero per-frame React writes across a whole glide | Preserve discrete dock writes; add coverage at actual observer/publication integration |
| Existing desktop → web | crates/ui/src/shell.rs:8269; titlebar-island.test.ts:29 | island_stays_centered_on_controls_while_expanding | Retain vertical geometry during animation-owner change |
| New behavior test | web/packages/app/tests/sidebar-tween.test.ts | column geometry ticks do not publish page state during shell motion | Drive repeated changed widths, settle/reverse/drag cancellation, and assert final width plus bounded publications |
| New behavior test | web/packages/app/tests/titlebar-island.test.ts | island reversal starts from painted progress and reduced motion settles immediately | Exercise whichever runtime animation driver is introduced; do not merely grep source |

| Scenario | Setup | Evidence required |
| --- | --- | --- |
| Canvas sidebar collapse/expand | 1440px window and a narrower desktop window; empty + multiline composer | Matched desktop/web recording, continuous centers, page commits no longer scale with frames |
| Rapid sidebar reversal and drag takeover | Reverse before 200ms, then seam drag | No stuck width, stale hold or ending jump |
| Canvas ↔ chat | Pane closed and open on destination; composer width above/below 768px cap | Dock phase timing and final geometry remain correct |
| Reduced motion; resize; text input | Preference on/off mid-motion, resize viewport, type during glide | Immediate reduced endpoint; text remains visible and wrapping correct |

### 4.65 New-chat background raster continuity

| Type | Verified existing file / prospective file | Test or scenario name | Required coverage |
| --- | --- | --- | --- |
| Existing | sidebar-tween.test.ts:82 | HeroRemaskGate — zero remasks during the tween, exactly one on settle | Extend the gate's behavior coverage to overlapping dock/sidebar lifecycles |
| Existing | sidebar-tween.test.ts:125 | an artwork/effect change remasks even mid-tween (the raster re-fixes the window) | Preserve coherent artwork arrival during motion |
| Existing desktop | crates/ui/src/new_thread_background_mask.rs:180 | mask_tracks_current_surface_in_window_space_without_rounding | Keep exact window-space geometry in web mask assertions |
| New behavior test | web/packages/app/tests/new-thread-background.test.ts | growth raster covers both endpoint windows without exposed edges | Check coverage numerically across intermediate geometry; visual capture still required |
| New behavior test | web/packages/app/tests/sidebar-tween.test.ts | overlapping sidebar and dock motion coalesces geometry remasks and paints final bounds | Simulate repeated notifications, reversal, canceled motion and unmount; assert bounded work and accurate final paint |

| Scenario | Setup | Evidence required |
| --- | --- | --- |
| Sidebar collapse and expansion | Default and wide sidebar; light/dark; installed/default artwork | No page-color bands, hole drift, stretch distortion or crop snap |
| Docking and undocking | Empty and multiline composer; small/large viewport; pane closed/open on destination | Mask remains aligned; canvas work stays within documented bounded plan |
| Overlap and interruption | Rapid reverse; sidebar toggle during dock; drag takeover; reduced motion toggled | Correct final coverage and no stale remask/hold |
| Cold artwork and effect changes | Art arrives mid-motion; supported effect changed | Readiness fade remains correct and final artwork is coherent |

### 4.66 Titlebar island: restore the desktop padding-box geometry

| Type | Verified existing file / prospective file | Test or scenario name | Required coverage |
| --- | --- | --- | --- |
| Existing incorrect expectations to replace | titlebar-island.test.ts:65,72,78 | with the `+` hidden the island spans the desktop's [16, 92]; with the `+` shown its 32px slot extends the span to [16, 124]; centers the icons like the desktop: pill center 54 over the [10, 92] controls | Replace bounds with [6,102] and [6,134]; preserve center 54 without plus |
| Existing desktop → web | crates/ui/src/shell.rs:8269; titlebar-island.test.ts:29 | island_stays_centered_on_controls_while_expanding | Preserve height and center across progress |
| Existing | titlebar-island.test.ts:95 | island_target | Preserve resolved-background and route/selection/sidebar gate |
| New geometry integration assertion | web/packages/app/tests/titlebar-island.test.ts | rendered island bounds contain all navigation button rectangles | Connect assertion to rendered CSS/markup geometry; actual browser rectangles remain required |

| Scenario | Setup | Evidence required |
| --- | --- | --- |
| Blank canvas, sidebar collapsed | No plus; hover each control; back/forward enabled and disabled | Desktop/web island bounds and button boxes match |
| Sidebar open→closed→open | Normal and reduced motion; reverse mid-animation | No control movement or vertical jump |
| Canvas→chat→canvas | Plus mount/unmount while island fading; pane open/closed | Transient plate contains the correct control row |
| Phone and narrow desktop | Phone notch inset and 769px boundary | Correct top inset; no horizontal overflow or identity collision introduced |

### 4.72 Phone pane close: preserve expanded width until the slide ends

| Type | Verified existing file / prospective file | Test or scenario name | Required coverage |
| --- | --- | --- | --- |
| Existing desktop contract | crates/ui/src/shell.rs:8442 | right_panel_content_keeps_the_larger_width_only_during_transition | Reference only; no desktop change or invented phone test port |
| Existing web suite | web/packages/app/tests/right-pane.test.ts | surface keys and value equality | Preserve pane ownership and store surface semantics |
| New lifecycle test | web/packages/app/tests/phone-pane-close.test.ts | expanded close retains presentation width while resetting logical mode | Assert logical flags and rendered presentation state independently |
| New lifecycle test | web/packages/app/tests/phone-pane-close.test.ts | reopen owner change breakpoint and reduced motion cancel stale close completion | Exercise interruption paths and final presentation cleanup |

| Scenario | Setup | Evidence required |
| --- | --- | --- |
| Expanded pane close | Phone portrait; Changes, Terminal and Files; toggle/backdrop/Escape | Width stays full through slide; Files policy distinguished from container motion |
| Normal pane close and reopen | Phone portrait and landscape | Normal width unchanged; closed expanded pane reopens normally |
| Rapid reopen and owner change | Reopen mid-close; navigate chats; resize across 768px | No stale hold or old transition callback |
| Reduced motion | Preference enabled before close and mid-close | Immediate settled presentation; no flash or deferred hold |

### 4.73 Diagnose the remaining desktop right-pane close snap

| Type | Verified existing file / prospective file | Test or scenario name | Required coverage |
| --- | --- | --- | --- |
| Existing desktop | crates/ui/src/shell.rs:8442 | right_panel_content_keeps_the_larger_width_only_during_transition | Reference for interpreting measured inner width |
| Existing web suite | web/packages/app/tests/right-pane.test.ts | surface keys and value equality | Reference for pane owner/surface behavior; does not prove browser animation |
| Diagnostic scenario, not a new unit test | Authorized running browser at desktop width | same-chat Changes/Terminal close vs Files close vs chat-owner switch | Record widths, flags, mount events and matched desktop recording |
| Follow-up only if a defect is isolated | Choose relevant existing suite after diagnosis | Name a behavior regression test from the confirmed trigger | Do not invent a test asserting a speculative cause |

| Scenario | Setup | Evidence required |
| --- | --- | --- |
| Normal desktop pane | 769px and wider; Changes and Terminal; sidebar open/closed | Outer/inner widths and same-chat key remain traceable |
| Files/file pane | Same viewport and close action as control case | Document early content unmount versus column animation |
| Takeover and chat switch | Expanded close; destination open/closed; rapid navigation | Separate takeover, owner snap and normal close |
| Reversal/preference/resize | Rapid close/open; reduced on/off; seam drag then close | Identify intended and accidental transition suppression |

### 4.76 Phone titlebar: prevent touch panning from app chrome

| Type | Verified existing file / prospective file | Test or scenario name | Required coverage |
| --- | --- | --- | --- |
| New optional stylesheet contract | web/packages/app/tests/phone-titlebar-gestures.test.ts | phone titlebar disables panning without changing desktop titlebar or transcript touch policy | Check scoping if the project's stylesheet-test idiom makes this valuable; not a substitute for gestures |
| Required device scenario | Authorized iOS Safari and Android Chrome where available | hold and slight drag begin on app titlebar; taps remain functional | Record document, transcript and visual viewport offsets |
| Desktop reference only | crates/ui/src/shell.rs:3941-3985 | titlebar_drag_region | No native phone equivalent or invented Rust test mapping |

| Scenario | Setup | Evidence required |
| --- | --- | --- |
| App title text, blank chrome, each control | Hold, slight drag and tap; iOS/Android where available | No app-initiated pan; taps work; record scroll offsets |
| Drawer closed/left open/right open | Gesture starts inside app titlebar | Titlebar stays guarded; drawer content scroll remains normal |
| Keyboard up/down and URL bar states | Composer focused and unfocused | Separate viewport resize from transcript/document movement |
| Safe area and breakpoint | Notched phone;768px/769px boundary; desktop pointer | No layout change; desktop behavior unaffected |
| OS status bar control case | Touch outside app DOM | Document separately; no unsupported prevention claim |

## 5. Gaps

### 5.64 New-chat layout update cost

| Item | Kind | Desktop / required value | Web value / evidence | Fix or deliverable |
| --- | --- | --- | --- | --- |
| 64a | performance mechanism | Desktop evaluates shell geometry in its render model; browser implementation need not rerender React on CSS frames | Column observer writes page state per geometry tick (chat-page.tsx:735-739) | Separate live geometry from page publication |
| 64b | performance mechanism | Island scalar follows one persistent tween (shell.rs:4013-4024) | React setPainted/setPump per frame (titlebar.tsx:227-229) | Move scalar interpolation to CSS/DOM |
| 64c | evidence | User-visible smoothness must be observed | No trace establishes the largest remaining cost | Capture matched before/after trace and report measured limits |

### 5.65 New-chat background raster continuity

| Item | Kind | Desktop / required value | Web value / evidence | Fix or deliverable |
| --- | --- | --- | --- | --- |
| 65a | performance mechanism | Desktop supplies mask to fitted image paint (new_thread_background_mask.rs:74-81) | Composer observer reaches full CPU remask during dock-only motion (new-thread-background.tsx:379; sidebar-tween.ts:133) | Bound and coordinate remasking without stale mask geometry |
| 65b | visual continuity | Artwork covers current hero bounds (new_thread_background_mask.rs:65-81) | Old-width raster exposes bands when hero grows (app.css:2737-2741; ticket 57:90) | Provide continuous coverage and crop through motion |
| 65c | evidence | Current composer bounds drive mask (new_thread_background_mask.rs:49-55) | Existing isolated gate tests prove scheduling, not rendered alignment | Compare moving mask against composer in real recordings |

### 5.66 Titlebar island: restore the desktop padding-box geometry

| Item | Kind | Desktop / required value | Web value / evidence | Fix or deliverable |
| --- | --- | --- | --- | --- |
| 66a | layout | Island [6,102], width 96 without plus (shell.rs:4025-4041; Taffy flexbox.rs:2164-2167,2336-2340) | Island [16,92], width 76 (app.css:347-356,410-414) | Restore padded containing block or equivalent 20px wider island |
| 66b | regression evidence | Desktop keeps every 24px control inside island bounds | Helper tests certify the narrower bounds; helper is unused by JSX (titlebar.tsx:143; titlebar-island.test.ts:64) | Correct expectations and verify actual rendered rectangles |

### 5.72 Phone pane close: preserve expanded width until the slide ends

| Item | Kind | Desktop / required value | Web value / evidence | Fix or deliverable |
| --- | --- | --- | --- | --- |
| 72a | phone layout continuity | Desktop close holds stable inner geometry while outer width glides (shell.rs:3850-3875); no native phone drawer | Phone close removes 100vw class before transform ends (right-pane.ts:253,304; app.css:6901,6904,6919) | Retain expanded phone presentation width until close settles |
| 72b | state separation | Desktop resets takeover immediately while preserving animation geometry (shell.rs:1987-1995) | Web uses logical expanded directly for phone width | Separate transient presentation width from logical expanded state |

### 5.73 Diagnose the remaining desktop right-pane close snap

| Item | Kind | Desktop / required value | Web value / evidence | Fix or deliverable |
| --- | --- | --- | --- | --- |
| 73a | diagnostic evidence | Native close uses width tween and stable content (shell.rs:1976-1995,3850-3875) | Same-chat web code appears equivalent; actual reported trigger is unidentified | Produce reproducible trace and matched desktop comparison |
| 73b | behavior classification | Files content intentionally disappears while closing (shell.rs:6472-6475) | Web does the same (right-pane.tsx:73-79) | Separate existing product behavior from regression; request scope only if policy change is desired |

### 5.76 Phone titlebar: prevent touch panning from app chrome

| Item | Kind | Desktop / required value | Web value / evidence | Fix or deliverable |
| --- | --- | --- | --- | --- |
| 76a | gesture semantics | Desktop chrome does not pan transcript; mobile has no native analog | manipulation explicitly permits pan (app.css:326,461,504; W3C Pointer Events 3§8.3) | Phone app titlebar uses none; preserve clicks |
| 76b | diagnostic evidence | Requested app-titlebar hold should leave content stable | Prior ticket incorrectly treated manipulation as a no-pan guard; exact device scroll source unmeasured | Record app hit region and document/transcript/visual-viewport movement; distinguish OS status bar |

## 6. Open evidence and decisions

- Ticket 64 requires a baseline trace before ranking residual costs; removing a code path is not evidence that it was the dominant stall.
- Ticket 65 must prove both bounded work and visual mask/crop continuity. A scheduling-only test cannot establish image coverage.
- Ticket 66's corrected arithmetic is source-proven; actual browser/native bounds and transition captures remain pending.
- Ticket 72 concerns expanded phone close only. Ticket 73 must identify whether any ordinary desktop close regression exists.
- If ticket 73 reproduces only Files-family blanking that matches native desktop, changing that policy is a product decision. The diagnosis ticket does not authorize it.
- Ticket 76 must distinguish app titlebar touch from native OS/browser status-bar gestures and identify which scroll/viewport coordinate changes.

No code, tests, executable artifacts or application state were changed in producing this artifact.
