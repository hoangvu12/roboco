# Desktop right-pane close snap — reproduction evidence (ticket 73)

Date: 2026-09-20. Baseline: `37c354ff` (worktree HEAD `7389969b` adds docs only;
`git diff 37c354ff..HEAD` over every cited source file is empty, so all line
citations below are exact at HEAD).

**Method and its limit.** No running app, browser, dev server or native build
was authorized for this ticket (ticket §5: no `pnpm dev`, `cargo run`,
`web_smoke`, browsers, or long-running processes). This artifact is therefore a
complete **source-level** diagnosis: every candidate snap channel is traced
through the web and desktop source and classified. Nothing runtime is claimed
as observed; every runtime capture in the ticket's matrix is recorded as
**PENDING** below with the exact measurement it requires. No production code,
tests, or application state were changed.

The user report under diagnosis: "the right sidebar closing feels weird, like
its snapping, not sliding" — device width, surface, takeover state and exact
action unidentified. Static inspection at `37c354ff` has not established a bug
in ordinary same-chat desktop-width close, and none is established here either.

## 1. Reproduction matrix (ticket §2.5) — runtime status

All rows are **PENDING — no authorized runtime**. Each row names the exact
measurement that would settle it. Every capture must also record: build
identity (commit), viewport, browser/device, pane surface, owning chatId,
expanded state, `prefers-reduced-motion` state, and the close entry point
(titlebar pane toggle vs shortcut), plus a matched native desktop recording of
the same action for comparison.

| # | Scenario (ticket §2.5/§2.2) | Status | Exact measurement required |
| --- | --- | --- | --- |
| M1 | Normal desktop pane, same chat: close Changes and Terminal via titlebar toggle; ≥769px; sidebar open and closed | **PENDING** | rAF-sampled `getBoundingClientRect().width` of `.right-pane` (outer) and `.right-pane-inner` (inner) across the close; assert a continuous 200ms ease-out descent open→0 on the outer, a constant inner width at the open endpoint, `data-pane-snap="0"` for the whole close (MutationObserver on the aside), `aria-hidden` flipping at close start, and `.right-pane-body` unmounting at ~200ms, not frame 0. Matched native recording of `toggle_right_pane`. |
| M2 | Files / file tab, same close action and viewport as M1 | **PENDING** | Same width sampling as M1, plus a MutationObserver timestamp for the `.right-pane-body` content removal relative to the close click. Expected by design (both clients): content gone at frame 0 while the column still glides — see §3 channel B. The measurement exists to separate that intentional blanking from column motion, not to pass/fail it. |
| M3 | Expanded desktop pane: close out of takeover; then chat switch with destination pane open and closed; rapid navigation | **PENDING** | Per-frame widths of `.right-pane`, `.right-pane-inner`, and `.main` plus `shell-pane-gliding` class presence during expanded close (conversation must hold its larger width while the pane glides). On chat switch: MutationObserver confirming `data-pane-snap="1"` for exactly one commit and the destination width landing immediately with no trailing transition (intentional snap — §3 channel F). |
| M4 | Rapid reversal: close then reopen inside the 200ms window | **PENDING** | Continuous width sampling across the reversal: the reopen transition must retarget from the painted intermediate width (no jump to an endpoint), and no stale glide timer may empty or re-hold the pane after settle (source expects the `usePaneGlide` cleanup to clear it — §3 channel G). |
| M5 | Reduced motion on; seam drag immediately before close | **PENDING** | With `prefers-reduced-motion: reduce`, close must snap instantly by design (app.css:13203-13210) — record the preference state so the snap is not misread as a regression. After a seam drag, verify `data-rb-resizing` is absent from `<html>` before the close begins (MutationObserver on the attribute; source expects the pointerup/pointercancel cleanup to remove it — §3 channel E). |
| M6 | Frame-time continuity during M1 | **PENDING** | A performance trace (long tasks / frame timestamps) across the close. The outer width transition is layout-affecting (not compositor-accelerated) and the conversation re-lays per frame; a main-thread stall would read as a jump even with correct geometry. This is the one channel source inspection cannot clear. |

