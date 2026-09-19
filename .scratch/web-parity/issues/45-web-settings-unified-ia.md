# 45 — Web settings unified IA: delete the Engines drawer, one pairing surface

**What to build:** The user menu no longer has an "Engines" drawer. Every
engine-management action lives in Settings → Devices, which now shows one
row per engine this browser paired — connection state, "Pair again" when
its Session was revoked, Forget — above the device rows it already shows.
Pairing stays exactly two entries (the Devices box for pasted URLs, `/pair`
for token URLs), matching the desktop's single-surface model. And the
settings pages that silently talk to the *active* engine (Remote access,
Agents, Accounts) now say which engine they address whenever more than one
is paired, with a switcher to retarget them.

**Blocked by:** None — can start immediately. (Ticket 31 §2.6 deferred the
drawer's removal only until ticket 29 landed Settings → Devices; 29 has
landed, `issues/29-settings-sections.md:762-843`.)

**Status:** ready-for-agent

**Research:** `../research-2026-09-19/settings-remote-access-accounts.md` §0 (surface map), S2.a–S2.e (web engine surfaces, desktop model, case matrix, unified IA, gap rows).

**Desktop reference (for lookups only):** `crates/ui/src/shell.rs::settings_outlet` (3326-3564; RemoteAccess cached arm 3332-3345), `::open_settings` (3268-3273), `::render_settings_nav` (4306-4429), `SettingsSection::ALL` (384-431; nav order 402-413; "Remote access" label 420), user menu = Settings only (5290-5302); `crates/ui/src/settings/devices.rs` (pairing box 125-158, "Roboco desktop" label 140, "This device" badge 469-479, pairing hint 613-615, `cx.observe` model 104); `crates/ui/src/engine_registry.rs` (pair 478-533, forget 534-558, supervise 679-738, synthesized "Remote engine" rows 117-129); `crates/ui/src/state.rs` (merged device rows 1931-1946); `crates/ui/src/settings/remote_access.rs` (local engine by construction 37-39).

