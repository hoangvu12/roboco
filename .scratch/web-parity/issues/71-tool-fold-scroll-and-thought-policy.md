# 71 — Decide tool-fold scroll ownership and automatic thought closure

**What to build:** Define how explicit tool/detail folds and automatic thought completion affect the viewport on both clients. After the policy is selected, a fold should behave consistently while reading, following the tail, or holding a just-sent prompt. This is a shared behavior decision, not a missing desktop compensation port.

**Blocked by:** Resolved — the §2.4 choices were recorded 2026-09-20 by the user (directive: "make the scrolling stop jumping around"; recorded decisions below). Ticket 70's geometry and ticket 68's fold memory have already merged; build on the current file shapes.

**Status:** ready-for-agent

**Research:** `../research-2026-09-20/followup-transcript-state-geometry.md` §3.4, §4.4, §4.5, §5.4. Relevant tables are copied verbatim below.

**Desktop reference (for lookups only):** `crates/ui/src/transcript.rs` at `37c354ff`; exact owning functions and lines are in the tables.

**Web files to touch (future implementation only):**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/components/tool-group.tsx` | edit after decision | Explicit group/detail interaction events and approved thought default transition |
| `web/packages/app/src/lib/tool-motion.ts` | edit after decision | Approved fold transition state/timestamps; preserve explicit pins |
| `web/packages/app/src/components/transcript.tsx` | edit after decision | Tool-specific scroll ownership/anchor compensation integrated with existing user-prompt path |
| `web/packages/app/src/components/stick-controller.ts` | edit after decision if needed | Release follow/hold without deleting reservation; user-navigation cancellation |
| `web/packages/app/tests/transcript-model.test.ts` | edit after decision | Fold policy and reservation ownership cases |
| `web/packages/app/tests/chat-arrival.test.ts` | edit after decision if needed | Replay/automatic completion separation |
| `crates/ui/src/transcript.rs` | edit after decision | toggle_fold/render_tool_group detail defaults, native scroll ownership, corresponding tests |

## 1. Context a fresh session needs

- Vocabulary: chat, transcript, turn, engine, harness; Session refers only to a pairing credential.
- `TranscriptStore` publishes entries; `TranscriptSurface` derives rows; `TranscriptScroller` calculates prefix sums from estimates or ResizeObserver measurements.
- `ToolGroupMotionStore` owns folds/reveal clocks. `ToolGroupRow` renders summary, rail chips, expandable invocation/detail bodies, and existing affordances.
- `StickController` owns pin/follow and own-turn state. A just-sent prompt receives a reservation of reply space that streaming content consumes.
- Tickets 40 and 58 already preserve rows during reset and hard-restore chat navigation; their historical implementation is not this ticket.
- All line references are from `37c354ff`; recheck when implementing after other merges.
- Current authorization is documentation only. The implementation sequence and checks below are future work.
- Desktop toggle_user_fold has compensation for the user prompt bubble. Native toggle_fold for tool groups does not call it. The web currently matches this separation.
- Inner thought default closure without a toggle timestamp snaps in both clients. Outer group closure may tween independently.
- The big whitespace after send is intentional reply reservation. Mentioning it in the report is not approval to remove it.

## 2. Spec

### 2.1 Contract and state

| Item | Current desktop and web contract | Proposed policy / decision |
| --- | --- | --- |
| Own-send blank runway | Desktop reserves viewport space below own prompt (`crates/ui/src/transcript.rs:3485-3508`); web mirrors it (`web/packages/app/src/components/transcript.tsx:711-723,931-939`) | Retain reservation and its existing retirement rule unless the user explicitly approves a separate change |
| User prompt fold compensation | toggle_user_fold releases navigation hold and anchors the prompt on an interpolated screen path (`crates/ui/src/transcript.rs:4376-4437`; `web/packages/app/src/components/transcript.tsx:1164-1218`) | Already ported; do not claim it applies to tool groups |
| Tool group click | Only updates fold state; neither implementation calls user-fold scroll compensation (`crates/ui/src/transcript.rs:4518-4525`; `web/packages/app/src/lib/tool-motion.ts:618-628`) | DECISION REQUIRED: recommended explicit group/detail clicks own the viewport, preserve clicked header, and release active follow/hold while retaining any reservation |
| Automatic thought detail close | Unresolved thought defaults open, resolved defaults closed; no user toggle clock means immediate target height (`crates/ui/src/transcript.rs:5983-5986,6025-6040`; `web/packages/app/src/components/tool-group.tsx:155-175,314-328`) | DECISION REQUIRED: keep current close, animate close with scroll ownership, or keep visible thought open after completion; apply chosen policy to both clients |
| Group auto-close | Rendered open-state flip seeds group fold tween (`crates/ui/src/transcript.rs:5858-5870`; `web/packages/app/src/lib/tool-motion.ts:657-671`) | Separate from inner thought closure and replay; decide whether automatic closure may move an escaped viewport |
| Thought line shape | Prewrap at 96 columns, then fixed 18 px rows truncated to available width (`crates/ui/src/transcript.rs:387,6773-6788`; `web/packages/app/src/lib/transcript.ts:351`; `web/packages/app/src/styles/app.css:9021-9038`) | Keep established geometry in this ticket; responsive prose wrapping would require a separately approved height-model change |

### 2.2 Motion

| What | Trigger | Current behavior | Target / decision and reduced motion |
| --- | --- | --- | --- |
| Explicit group fold | Header click | 140 ms EASE_OUT; no dedicated scroll owner | Recommended header anchor owns any compensation; choose parity behavior on both clients; snap geometry/anchor together under reduced motion |
| Inner thought default | unresolved→resolved without a user pin | Height snaps to closed target | Select retain-open or controlled close before coding; no guessed duration or automatic reuse of user-prompt timing |
| Own-turn runway | Send, streaming fill, wheel escape | Held or released reservation; filled runway retires and may pin | Preserve send semantics; define fold ownership when held, released, retired+pinned, and escaped |
| User input during compensation | Wheel/touch/navigation | User prompt path cancels its compensation | Any approved tool compensation must cancel immediately on user navigation and never fight live tail-follow |

### 2.3 Data and interactions

| Data / interaction | Reads | Proposed writes / effects | Constraint |
| --- | --- | --- | --- |
| Explicit fold click | Clicked group/detail identity, measured header position, fold state, follow/own-turn state | Approved fold state plus local scroll-owner token | No engine RPC; do not erase own-turn reservation |
| Automatic completion | Tool resolved state and explicit user choice | Chosen local automatic-fold policy | Explicit closed/open pins win; replay never impersonates a completion transition |
| Scroll ownership | Pinned, held/released own-turn, saved anchor, active user gesture | At most one active compensator/controller | User input cancels ownership; browser clamping must be tested near scroll end |
| Thought text | Existing flattened styled runs | No text-format change | Long-line redesign remains outside this policy ticket |

**Children in order:** existing group summary header, revealed rail/standalone cards, invocation/detail bodies and optional affordances; unchanged transcript trailer/spacer.

**Text / keyboard:** preserve existing labels, buttons, keyboard activation, tokens, opacity policy, and markup order. No new product strings or RPCs.

### 2.4 Explicit decision gates

Record each choice before implementation; do not silently infer it from the request to investigate:

**A. Explicit group/detail click and scrolling.** Recommended: preserve the clicked header in screen space, release active follow or own-turn hold, retain any live reservation, and cancel compensation immediately on wheel/touch/navigation. Alternative: preserve current follow/hold behavior and allow normal tail/controller adjustment as content shrinks. Apply the selected semantics to both clients.

**B. Automatic thought completion.** Choose one: keep the current immediate default close; perform a controlled animated close with specified scroll ownership; or keep the visible thought expanded after completion until the user closes it. Explicit open/closed pins override the default in every option. Recommended discussion direction is retaining a thought the user is reading; this is not an approved default.

**C. Automatic outer-group close.** Decide whether existing live-group collapse keeps current tail-follow behavior or must preserve a manually escaped reading anchor. Keep this distinct from inner detail closure and from chat-switch replay.

**D. Own-send reservation.** Retain it and its established held/released/fill-retired lifecycle. Removing or redesigning it requires a separate explicit decision and specification. This ticket must not delete it as a shortcut to hiding scroll jumps.

Decision record (2026-09-20, user directive: "the scroll shouldn't be jumping around — the jumping messes up the UX"):
- **A SELECTED (compensated explicit folds):** explicit group/detail clicks own the viewport — preserve the clicked header at its screen position, release active follow/hold while retaining any live reservation, cancel compensation immediately on wheel/touch/navigation, never fight live tail-follow. Reduced motion snaps geometry and anchor together.
- **B SELECTED (controlled animated close with scroll ownership):** automatic thought completion keeps closing by default, but over the existing 140ms EASE_OUT fold tween with the same scroll ownership anchoring the viewport through the shrink — no snap-jump. Explicit open/closed pins override the default in every case. (Density stays as today; "keep thought open until user closes" was not selected.)
- **C SELECTED (preserve the reading anchor):** automatic outer-group closure must not move a manually escaped viewport; a pinned tail keeps its existing tail-follow through the same compensated path.
- **D RETAINED:** own-send reservation and its held/released/fill-retired lifecycle unchanged.
All four apply to BOTH clients (web + crates/ui/src/transcript.rs).

Thought text currently uses 96-column prewrapping and fixed-height truncated lines on both clients. Responsive prose wrapping is out of scope here because it changes the analytic height model; a future request needs its own geometry spec.

### 2.5 Future implementation sequence

1. Record choices A/B/C and reduced-motion behavior. Keep needs-info until target states and ownership are explicit.
2. Add failing logic/runtime scenarios for held runway, released reservation, retired+pinned tail, manually escaped reading, and input interruption using the selected policy.
3. Introduce a tool-specific interaction/scroll-owner path only if selected. Do not invoke user prompt compensation with guessed user-row heights.
4. Preserve clicked header using measured/current row geometry; hand off between at most one compensator and StickController. Keep reservation when releasing its hold.
5. Implement the selected inner-thought and outer-group automatic policies in web and Rust; distinguish genuine completion from reset/replay. Preserve explicit pins.
6. Validate after ticket 70, including browser scroll clamping near bottom, rapidly repeated clicks, wheel/touch cancellation, late measurement, and reduced motion.
7. Obtain paired captures and run future checks; leave unobserved visual cases unchecked.

## 3. Pure logic and tests

The target policy must define a state transition table for explicit click versus automatic completion, crossed with held/released/retired runway and pinned/escaped viewport. A generic 'smooth fold' helper is insufficient without an owner/cancellation rule. Preserve reservation as separate state from its hold.

| Existing or proposed | Test and owner | What it proves / must prove |
| --- | --- | --- |
| Existing desktop | `live_thought_streams_open_and_settles_closed` (`crates/ui/src/transcript.rs:8678`), `trailing_group_auto_opens_only_while_streaming` (`:9110`) | Existing automatic policy; do not silently change expected values before a product decision |
| Existing reservation | `folding_releases_sent_turn_hold_without_removing_reservation` in `crates/ui/src/transcript.rs:10392`; web `folding_releases_sent_turn_hold_without_removing_reservation (shape)` in `web/packages/app/tests/transcript-model.test.ts:1427` | User-prompt fold contract, not tool-group compensation |
| Proposed after decision | `tool_fold_preserves_clicked_header_and_reservation` in `web/packages/app/tests/transcript-model.test.ts` and Rust transcript tests | Approved explicit-click behavior with held, released, retired+pinned, and escaped states |
| Proposed after decision | `thought_completion_obeys_explicit_pin_and_scroll_policy` in the same web/Rust owners | Automatic transition follows selected policy; explicit pins win; reduced motion is deterministic |
| Proposed after decision | `user_scroll_cancels_tool_fold_compensation` in the same web/Rust owners | Wheel/touch during animation cancels compensation and does not re-engage pin |

## 4. Gaps this ticket closes

| Item | Kind | Desktop value | Web value (file:line) | Fix |
| --- | --- | --- | --- | --- |
| Tool-fold scroll ownership | SHARED PRODUCT DECISION | toggle_fold only mutates fold; prompt compensation is separate (`crates/ui/src/transcript.rs:4376,4518-4525`) | Same separation (`web/packages/app/src/lib/tool-motion.ts:618-628`; `web/packages/app/src/components/transcript.tsx:1164`) | Decide explicit tool/detail click ownership on both clients; preserve reservation |
| Automatic thought closure | SHARED PRODUCT DECISION | No toggle timestamp returns target height directly (`crates/ui/src/transcript.rs:5986,6025-6040`) | Same immediate closure (`web/packages/app/src/components/tool-group.tsx:155,314-328`) | Choose retain-open or controlled/current close before implementation; do not label current parity a missing port |
| Narrow thought readability | SHARED GEOMETRY; OUTSIDE CURRENT FIX | 96-column preprocessing and truncated fixed rows (`crates/ui/src/transcript.rs:387,6773-6788`) | Same model (`web/packages/app/src/lib/transcript.ts:351`; `web/packages/app/src/styles/app.css:9021-9038`) | Preserve here; responsive wrapping requires separately specified analytic-height changes |

## 5. Do not

- Do not label this as a missing port of native user-prompt compensation; both clients lack tool-group-specific compensation.
- Do not delete own-send reservation, change fill retirement, or move the working trailer to hide symptoms.
- Do not choose global no-auto-open policy here; ticket 68 records that separate product question.
- Do not conflate inner thought completion, outer group default closure, and navigation replay.
- Do not add responsive thought text wrapping or guessed animation durations.
- Do not change expected desktop policy tests until the chosen product behavior is recorded.
- Do not add frosted surfaces or port desktop-only GPUI list internals to the web.
- Do not mark source-derived hypotheses or pure tests as runtime visual proof.

## 6. Acceptance

- [ ] Choices A/B/C, automatic/manual distinction, and reduced-motion semantics are recorded before code work.
- [ ] Both clients implement the same chosen behavior for explicit group and detail clicks.
- [ ] Explicit pins override automatic defaults; replay does not impersonate thought completion.
- [ ] Own-turn reservation survives any approved release of hold; fill retirement remains established behavior.
- [ ] Exactly one scroll owner acts during a fold; wheel/touch/navigation cancels tool compensation immediately.
- [ ] User prompt Show more/less remains correct and separate.
- [ ] Tests and runtime captures cover each ownership state, including bottom clamping and late measurements.
- [ ] Paired desktop/web screenshots plus short motion captures cover the matrix below. Use the existing app or coordinator-operated captures; subagents must not start long-running server/app/browser processes. If captures are unavailable, leave visual acceptance pending; do not waive it.
- [ ] Future `pnpm -r build` from `web/` and `pnpm test` from `web/packages/app/` pass after implementation.
- [ ] If Rust changes, future `cargo check -p roboco-ui` and `cargo test -p roboco-ui` pass.
- [ ] Reduced motion and desktop/phone geometry are checked; no arbitrary new values replace shared tokens.

| State / capture | Required observation under chosen policy |
| --- | --- |
| Live thought unresolved→resolved, outer group stays open | Inner detail target/timing and header/reading anchor |
| Last streaming group auto-closes; completed group manually toggles | Separate automatic and explicit behavior |
| Own-send runway held; released-but-reserved | Prompt/header position, hold release, reservation retained |
| Runway retired and tail pinned | Defined handoff; no competing compensation/spring |
| Manually escaped viewport; wheel/touch mid-fold | Reading anchor preserved according to policy; immediate cancellation |
| A→B→A and replay >500 ms | No completion-like transition fabricated by replay; ticket 68 pin policy respected |
| Reduced motion and repeated clicks near bottom | Coordinated endpoint placement, no stale compensator or scroll clamp surprise |

## Comments

Documentation created from read-only source inspection at `37c354ff`. No implementation, runtime capture, tests, or builds have been performed. Proposed test names identify future coverage.

2026-09-20 (implementation, wp-fu/71) — the recorded A/B/C/D policy landed on both clients.

**Web** (`feat(web): ticket 71 fold scroll ownership + animated thought close`):
- `lib/tool-fold-scroll.ts` (new): the policy as pure functions — one `ToolFoldCompensation` (a constant content-space anchor + fixed screen y held for `TOOL_FOLD_MS`), `toolFoldCompensationWrite` (drift-only correction: the write restores the anchor after a browser clamp or leftover write; it never interpolates, so reduced motion snaps geometry and anchor together by construction), `toolFoldCompensationDone`, and `automaticToolFoldCompensationArms` (§2.3's one-owner gate: an automatic transition arms only while no pin, held runway, user-fold compensation, escape anchor, or pending viewport restore is live).
- `lib/tool-motion.ts`: `sync` now seeds the animated thought close — a LIVE unresolved→resolved flip with no explicit pin (all tool groups, standalone included) writes a detail fold `{open: null, epoch+1, from: <rendered card height>, toggledAt: now}`, so the chip closes over the existing 140ms EASE_OUT tween instead of snapping; `noteDetailRendered(key, cardHeight)` records the renderer's per-paint card height (the tween's `from`); `onAutomaticFoldTransition` emits genuine completions and `noteRendered`'s rendered-open flip so the scroller can arm the reading anchor; the replay baseline and the per-surface store lifetime clear the trackers (replay/arrival never impersonate a completion — ticket 69's guard); explicit pins win in every case (the seed never fires under a pin; ticket-68 capture still drops the seeded, unpinned folds so restores stay jump-free).
- `components/tool-group.tsx`: both explicit click paths (`onToggleGroup`, the chip toggle) measure the clicked header from the click event and hand it to the scroller BEFORE flipping the fold state; `ToolGroupRow` reports per-chip card heights from the shared geometry (ticket 70's estimator/renderer agreement carries over — the estimator tweens with the same fold state).
- `components/transcript.tsx`: `onToolFoldNav` (release follow/hold via `beginScrollNavigation` — the reservation survives — then arm the clicked-header anchor), the automatic-transition subscription (reading-anchor arm under the one-owner gate), the per-frame step (re-resolves the row by id, writes drift corrections via `writePreserving`, yields to a re-engaged pin/hold, stands down at `TOOL_FOLD_MS`), and the per-commit escape-anchor preserve now yields while the tool compensator is live (one owner, no fighting writes). C needs no new web path beyond this: an escaped viewport is preserved by the existing per-commit preserve, a pinned tail keeps the spring, and the automatic arm covers the no-owner states.
- `components/stick-controller.ts`: new `onNavigation` callback fired in `beginScrollNavigation` (rail glide / selection auto-scroll / user-fold toggle cancel the tool compensation); `onUserInput` clears it for wheel/touch. The own-send reservation lifecycle is untouched.

**Rust** (`feat(ui): ticket 71 native fold scroll ownership mirror`):
- `ToolFoldScroll` + pure `tool_fold_scroll_correction`/`tool_fold_scroll_finished`/`automatic_tool_fold_compensation_arms`: the same constant-anchor policy over `list.bounds_for_item` (item-top + in-row offset, window space — the `step_user_collapse_scroll` idiom).
- `toggle_fold(row_id, row_ix, header_offset, …)` and the extracted `toggle_detail_fold(key, row_ix, header_offset, card_height, default_open)` (the chip click closure now calls the method): both run `begin_scroll_navigation` first — release follow/hold, keep the reservation, cancel prior compensations — then arm the clicked header's anchor. `render_tool_group` gained `row_ix` + `content_offset` (the row's top gap) and computes each chip header's offset from the rendered geometry (header reveal, `CHIPS_TOP_PAD`, per-row reveal clips, card centering margin).
- `step_tool_fold_scroll` advances once per frame (scheduled beside `step_user_collapse_scroll` in render): drift-only `scroll_by` corrections, yields to a re-engaged pin/held runway, disarms past `TOOL_FOLD`. Cancellation mirrors the user-fold contract: `handle_scroll`, `begin_scroll_navigation`, `jump_to_bottom`, `on_own_send`, and the attach reset all clear it synchronously.
- The animated thought close: `sync`'s per-chip tracker (`thought_seen_resolved` + `detail_card_heights`, cleared on attach and on the replay baseline) seeds `tool_details` exactly like the web store; `render_tool_group` records card heights per paint; the rendered-open flip (C) and the completion seed arm `arm_automatic_tool_fold_scroll` (the scroll-top item) under the one-owner gate.
- `live_thought_streams_open_and_settles_closed` extended per the decision: the row-model assertions are unchanged, plus the real `sync`/render drive — mid-flight the completion owns the anchor (tween clock armed, epoch > 0 keeps the body mounted, `from` = the rendered open height, no pin invented), the end state stays closed. `trailing_group_auto_opens_only_while_streaming` untouched (auto-open policy unchanged).

**Test evidence (Windows):**
- Web: `pnpm -r build` passes (tsc + vite, all 6 workspace packages); `pnpm test` in `web/packages/app`: 91 files / **1503 tests passed** (no registry flake this run). New: `tool_fold_preserves_clicked_header_and_reservation` (pure anchor math incl. browser-clamp restore + splice re-targeting; the held/released/retired+pinned/escaped controller matrix; a mounted jsdom integration — real header click through the real store/surface proves release + reservation retention), `thought_completion_obeys_explicit_pin_and_scroll_policy` (seed/geometry mid-flight tween + closed end state, pins win, baseline + armed-arrival never animate, reduced-motion snap via the shared geometry, the ownership-gate matrix), `user_scroll_cancels_tool_fold_compensation` (wheel cancels synchronously, no pin re-engagement, a queued frame neither writes nor resurrects, navigation re-arms→cancels), plus `chat-arrival.test.ts`: a thought completion during the armed arrival window records without animating; the same flip after the window closes seeds. Ticket-68 regressions (`explicit_fold_choices_survive_real_chat_switch`, `restored_folds_do_not_replay_arrival_motion`) and `folding_releases_sent_turn_hold_without_removing_reservation (shape)` stay green.
- Rust: `cargo check -p roboco-ui` clean (no new warnings); `cargo test -p roboco-ui`: **944 passed, 0 failed, 5 ignored**. New cross-platform `#[gpui::test]`s: the three ticket names (matrix, seeding/pins/replay/ownership + a reduced-motion snap through a genuine group auto-close with aged reveal starts, cancellation + navigation matrix + yield-to-pin) — the linux-gated `user_fold_scroll` windowed suite is untouched and runs in CI's ubuntu jobs.

**Pending runtime evidence (explicitly not captured — no app/browser was started):** the §6 capture matrix (live thought close mid-flight, auto-close vs manual toggle, held/released/retired+pinned/escaped viewport states, wheel/touch mid-fold cancellation, A→B→A and replay >500ms, reduced motion + repeated clicks near the bottom, browser clamping near the scroll end, late measurement) needs paired desktop/web screenshots and short motion captures through the coordinator. Two known limitations a reviewer should weigh: (1) near the physical scroll end a shrink can exceed the available scroll range — the compensator writes are clamped by the browser/list, which is the documented best-effort ("browser clamping must be tested near scroll end"); (2) a pre-existing web mount edge surfaced while writing the mounted test — a pending echo landing in the SAME commit as the scroller's presence flip makes the attach effect re-run (detach+attach), wiping the freshly-installed runway; the test sends after mount (the real flow) and the edge is out of this ticket's scope (the reservation's own path must stay untouched).
