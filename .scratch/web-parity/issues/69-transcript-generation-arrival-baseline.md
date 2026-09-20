# 69 — Make transcript replay baselines independent of intermediate React renders

**What to build:** Cached history and reconnect/reset snapshots must be recognized as replay even if React never renders an intermediate pending state. Existing historical tools should not gain fresh arrival starts or repeated scroll restoration. First reproduce the mounted cache/reset sequence; source inspection identifies a vulnerability but has not reproduced the reported runtime failure.

**Blocked by:** None for diagnosis and the specified regression; implementation remains future work under the current documentation-only request. Manual fold persistence is ticket 68, not a dependency for this fix.

**Status:** ready-for-agent

**Research:** `../research-2026-09-20/followup-transcript-state-geometry.md` §3.2, §4.2, §4.5, §5.2. Relevant tables are copied verbatim below.

**Desktop reference (for lookups only):** `crates/ui/src/transcript.rs` at `37c354ff`, with exact owning functions/lines in the tables below.

**Web files to touch (future implementation only):**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/state/transcript-store.ts` | edit after regression | Durable accepted-reset epoch/baseline snapshot metadata, cache seed provenance |
| `web/packages/app/src/components/transcript.tsx` | edit after regression | Baseline consumer before affected rows paint; preserve existing viewport/own-turn |
| `web/packages/app/src/lib/tool-motion.ts` | edit if needed | Baseline counts plus true subsequent arrival classification |
| `web/packages/app/src/lib/chat-arrival.ts` | edit only if regression requires | Replay-scoped arrival coordination; no arbitrary global cap extension |
| `web/packages/app/tests/pending-send.test.ts` | edit | Same/new generation and invalid/stale reset epoch tests |
| `web/packages/app/tests/chat-arrival.test.ts` | edit | Reset versus navigation scroll ownership |
| `web/packages/app/tests/transcript-replay-integration.test.ts` | new | Mounted real store/consumer cache→reset regression; per-file jsdom environment and React.createElement |
| `web/packages/app/package.json` | edit if ticket 67 infrastructure has not landed | Add compatible jsdom app devDependency; reuse it if already installed by ticket 67 |
| `web/pnpm-lock.yaml` | edit through package manager if dependency added | Record jsdom and transitive lock changes; reuse ticket 67 infrastructure without duplicate dependency churn |
| `web/packages/app/vitest.config.ts` | read; no edit expected | Keep node default and tests/*.test.ts discovery; environment override belongs only to the new test file |

## 1. Context a fresh session needs

- Vocabulary follows `CONTEXT.md`: chat, transcript, turn, engine, harness; Session means only a pairing credential.
- The web transcript is a virtualized row list: `TranscriptStore` publishes entries; `TranscriptSurface` derives rows; `TranscriptScroller` builds prefix sums and measures DOM heights.
- `ToolGroupMotionStore` owns group/detail pins and reveal clocks; `ToolGroupRow` renders analytic tool geometry. `StickController` owns scroll follow and the own-turn runway.
- Desktop references are the actual `crates/ui/src/transcript.rs` implementation at `37c354ff`. Line numbers below belong to that revision; recheck after merges.
- Ticket 40 already keeps rows loaded across reset and implements own-turn slack/expansion. Ticket 58 already hard-restores navigation and adds a time-limited arrival gate. Do not reapply their historical patches.
- Web is always opaque. No new chrome, strings, keyboard shortcuts, RPCs, motion tokens, or styling system are needed here.
- Current work authorizes research/tickets only. Implementation sequences and commands in this ticket are future work. No runtime reproduction has been claimed.
- The store commits pending and populated synchronously for a new generation. Listener notifications are not proof that a passive React effect saw both snapshots.
- Existing cache entries can already have loaded/replay=populated. Thus first live reset can occur after a baseline has been consumed and after the arrival window ends.

## 2. Spec

### 2.1 Contract and state

| State | Current code and evidence | Target condition |
| --- | --- | --- |
| Cached initial rows | Cache load calls seedEntries; loaded=true and replay=populated before the live watch reset (`web/packages/app/src/state/transcript-store.ts:353-362,415-423`) | Cached rows may paint, but the later authoritative reset is independently identifiable as a baseline |
| New connection generation | One onItem call commits pending, applies reset, then commits populated (`web/packages/app/src/state/transcript-store.ts:452-496,520-524`) | Baseline recognition must not depend on React rendering the intermediate pending snapshot |
| Surface consumption | Passive effect rearms a boolean only when it observes pending; dependency list lacks generation (`web/packages/app/src/components/transcript.tsx:342-361`) | Consume a durable store-owned replay/reset epoch; same-generation resubscribe resets also increment it |
| Desktop reference | Attach or pending arms veil_attach_pending; populated baseline clears reveal/tween clocks before counting tools (`crates/ui/src/transcript.rs:3948-3954,4063-4113`) | Existing reset rows have no new reveal starts; explicit pins remain; true post-reset additions remain eligible |
| Arrival timer | 50 ms measurement quiescence, 500 ms hard cap; armed once per scroller mount (`web/packages/app/src/lib/chat-arrival.ts:32-35,68-77`; `web/packages/app/src/components/transcript.tsx:953-968`) | Diagnose delayed reset/measurement independently of mount age; apply reset baseline before row paint without globally lengthening or disabling live motion |
| Scroll restore | Initial hard write from estimates; pinned arrival kicks write changing end (`web/packages/app/src/components/transcript.tsx:973-1028`; `web/packages/app/src/components/stick-controller.ts:171-182`) | Preserve saved anchor/runway on same-chat reset; baseline processing must not repeat initial viewport restore or scroll to end as a reset side effect |
| Evidence limit | Source proves synchronous publications, effect dependency, and missing durable reset identity; mounted React coalescing was not reproduced | Reproduce the integrated sequence before claiming it caused the reported bug; log snapshot/reset epoch and baseline consumption, not just listener callbacks |

### 2.2 Motion

| What | Trigger | Current behavior | Target and reduced motion |
| --- | --- | --- | --- |
| Replay reveal | Tools absent from old count after an unrecognized reset | sync schedules starts; arrival gate does not guard these assignments (`web/packages/app/src/lib/tool-motion.ts:708-729`) | Baseline reset rows before live-delta counting; reduced motion must also preserve logical pins and anchor |
| Fold seeding | Rendered open state flips | Arrival only suppresses noteRendered tween timestamps (`web/packages/app/src/lib/tool-motion.ts:657-671`) | Replay baseline is established before rendering affected groups; do not change their autoOpen policy |
| Arrival scroll | Pinned kick during arrival | Hard end write; after cap regular spring may resume (`web/packages/app/src/components/stick-controller.ts:170-182`) | Reset must not engage pin or disturb escaped/held state; late live content keeps existing follow behavior |

### 2.3 Data and interactions

| Data / interaction | Reads | Proposed writes / effects | Constraint |
| --- | --- | --- | --- |
| Accepted reset | Connection generation, successful reset frame, baseline entries | Store-local monotonic reset epoch and durable baseline identity/count information | Increment only after a valid accepted reset; stale generations and malformed frames must not create a baseline |
| Cache seed | Cached entries | Distinct seed provenance or initial epoch | Authoritative live reset must not be mistaken for a continuation of cache |
| Surface sync | Epoch, baseline rows/counts, latest rows | Clear reveal/tween clocks for reset history, then classify post-reset additions | Works when pending was never rendered and when reset plus later deltas are coalesced |
| Resubscribe | Existing rows, loaded flag, same or new generation | Keep rows; identify eventual accepted reset | No protocol/RPC change; generation alone is insufficient for same-generation reset |

**Children in order:** existing transcript rows, group summary, revealed tool/detail bodies, existing trailer and spacer. Preserve markup ordering unless a specific approved state-owner change requires otherwise.

**Text / keyboard:** no new product strings, labels, or keybindings. Group/detail activation retains existing accessible controls. This ticket changes state/geometry rather than introducing a new surface.

### 2.4 Diagnosis gate and target algorithm

First mount the actual store consumer and drive cached populated rows, then a live generation reset in one task. Record render-observed replay/generation values, accepted reset identity, baseline sync count, group starts, fold timestamps, pin/own-turn state, and initial-restore count. Assertions on raw store listeners alone do not satisfy this gate. If the reported mechanism does not reproduce, record that result and trace the actual residual path before declaring a user-visible root cause.

After evidence establishes the contract, introduce a monotonically increasing local accepted-reset identity in the published snapshot. Increment on a valid authoritative reset, including same-generation resubscribe; reject stale/malformed frames without increment. Cache seed provenance must be distinguishable from authoritative live reset.

Consume each reset baseline once before affected groups paint. Do not require an observable pending render, and do not use connection generation as the sole epoch. Preserve explicit pins while clearing replay reveal/tween clocks. Keep enough reset-baseline row/tool identity to classify genuinely later deltas even when React coalesces reset and delta. One viable shape is reset epoch plus reset entries/counts, then baseline sync followed by current-row delta sync; select the smallest correct representation and test it.

Reset recognition must preserve the current anchor, own-turn hold/reservation, and pin state. It must not call initial restore/snapToEnd again. If replay-specific arrival gating is needed, tie it to accepted reset identity; retain live-stream semantics and do not merely make the 500 ms timeout larger.

#### Mounted harness and discovery

Keep the new suite at `web/packages/app/tests/transcript-replay-integration.test.ts`, matching `vitest.config.ts:6`. Place `// @vitest-environment jsdom` at the top; use React.createElement instead of JSX, createRoot from react-dom/client, and act from React. Reuse ticket 67's jsdom setup if it has landed. Otherwise this ticket owns adding a jsdom app devDependency compatible with the implementation-time Node version and updating `web/pnpm-lock.yaml` through the package manager. Keep the default environment/include unchanged. Ticket 67 is not a semantic blocker.

