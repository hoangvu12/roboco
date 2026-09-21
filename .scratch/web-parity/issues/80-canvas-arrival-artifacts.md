# 80 — Canvas-origin arrival: no visible settle or fold replay on new-chat → chat

**What to build:** Navigating from the new-chat canvas to an existing chat visibly replays the chat's settle: the message list scrolls/glides into place and old tool calls render expanded then visibly close. Chat→chat navigation does not show either artifact. Close the gap by (a) never seeding stale `streaming` status from the offline cache and (b) treating the authoritative live reset that replaces a cache seed as a fresh arrival (re-arm the chat arrival window), so the settle cascade is atomic exactly like a chat→chat switch.

**Blocked by:** None (coordinate: ticket 65's integration owns `chat-page.tsx`/`dock-glide.ts`/`composer-dock.ts`/hero files — this ticket must stay out of those files).

**Status:** done

## 1. Evidence (web-parity/followup @ c81dd2fe)

**Artifact (a) — visible scrolling.** On new-chat→chat there is no previously-painted surface to retain (`lastPaintedTranscriptRef.current` is null on the canvas, `chat-page.tsx:1086-1094`), so `TranscriptSurface` mounts blank on the unloaded store and the first data frame lands as a LATER commit, mid-dock-fade (the transcript channel fades in 0.2–0.65 of the 420ms glide, composer-dock.ts:194-204). The restore effect arms the arrival window at that loaded commit (transcript.tsx:1091-1120), but the window falls 50ms after the seeded rows' measurement quiesce (`ARRIVAL_QUIESCE_MS`, lib/chat-arrival.ts:32-35). The live reset's engine roundtrip typically lands after that: its content growth then arms the stick spring (`stick-controller.ts:181-193` kick → `#stepSpring` eased per-frame writes, :556-621) — the visible glide. Chat→chat never shows this because swap-retention makes the destination's first paint the loaded mount commit and the whole cascade fits inside the armed window (ticket 58's design assumption, `.scratch/web-parity/issues/58-chat-switch-snap.md:67-73` — which the canvas route violates).

**Artifact (b) — old tool calls visibly opening.** The offline cache saves raw entries as-is (transcript-store.ts:568-579), so a chat left mid-run seeds with `status: "streaming"`. `rowsForEntry` sets `autoOpen = streaming && groupLastPartIx === lastPartIx` (lib/transcript.ts:1499, 1547) and unresolved thoughts default open (tool-group-geometry.ts:257) — the seed frame renders the last tool group EXPANDED on the first visible frame. When the authoritative reset settles the entry, `open` flips and `noteRendered`'s rendered-open flip seeds a 140ms close tween, gated ONLY by the time-boxed arrival window (tool-motion.ts:719-735) — which has typically already fallen. Result: old tool calls visibly open, then visibly close.

## 2. Spec

| Change | File | Requirement |
| --- | --- | --- |
| (a) Stale-streaming downgrade | `web/packages/app/src/state/transcript-store.ts` constructor cache-load path (:376-388) | Map seeded entries with `status: "streaming"` → `"aborted"` before `seedEntries` (a previous session's mid-run save cannot still be streaming; an interrupted run is aborted — the `MessageStatus` vocabulary is `streaming \| complete \| aborted`). Apply ONLY on the offline-cache load path; the subagent-snapshot `seedEntries` callers stay untouched. The cache itself still saves raw entries (truthful); the downgrade is presentation-only. |
| (b) Reset re-arm | `web/packages/app/src/components/transcript.tsx` baseline-consume layout effect (:403-415) | When consuming a baseline with provenance `"reset"`, re-arm `chatArrival.arm(performance.now())` — the authoritative reset IS the arrival (ticket 58's atomicity extended to the seed→reset handoff). Seed baselines do NOT re-arm (the restore effect already arms at the loaded commit). Late measurement growth then hard-writes pre-paint instead of arming the spring, and `noteRendered` open-flips stay gated. |
| No dock changes | — | Do not touch `chat-page.tsx`, `dock-glide.ts`, `composer-dock.ts`, or hero files (ticket 65 owns them concurrently). The dock choreography stays as-is. |
| Chat→chat unchanged | — | Swap-retention path and its behavior must not regress. |

## 3. Tests

- Extend the mounted replay harness (`tests/transcript-replay-integration.test.ts` idiom — controllable client, fake cache if present, jsdom stubs):
  - A cache seed carrying `status: "streaming"` renders its last tool group closed (no `autoOpen`), and the snapshot's `streaming` flag is false.
  - A reset frame replacing a seed re-arms the arrival window (probe `chatArrival.isArrival` or the stick controller's spring behavior: no spring steps for post-reset growth).
  - A reset landing long after the seed's quiesce window produces no `noteRendered` fold tween (no automatic fold transition emitted).
- Existing suites stay green; chat→chat replay coverage in the same harness must not change behavior.

## 4. Acceptance

- [ ] New-chat → existing chat shows no visible scroll glide and no old tool calls opening/closing (user re-test on the follow-up build).
- [ ] Chat→chat navigation is byte-for-byte the same behavior as before (existing tests).
- [ ] Tests above pass; full `pnpm test` green; `pnpm -r build` from `web/` green.

## Comments

Created 2026-09-20 evening from the live round-2 report ("when moving from the new chat page to an existing chat, the scrolling and the opening old tool calls still seem to happen, it doesnt happen when moving from chat to chat"). Root causes are the blank-mount asymmetry (no retention on the canvas route) plus the stale streaming cache seed; the fixes target the two code-proven triggers without touching the dock choreography.

## Comments

**2026-09-21 � implemented and merged to `web-parity/followup`.** `fix(web): ticket 80 canvas arrival artifacts`: (a) `downgradeStaleStreaming` in `state/transcript-store.ts` maps `status: "streaming"` ? `"aborted"` on the offline-cache load path only (subagent `seedEntries` callers untouched; the cache still saves raw entries); (b) the transcript surface's baseline-consume layout effect re-arms `chatArrival` on every `"reset"`-provenance baseline � the authoritative reset IS an arrival, so the seed?reset handoff settles atomically (hard pre-paint end-writes while pinned, `noteRendered` flips gated) exactly like a chat?chat mount commit. No dock/hero files touched (ticket 65 owns those). Tests: two new mounted cases in `tests/transcript-replay-integration.test.ts` � the stale-streaming seed renders closed (store-level `aborted` downgrade + all rendered-open records false, also across the follow-up reset), and a reset landing after the seed's window fell hard-writes the grown end in one commit (verified to FAIL without the re-arm by stashing the fix). Full suite **1512/1512** (92 files) and `pnpm -r build` green. User re-test on the follow-up build pending.
