# 67 — Keep picker catalogs alive through identity pinning and engine restart

**What to build:** After pairing an engine for the first time, opening the model picker must complete its catalog requests or show the existing real error and Retry surface without requiring a browser refresh. Pinning the engine's verified device identity must not destroy the picker catalog still held by the page. Engine-gate Retry must also move the page onto the registry's replacement client/cache and a live catalog.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

**Research:** `../research-2026-09-20/followup-picker-lifecycle.md` §§3–5. All required contracts and test/gap tables are inlined below; the link is for evidence depth.

**Baseline:** `web-parity/wave-2` at `37c354ff`. Recheck cited line numbers if intervening work shifts files.

**Desktop reference (for lookups only):** `crates/ui/src/pickers.rs` target-state observer at 588–606, engine at 697–698, picker-open reload at 1010–1018, ensure_harnesses at 1028–1040, render prefetch at 4164–4168.

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/state/session-provider.tsx` | edit | EngineSessionProvider subscriptions, current-resource reconciliation, catalog retention/disposal, stable context map publication; preserve existing routing and Retry controls. |
| `web/packages/app/src/state/engine-session.ts` | edit if needed | Correct lifecycle comments and a narrowly scoped reconciliation/planning helper if useful. Keep createEngineSession/disposeEngineSession resource boundaries intact. |
| `web/packages/app/tests/session-provider.test.ts` | new | Mounted provider lifecycle regressions using actual provider, session creation, and PickerCatalog with controllable store/registry/client seams. This path does not exist at baseline. |
| `web/packages/app/package.json` | edit | Add jsdom dev dependency for the single mounted-provider suite; React/react-dom/Vitest already exist. |
| `web/pnpm-lock.yaml` | edit through package manager | Record the selected compatible jsdom dependency and transitive lock changes. |
| `web/packages/app/tests/picker-catalog.test.ts` | existing validation; edit only if a reusable deferred-model seam is necessary | Existing catalog transition tests remain. Do not replace them with lifecycle-only coverage. |
| `web/packages/app/vitest.config.ts` | read; no edit expected | Keep node default and tests/*.test.ts include. Use a per-file jsdom pragma and React.createElement in the new .test.ts file. |
| `web/packages/app/src/state/fleet.ts` | read; no edit expected | Existing useFleetRegistry is the actual registry publication feed; no new singleton/store or polling API required. |

## 1. Context a fresh session needs

- The user reported: "the model picker first time connect still loading infinitely". This follows tickets 38 and 61, which repaired catalog loading/retry semantics but did not exercise the mounted session provider's ownership changes.
- EngineSessionProvider owns a map of EngineSession wrappers. The EngineRegistry owns connections and watch caches; each wrapper adds the PickerCatalog used by the composer. It is not a user-facing "chat session" concept.
- On fresh pairing, EngineStore writes deviceId null (`lib/engine-store.ts:140-149`). Later identity verification calls pinDevice, replacing the stored engine object and engines array (`state/fleet.ts:58-62`; `lib/engine-store.ts:171-178`).
- The provider is outside the connection/loading gate (`routes/root-layout.tsx:30-34`). The registry constructs resources before its async cache-seeded dial (`engine-client/src/registry.ts:237-282`). It can therefore create a catalog before the pin.
- The metadata refresh arm clones a wrapper with the same catalog (`state/session-provider.tsx:65-69`), then its wrapper-inequality cleanup disposes that catalog (`:78-80`). The replacement wrapper now retains an unusable object.
- disposeEngineSession only disposes the catalog (`state/engine-session.ts:40-42`). Catalog disposal permanently sets #disposed and removes listeners/models (`state/picker-catalog.ts:472-480`). Fleet transport and sidebar data can continue working, while all catalog retries permanently no-op (`:275-277`, `:337-339`).
- This ownership defect is proven from source. The exact sequence has NOT been observed against the user's browser/engine. Do not claim a runtime reproduction or that it explains every possible loading symptom.
- Refresh plausibly heals this path because identity is already persisted and pinDevice sees the same identity (`lib/engine-store.ts:172-173`, `:234-243`). A new page creates a new catalog without the metadata clone/disposal.
- The same ownership layer misses registry restart: Retry changes client/cache without changing stored credentials (`session-provider.tsx:111-126`; `engine-client/src/registry.ts:221-232`). The current provider only depends on fleet.engines and reuses by credential.
- Existing useFleetRegistry (`state/fleet.ts:75-80`) exposes changed registry snapshots. Its subscription must drive reconciliation of actual current client/cache identities as well as metadata. Do not make this a vague "recreate session on reconnect": an ordinary reconnect of the SAME client must preserve the catalog.
- Desktop forces loads on real picker open (`pickers.rs:1010-1018`) and runs NON-FORCED ensure/prefetch each render (`:4164-4168`). Historical notes claiming force-on-every-render are incorrect. This ticket does not add forcing/retry events.
- No CSS, geometry, tokens, animations, keyboard behavior, RPC formats, credentials, stored data formats, or desktop Rust changes are needed. Use the existing vocabulary: chat, space, engine, harness; Session only for pairing credentials in user-facing text.

## 2. Spec

### 2.1 EngineSessionProvider and catalog ownership

The following source/state/layout/motion/text/data tables are copied verbatim from research §3.

#### Source and ownership contracts

| Concern | Current source and behavior | Required behavior for this slice |
| --- | --- | --- |
| First pairing | `web/packages/app/src/lib/engine-store.ts:140-149` persists a StoredEngine with `deviceId: null`. | Keep the pairing format and initial null identity unchanged. |
| Identity pin | `web/packages/app/src/state/fleet.ts:58-62` invokes `pinDevice` on connected verified engines; `lib/engine-store.ts:171-178` replaces the stored engine object and engines array. | Metadata refresh preserves the already-live catalog and transport resources. |
| Session construction | `web/packages/app/src/state/engine-session.ts:31-37` combines a stored engine, registry client/cache, and newly owned PickerCatalog. | One catalog per currently adopted registry resource pair; do not create another socket. |
| Metadata reuse | `web/packages/app/src/state/session-provider.tsx:65-69` clones the wrapper while retaining client, cache, and catalog. | Wrapper identity may change; resource ownership must survive the clone. |
| Current cleanup defect | `web/packages/app/src/state/session-provider.tsx:78-80` disposes whenever wrapper identity changes. | Dispose a previous catalog only when no next-session entry retains that catalog identity. Deduplicate disposal within a reconciliation. |
| Disposal boundary | `web/packages/app/src/state/engine-session.ts:40-42` disposes only the catalog; `state/picker-catalog.ts:472-480` marks it disposed and clears model/status listeners. | Registry owns client/cache teardown. Provider owns catalog teardown only. Preserve this boundary. |
| Registry restart | `web/packages/engine-client/src/registry.ts:221-232` tears down the entry, spawns new client/cache, and commits. | Adopt replacement client/cache even if URL, credential, and stored engines array remain unchanged. |
| Existing registry feed | `web/packages/app/src/state/fleet.ts:75-80` exposes `useFleetRegistry` via `useSyncExternalStore`; `engine-client/src/registry.ts:413-433` publishes changed snapshots. | Provider observes this feed as well as `fleet.engines`, then looks up current resources via `clientFor` and `watchCacheFor` (`registry.ts:146-151`). |
| Snapshot stability | `web/packages/engine-client/src/registry.ts:129-131`, `:419-426` keep snapshot identity stable until a change. | Registry row updates may trigger reconciliation, but unchanged session entries preserve both wrapper identity and the existing sessions map. |
| Actual desktop reload cadence | `crates/ui/src/pickers.rs:1010-1018` forces on picker open; `:4164-4168` calls non-forced ensure/prefetch each render. | Preserve the web's existing interactions in this ticket. Do not interpret render cadence as permission for repeated forced loads. |
| Desktop target reset | `crates/ui/src/pickers.rs:588-606` clears device-specific catalogs to Idle and notifies the live picker entity on target change. | A real resource replacement creates a usable fresh catalog, never a wrapper holding an irreversibly disposed one. |

#### State contract

| State or transition | Condition and current source | Required result |
| --- | --- | --- |
| First engine registration | No previous session, current registry client/cache available; `session-provider.tsx:71-76`. | Create a fresh catalog and session once. |
| Metadata-only identity pin | Same URL, same credential, same actual client/cache objects, different StoredEngine object; `session-provider.tsx:65-69`. | New wrapper carries updated metadata and the same live catalog. Zero disposal calls. Pending requests and listeners survive. |
| Unchanged registry update | Same StoredEngine, credential, client, and cache; existing feed in `fleet.ts:75-80`. | Reuse existing wrapper and map; no catalog creation, disposal, or picker invalidation. |
| Restart with same credential | Current client or cache object differs; restart path `registry.ts:221-232`. | Create session/catalog for the new pair, publish it, and dispose displaced catalog exactly once. |
| Re-pair | Same URL, new credential; `registry.ts:173-178`. | Fresh session/catalog; old catalog disposed once. |
| Engine removal or unavailable resources | Engine absent from stored fleet, or current lookup returns null; `registry.ts:146-151`, `session-provider.tsx:73-74`. | Omit stale session; dispose its unretained catalog. A later registry publication can recreate the session when resources exist. |
| Provider unmount | Existing unmount cleanup `session-provider.tsx:89-96`. | Dispose current distinct catalogs once and clear the current map ref. Do not stop registry-owned clients. |
| Late old request completion | Catalog disposal guard `picker-catalog.ts:300-308`, `:329-330`, `:354-356`, `:395-397`. | Displaced catalog ignores completion/rejection; new catalog is unaffected. Metadata-only refresh must not discard a valid pending result. |
| StrictMode lifecycle replay | Provider uses effects for creation and cleanup, `session-provider.tsx:60-96`. | Cleanup/re-setup leaves a fresh live adopted catalog. Never reuse a catalog disposed by an earlier effect lifecycle. |

#### Layout, motion, interaction, and text contract

| Category | Value | Source or reason |
| --- | --- | --- |
| Layout | N/A: no geometry, CSS, tokens, panel hierarchy, or DOM children change. | Lifecycle defect lives in `state/session-provider.tsx:60-87`. |
| Existing card children | Harness skeleton, harness error/Retry, no-agents state, then tabs/search/model list/traits retain their current order. | `components/composer-pickers.tsx:630-659`, `:661-718`, `:724-737`. |
| Loading visuals | Preserve five-row skeleton styling. Fix resource liveness so requests can settle. | `components/composer-pickers.tsx:634-639`, `:731-733`; desktop `crates/ui/src/pickers.rs:3394-3400`. |
| Interactions | Opening/reopening picker, model selection, explicit Retry, and engine-gate Retry retain current controls. Engine-gate Retry must bind the newly created registry resources. | `composer-pickers.tsx:242-247`, `:342-345`; `session-provider.tsx:111-126`. |
| Motion | N/A: no trigger, duration, easing, from/to geometry, or reduced-motion rule changes. | State ownership repair only. |
| Text | N/A: add no strings. Existing error rows and no-agents copy remain unchanged. | `composer-pickers.tsx:641-654`, `:724-733`. |
| Keyboard and phone | N/A: no key handlers or mobile presentation changes. First-pair and restart resource lifetime applies equally at all viewport sizes. | Shared `EngineSessionProvider`. |

#### Data, RPC, and security contract

| Reads, writes, or invariant | Exact behavior | Source |
| --- | --- | --- |
| Read pairing metadata | Read existing `fleet.engines`; treat StoredEngine object replacement as metadata, not destruction of all shared resources. | `state/session-provider.tsx:51`, `:63-69`. |
| Read registry changes | Subscribe with existing `useFleetRegistry()`; use snapshots as a reconciliation trigger, not as transport objects. Fetch current client/cache by base URL from registry. | `state/fleet.ts:75-80`; `engine-client/src/registry.ts:146-151`. |
| In-memory writes | Update sessions ref/context map and dispose unretained catalogs. Reconciliation may create a new PickerCatalog for a replacement resource pair. | `state/engine-session.ts:31-42`; `state/session-provider.tsx:83-84`. |
| Harness RPC | Keep `ListHarnesses` and its existing parameters. Engine returns registry descriptors synchronously in the RPC arm. | `state/picker-catalog.ts:299`; `crates/engine/src/rpc.rs:879`. |
| Model RPC | Keep `ListModels`, harness and existing target parameters. Engine resolves harness and awaits its model discovery. | `state/picker-catalog.ts:350-353`; `crates/engine/src/rpc.rs:897-907`. |
| Credential invariant | No credential, session ID, URL, label, identity verification, storage key, or persisted format changes. Never log credentials in tests, screenshots, or diagnostics. | Existing pairing record `lib/engine-store.ts:140-149`; registry client verification configuration `engine-client/src/registry.ts:238-247`. |
| Data locality | No engine-data migration, copying chat docs, data-directory change, or new engine forwarding. | `CONTEXT.md` engine-local data vocabulary; this ticket touches client ownership only. |
| Connection ownership | Provider never disconnects/restarts registry clients during metadata refresh. Only the existing explicit Retry continues calling registry.restart. | `state/engine-session.ts:40-42`; `state/session-provider.tsx:111-126`. |
| Async isolation | Preserve catalog disposal/generation guards. Do not make disposed catalogs reusable to mask incorrect owner cleanup. | `state/picker-catalog.ts:329-330`, `:472-480`. |

### 2.2 Concrete reconciliation algorithm

Implement in the current provider effect, or use a small pure planning helper while keeping production effects and subscriptions in the provider:

1. Import and call existing useFleetRegistry alongside useFleet at component top level. Include its returned snapshot in the reconciliation effect dependencies alongside fleet.engines. This is the concrete notification path for registry-only resource replacement.
2. At each reconciliation, read sessionsRef.current as previous. For each current StoredEngine, ask engineRegistry.clientFor(baseUrl) and watchCacheFor(baseUrl) for CURRENT resources before the reuse decision. Do not decide reuse on credential alone and only then look up resources.
3. If a resource is unavailable, omit the entry. The registry feed must allow later resource availability to create it without any metadata update. Never preserve a wrapper referencing resources no longer adopted by the registry.
4. Reuse an existing session only when its engine.baseUrl and credential match and both its client and cache objects are exactly the current objects. If StoredEngine identity also matches, retain the existing wrapper. If only metadata differs, create `{ ...existing, engine }`, retaining the same live catalog.
5. When credential or either resource identity changes, call createEngineSession with the new current resources. This covers re-pair and explicit registry restart without treating ordinary status changes as replacement.
6. Construct all of next before cleanup. Build the set of retained catalog identities. Dispose each distinct previous catalog not retained in next exactly once. Retaining a catalog via a new wrapper is explicitly NOT a disposal condition.
7. Compare next and previous maps by keys and wrapper identities. If identical, retain previous map/ref and do not call setSessions with a fresh map. Registry watches publish frequently; they must not churn context identities, recreate catalogs, or resubscribe the composer for unchanged resources.
8. Publish the chosen map/ref and preserve the provider's existing routing and notification-driver behavior. Do not force notification-driver remounts merely for metadata changes; its existing session props/hooks must observe new resources on genuine replacement.
9. Keep disposal in explicit resource reconciliation and final unmount cleanup. Do not return a per-dependency cleanup that disposes all current catalogs each time metadata/registry changes. Creation and disposal are effects, not render side effects.
10. On final cleanup, dispose current distinct catalogs once and clear sessionsRef. Account for React StrictMode cleanup/re-setup: subsequent setup cannot adopt a catalog from a lifecycle already disposed. Catalog.dispose remains idempotent, but tests must still detect avoidable double disposal by this owner.
11. Keep the current catalog disposed/generation checks. Metadata refresh preserves pending calls/listeners; real replacement detaches old listeners, ignores late old success/error, and directs subsequent calls to the new client.

The registry's publication path is already sufficient: useFleetRegistry subscribes registry.subscribe; restart tears down/spawns and commits; a new entry has a new snapshot. Source: `state/fleet.ts:75-80`, `engine-client/src/registry.ts:221-232`, `:272-284`, `:413-433`. Do not add a refresh counter to stored pairing metadata or force a fake pinDevice write to obtain an effect trigger.

### 2.3 Async race boundaries

The ownership test is by object identity, not elapsed time. A first identity pin during either ListHarnesses or ListModels must preserve that response's right to land. A real replacement while an old call is pending must stop that old catalog's listener notifications and prevent it from overwriting or being adopted by the new session.

The registry is allowed to emit intermediate snapshots during restart. Reconciliation must read current resources rather than caching them from an obsolete snapshot. If a temporary null lookup is observable, omitting the stale entry and rebuilding when resources publish is valid. Tests must exercise eventual recovery without a fleet.engines update.

Do not reset every catalog or call invalidate on metadata pin. That would avoid disposal while still dropping valid requests and recreating the first-pair race.

## 3. Pure logic and mounted regressions

No new model-selection/loading algorithm is required. The small pure decision is:
- Same pairing identity plus same client/cache identities -> retain catalog, optionally replace metadata wrapper.
- Changed pairing identity or current resource pair -> fresh catalog.
- A catalog retained by any next entry -> not disposable.
- No key/wrapper changes -> same sessions map identity.

There is no existing desktop test to port for JavaScript wrapper cleanup. This is a web-specific ownership defect; do not label newly invented tests as desktop tests.

The following test-environment instructions, test matrix, and runtime validation matrix are copied verbatim from research §4.

### 3.1 Test environment and files

The current app package already depends on React 19 and react-dom 19, with Vitest 3 as a dev dependency (`web/packages/app/package.json`). Its `vitest.config.ts:5-6` selects the node environment and only `tests/*.test.ts`. There is no installed DOM-environment dependency in the app manifest, no mounted provider test, and no existing `tests/session-provider.test.ts`. The lockfile's jsdom/happy-dom mentions are Vitest optional peer declarations, not evidence that either environment is installed.

Add jsdom as an app dev dependency and update `web/pnpm-lock.yaml` using the repository package manager during implementation. Resolve a version compatible with the actual Node runtime when implementing; do not invent a pin in advance. Keep all existing tests in node. Put a per-file `// @vitest-environment jsdom` pragma in the new `tests/session-provider.test.ts`, use React `createElement` so the existing .test.ts include pattern needs no change, and mount with `createRoot` from react-dom/client plus `act` from React. No testing-library dependency is required.

Mount the real EngineSessionProvider and consume the real context with a small probe. Use the real createEngineSession and PickerCatalog; tests must not replace either with a no-op catalog or mock out the provider's reconciliation. Mock unrelated routing/notifications/settings dependencies narrowly. A controllable fleet module double may expose useFleet/useFleetRegistry backed by React useSyncExternalStore, with stable snapshots and explicit subscriber notification. Use real EngineStore with in-memory storage for pair/pin metadata transitions, or an equivalent faithful store double that explicitly proves engine-array identity changes. The registry double must expose replaceable client/cache objects and a restart that updates them and publishes only the registry snapshot, leaving fleet.engines identity unchanged. The new registry-subscription dependency is what that test must exercise.

Fake client calls should support deferred harness AND model results, a status event subscription, and observable close/disconnect calls. Fake caches need stable getSnapshot/subscribe behavior sufficient for the real provider's notification driver, or narrowly stub useWatchSnapshot for that unrelated driver. Drive all store emissions, promise settlements, mount, and unmount in act; cleanup roots, listeners, mocks, and document containers. Set the React act-environment flag in this test only, restoring it afterwards. Run the critical first-pin and restart cases under StrictMode as well as ordinary mount.

### 3.2 Regression test matrix

| Proposed test name | File | Setup and decisive assertions |
| --- | --- | --- |
| `first identity pin preserves the live mounted picker catalog` | New `web/packages/app/tests/session-provider.test.ts` | Mount with deviceId null, observe initial catalog, publish same credential and pinned identity; wrapper metadata updates but catalog/client/cache are identical; no catalog disposal; real loadHarnesses/loadModels settle and notify after pin. |
| `identity pin preserves pending harness and model requests` | New `web/packages/app/tests/session-provider.test.ts` | Start deferred loads before pin, pin identity, settle both; retained catalog publishes their rows exactly once and existing subscribers still receive updates. Assert no replacement calls caused solely by metadata refresh. |
| `registry restart replaces session resources without a fleet metadata change` | New `web/packages/app/tests/session-provider.test.ts` | Invoke useEngineRetry from a mounted consumer; fake restart replaces client/cache and emits registry snapshot only. Assert exact same fleet.engines array, new context client/cache/catalog, old catalog disposed once, subsequent requests reach new client only. |
| `registry row updates preserve unchanged sessions and catalogs` | New `web/packages/app/tests/session-provider.test.ts` | Emit several different registry row/status snapshots with unchanged resource objects; session wrapper/context identity stays stable, no new catalogs, no disposal, no resubscription churn caused by wrapper recreation. |
| `re-pair and removal dispose only displaced catalogs` | New `web/packages/app/tests/session-provider.test.ts` | Two engines; replace credential/resources for one then remove it; other catalog stays live. Each displaced catalog disposed once, no provider-owned client teardown. |
| `late results from replaced catalogs cannot affect the new session` | New `web/packages/app/tests/session-provider.test.ts` | Start old deferred harness/model loads, restart, load new rows, then resolve/reject old work; new context and rows stay unchanged, old subscribers receive no landing. |
| `missing registry resources recover on registry publication` | New `web/packages/app/tests/session-provider.test.ts` | Stored engine exists before current client/cache are available; publish resources without changing fleet.engines; provider creates usable session. It never falls back to stale resources. |
| `provider unmount disposes current catalogs without closing registry clients` | New `web/packages/app/tests/session-provider.test.ts` | Unmount after metadata clone and replacement; each still-owned catalog disposed once, already-displaced ones not disposed again, fake client close/disconnect counters unchanged by provider cleanup. |
| `strict lifecycle replay leaves the adopted catalog usable` | New `web/packages/app/tests/session-provider.test.ts` | StrictMode mount/replay, first pin, then real catalog calls settle. No retained session references any catalog disposed during an earlier lifecycle. |
| Existing catalog lifecycle/loading suite | Existing `web/packages/app/tests/picker-catalog.test.ts` | Preserve current `an offline call retries once the engine connects (the reload race)`, `a forced open plus the idle cadence fire exactly one ListHarnesses call`, and `a wedged in-flight load past the 10s bound is superseded, not held for the unary timeout (ticket 61 hole 2)`. |
| Existing loading predicates | Existing `web/packages/app/tests/catalog-loading.test.ts` | Preserve its current open-force and in-flight tables. They supplement, and cannot replace, the mounted-provider regressions. |

No existing Rust test maps to the web-only JavaScript wrapper/catalog ownership bug. Do not invent a desktop test name. The reference contract is the Rust state-machine behavior at `pickers.rs:1028-1040`, `:588-606`, and `:4164-4168`; the proposed test names above are explicitly new web regressions.

### 3.3 Implementation-time validation matrix

| Scenario | Setup | Observable success |
| --- | --- | --- |
| Real first pairing, no cached rows | Fresh browser storage/origin paired to real current engine; provider exists before identity pin; open picker immediately after gate opens. | Harnesses/models settle to rows or a real retryable error; no refresh required; registry stays connected and sidebar remains functional. |
| Pin with request in flight | Controlled integration timing around first EngineInfo/pin and deferred catalog response. | Valid response lands after pin; no permanent skeleton, duplicate transport, or erased listener. |
| Previously paired reload | Identity already persisted; cold page reload, then open/close/reopen picker. | No new lifecycle regression; existing reload/retry behavior remains. |
| Explicit engine Retry | Engine enters failed/off state, use gate Retry while pairing metadata is unchanged. | New registry client/cache are the context's resources; picker queries the replacement connection. |
| Re-pair and removal | Pair same engine again with replacement credential, then remove one of two paired engines. | Replacement adopts fresh resources; remaining engine's catalog is usable; no cross-engine rows. |
| Backend discovery failure | Make model discovery fail or time out with connection otherwise live. | Existing real error/Retry surface remains; no claim that all backend delays were fixed. |
| Desktop/web visual evidence | Desktop and web at desktop width: cold picker open, loaded models, and explicit error/Retry; additionally phone web first-pair smoke. | Screenshots preserve existing card geometry, strings, and loading/error presentation. Attach timing/sequence evidence because a screenshot alone cannot prove resource ownership. |

These are future validation requirements, not tests or runtime observations performed by this research. Parent agent owns any long-running engine/browser verification; implementation subagents must not start browsers, dev servers, cargo run, or web_smoke processes.

### 3.4 Implementation steps and review boundaries

1. Before edits, inspect the baseline provider, engine-session helpers, fleet registry hook, and current test configuration. Confirm no parallel change has already repaired session lifetime.
2. Add the isolated DOM test dependency and mounted provider harness. First encode first-pin and registry-only-restart cases with real PickerCatalog objects; show that baseline behavior fails their liveness/identity assertions. A helper-only unit test is insufficient.
3. Implement current-resource reconciliation and retained-catalog cleanup. Keep scope to the files listed. An explanatory comment should name the exact wrapper-versus-catalog distinction so future metadata fields cannot reintroduce the bug.
4. Add deferred harness/model landing, second engine isolation, re-pair/removal, missing resources, unmount, and StrictMode cases from the copied matrix.
5. Run the new mounted suite and existing catalog/loading/engine-store suites. If these pass, run `pnpm -r build` from web and `pnpm test` from web/packages/app. Record commands and actual results in Comments. Do not repeat broad suites without new changes or failures.
6. Parent agent performs real-engine first-connect/Retry validation and evidence capture using the matrix. Implementation subagents must not start long-running browser/server/cargo processes. Unit success is not permission to claim live first-pair reproduction.
7. Review the diff for accidental localStorage writes, token/credential logging, catalog-retry changes, render-time mutation, eager global test environment changes, and changes outside this ownership slice.

## 4. Gaps this ticket closes

Copied verbatim from research §5.

| Item | Kind | Desktop value | Web value (file:line) | Fix |
| --- | --- | --- | --- | --- |
| Catalog lifetime during metadata pin | WRONG | Live picker resets catalogs only for actual target change and notifies itself (`crates/ui/src/pickers.rs:588-606`). | Same-credential metadata wrapper clone shares catalog, then wrapper-identity cleanup disposes it (`web/packages/app/src/state/session-provider.tsx:65-80`). | Retain catalog by resource ownership through wrapper clones; dispose only unretained catalogs. |
| Registry restart propagation | MISSING | Picker resolves its current engine through selected_target (`crates/ui/src/pickers.rs:697-698`). | Registry replaces client/cache (`web/packages/engine-client/src/registry.ts:221-232`), but provider observes only fleet.engines and reuses by credential (`web/packages/app/src/state/session-provider.tsx:65-87`). | Consume useFleetRegistry publications and compare actual current client/cache identities before reusing a session. |
| Ownership-level regression coverage | MISSING | N/A: JS wrapper ownership is web-specific; desktop reference is lifecycle behavior above. | Existing `web/packages/app/tests/picker-catalog.test.ts` constructs catalogs directly; no mounted provider lifecycle suite exists. | Add mounted provider tests covering first pin, deferred landing, registry-only restart, disposal, and StrictMode replay. |

## 5. Do not

- Do not add polling, timer-based retries, new spinners, or forced refresh on every render. Existing ticket 61 policy is not being redesigned here.
- Do not change or revive disposed catalog instances. Fix the owner that disposed a still-retained resource.
- Do not close/disconnect registry clients or caches from session cleanup. Do not instantiate extra clients in the provider.
- Do not clear/redeem credentials, change origin scoping, mutate persisted pairing schemas, create a data migration, touch real engine data directories, or log real credential/pairing URLs.
- Do not change desktop Rust. Desktop is the lifecycle/cadence reference, and this is a web wrapper-ownership defect.
- Do not silently fix the separate full offered-harness prefetch gap: desktop completion prefetch at `pickers.rs:1080` versus web harness completion at `picker-catalog.ts:299-305`. Record it as follow-up only.
- Do not change Ready([]) model-list presentation. Both clients currently render skeletons for some successful-empty lists (`composer-pickers.tsx:724-733`; `pickers.rs:3373-3400`); that shared behavior is a separate decision.
- Do not expand the passive ten-second in-flight bound into a scheduler. The thirty-second underlying call timeout and slow harness discovery remain possible.
- Do not weaken identity checks or swallow errors to make tests pass; assert actual call targets and successful model/harness landing.
- Do not replace mounted provider tests with catalog-only tests, source-text matching, or screenshots that cannot detect disposed resources.
- Do not move every app test to jsdom or add a second test runner. Use the single-file environment pragma and existing React/Vitest infrastructure.
- Do not handle adjacent navbar geometry, mobile Enter/titlebar, pane motion, transcript replay/scroll, or composer padding in this ticket.

## 6. Acceptance

- [ ] New mounted first-pin regression demonstrably fails on baseline due to disposal of the retained catalog, then passes with the fix.
- [ ] Same-credential identity metadata update changes exposed metadata while preserving catalog/client/cache identities and existing subscriptions.
- [ ] Deferred harness and model results started before metadata pin land successfully afterward without forced replacement calls.
- [ ] Registry Retry with exactly unchanged fleet.engines identity publishes a session bound to the registry's new client/cache and a fresh usable catalog.
- [ ] Ordinary connection/status/row publications with identical resources preserve session and sessions-map identity; no gratuitous catalog creation or disposal.
- [ ] Re-pair, removal, temporary missing resources, and eventual registry publication follow the state table.
- [ ] Displaced catalogs are disposed once; retained catalogs are not disposed; final cleanup does not dispose displaced catalogs again.
- [ ] Old pending successes/rejections cannot update the new session; unmount and StrictMode replay leave no adopted disposed catalog or stale listeners.
- [ ] Provider cleanup never closes registry-owned clients/caches. Cross-engine catalog state remains isolated.
- [ ] All proposed mounted regression cases in §3 are implemented; existing named catalog/loading tests remain green.
- [ ] New DOM dependency/lockfile are consistent; node remains the default test environment and existing .test.ts discovery remains unchanged.
- [ ] Real first-pair sequence and explicit Retry are observed against a current engine without refresh; capture timing/ownership evidence and note browser/build/engine setup.
- [ ] Desktop/web screenshot pairs show cold-open, loaded-model, and error/Retry states at desktop widths; phone first-pair smoke has no presentation regression. If runtime evidence is unavailable, record exactly what remains unverified instead of checking this box.
- [ ] `pnpm -r build` from web and package `pnpm test` pass; Comments record actual commands/results and any unrelated known flakes.
- [ ] No new user-visible text, CSS geometry, literal colors/px, motion, keyboard behavior, credentials, persisted formats, or desktop code changes.
- [ ] Comments distinguish code-proven ownership repair from any remaining backend discovery/empty-success loading symptoms; excluded follow-ups are not claimed solved.

## Comments

Research/ticket preparation only. No implementation, dependency installation, test execution, runtime reproduction, or screenshot capture has occurred at authoring time.
