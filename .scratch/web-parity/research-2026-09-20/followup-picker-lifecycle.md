# Follow-up research — picker session resource lifetime

Date: 2026-09-20. Baseline: `web-parity/wave-2` at `37c354ff`.
Scope: user report 3, "the model picker first time connect still loading infinitely".
Method: read-only source analysis of the real web and desktop implementations and existing research/tickets. No runtime reproduction, builds, tests, servers, browsers, or code changes were performed. This research and its companion ticket are the only artifacts authored for this slice.

Read with the settled vocabulary in `CONTEXT.md`: chat, space, engine, harness; Session is the pairing credential. Internal EngineSession is the existing code type, not new user-facing terminology.
Companion ticket: `../issues/67-picker-session-resource-lifetime.md`.

## 1. Finding and evidence boundary

A metadata update can dispose the PickerCatalog still held by the replacement EngineSession wrapper. This is a source-proven ownership defect, not a conjectured RPC timeout. A fresh pairing supplies precisely that metadata update when its device identity is pinned after connection.

The reported first-pair timing has NOT been reproduced against the user's running engine. The causal sequence requires the provider to have created the initial session before the first identity pin. That ordering is supported by the provider being outside the gate and the registry awaiting cache seeding before dialing, but this research did not observe the browser's event schedule.

The result can be a working connection/sidebar with a permanently unusable picker. Catalog retries cannot repair an object deliberately marked disposed. The defect's ordering also explains why refresh can repair it: the device identity is then already persisted and the same-identity pin becomes a no-op.

### First-pair causal chain

1. `EngineStore.redeemPairingUrl` creates and stores an entry with `deviceId: null` (`web/packages/app/src/lib/engine-store.ts:133-149`).
2. Fleet syncs the registry when the store changes (`state/fleet.ts:49-55`). The registry creates the client/cache immediately, but connects after asynchronous cache seeding (`web/packages/engine-client/src/registry.ts:237-282`).
3. EngineSessionProvider is outside GateAndPage (`routes/root-layout.tsx:30-34`), so it can create a session/catalog while the child page is still gated (`state/session-provider.tsx:60-76`). The provider is not conditional on the engine being connected.
4. When the registry reports connected with EngineInfo, fleet calls pinDevice (`state/fleet.ts:58-62`). pinDevice replaces the StoredEngine object and engines array, then persists/notifies (`lib/engine-store.ts:171-178`, `:234-245`).
5. Provider reconciliation re-runs because its dependency is fleet.engines (`state/session-provider.tsx:87`). Same credential causes `{ ...existing, engine }` (`:65-69`), which changes wrapper identity but shares client, cache, and catalog.
6. The following cleanup compares wrappers using `next.get(key) !== session` and invokes disposeEngineSession (`:78-80`). That function calls only catalog.dispose (`state/engine-session.ts:40-42`); it does not close the shared engine connection.
7. Catalog disposal sets irreversible #disposed, removes status/listener subscriptions, and clears model slots (`state/picker-catalog.ts:472-480`). The new wrapper still owns that same now-dead catalog.
8. Later loadHarnesses/loadModels calls immediately return (`:275-277`, `:337-339`); pending results are ignored by disposed/generation guards (`:300-308`, `:329-330`). An initial Idle/Loading harness slot or now-missing model slot can render an endless skeleton (`components/composer-pickers.tsx:634-639`, `:724-733`).
9. Refresh recreates resources. Because pinDevice persisted the identity, an identical pin on reload returns without rewriting the stored engine (`lib/engine-store.ts:172-173`). The metadata-disposal path need not repeat.

### Related registry-restart defect

The current engine Retry callback calls engineRegistry.restart (`state/session-provider.tsx:111-126`). Restart tears down and replaces the client/cache even when pairing URL and credential are unchanged (`engine-client/src/registry.ts:221-232`). The provider depends only on fleet.engines and reuses by credential, so it can retain an obsolete client after Retry. This is the same resource-identity/lifetime slice and is included in ticket 67.

The concrete existing notification source is `useFleetRegistry` (`state/fleet.ts:75-80`). It subscribes directly to registry snapshots. Restart creates a new entry with snapshot null (`registry.ts:272`), its spawn/restart commits produce changed entry snapshots, and registry publishes them (`:284`, `:413-433`). Therefore the fix can use the existing feed without adding polling, a new transport event, or stored metadata churn.

## 2. Desktop comparison and bounded repair design