## 2. Source paths under diagnosis

Web (all at `37c354ff` line positions, verified at HEAD):

- `web/packages/app/src/components/right-pane.tsx`
  - 73-89 — Files/File content suppressed for the whole close (`filesWhileClosing`).
  - 129-146 — `data-pane-snap`: set only when the owning `chatId` changed this commit; dropped on the next commit.
  - 143-153 — the aside stays mounted at width 0 while closed; inline width `calc(openWidth|0 + var(--rb-pane-edge-offset, 0px))`.
  - 98-127, 215-257 — `usePaneGlide`: open/close arms `held = max(both endpoints)` for 200ms with `tween: null`; takeover arms the per-frame content tween; layout-effect arming; `setTimeout` cleanup on re-arm.
- `web/packages/app/src/components/app-shell.tsx`
  - 220-221 — `paneOpenWidth` (open endpoint) vs `paneWidth` (instantaneous target, 0 when closed).
  - 477-483, 839-855 — one shared `usePaneGlide`; `useTakeoverStableWidth` holds the conversation's larger width only across `expanded` flips.
  - 590-595, 624-626 — `--rb-pane-band` from `titlebarPaneBandWidth(...)`; the titlebar band stays mounted while shut.
  - 760-781 — pane seam unmounted while `glide.gliding`.
- `web/packages/app/src/styles/app.css`
  - 1156-1169 — `.right-pane`: `overflow: hidden; transition: width var(--rb-motion-resize) var(--rb-ease-ease-out)`.
  - 1181-1183 — `[data-pane-snap="1"] { transition: none }`.
  - 1185-1194 — `.right-pane-inner`: absolute, right-anchored, full height.
  - 650-681 — `.titlebar-pane-band`: width transitions on the same resize spec; inner right-anchored at the open width.
  - 1288-1295 — `:root[data-rb-resizing]` kills the pane/band/seam transitions during a held drag.
  - 13203-13210 — reduced-motion media query snaps the same elements.
- `web/packages/app/src/state/layout.ts`:171-179 — `evalWidthTween`, the eased 200ms lerp on the shared resize curve; `web/packages/theme/src/generated/artifact.json`:6548-6552 — `resize = 200ms easeOut`.
- `web/packages/app/src/components/pane-seam.tsx`:148-154 — `data-rb-resizing` set on drag start, removed in the effect cleanup (pointerup/pointercancel both route through `onUp`).

Desktop (`crates/ui/src/shell.rs`):

- 1976-2010 — `toggle_right_pane`: `from = eval_tween(right_tween, …)` (reversal-safe), resets takeover on close, suspends file images, arms `right_tween`, leaves `right_takeover_content_tween = None`, arms `main_takeover_tween` only when `was_expanded`.
- 1837-1862 — chat switch: "snap, no tween — the panels belong to the destination chat"; clears `right_tween`, `right_takeover_content_tween`, `main_takeover_tween`, `terminal_tween`.
- 3777-3791 — `eval_tween`: eased 200ms lerp; reduced motion returns the target.
- 177-187 — `stable_panel_content_width` / `right_panel_content_width`: content holds `from.max(to)` during a transition; takeover tracks instead.
- 3850-3879 — `right_pane_container`: clipped outer width riding the tween, right-anchored inner at the held content width.
- 6466-6476 — `render_right_pane`: Files/File render `gpui::Empty` while closed ("keep it unmounted throughout the closing animation after suspending its resources"); 2169-2173 — `suspend_file_images`.
- 8442-8469 — test `right_panel_content_keeps_the_larger_width_only_during_transition` pins the held-width contract.

## 3. Channel-by-channel classification

### A. Pane outer width (the column itself)

- **Web:** same-chat toggle changes only the inline `width` (right-pane.tsx:150); the CSS transition (app.css:1168) animates it 200ms ease-out; the aside never unmounts (app.css:1164-1165 note; right-pane.tsx:143-153). `data-pane-snap` stays `"0"` because `chatId` is unchanged (right-pane.tsx:137-141).
- **Desktop:** `toggle_right_pane` arms `right_tween(from=current painted width, to=target)` and `right_pane_container` paints `eval_tween` per frame (shell.rs:1978,1994,3868).
- **Verdict: source-cleared.** Equivalent duration and curve from the shared motion catalog; equivalent mounted-at-0 model. Whether the painted result is continuous is runtime-only (M1, M6).

