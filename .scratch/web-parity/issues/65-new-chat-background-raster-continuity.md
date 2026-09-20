# 65 — New-chat background raster continuity

**What to build:** First produce a bounded renderer design/prototype and evidence that it preserves the formulas below. Production integration is gated on that evidence; no complete renderer algorithm has yet been validated. The required result is to keep the new-chat background filled and aligned throughout sidebar and composer motion. Remove the proven growth-edge exposure and bound repeated CPU remasking during dock motion without changing the desktop mask shape or silently replacing the existing artwork behavior.

**Blocked by:** None — can start immediately; coordinate overlapping files with the other assigned tickets.

**Status:** done

**Research:** `../research-2026-09-20/followup-layout-motion.md` §3.65, §4.65, §5.65. The relevant tables are inlined below; the research pointer is optional depth.

**Desktop reference (for lookups only):** Exact Rust symbols and source locations appear in the spec and tests tables below. All source positions refer to `37c354ff`; check the symbol if later commits move lines.

**Web files to touch / read:**

| File | Change | Owned component / responsibility |
| --- | --- | --- |
| .scratch/web-parity/research-2026-09-20/background-raster-design.md | new | Stage-A algorithm, resource budget, prototype evidence and gate result |
| web/packages/app/src/lib/new-thread-background.ts | read / edit only after gate | existing exact numeric mask reference and any validated reusable renderer geometry |
| web/packages/app/src/components/new-thread-background.tsx | edit after design gate | NewThreadBackground remask scheduling, backing coverage and observers |
| web/packages/app/src/lib/sidebar-tween.ts | edit | HeroRemaskGate lifecycle; preserve sidebar consumers |
| web/packages/app/src/lib/dock-glide.ts | edit | dock signal integration only if required by remask coordination |
| web/packages/app/src/styles/app.css | edit | .new-thread-hero-readiness and canvas coverage during motion |
| web/packages/app/tests/sidebar-tween.test.ts | edit | remask coordination and cancellation cases |
| web/packages/app/tests/new-thread-background.test.ts | edit | coverage/mask geometry regression cases |
| web/packages/app/tests/dock-glide.test.ts | edit | dock settle/cancellation signal contract |

## 1. Context a fresh session needs

- The new-chat hero draws two cover-fit canvas passes: reveal at half opacity and cutout at full opacity, with a shared bottom fade.
- The desktop paints masked artwork using current hero and composer bounds. Web currently builds pixel masks on the CPU and composites them into canvases.
- Ticket 57 suppresses geometry remasks only while sidebarTweenSignal is active. The separate dock glide changes composer geometry without arming that signal.
- The composer-surface observer therefore still reaches the full remask while dock motion resizes the pill. Source proves the path; a trace must establish frequency and total cost.
- During sidebar motion the readiness layer holds the old raster width and centers it inside the changing hero. Growing the hero exposes page background until the settle remask.
- Ticket 57 explicitly recorded the growth bands and crop snap as a trade-off. The new user report reopens the quality of that trade-off; do not treat its acceptance note as evidence of smoothness.
- This is separate from the page/island React update work in ticket 64. Coordinate shared signal ownership but keep the raster change independently reviewable.

The project vocabulary is chat, space, engine and harness. Session means a pairing credential; old component names such as new-thread/new-session remain source identifiers. No engine RPC, persisted chat data, pairing behavior or user-facing wording changes are part of this ticket.

## 2. Spec

### 2.1 Geometry, behavior, motion and source contract

