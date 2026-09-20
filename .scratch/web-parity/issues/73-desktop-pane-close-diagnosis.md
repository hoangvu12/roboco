# 73 — Diagnose the remaining desktop right-pane close snap

**What to build:** Identify exactly which desktop-width close action snaps and compare it with the same desktop-app surface. Produce a reproducible case and a bounded fix recommendation; this ticket authorizes diagnosis and an evidence artifact, not speculative behavior changes.

**Blocked by:** None — the diagnosis can start immediately using existing authorized runtime evidence; missing captures remain pending.

**Status:** ready-for-agent

**Research:** `../research-2026-09-20/followup-layout-motion.md` §3.73, §4.73, §5.73. The relevant tables are inlined below; the research pointer is optional depth.

**Desktop reference (for lookups only):** Exact Rust symbols and source locations appear in the spec and tests tables below. All source positions refer to `37c354ff`; check the symbol if later commits move lines.

**Web files to touch / read:**

| File | Change | Owned component / responsibility |
| --- | --- | --- |
| .scratch/web-parity/research-2026-09-20/desktop-pane-close-reproduction.md | new | reproduction matrix, trace/recording references, desktop comparison and fix recommendation |
| web/packages/app/src/components/right-pane.tsx | read only | data-pane-snap, usePaneGlide and Files close policy |
| web/packages/app/src/components/app-shell.tsx | read only | pane owner, hasPane, open-width calculation and mounted host |
| web/packages/app/src/styles/app.css | read only | right-pane transition/clip rules and titlebar band |
| crates/ui/src/shell.rs | read only | toggle_right_pane and right_pane_container reference |

## 1. Context a fresh session needs

- The user reports "the right sidebar closing feels weird, like its snapping, not sliding", without identifying device width, surface, takeover state or exact action.
- Static inspection has not established a bug in ordinary same-chat desktop-width close. Preserve this distinction in the evidence.
- data-pane-snap compares previous chatId with current chatId. A same-chat toggle normally does not change that key.
- The desktop-width aside remains mounted at width 0; CSS transitions its width over 200ms. An inner right-anchored box holds the larger endpoint during close.
- Desktop Rust has the same clipped outer/stable inner model and deliberately snaps pane state on a chat owner change.
- Files and individual file content immediately unmount during close in both clients to suspend image resources. That visual disappearance can look abrupt while the container still glides.
- Expanded phone close has a separate proven width snap handled in ticket 72; it must not be counted as evidence of a desktop regression.
- A runtime diagnosis task is executable without a product decision. If evidence shows only intentional desktop behavior, record that result and the product question instead of implementing a redesign.

The project vocabulary is chat, space, engine and harness. Session means a pairing credential; old component names such as new-thread/new-session remain source identifiers. No engine RPC, persisted chat data, pairing behavior or user-facing wording changes are part of this ticket.

## 2. Spec

### 2.1 Geometry, behavior, motion and source contract

| Property / channel | Current or desktop contract | Required target | Source at 37c354ff |
| --- | --- | --- | --- |
| Ordinary desktop close | Same chat; pane.open true→false; chatId unchanged | Expected 200ms width transition with larger endpoint inner width; establish whether observation matches | web/packages/app/src/components/right-pane.tsx:137-150,227-255; styles/app.css:1167-1188 |
| Desktop native close | Capture painted width, reset takeover on close, arm right tween, no takeover content tween | Comparison baseline; do not assume a slide must translate the whole surface | crates/ui/src/shell.rs:1976-1995,3850-3875 |
| Owner switch | data-pane-snap kills transition when chatId changes | Intentional destination-state snap; classify separately from same-chat close | web/packages/app/src/components/right-pane.tsx:129-146; styles/app.css:1181-1182; crates/ui/src/shell.rs:1837-1862 |
| Files-family close | Content suppressed as soon as closed, even while pane animation remains | Desktop parity; changing visible resource-retention policy requires explicit product scope | web/packages/app/src/components/right-pane.tsx:73-79; crates/ui/src/shell.rs:6472-6475 |
| Reduced motion/drag | CSS can intentionally disable transitions | Record preference and resize flag before treating no animation as regression | web/packages/app/src/styles/app.css:1289,13203-13210 |
| Evidence deliverable | No confirmed ordinary-close root cause yet | Capture trigger, source path, actual widths/transforms/mount timing, matched native behavior and smallest justified next step | This research; baseline 37c354ff |

### 2.2 State transitions

| State | Trigger / condition | Required behavior |
| --- | --- | --- |
| Normal pane, same chat | Close Changes or Terminal via titlebar toggle | Measure outer width each frame and inner width/content lifetime |
| Files or file tab | Same close action | Separate content blanking from container motion |
| Expanded desktop pane | Close takeover | Measure conversation reveal, held inner geometry and expanded reset |
| Chat switch | Destination pane open/closed | Classify intentional snap and any lingering transition suppression |
| Rapid reversal | Close then reopen during 200ms | Check retargeting and stale timer cleanup |
| Reduced motion/resize | Preference on/off; resize immediately before close | Identify intentional transition suppression and whether flags clear |

### 2.3 Render order, data and interactions

Observe the existing shell row, overlaid titlebar and right-pane host without changing them. Collect only local geometry, flags, surface identity and event timing needed for diagnosis; redact chat content from shared artifacts where practical. No RPC, store, user-facing text or persisted preference changes are authorized.

