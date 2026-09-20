# 68 — Chat-switch fold policy on desktop and web

**What to build:** After the selected policy is approved, revisiting a chat will consistently handle explicitly opened and closed tool groups on both clients. Replayed history will not animate as newly arrived tools. This ticket records a product decision; it does not authorize removing live tool auto-open or reveal behavior.

**Blocked by:** Product choice in §2.4; no code work until it is recorded. Ticket 69 is independent replay correctness work; ticket 70 supplies geometry correction for final validation.

**Status:** needs-info

**Research:** `../research-2026-09-20/followup-transcript-state-geometry.md` §3.1, §4.1, §4.5, §5.1. Relevant tables are copied verbatim below.

**Desktop reference (for lookups only):** `crates/ui/src/transcript.rs` at `37c354ff`, with exact owning functions/lines in the tables below.

**Web files to touch (future implementation only):**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/components/transcript.tsx` | edit after decision | Surface remount, fold capture/restore ordering before viewport restore |
| `web/packages/app/src/lib/tool-motion.ts` | edit after decision | Serialize/restore explicit local fold choices without animation clocks |
| `web/packages/app/src/state/transcript-fold-state.ts` | new only if selected | Bounded engine/chat-scoped preference cache; not transcript payload storage |
| `web/packages/app/tests/transcript-model.test.ts` | edit after decision | Actual switch persistence logic and replay/live distinction |
| `crates/ui/src/transcript.rs` | edit after decision | Native per-chat fold preference memory, `sync` selected-chat branch, matching tests |

## 1. Context a fresh session needs

- Vocabulary follows `CONTEXT.md`: chat, transcript, turn, engine, harness; Session means only a pairing credential.
- The web transcript is a virtualized row list: `TranscriptStore` publishes entries; `TranscriptSurface` derives rows; `TranscriptScroller` builds prefix sums and measures DOM heights.
- `ToolGroupMotionStore` owns group/detail pins and reveal clocks; `ToolGroupRow` renders analytic tool geometry. `StickController` owns scroll follow and the own-turn runway.
- Desktop references are the actual `crates/ui/src/transcript.rs` implementation at `37c354ff`. Line numbers below belong to that revision; recheck after merges.
- Ticket 40 already keeps rows loaded across reset and implements own-turn slack/expansion. Ticket 58 already hard-restores navigation and adds a time-limited arrival gate. Do not reapply their historical patches.
- Web is always opaque. No new chrome, strings, keyboard shortcuts, RPCs, motion tokens, or styling system are needed here.
- Current work authorizes research/tickets only. Implementation sequences and commands in this ticket are future work. No runtime reproduction has been claimed.
- User asks whether to remove the behavior entirely, including desktop. That is an open question, not approval to remove all live reveal/auto-open behavior.
- Real A→B→A discards pins in both clients. Desktop's rapid new-chat detour can retain one entity; the existing navigation test exercises that narrower case.

## 2. Spec

### 2.1 Contract and state

| Item | Current desktop and web contract at `37c354ff` | Proposed target / decision | Source |
| --- | --- | --- | --- |
| Actual chat switch | Desktop clears folds on changed selected chat; web remounts the surface and creates a new motion store | DECISION REQUIRED: remember explicit group/detail choices in a bounded per-chat cache on both clients | `crates/ui/src/transcript.rs:3948,3978`; `web/packages/app/src/components/transcript.tsx:216,339-341` |
| Quick deselect and return | Desktop retains the same entity before route exit completes; this is not general A→B→A persistence | Preserve this path; exercise real A→B→A separately | `crates/ui/src/transcript.rs:3908-3916,7894-7915` |
| Default group open | Streaming source entry plus its last part sets auto-open; rendered resolution is explicit pin, else auto-open OR arrival pending | Recommended: preserve the live default; restored explicit closed pins override it. Alternative: default all groups closed, requiring explicit approval on both clients | `crates/ui/src/transcript.rs:1265,5849-5859`; `web/packages/app/src/lib/transcript.ts:1547`; `web/packages/app/src/components/tool-group.tsx:127-129` |
| Replay animation | First populated attach establishes a baseline without arrival starts; suppressing replay does not force groups closed | Existing/replayed rows do not gain new reveal starts, independent of chosen fold policy | `crates/ui/src/transcript.rs:4063-4113`; `web/packages/app/src/lib/tool-motion.ts:685-729` |
| Restore versus new content | Both retain scroll/own-turn state separately from fold state | Restore fold choices before calculating restored viewport; preserve live arrivals after the baseline | `crates/ui/src/transcript.rs:3960-4016`; `web/packages/app/src/components/transcript.tsx:945-1028` |
| Persistence identity | Fold maps use row IDs; the web routes paired engines | Key any new cache by engine identity plus chat/doc and stable group/detail identity; define eviction and vanished-row behavior before implementation | `web/packages/app/src/routes/chat-page.tsx:209-212`; `web/packages/app/src/components/tool-group.tsx:153`; `web/packages/app/src/lib/tool-motion.ts:618-644` |

### 2.2 Motion

| What | Trigger | Current behavior | Target and reduced motion |
| --- | --- | --- | --- |
| Arrival reveal | Newly counted tool rows | Future starts at 90 ms for a new group plus 65 ms per arrival; arrival-pending contributes to open (`crates/ui/src/transcript.rs:123-136,5849-5859`) | Replay never schedules these starts; genuine live arrivals retain existing timing unless the user selects global removal; reduced motion keeps existing instant resolution |
| Fold toggle | Explicit user click | 140 ms EASE_OUT on desktop; web shared fold motion (`crates/ui/src/transcript.rs:123`; `web/packages/app/src/components/tool-group.tsx:314-328`) | Keep existing click motion and reduced-motion snap; restoring a saved choice has no new click/tween timestamp |
| Scroll restore | Chat selection | Hard restore from estimates; arrival kicks hard-write end for pinned views (`web/packages/app/src/components/transcript.tsx:973-981`; `web/packages/app/src/components/stick-controller.ts:171-182`) | Restored folds inform geometry before restore; no newly invented scroll choreography |

### 2.3 Data and interactions

| Data / interaction | Reads | Writes / effects | Constraint |
| --- | --- | --- | --- |
| Group click | Row ID, effective auto-open, current fold | Local group fold pin | No engine RPC |
| Detail click | Stable tool/detail identity, default open | Local detail fold pin | Do not persist fetched output payloads as fold preferences |
| Chat switch | Incoming cached pins and viewport | Restore local state before paint | Cache design must distinguish engines and preserve outgoing capture ordering |
| New live tool | Current doc and reset baseline | Existing reveal state | A navigation policy must not silently disable live tool updates |

**Children in order:** existing transcript rows, group summary, revealed tool/detail bodies, existing trailer and spacer. Preserve markup ordering unless a specific approved state-owner change requires otherwise.

**Text / keyboard:** no new product strings, labels, or keybindings. Group/detail activation retains existing accessible controls. This ticket changes state/geometry rather than introducing a new surface.

### 2.4 Explicit decision gate

Record the user's selection before changing either client:

1. **Recommended:** remember explicit group and detail choices across chats; navigation/replay never replays reveal starts; unpinned groups still follow genuine live auto-open behavior.
2. Keep ephemeral fold choices but suppress replay animation. A manually closed live group may open again on revisit; document that consequence.
3. Disable automatic group opening on both clients, including genuine live arrivals. Specify whether thought-detail defaults also change; that part belongs to ticket 71. This is a broader product change and needs explicit selection.

Additional implementation choices to record with the selection: bounded cache capacity/eviction, whether preferences survive app/browser restart, stable identity for detail pins (current rendering keys use group row plus tool index), vanished/reordered-row handling, and engine/chat identity. Recommended persistence is in-memory across navigation only; do not invent disk persistence.

Decision record: **UNRESOLVED**. No question or approval request is required during this documentation turn.

### 2.5 Future implementation sequence

1. Record the policy above and update target conditions/test expectations; leave this ticket needs-info until then.
2. Add a regression that constructs two chat identities and actually destroys/restores their surface state; do not emulate switching by calling sync(true) on one retained store.
3. Implement a bounded cache of explicit choices only if selected. Capture outgoing state before eviction, restore incoming choices before geometry/viewport restoration, and strip tween timestamps.
4. Mirror the approved behavior in desktop sync/attach. Keep the quick deselect-retain path consistent.
5. Recheck streamed additions, completed history, independent engine IDs, reduced motion, and final geometry after tickets 69/70.
6. Obtain paired runtime captures and run future checks before completing.

## 3. Pure logic and tests

Retain the existing effective-open formula unless the decision explicitly replaces it. A remembered false pin must override streaming/arrival defaults. A replay baseline must not manufacture an explicit false pin: that would silently change future live behavior.

| Existing or proposed | Test and owner | What it proves / must prove |
| --- | --- | --- |
| Existing desktop | `tool_groups_stay_closed_on_populated_chat_attach`, `tool_groups_stay_closed_after_rapid_new_chat_navigation`, `tool_group_navigation_keeps_user_pins_and_new_arrivals` in `crates/ui/src/transcript.rs:7851,7862,7894` | Baseline and retained-entity behavior; not general A→B→A persistence |
| Existing web | Same three names in `web/packages/app/tests/transcript-model.test.ts:1249,1274,1302` | Direct store calls; tests retain one motion-store instance and explicitly supply baseline flags |
| Proposed | `explicit_fold_choices_survive_real_chat_switch` in the same web file and Rust module, plus a mounted/browser acceptance scenario | Explicit open/closed state survives actual A→B→A under the approved policy; separate engines do not collide |
| Proposed | `restored_folds_do_not_replay_arrival_motion` | Restored pin timestamps/reveals are cleared; new live tools still follow the approved live behavior |

## 4. Gaps this ticket closes

| Item | Kind | Desktop value | Web value (file:line) | Fix |
| --- | --- | --- | --- | --- |
| Explicit fold memory across A→B→A | SHARED PRODUCT DECISION | folds.clear on true chat change (`crates/ui/src/transcript.rs:3978`) | New surface/motion store (`web/packages/app/src/components/transcript.tsx:216,339-341`) | Decide bounded per-chat fold memory on both clients; restoring closed pins is distinct from disabling live auto-open |
| Scope of no-auto-open request | NEEDS INFO | auto_open OR arrival_pending (`crates/ui/src/transcript.rs:5858-5859`) | Same resolution (`web/packages/app/src/components/tool-group.tsx:127-129`) | Select switch-only replay suppression plus remembered pins, or explicitly approve global automatic-opening removal |

## 5. Do not

- Do not conflate pin memory with replay suppression; ticket 69 owns reset epochs.
- Do not globally remove autoOpen, arrivalPending, shimmer, or live reveal timing without choice 3.
- Do not delete own-send reservation or rewrite scroll controller.
- Do not add thought text wrapping or automatic inner-thought closure changes; ticket 71 owns that decision.
- Do not reintroduce frosted/backdrop-filter surfaces or desktop-only GPUI internals into the web.
- Do not persist engine transcript data or add a new engine RPC for local presentation state.
- Do not mark visual behavior verified from pure helper tests.

## 6. Acceptance

- [ ] The user-selected policy, persistence lifetime, and cache identity/eviction are recorded.
- [ ] Streaming and completed chats satisfy the chosen explicit open/closed behavior on real A→B→A.
- [ ] Restoring choices creates no new reveal starts or fold timestamps.
- [ ] New live tools after restoration still behave according to the selected live policy.
- [ ] Existing baseline tests plus actual-switch regression pass; retained-entity tests are not substituted for actual switching.
- [ ] Paired desktop/web screenshots and short motion captures exist for the named states below; runtime validation uses the existing app or coordinator captures. A subagent must not start long-running server/app/browser processes. If captures are unavailable, leave visual acceptance pending; do not waive it.
- [ ] From `web/`, `pnpm -r build` passes; from `web/packages/app/`, `pnpm test` passes after implementation.
- [ ] If Rust changes, `cargo check -p roboco-ui` and `cargo test -p roboco-ui` pass.
- [ ] No new literal visual values where shared tokens exist; reduced-motion state semantics remain correct.


| Capture / test state | Check |
| --- | --- |
| Completed A, closed/open groups; switch to B then A | Chosen pins and saved anchor restored with no historical reveal |
| Streaming A, manually closed latest group; B→A | Explicit pin versus default autoOpen follows recorded policy |
| A→new-chat→A before/after native route exit | Retained entity and real remount agree on policy |
| Delayed replay/measurement >500 ms | Pin memory does not depend on arrival timer |
| Own-send runway held/released; retired+pinned; manual escape | Restore does not change scroll ownership solely to apply fold preferences |

## Comments

Documentation created from read-only source research at `37c354ff`. Implementation, tests, and runtime captures have not been performed. Proposed tests are not existing test results.