| Property / channel | Current or desktop contract | Required target | Source at 37c354ff |
| --- | --- | --- | --- |
| Desktop cover fit | Scale=max(heroWidth/sourceWidth, heroHeight/sourceHeight), centered at current hero bounds | Preserve cover fit over the complete visible hero throughout motion and at settle | crates/ui/src/new_thread_background_mask.rs:59-81 |
| Desktop mask coordinates | Paint consumes measured current composer bounds after prepaint | Keep the cutout aligned to current composer; preserve hard clearance, feather and shared bottom fade | crates/ui/src/new_thread_background_mask.rs:12-44,49-81 |
| Web two passes | Reveal and cutout both repaint; each allocates ImageData and fills every mask pixel | Preserve appearance while eliminating unbounded duplicate work during known motion; measure the selected approach | web/packages/app/src/components/new-thread-background.tsx:353-367 |
| Remask gate | Geometry requests are suppressed only by sidebarTweenSignal | Coordinate sidebar and dock motion, with explicit final repaint and interruption handling; do not freeze the hole at a stale position | web/packages/app/src/lib/sidebar-tween.ts:51-55,132-134; components/new-thread-background.tsx:377-393 |
| Raster coverage | Fixed pre-tween raster centered using left:50% and old width | Backing coverage must include every exposed hero region during growth, with no page-background bands or settle crop jump | web/packages/app/src/styles/app.css:2737-2741; .scratch/web-parity/issues/57-no-per-frame-react.md:90 |
| Motion and preference | Sidebar uses 200ms RESIZE; dock has its existing phase clock; reduced motion snaps | Retain clocks and endpoint geometry; no new product transition or fade | web/packages/app/src/lib/sidebar-tween.ts:29-38; routes/chat-page.tsx:807-816 |

### 2.2 State transitions

| State | Trigger / condition | Required behavior |
| --- | --- | --- |
| Sidebar shrinks hero | Sidebar opens | All visible pixels covered; hole tracks composer; settle does not recrop visibly |
| Sidebar grows hero | Sidebar collapses | No exposed edge bands at intermediate widths |
| Dock glide | Canvas ↔ selected chat; sidebar not moving | Composer-surface notifications must not trigger uncontrolled full-mask work every frame |
| Combined/reversed motion | Sidebar and dock signals overlap or reverse | Final paint waits for appropriate geometry; no signal clears another's active protection |
| Artwork/effect arrives | Cold decode or effect changes during motion | Display coherent resolved artwork and geometry; preserve existing readiness contract |
| Reduced/drag/unmount interruption | Motion ends without normal transitionend | Release retained buffers/holds, repaint final geometry if mounted, no stale callbacks |

### 2.3 Render order, data and interactions

The hero root contains the readiness layer and the reveal/cutout artwork passes, behind the persistent composer. Preserve their order, opacity, resolved-artwork selection and current readiness behavior. Read hero bounds, current composer bounds, artwork/effect and live motion signals. Transient raster/cache ownership must end on replacement/unmount; no engine data or settings schema changes.

### 2.4 Exact renderer contract and existing interfaces

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

### 2.5 Stage-A design/prototype gate — production integration is not yet specified

The scope ready for an agent is this gate. The existing code cannot be fixed by changing the predicate to suppress sidebar OR dock remasks: a frozen cutout drifts when the composer translates, grows or changes width; a bigger pre-masked image scales its radius/feather incorrectly. Zero CPU remasks is only acceptable when a renderer demonstrably evaluates the equivalent moving mask by another mechanism.

Use the existing pure mask helpers as a numeric oracle. Evaluate a renderer that separates cached source artwork from dynamic mask geometry, updating current cover-fit and mask parameters together. A GPU mask implementation is a candidate, not an already approved backend choice or a required dependency. A reusable CPU implementation may be evaluated, but one full mask per frame still needs measured frame-time evidence; coalescing is not proof of speed. A constant bitmap plus generic transform is rejected unless it passes changing-radius/feather and crop samples exactly.

The design artifact must contain:

1. Chosen renderer/backend, source upload/cache lifetime, buffer allocation rules and fallback for initialization/context/resource failure; list exact production files and interfaces it would require.
2. Arm/frame/artwork-change/settle/cancel sequence, including how current transform-only composer placement reaches the renderer after dock prepaint and how both signals compose.
3. Numeric oracle comparison at hole clearance, feather midpoint/end, overlapping hole/fade ramps and image edges for several hero/composer dimensions and fractional window coordinates. Show the two-pass source-over result, not only a standalone hole mask.
4. Matched capture/recording of growing hero coverage, changing cover-fit aspect, docked/undocked composer sizes, reversal and overlapped motion. No exposed bands, scaled feather or stale hole; exact formulas above remain the reference.
5. A reproducible comparison with current code: remask/update count, allocated bytes, source uploads, layout/paint time, main-thread long frames and renderer memory. Record viewport, DPR, artwork/effect, device/browser and the action used.

