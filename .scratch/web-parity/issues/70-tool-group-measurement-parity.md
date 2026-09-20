# 70 — Make tool-group estimates match actual rail and detail geometry

**What to build:** Tool groups should mount at the height they actually render, so a long open rail does not shrink after first measurement. Correct the verified 38-versus-32 px estimate error and share effective-state geometry with the renderer. Preserve all current automatic opening, thought-collapse, and reservation behavior.

**Blocked by:** None — bounded geometry work is specified. Tickets 68/71 own separate policy choices and are not prerequisites for correcting the verified value.

**Status:** ready-for-agent

**Research:** `../research-2026-09-20/followup-transcript-state-geometry.md` §3.3, §4.3, §4.5, §5.3. Relevant tables are copied verbatim below.

**Desktop reference (for lookups only):** `crates/ui/src/transcript.rs` at `37c354ff`; exact owning functions and lines are in the tables.

**Web files to touch (future implementation only):**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/components/transcript.tsx` | edit | estimateRowHeight toolGroup branch, geometry inputs, and bounded validity/invalidation of affected tool-row measurements |
| `web/packages/app/src/components/tool-group.tsx` | edit | Effective-open/detail resolution and analytic heights reused from pure helpers |
| `web/packages/app/src/lib/tool-group-geometry.ts` | new if appropriate | Shared side-effect-free geometry and effective-state resolver |
| `web/packages/app/src/lib/transcript.ts` | edit only if needed | Existing geometry constants/helpers; preserve chipsHeight standalone semantics |
| `web/packages/app/src/lib/tool-motion.ts` | edit only if needed | Read-only state access for shared geometry; no new fetch side effects |
| `web/packages/app/tests/transcript-model.test.ts` | edit | Independent rail expected values and effective-state matrix |
| `web/packages/app/tests/chat-arrival.test.ts` | edit if needed | Near-threshold/controller geometry scenario and late measurement behavior |

## 1. Context a fresh session needs

- Vocabulary: chat, transcript, turn, engine, harness; Session refers only to a pairing credential.
- `TranscriptStore` publishes entries; `TranscriptSurface` derives rows; `TranscriptScroller` calculates prefix sums from estimates or ResizeObserver measurements.
- `ToolGroupMotionStore` owns folds/reveal clocks. `ToolGroupRow` renders summary, rail chips, expandable invocation/detail bodies, and existing affordances.
- `StickController` owns pin/follow and own-turn state. A just-sent prompt receives a reservation of reply space that streaming content consumes.
- Tickets 40 and 58 already preserve rows during reset and hard-restore chat navigation; their historical implementation is not this ticket.
- All line references are from `37c354ff`; recheck when implementing after other merges.
- Current authorization is documentation only. The implementation sequence and checks below are future work.
- The verified estimate discrepancy is +6 px per collapsible tool: 26+2+38n is estimated, while actual settled rail height is 26+2+32n before detail additions.
- CHIP_HEIGHT=38 is correct for standalone cards. Changing that constant globally would introduce a regression.
- Possible premature runway retirement from estimates is a runtime hypothesis; the arithmetic mismatch itself is proven.

## 2. Spec

### 2.1 Contract and state

| Property | Desktop / actual web rendering | Current web estimate | Target |
| --- | --- | --- | --- |
| Closed collapsible header | 26 px (`crates/ui/src/transcript.rs:120`; `web/packages/app/src/lib/transcript.ts:408`) | 26 px (`web/packages/app/src/components/transcript.tsx:1446-1447`) | Preserve |
| Open collapsible rail body | 2 px top pad + 32 px per tool before detail additions (`crates/ui/src/transcript.rs:6000-6023,6080`; `web/packages/app/src/components/tool-group.tsx:131,196`) | chipsHeight(n) = 2 + 38n, so +6 px per tool (`web/packages/app/src/components/transcript.tsx:1449`; `web/packages/app/src/lib/transcript.ts:392,1047-1051`) | Use the same rail-height calculation as rendering |
| Standalone spawn cards | CHIP_HEIGHT=38; no collapsible summary header (`crates/ui/src/transcript.rs:5846-5848,6000-6004`) | chipsHeight(n) (`web/packages/app/src/components/transcript.tsx:1443-1444`) | Preserve standalone geometry; do not globally change CHIP_HEIGHT |
| Effective group open | Explicit group pin, else autoOpen OR arrivalPending (`web/packages/app/src/components/tool-group.tsx:127-129`) | Explicit group pin, else autoOpen only (`web/packages/app/src/components/transcript.tsx:1446`) | Share effective-open resolution; retain reduced-motion behavior |
| Detail open and payload | Detail pin/default, invocation, effective fetched detail, affordance (`web/packages/app/src/components/tool-group.tsx:151-175`) | Only unresolved thought doc detail/invocation (`web/packages/app/src/components/transcript.tsx:1450-1456`) | Use the same effective detail state and analytic additions; never fetch from the estimator |
| Measurement and runway | Measured heights replace estimates; fill check may retire runway (`web/packages/app/src/components/transcript.tsx:700-706,742-751`; `web/packages/app/src/components/stick-controller.ts:648-655`) | Excess estimate can affect fill before measurement | Eliminate known model disagreement; verify estimate-dependent early retirement as a runtime hypothesis |

### 2.2 Motion

| What | Trigger | Current behavior | Target and reduced motion |
| --- | --- | --- | --- |
| Group body | Open/closed and reveal state | Analytic row heights multiplied by reveal progress, then fold tween (`web/packages/app/src/components/tool-group.tsx:163-202`) | Shared geometry accepts an explicit timestamp/reduced-motion input so the estimate and renderer agree for the same state |
| Detail body | User toggle or unresolved thought default | Adds analytic detailHeight, tween only with an active toggle timestamp (`web/packages/app/src/components/tool-group.tsx:155-175,314-328`) | Preserve behavior; ticket 71 owns any policy change |
| Late measurement | ResizeObserver after virtualized mount | Prefix sums converge and controller corrects scrolling | Test near reservation fill threshold and after 500 ms; no broad virtualizer or spring rewrite |

### 2.3 Data and interactions

| Data / interaction | Reads | Writes / effects | Constraint |
| --- | --- | --- | --- |
| Geometry resolver | Row tools, folds, reveal progress, effective details, affordances, timestamp, reduced-motion flag | Pure scalar/header/body/detail heights | One shared contract for renderer and estimator |
| Estimator | Existing motion-store state and loaded payloads | No side effects | No RPC, blob fetch, subscriptions, or fold mutations |
| Actual render | Same geometry snapshot | DOM height and existing motion subscriptions | Keep row markup, typography, tokens, and accessibility unchanged |
| Reservation | Current prefix sums and natural heights | Existing floor/fill behavior | Reservation semantics and auto-open policy stay outside this ticket |

**Children in order:** existing group summary header, revealed rail/standalone cards, invocation/detail bodies and optional affordances; unchanged transcript trailer/spacer.

**Text / keyboard:** preserve existing labels, buttons, keyboard activation, tokens, opacity policy, and markup order. No new product strings or RPCs.

### 2.4 Bounded geometry contract

For the same row state, fold state, effective payloads, timestamp, and reduced-motion flag, renderer and estimator must compute the same summary/body height. Resolve group open using an explicit pin if present, otherwise autoOpen or active arrivalPending. Resolve each detail using the detail pin/default and the effective loaded payload; include invocation, fetched detail, and existing affordance exactly as rendering does.

For settled open collapsible groups, expected natural content height is header 26 + body top pad 2 + sum of 32 px rail rows and open detail additions. Three plain tools total 124 px. Closed groups total 26 px. Standalone spawn cards retain 38 px rows without summary header. Empty/zero-tool edge cases follow current rendering rather than a guessed generic formula.

A pure shared resolver can receive pre-resolved inputs from the component/store. It must not fetch blobs or mutate folds. If it consumes current reveal/fold tween progress, use one caller-provided timestamp so estimates do not disagree with rendering merely because time was read twice. Preserve existing reduced-motion endpoint behavior.

Continue using actual measured DOM heights when they correspond to the current effective tool geometry. This ticket does not redefine prefix-sum virtualization, global measurement invalidation, reservation retirement thresholds, or browser scroll anchoring; the bounded tool-row cache validity rule below is in scope. If comparison reveals a separate DOM/layout defect, report its exact scope rather than folding a virtualization rewrite into this change.

#### Fetched payloads and measured-height cache boundary

effectiveDetail selects the most recently requested READY blob, otherwise doc detail (`web/packages/app/src/components/tool-group.tsx:803-817`). effectiveAffordance handles shown ref, loading, manual retry, and ready-but-not-shown recency toggles (`:820-879`). beginBlobFetch updates request order before ready/loading guards and bumps state on ready recency selection and asynchronous completion (`web/packages/app/src/lib/tool-motion.ts:757-787`). Share these existing rules rather than inventing a payload preference.

The estimator only reads that state: never call beginBlobFetch, change request order, bump versions, publish heights, or invoke sync/noteRendered. Mounted rows already subscribe to motion state (`tool-group.tsx:117`); DOM changes reach measured heights through ResizeObserver with a 0.5 px threshold (`components/transcript.tsx:775-791`). Cached measurements take precedence over estimates (`:704-706`). Therefore enriching only the fallback is insufficient when an unmounted row's in-flight fetch completes and its old measured height remains cached.

Bound the correction to tool rows. Associate a cached tool measurement with the effective geometry inputs it represents (row/detail/fold state and chosen ready payload/affordance). Reuse matching measurements; invalidate or replace only the affected stale tool-row measurement with the pure analytic result when semantic inputs change. Use one scroller-level semantic invalidation path if needed; do not subscribe each unmounted row, write measurement results back to the motion store, add geometry to sync-effect dependencies, invalidate unrelated markdown/user rows, or use timestamp-only cache keys that force perpetual invalidation. Repeated reads of unchanged state must cause no fetches or notifications.

The chain is user fetch/ready-recency click → motion data change → effective geometry change → bounded height update → existing layout correction. It stops when data and measurement agree. Test completion while mounted and while unmounted followed by remount. Existing payload selection and fold policy stay unchanged; whether thought completion should stay open remains unresolved in ticket 71.

### 2.5 Future implementation sequence

1. Add independent assertions for one/three rail tools (60/124 px) and closed 26 px, preserving standalone cards' 38 px rule; confirm old expectations that called chipsHeight were self-confirming.
2. Extract the minimum shared geometry/effective-state logic used by both renderer and estimator. Pass read-only store state/effective details explicitly.
3. Replace the estimator's rail branch; keep user/markdown/error estimates and transcript markup untouched.
4. Add matrix coverage for explicit group/detail pins, unresolved/resolved thoughts, fetched output+affordance, arrival-only open, mid-tween timestamp, and reduced motion.
5. Exercise a group near the runway fill threshold before and after measurement; distinguish arithmetic proof from runtime retirement evidence.
6. Capture desktop/web group geometry and scroll sequence on virtualized mount, late measurement, and chat switch; run future checks.

## 3. Pure logic and tests

Suggested signature shape: pure geometry(inputs, now, reduced) → headerHeight, rowHeights, bodyHeight, totalHeight, motionActive. Exact placement/names are implementation choices. Do not duplicate detail-open rules in the caller just to share a summation helper.

| Existing or proposed | Test and owner | What it proves / must prove |
| --- | --- | --- |
| Existing web estimates | `a closed group estimates its header only`, `an auto-open group estimates the open height (header + chips)`, `a user-pinned-open group estimates the open height; a pinned-closed one the header`, `a live thought chip's detail rides the open estimate`, `a spawn-only group still estimates its unwrapped chips` in `web/packages/app/tests/transcript-model.test.ts:1353-1401` | Some current expected values reuse the wrong chipsHeight helper; replace those expectations with independent rail geometry |
| Existing analytic helpers | `chips_height_is_analytic`, `detail_height is analytic per kind` in `web/packages/app/tests/transcript-model.test.ts:498,504`; desktop `chips_height_is_analytic` in `crates/ui/src/transcript.rs:11117` | Standalone CHIP_HEIGHT must remain 38; these do not establish rail height |
| Existing payload parsing | `blob_detail parses diff JSON and renders uncapped output` in `web/packages/app/tests/transcript-model.test.ts:485` | Parsing/line caps only; no existing beginBlobFetch/recency/measurement lifecycle coverage was found in app tests |
| Proposed geometry state | `fetched_detail_geometry_is_read_only_and_respects_request_recency` in `web/packages/app/tests/transcript-model.test.ts` | Use real motion store with deferred blob completion and a ready-ref recency click; repeated geometry reads produce no fetch/order/version changes and include the currently selected payload/affordance |
| Proposed cache boundary | `late_blob_completion_invalidates_only_affected_tool_measurement` in `web/packages/app/tests/transcript-model.test.ts`, plus the mounted harness/browser acceptance | Simulate measured row unmount, deferred payload completion, and remount; stale tool height is rejected, unrelated measurements survive, and unchanged follow-up inputs stop invalidation/notifications |
| Proposed | `rail_group_estimate_matches_rendered_geometry` in `web/packages/app/tests/transcript-model.test.ts` | Independent expected totals: one tool 26+2+32=60; three tools 26+2+96=124; closed header 26 |
| Proposed | `group_geometry_resolves_pins_arrivals_and_effective_details` in the same file | Matrix includes default/pinned detail, fetched payload/affordance, arrival-only open, reduced motion, and timestamped mid-tween state |
| Proposed integration | `unmeasured_rail_group_does_not_prematurely_fill_runway` in existing controller/geometry tests plus mounted/browser acceptance | Near-threshold geometry does not retire a held runway solely from the removed 6 px/tool excess |

