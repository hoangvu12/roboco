# 31 — Fleet

**What to build:** A user who has paired more than one engine sees every
paired engine's chats, spaces, and devices merged into one sidebar at once
— not just the single "active" engine's rows. Opening a chat that lives on
a different engine than the one currently "focused" just works, with no
manual switch step. A paired engine that goes offline shows its last-known
rows (from a local cache) instead of going blank, and reconnects on its own
with the same backoff curve the desktop uses. Pairing, forgetting, and
renaming devices (ticket 29's Devices page) operate against this same
registry. This is the last ticket in the parity effort because every other
surface (sidebar, composer, transcript, settings) currently assumes "the
one connected engine" and must not break when that assumption is lifted.

**Blocked by:** 05 (State fixes: nav history, send ids, optimistic echo), 08 (Sidebar)

**Status:** ready-for-agent

**Research:** `../../web-client/research/14-state-behavior.md` §2, §3.3, §3.4, §3.5, §4.3 (the `device_online`/`space_device_tag` rows only, for the sidebar-grouping data this ticket must supply), §5 (rows: "Multi-engine simultaneous connection", "Offline row/transcript cache", "`ScopedId` cross-engine id scheme", "Device online/presence derivation", "`space_device_tag`"), §6.

**Desktop reference (for lookups only):** `crates/ui/src/engine_registry.rs` (835 lines, full) + `crates/ui/src/engine_registry/tests.rs` (full, 3 tests), `crates/ui/src/engine_cache.rs` (205 lines, full), `crates/ui/src/request_routing.rs` (356 lines, full), `crates/ui/src/state.rs` (`EngineTarget`, `selected_target`, `device_target`, `device_online`, `space_device_tag` — excerpts), `crates/ui/src/settings/devices.rs` (`DEVICE_ONLINE_WINDOW_SECS`, `device_online`, `format_last_seen`, `presence_dot` — already ported by ticket 29's `lib/devices.ts`, reused here).

**Web files to touch:**
| File | Change | Owns |
| --- | --- | --- |
| `web/packages/engine-client/src/scoped-id.ts` | new | `SCOPED_ID_PREFIX`, `encodeScopedId`, `parseScopedId` |
| `web/packages/engine-client/src/registry.ts` | new | `EngineRegistry`-equivalent: owns one `EngineClient` + `ReconnectBackoff` per paired engine, drives all of them concurrently, exposes a merged snapshot |
| `web/packages/engine-client/src/request-routing.ts` | new | `wireParams`-equivalent: decode `ScopedId`-shaped fields before a call leaves for a specific engine, reject cross-engine ids, strip `targetDeviceId` |
| `web/packages/engine-client/src/client.ts` | edit | confirm/extend the existing single-connection backoff (`ReconnectBackoff`, already numerically correct per `backoff.ts`) is reusable per-engine inside the new registry rather than assuming exactly one instance app-wide |
| `web/packages/app/src/lib/engine-store.ts` | edit | `EngineStore` gains whatever the registry needs beyond today's pairing-storage role (it already does redeem/persist/setActive/remove/pinDevice) — this ticket wires it to drive *every* stored engine's connection, not just `fleet.active` |
| `web/packages/app/src/state/fleet.ts` | edit | expose the merged `RegistrySnapshot`-equivalent alongside today's `FleetState` (pairing list), so sidebar/composer/etc. can read "every chat across every engine" without knowing which engine owns which row |
| `web/packages/app/src/state/session-provider.tsx` | edit | replace the single `EngineSession` context with a registry-backed one that keeps a session alive per paired engine, not just `fleet.active`; `useEngineSession()`'s existing call sites should keep working for "the currently routed engine for this chat" via the new request-routing layer |
| `web/packages/app/src/lib/view.ts` | edit | `toChatRow`/`chatListRows` etc. need the offline-aware `spaceDeviceTag` (currently missing the `offline` flag entirely per research 14 §5) |
| `web/packages/app/src/components/engine-drawer.tsx` | edit or delete (decide per §2.6 below) | the user-menu device/engine list — this ticket resolves its long-deferred fate |
| `web/packages/app/tests/scoped-id.test.ts`, `registry.test.ts` | new | port the 3 desktop test names (§3) plus new web-specific cache/offline tests |

## 1. Context a fresh session needs

- Today the web drives **exactly one** engine connection at a time
  (`fleet.active` in `lib/engine-store.ts`, one `EngineSession` in
  `state/session-provider.tsx`). Every other engine you have paired with
  is fully disconnected while it is not the active one. The desktop
  connects to **every** paired engine simultaneously
  (`EngineRegistry::supervise`) and merges their rows into one flat
  namespace the sidebar renders without knowing which engine owns which
  row. This ticket closes that gap. It is explicitly the last ticket
  (spec.md decision 1) because every other surface currently assumes
  single-engine and must be re-checked once rows can come from more than
  one source.
- The existing single-connection building blocks are already correct and
  should be reused, not rewritten: `EngineClient`
  (`engine-client/src/client.ts`) already implements dial → auth →
  identity → subscribe-all → health-tick → reconnect with the right
  states (`connecting`/`connected`/`reconnecting`/`parked`/`closed`), and
  `ReconnectBackoff` (`engine-client/src/backoff.ts`) already implements
  the exact desktop numbers (500ms initial, ×2 growth, 15000ms cap,
  0–255ms jitter, reset after a 10s-plus connection). This ticket's job
  is to run **one instance of each per paired engine** and merge their
  outputs, not to reimplement connection mechanics that already match.
- `EngineWatchCache` (`engine-client/src/watch-cache.ts`) already has the
  right shape for a *single* engine's rows (`RowSet<T>` per collection,
  `WatchCacheSnapshot`). The new registry layer sits **above** one
  `EngineWatchCache` per engine and merges their snapshots — do not
  rewrite `EngineWatchCache` itself to be multi-engine-aware; keep it
  single-engine and compose.
- `lib/engine-store.ts`'s `EngineStore` already covers the *pairing
  storage* half correctly (redeem, persist to `localStorage
  ["roboco.fleet.v1"]`, `setActive`, `remove`, `pinDevice`). This ticket
  does not change its persistence shape — it changes what consumes it:
  today only `fleet.active` gets a live connection; after this ticket,
  every stored engine does.
- Vocabulary: "engine" is a paired backend; "device" is a machine
  registered with one engine; "chat"/"space" as elsewhere. A `ScopedId` is
  a client-local wire-format wrapper that tags a raw id with which engine
  it belongs to — it is never sent to any engine's own storage, it exists
  purely so the web's merged sidebar can dispatch "open this chat" back to
  the correct engine.

## 2. Spec

### 2.1 `ScopedId` cross-engine id scheme

Copy this scheme **verbatim** — it is 100% client-local (no server-side
coordination needed), but must be internally consistent so the same id
round-trips correctly across renders and reloads:

- **`PREFIX`**: the literal string `"engine:v1:"` marks a cross-engine-
  scoped id.
- **Encode** (`ScopedId::encode`): only ever emit the prefixed form for a
  **non-local** engine — i.e., in the web's model, for any engine that
  is not the sole/default one. Since the web has no "local engine"
  concept the way desktop does (every web engine is remote), treat the
  **first-ever paired engine** or a designated "primary" as the
  unscoped-by-convention case if you need one at all — or, simpler and
  equally correct: scope **every** id uniformly regardless of which
  engine it came from, since the web has no bare "local" engine to leave
  unscoped in the first place. Pick the simpler uniform-scoping approach
  unless it breaks an existing call site that assumes an unscoped id from
  the single-engine era; document the choice in Comments.
- Exact wire format: `PREFIX + base64url_no_pad(JSON.stringify([engineKey, rawId]))`
  — i.e. `"engine:v1:"` followed by unpadded base64url of the JSON array
  `[engineKey, rawId]`.
- **Parse** (`ScopedId::parse`): strip the prefix, base64url-decode,
  JSON-deserialize back to `[engineKey, rawId]`. An id with no `PREFIX`
  parses as belonging to whatever the "default" engine convention above
  resolves to.
- Base64url alphabet: `-`/`_` in place of `+`/`/`, **no padding**
  (`=` characters stripped) — use the same alphabet a `URL_SAFE_NO_PAD`
  base64 codec would produce; if the JS runtime's built-in base64 helpers
  pad, strip the padding explicitly.

**Test**: `scopedIdentityCodecIsCollisionSafe` — port desktop's
`scoped_identity_codec_is_collision_safe` (`engine_registry/tests.rs:40`):
two different `(engineKey, rawId)` pairs must never encode to the same
scoped string, and an id already carrying `PREFIX` under one engine must
not collide with the same raw bytes scoped under a different engine.

### 2.2 `EngineRegistry`-equivalent shape

```ts
interface EngineRegistrySnapshot {
  readonly engines: readonly EngineEntrySnapshot[];
  readonly configurationError: string | null;
}

interface EngineEntrySnapshot {
  readonly key: string; // the engine's baseUrl, matching StoredEngine.baseUrl
  readonly info: EngineInfo | null;
  readonly state: "connected" | "reconnecting" | "off";
  readonly lastError: string | null;
  readonly generation: number;
  readonly chats: RowSet<Chat>;
  readonly spaces: RowSet<Space>;
  readonly devices: RowSet<Device>;
  readonly sessions: RowSet<ChatStatus>;
}
```

`projected()` — the merge function the sidebar/composer/everything else
actually reads: scope every row's id via `encodeScopedId(engine.key, id)`
(chats, spaces, devices, sessions) and concatenate across every entry, so
rows from every paired engine land in **one flat list** with no per-row
indication of which engine they came from baked into the UI (only into
the id, which routing decodes when a request needs to go back out).

**States**

| State | Condition | What changes |
| --- | --- | --- |
| `connected` | dial + identity verify + all watch subscriptions succeeded | rows flow, `generation` increments |
| `reconnecting` | dial/handshake failed, not a permanent refusal | `client = null`, `lastError` set, the per-engine backoff loop retries |
| `off` | a close reason contains `401`, `403`, or an identity-changed signal — "refused" | connection abandoned **permanently** for that engine key; the retry loop stops entirely, matching the web's existing single-engine `EngineClient`'s "parked is permanent for a client instance" rule |
| Configuration error | the persisted pairing list (`roboco.fleet.v1`) fails to parse | `configurationError` set; pairing/persisting refuses until repaired; **existing bytes are preserved untouched** — never overwrite a damaged-but-present storage value with a blank one |

**Interactions**:
- `pair(pairingUrl, label)` — reuse `EngineStore.redeemPairingUrl`
  verbatim (already correct); after it resolves, the registry must start
  supervising the new engine immediately, not wait for it to become
  "active".
- `forget(key)` — reuse `EngineStore.remove` for the persisted-list half;
  additionally: abort that engine's connection task, flush any pending
  cache writes for it, **then** delete its cache directory/keys.
  **Ordering matters**: task-cancel → flush → delete, so an in-flight
  cache save can never race a `remove`.
- `shutdown()` — park every entry (stop retrying, close sockets), flush
  every pending cache write. Call this on page unload
  (`beforeunload`/`pagehide`) so a cache write in flight is not lost.

**Connection lifecycle numbers** (already correct in `backoff.ts` for one
engine — this ticket's job is to instantiate one `ReconnectBackoff` per
engine key, not to change these numbers):

| Constant | Value | Source |
| --- | --- | --- |
| Initial backoff delay | 500ms | `engine_registry.rs` `supervise()` |
| Backoff growth | ×2 per retry | same |
| Backoff cap | 15,000ms | same |
| Jitter | 0–255ms, from one random byte | same |
| Backoff reset threshold | connection lived > 10s | same |
| Identity/health-check timeout | 10s (identity call), 5s (health tick call) | same |
| Subscribe-all timeout | 15s | same |
| Unary call timeout | 30s | `EngineTarget::call` default |
| Long call timeout (method name contains `Clone`/`Fetch`) | 900s | same |
| Health-check tick interval | 5s | same |

**Park-on-revoked**: identical rule to the existing single-engine
`EngineClient` — a close reason signaling 401/403/identity-changed sets
that engine's state to `off` and stops all future retries for it. No
change needed to the per-connection logic; just make sure the registry
does not restart a parked entry on its own (only an explicit re-pair from
the Devices page, ticket 29, should ever move an entry out of `off`).

**Identity re-verification**: every (re)connect re-fetches engine
identity before trusting the connection; every health tick re-checks the
device id matches what was pinned at first connect — a mismatch mid-
connection is treated exactly like a rejected pairing (same `off` +
permanent-stop behavior).

**Per-engine routing** (`request-routing.ts`): before a call/subscribe
leaves for a specific engine, decode every `ScopedId`-shaped field
(`chatId`, `spaceId`, `deviceId`, `checkoutId`, `expectedCheckoutId`,
`docId`, `parentChatId`, `targetDeviceId`, plus `target.*` and `Mutate`'s
`id`) back to that engine's raw id. **Reject** the call (a client-side
error, never sent over the wire) if a decoded id belongs to a different
engine than the one the call is being routed to —
`"Request identity belongs to another engine"`. Always strip
`targetDeviceId` before the request actually leaves the client — routing
ends at the socket; no engine forwards a request on to another engine or
device on the wire.

