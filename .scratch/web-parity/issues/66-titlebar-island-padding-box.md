# 66 — Titlebar island: restore the desktop padding-box geometry

**What to build:** Make the collapsed-sidebar navigation island contain its buttons with the same bounds as the desktop. Correct the earlier research's padding-box arithmetic and the tests that currently encode an island 20px too narrow.

**Blocked by:** None — can start immediately; coordinate overlapping files with the other assigned tickets.

**Status:** ready-for-agent

**Research:** `../research-2026-09-20/followup-layout-motion.md` §3.66, §4.66, §5.66. The relevant tables are inlined below; the research pointer is optional depth.

**Desktop reference (for lookups only):** Exact Rust symbols and source locations appear in the spec and tests tables below. All source positions refer to `37c354ff`; check the symbol if later commits move lines.

**Web files to touch / read:**

| File | Change | Owned component / responsibility |
| --- | --- | --- |
| web/packages/app/src/components/titlebar.tsx | edit | titlebarIslandHorizontalGeometry and cluster markup if needed |
| web/packages/app/src/state/layout.ts | edit | island/cluster geometry constants only as needed |
| web/packages/app/src/styles/app.css | edit | .titlebar-cluster, .titlebar-island and phone top inset preservation |
| web/packages/app/tests/titlebar-island.test.ts | edit | replace incorrect horizontal assertions; preserve vertical and target tests |

## 1. Context a fresh session needs

- The island is decorative chrome behind sidebar toggle, Back and Forward; it is not the entire titlebar or the trailing right-pane band.
- Web cluster starts at x10, has no internal horizontal padding and contains 82px of controls when the new-chat plus is absent.
- Desktop cluster starts at x0 and has 10px horizontal padding on both sides, so its total width is 102px for the same controls.
- The island is absolutely positioned left 6/right 0. Absolute offsets resolve against the padding box (border removed), not against the content box (padding removed).
- Prior research called [10,92] the desktop padding box. That is the content box; the correct desktop island is [6,102], width 96.
- The installed Taffy 0.12.2 source confirms this directly. Its container-relative size subtracts borders, and left offset adds only the border plus the declared inset.
- The helper titlebarIslandHorizontalGeometry is only imported by its tests today, not used by JSX. Updating helper arithmetic alone cannot fix the rendered island.
- At rest island and plus targets are mutually exclusive, but a route change can show plus while the old island fades out. Include that transient geometry.

The project vocabulary is chat, space, engine and harness. Session means a pairing credential; old component names such as new-thread/new-session remain source identifiers. No engine RPC, persisted chat data, pairing behavior or user-facing wording changes are part of this ticket.

## 2. Spec

### 2.1 Geometry, behavior, motion and source contract

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

### 2.2 State transitions

| State | Trigger / condition | Required behavior |
| --- | --- | --- |
| Canvas, sidebar collapsed, background resolves | Island target 1; plus absent | Island [6,102]; buttons fully contained including hover boxes |
| Canvas, sidebar open | Island target 0 | Controls retain identical x positions while island fades |
| Chat selected | Plus shown; island target 0 | Controls span [10,124]; residual fading island extends to 134 |
| No background | Island target 0 | No invented plate or changed control layout |
| Phone safe area | Phone viewport with nonzero top inset | Only existing vertical safe-area displacement; horizontal bounds same |
| Reduced motion | Preference enabled | Immediately correct final geometry and visibility |

### 2.3 Render order, data and interactions

The cluster contains decorative island, sidebar toggle, Back/Forward group and conditional plus. The island stays pointer-events:none and behind controls. The identity and trailing pane band are separate titlebar children. Keep existing labels, click callbacks, enabled/disabled states and new-chat action. No store, RPC or persisted data changes.

### 2.4 Bounded work sequence

1. Replace the incorrect horizontal constants/helper assertions using the table above. Record the prior research correction explicitly in implementation comments.
2. Choose one equivalent rendered model: desktop-shaped cluster at left 0 with padding-inline 10, or existing left 10 cluster with island left -4/right -10. Prefer the desktop-shaped containing block if it avoids collateral layout changes.
3. Keep controls' window-space x positions, gap rhythm, identity inset, titlebar pane-band allocation and vertical safe-area behavior unchanged.
4. Retain conditional plus mounting and verify geometry during island fade-out when plus appears. Coordinate any animation-owner edits with ticket 64.
5. Verify actual DOM rectangles and desktop captures, including hover backgrounds. Make tests observe or share actual rendered geometry rather than maintaining a disconnected arithmetic oracle.

### 2.5 Runtime / visual matrix

| Scenario | Setup | Evidence required |
| --- | --- | --- |
| Blank canvas, sidebar collapsed | No plus; hover each control; back/forward enabled and disabled | Desktop/web island bounds and button boxes match |
| Sidebar open→closed→open | Normal and reduced motion; reverse mid-animation | No control movement or vertical jump |
| Canvas→chat→canvas | Plus mount/unmount while island fading; pane open/closed | Transient plate contains the correct control row |
| Phone and narrow desktop | Phone notch inset and 769px boundary | Correct top inset; no horizontal overflow or identity collision introduced |

Record build identity, viewport, device/browser, relevant preference state and exact action with each capture. Use matched native/web recordings where native behavior exists; phone-only cases use device evidence and the stated desktop contract. For visual motion, include intermediate frames or a recording, not just two endpoint screenshots.

## 3. Pure logic to port and meaningful tests

