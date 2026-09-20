# Follow-up research: transcript state, replay, and geometry

Baseline: `37c354ff`, branch `web-parity/wave-2`. Sources were inspected on 2026-09-20. All paths and line numbers below refer to that baseline; recheck if implementation begins after other changes.

Scope: user reports 4 and 6 from `C:/Users/ADMIN/AppData/Local/Temp/opencode/roboco-web-parity-handoff-2026-09-20.md`. Prior findings in `../issues/40-transcript-stability.md`, `../issues/58-chat-switch-snap.md`, and `../research-2026-09-19/transcript-tool-calls-scroll.md` were read first. This document records residual mechanisms, not a reimplementation of those tickets.

## 1. Evidence and limits

The user's reports concern closed tool groups reopening and downward scrolling on chat switches; own-send whitespace, tool/thought collapse, and scroll jumps. Read-only source inspection confirms several mechanisms. No runtime reproduction, screenshots, tests, build, server, browser process, source edit, or git mutation was performed for this research. Current authorization is documentation only.

A real A→B→A switch loses fold pins in both clients. Reopening a streaming group is therefore possible even when replay animation is correctly suppressed. A completed group without an explicit pin still defaults closed; do not use pin loss alone to explain every completed-group report.

The React reconnect hypothesis is narrower: the store publishes pending and populated within one synchronous callback, while a passive effect requires observing pending. Source proves that dependency, not whether a particular user reproduction coalesced those publications. Cache paint can precede the live reset. An integrated consumer test must establish the event order.

The web rail estimate is provably too large: 38 px standalone-chip sizing is used for actual 32 px rail rows. Subsequent measurement changes content extent. Whether this caused premature runway retirement or the observed scroll jump requires runtime validation.

The handoff's suggested desktop compensation reference is misleading for tool folds: desktop compensation exists for user prompt Show more/less, not group or thought detail toggles. Automatic thought detail collapse without a toggle timestamp also snaps on desktop.

## 2. Ownership and prospective direction

- Ticket 68: unresolved shared desktop/web fold persistence and auto-open policy; needs-info.
- Ticket 69: diagnose and correct replay-baseline identity at the store/React boundary; independent of manual fold persistence.
- Ticket 70: bounded shared geometry correction in web estimation/rendering; no spring or virtualizer redesign.
- Ticket 71: unresolved shared tool-fold scroll ownership and automatic thought-collapse policy; needs-info.
- The large own-send reply reservation remains established behavior. Removing it is not authorized by a report that mentions whitespace.
- The source entry's streaming flag controls autoOpen; sending a user prompt does not itself call a "reopen latest tool group" action. Trace source status/replay and live arrivals before claiming that connection.

## 3. Contracts, states, motion, and data

### 3.1 Ticket 68: chat-switch fold policy

| Item | Current desktop and web contract at `37c354ff` | Proposed target / decision | Source |
| --- | --- | --- | --- |
| Actual chat switch | Desktop clears folds on changed selected chat; web remounts the surface and creates a new motion store | DECISION REQUIRED: remember explicit group/detail choices in a bounded per-chat cache on both clients | `crates/ui/src/transcript.rs:3948,3978`; `web/packages/app/src/components/transcript.tsx:216,339-341` |
| Quick deselect and return | Desktop retains the same entity before route exit completes; this is not general A→B→A persistence | Preserve this path; exercise real A→B→A separately | `crates/ui/src/transcript.rs:3908-3916,7894-7915` |
| Default group open | Streaming source entry plus its last part sets auto-open; rendered resolution is explicit pin, else auto-open OR arrival pending | Recommended: preserve the live default; restored explicit closed pins override it. Alternative: default all groups closed, requiring explicit approval on both clients | `crates/ui/src/transcript.rs:1265,5849-5859`; `web/packages/app/src/lib/transcript.ts:1547`; `web/packages/app/src/components/tool-group.tsx:127-129` |
| Replay animation | First populated attach establishes a baseline without arrival starts; suppressing replay does not force groups closed | Existing/replayed rows do not gain new reveal starts, independent of chosen fold policy | `crates/ui/src/transcript.rs:4063-4113`; `web/packages/app/src/lib/tool-motion.ts:685-729` |
| Restore versus new content | Both retain scroll/own-turn state separately from fold state | Restore fold choices before calculating restored viewport; preserve live arrivals after the baseline | `crates/ui/src/transcript.rs:3960-4016`; `web/packages/app/src/components/transcript.tsx:945-1028` |
| Persistence identity | Fold maps use row IDs; the web routes paired engines | Key any new cache by engine identity plus chat/doc and stable group/detail identity; define eviction and vanished-row behavior before implementation | `web/packages/app/src/routes/chat-page.tsx:209-212`; `web/packages/app/src/components/tool-group.tsx:153`; `web/packages/app/src/lib/tool-motion.ts:618-644` |

