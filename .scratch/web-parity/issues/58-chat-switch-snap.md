# 58 — Chat switch: snap, don't choreograph (no scroll animation, no fold tweens, no shimmer on arrival)

**What to build:** Switching between chats arrives ATOMICALLY like the desktop: the transcript lands at its restored scroll offset instantly (hard-follow, no spring), tool groups render in their final open/closed state with no reveal tween or shimmer replay, and nothing animates "scrolling down". Live-streaming keeps its existing motion — only the ARRIVAL on a chat switch de-choreographs.

**Blocked by:** None — can start immediately (40's store logic is landed and correct; this ticket changes what runs on arrival).

**Status:** ready-for-agent

**Research:** `../research-2026-09-20/performance-audit.md` — §S2 mechanism (what animates on every switch, with the desktop's side-by-side), fix plan P2. 

**Desktop reference:** `crates/ui/src/state.rs:1740-1792` (atomic select — restore applies in one frame), `transcript.rs:4059-4072` (veil attach gate — the reveal baseline machinery the web already ports), `shell.rs:1837-1862` (pane-key snap on chat change), `composer.rs:5849-5874`.

## 1. Context a fresh session needs

- User: "there is a weird animation happen when i switch between chats, like it scrolling down, and try to close the opened group tabs... the web happen everytime, like it try to animate the grouped tool calls for some reason. dont do that, its not needed to animate scrolling down or anything when switching chats." The desktop does this "once in a while" at most; the web EVERY time.
- The per-switch animation chain (research §S2, all file:line): the `key={active.docId}` remount (transcript.tsx:210) rebuilds the virtualizer from estimates; a 12-frame restore poll + per-commit spring kicks (transcript.tsx:929-966, :1072-1085 → stick-controller.ts:520-585) animate the scroll down to the restored offset; per-mount fold state + the `noteRendered` close tween (tool-motion.ts:513-526) animates groups closed; the shimmer restarts (:569-571).
- Ticket 40 fixed the resubscribe wipe/baseline re-arm (groups no longer spontaneously OPEN — landed). The REMAINING problem is deliberate arrival motion: restore polling, fold tweens, shimmer — which the desktop does not run on a chat switch.

## 2. Spec

1. **Scroll**: on chat switch (chatId change with a loaded transcript), jump directly to the restored offset — hard `scrollTop` assignment, no spring, no poll frames (replace the 12-frame restore poll with: compute offset from estimates, assign once; a single follow-up correction after first measure is allowed if estimates were off — assign, don't animate).
2. **Folds**: tool groups mount in their final open/closed state — the `noteRendered` close tween does not run on the arrival mount (gate it on "same chat" renders, i.e. only animate folds that change while the chat is live).
3. **Shimmer**: no shimmer restart on arrival — the skeleton shimmer only runs while streaming/loading content, not on the first paint of an already-loaded transcript.
4. **Streaming unchanged**: everything ticket 40 landed (reveal baseline, reservation, own-turn runway, live-delta motion) keeps working during live runs — this ticket ONLY de-choreographs the arrival path (chatId transition).
5. If the research identifies an explicit "arrival" flag/site, centralize the gate there (e.g. a `justArrived` ref cleared after first commit) — one predicate, tested.

## 3. Pure logic to port

- The arrival predicate (chatId change → suppress restore animation, fold tweens, shimmer; cleared after the first settle) — extract + unit test.
- Desktop mirror tests: `tool_groups_stay_closed_after_rapid_new_chat_navigation` (already landed via 40 — keep green) plus a NEW test named after this behavior: arrival applies the restored offset in ONE assignment and schedules no spring (assert the spring queue is empty on arrival).

## 4. Gaps this ticket closes

| item | kind | desktop value (file:line) | web value (file:line) | fix |
| --- | --- | --- | --- | --- |
| Scroll on chat switch | behavior | atomic restore, one frame (state.rs:1740-1792) | 12-frame poll + spring (transcript.tsx:929-966, stick-controller.ts:520-585) | §2.1 |
| Fold state on arrival | behavior | final state, no tween (transcript.rs:4059-4072 baseline gate) | noteRendered close tween on mount (tool-motion.ts:513-526) | §2.2 |
| Shimmer on arrival | behavior | none for loaded transcripts | restarts (tool-motion.ts:569-571) | §2.3 |

## 5. Do not

- Do not touch live-streaming motion (40's reveal/ reservation/ runway behavior during runs).
- Do not change the virtualizer, row components, or store reset semantics (40 owns).
- Do not remove the jump-pill or reading-position machinery (41/40 own).
- Do not touch the desktop Rust.

## 6. Acceptance

- [ ] Switch between two chats with open tool groups + mid-transcript scroll offsets: content lands in place instantly — no visible scroll-down animation, no groups animating closed, no shimmer flash. *(Manual item — screenshot pairs waived per the worktree rule; see the implementer note.)*
- [x] Unit tests: arrival predicate + no-spring-on-arrival + existing 40 suites green.
- [x] Live streaming in the CURRENT chat still animates as before (manual/code check). *(Code check: every gate consults the arrival window, which is closed for all same-chat frames — ticket 40's reveal baseline, reservation, runway, and per-commit stepper run exactly as landed.)*
- [x] `pnpm -r build` + `pnpm test` green.

## Comments

(User report 2026-09-20 #6. Ticket 40's Comments record the landed baseline machinery this builds on.)

### Implementer note (2026-09-20)

**The ONE predicate.** `ChatArrivalWindow` (`lib/chat-arrival.ts`, pure — a
`now` parameter everywhere so the tests drive the timeline): `arm(now)` at
the surface's first loaded commit, `noteMeasure(now)` per ResizeObserver
height batch, `isArrival(now)` true while armed AND (no measure yet, or
within `ARRIVAL_QUIESCE_MS` = 50 of the last one) AND inside
`ARRIVAL_HARD_CAP_MS` = 500. The surface creates one instance per mount
(`useMemo` keyed on the store) and hands the SAME instance to the scroller
and `ToolGroupMotionStore`. The surface remounts per doc
(`key={active.docId}`) and the outlet hands the view the new store only
once its first frame has landed — so the first loaded commit IS the switch
arrival (chat-page.tsx:812-855's retain-paint swap). The arm lives in the
restore layout effect (declaration order: attach → own-send → restore), so
it runs before the per-commit kick effect on the same commit and before
the surface's passive baseline `sync`.

**§2.1 scroll.** The 12-frame rAF restore poll is REPLACED (the spec's
"replace", not the "can stay" branch): the restore effect assigns from THIS
commit's estimate-based prefix sums synchronously — `restoreViewport` /
`snapToEnd` are already hard writes — and the post-measure correction is
the landed per-commit anchor preserve (`writePreserving`, a write, never a
tween; the desktop's `viewport_finalize` follow-up). No new correction
machinery. The spring glide is dead at the source: `StickController.kick`
gains an arrival branch (pinned + window armed → write `maxScroll()`
directly, spring reset, NO `#schedule` — the spring queue stays empty),
mirroring `snapToEnd`'s shape; `shouldAnchorLiveStream`'s live-stream
hard-anchor path is untouched, so live tail-follow never changes. The gate
is pinned-only by design: the anchored restore path leaves `pinned=false`,
so its kicks pass through to the (no-op for a released runway) stepper,
and the anchor preserve owns the corrections.

**§2.2 folds.** `noteRendered` seeds its close tween only when
`isArrival(now)` is false — a mid-arrival rendered-open flip (the
streaming-status settle/desync flap behind "try to close the opened group
tabs") records the endpoint with NO tween; a same-chat flip after the
window closes animates exactly as before. User clicks are unaffected (a
click's own `toggledAt`/`from` come from `toggleGroupFold`, not this path).

**§2.3 shimmer.** `sync` arms `shimmerStartedAt` only outside the window:
the baseline frame and the settle-cascade syncs leave it null for an
already-loaded transcript's first paint; the next live sync (real streaming
content) arms it. Note the visible `.tool-shimmer` CSS class keys on
`active = collapses && autoOpen` (ticket 19/40 territory, untouched per §5
"do not change row components") — the arrival fix lands in the store
contract the ticket's §3 names (`tool-motion.ts`'s shimmer epoch), where
the restart mid-cycle was recorded.

**Subagent (alignTop) surfaces** get the same per-surface window and gates;
the desktop's override tabs also land at the end (`land_end_pending`,
transcript.rs:2852, :4239), so the arrival's hard write matches its
destination semantics.

**Tests** (`tests/chat-arrival.test.ts`, 7 new): the predicate timeline
(arm/cap, measure quiesce + re-extension, inert measures, superseding arm);
`chat_switch_arrival_restores_in_one_assignment_and_schedules_no_spring`
(the desktop-mirror name: `snapToEnd` → per-commit kicks through grown
content write the new end directly with `requestAnimationFrame` never
called; after the window closes a kick schedules the spring) plus the
anchored one-assignment restore; the fold-tween/shimmer gates (arrival
flap records no tween + shimmer stays null; post-window flip seeds the
tween and a live sync arms the shimmer); a window-less store keeps ticket
40's semantics byte-identical. All ticket 40 suites (incl.
`tool_groups_stay_closed_after_rapid_new_chat_navigation`) green and
unmodified.

**Screenshot pairs waived** per the worktree rule (verification = build +
tests only; no dev server / browser / `cargo run` was started). The visual
acceptance item stays open for a human pass.

**Flake note:** one full-suite run failed `registry.test.ts >
reconnectBackoffDoublesAndResetsAfterALongLivedConnection` under
first-run load (2357ms vs 532ms isolated); it passes in isolation and on
the immediate full re-run, and this change never touches the engine
registry — same load-flake class ticket 40's note records on this machine.

**Verification:** `pnpm -r build` green (proto, engine-client, app
`tsc --noEmit` + vite); `pnpm test` in `web/packages/app` green — 82 files
/ 1300 tests (1293 at base + 7 new). No CSS, string, or row-component
changes; no new hex/px (the two new constants are named ms durations in
`lib/chat-arrival.ts`).
