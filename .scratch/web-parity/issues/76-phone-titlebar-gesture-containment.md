# 76 — Phone titlebar: prevent touch panning from app chrome

**What to build:** Holding or dragging the app's phone titlebar should not initiate browser panning of the chat/document. Keep titlebar buttons tappable and distinguish the app's titlebar from the operating system status bar, whose native gestures are outside this CSS surface.

**Blocked by:** None — can start immediately; coordinate overlapping files with the other assigned tickets.

**Status:** ready-for-agent

**Research:** `../research-2026-09-20/followup-layout-motion.md` §3.76, §4.76, §5.76. The relevant tables are inlined below; the research pointer is optional depth.

**Desktop reference (for lookups only):** Exact Rust symbols and source locations appear in the spec and tests tables below. All source positions refer to `37c354ff`; check the symbol if later commits move lines.

**Web files to touch / read:**

| File | Change | Owned component / responsibility |
| --- | --- | --- |
| web/packages/app/src/styles/app.css | edit | phone-scoped .titlebar touch-action; correct misleading gesture comments |
| web/packages/app/src/components/titlebar.tsx | read only unless demonstrated necessary | verify titlebar click controls and hit regions; no speculative handlers |
| web/packages/app/tests/phone-titlebar-gestures.test.ts | new if useful | narrow stylesheet contract; actual gestures require device validation |

## 1. Context a fresh session needs

- The user asks to remove "when I press and hold the titlebar, it scroll up? like automatically?" entirely.
- Ticket50 added document overscroll containment, phone100dvh, safe-area inset and titlebar selection suppression. Those remain relevant but do not change the meaning of touch-action.
- The current titlebar and control rules use touch-action:manipulation. Their comments incorrectly claim that it prevents panning.
- W3C Pointer Events defines manipulation as allowing panning and continuous zooming. It disables some multi-activation gestures such as double-tap zoom, not ordinary pan.
- No app titlebar hold timer, touch drag handler or scroll command was found. Buttons have ordinary click callbacks.
- Desktop titlebar drag moves the native window; that is not a phone scroll interaction to port.
- The app bar is a DOM region including its safe-area handling. Touching the OS/browser status bar may trigger native scroll-to-top behavior that app CSS cannot promise to disable.
- Static evidence proves the wrong guard value. The exact observed movement may be document/visual viewport panning or transcript compensation after viewport resize; device observation is required.

The project vocabulary is chat, space, engine and harness. Session means a pairing credential; old component names such as new-thread/new-session remain source identifiers. No engine RPC, persisted chat data, pairing behavior or user-facing wording changes are part of this ticket.

## 2. Spec

### 2.1 Geometry, behavior, motion and source contract

| Property / channel | Current or desktop contract | Required target | Source at 37c354ff |
| --- | --- | --- | --- |
| Phone titlebar gesture policy | touch-action:manipulation allows panning | Apply touch-action:none to the app titlebar at phone widths; preserve ordinary click activation and selection suppression | web/packages/app/src/styles/app.css:323-328; W3C Pointer Events 3§8.3 |
| Button descendants | Controls separately declare manipulation | Ancestor titlebar none constrains gestures initiated on descendant controls; verify the actual hit-tested region | web/packages/app/src/styles/app.css:461,504; W3C Pointer Events 3§8.2 |
| Viewport and safe area | Phone100dvh chain; titlebar grows/shifts by safe-area inset | Keep existing layout; guard includes app chrome within the inset, not OS-owned status bar | web/packages/app/src/styles/app.css:373-392 |
| Document containment | html/body overscroll-behavior:none; body overflow:hidden | Preserve existing containment; do not assume it alone proves no visual-viewport motion | web/packages/app/src/styles/app.css:26-28,43 |
| Desktop behavior | Native titlebar hands drag to window compositor | No web native-window drag port and no desktop-width gesture/layout changes | crates/ui/src/shell.rs:3941-3985 |
| Control activation | Window/nav controls invoke onClick | Taps continue toggling/navigating; no long-press scroll behavior is added | web/packages/app/src/components/titlebar.tsx:399-408,431-439 |