| What | Trigger | Current behavior | Target and reduced motion |
| --- | --- | --- | --- |
| Arrival reveal | Newly counted tool rows | Future starts at 90 ms for a new group plus 65 ms per arrival; arrival-pending contributes to open (`crates/ui/src/transcript.rs:123-136,5849-5859`) | Replay never schedules these starts; genuine live arrivals retain existing timing unless the user selects global removal; reduced motion keeps existing instant resolution |
| Fold toggle | Explicit user click | 140 ms EASE_OUT on desktop; web shared fold motion (`crates/ui/src/transcript.rs:123`; `web/packages/app/src/components/tool-group.tsx:314-328`) | Keep existing click motion and reduced-motion snap; restoring a saved choice has no new click/tween timestamp |
| Scroll restore | Chat selection | Hard restore from estimates; arrival kicks hard-write end for pinned views (`web/packages/app/src/components/transcript.tsx:973-981`; `web/packages/app/src/components/stick-controller.ts:171-182`) | Restored folds inform geometry before restore; no newly invented scroll choreography |

| Data / interaction | Reads | Writes / effects | Constraint |
| --- | --- | --- | --- |
| Group click | Row ID, effective auto-open, current fold | Local group fold pin | No engine RPC |
| Detail click | Stable tool/detail identity, default open | Local detail fold pin | Do not persist fetched output payloads as fold preferences |
| Chat switch | Incoming cached pins and viewport | Restore local state before paint | Cache design must distinguish engines and preserve outgoing capture ordering |
| New live tool | Current doc and reset baseline | Existing reveal state | A navigation policy must not silently disable live tool updates |

Recommended decision: remember explicit open/closed group and detail choices by engine/chat and stable item identity; suppress replay starts on navigation while preserving real live-arrival/default behavior. Alternatives are current ephemeral pins, or explicitly disabling automatic opening across both clients. No option has been selected.

### 3.2 Ticket 69: authoritative replay baseline

| State | Current code and evidence | Target condition |
| --- | --- | --- |
| Cached initial rows | Cache load calls seedEntries; loaded=true and replay=populated before the live watch reset (`web/packages/app/src/state/transcript-store.ts:353-362,415-423`) | Cached rows may paint, but the later authoritative reset is independently identifiable as a baseline |
| New connection generation | One onItem call commits pending, applies reset, then commits populated (`web/packages/app/src/state/transcript-store.ts:452-496,520-524`) | Baseline recognition must not depend on React rendering the intermediate pending snapshot |
| Surface consumption | Passive effect rearms a boolean only when it observes pending; dependency list lacks generation (`web/packages/app/src/components/transcript.tsx:342-361`) | Consume a durable store-owned replay/reset epoch; same-generation resubscribe resets also increment it |
| Desktop reference | Attach or pending arms veil_attach_pending; populated baseline clears reveal/tween clocks before counting tools (`crates/ui/src/transcript.rs:3948-3954,4063-4113`) | Existing reset rows have no new reveal starts; explicit pins remain; true post-reset additions remain eligible |
| Arrival timer | 50 ms measurement quiescence, 500 ms hard cap; armed once per scroller mount (`web/packages/app/src/lib/chat-arrival.ts:32-35,68-77`; `web/packages/app/src/components/transcript.tsx:953-968`) | Diagnose delayed reset/measurement independently of mount age; apply reset baseline before row paint without globally lengthening or disabling live motion |
| Scroll restore | Initial hard write from estimates; pinned arrival kicks write changing end (`web/packages/app/src/components/transcript.tsx:973-1028`; `web/packages/app/src/components/stick-controller.ts:171-182`) | Preserve saved anchor/runway on same-chat reset; baseline processing must not repeat initial viewport restore or scroll to end as a reset side effect |
| Evidence limit | Source proves synchronous publications, effect dependency, and missing durable reset identity; mounted React coalescing was not reproduced | Reproduce the integrated sequence before claiming it caused the reported bug; log snapshot/reset epoch and baseline consumption, not just listener callbacks |

