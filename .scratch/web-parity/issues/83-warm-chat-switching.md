# 83 — Reuse live transcripts when switching back to recent chats

**What to build:** Switching between already-visited existing chats should reuse current transcript data immediately. A cold destination should acknowledge selection with a loading state instead of leaving the previous chat visible for the whole engine roundtrip. Preserve ticket 82's guard against exposing a seed before its authoritative reset.

**Blocked by:** None for implementation; manual browser acceptance remains pending.

**Status:** ready-for-human

## 1. Evidence

2026-09-21 user report: the implemented flicker guard appears to work, but switching existing chats leaves the old chat visible for 1–2 seconds, including repeated back-and-forth navigation. The duration is user-reported, not measured by this investigation.

Before this change, `ConversationPage` constructed a new `TranscriptStore` on every selected chat change and disposed the previous store. The modified arrival gate retained the previous painted transcript until the destination had reset provenance or an error. Thus a cache hit did not release retention, and revisiting a chat still required a new watch/reset. There was no fixed 1–2 second timer in that retention path.

Two reproductions were run red before correction:

- Extracting the existing retention policy into the real outlet component and mounting it with controllable stores: selecting B while its reset is pending still rendered A. Rapid B→C also retained B. `pnpm vitest run tests/chat-transcript-outlet.test.ts`: two failed assertions (`data-doc=A/B` remained).
- Mounting a kept real store after it received deltas while unmounted caused old tools to receive reveal starts. `pnpm vitest run tests/transcript-replay-integration.test.ts -t 'mounted warm-chat return'`: both normal and StrictMode cases failed. Its last reset baseline was older than its current entries, so the surface classified background history as new arrivals.

These tests establish lifecycle behavior and DOM endpoints. They do not establish browser frame time, paint smoothness, or the cause of network latency.

## 2. Implementation

| File under `web/packages/app/` | Behavior |
| --- | --- |
| `src/state/transcript-pool.ts` | Session-owned LRU of at most five visited transcript stores. Returning a store refreshes recency; its existing watch stays subscribed and receives updates while the chat is offscreen. Eviction cancels the oldest watch and releases its store. No speculative status changes or new durable cache. |
| `src/state/engine-session.ts` | Owns the pool with the connection's picker catalog. Metadata-only session refresh retains both; replacement, forgetting, and provider teardown dispose them. Separate engines cannot share raw transcript stores. |
| `src/routes/chat-page.tsx` | Gets the destination store from the session pool; removes route-switch disposal and old-chat retention. Canvas departure still paints the departing store while its existing dock exit runs. |
| `src/components/chat-transcript-outlet.tsx` | Selects the destination immediately. Already-live stores are visible on mount; cold stores hydrate behind the authoritative-arrival gate with “Loading chat…”. Hidden content is inert and hidden from accessibility APIs. The gate is keyed by doc so a new seed cannot inherit the outgoing gate's visible opacity. |
| `src/components/transcript.tsx` | A loaded store's entries at surface mount form its initial reveal baseline, including deltas received while away. Later reset/delta batching retains ticket 69's separate baseline/live passes. StrictMode cleanup re-arms the mount baseline along with its cleared motion state. |
| `src/styles/app.css` | Flex-sized outlet with a loading overlay; preserves the existing 120ms cold-arrival fade and reduced-motion rule. A warm destination mounts at opacity 1 without waiting for a visibility transition. |

Resource tradeoff: up to five transcript watches per paired engine remain active, so recent streaming chats stay current at the cost of bounded background updates/memory. Only visited chats are included. Older evicted chats, a page reload, or a replaced engine connection still need a live reset. Rendering and row measurement still take time; this change removes the repeat network wait, not every possible source of UI work.

## 3. Verification

- `tests/transcript-pool.test.ts`: A→B→A reuses A's watch, receives its background completion, maintains LRU bound, reloads evicted stores, retains resources on metadata refresh, and disposes old resources on connection replacement.
- `tests/chat-transcript-outlet.test.ts`: warm destinations have no loading state; cold destinations replace old content immediately but keep the seed hidden; abandoned destination resets do not overwrite current selection; error and departure paths remain available.
- `tests/transcript-replay-integration.test.ts`: real warm-store remount baselines background tools without entrance animation, then still animates genuinely new live tools; normal and StrictMode. Existing replay, arrival, fold and scroll cases retained.
- `pnpm vitest run` from `web/packages/app/`: **1550 passed, 96 files**.
- `pnpm -r build` from `web/`: **passed**. Local assets: `main-DwV4c2L9.js`, `main-BZAG8CMZ.css`. Existing large-chunk advisory remains. This does not identify any currently served engine bundle.
- `git diff --check`: passed.
- No server, browser, `cargo run`, commit, or push performed.

## 4. Acceptance

- [x] A→B→A reuses the current store without opening another transcript watch.
- [x] Updates received while away mount as settled history; post-return live arrivals remain live.
- [x] Cold navigation removes previous chat content immediately while preserving the seed visibility guard.
- [x] Bounded retention and session replacement cleanup covered by tests.
- [ ] Rebuild the engine with these web assets and manually check existing-chat switching, streaming return, fold/viewport preservation, canvas departure, and first-visit loading. No manual result claimed.

## Comments

2026-09-21 — Implemented locally after the user clarified existing-chat navigation and requested smoother repeat switching. This extends the original diagnosis-only work under the subsequent implementation request. Pre-existing ticket 81/82 changes remain in the worktree; no reset of user changes was performed.

2026-09-21 - committed (no push yet) as "fix(web): tickets 82/83 live arrival gate and warm chat switching". Full suite 1550/1550 (96 files) re-verified before commit; pnpm -r build green. User browser re-test in progress; a mobile transcript overflow report (content flowing outside the screen at phone width) is filed as the next follow-up.