Mount exported TranscriptView with a real TranscriptStore through the existing public `store` prop (internally renamed sharedStore, `web/packages/app/src/components/transcript.tsx:119-129,210-218`). Exercise real row derivation, ToolGroupMotionStore, baseline effect, and scroller/controller. The fake client exposes a controllable watch callback with generation context and the minimal status/call interfaces used by the view. A deferred cache load seeds actual store state. Narrowly provide/stub unrelated settings/theme/context dependencies; do not replace baseline synchronization or motion-store mutation with mocks.

In act, settle cached populated history first, then invoke the captured watch callback with a reset containing additional historical tools in one task. Drive a genuine subsequent delta separately and also in the reset's React batch. Observe render-consumed replay/generation/epoch, calls to the real motion store, final reveal state, and controller ownership; spies must call through. Advance controlled time beyond 500 ms for late reset. Repeat critical cases under StrictMode.

Scope matchMedia, ResizeObserver, requestAnimationFrame/time, getBoundingClientRect, clientHeight, scrollHeight, and scrollTop stubs to this suite. Supply deterministic geometry and explicitly deliver measurement batches; jsdom does not perform layout. Set/restore the React act-environment flag, unmount roots in act, remove containers, dispose stores, cancel scheduled callbacks, and restore mocks. These stubs prove lifecycle/state transitions only, not browser layout, scroll clamping, or screenshot parity; required runtime captures remain separate.