| What | Trigger | Current behavior | Target and reduced motion |
| --- | --- | --- | --- |
| Replay reveal | Tools absent from old count after an unrecognized reset | sync schedules starts; arrival gate does not guard these assignments (`web/packages/app/src/lib/tool-motion.ts:708-729`) | Baseline reset rows before live-delta counting; reduced motion must also preserve logical pins and anchor |
| Fold seeding | Rendered open state flips | Arrival only suppresses noteRendered tween timestamps (`web/packages/app/src/lib/tool-motion.ts:657-671`) | Replay baseline is established before rendering affected groups; do not change their autoOpen policy |
| Arrival scroll | Pinned kick during arrival | Hard end write; after cap regular spring may resume (`web/packages/app/src/components/stick-controller.ts:170-182`) | Reset must not engage pin or disturb escaped/held state; late live content keeps existing follow behavior |

| Data / interaction | Reads | Proposed writes / effects | Constraint |
| --- | --- | --- | --- |
| Accepted reset | Connection generation, successful reset frame, baseline entries | Store-local monotonic reset epoch and durable baseline identity/count information | Increment only after a valid accepted reset; stale generations and malformed frames must not create a baseline |
| Cache seed | Cached entries | Distinct seed provenance or initial epoch | Authoritative live reset must not be mistaken for a continuation of cache |
| Surface sync | Epoch, baseline rows/counts, latest rows | Clear reveal/tween clocks for reset history, then classify post-reset additions | Works when pending was never rendered and when reset plus later deltas are coalesced |
| Resubscribe | Existing rows, loaded flag, same or new generation | Keep rows; identify eventual accepted reset | No protocol/RPC change; generation alone is insufficient for same-generation reset |

An epoch must represent accepted reset boundaries, not just a WebSocket generation: resubscribe may reset within the same generation. If reset and later delta coalesce before rendering, retain enough baseline identity/count information to distinguish reset history from genuine additions. A hook observing only the final rows cannot infer that distinction from a boolean replay flag.

### 3.3 Ticket 70: tool geometry

| Property | Desktop / actual web rendering | Current web estimate | Target |
| --- | --- | --- | --- |
| Closed collapsible header | 26 px (`crates/ui/src/transcript.rs:120`; `web/packages/app/src/lib/transcript.ts:408`) | 26 px (`web/packages/app/src/components/transcript.tsx:1446-1447`) | Preserve |
| Open collapsible rail body | 2 px top pad + 32 px per tool before detail additions (`crates/ui/src/transcript.rs:6000-6023,6080`; `web/packages/app/src/components/tool-group.tsx:131,196`) | chipsHeight(n) = 2 + 38n, so +6 px per tool (`web/packages/app/src/components/transcript.tsx:1449`; `web/packages/app/src/lib/transcript.ts:392,1047-1051`) | Use the same rail-height calculation as rendering |
| Standalone spawn cards | CHIP_HEIGHT=38; no collapsible summary header (`crates/ui/src/transcript.rs:5846-5848,6000-6004`) | chipsHeight(n) (`web/packages/app/src/components/transcript.tsx:1443-1444`) | Preserve standalone geometry; do not globally change CHIP_HEIGHT |
| Effective group open | Explicit group pin, else autoOpen OR arrivalPending (`web/packages/app/src/components/tool-group.tsx:127-129`) | Explicit group pin, else autoOpen only (`web/packages/app/src/components/transcript.tsx:1446`) | Share effective-open resolution; retain reduced-motion behavior |
| Detail open and payload | Detail pin/default, invocation, effective fetched detail, affordance (`web/packages/app/src/components/tool-group.tsx:151-175`) | Only unresolved thought doc detail/invocation (`web/packages/app/src/components/transcript.tsx:1450-1456`) | Use the same effective detail state and analytic additions; never fetch from the estimator |
| Measurement and runway | Measured heights replace estimates; fill check may retire runway (`web/packages/app/src/components/transcript.tsx:700-706,742-751`; `web/packages/app/src/components/stick-controller.ts:648-655`) | Excess estimate can affect fill before measurement | Eliminate known model disagreement; verify estimate-dependent early retirement as a runtime hypothesis |