### B. Files/File content unmount (the designed blank)

- **Web:** `filesWhileClosing` nulls the content for the entire close while the column still glides (right-pane.tsx:70-89); the body itself stays mounted via `glide.mounted` (right-pane.tsx:180,247-256).
- **Desktop:** identical — `render_right_pane` returns `gpui::Empty` for Files/File the moment the pane is closed, mid-tween included, after `suspend_file_images` (shell.rs:6469-6476,1988,2169-2173).
- **Verdict: CONFIRMED as intentional desktop parity (gap 73b), not a regression.** This is the one channel that visibly empties the pane on frame 0 of a close and is the strongest candidate for what the report calls a "snap" — but only if the reporter's active surface was Files or a file tab. M2 would confirm that attribution. Changing the visible resource-retention policy is a product decision this ticket does not authorize.

### C. Titlebar band (pane tabs strip)

- **Web:** band width is `var(--rb-pane-band)` computed from the instantaneous `paneWidth` target (app-shell.tsx:590-595); the band's own `transition: width` on the same 200ms spec glides it (app.css:650-657); its inner holds the open width, right-anchored (app.css:664-681); the band stays mounted while shut precisely so it glides away instead of blinking (app-shell.tsx:618-626).
- **Desktop:** the strip is sized from `right_now` — the *sampled* animated width — `(right_now - pr) - 28` (app.css:640-644 documents the port).
- **Verdict: source-cleared with a noted mechanistic difference.** Web sets the target and lets CSS transition it; desktop samples the tween each frame. Same duration/curve, so identical trajectories when transitions run uninterrupted; divergence is only possible under suppression (drag flag, reduced motion) or a stalled frame — covered by M5/M6.

### D. Conversation reflow

- **Web:** `.main` is the flex remainder; on an ordinary close it re-lays per frame as the pane column shrinks. `useTakeoverStableWidth` holds the conversation's larger width only across `expanded` flips (app-shell.tsx:483,839-852) — including closing *out of* takeover, since `takeover = hasPane && open && expanded` flips false.
- **Desktop:** identical split — `main_takeover_tween` arms only when `was_expanded` (shell.rs:1996-2001); ordinary close recomputes `conversation_width(viewport, sidebar, right_now)` per frame (shell.rs:189-191).
- **Verdict: source-cleared.** Both clients gradually re-lay the conversation on an ordinary close and both hold it only across takeover. Any perceived jump here would be a frame stall (channel E/F runtime rows), not a geometry difference.

### E. Transition suppression flags (`data-pane-snap`, `data-rb-resizing`, reduced motion)

- `data-pane-snap` cannot fire on a same-chat toggle: it compares previous vs current `chatId` (right-pane.tsx:137-141) and self-clears on the next commit; on an actual owner switch the snap is intentional on both clients (shell.rs:1837-1862). **Source-cleared for same-chat close.**
- `data-rb-resizing` is set on seam drag start and removed in the effect cleanup, which pointerup and pointercancel both reach via `onUp` (pane-seam.tsx:134-156). Source shows no stuck-flag path; runtime confirmation that the flag is gone before a post-drag close is M5.
- Reduced motion snaps by design on both clients (app.css:13203-13210; shell.rs:3781-3783). It must be recorded, not treated as a regression (M5).
- **Verdict: source-cleared; runtime verification pending (M5).**

### F. Frame stall / dropped frames

- The ordinary close is a layout-affecting CSS width transition with per-frame conversation reflow; neither is compositor-accelerated, and no rAF work runs for an ordinary close (the takeover content tween is the only rAF loop, right-pane.tsx:98-127, and it does not run on open/close). A main-thread stall would still present as a snap with all geometry correct.
- **Verdict: NOT source-decidable. Pending M6.** No frame-continuity claim is made in either direction.

