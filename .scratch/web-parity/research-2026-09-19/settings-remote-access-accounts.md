# Settings research: engines / devices / remote access / accounts (S1–S3)

Date: 2026-09-19. Branch `web-parity/wave-1`, HEAD `bc3a3945`. Read-only research;
every claim cites `file:line` under repo root. Scope: the SETTINGS area — the
pairing/fleet model as it appears in Desktop Settings (`crates/ui/src/settings/`)
vs the web (`web/packages/app/src/routes/settings-*.tsx`), plus the Accounts
add-account flow. Prior art read: tickets 28/29/30/31 (incl. Comments),
`.scratch/remote-access/spec.md`, ARCHITECTURE.md, CONTEXT.md.

---

## 0. Surface map (who renders what, today)

| Surface | Desktop | Web |
| --- | --- | --- |
| Settings nav (9 web rows) | `shell.rs:384-431` (`SettingsSection::ALL`), `shell.rs:4306-4429` | `components/settings-nav.tsx:21-31` |
| Settings → Devices | `crates/ui/src/settings/devices.rs` | `routes/settings-devices.tsx` |
| Settings → Remote access | `crates/ui/src/settings/remote_access.rs` | `routes/settings-remote-access.tsx` |
| Settings → Accounts | `crates/ui/src/settings/accounts.rs` | `routes/settings-accounts.tsx` |
| Engine fleet (engines this client paired to) | `crates/ui/src/engine_registry.rs` (no dedicated UI; surfaces via Devices) | `components/engine-drawer.tsx` (user-menu drawer, web-only) + `state/fleet.ts` |
| Pairing landing page | n/a (desktop pairs from Devices' box) | `routes/pair-page.tsx` |
| Engine-side session store | `crates/engine/src/pairing.rs` (`pairing.sqlite3`) | same file — engine-owned |
| Engine-side listener | `crates/engine/src/listener.rs` + `remote_access.rs` | same |

Vocabulary (CONTEXT.md:3-24): **engine** = the per-device backend; **pairing** =
one-time registration of a client; **Session** = the credential a paired client
holds; **device** = "a machine running an engine, as represented to paired
clients" (CONTEXT.md:92-97) — a device is an *engine host*, never a browser
client. A paired **client** (web or desktop) is a *Session*, listed only in
Settings → Remote access. This distinction is the root of most of the confusion
below.

---

## S1 — "Desktop paired-session settings doesn't show the web pairing"

### S1.a Mechanism trace

**A. Engine-side write path — a web pairing IS recorded, exactly like a native one.**

1. The web pair page (engine-served at `/pair`, `listener.rs:158-159` →
   `pair.html`) auto-redeems the token from the URL fragment:
   `pairEngine(window.location.href, webDeviceLabel())`
   (`routes/pair-page.tsx:31`, manual paste at `:46`). The label is
   `"Roboco web on {platform}"` (`lib/engine-store.ts:251-260`).
2. `pairEngine` → `fleetStore.redeemPairingUrl` (`state/fleet.ts:88-90`,
   `lib/engine-store.ts:133-151`) → `redeemPairingCode`
   (`engine-client/src/pairing.ts:16-60`): `POST {base}/pairing/redeem` with
   `Authorization: Bearer <pair code>` and body `{"label": …}`.
3. The engine serves that route CORS-open on the *paired* bind — the pair code
   itself is the credential (`listener.rs:114-135`, redeem fn `:243-272`; the
   store call is `pairing.redeem(&code, &input.label)` at `listener.rs:266`).
   Browsers (which cannot set `Authorization` on a WebSocket handshake)
   authenticate the RPC socket later with a first-frame `{"auth": …}` envelope
   (`listener.rs:274-310`, timeout `:25`).
4. `PairingStore::redeem` (`crates/engine/src/pairing.rs:84-128`) deletes the
   single-use code and **INSERTs a `paired_sessions` row** — `id, verifier
   (hashed), label, created_at, last_seen, revoked_at` (`pairing.rs:114-122`,
   schema `:49-52`, file `pairing.sqlite3` under the engine data dir `:38-41`).
   Label: caller label if non-empty, else the code's label (`pairing.rs:103-109`)
   — for the web that is `"Roboco web on Windows"` etc. There is **no
   client-kind column**: web, desktop and phone pairings are identical rows
   distinguished only by `label`.
5. Every (re)connect refreshes `last_seen` (`pairing.rs:130-138`
   `authenticate` — `UPDATE paired_sessions SET last_seen = …`).

**B. Engine-side read path — `GET_REMOTE_ACCESS` lists every row.**

`RemoteAccessController::snapshot` (`crates/engine/src/remote_access.rs:234-242`)
opens the same `pairing.sqlite3` and returns `RemoteAccessSnapshot { status,
sessions }` (`list_sessions` `pairing.rs:140-146`, `ORDER BY created_at, id`,
revoked rows included). The RPC handler is `crates/engine/src/rpc.rs:859-876`
(GET/SET/CREATE/REVOKE all route to the controller). Both binds and the
controller use the same data dir: the remote listener is started by the
controller with `&self.directory` (`crates/engine/src/lib.rs:624-639` called
from `remote_access.rs:212`), the loopback IPC listener with
`&engine_config.data_dir` (`lib.rs:590-602`), and
`EngineProfile::local(data_dir).device_root() == data_dir`
(`crates/engine/src/profile.rs:34-45`) — one store, no split.

**C. Desktop render path — the page that lists them.**

`RemoteAccessPage` (`crates/ui/src/settings/remote_access.rs:10-247`):

| Row/section (render order) | Label / control | Behavior | Source |
| --- | --- | --- | --- |
| Header | "Remote access" | — | `remote_access.rs:94` |
| Subtitle | "Pair your other devices with this engine. Use a trusted network or your own tunnel." | — | `remote_access.rs:95` |
| Card row 1: toggle | "Allow remote connections" + meta "Remote clients can connect with a paired session." / "Only local clients can connect." + globe icon | Click → `SetRemoteAccess {enabled: !enabled}`; reply is a fresh snapshot | `remote_access.rs:96-114` |
| Card row 1: Refresh | icon-only ghost action (14px REFRESH glyph, no text) | Click → `GetRemoteAccess` | `remote_access.rs:103-111` |
| Error strip | engine `status.error` or page error | below the card | `remote_access.rs:115-117` |
| Section header "Pairing link" | trailing "Create pairing link" ghost (+ icon) — only when `enabled` | `CreatePairingLink` → shows the minted URL | `remote_access.rs:119-133` |
| Link card | mono `url_fragment` + "Use once within five minutes." + Copy | clipboard copy; static 5-minute copy (no countdown, desktop has none) | `remote_access.rs:134-161` |
| Empty-link card | "No link yet. Create one and paste it on the other device under Settings → Devices." | shown while enabled and no url | `remote_access.rs:162-175` |
| Section header "Paired sessions" | — | always rendered (not gated on enabled) | `remote_access.rs:182` |
| Session row | status dot (emerald `theme.success.opacity(0.9)`; revoked `ink(0.22)`), title = `label` (fallback "Paired device"), meta = "Last seen {…}" or red "Revoked", trailing Revoke | Revoke → `RevokePairingSession {sessionId}` (revoked rows hide the button) | `remote_access.rs:184-246` (label `:197-201`, seen `:202-218`, dot `:204-208`, revoke `:229-243`) |
| Empty state | "No devices paired yet." | 12px, 0.6 opacity | `remote_access.rs:184-192` |

Data loading: one `GetRemoteAccess` at construction (`remote_access.rs:29`),
and `request()` refetches the snapshot after every *mutation*
(`remote_access.rs:48-56`). **Nothing else ever refetches.**

**D. The web client's own page.** `routes/settings-remote-access.tsx` is a
faithful port of the same IA (toggle `:115-138`, refresh `:123-125` with a
text label "Refresh" unlike the desktop's icon-only one, link `:146-172`,
"Paired sessions" `:174-200`, `sessionRows` in `lib/remote-access.ts:30-49`
with the same "Paired device" fallback `:44`). It mounts fresh on every route
visit (`:55-62`) and resets on engine change — i.e. the web page is always
fresh, the desktop page is not (below).

### S1.b Verdict — should the web pairing appear?

**Yes — expected behavior.** The engine treats web clients exactly like native
ones (ADR 0006; CONTEXT.md:21-24 "pairs like any other client and holds its
Session credential in browser storage"). A web pairing produces a
`paired_sessions` row with label "Roboco web on Windows" (or the platform
string), which `GetRemoteAccess` returns and the desktop page renders as a row
with "Last seen just now". There is no engine-side classification gap, no
separate storage, and no missing kind field — the only differentiator is the
label, and the web sends one.

### S1.c Root cause (why the user saw nothing)

1. **The desktop page is stale, not wrong.** `settings_outlet` creates
   `RemoteAccessPage` once and caches the entity forever
   (`shell.rs:3332-3345`); `open_settings` only recreates the Harnesses page
   (`shell.rs:3268-3273`, "Recreate per visit"). The page has no
   `cx.observe(&state, …)` subscription (struct fields at
   `remote_access.rs:10-17` — unlike `DevicesPage`, which observes
   `devices.rs:104`) and no watch on the session list. So: user opens Remote
   access, mints the link (this fetches a snapshot with `sessions: []`), pairs
   the web in the browser, returns to the section — the cached page still
   shows the pre-pairing snapshot, i.e. **"No devices paired yet."**
2. **The only refresh affordance is an icon-only ghost button** with no text
   (`remote_access.rs:103-111`) — easy to miss; the web version at least says
   "Refresh" (`settings-remote-access.tsx:123`).
3. **The user may have looked in the wrong list.** The web pairing never
   appears in Settings → **Devices**: the engine's device registry contains
   *engine hosts* (its own row upserted at boot, `workspace_host.rs:117-147`),
   and a client pairing writes no device row. Someone hunting for "the web
   device" in Devices finds only the engine's machine.
4. **Copy actively misleads.** The empty state says "No devices paired yet."
   (`remote_access.rs:191`) — "devices", in an app where Devices is a
   different settings page with different content. The web's own session also
   appears in the web's Remote access list with no "this is you" marker
   (`sessionRows` `lib/remote-access.ts:39-49`), while on the desktop the
   local loopback client has no session row at all (`listener.rs:34-41` —
   local policy is credential-free), so the same page means subtly different
   populations depending on which client renders it.

### S1.d The "confusing" assessment + unified model

What mismatches today:

- **Two lists, one word.** "Paired sessions" (clients that paired TO this
  engine — `pairing.sqlite3`) vs "Devices" (engine hosts this engine/client
  knows — registry rows + the client's own fleet, `state.rs:1931-1946`). Both
  get called "pairing" in copy: Remote access says "Pair your other devices
  with this engine" (`remote_access.rs:95`) while the Devices box says
  "Create a pairing link in the engine's Remote access settings, then paste it
  here" (`devices.rs:613-615`). The direction of pairing is inverted between
  the two pages and nothing but careful reading reveals it.
- **Staleness asymmetry.** Desktop Remote access: one-shot fetch, icon-only
  refresh. Web Remote access: fresh on every visit. Desktop Devices: live via
  `cx.observe`. The desktop user therefore sees the *live* Devices page and
  the *stale* Remote access page in the same nav.
- **No self-marker.** Neither client marks "this is the session you're using"
  in Paired sessions; the web's own row reads like a stranger
  ("Roboco web on Windows" with no badge).

A unified model (feeds the S2 redesign): one page owns "who can reach this
engine" (Remote access: toggle, link mint, session rows with a "This client"
badge where the session id matches the credential in the client's storage),
one page owns "which engines this client reaches" (Devices: the fleet +
per-engine device rows). Both lists refresh on visit. The desktop needs the
refresh-on-visit fix; the web needs the self-marker; both need copy that
names the direction ("paired with this engine" vs "engines paired from this
browser/desktop").

### S1.e Gap rows

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
| --- | --- | --- | --- | --- |
| Remote access page refreshes on section (re)entry | MISSING | cached entity, no refetch (`shell.rs:3332-3345`, `remote_access.rs:29`) | fresh mount per visit (`settings-remote-access.tsx:55-62`) | Desktop: refetch `GetRemoteAccess` in `settings_outlet` when entering the section (cheapest: recreate the page per visit like Harnesses, `shell.rs:3269-3273`) |
| Refresh affordance labeled | WRONG VALUE (discoverability) | icon-only ghost (`remote_access.rs:103-111`) | text "Refresh" (`settings-remote-access.tsx:123-125`) | Desktop: add the "Refresh" label (matches Accounts' labeled Refresh, `accounts.rs:1424-1438`) |
| Web pairing visible in Paired sessions | CONFIRMED WORKING | rows render `label`/`Paired device` (`remote_access.rs:194-227`) | same (`lib/remote-access.ts:39-49`) | no fix — engine records it (`pairing.rs:114-122`); only staleness hides it |
| "This client" marker on the session row | MISSING | none — desktop has no session row for itself (`listener.rs:34-41`) | none (`settings-remote-access.tsx:183-198`) | Web: compare each row's `id` to the stored engine's `sessionId` (`lib/engine-store.ts:20`) and badge it; desktop N/A (loopback has no session) |
| Empty-state copy names clients, not devices | WRONG VALUE | "No devices paired yet." (`remote_access.rs:191`) | same string (`settings-remote-access.tsx:181`) | Both: "No clients paired yet." or "No paired sessions." — one-word fix, kills the Devices confusion |
| Client kind on PairedSession | MISSING (by design) | no field (`crates/proto/src/remote.rs:22-28`) | same | optional: derive a "Web" chip from the label prefix at render time; do not add a wire field without a desktop need |

---

## S2 — Web "Engines" surface removal + unified settings IA

### S2.a Web today: every engine-management surface (mechanism trace)

The web currently has **five** surfaces that touch pairing/engines:

1. **User menu → "Engines" drawer** (`components/engine-drawer.tsx:25-47`,
   opened via `emitShortcut("open-engines")` from `account-row.tsx:84-95`,
   mounted `app-shell.tsx:700`, shortcut registered `app-shell.tsx:212`). A
   web-only menu row — `account-row.tsx:19-22` says so outright ("the web-only
   Engines row (the pairing entry point — the desktop has no per-device
   identity concept here)"). Content: one row per engine **this browser
   paired** (`fleet.engines`), each with a connection-state dot + label
   (Connected / Reconnecting… / "Session revoked" / "Engine changed",
   `engine-drawer.tsx:75-97`), an identity line `Engine {deviceId.slice(0,8)}`
   (`:127-129`), urgent-chat dot from that engine's snapshot (`:106-109`),
   "Pair again" (→ `/pair`, `:111-113`), Forget with confirm (`:144-158`), and
   an "Add an engine by its pairing URL" form (`:160-205`) →
   `pairEngine(url, webDeviceLabel())` (`:175`).
2. **Settings → Devices** (`routes/settings-devices.tsx:41-231`): the pairing
   box ("Paste a pairing URL" + Connect, `:172-196`) → the *same*
   `fleetStore.redeemPairingUrl(url, webDeviceLabel())` (`:105-125`); rows
   from the **connected engine's** `WatchDevices` (`:70`); "This device" badge
   on the engine's own device row (`:71`, `:300`); a row matching a *parked
   fleet engine* renders off + Forget (`:90-103`, `:301-305`); Rename via
   `Mutate renameDevice` (`:132-146`).
3. **`/pair` landing** (`routes/pair-page.tsx:17-52`): token auto-redeem +
   manual paste. Reached from engine pairing URLs and the drawer's
   "Pair again".
4. **Settings → Remote access** (`routes/settings-remote-access.tsx`):
   configures the **routed (active) engine's** listener, mints links, lists
   and revokes that engine's sessions (S1).
5. **Storage/registry** (`state/fleet.ts:25-64`): `fleetStore`
   (`lib/engine-store.ts`, `localStorage["roboco.fleet.v1"]` `:61`,
   `StoredEngine {baseUrl, credential, label, sessionId, pairedAt, deviceId}`
   `:13-24`) + `engineRegistry` (one supervised client + watch cache + cache
   per engine, ticket 31).

**Overlaps and conflicts:**

- The drawer (1) and the Devices pairing box (2) are the *same action* (both
  call the fleet layer with `webDeviceLabel`), duplicated in two vocabularies:
  "Add engine" vs "Connect"/"Paste a pairing URL". The drawer even tells you
  to use a URL minted in "the engine's remote access settings" implicitly via
  its placeholder (`engine-drawer.tsx:193`).
- `fleet.active` (the engine the settings pages 4 talk to) is set only by
  pairing (`engine-store.ts:149`) and the remove-fallback (`:160-168`); the
  drawer's "Switch" action was deleted in ticket 31
  (`issues/31-fleet.md:538-542`), and **nothing in the UI calls `setActive`
  anymore** (grep: only `engine-store.ts:153` defines it). With 2+ engines
  paired, Settings → Remote access/Agents/Accounts silently configure the
  *last-paired* engine with no indicator or switcher on those pages.
- The user-menu drawer duplicates what the merged sidebar already
  communicates (per-engine state via `@ device` tags, urgent dots) — its only
  unique value is pairing and forgetting, both of which Devices already does.

### S2.b Desktop reference — how the desktop models it

**Desktop settings IA** (nav order, `shell.rs:402-413`; user menu lands on
Devices, `shell.rs:5290-5302`; `/settings` redirect parity on web
`router.tsx:36-44`):

| # | Section (label) | Owns | Web counterpart |
| --- | --- | --- | --- |
| 1 | Devices | engines this app pairs to + the merged device registry | `settings-devices.tsx` |
| 2 | Remote access (label, `shell.rs:420`) | this engine's listener + its paired sessions | `settings-remote-access.tsx` |
| 3 | Harnesses → "Agents" | per-device harness toggles + titles | `settings-agents.tsx` |
| 4 | Agents → "Accounts" | per-device CLI logins | `settings-accounts.tsx` |
| 5-9 | Appearance / Files / Notifications / Shortcuts / Archived | device-local prefs + engine chats | shipped (tickets 28/29) |
| 10 | Appshots | desktop/Linux-only | never on web |

**Fleet mechanics** (`crates/ui/src/engine_registry.rs`): `pair(url, label)`
redeems over HTTP, connects an authenticated WS, fetches identity, persists
`paired-engines-v1.json` under the data dir (`engine_registry.rs:478-533`;
path from `state.rs:1898-1900`), and supervision starts immediately
(`:530-531`); `forget` (`:534-558`) unpersists + aborts + flushes + deletes
the engine's cache; `supervise` retries forever with 500ms→×2→15s backoff
(10s-lived connections reset; refusals park permanently) (`:679-738`); remote
engines with no device rows get a synthesized `"Remote engine"` device row so
the merged registry always shows one device per engine
(`engine_registry.rs:117-129`). The registry is *not* a settings page — it is
state; the desktop's only pairing UI is the Devices box
(`devices.rs:125-158`, label `"Roboco desktop"` at `:140`), and the desktop
user menu has exactly one entry: Settings (`shell.rs:5290-5302`).

**Devices page rows** (desktop, `devices.rs:307-623`): platform tile with
corner presence dot driven by the owning engine's connection state
(`:361-382`), name, meta line `platform · v{version} · Connected/Reconnecting/
Off · Last seen {…} · Added {…} · short-id chip` (`:385-456`), "This device" /
"Local only" badge (`:469-479`), Forget on non-local engine-backed rows
(`:481-490`), Rename (`:491-512`). Rows come from
`state.devices = RegistrySnapshot::projected().devices` — every paired
engine's device rows merged and scoped (`state.rs:1931-1946`,
`engine_registry.rs:96-145`).

### S2.c Case matrix (core deliverable)

| case | desktop behavior (file:line) | web today (file:line) | web target | notes |
| --- | --- | --- | --- | --- |
| 1. Local engine + native client only (single machine) | Attaches/embeds via loopback, no Session (`state.rs:219-339`, `listener.rs:34-41`); Devices shows 1 row "This device" (`devices.rs:469-479`); Remote access sessions list empty ("No devices paired yet." `remote_access.rs:184-192`) | n/a (web always pairs) | n/a | The "empty paired sessions" experience that sets user expectations |
| 2. Web client paired to its own serving engine (user's S1 setup) | Engine records the web Session (`pairing.rs:114-122`); Remote access shows "Roboco web on {platform}" — *after refresh* (staleness §S1.c); Devices unchanged (web is a client, not a device) | Web app works off `roboco.fleet.v1`; its own Session appears in its own Remote access list with no self-marker (`settings-remote-access.tsx:183-198`) | Web badges "This browser" on its own session row; desktop refreshes on visit | Pairing direction: web → engine |
| 3. Web pairing to a REMOTE engine from another machine (paste URL) | Desktop equivalent: pair from Devices box, label "Roboco desktop" (`devices.rs:125-158`); engine appears in merged sidebar immediately (`engine_registry.rs:530-531`) | `/pair` auto-redeem or paste (`pair-page.tsx:24-52`); drawer "Add engine" (`engine-drawer.tsx:160-205`); Devices box (`settings-devices.tsx:105-125`) — three redundant entries, all calling the same fleet layer | ONE entry: Settings → Devices pairing box (+ `/pair` for token URLs); drawer removed | Cross-origin redeem is CORS-open (`listener.rs:118-131`) so any of the three works from any origin |
| 4. Multiple engines paired (fleet) | All supervised at once; merged sidebar rows; Devices shows each engine's device rows (+ "Remote engine" synth, `engine_registry.rs:117-129`); Remote access configures the LOCAL engine only (`remote_access.rs:37-39` uses `state.engine()`) | All supervised (`state/fleet.ts:34-55`); merged sidebar; **settings pages talk to `fleet.active` = last-paired, no switcher** (S2.a); drawer shows all engines but can only Pair-again/Forget | Settings pages get an engine indicator (and optionally a switcher) naming which engine they address; Remote access should name the engine it configures | Desktop Remote access = local engine by construction; web has no "local" engine, so "active" is the closest concept — but it is invisible |
| 5. Revoked credential (engine side) | Next dial refused → engine parked "Off"; Devices row shows off dot; re-pair via Devices box | Client parks permanently (4401 → "Session revoked", `client.ts:456-461`); gate card offers Retry (recreate, `session-provider.tsx:109-124`); drawer "Pair again" (`engine-drawer.tsx:111-113`); row "off" + Forget (`settings-devices.tsx:90-103`) | Revoked engine visible in Devices with "Pair again" affordance + Revoke info from the engine's Remote access page if reachable | Revocation UI lives on the *engine's* Remote access page (Revoke button `remote_access.rs:229-243`) |
| 6. Engine offline (process down / network) | Rows keep rendering from `engine_cache.rs`; reconnect with backoff (`engine_registry.rs:679-738`); writes refused | Rows from IndexedDB cache (`engine-cache.ts`); reconnecting badge; offline chat read-only (`shots/31`) | same — already correct (ticket 31) | — |
| 7. Pairing from the web TO another machine's engine via URL (cross-machine) | n/a (desktop analog = case 3) | Same as case 3 — the web has no local engine; every pairing is remote | same | The web's "local engine" is merely the one serving the page |
| 8. Desktop app pairing to a remote engine (desktop as client) | Devices box pair; remote engine's Remote access page (visited on that engine's client) lists "Roboco desktop" | The remote engine's *web* client would list it too | no web change | Confirms the symmetric model: every client is a session row on its engine |

### S2.d Proposed unified web settings IA

Grounded in the desktop split (engines-you-reach vs clients-who-reach-you):

**Remove**: the user-menu "Engines" drawer (`engine-drawer.tsx`, the
`emitShortcut("open-engines")` row `account-row.tsx:84-95`, the mount
`app-shell.tsx:700`, shortcut `:212`). Ticket 31 §2.6 already resolved the
drawer's fate as "delete + redirect to Settings → Devices once 29 lands"
(`issues/31-fleet.md:341-357`, deferred only because 29 hadn't landed — it
has, `issues/29-settings-sections.md:762-843`). Spec decision 4 ("invented
web-only UI is deleted, not polished", `.scratch/web-parity/spec.md:28-29`)
makes removal the default for a web-only surface that now duplicates a
shipped page. Move `describeRedeemError` (`engine-drawer.tsx:207-222`) to
`lib/` (it is already imported by `pair-page.tsx:5` and
`settings-devices.tsx:9`). The user menu keeps: identity line + Settings
(desktop parity, `shell.rs:5290-5302`).

**Keep, with these rows** (Settings → Devices, the single pairing surface):

| Row | Content | Source today |
| --- | --- | --- |
| Pairing box | "Paste a pairing URL" + Connect; hint "Create a pairing link in the engine's Remote access settings, then paste it here." | `settings-devices.tsx:172-196` (keep verbatim) |
| Engine rows (one per paired engine) | host (`engineHost`), connection state dot + label, session state ("Session revoked" → "Pair again" → `/pair`), Forget (confirm) | folded from `engine-drawer.tsx:99-158` — the drawer's per-engine row, minus the urgent dot (sidebar owns urgency) |
| Device rows (per engine) | current rows: platform tile + presence, meta line, This device badge, Rename | `settings-devices.tsx:198-312` (keep) |

**Keep as-is** (already coherent): Settings → Remote access (engine's
listener + sessions), with two additions from S1: a "This browser" badge on
the row matching `sessionId`, and an engine-name indicator when >1 engine is
paired (the page's data is `useEngineSession()` → routed/active engine,
`session-provider.tsx:101-107`).

**Resulting web settings nav**: unchanged 9 rows (`settings-nav.tsx:21-31`)
— the unification deletes a *drawer*, not a section, and routes all
pairing through Devices, matching the desktop exactly.

### S2.e Gap rows

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
| --- | --- | --- | --- | --- |
| User-menu engine list | NOT ON DESKTOP (invented) | none — user menu = Settings only (`shell.rs:5290-5302`) | "Engines" drawer (`engine-drawer.tsx:25-47`, `account-row.tsx:84-95`) | Delete drawer + menu row; move `describeRedeemError` to `lib/`; keep `/pair` |
| Duplicate pairing entry points | NOT ON DESKTOP | one: Devices box (`devices.rs:125-158`) | three: drawer form, Devices box, `/pair` paste (`engine-drawer.tsx:160-205`, `settings-devices.tsx:105-125`, `pair-page.tsx:39-52`) | Drawer removed → two (Devices for paste, `/pair` for token URLs) — same as desktop's one + URL landing |
| Settings pages address a visible engine | DIFFERENT MODEL | Remote access/Accounts always the local engine (`remote_access.rs:37-39`, `accounts.rs:492` via `device_target`) | pages use `fleet.active`, last-paired, unlabeled (`session-provider.tsx:101-107`, no `setActive` caller) | Show "Engine {host}" next to the page subtitle when `fleet.engines.length > 1`; optionally a switcher popover |
| Engine rows in Devices (fleet health) | Devices page rows are device rows; fleet state visible via synthesized "Remote engine" rows (`engine_registry.rs:117-129`) | drawer rows only (`engine-drawer.tsx:99-142`); Devices shows parked-engine rows as off+Forget (`settings-devices.tsx:90-103`) | Fold drawer's engine row into Devices above the device rows (per S2.d table) |
| "Pair again" entry after revoke | Devices box re-pair (re-enters the flow, `devices.rs:125-158`) | drawer "Pair again" → `/pair` (`engine-drawer.tsx:111-113`); gate-card escape (`session-provider.tsx:109-124` retry) | Engine row's "Pair again" in Devices → `/pair` |

---

## S3 — Accounts: Add account opens two tabs

### S3.a Mechanism trace

**Engine** (`crates/engine/src/agent_accounts.rs:522-532` dispatch):
- **Claude Code** → `start_claude_login` builds a PKCE URL in-process, no
  child, `mode: PasteCode` (`:534-564`).
- **Codex** → `start_codex_login` spawns `codex login` with an isolated
  `CODEX_HOME` (`:581-659`; `crates/harness/src/codex/mod.rs:92-98`), scans
  the auth URL off the CLI's output (`:653`, `scan_openai_url` `:1832-1837`),
  returns `mode: Browser` (`:657`). The CLI **opens the authorization tab
  itself** via the `webbrowser` crate — the engine's own comment documents the
  resulting double-tab history and the fix: set `BROWSER` to a no-op script so
  the CLI's open stays quiet — **`#[cfg(unix)]` only**
  (`agent_accounts.rs:614-623`, helper `:1839-1853` whose doc says "Unix only
  — `webbrowser` only consults `BROWSER` on unix; elsewhere the CLI's own
  open is left as-is").
- **Cursor** → `start_cursor_login` runs the roboco shim in login mode with
  `openBrowser: false` (`:665-710`;
  `crates/harness/src/cursor/shim.mjs:129-148`, option at `:133-135`) — no
  CLI-side open, `mode: Browser` (`:708`).

**Desktop client** (`crates/ui/src/settings/accounts.rs:491-541`): `Add
account` → `StartAgentLogin` (routed through the device target `:492`, params
`:497`) → on reply the app opens the page itself: `cx.open_url(&start.url)`
(`:508`) → paste-code dialog (`:510-519`) or browser-poll dialog +
`spawn_poll` (1.5s `PollAgentLogin` loop, `:520-528`, `:590-666`). The dialog
carries a click-only "Reopen the authorization page" link
(`:932-949`, `:977-982`).

**Web client** (`routes/settings-accounts.tsx:138-158`): `addAccount` →
`startAgentLogin` (`lib/accounts.ts:67-76`) → **`window.open(start.url,
"_blank", "noopener,noreferrer")` (`settings-accounts.tsx:147`)** → dialog;
paste-code submit (`:160-183`, `completeAgentLogin` `lib/accounts.ts:78-89`)
or browser poll (`:84-114`, `pollAgentLogin` 1.5s loop `lib/accounts.ts:265-300`).
The dialog has click-only "Reopen" anchors (`:482-484`, `:521-523`). The
`window.open` is the **only** open call site in the web app besides markdown
links (`components/markdown.tsx:357`), and `addAccount` is an event handler —
no effect double-fire; React StrictMode (`main.tsx:12-17`) does not
double-invoke event handlers.

### S3.b Root cause

Two opens, one per side, both land in the user's browser when the engine runs
on the user's own machine (the S1 setup — web paired to the local Windows
engine):

1. **Engine machine, codex only:** the spawned `codex login` CLI opens the
   auth tab via its own browser open — unsuppressed on Windows because the
   `BROWSER`-no-op trick is `#[cfg(unix)]`
   (`agent_accounts.rs:620-623`, `:1839-1842`).
2. **Client:** the web page's `window.open(start.url)`
   (`settings-accounts.tsx:147`) — the desktop has the same second open
   (`cx.open_url`, `accounts.rs:508`), so **the desktop on Windows double-opens
   too**. This is a pre-existing engine-side Windows gap the web inherited,
   not a web regression. Claude is immune (no child), Cursor is immune
   (`openBrowser: false`).

Secondary web-only robustness note: `window.open` runs after an `await` of
the RPC, so it has lost the user-gesture context — popup blockers may swallow
it (`settings-accounts.tsx:144-147`); if that happens the codex CLI's tab is
the only one, masking the bug intermittently.

### S3.c Fix sketches

- **Engine (primary, fixes desktop + web):** make the Windows path match the
  unix intent — exactly one open, by the client. `webbrowser` on Windows
  ignores `BROWSER`, so suppression needs a different lever; the practical
  route is to stop relying on the CLI's open at all: extend
  `AgentLoginStart` (`crates/proto/src/entities.rs:797-814`) with a flag
  (e.g. `cliOpensBrowser: bool`) set true only where the CLI's open is known
  unsuppressed (codex on Windows); both clients then skip their own open when
  the flag is set and keep the "Reopen" link for the remote/headless-engine
  case where the CLI's open lands nowhere useful.
- **Web (independent hardening):** keep exactly one `window.open`, but call it
  synchronously from the click handler where possible (open `about:blank`
  first, set `location` after the RPC resolves) so popup blockers cannot eat
  the only user-visible tab; and make the codex dialog's wait line explicit
  ("A tab opened on the engine's machine") until the engine flag exists.
- **No web-only double-open exists** — do not hunt for one in React; the
  second tab is spawned by the engine machine.

### S3.d Gap rows

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
| --- | --- | --- | --- | --- |
| Codex login opens exactly one tab on Windows | WRONG BEHAVIOR (engine) | CLI opens (unsuppressed, `agent_accounts.rs:620-623` unix-only noop) + app opens `accounts.rs:508` | CLI opens + `window.open` `settings-accounts.tsx:147` | engine flag on `AgentLoginStart`; clients gate their open on it |
| Cursor/claude single-tab | OK | `openBrowser:false` `shim.mjs:133-135`; claude URL built in-process `agent_accounts.rs:534-564` | same engine reply; one `window.open` | none |
| Popup-gesture safety for `window.open` after RPC | N/A | `cx.open_url` has no gating | `settings-accounts.tsx:144-147` | pre-open a blank tab in the click handler, navigate it post-RPC |
| Login dialog "Reopen …" link | OK | `accounts.rs:932-949` | `settings-accounts.tsx:482-484, 521-523` | none — parity |

---

## Consolidated gap table

| item | kind | desktop value (file:line) | web value (file:line) | fix |
| --- | --- | --- | --- | --- |
| Remote access refreshes on section re-entry | MISSING (desktop) | cached page, no refetch (`shell.rs:3332-3345`) | fresh per mount (`settings-remote-access.tsx:55-62`) | desktop: recreate/refetch on visit |
| Refresh button has a label | WRONG VALUE (desktop) | icon-only (`remote_access.rs:103-111`) | "Refresh" (`settings-remote-access.tsx:123`) | desktop: label it |
| "This browser" badge on own session row | MISSING (web) | n/a (no self-session) | none (`settings-remote-access.tsx:183-198`) | badge rows where `id === stored sessionId` |
| Empty-state copy "No devices paired yet." | WRONG VALUE (both) | `remote_access.rs:191` | `settings-remote-access.tsx:181` | say "clients"/"sessions" |
| User-menu Engines drawer | INVENTED (web) | user menu = Settings only (`shell.rs:5290-5302`) | `engine-drawer.tsx` + `account-row.tsx:84-95` | delete; fold rows into Devices (31 §2.6 option 2, now unblocked) |
| Pairing entry points | WRONG COUNT (web) | 1 (+URL landing) (`devices.rs:125-158`) | 3 (`engine-drawer.tsx:160-205`, `settings-devices.tsx:105-125`, `pair-page.tsx:39-52`) | 2 after drawer removal |
| Settings pages' target engine invisible | MISSING (web) | implicit local engine (`remote_access.rs:37-39`) | `fleet.active`, unlabeled, no switcher | engine-name indicator (+optional switcher) |
| Engine fleet rows in Devices | MISSING (web) | synthesized "Remote engine" rows (`engine_registry.rs:117-129`) | drawer-only (`engine-drawer.tsx:99-142`) | fold into Devices |
| Codex login double tab (Windows) | WRONG BEHAVIOR (engine, both clients) | `agent_accounts.rs:620-623` unix-only suppression + `accounts.rs:508` | same engine + `settings-accounts.tsx:147` | engine flag; client gates open |
| `window.open` after await popup risk | N/A (web) | n/a | `settings-accounts.tsx:144-147` | pre-open blank tab in handler |

## Pure logic to port + desktop test names

Already ported and verified (no action): `format_last_seen` /
`device_online` / `presence_dot` / `platform_label` / `short_id`
(`devices.rs:27-70, 286-305` → `lib/devices.ts`, tests named in ticket 29
`issues/29-settings-sections.md:743-749`); `sessionRows`
(`lib/remote-access.ts:30-49` vs `remote_access.rs:194-244`); usage meters and
`forceUsageFor`/`formatReset` (`lib/accounts.ts` vs `accounts.rs`, ticket 29
§2.6). New ports implied by this research:

- **Session self-identity** (web): pure fn `ownSessionId(stored: StoredEngine)
  → string | null` + a `sessionIsSelf(row, stored)` predicate over
  `StoredEngine.sessionId` (`lib/engine-store.ts:20`) — no desktop test
  exists (desktop has no self-session); write
  `sessionIsSelfMatchesStoredSessionId`.
- **Engine-indicator derivation** (web): `settingsEngineLabel(fleet: FleetState):
  string | null` — host of `fleet.active` when >1 engine; no desktop analog;
  write `settingsEngineLabelNamesActiveEngineOnlyWhenFleetIsPlural`.
- Desktop-side (not web): the Remote access re-entry refresh — the desktop
  test to mirror is the page-lifecycle behavior of `HarnessesPage`
  ("Recreate per visit", `shell.rs:3269-3273`); no unit test exists for it.

## Desktop-only items NOT to port

- The icon-only Refresh affordance (desktop quirk; the web's labeled button
  is *better* — keep the web's, fix the desktop's).
- macOS/Unix login plumbing: `ensure_noop_browser` script
  (`agent_accounts.rs:1839-1853`), `BROWSER` env games, the Keychain
  footnote copy (`accounts.rs:1468-1473` is ported copy only).
- Native `cx.open_url` semantics (no popup gating on desktop).
- The desktop's implicit "settings = local engine" model (the web has no
  local engine; it needs the explicit indicator instead).
- `scoped_identity_codec_is_collision_safe` and the registry cache/backoff
  tests — already ported by ticket 31 (`issues/31-fleet.md:543-548`).

## Open design questions

1. **Drawer removal vs fold-back for the "Engines" user-menu row** — removal
   is recommended (§S2.d), but the user menu then has no engine surface at
   all; alternative is a menu item that navigates to
   `/settings/devices` (ticket 31 §2.6's option 2 literal form). Pick one.
2. **Naming for the unified section** — "Devices" is the desktop label and
   must stay (parity, `shell.rs:419`), but its web subtitle could name both
   populations ("Engines this browser reaches and the devices they know");
   exact copy needs a human pass.
3. **The codex double-tab fix shape** — engine flag on `AgentLoginStart`
   (recommended) vs Windows-specific client heuristics vs accepting the CLI's
   tab and dropping the client's open only for same-origin engines. Also:
   does `codex` respect `BROWSER` on Windows at all (unverified — the repo's
   own comment implies it does not)?
4. **Does the desktop Remote access fix land desktop-side only** (recreate
   page per visit) **or as a watch** (a `WatchPairingSessions`-style stream)?
   No stream exists today (`rpc.rs:859-876` is unary-only); per-visit refresh
   is the parity-minimal fix.