The older research/ticket commentary must not be treated as exact desktop behavior where it says the desktop forces every render. Actual code:
- Picker open forces harness/model refresh: `crates/ui/src/pickers.rs:1010-1018`.
- Every render calls non-forced ensure_harnesses(false) and prefetch_models(false): `:4164-4168`.
- Non-forced Error reload is deliberately blocked so Retry can paint: `:1028-1040`.
- Target changes reset to Idle/clear model slots and notify the living picker entity: `:588-606`.
- Engine calls resolve through selected_target: `:697-698`.

Ticket 67 repairs web resource ownership, with no new loading cadence or desktop code changes:

1. Subscribe the provider to useFleetRegistry in addition to useFleet.
2. Reconcile after either stored-engine metadata or registry snapshot changes.
3. For each stored engine, obtain CURRENT clientFor(baseUrl) and watchCacheFor(baseUrl). If either is null, do not retain an obsolete session.
4. Reuse a catalog only if base URL/credential and both actual resource identities still match. For identical StoredEngine metadata object, reuse the whole wrapper. For metadata-only change, clone the wrapper and retain its live resources.
5. For new credentials/resources, createEngineSession with the current client/cache.
6. Build the complete next map before deciding disposal. Make a Set of catalogs retained by next. For each distinct previous catalog not retained, dispose once. Wrapper inequality is never the cleanup predicate.
7. Preserve map identity and skip setSessions when every key and wrapper is unchanged. Registry row/status emissions then cost a comparison, not new session ownership.
8. Publish the new map/ref within the existing effect discipline. Keep resource creation/disposal out of render; do not return per-update cleanup that destroys resources before metadata reconciliation.
9. Unmount disposes current distinct catalogs and clears the map ref. StrictMode effect replay must rebuild rather than adopt a disposed catalog. Registry owns sockets/watch-cache teardown; provider never closes them.
10. Keep existing disposed/epoch guards on asynchronous catalog work. Old responses after real replacement are ignored; valid responses across metadata-only refresh still land.

A small pure planning helper in engine-session.ts is optional if it makes the diff readable; the required mounted-provider tests must exercise the production subscription and effect ordering even if helper tests also exist. Do not expand this into a general store rewrite.

## 3. Source, state, presentation, and data contracts

### 3.1 Source and ownership contracts

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

### 3.2 State contract

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

### 3.3 Layout, motion, interaction, and text contract

| Category | Value | Source or reason |
| --- | --- | --- |
| Layout | N/A: no geometry, CSS, tokens, panel hierarchy, or DOM children change. | Lifecycle defect lives in `state/session-provider.tsx:60-87`. |
| Existing card children | Harness skeleton, harness error/Retry, no-agents state, then tabs/search/model list/traits retain their current order. | `components/composer-pickers.tsx:630-659`, `:661-718`, `:724-737`. |
| Loading visuals | Preserve five-row skeleton styling. Fix resource liveness so requests can settle. | `components/composer-pickers.tsx:634-639`, `:731-733`; desktop `crates/ui/src/pickers.rs:3394-3400`. |
| Interactions | Opening/reopening picker, model selection, explicit Retry, and engine-gate Retry retain current controls. Engine-gate Retry must bind the newly created registry resources. | `composer-pickers.tsx:242-247`, `:342-345`; `session-provider.tsx:111-126`. |
| Motion | N/A: no trigger, duration, easing, from/to geometry, or reduced-motion rule changes. | State ownership repair only. |
| Text | N/A: add no strings. Existing error rows and no-agents copy remain unchanged. | `composer-pickers.tsx:641-654`, `:724-733`. |
| Keyboard and phone | N/A: no key handlers or mobile presentation changes. First-pair and restart resource lifetime applies equally at all viewport sizes. | Shared `EngineSessionProvider`. |

### 3.4 Data, RPC, and security contract

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

## 4. Tests and validation

### 4.1 Test environment and files

The current app package already depends on React 19 and react-dom 19, with Vitest 3 as a dev dependency (`web/packages/app/package.json`). Its `vitest.config.ts:5-6` selects the node environment and only `tests/*.test.ts`. There is no installed DOM-environment dependency in the app manifest, no mounted provider test, and no existing `tests/session-provider.test.ts`. The lockfile's jsdom/happy-dom mentions are Vitest optional peer declarations, not evidence that either environment is installed.