**Offline cache reads**: on registry startup, seed every entry's
`chats`/`spaces`/`devices`/`sessions` synchronously from the local cache
(§2.3) **before** dialing any socket, and mark those collections
`loaded: true` immediately — the sidebar must render last-known rows
instantly while connections dial in the background, not show a blank
list until the first live frame arrives. Writes to the cache flow through
one FIFO queue per engine so out-of-order saves can never regress the
persisted data; `forget`/`shutdown` must flush and await that queue before
touching the cache's storage keys.

### 2.3 `EngineCache`-equivalent (offline reads)

**What is cached**: per paired engine, the last-known `chats`, `spaces`,
`devices`, `sessions` row sets, plus a per-chat transcript cache (the
last-seen `SessionMessageEntry[]` for chats the user has actually opened
— not every chat, to bound storage).

**Where**: IndexedDB (preferred over `localStorage` for this — row sets
and transcripts can be large, and `localStorage`'s synchronous API and
5–10MB-ish quota are a poor fit). Key structure mirrors the desktop's
`data_dir/engine-cache-v1/<sha256(engineKey)>/{rows.json,
chat-<sha256(chatId)>.json}` shape conceptually: one IndexedDB object
store keyed by `(engineKey, collection)` for rows, and one keyed by
`(engineKey, chatId)` for transcripts. Hashing the key before storage is
not required on web (IndexedDB keys are not filesystem paths), but keep
the same **granularity** — one row-set entry per engine, one transcript
entry per chat.