### G. Rapid reversal and stale timers

- Close→reopen inside 200ms: the `usePaneGlide` effect cleanup clears the pending settle timer and re-arms with `held = max(was, now)` (right-pane.tsx:227-245); the outer CSS transition retargets from the painted intermediate value by construction. Desktop is explicitly reversal-safe (`from = eval_tween(…)`, shell.rs:1977-1978).
- **Verdict: source-cleared; painted continuity pending (M4).**

### Excluded from evidence

The expanded **phone** close width snap is ticket 72's proven bug (research
§5.72: the phone close drops the 100vw class before the slide ends) and is
excluded from this desktop diagnosis per ticket §1/§5. It is not evidence of a
desktop regression.

## 4. Confirmed vs rejected hypotheses

**Confirmed (source-level):**

- H1 — Files/File close blanks its content on frame 0 while the column still
  glides: **CONFIRMED as designed behavior in both clients** (channel B;
  gap 73b). This is existing product behavior, not a regression; a policy
  change needs explicit product scope.

**Rejected at source level as causes of a same-chat desktop close snap**
(runtime evidence could resurrect them only via a mechanism outside the
reviewed paths):

- H2 — `data-pane-snap` kills the transition on an ordinary same-chat toggle:
  rejected; the key cannot change on a toggle (channel E).
- H3 — the column unmounts at close start and blinks out: rejected; it stays
  mounted at width 0 and the body rides `glide.mounted` for the 200ms
  (channels A, B).
- H4 — the inner content reflows/squeezes through intermediate widths:
  rejected; open/close holds the larger endpoint, matching the desktop test
  at shell.rs:8442 (channel A).
- H5 — the titlebar band snaps independently of the column: rejected at
  source; same spec, held inner, mounted while shut (channel C).
- H6 — closing out of takeover snaps the pane content: rejected; a close is
  never a `takeoverFlip`, so the held endpoint applies, matching
  `right_takeover_content_tween = None` on close (channels A, D).
- H7 — the phone expanded-close snap explains the report at desktop width:
  rejected as evidence; that is ticket 72's phone-only bug.

**Not decidable from source (pending runtime):**

- H8 — a main-thread frame stall during close presents as a snap (M6).
- H9 — a stuck `data-rb-resizing` or an unrecorded reduced-motion preference
  suppressed the transition in the reporter's session (M5).
- H10 — rapid reversal or a chat switch mid-close left a stale hold or a
  lingering suppression in the reporter's session (M3, M4).
- H11 — the reporter's surface was Files/File, making H1 the observed
  "snap" (M2).

## 5. Untested states

Every runtime state in the ticket's matrix is untested: M1 (ordinary
Changes/Terminal close, sidebar open/closed), M2 (Files/File close), M3
(takeover close, chat switch, rapid navigation), M4 (reversal), M5 (reduced
motion, post-drag flag state), M6 (frame timing). No untested case is
represented as passed; the source-level verdicts above classify *mechanisms*,
not observed behavior.

## 6. Minimal follow-up recommendation

1. **One bounded capture session, no code change.** With an authorized running
   web app at desktop width plus the native desktop app, run M1-M6 exactly as
   specified in §1 (rAF width sampling + MutationObservers + a performance
   trace + matched native recording). This is a coordinator/user capture task;
   it needs no product decision and no new code.
2. **Branch on the outcome:**
   - If M1 shows a continuous 200ms descent and the only blanking is M2's
     Files/File case: no regression exists; record the report as explained by
     the designed Files resource policy (H1) and route the "keep a closing
     visual" question to a product decision (gap 73b). Close ticket 73 with
     that classification.
   - If M1/M6 isolates a discontinuity on a measured channel (outer width,
     inner layout, band, or frame time): open a narrowly scoped implementation
     ticket against that channel only, with the trace attached. Do not
     pre-authorize CSS/timing changes from this artifact.
3. **Do not** remove `data-pane-snap`, force navigations to animate, change
   the Files resource policy, or alter timings as a side effect of this
   diagnosis (ticket §5).
