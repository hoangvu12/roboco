# 36 — Transition matrix 1:1

**What to build:** The FULL desktop route-change transition matrix, ported
1:1. The flagship case — new-thread → a chat whose right pane has stored-open
tabs — arms the **panel handoff**: a 0.320 s fade-through of the composer with
the horizontal geometry snapping at p ≥ 0.22 while invisible, a 12 px (docking)
/ 8 px (undocking) travel offset, and the `panel_departure` fast dissolve of
the hero. The departing transcript retains its source column width until the
handoff ends. Chat → chat swaps transcript content in place without a
blank-then-rows flash. And where the desktop deliberately **snaps** (a chat
switch whose destination pane flags differ), the web snaps instead of gliding.
User symptom: "the transition between new chat page to a chat that has a
sidebar should be different — look at all the cases from the desktop app, port
1:1". ("a chat that has a sidebar" = a chat whose right pane has stored-open
tabs.)

**Blocked by:** 34 (Canvas sidebar slide + titlebar island — the handoff's
synchronous width source and the hero's width share the sidebar-now plumbing
this batch establishes), 35 (Route-change double flash — the background must
hand off cleanly before the matrix's fades can be judged).

**Status:** ready-for-agent

**Research:** `../research-2026-09-19/new-thread-background-and-transitions.md`
S6(a)–(d) (+ S5(b)'s dock choreography table, which both S5/S6 reference),
consolidated gap rows G20–G24, "Pure logic to port" items 4 and 6,
"Desktop-only items NOT to port".

**Desktop reference (for lookups only):**
`crates/ui/src/shell.rs` (the `observe_pane` call 7883–7889; `right_now`
3796–3802, `right_target` 1929–1945; the chat-switch snap 1820–1845; the
transcript swap 5911–5921, 1826–1856; the dock tick 5865–5868; the retained
transcript width 7893–7903), `crates/ui/src/composer_dock.rs` (`observe_pane`,
`layout_width` 220–241, `tick` 243–311, `prepaint` 368–427,
`transcript_width` 195–204), `crates/ui/src/composer_dock/panel_handoff.rs`
(4–52), `crates/ui/src/composer.rs::set_dock_frame` (4140–4149),
`::dock_clearance_correction` (4159; shell.rs:6084–6101).

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/routes/chat-page.tsx` | edit | the `observePane` call (:497) — the synchronous pane width replaces the measured `columnWidth`; `transcriptWidth` wiring (`.chat-body`'s retained width while departing); the chat→chat transcript retention; the composer's handoff opacity (:780) already reads `dockRef.current.opacity()` — verify it actually moves |
| `web/packages/app/src/components/app-shell.tsx` | edit | publishing the synchronously-computed pane width (`paneWidth`, :175–180) to the page (or the page computes `viewport − sidebar-now − column` itself) |
| `web/packages/app/src/components/right-pane.tsx` | edit | suppress the `.right-pane` width transition when the pane KEY (chatId) changed — the desktop's chat-switch snap (:117–120 + `app.css:1021`) |
| `web/packages/app/src/lib/composer-dock.ts` | edit (small) | host-level integration only — `PanelHandoff.sample`, `DockState.layoutWidth`/`tick`/`prepaint`, `transcriptWidth` are ALREADY ported field-for-field (:262-289, :389-410, :416-472, :358-367); do not rewrite them |
| `web/packages/app/src/styles/app.css` | edit | `.chat-body` width retention support (2451–2456); the pane-key snap suppression on `.right-pane` (1009–1022) |
| `web/packages/app/tests/composer-dock.test.ts` | edit | host-level arming cases + the retained-width host case |

---

## 1. Context a fresh session needs

- Ticket 15 ported the entire pure choreography — `Glide`,
  `stage`, `DockVisuals` (both direction-specific window tables +
  `return_from_panel`), `PanelHandoff` (0.320 s fade-through,
  reversal-preserving, disable-reset), `DockState` (`tick`, `prepaint` with the
  top-anchored hero position and the 12/8 px travel, `layoutWidth` with the
  p=0.22 snap, `transcriptWidth`) — into `lib/composer-dock.ts`, with the
  desktop's tests mirrored in `tests/composer-dock.test.ts`. **The pure port is
  done. What never landed is the HOST wiring** — the inputs that arm it.
- The host: `ConversationPage` (`routes/chat-page.tsx`) runs the dock. Per
  commit, a layout effect (:491-505) calls
  `dock.observePane(hasSelection, columnWidth ?? 0, !dockReduced, nowMs)`
  (:497) — the arming sample — then ticks on route changes and re-anchors the
  composer wrapper. A rAF pump (:539-552) advances the clock per animation
  frame.
- `columnWidth` (:462-481) is a **ResizeObserver-measured** value of the chat
  column — stale at the navigation commit by construction (the observer fires
  after layout).
- The desktop's arming input is **synchronous**: `observe_pane` feeds
  `right_now(cx)` — the pane's width computed from state
  (`eval_tween(right_tween, right_target)`, shell.rs:3796-3802, 1929-1945;
  on a chat switch `right_tween = None` → `right_now = right_target`, a snap).
  The web equivalent is `paneWidth` from app-shell scope
  (`components/app-shell.tsx:175-180` computes it synchronously off the router
  + pane store) or `viewport − sidebar-now − columnWidth` computed
  synchronously.
- The composer's fade-through opacity is already wired inline:
  `.persistent-composer`'s `style.opacity = dockRef.current.opacity()`
  (`routes/chat-page.tsx:780`) — it stays 1 today only because the handoff
  never arms.
- The pane column: `RightPane` (`components/right-pane.tsx:117-141`) carries
  an inline width `calc(<open ? openWidth : 0>px + var(--rb-pane-edge-offset))`
  (:120) with a 200 ms CSS width transition (`.right-pane`,
  `app.css:1009-1022`). It is NOT keyed per chat — a chat switch re-renders the
  same element, so a pane-flag change glides 200 ms. On the canvas route the
  pane is freshly mounted at full width when a chat with an open pane is
  selected (nothing to transition from). `.main` clips only under
  `.shell-pane-gliding`/takeover (`app.css:1930-1933`).
- The transcript: chat→chat swaps through a **fresh `TranscriptStore` per
  chatId** (`routes/chat-page.tsx:183-192`, with a per-chat offline cache at
  :190) and an async first load → a blank-then-rows flash. The departing
  (canvas-bound) retention already exists (:197-213, :572-581) — the
  `transcriptWidth` method for it is ported but **never called** (grep: zero
  call sites of `transcriptWidth` outside the lib + tests).
- The width caps: the composer is clamped to `COMPOSER_MAX_WIDTH = 768`
  (`lib/composer-flip.ts:55`, used at `chat-page.tsx:559`).
- Ticket 35 hoists the artwork/readiness to shell scope (the hero continuity
  rows below poison cases A/D); ticket 34 establishes the animated
  sidebar-now width source. Both must land first — this ticket's width
  computation and its background fades build on them.
- Vocabulary (`CONTEXT.md`): **chat**, **space**, **engine**, **harness**.

---

## 2. Spec

### 2.1 Arming the handoff — the synchronous width

The arming input today, verbatim from the research:

| | desktop | web |
|---|---|---|
| call | `observe_pane(selected_chat.is_some(), right_target_width, on_chat && !reduced, render_time)` (shell.rs:7883-7889) | `dock.observePane(hasSelection, columnWidth ?? 0, !dockReduced, nowMs)` (chat-page.tsx:497) |
| width input | `right_now(cx)` — the pane's width **computed synchronously from state** (`eval_tween(right_tween, right_target)`, shell.rs:3796-3802, 1929-1945) | `columnWidth` — a **ResizeObserver-measured** value (chat-page.tsx:462-481), stale at the navigation commit |

Sequence for new-thread → chat-with-pane (web), verbatim:

1. navigation commit: `observePane(true, W_canvas)` with `previous = (false,
   W_canvas)` → docked differs but width equal and no handoff running → **no
   arm** (composer-dock.ts:270-277);
2. the ResizeObserver fires with the narrowed width → next commit:
   `observePane(true, W_new)` → `previous = (true, W_canvas)` → docked equal →
   **no arm** — ever.

**What to build:** feed the computed pane width into `observePane` — the
synchronous value (app-shell's `paneWidth`, `components/app-shell.tsx:175-180`,
or `viewport − sidebar-now − columnWidth` computed synchronously in the page;
after ticket 34, `sidebar-now` is the animated source, which is exactly why 34
blocks this ticket). Everything else — the fade-through, the p ≥ 0.22 snap,
the travel, the fast dissolve — is the already-ported `PanelHandoff` /
`DockState` logic responding to a correctly-armed sample.

### 2.2 The dock choreography (motion specs, verbatim)

The state machine both S5/S6 reference (`composer_dock.rs`):

| element | spec | source |
|---|---|---|
| glide | critically damped, `ω = 12/duration`, durations 0.420 s dock / 0.470 s undock, settle 0.0005 / 0.005; position AND velocity survive retarget | composer_dock.rs:23-55; proto/motion.rs:476-484 |
| phase clock | `tick(docked, reduced, now)`; a route flip resets dt to 0 (click-after-idle is the start of motion, not elapsed time) and captures the previous visuals as the choreography `from` | composer_dock.rs:243-267 |
| four channels (docking) | transcript 0.20→0.65, selectors 0.55→0.78, footer 0.78→1.00, dissolve 0.06→0.88 | composer_dock.rs:117-123 |
| four channels (undocking) | transcript 0.00→0.25, selectors 0.50→0.95, footer 0.00→0.18, dissolve 0.08→0.85 | composer_dock.rs:127-132 |
| active flag | `phase.active() \|\| choreography.is_some()` — the hero layer holds until both end | composer_dock.rs:304-309 |
| composer anchor | x = slot left; y = `bounds.top` docked / `(vh − h)·0.5 + 8` hero (top-anchored) | composer_dock.rs:377-385 |
| width | `layout_width` glides on the same clock; snaps inside the handoff's invisible interval (p ≥ 0.22) | composer_dock.rs:220-241 |
| pill height | `dock_height(amount)` = lerp(hero, session, amount); shell reserves the DESTINATION footprint via `dock_clearance_correction` | composer.rs:7489-7500, 4159; shell.rs:6084-6101 |
| morph suppression | `set_dock_frame` (active) kills the composer's own flip+height morphs — one clock owns the height | composer.rs:4140-4149 |
| radius | `COMPOSER_RADIUS − 4·amount` = 26 → 22 | composer.rs:7603 |
| transcript | `opacity = transcript()`, `top: 8·(1 − transcript())`, hidden until `transcript_geometry_ready` | shell.rs:5911-5920 |
| reduced motion | glide, channels, handoff, readiness all snap; handoff disabled (opacity 1) | composer_dock.rs:246-248, 250-251; panel_handoff.rs:32-35 |

All of this is already ported and unit-tested (`lib/composer-dock.ts`,
`tests/composer-dock.test.ts`). This ticket's §2.4 rows are the host cases
that make it observable.

### 2.3 The transition matrix (verbatim)

Common to every case: the dock tick on the docked flip (shell.rs:5865-5868),
`set_dock_frame` kills the composer's own morphs (composer.rs:4140-4149), the
choreography captures the previous visuals (composer_dock.rs:263-266), and
`dock_clearance_correction` reserves the destination footprint
(composer.rs:4159; shell.rs:6084-6101).

| case | dock | panel handoff (0.320 s) | right pane | background (hero) | what the user sees |
|---|---|---|---|---|---|
| **A. new-thread → chat, NO pane** | tick(true): 0.420 s glide + docking channels (transcript 0.20→0.65, selectors 0.55→0.78, footer 0.78→1.00) | not armed (right_now 0→0, width equal) | absent (width 0) | dissolve 0.06→0.88 on the 0.420 clock | pill glides center→bottom (height via `dock_height`, radius 26→22), transcript rises in (8 px), hero dissolves |
| **B. new-thread → chat WITH stored-open pane** | tick(true) | **armed**: `observe_pane` sees the docked flip AND `right_now` 0→pane in the same frame (shell.rs:7883-7889) | **snaps** to the target (`right_tween = None` on chat switch, shell.rs:1842; container = `eval_tween(None, target)` = target, shell.rs:3826-3844) | `panel_departure`: dissolve ramps 0→1 over `stage(p, 0, 0.18)` of the 0.320 s clock — a FAST kill (composer_dock.rs:247, 284-289) | composer fades to 0 by p≈0.18 (panel_handoff.rs:18-22), geometry switches at p ≥ 0.22 **while invisible** (layout_width snaps, composer_dock.rs:230-234), fades back 0.26→1 with a **12 px** travel offset decaying over `stage(p, 0.22, 1)` (composer_dock.rs:399-406); the pane pops in but nothing visible reflows; the transcript fades in on the docking channel |
| **C. chat → chat (same column)** | no tick (docked stays true); nothing moves | never arms (panel_handoff.rs:36-41 needs the docked flip; test :92-98) | flags snap per destination chat (shell.rs:1820-1845) | none (hero unmounted) | the transcript content swaps in place — ONE `Transcript` entity, doc swapped synchronously from state (shell.rs:5911-5921) |
| **D. chat → new-thread, NO pane on source** | tick(false): 0.470 s glide + undocking channels (transcript 0.00→0.25, selectors 0.50→0.95, footer 0.00→0.18) | not armed (width 0→0) | absent | dissolve decays 0.08→0.85 → the hero fades IN | transcript fades out with its 8 px rise, hero unfolds, selectors return, composer glides up (anchored by the surface top) |
| **D'. chat WITH pane → new-thread** | tick(false) | **armed** (docked flip + width pane→0) | snaps closed (shell.rs:1842) | `panel_return`: the short 0.320 s clock — `return_from_panel` visuals: transcript+footer decay `stage(t, 0, 0.18)`, selectors rise `stage(t, 0.26, 0.85)`, dissolve decays `stage(t, 0.26, 0.80)` (composer_dock.rs:136-147, 279-283); `amount` held at the painted value until p ≥ 0.22 then 0 (composer_dock.rs:294-303) | the departing transcript is retained at the SOURCE column width until the handoff ends (`transcript_width`, composer_dock.rs:195-204, shell.rs:7898-7903, 5915) and covered by an occluding veil (shell.rs:5925-5927); the composer carries an **8 px** travel |
| **reversal mid-flight** (either direction) | glide preserves position+velocity (composer_dock.rs:38-49; test :655-666) | reversal preserves the current opacity (panel_handoff.rs:36-41; test :77-89) | — | — | continuous — no restart-from-zero |
| **reduced motion** | everything snaps (composer_dock.rs:250-251, 680-686) | disabled — `enabled=false` resets to opacity 1 (panel_handoff.rs:32-35) | snaps | readiness snaps to 1 (effects.rs:25-27) | instant state swap |

Background handoff per case is the `dissolve` channel above; the hero layer is
held by `dock_frame.active` (shell.rs:5883) so it never drops mid-case (see
S5 — ticket 35).

### 2.4 The five host wirings

1. **Arming (§2.1):** the synchronous pane width into `observePane`
   (`chat-page.tsx:497`). After this, case B arms on the navigation commit
   itself (docked flip + width 0→pane in the same frame), and D' arms on the
   reverse.
2. **Composer fade-through:** already wired — `.persistent-composer`'s inline
   `opacity: dockRef.current.opacity()` (`chat-page.tsx:780`). With the handoff
   armed, `PanelHandoff.opacity()` produces 1→0 by p≈0.18 (ease over
   `[0, 0.18]`), geometry switch at p ≥ 0.22, and 0.26→1 back. Verify the pump
   keeps ticking while `pane.progress !== null` (the dock's `moving` flag
   covers the prepaint; the frame pump at :539-552 must not stop early —
   `dockFrame.active` alone does not cover a handoff whose glide has settled).
3. **`transcriptWidth` retention:** call
   `dockRef.current.transcriptWidth(mainContentWidth, hasSelection,
   panelHandoff)` from the page (the desktop call: shell.rs:7893-7903 — the
   retained value feeds the transcript's wrapper width,
   `main_width = (transcript_width − 10).max(0)`; on the web, fix
   `.chat-body`'s width to the retained value while `departing` — the existing
   departing veil/retention path at `chat-page.tsx:572-581` already holds the
   pixels; it just reflows into the canvas width today). This is the exact
   "exit flash" the retention exists to prevent.
4. **Chat → chat transcript swap:** keep the previous rows mounted (crossfade
   or cache-first paint — the per-chat offline cache at
   `chat-page.tsx:190` makes cache-first feasible) until the new store's first
   frame. The desktop's shape is ONE `Transcript` entity whose doc swaps
   synchronously (shell.rs:1826-1856, 5911-5921); the web keeps per-chat stores
   but must not paint a blank frame between them.
5. **Pane-flag snap on chat switch:** when the pane key (chatId) changed in
   the same commit that the pane's open flags changed, suppress the
   `.right-pane` width transition for that commit (the element must land at
   the destination's width immediately — the desktop clears
   `right_tween`/`right_takeover_content_tween`/`main_takeover_tween` with the
   comment "restore THAT chat's panel state (per-session open flags; **snap,
   no tween** — the panels belong to the destination chat)",
   shell.rs:1820-1845). The pane's OWN open/close/takeover glides (same chat)
   are untouched.

**States:** the matrix's eight rows above ARE the states. Reduced motion and
phone collapse to the snap row (`dockReduced` already includes phone,
`chat-page.tsx:443`).

---

## 3. Pure logic to port

From the research's "Pure logic to port" items 4 and 6:

- **Handoff arming with a synchronous pane width** — the arming predicate is
  already ported (`PanelHandoff.sample`, composer-dock.ts:262-289); the HOST
  wiring needs the computed width. Mirror tests (already in
  `tests/composer-dock.test.ts` per ticket 15; add a host-level case):
  `panel_handoff_hides_background_during_geometry_switch_in_both_sidebar_states`
  (composer_dock.rs:456), `panel_return_sizes_while_hidden_and_finishes_controls_with_input`
  (:502), `ordinary_resizing_and_same_column_navigation_do_not_fade`
  (panel_handoff.rs:92).
- **`transcript_width` wiring**: mirror
  `panel_exit_retains_source_transcript_width_only_until_handoff_ends`
  (composer_dock.rs:488) — already ported pure; add the host call.

---

## 4. Gaps this ticket closes

S6(d), verbatim:

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
|---|---|---|---|---|
| handoff width input | BROKEN | synchronous `right_now(cx)` (shell.rs:7883-7889, 3796-3802) | measured `columnWidth`, one commit stale (chat-page.tsx:497, 462-481) | feed the computed pane width (`paneWidth` from app-shell scope, or `viewport − sidebar − columnWidth` computed synchronously) into `observePane` |
| composer fade-through | MISSING | opacity 1→0→1 over 0.320 s, geometry at p ≥ 0.22 (panel_handoff.rs:18-22; composer_dock.rs:399-406) | opacity stays 1 (chat-page.tsx:780) | falls out of the row above — the ported `PanelHandoff` already computes it |
| width snap in invisible interval | MISSING | `layout_width` snaps at p ≥ 0.22 (composer_dock.rs:230-234) | width glides 768→narrow, composer overflows/covered ~0.42 s (composer-dock.ts:389-410; app.css:1930-1933) | ditto — handoff progress gates the snap |
| 12/8 px travel | MISSING | 12 docking / 8 undocking, decaying `stage(p, 0.22, 1)` (composer_dock.rs:399-406) | dead code (composer-dock.ts:502-511) | ditto |
| panel_departure fast dissolve | MISSING | dissolve 0→1 over `stage(p, 0, 0.18)` (composer_dock.rs:247, 284-289) | never triggers (composer-dock.ts:448-454 needs pane.progress) | ditto |
| pane column on route change | MATCHES (snap) | snaps — `right_tween = None` on chat switch (shell.rs:1820-1845) | mounts at full width (right-pane.tsx:117-141) | none (the handoff covers it on the desktop) |
| departing transcript width retention | MISSING | `transcript_width` holds the source column until the handoff ends (composer_dock.rs:195-204; shell.rs:7898-7903) | method ported but never called (composer-dock.ts:358-367; no call sites) | call it from the page: fix `.chat-body`'s width to the retained value while `departing` |
| chat→chat transcript swap | DIVERGENT | one entity, synchronous doc swap (shell.rs:5911-5921) | fresh `TranscriptStore` + async load → blank flash (chat-page.tsx:183-192) | keep the previous rows mounted (crossfade or cache-first paint) until the new store's first frame |
| chat→chat pane flag change | INVERTED | snap, no tween (shell.rs:1820-1845) | 200 ms CSS close glide (right-pane.tsx:120) | suppress the transition when the pane key (chatId) changed |
| hero continuity (cases A/D) | BROKEN | see S5 | see S5 | see S5 rows |

(The last row is ticket 35's — listed here because cases A/D cannot be
accepted until it lands, which is why 35 blocks this ticket.)

---

## 5. Do not

- **Do not rewrite the pure dock/handoff port** — `lib/composer-dock.ts` is
  field-for-field with `composer_dock.rs`/`panel_handoff.rs` and its tests are
  the desktop's. This ticket wires the HOST (the width input, the
  `transcriptWidth` call, the retention, the snap suppression). If a pure
  function seems wrong, re-check against the Rust before touching it.
- **Do not change the pane's own open/close/takeover glides** — same-chat pane
  toggles keep the 200 ms resize curve (`.right-pane`, `app.css:1021`) and the
  takeover tween machinery (`right-pane.tsx:86-115`). Only a pane-KEY change
  (chat switch) suppresses the transition.
- **Do not add a new transition** for any case — the matrix is exhaustive
  (A/B/C/D/D'/reversal/reduced). If a case is not in it, it snaps (the
  `ordinary_resizing_and_same_column_navigation_do_not_fade` rule).
- **Do not port `measured_dock_retargets_without_a_first_frame_jump`**
  (composer_dock.rs:527) — a gpui `TestAppContext` harness; its observable
  rule is already covered by `initial_and_reduced_motion_frames_snap`
  (ticket 15 §3).
- **`ROBOCO_MOTION_SCALE` multipliers are desktop-only** — the web keeps
  hard-coded durations.
- **Do not change the hero's paint or its frost branch** — ticket **33**.
- **Do not change the hero's width source or the titlebar island** — ticket
  **34** (this ticket consumes its animated sidebar-now; it does not rework
  it).
- **Do not change the artwork hoisting/readiness/blob identity** — ticket
  **35** (a blocker; the matrix's background handoffs ride its continuity).
- **Do not touch** ticket **39** (the new-chat send path — the canvas-send
  mint/navigation chain that triggers case A is 39's, not this ticket's;
  this ticket consumes the navigation it produces), ticket **42** (the icon
  family fixes), or ticket **48** (the active-background resolution
  semantics, including the default).
- **Do not keep the composer at opacity 1 "for safety"** during the handoff —
  the fade-through IS the desktop behavior; the invisible interval is what
  makes the geometry snap invisible.

---

## 6. Acceptance

- [ ] Case B arms: new-thread → chat-with-stored-open-pane triggers the
      0.320 s handoff (the composer's inline opacity leaves 1: 0 by p≈0.18,
      back to 1 by p=1 — frame-sampled or DOM-logged).
- [ ] The geometry switch is invisible: the composer's width snaps at
      p ≥ 0.22 while its opacity is ~0 — no 768 px composer overflowing the
      narrowed column (~0.42 s covered overflow gone).
- [ ] The 12 px travel lands on docking (and 8 px on D' undocking), decaying
      over `stage(p, 0.22, 1)` — the wrapper's transform shows the offset.
- [ ] `panel_departure`: the hero's dissolve ramps 0→1 over `stage(p, 0, 0.18)`
      of the 0.320 s clock (a FAST kill, not the 0.420 channel window).
- [ ] Ordinary resizing and same-column chat→chat navigation do NOT fade
      (`ordinary_resizing_and_same_column_navigation_do_not_fade` —
      host-level case in `tests/composer-dock.test.ts`).
- [ ] D': the departing transcript holds the SOURCE column width until the
      handoff ends (`.chat-body`'s width pinned to the retained value; no
      reflow-while-fading exit flash), covered by the existing veil.
- [ ] Chat → chat (same column): the transcript swaps without a blank frame —
      the previous rows stay mounted until the new store's first frame
      (cache-first or crossfade).
- [ ] Chat → chat where the destination's pane flags differ: the pane SNAPS
      (no 200 ms glide) — verified computed (no transition on that commit).
- [ ] Reversal mid-flight: position+velocity preserved (no restart from
      zero); the handoff preserves its current opacity.
- [ ] Reduced motion: every case snaps to its settled state (handoff
      disabled, opacity 1).
- [ ] Unit tests: the three host-level handoff cases +
      `panel_exit_retains_source_transcript_width_only_until_handoff_ends` →
      `web/packages/app/tests/composer-dock.test.ts`.
- [ ] Screenshot pair, desktop vs web, states: mid-handoff of case B
      (~p 0.2 — composer invisible, pane snapped in); case B settled; case A
      mid-glide; case D' mid-return (retained transcript + veil); chat→chat
      content swap frame; reduced-motion settled state.
- [ ] `pnpm -r build` green; package vitest green.
- [ ] No new literal hex/px where a `--rb-*` token exists.

## Comments

(empty; appended during implementation)