**Staleness**: the cache is a read-through fallback with no separate
expiry — it is overwritten every time a fresher frame arrives from the
live watch, and is only ever read at startup (before the first live
frame) or while an engine is in `reconnecting`/`off` state. It does not
need a TTL; "staleness" is communicated to the user implicitly by that
engine's connection-state badge (ticket 08's sidebar grouping renders
this), not by hiding or graying the cached rows themselves.

**Writes**: atomic-enough for a browser context — write the full
collection/transcript blob in one `IndexedDB` transaction per save, not
incrementally, so a mid-write failure cannot leave a half-updated row set.
Debounce writes (a few hundred ms) so a burst of watch frames does not
thrash storage.

**`forgetEngine(key)`**: delete every stored entry for that engine key
(both the row-set entry and every transcript entry) — tolerate the
entries already being absent (no error if nothing to delete).

Web gap this closes (research 14 §5, "Offline row/transcript cache"):
currently **entirely MISSING** — `EngineWatchCache`/`TranscriptStore` are
memory-only; a page reload starts every collection blank until the
engine's streams answer again. This directly contradicts the
architecture's stated "cached session lists and open transcripts support
offline reading" for the web client specifically.

### 2.4 `WatchConnectivity`/`WatchTransfers` streams

These are **not** newly built by this ticket for their own sake — ticket
30 already adds `WatchConnectivity` for the notification engine's use.
This ticket's obligation is narrower: make sure the per-engine connection
model this ticket builds exposes connectivity **per engine**, not a
single global value, since with multiple engines connected simultaneously
each can independently be online/reconnecting/offline. If ticket 30 has
already landed a single-engine `WatchConnectivity` wiring by the time
this ticket starts, extend it to be keyed by engine rather than assuming
one global connectivity value. `WatchTransfers` (relay-leg upload
progress) has no dedicated consumer yet on web (composer/queue surfaces'
concern) — this ticket only needs to note that, once added, it too must
be engine-scoped, not wire it up itself.

