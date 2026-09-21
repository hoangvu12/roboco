# 82 â€” Live arrival: identify the frame that closes the tool group

**What to investigate:** Canvas â†’ an existing running chat initially shows open tools, then closes them; earlier reports also describe scrolling toward ongoing work. Establish the actual incoming-frame â†’ store â†’ rendered row â†’ motion â†’ paint chain before selecting a production fix. Desktop first-populated arrival is the parity reference.

**Blocked by:** Live remote-access URL and browser authorization (requested during this investigation), plus identification of the served bundle. Synthetic replay distinguishes several mechanisms but cannot identify which payload the reported session received.

**Status:** done

**Scope / identity:** 2026-09-21; `roboco-wt/pr`, branch `web-parity/followup`, HEAD `94b7f6430bcccef2adb96ff03581c506e6ce78d9`. Diagnosis and harness characterizations only. No production fix implemented, commit/push, Rust run, dev server, or browser session. Read `AGENTS.md`, `CONTEXT.md`, ADR 0004, the follow-up verification discipline and round-2/3 history, and tickets 80/81. Code line references below refer to this worktree unless explicitly marked HEAD.

## 1. Evidence and diagnosis boundary

**Confirmed by mounted reproduction:** A recent seed with a streaming tool tail renders that group open. A live reset which adds a later text part closes the same group even while **the same entry and snapshot remain streaming**. A reset which completes that entry and starts another streaming entry also closes the old group. The reset produces a closed DOM endpoint before the harness commit finishes; this is a correction between separate data commits, not a surviving close tween.

**A separate confirmed ordering defect:** On a late reset, the child tool row's layout effect runs before the parent surface's arrival re-arm/baseline sync. `noteRendered` temporarily emits an automatic group-close transition against the old reveal record. The ensuing baseline strips its tween clocks in the same commit. The existence of that event does **not** prove a visible 140 ms animation on reset.

**Another reproduced mechanism:** After an unchanged reset, a delta adding a tool followed by text can keep the group open through `arrivalPending`. When the connector reveal expires, a row animation frame flips it closed and seeds a real close tween, without any new transcript publication. This mechanism must not be mistaken for a reset correction.

**Not established:** Which mechanism occurred in the user's session, the actual browser paint frames, why that particular chatâ†’chat comparison was clean, or whether the recorded desktop run received identical seed/reset/delta data. No live capture was available during this pass. The report is not marked diagnosed/resolved.

### Existing worktree changes matter

At investigation start these files were already modified: `src/routes/chat-page.tsx`, `src/state/transcript-store.ts`, `src/styles/app.css`, `tests/chat-arrival.test.ts`, `tests/pending-send.test.ts` (paths relative to `web/packages/app/`). They were preserved.

The pre-existing changes add `transcriptSnapshotIsLive` (`state/transcript-store.ts:69`), wait for reset provenance or an error in swap retention (`routes/chat-page.tsx:1124`), and hide seed-only canvas content behind `.chat-arrival-gate` (`1144`, `1328`). HEAD instead releases retained content on `snapshot.loaded` (HEAD `chat-page.tsx:1124`) and has no seed-visibility gate. These are materially different presentation paths. The harness mounts `TranscriptView` directly, so it diagnoses the real transcript lifecycle but does **not** test either route's visibility gating. A successful local build is not evidence that a running executable embeds it.

### Candidate timeline, before the experiment

Predictions tested: (1) changed seed/reset tail data changes the open endpoint; identical data does not; (2) expiry changes whether a flip emits a tween event, not whether `autoOpen` becomes false; (3) replay baseline should clear thought completion history; (4) remembered false pins apply from the first row render; (5) delayed geometry growth can glide outside the arrival window even when streaming is true. Also checked whether a resubscribe is needed and whether a post-reset reveal can later own the close.

## 2. Reproduction: exact commits and render transitions

