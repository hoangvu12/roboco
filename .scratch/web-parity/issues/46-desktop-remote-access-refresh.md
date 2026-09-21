# 46 — Desktop Remote access refresh-on-visit (and the web "This browser" badge)

**What to build:** The desktop's Settings → Remote access page is never
stale again: re-entering the section refetches the engine's snapshot, so a
web client paired from the browser shows up ("Roboco web on Windows") the
moment you come back to the page. The refresh control gets a text label
("Refresh") instead of a bare icon, and the empty state says "clients",
not "devices". On the web, the paired-sessions list badges the row that is
this browser's own Session ("This browser") so it stops reading like a
stranger's device. This is a **desktop ticket** (the fixes live in
`crates/ui`) filed with the web-parity batch for traceability; its web
half is one badge plus one string.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

**Research:** `../research-2026-09-19/settings-remote-access-accounts.md` §0 (surface map), S1.a–S1.e (mechanism trace, verdict, root cause, unified model, gap rows).

**Desktop reference (for lookups only):** `crates/ui/src/shell.rs::settings_outlet` (RemoteAccess arm 3332-3345 — the entity cached forever), `::open_settings` (3268-3273 — Harnesses' "Recreate per visit" pattern to copy); `crates/ui/src/settings/remote_access.rs` (page struct 10-17, `new` 20-31, `request` 33-81, refresh action 103-111, error strip 115-117, sessions 182-246, empty state 184-192); `crates/ui/src/settings/accounts.rs` (labeled Refresh to match, 1424-1438); `crates/ui/src/settings/devices.rs` (the `cx.observe` counter-model, 104); `crates/engine/src/pairing.rs` (redeem 84-128 — the INSERT at 114-122, authenticate/last_seen 130-138, list_sessions 140-146); `crates/engine/src/remote_access.rs` (snapshot 234-242); `crates/engine/src/rpc.rs` (GET/SET/CREATE/REVOKE all unary via the controller, 859-876); `crates/engine/src/listener.rs` (loopback is credential-free 34-41); `crates/engine/src/workspace_host.rs` (the engine's own device-row upsert 117-147).

**Desktop files to touch:**
| File | Change | Owns |
| --- | --- | --- |
| `crates/ui/src/shell.rs` | edit | `settings_outlet`/`open_settings` — recreate `RemoteAccessPage` per visit, the Harnesses pattern |
| `crates/ui/src/settings/remote_access.rs` | edit | label the Refresh ghost action (103-111); empty-state string (191) |