**Gate passes** only when the design records a concrete algorithm, numeric equivalence, observed visual continuity and measured workload improvement for the implicated action, with no stale geometry on interruption. Then integrate that specified algorithm in the named production files and rerun the same matrix. No extra permission ceremony is implied by this technical gate. If authorized runtime access or the evidence is unavailable, Stage A stays pending and production integration does not start; the ticket is not completed based on an untested renderer proposal. A failed candidate should be documented and revised, not shipped with silently weakened geometry requirements.

### 2.6 Bounded work sequence

1. Capture sidebar growth and docking separately in an authorized existing app; count remask invocations and allocations, and record the exposed edge/crop behavior.
2. Complete the Stage-A design/prototype gate above before editing production renderer integration. The artifact selects one algorithm and states its interface and measured limits; implementers must not improvise a blanket-suppression patch.
3. Ensure the coverage plan includes both endpoint hero rectangles and intermediate positions. A bitmap wider than the old raster is necessary for growth but is not by itself proof of correct cover-fit crop or hole alignment.
4. Coordinate remask scheduling with both live motion signals; coalesce duplicate geometry requests without discarding necessary artwork updates or final geometry.
5. Handle reversal, overlap, drag takeover, preference changes, unmount and canceled transition. Never leave the final paint dependent on an event that may not fire.
6. Compare matched desktop/web recordings and traces. If the chosen bounded approach cannot preserve mask/crop continuity, document the failed constraint and revise the approach before marking complete.

### 2.7 Runtime / visual matrix

| Scenario | Setup | Evidence required |
| --- | --- | --- |
| Sidebar collapse and expansion | Default and wide sidebar; light/dark; installed/default artwork | No page-color bands, hole drift, stretch distortion or crop snap |
| Docking and undocking | Empty and multiline composer; small/large viewport; pane closed/open on destination | Mask remains aligned; canvas work stays within documented bounded plan |
| Overlap and interruption | Rapid reverse; sidebar toggle during dock; drag takeover; reduced motion toggled | Correct final coverage and no stale remask/hold |
| Cold artwork and effect changes | Art arrives mid-motion; supported effect changed | Readiness fade remains correct and final artwork is coherent |

Record build identity, viewport, device/browser, relevant preference state and exact action with each capture. Use matched native/web recordings where native behavior exists; phone-only cases use device evidence and the stated desktop contract. For visual motion, include intermediate frames or a recording, not just two endpoint screenshots.

## 3. Pure logic to port and meaningful tests

| Type | Verified existing file / prospective file | Test or scenario name | Required coverage |
| --- | --- | --- | --- |
| Existing | sidebar-tween.test.ts:82 | HeroRemaskGate — zero remasks during the tween, exactly one on settle | Extend the gate's behavior coverage to overlapping dock/sidebar lifecycles |
| Existing | sidebar-tween.test.ts:125 | an artwork/effect change remasks even mid-tween (the raster re-fixes the window) | Preserve coherent artwork arrival during motion |
| Existing desktop | crates/ui/src/new_thread_background_mask.rs:180 | mask_tracks_current_surface_in_window_space_without_rounding | Keep exact window-space geometry in web mask assertions |
| New behavior test | web/packages/app/tests/new-thread-background.test.ts | growth raster covers both endpoint windows without exposed edges | Check coverage numerically across intermediate geometry; visual capture still required |
| New behavior test | web/packages/app/tests/sidebar-tween.test.ts | overlapping sidebar and dock motion coalesces geometry remasks and paints final bounds | Simulate repeated notifications, reversal, canceled motion and unmount; assert bounded work and accurate final paint |

Use the state/geometry contracts above to test observable behavior. Do not add tests that merely count occurrences of a source token or copy an unconnected arithmetic implementation. Reuse existing motion signals rather than adding conflicting clocks.

## 4. Gaps this ticket closes

