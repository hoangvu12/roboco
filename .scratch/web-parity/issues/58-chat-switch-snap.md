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

- [ ] Switch between two chats with open tool groups + mid-transcript scroll offsets: content lands in place instantly — no visible scroll-down animation, no groups animating closed, no shimmer flash.
- [ ] Unit tests: arrival predicate + no-spring-on-arrival + existing 40 suites green.
- [ ] Live streaming in the CURRENT chat still animates as before (manual/code check).
- [ ] `pnpm -r build` + `pnpm test` green.

## Comments

(User report 2026-09-20 #6. Ticket 40's Comments record the landed baseline machinery this builds on.)