**Web files to touch:**
| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/routes/settings-remote-access.tsx` | edit | "This browser" badge on the own session row (rows render 183-198); empty-state copy (181) |
| `web/packages/app/src/lib/remote-access.ts` | edit | `sessionIsSelf` (+ `ownSessionId` helper) beside `sessionRows` (39-49) |
| `web/packages/app/tests/remote-access-view.test.ts` | edit | `sessionIsSelfMatchesStoredSessionId` |

## 1. Context a fresh session needs

- Surface map (research §0, the rows this ticket lives in), verbatim:

| Surface | Desktop | Web |
| --- | --- | --- |
| Settings → Remote access | `crates/ui/src/settings/remote_access.rs` | `routes/settings-remote-access.tsx` |
| Settings → Devices | `crates/ui/src/settings/devices.rs` | `routes/settings-devices.tsx` |
| Engine-side session store | `crates/engine/src/pairing.rs` (`pairing.sqlite3`) | same file — engine-owned |
| Engine-side listener | `crates/engine/src/listener.rs` + `remote_access.rs` | same |

- Vocabulary: **Session** (capital S) = the credential a paired client
  holds — a web browser, a desktop, a phone are all Sessions, never
  devices. **device** = a machine running an engine, as represented to
  paired clients (CONTEXT.md:92-97). **pairing** = one-time registration;
  never "login". The web client's device label is
  `"Roboco web on {platform}"` (`lib/engine-store.ts:251-260`).
- **The engine records web pairings identically to native ones.** The
  pair page redeems the token → `POST /pairing/redeem` →
  `PairingStore::redeem` deletes the single-use code and INSERTs a
  `paired_sessions` row — `id, verifier (hashed), label, created_at,
  last_seen, revoked_at` (`pairing.rs:114-122`); the label is the
  caller's, i.e. "Roboco web on Windows" (`pairing.rs:103-109`). There is
  **no client-kind column** — web, desktop, and phone rows differ only by
  label. Every (re)connect refreshes `last_seen` (`pairing.rs:130-138`).
  `RemoteAccessController::snapshot` returns every row
  (`remote_access.rs:234-242`, `list_sessions` `pairing.rs:140-146`);
  the RPC handler routes GET/SET/CREATE/REVOKE to it (`rpc.rs:859-876`).
  Both binds share one data dir — one store, no split. So the S1 report
  ("web pairing doesn't show") was never an engine gap; it is display
  staleness:
- **Desktop root cause chain** (research S1.c):
  1. `settings_outlet` creates `RemoteAccessPage` once and caches the
     entity forever (`shell.rs:3332-3345`); only Harnesses is recreated
     per visit (`shell.rs:3268-3273`, "Recreate per visit"). The page has
     no `cx.observe` subscription (struct fields `remote_access.rs:10-17`,
     unlike `DevicesPage` at `devices.rs:104`) and no session watch. Open
     the page → mint a link (a snapshot with `sessions: []` lands) → pair
     the web in the browser → return: the cached page still shows the
     pre-pairing snapshot — "No devices paired yet."
  2. The only refresh affordance is an icon-only ghost button
     (`remote_access.rs:103-111`); the web's says "Refresh"
     (`settings-remote-access.tsx:123-125`).
  3. The empty state says "devices" (`remote_access.rs:191`) while the
     rows are client Sessions — misleading next to the Devices page.
- The web page already mounts fresh per visit (`:55-62`, resets on engine
  change) — the staleness asymmetry is desktop-only.
- **The web pairing never appears in Settings → Devices — by design.**
  The engine's device registry contains engine hosts: its own row
  upserted at boot (`workspace_host.rs:117-147`); a client pairing writes
  no device row. The web client is a Session on the Remote access list,
  not a device. Document this here so nobody "fixes" it later.
- The desktop's local loopback client has **no Session at all**
  (`listener.rs:34-41` — local policy is credential-free), so the desktop
  never needs a self-marker; only the web (which always pairs) does. The
  web's own row is reachable today with no marker
  (`lib/remote-access.ts:39-49`, "Paired device" fallback at `:44`).
- No watch/stream exists for pairing sessions — `rpc.rs:859-876` is
  unary-only — so per-visit refresh is the parity-minimal fix (research
  open question 4).

## 2. Spec

### 2.1 Desktop: refresh on section (re)entry

**Change** — extend the recreate-per-visit pattern to the Remote access
page, exactly as Harnesses does it (`shell.rs:3268-3273`):

- In `open_settings` (`shell.rs:3268`), add `SettingsSection::RemoteAccess`
  to the recreate branch alongside `Harnesses` (set
  `self.remote_access_page = None;` before routing), **or** equivalently
  drop the one-time cache in `settings_outlet`'s RemoteAccess arm
  (`shell.rs:3332-3345`) so each entry into the section constructs a fresh
  `RemoteAccessPage::new` (whose constructor already fires
  `GET_REMOTE_ACCESS`, `remote_access.rs:29`). Pick one site; keep the
  "Recreate per visit" comment style and extend it to say why (the page
  has no observe/watch; the sessions list must reflect pairings made
  since the last visit).

**Behavior**

| event | today | after |
| --- | --- | --- |
| Open Settings → Remote access (first time) | construct + one GET | same |
| Re-enter the section (nav click while cached) | cached entity, NO fetch (`shell.rs:3332-3345`) | fresh page, fresh GET (mirror of Harnesses, `shell.rs:3269-3273`) |
| Mutation (toggle/mint/revoke) | `request()` refetches after every mutation (`remote_access.rs:48-56`) | unchanged |

**Rejected alternative** (record in Comments if revisited): a
`WatchPairingSessions`-style stream — none exists (`rpc.rs:859-876`
unary-only); per-visit refresh is the parity-minimal fix and matches the
Harnesses precedent.

**Data** — none new: the page's existing `GetRemoteAccess` on
construction does all the work once the page is actually reconstructed.

### 2.2 Desktop: label the Refresh control

**Layout**

| property | value | source |
| --- | --- | --- |
| control | the existing ghost action at `remote_access.rs:103-111` — keep the 14px REFRESH glyph | `remote_access.rs:106-108` |
| label | add the text "Refresh" after the icon, matching the Accounts page's labeled Refresh (`accounts.rs:1424-1438`) | `accounts.rs:1424-1438` |

**Interactions** — unchanged: click → `GET_REMOTE_ACCESS`
(`remote_access.rs:109-111`).

**Text** — "Refresh" (verbatim, the Accounts control's label).

### 2.3 Copy fix (both sides): clients, not devices

**Text** — the paired-sessions empty state, one string, both sides:

| where | today | after |
| --- | --- | --- |
| desktop `remote_access.rs:191` | "No devices paired yet." | "No clients paired yet." |
| web `settings-remote-access.tsx:181` | "No devices paired yet." | "No clients paired yet." |

(The research offers "No clients paired yet." or "No paired sessions.";
pick the former — it names the population the rows actually are.) Do not
reword anything else: the subtitle ("Pair your other devices with this
engine", `remote_access.rs:95`) and the link-card copy stay verbatim.

### 2.4 Web: "This browser" badge on the own session row

**Data** — the routed engine's stored Session id:
`session.engine.sessionId` (`EngineSession.engine: StoredEngine`,
`state/engine-session.ts:14-20`; the field is `sessionId`,
`lib/engine-store.ts:20`). The page already holds `session`
(`settings-remote-access.tsx:23`).

**Pure logic** — `lib/remote-access.ts`:

```ts
export function ownSessionId(stored: StoredEngine): string | null
export function sessionIsSelf(row: SessionRow, stored: StoredEngine): boolean
```

`sessionIsSelf` is `row.id === ownSessionId(stored)` (null id never
matches). `sessionRows` stays shape-compatible; the route computes
`sessionIsSelf(row, session.engine)` while mapping rows (or
`sessionRows` gains an optional `stored` argument — implementer's choice,
keep both exported and tested).

**Children (session row, in order)** — unchanged from today (dot, label,
"Last seen …"/"Revoked" meta, Revoke), plus: when
`sessionIsSelf(row, session.engine)`, a `.badge` pill reading
"This browser" before the Revoke button (reuse the existing `.badge`
class — the 10.5px bordered pill; no new CSS needed).

**States**

| state | condition | what changes |
| --- | --- | --- |
| self | `row.id === session.engine.sessionId` | "This browser" badge; Revoke stays available (revoking your own session is legal — the next dial parks) |
| other | any other row | unchanged |
| revoked self | self + `revokedAt !== null` | badge still renders; row keeps the revoked styling |

**Interactions** — none new. Revoke on the self row keeps today's
behavior (`revokePairingSession`).

**Text** — "This browser" (the web's device-label analog; the desktop has
no self-session so there is no desktop string to port).

**Motion** — none.

### 2.5 Documented invariant: the web client never appears in Devices

Not a code change — a note for every future reader, from the research:
the engine's device registry contains *engine hosts* only (its own row
upserted at boot, `workspace_host.rs:117-147`); a client pairing writes a
`paired_sessions` row, never a device row. The web client is a Session on
Remote access. Someone hunting for "the web device" in Devices finds only
the engine's machine — that is correct, not a bug. (Kept in §5 "Do not"
as well so it survives skimming.)

## 3. Pure logic to port

- `ownSessionId(stored: StoredEngine): string | null` and
  `sessionIsSelf(row: SessionRow, stored: StoredEngine): boolean` —
  new, web-only. **No desktop test exists** (the desktop has no
  self-session: loopback is credential-free, `listener.rs:34-41`); write
  the web unit test `sessionIsSelfMatchesStoredSessionId` →
  `web/packages/app/tests/remote-access-view.test.ts` (cases: id match →
  true; different id → false; `sessionId` null → always false; revoked
  self row still true).
- Desktop-side page-lifecycle change: **no unit test exists** for the
  Harnesses recreate-per-visit behavior either (`shell.rs:3269-3273`) —
  say so in Comments after landing and verify by the §6 manual check.
  The desktop behavior to mirror is named: HarnessesPage "Recreate per
  visit" (`shell.rs:3269-3273`).

## 4. Gaps this ticket closes

Copied verbatim from research S1.e:

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
| --- | --- | --- | --- | --- |
| Remote access page refreshes on section (re)entry | MISSING | cached entity, no refetch (`shell.rs:3332-3345`, `remote_access.rs:29`) | fresh mount per visit (`settings-remote-access.tsx:55-62`) | Desktop: refetch `GetRemoteAccess` in `settings_outlet` when entering the section (cheapest: recreate the page per visit like Harnesses, `shell.rs:3269-3273`) |
| Refresh affordance labeled | WRONG VALUE (discoverability) | icon-only ghost (`remote_access.rs:103-111`) | text "Refresh" (`settings-remote-access.tsx:123-125`) | Desktop: add the "Refresh" label (matches Accounts' labeled Refresh, `accounts.rs:1424-1438`) |
| Web pairing visible in Paired sessions | CONFIRMED WORKING | rows render `label`/`Paired device` (`remote_access.rs:194-227`) | same (`lib/remote-access.ts:39-49`) | no fix — engine records it (`pairing.rs:114-122`); only staleness hides it |
| "This client" marker on the session row | MISSING | none — desktop has no session row for itself (`listener.rs:34-41`) | none (`settings-remote-access.tsx:183-198`) | Web: compare each row's `id` to the stored engine's `sessionId` (`lib/engine-store.ts:20`) and badge it; desktop N/A (loopback has no session) |
| Empty-state copy names clients, not devices | WRONG VALUE | "No devices paired yet." (`remote_access.rs:191`) | same string (`settings-remote-access.tsx:181`) | Both: "No clients paired yet." or "No paired sessions." — one-word fix, kills the Devices confusion |
| Client kind on PairedSession | MISSING (by design) | no field (`crates/proto/src/remote.rs:22-28`) | same | optional: derive a "Web" chip from the label prefix at render time; do not add a wire field without a desktop need |

## 5. Do not

- Do not "fix" the web client's absence from Settings → Devices — by
  design: clients are Sessions, not devices
  (`workspace_host.rs:117-147`); the Remote access list is their only
  surface.
- Do not add a client-kind field to the `PairedSession` wire type
  (`crates/proto/src/remote.rs:22-28`) — rows differ by label only; a
  "Web" chip, if ever wanted, derives from the label at render time.
- Do not add a `WatchPairingSessions` stream — none exists
  (`rpc.rs:859-876` is unary-only); per-visit refresh is the
  parity-minimal fix.
- Do not reword the page subtitle, the pairing-link copy, or the "Paired
  sessions" header — only the empty-state string changes.
- Do not add a countdown timer or QR code to the pairing link (ticket 29
  §2.5 settled both: `expiresAt` is fetched and never displayed; desktop
  has no QR).
- Do not touch the settings IA / drawer removal (ticket 45) or the
  accounts login flow (ticket 47).
- Do not restore removed cloud/sync concepts; do not add a native folder
  picker.

## 6. Acceptance

- [ ] Pair a web client from the browser → desktop Settings → Remote
      access shows "Roboco web on Windows" after re-entering the page
      (leave the section, come back — no manual Refresh needed).
- [ ] Desktop: the Refresh control reads "Refresh" (icon + label),
      matching the Accounts page's control.
- [ ] Both clients' paired-sessions empty state reads "No clients paired
      yet."
- [ ] Web Remote access: the row whose `id` equals
      `session.engine.sessionId` carries the "This browser" badge; other
      rows are unbadged; Revoke still works on the badged row.
- [ ] The web pairing still never appears in Settings → Devices (verify
      unchanged — by design, §2.5).
- [ ] Desktop build/tests green: `cargo check -p roboco-ui` and
      `cargo test -p roboco-ui` from the repo root.
- [ ] Web (files touched): `pnpm -r build` green; package vitest green
      (`sessionIsSelfMatchesStoredSessionId` in
      `tests/remote-access-view.test.ts`).
- [ ] No new literal hex/px where a `--rb-*` token exists.

## Comments

**Implementer note (2026-09-19, landed in `fix(ui): ticket 46 remote access
list refresh-on-visit and copy`):**

- Chose the `open_settings` site (§2.1's first option): added
  `SettingsSection::RemoteAccess` to the recreate-per-visit branch
  alongside `Harnesses` (`shell.rs:3271-3280`, comment extended with the
  why — no observe/watch on the pairing store, so the constructor's
  `GetRemoteAccess` is the only refetch). `settings_outlet`'s RemoteAccess
  arm is untouched; back/forward re-entry uses the cached entity exactly
  as Harnesses does (mirror-of-precedent, per spec).
- Refresh label added after the 14px glyph
  (`remote_access.rs:109`), matching this file's own labeled ghost actions
  ("Create pairing link", "Copy") — Accounts' 12.5px text-size tweak is
  not carried over (its context differs); the label text is verbatim
  "Refresh".
- Empty state is "No clients paired yet." on both sides
  (`remote_access.rs:192`, `settings-remote-access.tsx:185`); subtitle and
  link-card copy untouched.
- Web: went with "the route computes `sessionIsSelf(row, session.engine)`
  while mapping rows" (the first of the two offered options);
  `sessionRows` keeps its shape. The badge is guarded by
  `session !== null` (`useEngineSession()` returns `EngineSession | null`)
  and reuses the existing `.badge` pill — no new CSS. `ownSessionId`
  returns null for a non-string `sessionId` (shape-drifted storage) so a
  null id never matches, per §2.4.
- Rejected-alternative record (§2.1): a `WatchPairingSessions` stream was
  not attempted — `rpc.rs:859-876` is unary-only; per-visit refresh
  remains the parity-minimal fix.
- Desktop tests (§3/§6): no unit test exists for the recreate-per-visit
  behavior (none exists for the Harnesses precedent either) — verified by
  the full suite staying green plus the §6 manual re-entry check; the web
  unit test `sessionIsSelfMatchesStoredSessionId` covers the four
  specified cases (match / different id / null sessionId / revoked self).
- Verification on Windows (this worktree): `cargo check -p roboco-ui`
  green (pre-existing dead-code warnings only); `cargo test -p roboco-ui`
  → 934 passed, 0 failed, 5 ignored; web `pnpm -r build` green;
  `web/packages/app` `pnpm test` → 72 files, 1169 tests passed.
  `roboco-engine` not touched, so not re-checked.
- No new literal hex/px anywhere (badge reuses `.badge`; the 14px icon
  size was pre-existing and kept).