## 4. Gaps this ticket closes

| Item | Kind | Desktop value | Web value (file:line) | Fix |
| --- | --- | --- | --- | --- |
| Collapsible rail estimate | VERIFIED WRONG VALUE | Actual rail rows 32 px (`crates/ui/src/transcript.rs:6000-6023`) | Estimator uses 38 px standalone chips (`web/packages/app/src/components/transcript.tsx:1449`; `web/packages/app/src/lib/transcript.ts:1047-1051`) | Shared rail geometry; preserve standalone 38 px |
| Estimate effective state | VERIFIED OMITTED INPUTS | Renderer resolves pins, effective details, and arrivals (`crates/ui/src/transcript.rs:5849-5859,5874-6023`) | Estimator omits arrival open, detail pins, fetched payload/affordance (`web/packages/app/src/components/transcript.tsx:1446-1456`) | Shared side-effect-free effective-state/height resolver; verify near runway fill threshold |

## 5. Do not

- Do not change global CHIP_HEIGHT to 32; spawn/standalone cards remain 38.
- Do not remove arrivalPending or automatic thought closure; policy is tickets 68/71.
- Do not add blob fetches to estimates, subscribe every virtual row, or move engine data into presentation caches.
- Do not rewrite virtualization, spring, reservation semantics, or responsive thought text wrapping.
- Do not change Rust geometry, which already uses the correct rail row size.
- Do not add frosted surfaces or port desktop-only GPUI list internals to the web.
- Do not mark source-derived hypotheses or pure tests as runtime visual proof.