**Web files to touch:**
| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/components/engine-drawer.tsx` | delete | the whole file: `EngineDrawer`, `EngineList`, `EngineRow`, `RemoveEngineButton`, `AddEngineForm`, `entryConnection` — after `describeRedeemError` (207-222) has been moved out |
| `web/packages/app/src/lib/pairing-errors.ts` | new | `describeRedeemError`, moved verbatim from `engine-drawer.tsx:207-222` |
| `web/packages/app/src/components/account-row.tsx` | edit | remove the "Engines" menu item (84-95, the `emitShortcut("open-engines")` button) and its comment (79-83); keep the identity line (78) and the "Settings" row (96-99) |
| `web/packages/app/src/components/app-shell.tsx` | edit | remove the `EngineDrawer` import (67), the `open-engines` shortcut registration (212), the `drawerOpen` state, and the `<EngineDrawer>` mount (700) |
| `web/packages/app/src/state/shortcuts.ts` | edit | remove the `"open-engines"` shortcut id from the union (515-519) |
| `web/packages/app/src/routes/pair-page.tsx` | edit | `describeRedeemError` import path (5) → `../lib/pairing-errors` |
| `web/packages/app/src/routes/settings-devices.tsx` | edit | the engine-rows section (new, between the pairing box and the device-rows card); `describeRedeemError` import path (9) |
| `web/packages/app/src/components/settings-engine-indicator.tsx` | new | `SettingsEngineIndicator` — engine-name indicator + switcher popover |
| `web/packages/app/src/lib/settings-engine.ts` | new | `settingsEngineLabel` |
| `web/packages/app/src/routes/settings-remote-access.tsx` | edit | mount `SettingsEngineIndicator` beside the subtitle (111-113) — nothing else on this page (ticket 46 owns the session list and "This browser" badge) |
| `web/packages/app/src/routes/settings-agents.tsx` | edit | mount `SettingsEngineIndicator` beside the subtitle (160) |
| `web/packages/app/src/routes/settings-accounts.tsx` | edit | mount `SettingsEngineIndicator` beside the subtitle (239) — nothing else (ticket 47 owns the login flow) |
| `web/packages/app/src/styles/app.css` | edit | delete the drawer-only classes (`.engine-list`, `.engine-row*`, `.engine-row-pair`, `.engine-row-parked`, `.drawer-note*`, `.add-engine` form classes — app.css ~5604-5770; grep each for other users first); KEEP `.add-engine-label` (5604) — `pair-page.tsx:60` uses it; new `.settings-engine-indicator*` |
| `web/packages/app/tests/settings-engine.test.ts` | new | `settingsEngineLabelNamesActiveEngineOnlyWhenFleetIsPlural` |

## 1. Context a fresh session needs

- Surface map (research §0, the rows this ticket lives in), verbatim:

| Surface | Desktop | Web |
| --- | --- | --- |
| Settings → Devices | `crates/ui/src/settings/devices.rs` | `routes/settings-devices.tsx` |
| Settings → Remote access | `crates/ui/src/settings/remote_access.rs` | `routes/settings-remote-access.tsx` |
| Settings → Accounts | `crates/ui/src/settings/accounts.rs` | `routes/settings-accounts.tsx` |
| Engine fleet (engines this client paired to) | `crates/ui/src/engine_registry.rs` (no dedicated UI; surfaces via Devices) | `components/engine-drawer.tsx` (user-menu drawer, web-only) + `state/fleet.ts` |
| Pairing landing page | n/a (desktop pairs from Devices' box) | `routes/pair-page.tsx` |

- Vocabulary (CONTEXT.md:3-24): **engine** = the per-device backend;
  **pairing** = one-time registration of a client; **Session** = the
  credential a paired client holds; **device** = an engine host as
  represented to paired clients — never a browser client. A paired client
  (web or desktop) is a Session, listed only in Remote access. Also: chat,
  space. Pairing is never "login".
- The drawer is **web-only invented UI**: `account-row.tsx:19-22` says so
  outright ("the web-only Engines row (the pairing entry point — the
  desktop has no per-device identity concept here)"). The desktop user
  menu has exactly one entry — Settings (`shell.rs:5290-5302`) — and the
  desktop's only pairing UI is the Devices box (`devices.rs:125-158`,
  label "Roboco desktop" at `:140`). Spec decision 4
  (`.scratch/web-parity/spec.md:28-29`): invented web-only UI is deleted,
  not polished.
- Ticket 31's §2.6 resolved the drawer's fate as "delete + redirect to
  Settings → Devices once 29 lands" (`issues/31-fleet.md:341-357`; its
  Comments landed option 1 only because 29 had not — "when 29 lands it can
  fold the drawer's rows into its Devices page and revisit",
  `issues/31-fleet.md:538-557`). 29 has landed. This ticket performs the
  fold.
- What the drawer did (all of it moves or dies): one row per
  `fleet.engines` entry with a connection-state dot + label (Connected /
  Reconnecting… / "Session revoked" / "Engine changed",
  `engine-drawer.tsx:75-97`), identity line `Engine {deviceId.slice(0,8)}`
  (`:127-129`), urgent-chat dot (`:106-109` — sidebar owns this, dies),
  "Pair again" → `/pair` (`:111-113`), Forget with confirm (`:144-158`),
  and the "Add an engine by its pairing URL" form (`:160-205`) →
  `pairEngine(url, webDeviceLabel())` (`:175`).
- The pairing box in `settings-devices.tsx:105-125` already calls the
  SAME fleet layer (`fleetStore.redeemPairingUrl(url, webDeviceLabel())`)
  — the drawer's form and the Devices box are one action in two
  vocabularies; the drawer is the duplicate.
- **`fleet.active` is invisible and unswitchable today.** It is set only
  by pairing (`lib/engine-store.ts:149`) and the remove-fallback
  (`:160-168`); the drawer's "Switch" was deleted in ticket 31, and
  **nothing in the UI calls `setActive` anymore** (grep: only the
  definition at `lib/engine-store.ts:153` remains — verified: the only
  other `setActive` hits in `web/packages/app/src` are
  `rightPaneStore.setActive` and local component state). With 2+ engines
  paired, Settings → Remote access/Agents/Accounts silently configure the
  last-paired engine. Those pages get their engine from
  `useEngineSession()` → routed/active engine
  (`state/session-provider.tsx:101-107`); on `/settings/*` routes the
  routed key is always `fleet.active`.
- Data sources for the folded rows: `useFleet()` (pairing list,
  `state/fleet.ts:30-32`) + `useFleetRegistry()` (one
  `EngineEntrySnapshot` per engine: `key`, `state`
  connected/reconnecting/off, `lastError`, `info`,
  `state/fleet.ts:79-81`). The registry syntheses and merged rows are
  ticket 31's, unchanged here.
- Shared CSS vocabulary already exists (`.settings-page`,
  `.settings-card`, `.settings-row`, `.badge`, `.dot-*`, `.btn btn-ghost`)
  — reuse it; the drawer's `.engine-*` classes die with the drawer.

## 2. Spec

### 2.1 Delete the user-menu "Engines" drawer

**Removals (in order, mechanical):**

1. Move `describeRedeemError` (`engine-drawer.tsx:207-222`) to
   `lib/pairing-errors.ts` **verbatim** (same branches, same strings) —
   it is already imported by `pair-page.tsx:5` and
   `settings-devices.tsx:9`; update both import paths.
2. Delete `components/engine-drawer.tsx` entirely.
3. `account-row.tsx`: delete the "Engines" menu item (the
   `emitShortcut("open-engines")` button, 84-95) and its comment
   (79-83). The menu keeps exactly two things: the muted identity line
   "Stored on this device" (78) and the "Settings" row (96-99) — desktop
   parity (`shell.rs:5290-5302`).
4. `app-shell.tsx`: delete the `EngineDrawer` import (67), the
   `onShortcut("open-engines", …)` registration (212), the `drawerOpen`
   state, and the `<EngineDrawer …>` mount (700).
5. `state/shortcuts.ts`: remove `"open-engines"` from the shortcut union
   (515-519).
6. `app.css`: delete the drawer-only classes. Grep each class for other
   users before deleting. Known keep: `.add-engine-label` (used by
   `pair-page.tsx:60`). The `.conn-*`/`.dot-*` status vocabulary is
   shared with the sidebar — keep those.

**Children (user menu, after)** — identity line, then Settings. Nothing
else.

**States** — none new. Popovers the menu had are gone.

**Interactions** — Settings click navigates to `/settings/devices`
(unchanged, ticket 29). No keyboard path to engines exists anymore
(there was none on the desktop either).

**Text** (verbatim, the two survivors): "Stored on this device";
"Settings".

**Data** — none; the menu stops reading `useEngineSessions()` for the
identity subline only insofar as the Engines row required it (the trigger
row's own name/subline logic stays).

### 2.2 Settings → Devices — the single pairing surface

Row inventory (research S2.d, verbatim):

| Row | Content | Source today |
| --- | --- | --- |
| Pairing box | "Paste a pairing URL" + Connect; hint "Create a pairing link in the engine's Remote access settings, then paste it here." | `settings-devices.tsx:172-196` (keep verbatim) |
| Engine rows (one per paired engine) | host (`engineHost`), connection state dot + label, session state ("Session revoked" → "Pair again" → `/pair`), Forget (confirm) | folded from `engine-drawer.tsx:99-158` — the drawer's per-engine row, minus the urgent dot (sidebar owns urgency) |
| Device rows (per engine) | current rows: platform tile + presence, meta line, This device badge, Rename | `settings-devices.tsx:198-312` (keep) |

**Layout** — page order becomes: header → subtitle → error strip →
pairing box card → *engines card (new)* → devices card → rename dialog.
The engines card is a `.settings-card` of `.settings-row`s, one per
`fleet.engines` entry (sorted as the store sorts them, by `baseUrl`).

**Children (engine row, in order)** — status dot (`.dot-connected` /
`.dot-reconnecting` / `.dot-parked`) + `.settings-row-main` column:
`.settings-row-title` = `engineHost(engine.baseUrl)`; meta line =
connection label · identity (`Engine {deviceId.slice(0,8)}` when
`engine.deviceId !== null`, else "Identity unverified"); trailing
actions: "Pair again" (`.btn btn-ghost`, only when the entry is
parked/`pairable`) and `RemoveEngineButton` ("Forget" → "Forget?"
confirm, danger ghost).

**States**

| state | condition | what changes |
| --- | --- | --- |
| Connected | registry entry `state === "connected"` | emerald dot, label "Connected", no "Pair again" |
| Reconnecting | `state === "reconnecting"` | amber dot, label "Reconnecting…", no "Pair again" |
| Parked | `state === "off"` | faint dot; label "Session revoked", or "Engine changed" when `entry.lastError` contains "identity"; "Pair again" appears |
| No registry entry yet | `entry === null` (engine just paired, spawn pending) | "Starting…" label, no actions |
| Forget confirming | first Forget click | button flips to "Forget?" (danger ghost); second click calls `forgetEngine(engine.baseUrl)` |

Port the label/dot/pairable mapping from `entryConnection`
(`engine-drawer.tsx:75-97`) as a pure helper next to the rows.

**Interactions** — "Pair again" → `navigate({ to: "/pair" })` (the
revoked-credential re-pair flow; `/pair` pastes/redeems a fresh URL).
"Forget?" → `forgetEngine(engine.baseUrl)` (the `state/fleet.ts:93-95`
wrapper: unpersist + registry stops + cache delete). Neither action
changes the pairing code path itself.

**Motion** — none new. Rows reuse existing hover transitions.

**Text** (verbatim from the drawer): "Pair again"; "Forget"; "Forget?";
"Session revoked"; "Engine changed"; "Connected"; "Reconnecting…";
"Starting…"; "Identity unverified"; `Engine {shortId}`; the engines-card
section header "Engines". Empty fleet (no engines paired — only
reachable pre-first-pair): "No engines paired yet. Pair one above."
(the drawer's copy, adjusted to the new location).

**Data** — reads: `useFleet()` (`fleet.engines`, `fleet.active`),
`useFleetRegistry()` (per-engine `state`/`lastError`/`info`). Writes:
`forgetEngine(baseUrl)`. No new RPC; no pairing-path changes
(`fleetStore.redeemPairingUrl` stays the only redeem entry).

### 2.3 Engine indicator + switcher on engine-addressing settings pages

**Layout**

| property | value | source |
| --- | --- | --- |
| placement | inline after the page subtitle text (`settings-remote-access.tsx:111-113`, `settings-agents.tsx:160`, `settings-accounts.tsx:239`) | research S2.e "Settings pages address a visible engine" row |
| trigger | text pill: `Engine {engineHost(active)}` — 11px, `theme.text_muted.opacity(0.8)`, icon `chevronDown`-equivalent 12px at 0.5 opacity | new (no desktop analog — the desktop's settings always address the local engine by construction, `remote_access.rs:37-39`) |
| popover | existing anchored-menu pattern (the app's shared menu/popover component), one row per `fleet.engines`: dot + `engineHost`, active row checked | reuse; MENU_IN/MENU_OUT motion catalog (ticket 28 §2.10) |

**States**

| state | condition | what changes |
| --- | --- | --- |
| hidden | `fleet.engines.length <= 1` | no indicator at all (the single-engine case is unambiguous) |
| shown | `fleet.engines.length > 1` | `Engine {host}` pill naming `fleet.active` |
| popover open | pill clicked | engine list; the active engine's row shows a check |

**Interactions** — click a popover row → `fleetStore.setActive(row.baseUrl)`.
This is the fix for the research's finding that **no caller of `setActive`
remains** (`lib/engine-store.ts:153` defines it; grep shows no UI caller):
the switcher becomes its caller, and changing `fleet.active` re-routes
`useEngineSession()` on `/settings/*` routes
(`session-provider.tsx:101-107`), so the page's data reloads against the
chosen engine (the pages already reset on session identity change — e.g.
`settings-remote-access.tsx:54-62`, `settings-devices.tsx:55-60`).

**Motion** — popover open/close reuses the shared `MENU_IN` (140ms) /
`MENU_OUT` (100ms) catalog + 400ms re-open guard; honors
`prefers-reduced-motion`.

**Text** — `Engine {host}`; no other new copy.

**Data** — reads: `useFleet()`; writes: `fleetStore.setActive(baseUrl)`.
No RPC.

### 2.4 Case matrix (copied verbatim from research S2.c — the core deliverable)

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

### 2.5 Desktop settings IA (reference — copied verbatim from research S2.b)

| # | Section (label) | Owns | Web counterpart |
| --- | --- | --- | --- |
| 1 | Devices | engines this app pairs to + the merged device registry | `settings-devices.tsx` |
| 2 | Remote access (label, `shell.rs:420`) | this engine's listener + its paired sessions | `settings-remote-access.tsx` |
| 3 | Harnesses → "Agents" | per-device harness toggles + titles | `settings-agents.tsx` |
| 4 | Agents → "Accounts" | per-device CLI logins | `settings-accounts.tsx` |
| 5-9 | Appearance / Files / Notifications / Shortcuts / Archived | device-local prefs + engine chats | shipped (tickets 28/29) |
| 10 | Appshots | desktop/Linux-only | never on web |

**Resulting web settings nav**: unchanged 9 rows
(`settings-nav.tsx:21-31`) — the unification deletes a *drawer*, not a
section, and routes all pairing through Devices, matching the desktop
exactly.

## 3. Pure logic to port

- `settingsEngineLabel(fleet: FleetState): string | null` (new,
  `lib/settings-engine.ts`): `engineHost(fleet.active)` when
  `fleet.engines.length > 1`, else `null`. No desktop analog (the
  desktop's settings address the implicit local engine,
  `remote_access.rs:37-39`). Test:
  `settingsEngineLabelNamesActiveEngineOnlyWhenFleetIsPlural` — null with
  0 or 1 engines, the active engine's host with 2+.
- The engine-row state mapping (dot / label / pairable) ported from
  `entryConnection` (`engine-drawer.tsx:75-97`): pure function
  `engineConnection(entry: EngineEntrySnapshot | null)`; no desktop test
  exists (the desktop never had this row) — cover with a table-driven web
  test `engineConnectionLabelsParkedAndIdentityChanged` if the suite
  wants it, else rely on the rows' rendering.
- Nothing else is ported: this ticket deletes UI and re-homes rows; the
  registry/fleet mechanics are ticket 31's, already tested
  (`tests/registry.test.ts`, `tests/fleet-view.test.ts`).

## 4. Gaps this ticket closes

Copied verbatim from research S2.e:

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
| --- | --- | --- | --- | --- |
| User-menu engine list | NOT ON DESKTOP (invented) | none — user menu = Settings only (`shell.rs:5290-5302`) | "Engines" drawer (`engine-drawer.tsx:25-47`, `account-row.tsx:84-95`) | Delete drawer + menu row; move `describeRedeemError` to `lib/`; keep `/pair` |
| Duplicate pairing entry points | NOT ON DESKTOP | one: Devices box (`devices.rs:125-158`) | three: drawer form, Devices box, `/pair` paste (`engine-drawer.tsx:160-205`, `settings-devices.tsx:105-125`, `pair-page.tsx:39-52`) | Drawer removed → two (Devices for paste, `/pair` for token URLs) — same as desktop's one + URL landing |
| Settings pages address a visible engine | DIFFERENT MODEL | Remote access/Accounts always the local engine (`remote_access.rs:37-39`, `accounts.rs:492` via `device_target`) | pages use `fleet.active`, last-paired, unlabeled (`session-provider.tsx:101-107`, no `setActive` caller) | Show "Engine {host}" next to the page subtitle when `fleet.engines.length > 1`; optionally a switcher popover |
| Engine rows in Devices (fleet health) | Devices page rows are device rows; fleet state visible via synthesized "Remote engine" rows (`engine_registry.rs:117-129`) | drawer rows only (`engine-drawer.tsx:99-142`); Devices shows parked-engine rows as off+Forget (`settings-devices.tsx:90-103`) | Fold drawer's engine row into Devices above the device rows (per S2.d table) |
| "Pair again" entry after revoke | Devices box re-pair (re-enters the flow, `devices.rs:125-158`) | drawer "Pair again" → `/pair` (`engine-drawer.tsx:111-113`); gate-card escape (`session-provider.tsx:109-124` retry) | Engine row's "Pair again" in Devices → `/pair` |

## 5. Do not

- Do not touch the pairing flows: `fleetStore.redeemPairingUrl`,
  `pairEngine`, `parsePairingUrl`, the `/pair` redeem logic — unchanged.
  This ticket moves UI entry points only. Ticket 46 owns the Remote
  access session list and the "This browser" badge; ticket 47 owns the
  accounts login flow — mount the indicator on those pages but change
  nothing else in them.
- Do not delete or weaken the `/pair` route — token URLs land there
  ("Pair again" targets it), and the desktop's analog is the URL landing
  itself.
- Do not re-add the urgent-chat dot to the Devices engine rows — the
  sidebar owns urgency (tickets 08/31).
- Do not rename the "Devices" section or its subtitle (desktop parity,
  `shell.rs:419`, ticket 29 §2.1).
- Do not restore removed cloud/sync concepts (no accounts, no WorkOS, no
  edge, no sync rooms — AGENTS.md rebrand boundaries).
- Do not add a native folder picker (nothing here needs one).
- Do not add a titlebar engine indicator or a second engine surface
  anywhere else — the indicator exists only on the three engine-addressing
  settings pages.

## 6. Acceptance

- [ ] web Settings has no Engines drawer; Devices lists per-engine rows
      with state/Pair again/Forget.
- [ ] The user menu offers exactly the identity line and "Settings"
      (desktop parity); no `open-engines` shortcut id, `EngineDrawer`
      import/mount, or drawer class survives (grep `open-engines`,
      `EngineDrawer`, `engine-drawer` in `web/packages/app/src` → zero
      code references; stale doc comments that still name the drawer —
      e.g. `settings-devices.tsx:26` — are reworded).
- [ ] Pairing entry points number exactly two: the Devices box (paste)
      and `/pair` (token URLs); both still pair via
      `fleetStore.redeemPairingUrl` and the registry starts supervising
      immediately.
- [ ] Revoked engine: its Devices engine row shows "Session revoked"
      ("Engine changed" on identity mismatch) with a working "Pair again"
      → `/pair`, and Forget (confirm) removes it from the fleet.
- [ ] With 2+ engines paired, Remote access/Agents/Accounts each show
      `Engine {host}` naming `fleet.active`; switching via the popover
      retargets the page (its data reloads for the chosen engine). With
      0–1 engines the indicator is absent.
- [ ] `lib/pairing-errors.ts::describeRedeemError` is byte-identical to
      the old drawer export; `pair-page.tsx` and `settings-devices.tsx`
      import it from the new path.
- [ ] CSS: no orphaned drawer classes remain; `.add-engine-label` still
      styles the `/pair` label.
- [ ] Unit tests: `settingsEngineLabelNamesActiveEngineOnlyWhenFleetIsPlural`
      → `web/packages/app/tests/settings-engine.test.ts`.
- [ ] Screenshot pair, desktop vs web, states: (a) Settings → Devices
      with 2 paired engines (one Connected, one "Session revoked" with
      Pair again visible) above the device rows; (b) the user menu open
      (identity + Settings only); (c) Remote access with the engine
      indicator + popover open.
- [ ] `pnpm -r build` green; package vitest green.
- [ ] No new literal hex/px where a `--rb-*` token exists.

## Comments

### Implementer note (2026-09-19)

Landed as specified. Notes and small judgment calls:

- `describeRedeemError` moved to `lib/pairing-errors.ts` byte-identical
  (verified against the deleted drawer's export with a script compare);
  `pair-page.tsx` and `settings-devices.tsx` import the new path.
- The `entryConnection` port lives as `engineConnection` in
  `lib/settings-engine.ts` (not inside the route file) so the suite's
  pure-function test convention covers it — the table-driven
  `engineConnectionLabelsParkedAndIdentityChanged` test is included.
- The indicator popover reuses `PickerCard` + `MenuRow` (the
  DeviceSwitcher shape); rows are connection dot · host · check-on-active,
  with the dot fed from `useFleetRegistry()` — a store read, not an RPC, so
  the "reads: useFleet(), no RPC" contract holds. `fleetStore.setActive`
  gained its first UI caller here.
- Drawer deletions went beyond the class list where grep found more
  orphans: the `.drawer` entries in the scrollbar-hiding selector lists,
  the phone media-query `.drawer`/`.drawer-backdrop` block, and stale
  comments in `state/escape.ts`, `app.css`'s z-ladder, and the
  login-dialog scrim note. `--rb-z-drawer` (the ladder token, not a class)
  was kept — the documented six-tier scale still reserves the drawer tier.
- `.add-engine-label` kept (pair-page.tsx:60); `.pair-form` kept as the
  sole selector where it shared a rule with the dead `.add-engine` form.
- Not done: the acceptance's screenshot pair (desktop vs web) — no
  runnable desktop/web session pair was available in this environment;
  static verification (build, vitest 1207, grep-zero drawer references)
  stands in for it.
- Verification: `pnpm -r build` green; `pnpm test` in packages/app green
  (1207 = base 1205 + the 2 new settings-engine tests).