### 2.5 Future implementation sequence

1. Add the integrated mounted regression and establish whether pending is skipped; document trace and failure before editing behavior.
2. Add accepted-reset epoch/provenance tests for cached seed, same generation, new generation, stale frame, malformed frame, authoritative empty, and reset-plus-delta batching.
3. Publish durable baseline metadata atomically with accepted entries; keep ticket 40's no-empty-window behavior.
4. Replace the surface's pending-observation latch with epoch consumption, synchronizing baseline before visible reveal decisions. Preserve actual post-reset additions.
5. Test delayed reset/measurement beyond 500 ms with pinned and escaped viewport and a held/released runway. Any broader scroll-settle defect discovered becomes a separately scoped follow-up unless directly caused by replay classification.
6. Capture runtime traces/screenshots and run future checks. Record which path was reproduced and which remains hypothetical.

## 3. Pure logic and tests

Suggested state shape: store-owned reset sequence plus authoritative baseline identity; surface remembers the consumed sequence. Exact field names are implementation choices. Pure reset classification tests supplement, but cannot replace, a mounted consumer regression.

| Existing or proposed | Test and owner | What it proves / must prove |
| --- | --- | --- |
| Existing store | `a_desync_resubscribe_keeps_the_previous_entries_until_the_reset_frame_lands`, `a_generation_swap_keeps_the_previous_entries_until_the_reset_frame_lands` in `web/packages/app/tests/pending-send.test.ts:235,266` | Rows remain loaded; does not prove React observed pending |
| Existing surface logic | `tool_groups_stay_closed_on_populated_chat_attach`, `tool_groups_stay_closed_after_rapid_new_chat_navigation` in `web/packages/app/tests/transcript-model.test.ts:1249,1274` | Direct motion-store baseline calls; must remain green |
| Existing arrival | `chat_switch_arrival_restores_in_one_assignment_and_schedules_no_spring`, `an anchored restore is ONE hard assignment — no poll frames, no spring`, `no fold tween and no shimmer on the arrival frame; live flips animate` in `web/packages/app/tests/chat-arrival.test.ts:108,145,171` | Isolated controller/motion behavior, not mounted React delivery order |
| Proposed integrated | `cached_transcript_generation_reset_rebaselines_without_rendering_pending` in new `web/packages/app/tests/transcript-replay-integration.test.ts` | Mount the real consumer, publish cache then same-task pending/reset, inspect final reveal starts and baseline consumption |
| Proposed integrated | `same_generation_reset_rebaselines_once_and_preserves_post_reset_arrivals` in the same new file | Durable local epoch works when connection generation does not change; a reset plus later delta in one React batch does not erase genuine post-reset additions |
| Proposed integrated | `late_replay_preserves_escaped_anchor_and_own_turn` in the same new file | After 500 ms, authoritative replay does not re-run initial restore, engage pin, or retire an otherwise valid runway |