| What | Trigger | Current behavior | Target and reduced motion |
| --- | --- | --- | --- |
| Group body | Open/closed and reveal state | Analytic row heights multiplied by reveal progress, then fold tween (`web/packages/app/src/components/tool-group.tsx:163-202`) | Shared geometry accepts an explicit timestamp/reduced-motion input so the estimate and renderer agree for the same state |
| Detail body | User toggle or unresolved thought default | Adds analytic detailHeight, tween only with an active toggle timestamp (`web/packages/app/src/components/tool-group.tsx:155-175,314-328`) | Preserve behavior; ticket 71 owns any policy change |
| Late measurement | ResizeObserver after virtualized mount | Prefix sums converge and controller corrects scrolling | Test near reservation fill threshold and after 500 ms; no broad virtualizer or spring rewrite |

| Data / interaction | Reads | Writes / effects | Constraint |
| --- | --- | --- | --- |
| Geometry resolver | Row tools, folds, reveal progress, effective details, affordances, timestamp, reduced-motion flag | Pure scalar/header/body/detail heights | One shared contract for renderer and estimator |
| Estimator | Existing motion-store state and loaded payloads | No side effects | No RPC, blob fetch, subscriptions, or fold mutations |
| Actual render | Same geometry snapshot | DOM height and existing motion subscriptions | Keep row markup, typography, tokens, and accessibility unchanged |
| Reservation | Current prefix sums and natural heights | Existing floor/fill behavior | Reservation semantics and auto-open policy stay outside this ticket |

A shared pure geometry resolver may accept already-resolved inputs rather than acquiring data itself. Use a single timestamp per estimate/render comparison. Keep rendering side effects, subscriptions, and fetched payload acquisition in the current motion-store/component owners.

#### Fetched payloads and measured-height cache boundary

effectiveDetail selects the most recently requested READY blob, otherwise doc detail (`web/packages/app/src/components/tool-group.tsx:803-817`). effectiveAffordance handles shown ref, loading, manual retry, and ready-but-not-shown recency toggles (`:820-879`). beginBlobFetch updates request order before ready/loading guards and bumps state on ready recency selection and asynchronous completion (`web/packages/app/src/lib/tool-motion.ts:757-787`). Share these existing rules rather than inventing a payload preference.

The estimator only reads that state: never call beginBlobFetch, change request order, bump versions, publish heights, or invoke sync/noteRendered. Mounted rows already subscribe to motion state (`tool-group.tsx:117`); DOM changes reach measured heights through ResizeObserver with a 0.5 px threshold (`components/transcript.tsx:775-791`). Cached measurements take precedence over estimates (`:704-706`). Therefore enriching only the fallback is insufficient when an unmounted row's in-flight fetch completes and its old measured height remains cached.

Bound the correction to tool rows. Associate a cached tool measurement with the effective geometry inputs it represents (row/detail/fold state and chosen ready payload/affordance). Reuse matching measurements; invalidate or replace only the affected stale tool-row measurement with the pure analytic result when semantic inputs change. Use one scroller-level semantic invalidation path if needed; do not subscribe each unmounted row, write measurement results back to the motion store, add geometry to sync-effect dependencies, invalidate unrelated markdown/user rows, or use timestamp-only cache keys that force perpetual invalidation. Repeated reads of unchanged state must cause no fetches or notifications.

The chain is user fetch/ready-recency click → motion data change → effective geometry change → bounded height update → existing layout correction. It stops when data and measurement agree. Test completion while mounted and while unmounted followed by remount. Existing payload selection and fold policy stay unchanged; whether thought completion should stay open remains unresolved in ticket 71.

### 3.4 Ticket 71: tool-fold viewport and thought policy