## 6. Acceptance

- [ ] One/three plain open rail tools compute and render 60/124 px before external row gap/padding; closed header remains 26 px.
- [ ] Standalone spawn cards preserve 38 px row sizing and no collapsible summary.
- [ ] Estimator and renderer share effective-open/detail/payload geometry rather than duplicate rules.
- [ ] Fetched payload completion/ready-recency selection updates the affected measured or estimated tool geometry even after unmount; repeated unchanged geometry reads trigger no fetches, state bumps, or invalidation loop.
- [ ] Independent expected-value tests cover pinned states, arrival-only open, fetched payload/affordance, and reduced motion.
- [ ] Near-threshold runway validation demonstrates whether the old excess caused early retirement; no claim is made without that evidence.
- [ ] Existing live opening/folding, group markup, and thought line rendering remain unchanged.
- [ ] Paired desktop/web screenshots plus short motion captures cover the matrix below. Use the existing app or coordinator-operated captures; subagents must not start long-running server/app/browser processes. If captures are unavailable, leave visual acceptance pending; do not waive it.
- [ ] Future `pnpm -r build` from `web/` and `pnpm test` from `web/packages/app/` pass after implementation.
- [ ] If Rust changes, future `cargo check -p roboco-ui` and `cargo test -p roboco-ui` pass.
- [ ] Reduced motion and desktop/phone geometry are checked; no arbitrary new values replace shared tokens.

| State / capture | Check |
| --- | --- |
| Completed group closed/open, one and many tools | Header/rail estimate equals actual settled geometry |
| Streaming thought, detail explicitly open/closed, fetched output | Correct effective additions; no fetch triggered by estimate |
| A→B→A and virtual row unmount/remount | Geometry correct independent of ticket 68's fold persistence policy |
| Late measurements after 500 ms | Removed estimate mismatch does not produce the old content-extent correction |
| Own-send held near fill; released reservation | Natural height and fill transition agree after measurement |
| Retired runway+pinned; manually escaped transcript | Geometry fix does not introduce a new scroll owner or discard anchor |

## Comments

Documentation created from read-only source inspection at `37c354ff`. No implementation, runtime capture, tests, or builds have been performed. Proposed test names identify future coverage.