The existing mounted harness is `web/packages/app/tests/transcript-replay-integration.test.ts`. The new suite starts at line 717. It mounts the actual `TranscriptView`, `TranscriptStore`, `ToolGroupMotionStore`, and stick controller. The initial store is unloaded with no prior painted transcript, equivalent to the requested canvas-origin surface state. A deferred cache and controllable fake client deliver synthetic protocol inputs. Call-through probes record `sync`, `noteRendered`, arrival `arm`, automatic transitions, and store snapshots. The trace type/probes are at lines 215, 255, 272, 286.

The scratch probes were run first using `pnpm vitest run tests/transcript-replay-integration.test.ts -t 'ticket 82 scratch' --reporter=verbose`: **4 passed**, with raw chronological output. A fifth scratch case isolated live reveal expiry. Scratch logging was then removed; only assertions exercising the reproduced mechanisms remain. These are diagnostic characterizations of current behavior, including the erroneous late-reset event, not claims that current behavior satisfies the desired UI contract.

### A. Recent seed â†’ changed streaming reset: endpoint snap

Synthetic input: entry `S`, `status: streaming`, parts `[tool(pwd), tool(ls)]`, cache stamp `Date.now()`. Reset at performance time 11000 replaces its parts with `[tool(pwd), tool(ls), text("Still working")]`, keeping `status: streaming`.

| Frame / commit | Store and surface state | Motion event / observable endpoint | Owner |
| --- | --- | --- | --- |
| Mount, t=10000 | Unloaded, replay pending; no rows | Empty live sync | `TranscriptStore` initialization; surface sync |
| Cache publication C1, t=10000 | `seedEntries`: loaded, replay populated, baseline epoch 1/provenance seed; `streaming=true` | `rowsForEntry` builds `S#g0` with `autoOpen=true` | `transcript-store.ts:414-419,472-485`; `lib/transcript.ts:1563,1600-1616` |
| Seed commit layout effects | Row renders open; scroller arms arrival and performs one initial snap; parent syncs seed baseline then live rows | Initial child `noteRendered` finds no reveal record; baseline installs settled history; follow-up render records `open=true`, body **66px** | `tool-group.tsx:128,164-166`; `transcript.tsx:1109-1151,405-421`; `tool-motion.ts:760-778,833-854` |
| Network reset, C2 then C3, t=11000 | Generation 0â†’1 first publishes pending with seed epoch 1; accepted reset then publishes populated epoch 2/reset with changed parts. Both snapshots still say `streaming=true` | React consumes the populated result in this batch; no empty transcript render is required | `transcript-store.ts:514-526,534,553-563,574-589` |
| Reset render R1 | `S#g0` is no longer the entry's last part; `autoOpen=false`; no explicit pin or pending reveal | DOM calculation is closed/body **0px**. Child `noteRendered` sees prior `renderedOpen=true` and expired old arrival; emits `{rowId:S#g0,key:null,toggledAt:11000}`, seeds `from=66` | `lib/transcript.ts:1611`; `tool-group-geometry.ts:227-229`; `tool-motion.ts:719-734` |
| Same commit, parent effect | Parent now arms arrival at 11000, consumes reset baseline, then syncs live rows | Clears reveal/thought history and group clocks. Follow-up render remains closed/body **0px**, `toggledAt=null`; no surviving reset fold tween | `transcript.tsx:417-421`; `tool-motion.ts:762-777` |

The retained tests assert child close-record < parent `arm` < baseline `sync` in the chronological probe. They assert the intermediate group transition, final cleared clock, three raw store publications, and final DOM endpoint. At reset delay **25ms**, the same data closes the group but emits **no** automatic transition. Thus expiry is unnecessary for the two data commits to show different fold endpoints; it only enables the extra pre-baseline event here. The 1000ms delay is controlled fixture time, not measured network latency.