| Type | Verified existing file / prospective file | Test or scenario name | Required coverage |
| --- | --- | --- | --- |
| Existing incorrect expectations to replace | titlebar-island.test.ts:65,72,78 | with the `+` hidden the island spans the desktop's [16, 92]; with the `+` shown its 32px slot extends the span to [16, 124]; centers the icons like the desktop: pill center 54 over the [10, 92] controls | Replace bounds with [6,102] and [6,134]; preserve center 54 without plus |
| Existing desktop → web | crates/ui/src/shell.rs:8269; titlebar-island.test.ts:29 | island_stays_centered_on_controls_while_expanding | Preserve height and center across progress |
| Existing | titlebar-island.test.ts:95 | island_target | Preserve resolved-background and route/selection/sidebar gate |
| New geometry integration assertion | web/packages/app/tests/titlebar-island.test.ts | rendered island bounds contain all navigation button rectangles | Connect assertion to rendered CSS/markup geometry; actual browser rectangles remain required |

The pure horizontal rule is: controlsWidth=82+(plus?32:0), desktopContainerWidth=controlsWidth+20, islandLeft=6, islandRight=desktopContainerWidth. Vertical geometry stays height=28+4×clampedProgress and center21. If retaining a helper, its result must be tied to actual CSS/markup behavior.

## 4. Gaps this ticket closes

| Item | Kind | Desktop / required value | Web value / evidence | Fix or deliverable |
| --- | --- | --- | --- | --- |
| 66a | layout | Island [6,102], width 96 without plus (shell.rs:4025-4041; Taffy flexbox.rs:2164-2167,2336-2340) | Island [16,92], width 76 (app.css:347-356,410-414) | Restore padded containing block or equivalent 20px wider island |
| 66b | regression evidence | Desktop keeps every 24px control inside island bounds | Helper tests certify the narrower bounds; helper is unused by JSX (titlebar.tsx:143; titlebar-island.test.ts:64) | Correct expectations and verify actual rendered rectangles |

## 5. Do not

- Do not change button size, icon size, hit targets or gap rhythm to make the wrong island fit.
- Do not keep [16,92] as a desktop reference; it came from incorrect padding-box arithmetic.
- Do not fix only the unused helper while leaving CSS unchanged.
- Do not change identity/pane-band geometry or add native desktop drag controls.
- Do not restore frosted web styling.
- Do not start long-running processes, servers, browsers, `pnpm dev`, `cargo run` or `web_smoke` from a subagent. Use an already authorized running app or coordinator/user captures.
- Do not waive runtime/visual evidence. If access is unavailable, record the relevant acceptance as pending.
- Do not mix unrelated changes or modify sibling tickets' code ownership without coordinator agreement.

## 6. Acceptance

- [ ] Actual no-plus web island bounds are [6,102] relative to window origin, with unchanged [10,92] button span.
- [ ] With plus present, island bounds are [6,134] and control span remains [10,124].
- [ ] Hover button rectangles fit inside the panel; screenshot/DOM evidence includes enabled and disabled navigation controls.
- [ ] Vertical geometry, target gate, reduced motion, safe area and reversal retain their existing behavior.
- [ ] Each required runtime-matrix case has evidence or an explicit pending entry; untested cases are not represented as passed.
- [ ] Relevant existing and new behavior tests pass; record exact suites and results.
- [ ] From `web/`, `pnpm -r build` passes; from `web/packages/app/`, `pnpm test` passes. Run these only during authorized implementation, not ticket creation.
- [ ] Use shared tokens where available; web remains opaque and reduced motion reaches correct final state.

## Comments

Created from read-only source research at `37c354ff`. Implementation and runtime validation have not run. The current authoring task only writes the ticket and research artifact.

**2026-09-20 — implemented on `wp-fu/66` (commit 58d9027b).** Landed the desktop-shaped containing block (step 2's preferred option): `.titlebar-cluster` is now `left: 0` + `padding-inline: var(--rb-titlebar-pad)`, the island keeps `left: 6px / right: 0`, so the rendered island is [6, 102] without the `+` and [6, 134] with it, while controls keep [10, 92] / [10, 124]. The padding-box correction is recorded in code comments (app.css cluster + island blocks, `titlebarIslandHorizontalGeometry`, layout.ts's `TITLEBAR_CLUSTER_PAD`). The helper was re-derived from the padded container and its tests now read the SHIPPED stylesheet (`readFileSync` of app.css, same pattern as sidebar-fade.test.ts) and derive island + button bounds from the cluster's real `left`/`padding-inline`, the island's real insets, the 24px `.window-control` and the gap tokens — helper output is asserted equal to that CSS-derived model, so it can no longer be a disconnected oracle. Vertical geometry, `island_target` gating, the `useIslandTween` loop (ticket 64's ownership — untouched), phone safe-area top inset and `touch-action` (ticket 76's ownership — untouched) are unchanged. Verification run: from `web/`, `pnpm -r build` — passed (all 6 projects, app `tsc --noEmit` + vite build ✓). From `web/packages/app/`, `pnpm test` — passed: 86 files, 1364/1364 tests (includes the rewritten titlebar-island suite, 12/12; run alone: `pnpm vitest run tests/titlebar-island.test.ts` 12/12). One intermediate run showed a single failure in an unrelated timing-based backoff test (`FAST_BACKOFF` elapsed-ms assertion); it passed on immediate re-run and the two subsequent full runs — noted as flaky, not caused by this change. **Pending runtime evidence (not gathered — no dev servers, browsers or web_smoke were started per the subagent rule):** every §2.5 visual-matrix case — actual DOM rectangles/screenshots for the no-plus island ([6, 102] vs [10, 92] buttons, hover boxes, enabled+disabled nav controls), sidebar open↔closed reversal frames normal + reduced motion, canvas→chat→canvas transient with the `+` mounted while the island fades, and phone notch / 769px-boundary captures, each with build identity and viewport/device/preference metadata. Acceptance items 1–3 are CSS-derived + unit-tested but still owe actual browser rectangles; item 4 is preserved-by-construction (code paths untouched) but owes captures.