### 2.5 How the sidebar groups by device (data only — ticket 08 renders)

This ticket supplies the **data**: `EngineRegistrySnapshot::projected()`
gives the sidebar one flat, scoped-id-tagged row list per collection,
exactly like a single engine's `WatchCacheSnapshot` today, so ticket 08's
existing grouping/sorting logic (`lib/view.ts`'s `sortRows`, `groupChats`
if/when used, etc.) does not need to know engines exist at all — it just
operates over more rows. The one new thing ticket 08 will need from this
ticket is `spaceDeviceTag`'s offline flag:

`spaceDeviceTag(device, now) -> { label: string, offline: boolean }` —
`label = "@ {device.name ?? 'Unknown device'}"`, `offline =
!deviceOnline(device, now)` where `deviceOnline` is ticket 29's already-
ported `lib/devices.ts::deviceOnline` (70s window,
`DEVICE_ONLINE_WINDOW_SECS`), except: a device backed by a known
**registry** engine (i.e., one of this ticket's `EngineEntrySnapshot`s)
reports that engine's connection state (`connected` → online, else
offline) **instead of** the raw last-seen-timestamp heuristic — the live
connection state is more accurate than a heartbeat timestamp when you
actually have a supervised connection to that engine. Fix `lib/view.ts`'s
`toChatRow`, which today composes `"{project} @ {device.name}"` with no
offline flag at all (research 14 §5, "`space_device_tag`" row).