Primary browser authority: [W3C Pointer Events 3, §8.2–8.3](https://www.w3.org/TR/pointerevents3/#details-of-touch-action-values). The ancestor/descendant intersection and distinction between pan and control activation come from this specification.

### 2.2 State transitions

| State | Trigger / condition | Required behavior |
| --- | --- | --- |
| Hold title text/background | Phone app titlebar, no deliberate movement | No new document/transcript scroll initiated by chrome interaction |
| Slight vertical/horizontal drag | Gesture begins on app titlebar | Browser panning disallowed for that region; regular content scrolling unaffected |
| Tap titlebar controls | Sidebar toggle, navigation, pane toggle | Normal click action works once |
| Drawer open | Left or right phone drawer visible | App titlebar gesture guard still applies; drawer content scrolls normally |
| Keyboard visible | Composer focused; touch app bar | Record visual viewport and transcript changes separately; no unsupported promise about OS keyboard behavior |
| OS status bar touch | Gesture outside app DOM | Classify separately; do not claim app CSS can disable native OS scroll-to-top |

### 2.3 Render order, data and interactions

The titlebar overlays the shell; its cluster, identity and trailing controls remain in their current order. The touch policy applies to the app-owned phone titlebar region. Existing onClick controls continue to act normally. No touch timer, state store, RPC or visible setting is required.

### 2.4 Bounded work sequence

1. Reproduce on an authorized existing phone browser or obtain coordinator/user recording; identify whether the finger lands in app titlebar or OS/browser chrome.
2. Apply phone-scoped touch-action:none to the app titlebar and correct the inaccurate manipulation comments without broadening the rule to the transcript or entire document.
3. Retain user-select suppression, safe-area geometry and existing button click handlers. Verify ancestor gesture intersection covers descendants before adding redundant per-button overrides.
4. Measure document scrollingElement.scrollTop, transcript.scrollTop and visualViewport height/offset during the interaction. Attribute any remaining motion before proposing additional code.
5. If motion remains after the correct guard, investigate the measured trigger only. Avoid body-wide gesture suppression or preventDefault listeners that could break content scrolling.
6. Record app-bar hold/drag, ordinary taps, drawer and keyboard states on real mobile browsers. Keep unavailable device evidence pending rather than claiming the CSS declaration proves all symptoms fixed.

### 2.5 Runtime / visual matrix

| Scenario | Setup | Evidence required |
| --- | --- | --- |
| App title text, blank chrome, each control | Hold, slight drag and tap; iOS/Android where available | No app-initiated pan; taps work; record scroll offsets |
| Drawer closed/left open/right open | Gesture starts inside app titlebar | Titlebar stays guarded; drawer content scroll remains normal |
| Keyboard up/down and URL bar states | Composer focused and unfocused | Separate viewport resize from transcript/document movement |
| Safe area and breakpoint | Notched phone;768px/769px boundary; desktop pointer | No layout change; desktop behavior unaffected |
| OS status bar control case | Touch outside app DOM | Document separately; no unsupported prevention claim |

Record build identity, viewport, device/browser, relevant preference state and exact action with each capture. Use matched native/web recordings where native behavior exists; phone-only cases use device evidence and the stated desktop contract. For visual motion, include intermediate frames or a recording, not just two endpoint screenshots.

## 3. Pure logic to port and meaningful tests

| Type | Verified existing file / prospective file | Test or scenario name | Required coverage |
| --- | --- | --- | --- |
| New optional stylesheet contract | web/packages/app/tests/phone-titlebar-gestures.test.ts | phone titlebar disables panning without changing desktop titlebar or transcript touch policy | Check scoping if the project's stylesheet-test idiom makes this valuable; not a substitute for gestures |
| Required device scenario | Authorized iOS Safari and Android Chrome where available | hold and slight drag begin on app titlebar; taps remain functional | Record document, transcript and visual viewport offsets |
| Desktop reference only | crates/ui/src/shell.rs:3941-3985 | titlebar_drag_region | No native phone equivalent or invented Rust test mapping |

Use the state/geometry contracts above to test observable behavior. Do not add tests that merely count occurrences of a source token or copy an unconnected arithmetic implementation. Reuse existing motion signals rather than adding conflicting clocks.

## 4. Gaps this ticket closes

| Item | Kind | Desktop / required value | Web value / evidence | Fix or deliverable |
| --- | --- | --- | --- | --- |
| 76a | gesture semantics | Desktop chrome does not pan transcript; mobile has no native analog | manipulation explicitly permits pan (app.css:326,461,504; W3C Pointer Events 3§8.3) | Phone app titlebar uses none; preserve clicks |
| 76b | diagnostic evidence | Requested app-titlebar hold should leave content stable | Prior ticket incorrectly treated manipulation as a no-pan guard; exact device scroll source unmeasured | Record app hit region and document/transcript/visual-viewport movement; distinguish OS status bar |

## 5. Do not

- Do not disable touch panning across the transcript, drawers, settings or entire body.
- Do not add speculative touch timers, scroll-to-top interception or native-window dragging.
- Do not conflate OS/browser status-bar gestures with app titlebar behavior.
- Do not claim manipulation prevents pans or that overscroll containment proves viewport stability.
- Do not change phone dimensions, safe-area policy or desktop titlebar interaction.
- Do not start long-running processes, servers, browsers, `pnpm dev`, `cargo run` or `web_smoke` from a subagent. Use an already authorized running app or coordinator/user captures.
- Do not waive runtime/visual evidence. If access is unavailable, record the relevant acceptance as pending.
- Do not mix unrelated changes or modify sibling tickets' code ownership without coordinator agreement.

## 6. Acceptance

- [ ] Phone app titlebar explicitly disallows direct-manipulation panning while all existing titlebar button taps still work.
- [ ] Touch/scroll behavior inside transcript and drawers remains normal.
- [ ] Recordings identify app DOM versus OS status bar and distinguish document, transcript and visual-viewport movement.
- [ ] If the exact reported hold symptom persists, evidence names the residual trigger; no unverified all-fixed claim.
- [ ] Each required runtime-matrix case has evidence or an explicit pending entry; untested cases are not represented as passed.
- [ ] Relevant existing and new behavior tests pass; record exact suites and results.
- [ ] From `web/`, `pnpm -r build` passes; from `web/packages/app/`, `pnpm test` passes. Run these only during authorized implementation, not ticket creation.
- [ ] Use shared tokens where available; web remains opaque and reduced motion reaches correct final state.

## Comments

Created from read-only source research at `37c354ff`. Implementation and runtime validation have not run. The current authoring task only writes the ticket and research artifact.
