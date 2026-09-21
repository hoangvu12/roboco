# 81 — Live-seed streaming: entering a running chat shows the open tail immediately

**What to build:** Switching to a chat whose run is STILL streaming should match the desktop's state-preserved switch: the last tool call renders OPEN with its state preserved, no animation. The web showed it CLOSED for ~one live roundtrip and then visibly re-opened, because ticket 80's stale-streaming downgrade is unconditional — the offline cache saves mid-run entries every ~300 ms, so a LIVE run's seed also gets `streaming → aborted`. Stamp each cache save with WHEN it happened and keep a seconds-old seed's `status: "streaming"` verbatim (it is a live mid-run snapshot); only old or unstamped saves downgrade as ticket 80's dead-session leftovers.

**Blocked by:** None (supersedes the unconditional half of ticket 80's (a) in `state/transcript-store.ts`; shares no files with 65's hero/dock lane).

**Status:** done

## 1. Evidence (web-parity/followup @ f8da73ff + round-3 report)

**The report.** Desktop switches to a currently-running chat with the last tool call OPEN, state preserved, no animation; the web renders it CLOSED ~1 second, then it opens. The ~1s matches the live frame roundtrip (cache seed paints immediately; the authoritative reset replaces it later).

**The mechanism.** The offline cache re-saves on a 300 ms debounce while frames land (`#scheduleCacheSave`, transcript-store.ts), so a LIVE run's save is seconds old and still carries `status: "streaming"` on the tail entry. Ticket 80's `downgradeStaleStreaming` runs on EVERY cache seed (the constructor's load path), so the live seed's tail is rewritten `"aborted"` → `rowsForEntry` drops `autoOpen` (lib/transcript.ts:1611) → the tail group renders closed; the live reset then restores `"streaming"` → `noteRendered` open-flip → the visible closed→open transition. Ticket 80's own rationale ("a previous session's save cannot still be streaming") is only true for OLD saves — a save from the current, still-running session is exactly the desktop's live-doc read.

## 2. Spec

| Change | File | Requirement |
| --- | --- | --- |
| Seed carries its save time | `web/packages/app/src/state/transcript-store.ts` | `TranscriptCache.load()` returns a `TranscriptSeed { entries, savedAtMs }` (0 = unknown). `LIVE_SEED_STREAMING_MS` (300_000, exported): the seed is LIVE when `savedAtMs > 0 && Date.now() - savedAtMs <= LIVE_SEED_STREAMING_MS`. The constructor seeds LIVE entries verbatim; stale/unstamped seeds keep ticket 80's `downgradeStaleStreaming` map. The constant stays per-entry and the branch stays at the call site. The cache still SAVES raw entries (truthful); nothing else in the store changes. |
| Stamp the saves | `web/packages/app/src/routes/chat-page.tsx` `transcriptCacheFor` | A small localStorage JSON map (`roboco.transcriptSeedStamps.v1`) keyed `(engineKey, rawChatId)` → epoch ms. `save` writes the stamp after the durable write; `load` reads it into the seed (`savedAtMs: 0` when absent/corrupt/unwritable ⇒ stale). All access in try/catch; no engine-client/registry changes. |
| No surface changes | — | The open tail, tail-follow (`stick.setStreaming`) and arrival behavior all already key on the entry status / snapshot `streaming` flag — keeping the seed verbatim is sufficient. No dock/hero files (65's lane). |

## 3. Tests

- `tests/transcript-replay-integration.test.ts`: the harness's deferred cache produces `TranscriptSeed` (a bare entry array in `settleCache` is the stale shape, `savedAtMs: 0`, so every existing expectation — including ticket 80's stale-downgrade case — keeps passing). New: a live seed (`savedAtMs: Date.now()`) asserts `snapshot.streaming === true` and the tail group's rendered-open records carry `open: true` across the follow-up live reset; a seed stamped past `LIVE_SEED_STREAMING_MS` still downgrades.
- `tests/pending-send.test.ts`: `deferredCache` updated to the seed load shape (`savedAtMs: 0`).

## 4. Acceptance

- [ ] Entering a currently-running chat shows the last tool call open immediately, no closed→open flash (user re-test on the follow-up build).
- [ ] Entering a chat whose run was interrupted by app close still renders closed (ticket 80's behavior — covered by the stale tests).
- [ ] Tests above pass; full `pnpm test` green; `pnpm -r build` from `web/` green.

## Comments

Created 2026-09-21 from the live round-3 report (desktop shows the running chat's last tool call open, state preserved; web shows it closed ~1s then opens). Root cause: ticket 80's unconditional downgrade also rewrites LIVE mid-run seeds. Fix: stamp saves, keep seconds-old seeds verbatim.

**2026-09-21 — implemented and merged to `web-parity/followup`.** `fix(web): ticket 81 keep live-seed streaming status`: `TranscriptSeed { entries, savedAtMs }` + exported `LIVE_SEED_STREAMING_MS` (300_000) in `state/transcript-store.ts` (live ⇒ verbatim seed, stale/unstamped ⇒ ticket 80's per-entry downgrade, branch at the constructor's call site); `routes/chat-page.tsx` `transcriptCacheFor` stamps/reads `Date.now()` per `(engineKey, rawChatId)` in a localStorage JSON map (try/catch, absent storage ⇒ 0 ⇒ stale; the stamp is written after the durable save; no engine-client/registry changes). Tests: harness + `deferredCache` moved to the seed shape (all existing expectations unchanged — stale by default), plus the live-seed case (streaming snapshot + open tail across the live reset) and the past-window stale case. Full suite **1526/1526** (94 files, +3) and `pnpm -r build` green. User re-test on the follow-up build pending.