### 2.4 Bounded work sequence

1. Use an already authorized running app or coordinator/user captures. Record build identity, viewport, surface, owner/chat, expanded state, reduced-motion setting and close entry point.
2. Reproduce the matrix and capture the same surface in the native desktop app. A screenshot cannot establish animation continuity; collect a recording or timing trace plus endpoint screenshots.
3. Inspect aside computed transition, data-pane-snap, aria-hidden, outer width, inner width, active surface mount, pane key and resize flag around the triggering commit.
4. Check whether the observed snap is pane width, content disappearance, titlebar tabs/band, conversation reflow, or a dropped frame. Trace the specific channel instead of assuming data-pane-snap caused it.
5. Write the evidence artifact with confirmed observations, rejected hypotheses, untested states and a minimal follow-up scope. If no regression reproduces, state that explicitly with covered conditions.
6. Do not implement a fix in this ticket. If only Files blanking reproduces and matches desktop, surface the product decision about preserving a closing visual/resource policy.

### 2.5 Runtime / visual matrix

| Scenario | Setup | Evidence required |
| --- | --- | --- |
| Normal desktop pane | 769px and wider; Changes and Terminal; sidebar open/closed | Outer/inner widths and same-chat key remain traceable |
| Files/file pane | Same viewport and close action as control case | Document early content unmount versus column animation |
| Takeover and chat switch | Expanded close; destination open/closed; rapid navigation | Separate takeover, owner snap and normal close |
| Reversal/preference/resize | Rapid close/open; reduced on/off; seam drag then close | Identify intended and accidental transition suppression |

Record build identity, viewport, device/browser, relevant preference state and exact action with each capture. Use matched native/web recordings where native behavior exists; phone-only cases use device evidence and the stated desktop contract. For visual motion, include intermediate frames or a recording, not just two endpoint screenshots.

## 3. Pure logic to port and meaningful tests

| Type | Verified existing file / prospective file | Test or scenario name | Required coverage |
| --- | --- | --- | --- |
| Existing desktop | crates/ui/src/shell.rs:8442 | right_panel_content_keeps_the_larger_width_only_during_transition | Reference for interpreting measured inner width |
| Existing web suite | web/packages/app/tests/right-pane.test.ts | surface keys and value equality | Reference for pane owner/surface behavior; does not prove browser animation |
| Diagnostic scenario, not a new unit test | Authorized running browser at desktop width | same-chat Changes/Terminal close vs Files close vs chat-owner switch | Record widths, flags, mount events and matched desktop recording |
| Follow-up only if a defect is isolated | Choose relevant existing suite after diagnosis | Name a behavior regression test from the confirmed trigger | Do not invent a test asserting a speculative cause |

No new production pure logic is authorized. Derive a regression test only after the trigger is established and a separate implementation scope is approved. Existing tests help interpret state, but cannot prove browser frame continuity.

## 4. Gaps this ticket closes

| Item | Kind | Desktop / required value | Web value / evidence | Fix or deliverable |
| --- | --- | --- | --- | --- |
| 73a | diagnostic evidence | Native close uses width tween and stable content (shell.rs:1976-1995,3850-3875) | Same-chat web code appears equivalent; actual reported trigger is unidentified | Produce reproducible trace and matched desktop comparison |
| 73b | behavior classification | Files content intentionally disappears while closing (shell.rs:6472-6475) | Web does the same (right-pane.tsx:73-79) | Separate existing product behavior from regression; request scope only if policy change is desired |

## 5. Do not

- Do not remove data-pane-snap or force every navigation to animate.
- Do not change Files resource policy before confirming the symptom and product intent.
- Do not claim a normal desktop close defect from the phone takeover-width bug.
- Do not implement speculative CSS/state fixes, change timings or modify desktop Rust.
- Do not waive runtime evidence or mark an unobserved hypothesis as confirmed.
- Do not start long-running processes, servers, browsers, `pnpm dev`, `cargo run` or `web_smoke` from a subagent. Use an already authorized running app or coordinator/user captures.
- Do not waive runtime/visual evidence. If access is unavailable, record the relevant acceptance as pending.
- Do not mix unrelated changes or modify sibling tickets' code ownership without coordinator agreement.

## 6. Acceptance

- [ ] The evidence artifact identifies exact build, trigger, device/viewport, surface and state, or clearly records inability to reproduce with tested matrix.
- [ ] Each observed snap is assigned to a measured channel: outer width, inner layout, content mount, titlebar or frame-time stall.
- [ ] Matched desktop/web recording establishes whether the behavior diverges or already exists in native desktop.
- [ ] The artifact recommends a bounded follow-up only when evidence supports it; unresolved product decisions and pending captures are explicit.
- [ ] Each required runtime-matrix case has evidence or an explicit pending entry; untested cases are not represented as passed.
- [ ] No production code, tests, application state or native behavior is changed by this diagnosis task.
- [ ] The result is an evidence artifact and proposed follow-up scope; unavailable runtime access is explicitly pending, not a waived check.

## Comments

Created from read-only source research at `37c354ff`. Implementation and runtime validation have not run. This ready-for-agent status applies to the bounded diagnosis task, not a preapproved behavioral fix.