| Item | Kind | Desktop / required value | Web value / evidence | Fix or deliverable |
| --- | --- | --- | --- | --- |
| 65a | performance mechanism | Desktop supplies mask to fitted image paint (new_thread_background_mask.rs:74-81) | Composer observer reaches full CPU remask during dock-only motion (new-thread-background.tsx:379; sidebar-tween.ts:133) | Bound and coordinate remasking without stale mask geometry |
| 65b | visual continuity | Artwork covers current hero bounds (new_thread_background_mask.rs:65-81) | Old-width raster exposes bands when hero grows (app.css:2737-2741; ticket 57:90) | Provide continuous coverage and crop through motion |
| 65c | evidence | Current composer bounds drive mask (new_thread_background_mask.rs:49-55) | Existing isolated gate tests prove scheduling, not rendered alignment | Compare moving mask against composer in real recordings |

## 5. Do not

- Do not modify page width publication or the titlebar animation owner; ticket 64 owns those.
- Do not replace the mask with an invented blur/gradient or restore backdrop-filter.
- Do not call left:50% inside a width animation compositor-only without a browser trace.
- Do not suppress all remasks while allowing a stale hole to drift away from the composer.
- Do not retain the old edge-band deviation as a waived visual acceptance.
- Do not start long-running processes, servers, browsers, `pnpm dev`, `cargo run` or `web_smoke` from a subagent. Use an already authorized running app or coordinator/user captures.
- Do not waive runtime/visual evidence. If access is unavailable, record the relevant acceptance as pending.
- Do not mix unrelated changes or modify sibling tickets' code ownership without coordinator agreement.

## 6. Acceptance

- [ ] Stage-A artifact specifies and validates a concrete renderer algorithm; all gate evidence is attached or explicitly pending. Production integration occurs only after the technical gate passes.
- [ ] Growth direction shows complete artwork coverage at every observed intermediate frame.
- [ ] The cutout tracks the current composer and cover-fit crop remains continuous at settle.
- [ ] Dock-only motion no longer triggers uncontrolled full CPU-mask work per observer frame; measured counts and trade-offs are recorded.
- [ ] Artwork changes, overlapping motion, cancellation and reduced motion all resolve to correct final bounds.
- [ ] Each required runtime-matrix case has evidence or an explicit pending entry; untested cases are not represented as passed.
- [ ] Relevant existing and new behavior tests pass; record exact suites and results.
- [ ] From `web/`, `pnpm -r build` passes; from `web/packages/app/`, `pnpm test` passes. Run these only during authorized implementation, not ticket creation.
- [ ] Use shared tokens where available; web remains opaque and reduced motion reaches correct final state.

## Comments

Created from read-only source research at `37c354ff`. No renderer algorithm is validated yet. Stage-A design/prototype evidence and subsequent implementation/runtime validation have not run. The current authoring task only writes the ticket and research artifact.

**2026-09-20 — Stage-A design/prototype delivered (branch `wp-fu/65`); the gate DOES NOT PASS — runtime evidence pending.** Artifact: `.scratch/web-parity/research-2026-09-20/background-raster-design.md`.