## 4. Gaps this ticket closes

| Item | Kind | Desktop value | Web value (file:line) | Fix |
| --- | --- | --- | --- | --- |
| Durable reset baseline identity | SOURCE-PROVEN PATH; RUNTIME CAUSE UNREPRODUCED | Attach/pending gate consumed with populated baseline (`crates/ui/src/transcript.rs:3948-3954,4063-4113`) | Synchronous pending/populated publications, effect observes only rendered replay value (`web/packages/app/src/state/transcript-store.ts:452-496`; `web/packages/app/src/components/transcript.tsx:342-361`) | Integrated regression first, then durable accepted-reset epoch/baseline rather than reliance on an intermediate render |
| Late cached/live replay | DIAGNOSIS REQUIRED | Baseline follows document attachment | Cache can paint before live reset; timer armed once and capped at 500 ms (`web/packages/app/src/state/transcript-store.ts:353-362`; `web/packages/app/src/lib/chat-arrival.ts:73-77`) | Preserve anchor/runway, baseline authoritative reset before reveal classification, verify late measurements without globally disabling live follow |

## 5. Do not

- Do not claim React batching caused the user's symptom before reproducing the integrated path.
- Do not disable default autoOpen or preserve cross-chat manual pins here; ticket 68 owns policy.
- Do not reset all fold state on every stream delta or classify a genuine new tool as replay.
- Do not clear entries/loaded during reset, discard own-turn state, repeat initial scroll restore, or globally extend arrival windows.
- Do not change Rust policy or rewrite virtualizer/spring for this web lifecycle fix.
- Do not reintroduce frosted/backdrop-filter surfaces or desktop-only GPUI internals into the web.
- Do not persist engine transcript data or add a new engine RPC for local presentation state.
- Do not mark visual behavior verified from pure helper tests.

## 6. Acceptance