| Item | Current desktop and web contract | Proposed policy / decision |
| --- | --- | --- |
| Own-send blank runway | Desktop reserves viewport space below own prompt (`crates/ui/src/transcript.rs:3485-3508`); web mirrors it (`web/packages/app/src/components/transcript.tsx:711-723,931-939`) | Retain reservation and its existing retirement rule unless the user explicitly approves a separate change |
| User prompt fold compensation | toggle_user_fold releases navigation hold and anchors the prompt on an interpolated screen path (`crates/ui/src/transcript.rs:4376-4437`; `web/packages/app/src/components/transcript.tsx:1164-1218`) | Already ported; do not claim it applies to tool groups |
| Tool group click | Only updates fold state; neither implementation calls user-fold scroll compensation (`crates/ui/src/transcript.rs:4518-4525`; `web/packages/app/src/lib/tool-motion.ts:618-628`) | DECISION REQUIRED: recommended explicit group/detail clicks own the viewport, preserve clicked header, and release active follow/hold while retaining any reservation |
| Automatic thought detail close | Unresolved thought defaults open, resolved defaults closed; no user toggle clock means immediate target height (`crates/ui/src/transcript.rs:5983-5986,6025-6040`; `web/packages/app/src/components/tool-group.tsx:155-175,314-328`) | DECISION REQUIRED: keep current close, animate close with scroll ownership, or keep visible thought open after completion; apply chosen policy to both clients |
| Group auto-close | Rendered open-state flip seeds group fold tween (`crates/ui/src/transcript.rs:5858-5870`; `web/packages/app/src/lib/tool-motion.ts:657-671`) | Separate from inner thought closure and replay; decide whether automatic closure may move an escaped viewport |
| Thought line shape | Prewrap at 96 columns, then fixed 18 px rows truncated to available width (`crates/ui/src/transcript.rs:387,6773-6788`; `web/packages/app/src/lib/transcript.ts:351`; `web/packages/app/src/styles/app.css:9021-9038`) | Keep established geometry in this ticket; responsive prose wrapping would require a separately approved height-model change |

| What | Trigger | Current behavior | Target / decision and reduced motion |
| --- | --- | --- | --- |
| Explicit group fold | Header click | 140 ms EASE_OUT; no dedicated scroll owner | Recommended header anchor owns any compensation; choose parity behavior on both clients; snap geometry/anchor together under reduced motion |
| Inner thought default | unresolved→resolved without a user pin | Height snaps to closed target | Select retain-open or controlled close before coding; no guessed duration or automatic reuse of user-prompt timing |
| Own-turn runway | Send, streaming fill, wheel escape | Held or released reservation; filled runway retires and may pin | Preserve send semantics; define fold ownership when held, released, retired+pinned, and escaped |
| User input during compensation | Wheel/touch/navigation | User prompt path cancels its compensation | Any approved tool compensation must cancel immediately on user navigation and never fight live tail-follow |

| Data / interaction | Reads | Proposed writes / effects | Constraint |
| --- | --- | --- | --- |
| Explicit fold click | Clicked group/detail identity, measured header position, fold state, follow/own-turn state | Approved fold state plus local scroll-owner token | No engine RPC; do not erase own-turn reservation |
| Automatic completion | Tool resolved state and explicit user choice | Chosen local automatic-fold policy | Explicit closed/open pins win; replay never impersonates a completion transition |
| Scroll ownership | Pinned, held/released own-turn, saved anchor, active user gesture | At most one active compensator/controller | User input cancels ownership; browser clamping must be tested near scroll end |
| Thought text | Existing flattened styled runs | No text-format change | Long-line redesign remains outside this policy ticket |

Recommended explicit-click policy: preserve the clicked header and let that deliberate interaction own the viewport, release active hold/follow while keeping reservation, cancel on wheel/touch. Automatic thought completion needs a separate selection: preserve current immediate close, animate a controlled close, or keep the visible thought open after completion. None is approved here. Thought text reflow is separate from fold policy.

## 4. Existing and proposed verification