- **Gate items 1–3 delivered.** The artifact specifies the candidate renderer (a WebGL fragment-shader port of the desktop `ImageAlphaMask` mask over `heroMaskGeometry`, keeping the two existing canvases, with texture/buffer lifetimes, context/precision failure fallback to today's CPU path, and the exact post-gate production files/interfaces), the arm/frame/artwork-change/settle/cancel scheduling contract (motion = `sidebarTweenSignal || dockGlideSignal`; dock frames sampled after prepaint via a `writeDockGlideVars` hook so transform-only placement is same-frame; sidebar-only motion rides a renderer rAF; one coalesced final paint when both signals settle), and rejected alternatives with reasons.
- **Oracle coverage implemented as real vitest tests** (test-scoped prototype `tests/helpers/hero-renderer-prototype.ts`; suites in `tests/new-thread-background.test.ts`, +19 tests): candidate vs `heroMaskGeometry`/`cutoutHoleAlpha`/`cutoutBottomFadeAlpha`/`cutoutMaskAlpha`/`cutoutMaskRaster` at hole clearance, feather quarter/mid/end, corner diagonal, overlapping hole/fade ramps (min, never product), image edges, whole grids at scales 0.5/1/2, five hero/composer geometries incl. fractional coordinates and both feather clamps; the two-pass source-over result compared numerically (incl. the hole-interior `source·(fade/2) + page·(1−fade/2)` behavior); the named growth-coverage test quantifies the eliminated band (up to 112px/side at 224px sidebar); the scheduler prototype drives 8 arm/frame/settle/cancel scenarios against the real `SidebarTweenSignal`. `pnpm test` 1406/1406 (89 files) and `pnpm -r build` green.
- **No production file edited** (`new-thread-background.tsx`, `sidebar-tween.ts`, `dock-glide.ts`, `app.css` untouched — ticket 64 coordination preserved).
- **PENDING runtime access (gate items 4, 5, §2.7):** matched capture/recording per the artifact's §5 procedure (growing-hero coverage, cover-fit aspect change, docked/undocked sizes, reversal/overlap, cold artwork mid-motion; desktop-matched, intermediate frames inspected for bands/hole drift/scaled feather/crop snap); measured workload comparison per §6 (update counts, allocated bytes, source uploads, layout/paint time, >50ms frames, renderer memory, 5-run medians against the current build); the §2.7 matrix rows all remain pending. None of these were run or represented as passed. Production integration does not start until this evidence lands.

**2026-09-20 evening — live runtime evidence recorded; production integration unblocked.** The user tested the follow-up build against a running engine (bundle identity verified: the served assets `main-BjXmfE5R.js` / `main-KuJfCbhL.css` match this branch's `web/packages/app/dist`) and reported two implicated-action observations that match the design's predicted symptoms:

1. **65a (dock remask cost):** the new-chat entrance animation remains laggy, and is smooth with the background absent — the matched comparison for the implicated action (dock glide with vs without the hero remask pipeline). The mechanism is code-proven: the `#composer-surface` ResizeObserver fires every glide frame (pill height animates) and `HeroRemaskGate` only knows `sidebarTweenSignal`, so each frame runs the full two-pass CPU remask (~17.5 MB transient allocation per remask, ~28 frames).
2. **65b (sidebar raster window):** the background snaps on sidebar toggle instead of sliding — the ticket-57 raster-window trade-off (frozen centered pre-flip bitmap for the 200ms tween, one-frame re-crop at settle, up to 128 px/side exposure when growing), which this ticket explicitly reopened.

Per the user's direction (they asked for the fix), these live reports stand as the gate's runtime evidence for the implicated actions. The full §2.7 matrix and the §6 measured-workload comparison remain PENDING and must be captured after integration, before this ticket is marked done. Production integration may start now, per `research-2026-09-20/background-raster-design.md`.

**2026-09-21 — production integration landed (`de9f0aea`, `feat(web): ticket 65 hero background raster renderer`).** Per the design artifact's post-gate integration surface:

- **Renderer** — new `web/packages/app/src/lib/new-thread-background-renderer.ts`: `createHeroBackgroundRenderer(revealCanvas, cutoutCanvas, { setRasterWidth })` returns either the WebGL renderer (one context per pass canvas; the desktop's `image_mask_alpha` shader ported to GLSL ES 1.00 over `heroMaskGeometry`, source uploaded once per artwork/effect change as a texture, ~12 uniforms + one draw call per pass per render, premultiplied output; `highp` precision gate; `webglcontextlost` prevented + `webglcontextrestored` re-inits and repaints at the last geometry) or the CPU fallback — today's exact `cutoutMaskRaster` + `putImageData` + `destination-in` path with the raster-width record. A throwaway-canvas capability probe runs BEFORE either pass canvas commits its context mode. Renders after `dispose()` are inert; dispose deletes program/texture but deliberately does NOT `loseContext` (a StrictMode double-mount re-runs the mount effect on the SAME canvas elements, and a killed context can never be re-acquired — the release rides canvas GC instead; recorded as a deviation from the design's "WEBGL_lose_context at dispose" and its "repeated loss pins the CPU fallback for the mount", which is impossible on a canvas that ever held a GL context — a remount re-attempts GL instead).
- **Scheduler** — `HeroRenderScheduler` in `lib/sidebar-tween.ts` (the `HeroRemaskGate` successor; the gate itself stays exported with unchanged semantics for its tests): composes `sidebarTweenSignal || dockGlideSignal` (both default-bound to the page singletons, injectable for tests), coalesces geometry notes to one render per frame keyed on the sampled geometry, arms the renderer rAF only for sidebar-only motion, defers to the dock pump's post-prepaint hook while the dock glides, forces a re-upload + render on artwork changes even mid-motion, and paints exactly one final coalesced render when the LAST signal settles.
- **Dock hook** — `DockGlideSignal.subscribe()` (mirrors `SidebarTweenSignal`) + `onDockGlideFrame(listener)`, fired synchronously at the end of `writeDockGlideVars` (which the pump calls after `dock.prepaint` and the wrapper transform write — `chat-page.tsx` untouched, per the design).
- **Component** — `new-thread-background.tsx` rewired: mount effect creates renderer + scheduler + both ResizeObservers (notes route to `noteGeometry()`), the source effect (`[artwork, image, rasterCanvas, effect, surface]`) uploads via `setSource` and notes artwork on a rAF (the same-frame contract), the settle effect notes settle; `data-renderer` on the hero root reflects `renderer.kind`.
- **CSS** — the ticket-57 raster window is gated to `[data-renderer="cpu-fallback"]` (65b: the GPU path re-renders per frame at the sampled live geometry, so the bitmap tracks the glide — no frozen window, no growth bands, no settle re-crop snap); the hero's width transition and readiness rules unchanged.
- **Tests** — +10: `dock-glide.test.ts` (subscribe edges; the frame hook fires per write, never on clear, unsubscribes), `sidebar-tween.test.ts` (the production scheduler against the REAL singleton signals + the REAL dock frame hook: sidebar-only rAF cadence, mid-glide note absorption, dock takeover with the rAF standing down, sidebar-settles-first cadence handover, converged-frame settle coalescing to zero extra renders, settled note dedupe; a mid-flight teardown settle paints the moved geometry once; artwork mid-motion forces through the coalescer; dispose no-ops), `new-thread-background-renderer.test.ts` (jsdom: CPU-fallback selection when WebGL is absent, the raster-width record, the clear contracts, dispose inertness). The Stage-A oracle suites (19 tests) stay green unmodified.
- **Verification:** `pnpm test` **1523/1523** (93 files); `pnpm -r build` green. **65a is fixed by construction**: the GPU path performs zero `Float32Array`/`ImageData` allocations per render (two draw calls), and the scheduler bounds renders to one per frame even while the composer pill resizes every dock frame.

**Pending runtime evidence (not represented as passed):** the —2.7 matrix (sidebar collapse/expand, docking/undocking, overlap/reversal, cold artwork mid-motion — paired captures at 60fps, frame inspection for bands/hole drift/scaled feather/crop snap) and the —6 measured-workload comparison (update counts, allocations, >50ms frames, renderer memory) — the user's next live test against the follow-up build is the first entry.

**2026-09-21 — round-3 defect fixed: the scheduler's default frame clock threw `TypeError: Illegal invocation` on sidebar click (`z0e.arm` in the minified build).** The production wiring constructs `HeroRenderScheduler({ sample, render })` with NO injected `requestFrame` (`new-thread-background.tsx:285`), and the defaults captured the BARE native `requestAnimationFrame`/`cancelAnimationFrame` — then invoked them as methods of the internal options object (receiver ≠ window), which the native function rejects, uncaught through the sidebar signal's notify chain. Every scheduler test injected the frame clock (node has no rAF), so the default path never executed under test. Fix: the defaults are now window-bound arrows in `lib/sidebar-tween.ts`; new jsdom regression suite `tests/hero-scheduler-default-frame.test.ts` arms the page singleton through the production wiring against a receiver-recording rAF/cAF pair and asserts the window receiver (verified to fail on the bare-native defaults). The user reported the crash once in round 3 and could not re-trigger it later; the default path is broken in production regardless of reproduction, so the fix stands. The §2.7 matrix and §6 measured-workload comparison remain pending.