- [ ] The new .test.ts suite is discovered under the unchanged include glob, uses its own jsdom pragma, and owns/reuses the jsdom dependency and lockfile consistently; other tests retain the node environment.
- [ ] A mounted cache→live reset trace demonstrates the actual consumer sequence; the diagnosis records reproduced versus hypothetical behavior.
- [ ] Accepted reset epochs work without an intermediate pending render, for same/new generation.
- [ ] Invalid/stale frames cannot advance baseline; authoritative empty stays authoritative.
- [ ] Reset rows have no replay starts; true post-reset additions still reveal even when batched.
- [ ] Existing explicit pins, own-turn reservation/hold, and escaped anchor survive same-chat reset.
- [ ] Reset causes no second initial viewport restore; late measurements do not reclassify history as arrivals.
- [ ] Paired desktop/web screenshots and short motion captures exist for the named states below; runtime validation uses the existing app or coordinator captures. A subagent must not start long-running server/app/browser processes. If captures are unavailable, leave visual acceptance pending; do not waive it.
- [ ] From `web/`, `pnpm -r build` passes; from `web/packages/app/`, `pnpm test` passes after implementation.
- [ ] If Rust changes, `cargo check -p roboco-ui` and `cargo test -p roboco-ui` pass.
- [ ] No new literal visual values where shared tokens exist; reduced-motion state semantics remain correct.


| Regression / capture | Expected result |
| --- | --- |
| Completed cache→new generation reset in one task | No historical arrival starts or opening caused by arrivalPending |
| Streaming reset, existing group and new post-reset tool | Existing history baselined, new tool eligible; autoOpen policy unchanged |
| A→B→A with cached paint | Replay suppression independent of manual pin loss (ticket 68) |
| Same-generation resubscribe reset; stale generation frame | One accepted-reset baseline; stale frame ignored |
| Reset after >500 ms, late measurements | No repeated navigation restore; diagnose any independent measurement scroll separately |
| Runway held/released; retired+pinned; manual escape | Preserve ownership and visible anchor; do not manufacture tail-follow |

## Comments

Documentation created from read-only source research at `37c354ff`. Implementation, tests, and runtime captures have not been performed. Proposed tests are not existing test results.

**2026-09-20 — implemented on `wp-fu/69`.** Diagnosis gate: **REPRODUCED** (mounted, pre-fix). The integrated cache→same-task-new-generation-reset trace against the unmodified consumer showed: store publications `[pending gen2, populated gen2]`; consumer sync sequence `sync([], live, replaying)` at mount → `sync([A#g0×1], baseline)` at cache settle → `sync([A#g0×1, B#g0×2], live)` at the reset — the pending snapshot was published but never render-consumed, so the latch stayed consumed from the seed and the reset ran as a live sync. Replayed history group `B#g0` received `headerStartedAt` + staggered starts `[+90ms, +155ms]` and rendered open (`noteRendered` flip to `open:true`). Same mechanism reproduced for the same-generation resubscribe (replayed growth on an existing group got a start) and for the >500ms late reset. Landed: (1) `TranscriptStore` publishes a durable `baseline: { epoch, provenance, entries }` — a store-local monotonic accepted-reset identity incremented only after a reset frame is accepted and applied (same- or new-generation, authoritative empty included); the cache seed publishes provenance `seed`, stale generations/malformed frames/deltas/bare resubscribes never advance it. (2) `TranscriptSurface` consumes each epoch exactly once in a layout effect (before paint): baseline rows derived from the reset's own entries via `rowsForEntry` with an empty markdown tree (tool-group identity/counts are parse-independent) are synced as the replay baseline, then the current rows as the live delta — genuine post-reset additions batched with the reset still arrive. `tool-motion.ts` and `chat-arrival.ts` needed no changes (existing baseline branch and mount-scoped window already carry the semantics once the surface feeds them correctly). No fold/autoOpen policy touched. Verification: `pnpm -r build` passes; `pnpm test` passes — 91 files / 1472 tests (new: `transcript-replay-integration.test.ts` ×5 incl. StrictMode repeats, pending-send +3 epoch/provenance, chat-arrival +1 reset-vs-escaped-pin). Pending runtime evidence (cannot be gathered here — no dev server/browser/app captures may be started): paired desktop/web screenshots and motion captures for the §6 regression table (cache→reset in one task, A→B→A cached paint, reset after >500ms with late measurements, runway held/released/escaped); visual acceptance stays pending, not waived.