The same matrix runs for `[reasoning] â†’ [reasoning,text]`, and for `S.streaming â†’ S.complete + T.streaming`. Reasoning becomes resolved when no longer the final part (`lib/transcript.ts:1654-1660`), but no thought-detail completion transition is emitted on the reset: baseline clears the tracker first (`tool-motion.ts:769,807-825`). Late cases emit **group** events (`key:null`), not detail events (`key:S#g0#d0`). No explicit fold pin and no resubscribe are needed; `client.watches.length === 1` throughout.

**Paint limitation:** jsdom proves DOM heights and effect order, not browser paints. If the seed and reset commits are both exposed by the route, their endpoints are open then closed. It cannot say whether the dock hid either endpoint in the actual session. Multiple layout-effect records within one commit must not be counted as separate visible frames.

### B. Unchanged reset â†’ live delta â†’ reveal expiry: a real close tween

Reproduction at test line 801, with normal 16ms animation steps:

| Time | Transcript / rendered state | Motion consequence |
| --- | --- | --- |
| 10000 | Seed `[pwd]`, then identical streaming reset; `S#g0` open | Reset baseline is settled, no flip |
| 10100 | Delta upserts the same streaming entry as `[pwd,ls,text]`; baseline remains epoch 2 | `autoOpen=false`. Child first reports closed, but live `sync` gives the added tool a reveal start at 10100. Re-render is open via `arrivalPending`. Both records are within one commit; final endpoint is open |
| 10564 | rAF updates through 29Ã—16ms; no network frame | Group remains open, body 66px |
| 10580 | Connector reveal reaches 480ms; reset arrival window is 580ms old | `arrivalPending=false`; `noteRendered` seeds `from=66`, `toggledAt=10580`, emits group transition. No baseline sync clears it. Header `aria-expanded=false`, body still 66px at tween start |
| 10650 â†’ 10730 | Row clock alone advances | Body passes through an intermediate height, then reaches 0px; store snapshot identity stays unchanged at expiry |

Owners: `tool-motion.ts:852-854` assigns new starts; connector duration **480ms**, first-new-group delay **90ms**, per-tool stagger **65ms** (`86-90`); `tool-group-geometry.ts:227-229` resolves `autoOpen || arrivalPending`; `tool-group.tsx:154-165` drives row clock/report; `tool-motion.ts:724-730` seeds close; `tool-group-geometry.ts:292-300` resolves its height. The group can close without a status change or a new `WatchDocMessages` frame at that instant.

### C. Scrolling is independently reproducible

At test line 850, stubbed scroller starts `clientHeight=600`, `scrollHeight=4000`, pinned at `scrollTop=3400`. Seed and identical reset both stream. Increase height to 4600 and deliver a row measurement:

- At reset+25ms: arrival `kick` hard-writes **4000**.
- At reset+501ms: first frame writes a value strictly between **3400 and 4000**, proving spring progression under these stubbed dimensions.

Measurements refresh arrival at `transcript.tsx:923-926`, per-commit kick is `1244-1258`, arrival hard-write is `stick-controller.ts:180-192`; outside it, `#stepSpring` uses `shouldAnchorLiveStream` (`574`). That predicate only hard-anchors within **AT_BOTTOM_PX**, not for every streaming/pinned state (`lib/stick-spring.ts:84-85`). The 70px restick band is a different predicate (`65-66`). `setStreaming` merely stores the flag (`stick-controller.ts:170-172`); it neither opens tools nor itself starts scrolling.

This proves a possible scroll owner, not the reported browser's actual growth amount, timing, or dock overlap. `ChatArrivalWindow` has a 50ms measurement-quiescence rule and 500ms hard cap (`lib/chat-arrival.ts:32,35,68-77`). The clock alone does not close tools; it gates how an existing state flip moves.

## 3. Desktop parity and engine evidence

Detailed source audit: [desktop and engine evidence](../research-2026-09-20/82-desktop-engine-evidence.md). No native execution was performed.