All tests below were read, not executed. Proposed names are specifications, not claims that tests exist. `web/packages/app/vitest.config.ts:5-6` uses the node environment and only `tests/*.test.ts`; a proposed .test.tsx file would not be discovered. The app manifest has React/react-dom/Vitest but no DOM-environment dependency. Ticket 69 uses `.test.ts`, React.createElement, createRoot from react-dom/client, act from React, and a per-file `// @vitest-environment jsdom` pragma. Add a Node-compatible jsdom app devDependency and update `web/pnpm-lock.yaml` during future implementation if ticket 67 has not supplied that infrastructure; otherwise reuse it. Ticket 67 is an infrastructure reuse opportunity, not a semantic blocker. Keep the default test environment and discovery unchanged.

### 4.1 Ticket 68

| Existing or proposed | Test and owner | What it proves / must prove |
| --- | --- | --- |
| Existing desktop | `tool_groups_stay_closed_on_populated_chat_attach`, `tool_groups_stay_closed_after_rapid_new_chat_navigation`, `tool_group_navigation_keeps_user_pins_and_new_arrivals` in `crates/ui/src/transcript.rs:7851,7862,7894` | Baseline and retained-entity behavior; not general A→B→A persistence |
| Existing web | Same three names in `web/packages/app/tests/transcript-model.test.ts:1249,1274,1302` | Direct store calls; tests retain one motion-store instance and explicitly supply baseline flags |
| Proposed | `explicit_fold_choices_survive_real_chat_switch` in the same web file and Rust module, plus a mounted/browser acceptance scenario | Explicit open/closed state survives actual A→B→A under the approved policy; separate engines do not collide |
| Proposed | `restored_folds_do_not_replay_arrival_motion` | Restored pin timestamps/reveals are cleared; new live tools still follow the approved live behavior |

### 4.2 Ticket 69

| Existing or proposed | Test and owner | What it proves / must prove |
| --- | --- | --- |
| Existing store | `a_desync_resubscribe_keeps_the_previous_entries_until_the_reset_frame_lands`, `a_generation_swap_keeps_the_previous_entries_until_the_reset_frame_lands` in `web/packages/app/tests/pending-send.test.ts:235,266` | Rows remain loaded; does not prove React observed pending |
| Existing surface logic | `tool_groups_stay_closed_on_populated_chat_attach`, `tool_groups_stay_closed_after_rapid_new_chat_navigation` in `web/packages/app/tests/transcript-model.test.ts:1249,1274` | Direct motion-store baseline calls; must remain green |
| Existing arrival | `chat_switch_arrival_restores_in_one_assignment_and_schedules_no_spring`, `an anchored restore is ONE hard assignment — no poll frames, no spring`, `no fold tween and no shimmer on the arrival frame; live flips animate` in `web/packages/app/tests/chat-arrival.test.ts:108,145,171` | Isolated controller/motion behavior, not mounted React delivery order |
| Proposed integrated | `cached_transcript_generation_reset_rebaselines_without_rendering_pending` in new `web/packages/app/tests/transcript-replay-integration.test.ts` | Mount the real consumer, publish cache then same-task pending/reset, inspect final reveal starts and baseline consumption |
| Proposed integrated | `same_generation_reset_rebaselines_once_and_preserves_post_reset_arrivals` in the same new file | Durable local epoch works when connection generation does not change; a reset plus later delta in one React batch does not erase genuine post-reset additions |
| Proposed integrated | `late_replay_preserves_escaped_anchor_and_own_turn` in the same new file | After 500 ms, authoritative replay does not re-run initial restore, engage pin, or retire an otherwise valid runway |

#### Mounted harness and discovery

Keep the new suite at `web/packages/app/tests/transcript-replay-integration.test.ts`, matching `vitest.config.ts:6`. Place `// @vitest-environment jsdom` at the top; use React.createElement instead of JSX, createRoot from react-dom/client, and act from React. Reuse ticket 67's jsdom setup if it has landed. Otherwise this ticket owns adding a jsdom app devDependency compatible with the implementation-time Node version and updating `web/pnpm-lock.yaml` through the package manager. Keep the default environment/include unchanged. Ticket 67 is not a semantic blocker.

Mount exported TranscriptView with a real TranscriptStore through the existing public `store` prop (internally renamed sharedStore, `web/packages/app/src/components/transcript.tsx:119-129,210-218`). Exercise real row derivation, ToolGroupMotionStore, baseline effect, and scroller/controller. The fake client exposes a controllable watch callback with generation context and the minimal status/call interfaces used by the view. A deferred cache load seeds actual store state. Narrowly provide/stub unrelated settings/theme/context dependencies; do not replace baseline synchronization or motion-store mutation with mocks.

