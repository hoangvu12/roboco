# 72 — Phone pane close: preserve expanded width until the slide ends

**What to build:** Closing a full-width phone pane should slide it away at the width the user was viewing, instead of narrowing it immediately before it moves. Reopening still starts in normal pane mode, matching the existing close semantics.

**Blocked by:** None — can start immediately; coordinate overlapping files with the other assigned tickets.

**Status:** ready-for-agent

**Research:** `../research-2026-09-20/followup-layout-motion.md` §3.72, §4.72, §5.72. The relevant tables are inlined below; the research pointer is optional depth.

**Desktop reference (for lookups only):** Exact Rust symbols and source locations appear in the spec and tests tables below. All source positions refer to `37c354ff`; check the symbol if later commits move lines.

**Web files to touch / read:**

| File | Change | Owned component / responsibility |
| --- | --- | --- |
| web/packages/app/src/components/right-pane.tsx | edit | phone presentation state and close/transition lifecycle; retain desktop glide |
| web/packages/app/src/styles/app.css | edit | phone .right-pane width/presentation selector and transform transition |
| web/packages/app/tests/right-pane.test.ts | edit | close logical state regression |
| web/packages/app/tests/phone-pane-close.test.ts | new | phone close presentation lifecycle tests |

## 1. Context a fresh session needs

- The phone right pane is a fixed overlay drawer, unlike the desktop's width-animated third column.
- Phone normal width is min(30rem,88vw), expanded width 100vw, and the drawer transitions transform on the existing 140ms menu-in curve.
- Both toggle-close and close reset open:false and expanded:false immediately. The class uses logical expanded directly.
- The width therefore changes from 100vw to min(30rem,88vw) in the same commit that starts translateX(100%); CSS transitions only transform.
- This is a proven phone expanded-close discontinuity. It does not establish the cause of the separate ordinary desktop-width snapping report, owned by ticket 73.
- Keep logical expanded reset immediately: a later reopen should use normal mode. A temporary presentation width is distinct from stored pane flags.
- The drawer header owns phone tabs. Inner content width is CSS 100% and surfaces stay mounted via the existing glide; do not move tabs back into the phone titlebar.
- Files-family content intentionally disappears on close on both desktop and web. This ticket changes container width continuity, not that resource policy.

The project vocabulary is chat, space, engine and harness. Session means a pairing credential; old component names such as new-thread/new-session remain source identifiers. No engine RPC, persisted chat data, pairing behavior or user-facing wording changes are part of this ticket.

## 2. Spec

### 2.1 Geometry, behavior, motion and source contract

| Property / channel | Current or desktop contract | Required target | Source at 37c354ff |
| --- | --- | --- | --- |
| Normal phone width | min(30rem,88vw) | Unchanged open and closing presentation | web/packages/app/src/styles/app.css:6901 |
| Expanded phone width | 100vw while .right-pane-expanded is present | Retain the expanded visible width through closing; release only after its slide settles | web/packages/app/src/styles/app.css:6919-6920 |
| Close state | toggle/close immediately clear open and expanded | Logical state still clears immediately; presentation remembers prior expanded width until close completes | web/packages/app/src/state/right-pane.ts:250-254,303-304 |
| Slide | Open translateX(0); shut translateX(100%); 140ms menu-in ease-out | Unchanged curve/duration; translate against retained presentation width; reduced motion settles immediately | web/packages/app/src/styles/app.css:6885-6904,6911-6913 |
| Desktop comparison | Closing resets takeover; outer width tweens while inner holds larger endpoint | Preserve desktop behavior; phone's retained width is the corresponding presentation-continuity contract | crates/ui/src/shell.rs:1987-1995,3850-3875 |
| Content and tabs | Phone header contains RightTabStrip; inner width 100%; Files-family drops content on close | Preserve tab placement and existing resource policy | web/packages/app/src/components/right-pane.tsx:73-79,160-175; styles/app.css:6929-6955 |

### 2.2 State transitions

| State | Trigger / condition | Required behavior |
| --- | --- | --- |
| Expanded open→closed | Close via pane toggle, Escape or backdrop | Immediately clear logical flags; hold expanded presentation width during slide |
| Normal open→closed | Any close entry point | Keep normal width; no unnecessary expanded hold |
| Reopen before close finishes | Same chat toggled open quickly | Release/retarget presentation deterministically to current open mode; no stale close completion hides it |
| Chat/owner switch | Different chat or route during close | Cancel old presentation ownership; honor destination pane state |
| Phone→desktop breakpoint | Viewport crosses 768px during close | Clear phone hold and restore desktop width/glide rules |
| Reduced motion or canceled transition | Preference enabled or transition cannot complete | Release hold at the correct immediate/fallback settle; no permanent 100vw closed state |

### 2.3 Render order, data and interactions

The phone aside contains its tab strip header then the inner surface body. The backdrop remains outside the aside. Read logical open/expanded and pane owner; hold only transient presentation geometry. All existing close entry points must use the same presentation policy. Do not persist the hold or change engine communication.