### 2.6 How Devices settings pairs/forgets (ticket 29)

Ticket 29's Devices page pairing box and Forget action must call into
**this ticket's** registry (`pair`/`forget` from §2.2), not directly into
`EngineStore`'s raw persistence methods — the registry wraps
`EngineStore`'s persistence with "also start/stop supervising the
connection," which ticket 29 needs but should not reimplement. If ticket
29 lands first and calls `fleetStore.redeemPairingUrl`/`.remove()`
directly (the only thing available at the time), this ticket must audit
those call sites and redirect them to the new registry's `pair`/`forget`
once it exists, so pairing a device from Settings actually starts a live
connection immediately rather than waiting for some other trigger to
notice a new stored engine.

### 2.7 `engine-drawer.tsx`'s fate

`components/engine-drawer.tsx` has **no direct desktop line-for-line
counterpart** — it is the web's own answer to "which engine(s) am I
paired with, and which is active," reached from the sidebar's user menu
(`account-row.tsx`'s "Engines" item, `emitShortcut("open-engines")`). It
is architecturally closer to the desktop's *implicit* engine registry
(the thing `settings/devices.rs`'s pairing box calls into, via
`registry()`, but that desktop page itself never renders a full
engine-list UI) than to `settings/devices.rs` itself (which lists
*devices within one engine*, not *which engines are paired*).

**Decision for this ticket**: once the fleet model lands and the sidebar
shows every paired engine's chats merged together, the desktop no longer
has a per-window "which engine is active" concept at all — every engine
is always active simultaneously. This makes `EngineDrawer`'s "Switch"
action (making one engine "the" active one) meaningless in the new model:
there is no more single active engine to switch to. Two defensible
outcomes, pick one and document the choice in Comments before writing
code:

1. **Keep it, repurposed** — as the user-menu's device/engine list,
   dropping "Switch" (no longer meaningful) and keeping "Add engine"
   (pairing), "Pair again" (re-verify a parked engine), and "Forget",
   each now operating on the shared registry from §2.2 instead of the
   single-active-engine `EngineStore`. This matches the desktop's own
   user-menu pattern most closely (Settings → Devices is a *different*,
   per-engine page; the user menu's own quick list is the closer analog
   of what `EngineDrawer` already does).
2. **Delete it** — fold "pair a new engine" entirely into ticket 29's
   Devices settings page pairing box (which already exists and already
   does the same redeem-by-URL flow), and let the user menu's "Engines"
   item simply navigate to Settings → Devices instead of opening a
   separate drawer.

Given `Settings → Devices` (ticket 29) already has its own pairing box
and device list per engine, and the desktop truly has no second "which
engines am I paired with" UI outside of Settings, **option 2 (delete,
redirect to Settings → Devices) is the closer parity fit** — but this is
explicitly the researcher's/team's call to make, so treat it as this
ticket's decision to finalize, not something to leave ambiguous. If
option 2 is chosen: delete `engine-drawer.tsx`, remove its import from
wherever it is mounted (search for `EngineDrawer` usage), and change
`account-row.tsx`'s "Engines" menu item to navigate to `/settings/devices`
instead of `emitShortcut("open-engines")`. If option 1 is chosen: keep
the file, strip the "Switch" action, and rewire its data source to the
new registry.