| Concern | Desktop code-proven behavior | Web divergence / shared behavior |
| --- | --- | --- |
| Selection | `state.rs:1748-1756` clears transcript, marks replay pending; `1775-1792` starts watch | Web uses per-chat stores and keyed surface remount (`components/transcript.tsx:229-230`) |
| Data arrival | Desktop **also** reads raw offline cache before watch (`state.rs:2174-2211`, `engine_cache.rs:47-61`) | Web cache seed carries timestamp, accepts recent status verbatim, and has separate seed/reset epochs. Native has no seed-age rewrite |
| First populated rows | Attach restores explicit folds before row derivation (`transcript.rs:4134-4187`); replay baseline clears reveals/clocks (`4273-4293`) and makes old tools history (`4351-4373`) | Same intended endpoint on web; baseline is parent layout effect, after descendant row effects |
| Open tail | `auto_open = streaming && last_ix == last_part_ix` (`transcript.rs:1257-1275`); first render has no old `rendered_open`, so no flip tween (`6239-6267`) | Shared formula. Web unchanged recent seed/reset also mounts open and stays open in the harness |
| Subsequent reset | Native first populated seed consumes `veil_attach_pending` (`4411-4418`); reset has no separate baseline epoch (`state.rs:1026-1035`) | Web re-arms and clears baseline on every accepted reset (`components/transcript.tsx:405-421`); it can first emit a child event against old baseline state |
| Viewport | Native first fill uses `scroll_to_end` (`transcript.rs:4521-4533`); restored anchors resolve to row offsets (`3156-3183`); live-following path anchors and resets spring (`4440-4446`) | DOM estimates and observer corrections span commits; web timed arrival suppresses spring only during its window |
| Canvas presentation | Selection, transcript sync/reveal/list handling are separate native stages; `select_chat` alone is not an atomic fresh-document read | HEAD web canvas with no prior painted store mounts unloaded and can expose seed before live reset; chatâ†’chat retains old surface only until **loaded**, including a seed (HEAD `routes/chat-page.tsx:1106-1150`) |

**Do not claim desktop is intrinsically immune to seed correction.** Its raw cache can also be old. After the first populated baseline, a later open-state flip can animate (`crates/ui/src/transcript.rs:6257-6265`). Both clients also use temporary reveal-driven opening. The user-observed desktop arrival matches a first populated streaming tail mounted at its endpoint; source does not establish that it saw the same intermediate data as the web report.

**Exact proven web ordering divergence:** data is rendered into descendant rows before `TranscriptSurface` consumes the new reset baseline in its layout effect. Native sync prepares rows/baseline before `render_tool_group` reads them. The harness demonstrates the web's resulting extra reset event. This does not explain every visible close, since shared data/part progression also closes groups correctly.

### Engine: what a reset actually means

- `WatchDocMessages` sends the current published joined snapshot as its first reset (`crates/engine/src/rpc.rs:803-840,951-960`). `watch_messages` subscribes and refreshes a dirty mirror (`doc_host.rs:276-289`); publication reads entries and joins continuations (`379-386`). There is no reset-specific status downgrade or reconstruction from chat Working.
- `STREAM_COMMIT_MS=120` (`crates/doc/src/constants.rs:15`); sessions coalesce dirty fold writes (`sessions.rs:1556-1565,2183-2186`). `sync_segment` lazily starts on nonempty output (`1263-1279`). `SegmentWriter::begin` commits streaming with empty parts (`schema.rs:941-964`); `sync` writes parts, leaving status unchanged (`1007-1069`); `finish` writes terminal status (`1074-1079`). Intermediate commits may coalesce before observation.
- A streaming entry can gain text after tools. A Working chat can also have a completed prior segment, e.g. at a steer boundary (`sessions.rs:2006-2035`). Continuation joining retains the root's scalar status while extending parts (`schema.rs:894-918`). These facts permit several payload shapes; none proves which one occurred in the report.
- A five-minute-valid cache stamp is not a content-freshness guarantee. Web saves on a 300ms timer (`transcript-store.ts:349,602-612`), and ticket 81's acceptance threshold is **300000ms** (`359,418`). It does not promise identical tail-part position at live reset.