### 2.4 Bounded work sequence

1. Model the close presentation width independently of pane.expanded. Capture only on the expanded-open→closed edge, keyed to the current pane owner.
2. Drive the phone width selector/style from presentation state while preserving logical store behavior and the existing desktop expanded class semantics.
3. End the hold on the drawer transform transition completion; guard the target/property and provide deterministic cancellation/reduced-motion/fallback handling.
4. Handle reopen, another chat, route unmount and breakpoint changes so an old completion cannot alter a new pane state.
5. Verify all close entry points and document actual transition frames in a real phone browser or equivalent authorized recording.

### 2.5 Runtime / visual matrix

| Scenario | Setup | Evidence required |
| --- | --- | --- |
| Expanded pane close | Phone portrait; Changes, Terminal and Files; toggle/backdrop/Escape | Width stays full through slide; Files policy distinguished from container motion |
| Normal pane close and reopen | Phone portrait and landscape | Normal width unchanged; closed expanded pane reopens normally |
| Rapid reopen and owner change | Reopen mid-close; navigate chats; resize across 768px | No stale hold or old transition callback |
| Reduced motion | Preference enabled before close and mid-close | Immediate settled presentation; no flash or deferred hold |

Record build identity, viewport, device/browser, relevant preference state and exact action with each capture. Use matched native/web recordings where native behavior exists; phone-only cases use device evidence and the stated desktop contract. For visual motion, include intermediate frames or a recording, not just two endpoint screenshots.

## 3. Pure logic to port and meaningful tests

| Type | Verified existing file / prospective file | Test or scenario name | Required coverage |
| --- | --- | --- | --- |
| Existing desktop contract | crates/ui/src/shell.rs:8442 | right_panel_content_keeps_the_larger_width_only_during_transition | Reference only; no desktop change or invented phone test port |
| Existing web suite | web/packages/app/tests/right-pane.test.ts | surface keys and value equality | Preserve pane ownership and store surface semantics |
| New lifecycle test | web/packages/app/tests/phone-pane-close.test.ts | expanded close retains presentation width while resetting logical mode | Assert logical flags and rendered presentation state independently |
| New lifecycle test | web/packages/app/tests/phone-pane-close.test.ts | reopen owner change breakpoint and reduced motion cancel stale close completion | Exercise interruption paths and final presentation cleanup |

A prospective presentation reducer may take previous/current owner, phone/open/expanded state, reduced-motion flag and completion events. It must return the closing width hold independently of logical state. Choose an interface consistent with existing hooks; the transition table above is the contract, not a mandated new abstraction.

## 4. Gaps this ticket closes

| Item | Kind | Desktop / required value | Web value / evidence | Fix or deliverable |
| --- | --- | --- | --- | --- |
| 72a | phone layout continuity | Desktop close holds stable inner geometry while outer width glides (shell.rs:3850-3875); no native phone drawer | Phone close removes 100vw class before transform ends (right-pane.ts:253,304; app.css:6901,6904,6919) | Retain expanded phone presentation width until close settles |
| 72b | state separation | Desktop resets takeover immediately while preserving animation geometry (shell.rs:1987-1995) | Web uses logical expanded directly for phone width | Separate transient presentation width from logical expanded state |

## 5. Do not

- Do not delay logical expanded reset or persist phone animation state in the pane store.
- Do not change desktop width animation or remove data-pane-snap; ticket 73 diagnoses desktop close.
- Do not change phone drawer width tokens, 140ms timing, z-order, or tab placement.
- Do not retain/revive Files resources solely to change close appearance.
- Do not invent a new slide/fade or restore translucent web surfaces.
- Do not start long-running processes, servers, browsers, `pnpm dev`, `cargo run` or `web_smoke` from a subagent. Use an already authorized running app or coordinator/user captures.
- Do not waive runtime/visual evidence. If access is unavailable, record the relevant acceptance as pending.
- Do not mix unrelated changes or modify sibling tickets' code ownership without coordinator agreement.

## 6. Acceptance

- [ ] Expanded phone close maintains its initial 100vw presentation width through all non-reduced transform frames.
- [ ] Logical state clears expanded immediately, and reopening after close uses normal width.
- [ ] Normal close, rapid reopen, owner switch, breakpoint transition and reduced-motion paths settle without stale holds.
- [ ] Recordings distinguish container slide from the separately intentional Files content unmount.
- [ ] Each required runtime-matrix case has evidence or an explicit pending entry; untested cases are not represented as passed.
- [ ] Relevant existing and new behavior tests pass; record exact suites and results.
- [ ] From `web/`, `pnpm -r build` passes; from `web/packages/app/`, `pnpm test` passes. Run these only during authorized implementation, not ticket creation.
- [ ] Use shared tokens where available; web remains opaque and reduced motion reaches correct final state.

## Comments

Created from read-only source research at `37c354ff`. Implementation and runtime validation have not run. The current authoring task only writes the ticket and research artifact.