## 3. Pure logic to port

- `encodeScopedId`/`parseScopedId` — §2.1's exact scheme. Test:
  `scopedIdentityCodecIsCollisionSafe` (from
  `engine_registry/tests.rs::scoped_identity_codec_is_collision_safe`).
- `EngineRegistrySnapshot::projected()` — the merge-and-scope function,
  §2.2. No dedicated desktop unit test beyond the two integration tests
  below exercising it indirectly; add a focused web unit test
  `projectedMergesRowsFromEveryPairedEngineUnderScopedIds`.
- `wireParams` (request-routing) — the decode/reject/strip rules in
  §2.2's "Per-engine routing" paragraph. Add
  `wireParamsRejectsRequestForAnotherEngine` and
  `wireParamsAlwaysStripsTargetDeviceId` as new web tests (no single
  desktop test name maps 1:1 — `request_routing.rs`'s own test suite was
  not in this ticket's reading list; use these two descriptive names).
- `spaceDeviceTag` — §2.5's offline-aware tag. No isolated desktop test
  name found in the research; add `spaceDeviceTagReflectsLiveEngineState`
  and `spaceDeviceTagFallsBackToLastSeenWindow`.
- Full desktop `engine_registry/tests.rs` checklist (every test in that
  file, per the assignment) — port the two integration-style tests as
  web integration tests against a mock/fake engine transport:
  - `scoped_identity_codec_is_collision_safe` → `scopedIdentityCodecIsCollisionSafe` (unit-level, no transport needed).
  - `real_engine_reconnect_retains_rows_persists_pairing_and_forgets` → `realEngineReconnectRetainsRowsPersistsPairingAndForgets`: pair a fake engine, observe rows flow, drop the connection, observe reconnect via the backoff curve while cached rows keep rendering, then forget it and confirm its cache is deleted and its task is stopped.
  - `damaged_pairing_config_preserves_local_access_and_original_bytes` → `damagedPairingConfigPreservesLocalAccessAndOriginalBytes`: corrupt the persisted `roboco.fleet.v1` value, confirm the registry surfaces a `configurationError`, refuses new pairing/persisting, and — critically — never overwrites the damaged bytes with a blank/default value.

## 4. Gaps this ticket closes

| item | kind | desktop value | web value (file:line) | fix |
| --- | --- | --- | --- | --- |
| Multi-engine simultaneous connection | WRONG BEHAVIOR | `EngineRegistry` connects to and merges every paired engine at once | `EngineStore`/`EngineSessionProvider` drive exactly one `EngineSession` for `fleet.active` (`state/session-provider.tsx:15-52`) | Build the registry layer in §2.2, drive every stored engine |
| Offline row/transcript cache | MISSING | `EngineCache` persists rows + per-chat transcripts, atomic writes, single FIFO writer | none — memory-only (`watch-cache.ts`, transcript store) | Add the IndexedDB-backed cache in §2.3 |
| `ScopedId` cross-engine id scheme | MISSING (by design, single-engine) | `engine_registry.rs:16-62`, `request_routing.rs` | none | Add per §2.1 |
| Device online/presence used for `space_device_tag` | WRONG VALUE (partial) | `"@ {name}"` + offline flag, live-engine-state-aware | `toChatRow` composes `"{project} @ {device.name}"` with no offline flag | Add the offline computation per §2.5 |
| `EngineDrawer` vs Devices | (resolved by this ticket) | no direct equivalent | `components/engine-drawer.tsx` | Finalize per §2.6 — delete-and-redirect is the recommended outcome |

## 5. Do not

- Do not touch `settings/devices.rs`-equivalent UI (ticket 29's Devices
  page) beyond redirecting its pair/forget calls to this ticket's
  registry per §2.6 — the page's own layout/rows/rename dialog are
  already built and correct.
- Do not rebuild `EngineClient`'s single-connection dial/auth/identity/
  backoff/health-tick machinery — reuse it verbatim, one instance per
  paired engine.
- Do not build the sidebar's device-grouping rendering — that is ticket
  08's job; this ticket only supplies the merged, scoped-id-tagged data
  and the offline flag.
- Do not build `WatchTransfers` consumption — out of scope, noted only
  as "must be engine-scoped whenever it is built."
- Do not leave `EngineDrawer`'s fate undecided — §2.6 requires a decision
  and its consequences implemented, not just documented as an open
  question.
- Do not persist the offline cache in `localStorage` — use IndexedDB (row
  sets and transcripts are too large and too write-frequent for
  `localStorage`'s synchronous API and small quota).

## 6. Acceptance

- [ ] Pairing a second engine (while one is already paired) results in
      both engines' chats appearing in the sidebar simultaneously, with
      no manual "switch" step.
- [ ] Killing one paired engine's connection (e.g. stop its process in a
      test harness) leaves the other engine's rows live while the
      dropped engine's last-known rows keep rendering from cache, then
      updates to a reconnecting/off indicator per §2.2's state table.
- [ ] Reconnect timing matches the backoff table in §2.2 (500ms initial,
      doubling, 15s cap, reset after a 10s-plus connection) — verified by
      a test harness controlling a fake transport's connect/disconnect
      timing, not by wall-clock observation alone.
- [ ] A corrupted `roboco.fleet.v1` value surfaces a configuration error,
      blocks new pairing, and is never silently overwritten.
- [ ] Forgetting an engine stops its connection, deletes its cache
      entries, and removes it from the sidebar — with no race between the
      cache flush and the deletion (verified by the ordering in
      `realEngineReconnectRetainsRowsPersistsPairingAndForgets`'s forget
      phase).
- [ ] A request whose id belongs to engine A is rejected client-side (not
      sent over the wire) when routed at engine B.
- [ ] `spaceDeviceTag` reflects a paired engine's live connection state
      when one exists, falling back to the 70s last-seen window
      otherwise, and renders an offline indicator when appropriate.
- [ ] `EngineDrawer`'s fate is resolved per §2.6 (either deleted with the
      user menu redirecting to Settings → Devices, or kept with "Switch"
      removed and its data re-sourced from the registry) — no
      half-migrated state.
- [ ] Unit/integration tests (exact names):
      `scopedIdentityCodecIsCollisionSafe`,
      `realEngineReconnectRetainsRowsPersistsPairingAndForgets`,
      `damagedPairingConfigPreservesLocalAccessAndOriginalBytes`,
      `projectedMergesRowsFromEveryPairedEngineUnderScopedIds`,
      `wireParamsRejectsRequestForAnotherEngine`,
      `wireParamsAlwaysStripsTargetDeviceId`,
      `spaceDeviceTagReflectsLiveEngineState`,
      `spaceDeviceTagFallsBackToLastSeenWindow`.
- [ ] Screenshot pair, desktop vs web: sidebar showing chats from 2+
      paired engines merged into one list, with one engine shown in a
      reconnecting/offline state and its rows still visible from cache.
- [ ] `pnpm -r build` green; package vitest green.
- [ ] No new literal hex/px where a `--rb-*` token exists.

## Comments

(empty; appended during implementation)