### Dock and route: visibility owners, not fold-state owners

`routes/chat-page.tsx:988-1072` pumps dock geometry and CSS variables; `lib/dock-glide.ts:76,112` writes transcript opacity; `.chat-body` consumes it at `chat-page.tsx:1317-1318`. `composer-dock.ts:194-200` blends the entering transcript over phase 0.2â€“0.65. These paths can expose or conceal successive transcript endpoints, but do not set tool folds. The GPU hero fix is not required for any harness reproduction.

HEAD chatâ†’chat retention does **not** guarantee a live-only destination: its release condition is loaded, and cache seeding sets loaded. Also `lastPaintedTranscriptRef` is only assigned for non-null outlets; it is not unconditionally cleared on every canvas visit. The reported clean chatâ†’chat behavior is an observation to reproduce, not a universal consequence of the outlet's source comments. The pre-existing uncommitted gate changes this policy and requires separate runtime validation.

## 4. Hypothesis results and missing capture

| Candidate | Result |
| --- | --- |
| Old/unstamped seed status rewrite | Ticket 80 behavior remains covered; it starts closed and cannot alone explain this open-first reproduction |
| Recent seed differs from live reset | Reproduced with changed parts and with old-entry completion; status need not become nonstreaming globally |
| Identical streaming seed/reset | Remains open, including post-reset tail-tool growth and elapsed time beyond cap (test line 781) |
| Thought tracker fires on reset | Refuted in the tested reset matrices; baseline clears tracker, only late **group** event occurs |
| Arrival expiry causes close | Expiry alone cannot change open state; it permits a flip event. Late reset event is cleared; later reveal-expiry event sustains a tween |
| Fold memory pins closed after delay | Refuted for same stable row id in mounted reproduction: closed pin applies on first render (line 837) |
| Desync/resubscribe mid-arrival | Not needed for reproduced closures; one watch. Existing ticket 69 tests cover resubscribe baselines. No live evidence to exclude its presence in the report |
| Stick/restick causes group close | Separate owners: stick affects viewport, not fold state. Outside arrival, sufficient stubbed growth springs even while streaming |
| Dock/GPU flicker | Harness has neither; no evidence assigning actual raster/opacity flicker to either. Requires browser capture |

The live request must capture, with monotonic timestamps:

1. Executable identity and served JS/CSS hashes; whether the pre-existing arrival gate is present. Identify chat id, frame generation, watch/subscription, and canvas origin; do not copy credentials.
2. Cached `TranscriptSeed` (save age, relevant entry/part ids/statuses/order) and actual `WatchDocMessages` reset/delta payloads through the close. Text can be redacted while preserving part kinds, identities and lengths.
3. Store publications (baseline epoch/provenance, replay, streaming), descendant rendered-open/body height, baseline sync, arrival arm/measure/isArrival, fold/reveal timestamps, automatic transition key.
4. Correlate those events with visible header/body frames, `.chat-body` and gate opacity, scroller height/top and kick/spring writes. Repeat chatâ†’chat and desktop against matching data where practical.

Then replay the captured minimal frames in this harness. If the close occurs on reset, identify exactly which row's part/status changed. If it occurs later without a store commit, test reveal expiry. If frames and derived open state remain identical, investigate presentation/remount/geometry rather than attributing a content correction. Keep capture instrumentation temporary and redact credentials.

## 5. Minimal fix proposal â€” conditional, not authorized implementation

**For the demonstrated reset ordering defect:** make accepted-baseline reconciliation precede descendant `noteRendered` and scroll/motion side effects for that baseline. Touch `components/transcript.tsx` and, only if needed for a baseline-generation guard, `components/tool-group.tsx` / `lib/tool-motion.ts`; add a mounted expectation that a late changed reset emits no automatic replay transition. Preserve the separate baseline-rows/live-rows passes so genuinely post-reset deltas in one batch still receive their reveals. Do not mutate/publish the shared motion store unsafely during React render. This repairs the extra reset event; it does not by itself conceal two different data endpoints already painted.