In act, settle cached populated history first, then invoke the captured watch callback with a reset containing additional historical tools in one task. Drive a genuine subsequent delta separately and also in the reset's React batch. Observe render-consumed replay/generation/epoch, calls to the real motion store, final reveal state, and controller ownership; spies must call through. Advance controlled time beyond 500 ms for late reset. Repeat critical cases under StrictMode.

Scope matchMedia, ResizeObserver, requestAnimationFrame/time, getBoundingClientRect, clientHeight, scrollHeight, and scrollTop stubs to this suite. Supply deterministic geometry and explicitly deliver measurement batches; jsdom does not perform layout. Set/restore the React act-environment flag, unmount roots in act, remove containers, dispose stores, cancel scheduled callbacks, and restore mocks. These stubs prove lifecycle/state transitions only, not browser layout, scroll clamping, or screenshot parity; required runtime captures remain separate.

### 4.3 Ticket 70

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

### 4.4 Ticket 71

| Existing or proposed | Test and owner | What it proves / must prove |
| --- | --- | --- |
| Existing desktop | `live_thought_streams_open_and_settles_closed` (`crates/ui/src/transcript.rs:8678`), `trailing_group_auto_opens_only_while_streaming` (`:9110`) | Existing automatic policy; do not silently change expected values before a product decision |
| Existing reservation | `folding_releases_sent_turn_hold_without_removing_reservation` in `crates/ui/src/transcript.rs:10392`; web `folding_releases_sent_turn_hold_without_removing_reservation (shape)` in `web/packages/app/tests/transcript-model.test.ts:1427` | User-prompt fold contract, not tool-group compensation |
| Proposed after decision | `tool_fold_preserves_clicked_header_and_reservation` in `web/packages/app/tests/transcript-model.test.ts` and Rust transcript tests | Approved explicit-click behavior with held, released, retired+pinned, and escaped states |
| Proposed after decision | `thought_completion_obeys_explicit_pin_and_scroll_policy` in the same web/Rust owners | Automatic transition follows selected policy; explicit pins win; reduced motion is deterministic |
| Proposed after decision | `user_scroll_cancels_tool_fold_compensation` in the same web/Rust owners | Wheel/touch during animation cancels compensation and does not re-engage pin |

### 4.5 Runtime matrix and capture requirement

| Scenario | Inspect | Expected ownership |
| --- | --- | --- |
| Completed A→B→A, explicit open and closed groups | Final pins, reveal starts, restored anchor | Ticket 68 selected persistence policy; no historical arrival replay |
| Streaming A→B→A, closed latest group | Explicit pin versus default autoOpen | Pin restoration is separate from replay-baseline processing |
| Cache paint, authoritative reset within same task, new/same generation | Reset epoch, consumed baseline, real later tool | Ticket 69 must classify reset history independently of intermediate renders |
| Reset/measurement after 500 ms | Hard restore count, spring state, current anchor | Replay cannot repeat navigation or discard runway; late genuine live content retains normal behavior |
| Own-send runway held and near fill | Estimated/measured height, fill/retirement transition | Ticket 70 removes model error; ticket 71 must preserve reservation policy |
| Runway released but reserved; runway retired and pinned | Header location during explicit/automatic fold | Ticket 71 selected scroll-owner behavior |
| Manually escaped transcript, wheel/touch during fold | Visible anchor and cancellation | Never re-engage follow merely because a fold changed height |
| Reduced motion, narrow phone and desktop width | Logical state, clamping, fixed thought lines | Same chosen state semantics; coordinated instantaneous geometry/anchor where required |

Before future visual completion, capture paired desktop/web states and a short motion sequence for switching, own-send, and folding. Use the already-running app or coordinator-operated capture workflow. Subagents must not start long-running app/server/browser processes. Screenshots are not waived by unit tests; if unavailable, leave visual acceptance pending.

Future implementation checks: from `web/`, `pnpm -r build`; from `web/packages/app/`, `pnpm test`; for Rust changes, `cargo check -p roboco-ui` and `cargo test -p roboco-ui`. These are future criteria, not commands authorized or performed during documentation.