Add jsdom as an app dev dependency and update `web/pnpm-lock.yaml` using the repository package manager during implementation. Resolve a version compatible with the actual Node runtime when implementing; do not invent a pin in advance. Keep all existing tests in node. Put a per-file `// @vitest-environment jsdom` pragma in the new `tests/session-provider.test.ts`, use React `createElement` so the existing .test.ts include pattern needs no change, and mount with `createRoot` from react-dom/client plus `act` from React. No testing-library dependency is required.

Mount the real EngineSessionProvider and consume the real context with a small probe. Use the real createEngineSession and PickerCatalog; tests must not replace either with a no-op catalog or mock out the provider's reconciliation. Mock unrelated routing/notifications/settings dependencies narrowly. A controllable fleet module double may expose useFleet/useFleetRegistry backed by React useSyncExternalStore, with stable snapshots and explicit subscriber notification. Use real EngineStore with in-memory storage for pair/pin metadata transitions, or an equivalent faithful store double that explicitly proves engine-array identity changes. The registry double must expose replaceable client/cache objects and a restart that updates them and publishes only the registry snapshot, leaving fleet.engines identity unchanged. The new registry-subscription dependency is what that test must exercise.

Fake client calls should support deferred harness AND model results, a status event subscription, and observable close/disconnect calls. Fake caches need stable getSnapshot/subscribe behavior sufficient for the real provider's notification driver, or narrowly stub useWatchSnapshot for that unrelated driver. Drive all store emissions, promise settlements, mount, and unmount in act; cleanup roots, listeners, mocks, and document containers. Set the React act-environment flag in this test only, restoring it afterwards. Run the critical first-pin and restart cases under StrictMode as well as ordinary mount.

### 4.2 Regression test matrix

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

### 4.3 Implementation-time validation matrix

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

## 5. Gap table for ticket 67

| Item | Kind | Desktop value | Web value (file:line) | Fix |
| --- | --- | --- | --- | --- |
| Catalog lifetime during metadata pin | WRONG | Live picker resets catalogs only for actual target change and notifies itself (`crates/ui/src/pickers.rs:588-606`). | Same-credential metadata wrapper clone shares catalog, then wrapper-identity cleanup disposes it (`web/packages/app/src/state/session-provider.tsx:65-80`). | Retain catalog by resource ownership through wrapper clones; dispose only unretained catalogs. |
| Registry restart propagation | MISSING | Picker resolves its current engine through selected_target (`crates/ui/src/pickers.rs:697-698`). | Registry replaces client/cache (`web/packages/engine-client/src/registry.ts:221-232`), but provider observes only fleet.engines and reuses by credential (`web/packages/app/src/state/session-provider.tsx:65-87`). | Consume useFleetRegistry publications and compare actual current client/cache identities before reusing a session. |
| Ownership-level regression coverage | MISSING | N/A: JS wrapper ownership is web-specific; desktop reference is lifecycle behavior above. | Existing `web/packages/app/tests/picker-catalog.test.ts` constructs catalogs directly; no mounted provider lifecycle suite exists. | Add mounted provider tests covering first pin, deferred landing, registry-only restart, disposal, and StrictMode replay. |

## 6. Exclusions and future investigation

- Full offered-harness model prefetch is a separate parity gap. Desktop prefetches after harness completion (`pickers.rs:1080`) and every render (`:4168`); web publishes harness rows (`picker-catalog.ts:299-305`), ensures the effective harness in composer (`components/composer.tsx:614-624`), and prefetches on open/refire (`composer-pickers.tsx:242-247`). Record it, do not silently change it in this ownership ticket.
- A successful empty model list can still render a skeleton in BOTH clients (`composer-pickers.tsx:724-733`; `pickers.rs:3373-3400`). Unknown-method fallback can create an empty success on web. Distinguish that settled-empty state from a disposed catalog during runtime validation; a shared empty-state behavior change requires a separate decision.
- Ticket 61's ten-second in-flight limit is a passive re-kick eligibility rule, not a scheduled deadline (`lib/catalog-loading.ts:68-97`). An untouched Loading slot still normally reaches the thirty-second unary timeout (`engine-client/src/client.ts:81`, `:548-559`). Do not add timers or broader retry policy here.
- Model discovery can genuinely be slow/fail; engine ListModels awaits harness discovery (`crates/engine/src/rpc.rs:897-907`). The ownership fix does not claim to repair all backend discovery issues.
- No composer Enter, mobile titlebar, transcript replay, scroll runway, pane motion, navbar geometry, or sidebar-performance work belongs in this ticket.
- No saved credentials, real pair URLs, or private engine data should be copied into these research artifacts.