**If the actual visible artifact is stale seed â†’ reset correction:** a presentation gate can make the first visible destination use accepted authoritative data while the seed prepares state/geometry. Ownership is `routes/chat-page.tsx`, with a store snapshot predicate in `state/transcript-store.ts` and scoped presentation CSS only as needed. This is the direction of the worktree's pre-existing change, not a fix verified by this investigation. Explicitly decide offline/error/never-arriving-watch fallback; preserve viewport measurement and interaction isolation while hidden. Validate both route origins. Do not suppress real post-reset live changes or falsify streaming status to keep old tools open.

**If capture identifies a real post-reset reveal expiry instead:** the existing behavior may be shared native live behavior. Changing automatic fold policy needs the user's decision and matched desktop evidence; extending the arrival timeout would suppress motion but would not preserve an endpoint whose `autoOpen` is false.

Risks: stale hidden loading forever; offline transcript availability; seed-era scroll/fold effects firing behind a visibility gate; genuine live tool entrances swallowed; explicit pins lost; reset+delta coalescing misclassified; StrictMode duplicate reconciliation; reconnect replay changes; escaped viewport ownership; measured geometry after first visible paint. Preserve always-opaque web surfaces, reduced-motion endpoints, engine-local data, and the existing 65/68/69/70/71/80/81 contracts.

## 6. Tests and acceptance

Executed:

- From `web/packages/app/`: `pnpm vitest run tests/transcript-replay-integration.test.ts --reporter=verbose` â€” **20/20 passed**, including 11 new diagnosis cases and the 9 existing 69/80/81 cases.
- From `web/packages/app/`: `pnpm vitest run` â€” **1542/1542 passed, 94 files**. This includes the pre-existing worktree tests: 1526 baseline + 5 pre-existing cases + 11 new diagnosis cases.
- From `web/`: `pnpm -r build` â€” **passed** (TypeScript checks and Vite build; existing large-chunk advisory). Local output: `main-CaPn1AGW.js`, `main-Bdk0oKXl.css`. These names identify this local build, not any running engine's embedded assets.
- `git diff --check` passed; scratch debug markers removed.
- No Rust changes; native tests were read, not executed. No browser/phone/desktop manual acceptance has passed in this investigation.

Acceptance remains:

- [x] Candidate timelines, code evidence, desktop/engine contract, and synthetic mounted reproduction recorded separately from runtime attribution.
- [x] Scratch logs replaced with focused retained assertions; production code left untouched by this investigation.
- [ ] Capture actual entering-chat frames and correlate each visible transition with its owner.
- [ ] Confirm the cause of this user's report, then select the minimal proposal with the user.
- [ ] Only after authorization: implement; prove canvas and chatâ†’chat parity, continued live-stream behavior, offline fallback, fold memory, restored/escaped viewport, reduced motion, and reset+delta batching against the actual served bundle.

## Comments

2026-09-21 â€” Diagnosis pass established two distinct close mechanisms and a child-before-baseline event-ordering gap. The harness cannot determine which frames the reported session received. A live URL and browser authorization were requested; ticket remains needs-info. No speculation is promoted to the report's confirmed root cause.


2026-09-21 - landed via the ticket 83 delivery: the presentation gate (	ranscriptSnapshotIsLive + the keyed .chat-arrival-gate outlet) is implemented and committed with warm switching. Runtime confirmation: the user's re-test of the gated bundle reported the flicker gone ('the implemented flicker guard appears to work', recorded in ticket 83's evidence). Mechanism B (reveal-expiry close on live deltas) deliberately left at desktop parity - shared live behavior. The child-before-baseline ordering defect noted in section 1 remains open as a small follow-up.