## 5. Gap tables

### 5.1 Ticket 68

| Item | Kind | Desktop value | Web value (file:line) | Fix |
| --- | --- | --- | --- | --- |
| Explicit fold memory across A→B→A | SHARED PRODUCT DECISION | folds.clear on true chat change (`crates/ui/src/transcript.rs:3978`) | New surface/motion store (`web/packages/app/src/components/transcript.tsx:216,339-341`) | Decide bounded per-chat fold memory on both clients; restoring closed pins is distinct from disabling live auto-open |
| Scope of no-auto-open request | NEEDS INFO | auto_open OR arrival_pending (`crates/ui/src/transcript.rs:5858-5859`) | Same resolution (`web/packages/app/src/components/tool-group.tsx:127-129`) | Select switch-only replay suppression plus remembered pins, or explicitly approve global automatic-opening removal |

### 5.2 Ticket 69

| Item | Kind | Desktop value | Web value (file:line) | Fix |
| --- | --- | --- | --- | --- |
| Durable reset baseline identity | SOURCE-PROVEN PATH; RUNTIME CAUSE UNREPRODUCED | Attach/pending gate consumed with populated baseline (`crates/ui/src/transcript.rs:3948-3954,4063-4113`) | Synchronous pending/populated publications, effect observes only rendered replay value (`web/packages/app/src/state/transcript-store.ts:452-496`; `web/packages/app/src/components/transcript.tsx:342-361`) | Integrated regression first, then durable accepted-reset epoch/baseline rather than reliance on an intermediate render |
| Late cached/live replay | DIAGNOSIS REQUIRED | Baseline follows document attachment | Cache can paint before live reset; timer armed once and capped at 500 ms (`web/packages/app/src/state/transcript-store.ts:353-362`; `web/packages/app/src/lib/chat-arrival.ts:73-77`) | Preserve anchor/runway, baseline authoritative reset before reveal classification, verify late measurements without globally disabling live follow |

### 5.3 Ticket 70

| Item | Kind | Desktop value | Web value (file:line) | Fix |
| --- | --- | --- | --- | --- |
| Collapsible rail estimate | VERIFIED WRONG VALUE | Actual rail rows 32 px (`crates/ui/src/transcript.rs:6000-6023`) | Estimator uses 38 px standalone chips (`web/packages/app/src/components/transcript.tsx:1449`; `web/packages/app/src/lib/transcript.ts:1047-1051`) | Shared rail geometry; preserve standalone 38 px |
| Estimate effective state | VERIFIED OMITTED INPUTS | Renderer resolves pins, effective details, and arrivals (`crates/ui/src/transcript.rs:5849-5859,5874-6023`) | Estimator omits arrival open, detail pins, fetched payload/affordance (`web/packages/app/src/components/transcript.tsx:1446-1456`) | Shared side-effect-free effective-state/height resolver; verify near runway fill threshold |

### 5.4 Ticket 71

| Item | Kind | Desktop value | Web value (file:line) | Fix |
| --- | --- | --- | --- | --- |
| Tool-fold scroll ownership | SHARED PRODUCT DECISION | toggle_fold only mutates fold; prompt compensation is separate (`crates/ui/src/transcript.rs:4376,4518-4525`) | Same separation (`web/packages/app/src/lib/tool-motion.ts:618-628`; `web/packages/app/src/components/transcript.tsx:1164`) | Decide explicit tool/detail click ownership on both clients; preserve reservation |
| Automatic thought closure | SHARED PRODUCT DECISION | No toggle timestamp returns target height directly (`crates/ui/src/transcript.rs:5986,6025-6040`) | Same immediate closure (`web/packages/app/src/components/tool-group.tsx:155,314-328`) | Choose retain-open or controlled/current close before implementation; do not label current parity a missing port |
| Narrow thought readability | SHARED GEOMETRY; OUTSIDE CURRENT FIX | 96-column preprocessing and truncated fixed rows (`crates/ui/src/transcript.rs:387,6773-6788`) | Same model (`web/packages/app/src/lib/transcript.ts:351`; `web/packages/app/src/styles/app.css:9021-9038`) | Preserve here; responsive wrapping requires separately specified analytic-height changes |
