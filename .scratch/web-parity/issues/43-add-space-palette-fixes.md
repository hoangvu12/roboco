# 43 — Add-space palette fixes: deviceless-open state, entry-point verification, mirroring residue

**What to build:** After this ticket, every "New project…" entry point — the
spaces-menu row, the project-popover row, and `Mod+K` — provably opens the
add-space palette, and the palette always reaches a terminal state: a
browsable folder list, or an explicit error with a working Retry — never an
eternal skeleton. Opening the palette before the routed engine's device rows
have streamed resolves itself the moment a row lands; if rows never land, an
honest error replaces the skeleton. Two mirroring residues close with it:
chats whose spaces frame lags no longer vanish from the sidebar, and
switching the active engine no longer remounts the whole sidebar (which today
force-closes an open palette).

**Blocked by:** None — can start immediately. Ticket 39 (send path + id
namespace, cut from the same research) runs in **parallel**; cross-referenced
in §5, never a blocker.

**Status:** ready-for-agent

**Research:** `../research-2026-09-19/spaces-sidebar-mirroring.md` S2 (all
four parts — §a web trace, §b desktop reference, §c root cause, §d gap rows),
S3 (§a–§c for the pairing-topology context, §d's two actionable rows),
"Consolidated gap table" rows 5, 6, 9, 10, "Desktop-only items NOT to port"
(native folder picker, relay `targetDeviceId` bullets).

**Desktop reference (for lookups only):**
`crates/ui/src/shell/spaces.rs::open_add_space` (1825-1904),
`::AddSpaceFlow` (186), the spaces-menu "New project…" row (1223-1248) and
`::activate_spaces_menu_row` (740-748); `crates/ui/src/pickers.rs` project
popover "New project…" / "Don't work in a project" rows (2062-2102);
`crates/ui/src/shell.rs:362-364` (the fixed `Mod+K` binding, cited by ticket
11); `crates/ui/src/state.rs:1432-1462` (`overview_chats`/`sidebar_chats` —
the dangling-space rule); `crates/ui/src/engine_registry.rs:792-793` (the
one-drive-loop row write). Engine side:
`crates/engine/src/rpc.rs:1609-1642` (`ListFolders` / `PrepareSpacePath` /
`ListDrives` handlers), `rpc.rs:687-696` (`Mutate createSpace`);
`crates/engine/src/repos.rs:34` (`FOLDER_LIST_TIMEOUT`), `::list_folders_for_query`
(1226-1233), `::list_folders_options` (1305-1337), `::list_drives`
(1345-1354); `crates/engine/src/space_paths.rs::prepare` (13-54).

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/state/add-space.ts` | edit | `open()` (:150-183) — the deviceless-open state: device-wait + deadline; `#loadFolders`'s null-device early return (:619-621); `retryLoad()` (:438-444) — Retry semantics for the deviceless error; a new `resolveDevice()`-shaped entry point |
| `web/packages/app/src/components/add-space-palette.tsx` | edit | the body's state branches (:123-126, :430-458) — the deviceless error row + Retry; the device-resolve effect over `useWatchSnapshot(session)` |
| `web/packages/app/src/lib/view.ts` | edit | `toChatRow`'s dangling-spaceId gate (:473-477) — hide only once the spaces RowSet is `loaded` |
| `web/packages/app/src/components/app-shell.tsx` | edit | the `<SidebarBody key={fleet.active ?? "none"} />` remount key (:561) |
| `web/packages/app/tests/add-space.test.ts` | edit | the deviceless state-machine tests (extend the existing `addSpaceStore` suite's fake-session pattern) |
| `web/packages/app/tests/view.test.ts` | edit | the dangling-gate tests |

---

## 1. Context a fresh session needs

- **Vocabulary**: "space" in code == "project" in every user-facing string
  (`"New project…"`); "engine" is a paired backend, "device" a machine
  registered with one engine; "chat", not session; "harness", not provider.
- The add-space palette **landed whole in ticket 11**
  (`.scratch/web-parity/issues/11-add-space-palette.md` — its §2 owns the
  card's layout/entries/keyboard spec, live-verified against the smoke
  engine). This ticket does NOT rebuild any of it: it fixes the one state
  ticket 11 never had to handle (no device rows streamed yet) and verifies
  the entry-point wiring.
- The store (`state/add-space.ts`) is a module-level singleton:
  `open(startDeviceId?)` (:150-183) mints an `AddSpaceFlow` with
  identity/revision staleness guards; `attach({ session, goToCanvas })`
  (:219) binds the routed engine session (the palette is single-engine by
  ticket 31's recorded deviation); `#devices()` reads
  `session.cache.getSnapshot().devices.rows` (:787-789); `#localDeviceId()`
  reads `session.client.engineInfo?.deviceId` (:791-793). The palette mounts
  in the sidebar (`components/sidebar-body.tsx:51`) and binds the routed
  session (`components/add-space-palette.tsx:81-92` — a host unmount
  force-closes it, which §2.5's remount fix also addresses).
- **All three entry points are wired at HEAD `bc3a3945`** (verified by read,
  §2.1's table). The reported "dead new-project button" is best explained by
  (1) a served `dist/` older than `3b14d2a1`, whose SpaceFilter trigger
  unmounted entirely when the engine had zero spaces, or (2) the
  null-device eternal skeleton this ticket fixes (§2.2).
- **There is no native folder picker on the desktop** — `rg
  rfd|pick_folder|FileDialog` over `crates/` returns zero hits. The ⌘K
  palette over engine-side `ListFolders`/`ListDrives`/`PrepareSpacePath`
  **is** the desktop flow, and those RPCs are remote-client ready: they serve
  the web directly. The wire methods exist on the web
  (`web/packages/engine-client/src/methods.ts:42,45,49`) and the palette
  already calls them.
- `targetDeviceId` is a desktop multi-engine *relay* concept; the web strips
  it at the wire boundary (`engine-client/src/request-routing.ts`), so folder
  browsing runs on the engine the browser is paired with — exactly the
  machine whose folders are meant. Keep that (ticket 31's recorded decision).
- **Pairing topology, for context (do not chase S3 in code)**: the engine
  owns all data (ADR 0004, `ARCHITECTURE.md:15-31`); the desktop attaches to
  a local engine on loopback whose listener **rejects browser Origin
  headers** (`ARCHITECTURE.md:11`), so a browser only mirrors an engine it
  has paired with via that engine's opt-in remote bind
  (`crates/engine/src/remote_access.rs:189-232`). The `web_smoke` fixture
  boots a **fresh tempdir with exactly one seeded chat**
  (`crates/engine/examples/web_smoke.rs:10-32`), so no desktop session
  history can ever appear there. The web's mirroring pipeline itself is
  complete and structurally identical to the desktop's at HEAD (research
  S3(a)) — the "shows nothing / no past chats" symptom is topology, not
  code. See §4's explicit note.

## 2. Spec

### 2.1 The three entry points — verification only, no new code

Copied from research S2(a)/(b) with the web lines re-verified at HEAD:

| Entry point | Web wiring (verified at `bc3a3945`) | Desktop wiring |
| --- | --- | --- |
| Spaces menu (sidebar header) | "New project…" row at `space-filter.tsx:223-229`; `pick("new")` at `:144-150` — `setOpen(false); addSpaceStore.open()` | row assembly `spaces.rs:1223-1248`; `activate_spaces_menu_row` closes the menu then `open_add_space` (`:740-748`) |
| Project popover (composer footer / canvas chips) | row at `composer-footer.tsx:462-474` — click runs `onClose(); addSpaceStore.open()` | `pickers.rs:2084-2102` dispatches `AddSpacePalette` after dismissing the popover; "Don't work in a project" is the row beneath (`:2062-2082`) |
| `Mod+K` | `state/shortcuts.ts:549-550` (`mod-k` → `add-space-palette`) → `app-shell.tsx:339` → `toggleAddSpace()` (`state/add-space.ts:862-868`) | fixed binding (`shell.rs:362-364`, cited by ticket 11) |

**Zero-spaces trigger reachability** (research S2(d) row 2): the trigger
renders unconditionally — `space-filter.tsx:160-166` gates only on
`!snapshot.spaces.loaded`; with zero spaces the label reads "All projects"
and the menu degenerates to `["All projects", "New project…"]`, exactly the
desktop's empty-engine rows (`shell.rs:4935-4946`, gate-free). Fixed at HEAD
by `3b14d2a1`. The only action: confirm the served `dist/` is current (§6).

### 2.2 Deviceless open — the fix

**The desktop rule** (`open_add_space`, `spaces.rs:1825-1833` + `:1899-1902`):
land on the local device's tab (else the first registered device); if a
device exists, kick the home browse (`ListFolders` at `null`) and the drives
load concurrently. Devices always exist on a connected engine — the desktop
has **no deviceless state**.

**The web today**: `open()` (`add-space.ts:150-183`) mirrors the pick, but
when no device row has streamed to the routed session, `flow.deviceId ===
null`, no loads kick, `#loadFolders` returns silently (`:619-621`), and the
body's `loading = !listing && loadError === null`
(`add-space-palette.tsx:126`) renders `<SkeletonRows count={6} />` (`:433`)
**forever** — no error row, no retry (the error row at `:437-452` only fires
when a `ListFolders` call actually fails). Reads as "clicked and did
nothing".

**New states** (the state machine must be unit-testable without React; the
wait may live in the store — subscribe the session's watch cache — or be
driven by the mounted palette's effect over `useWatchSnapshot(session)`
calling a new `addSpaceStore.resolveDevice()`; either seam is acceptable):

| State | Condition | What renders | What the store does |
| --- | --- | --- | --- |
| waiting-for-device | flow open, `deviceId === null`, within the deadline | the skeleton rows (same body — but time-bounded, not eternal) | armed wait; resolves on the first devices frame |
| device-resolved | a device row appears (or already existed) | the normal palette: folder list + rail | applies `open()`'s pick rule (local `??` first), kicks `#loadFolders(null)` + `#loadDrives()` |
| deviceless-error | `deviceId === null` past the deadline | the existing folder-level `ErrorRow` + Retry chip (`add-space-palette.tsx:445-450`'s component) — message shape `"{device} didn't respond — is it online?"` with the `"This device"` fallback (`:132`) | terminal until Retry |

- `ADD_SPACE_DEVICE_WAIT_MS = 10_000` — a new named constant (family
  precedent: the registry's 10s identity-call ceiling, ticket 31's numbers
  table). A constant, never an inline literal.
- **Retry re-runs `open()`'s full pick** (fresh identity, re-armed wait) —
  not `retryLoad()`'s current-path reload (`add-space.ts:438-444`), which
  presumes a device and would silently early-return again.
- Close/Escape paths and the identity/revision staleness guards are
  unchanged.
- No new UI is invented: the error state reuses the existing ErrorRow +
  Retry chip verbatim.

### 2.3 The palette and its engine RPC surface at HEAD — verification only

Ticket 11 landed and live-verified the card; this table exists so a fresh
session can verify the surface without re-reading the Rust (do not rebuild
any of it):

| Element | Spec | Source |
| --- | --- | --- |
| Card | 680px wide, radius 14, `modal_glass` scrim alpha 0.35, flex column, no padding | `spaces.rs::render_add_space_overlay` (2526-3274); `popover.rs::palette_card` (820) |
| Input row | 46px, `band()`, ⌘K + esc key caps, ghost-suffix completion preview | `:2644-2688`; ticket 11 §2.4 |
| Body | fixed 330px: breadcrumbs + folder list left, 196px device/drives rail right | ticket 11 §2.4 |
| Footer | key hints `↑↓ Navigate`, `← Up`, `→ Open`, `Tab Complete`, + truncating error line | ticket 11 §2.4 |
| Keyboard | `→`/`Enter` open, `←`/`Backspace` (empty query) up, `Tab` complete, `↑↓` move, `Esc` close, `⌘Enter`/`Ctrl+Enter` submit | `spaces.rs::add_space_key` (2460-2522) |

**The engine-side browse sequence** (copied from research S2(b), re-verified;
these serve remote web clients directly):

| Method | Engine implementation | Behavior |
| --- | --- | --- |
| `ListFolders` | `repos.rs:1226-1233` (`list_folders_for_query`) → `list_folders_options` (`:1305-1337`); handler `rpc.rs:1609-1617` | disposable worker + **6s wall-clock timeout** (`FOLDER_LIST_TIMEOUT`, `repos.rs:34`) — a huge or dead directory fails loudly, not silently ("folder listing timed out on the device", `repos.rs:1333-1335`); max 500 entries |
| `ListDrives` | `repos.rs:1345-1354` (`list_drives`); handler `rpc.rs:1635-1642` | same disposable-worker + timeout shape ("drive listing timed out on the device"); max 50 drives |
| `PrepareSpacePath` | `space_paths.rs:13-54` (`prepare`); handler `rpc.rs:1618-1634` | `~`/`~/x` expansion against the engine's home; absolute-path and directory checks ("the path is a file, not a folder"); `gitDetected = path.join(".git").exists()`; `createIfMissing` creates; Windows-style paths rejected on non-Windows engines |
| `Mutate {op: "createSpace"}` | `rpc.rs:687-696` | engine dedupes `(deviceId, path)` — resubmitting an existing pair lands in the existing space |

All four are wired on the web (`engine-client/src/methods.ts:42,45,49`;
`proto/src/shims.ts`'s `PrepareSpacePathReply` fixed by ticket 11). Nothing
to build. The body's existing states (unchanged, for reference): loading
skeleton / folder-level error + Retry (the engine's error string shown as-is
when it contains "folder", else `"{device} didn't respond — is it online?"`)
/ `"No folders here"` / `"No folders match"`.

### 2.4 Mirroring residue A — the dangling-spaceId hiding gate (`view.ts`)

- **Desktop**: `overview_chats` (`state.rs:1435-1439`) keeps chats whose
  `spaceId` names a missing space out of the list — but the check runs
  against the merged in-memory registry, where chats and spaces land in ONE
  drive loop (`engine_registry.rs:792-793`), so it is never transiently
  wrong and never degrades to a blank spaces set.
- **Web today**: chats and spaces are separate streams
  (`engine-client/src/watch-cache.ts:210-227`); `toChatRow` returns `null`
  for a dangling `spaceId` (`view.ts:473-477`), so space-attached chats are
  hidden until the spaces frame lands — and if the spaces stream errors
  (`RowSet.error`), they stay hidden indefinitely.
- **Fix**: apply the hide only when the spaces RowSet is `loaded` and the id
  is truly missing (desktop parity, `state.rs:1438`). While spaces are
  unloaded/loading/errored, render the row with the `"?"` project label (the
  desktop's dangling-space label, `spaces.rs:1382-1398`'s line-1 derivation)
  until the space resolves. The `"?"`-while-unresolved label is this
  ticket's judgment call — the alternative (keep hiding while spaces load)
  preserves the current transient blink; the research's fix sketch ("hide
  only when spaces are `loaded`") is what this follows. Whichever label
  falls out, the invariant is: **a spaces-stream error must never blank
  space-attached chats**, and the transient hide window must end when the
  spaces frame lands.

### 2.5 Mirroring residue B — the sidebar remount on engine switch (`app-shell.tsx`)

- **Desktop**: the shell tree is never keyed to engines — the sidebar
  persists across engine changes.
- **Web today**: `app-shell.tsx:561` renders `<SidebarBody key={fleet.active
  ?? "none"} />` — an active-engine change remounts the whole sidebar,
  force-closing an open add-space palette (`add-space-palette.tsx:88-91`'s
  `forceClose` on host unmount) and resetting in-memory group-collapse state
  (`chat-list.tsx:168`).
- **Fix**: drop the key. The sidebar reads the fleet-merged snapshot at HEAD
  (ticket 31) and is engine-agnostic; `SpaceFilter` already routes a picked
  space to its owning engine itself. If some child genuinely needs a
  reset-on-switch, key THAT child on a stable identity — never the whole
  sidebar. The settings-nav branch (`:558-560`) is untouched.

## 3. Pure logic to port

None — the research's §4 pure-logic list is entirely ticket 39's
send-path/namespace domain, and no desktop test names apply to anything
here: the deviceless state has no desktop analogue (devices always exist on
a connected engine), and the two mirroring residues are structural. Write
new web tests instead (fresh names, no Rust mirrors):

- `openWithoutDeviceRowsWaitsThenResolvesWhenDevicesStream` — store test
  with a fake session whose devices RowSet starts empty and populates.
- `openWithoutDeviceRowsTimesOutToErrorWithWorkingRetry` — fake timers; the
  error state is reached after `ADD_SPACE_DEVICE_WAIT_MS`, and Retry re-mints
  the identity and re-arms the wait (not `retryLoad`'s silent early-return).
- `danglingSpaceChatsHiddenOnlyWhenSpacesAreLoaded` — `chatListRows`/`toChatRow`
  with spaces unloaded/errored (row renders, `"?"` label) vs loaded-and-missing
  (row hidden).
- `toggleAddSpace`'s existing suite keeps passing (open/close unchanged).

## 4. Gaps this ticket closes

S2's rows, verbatim from research §S2(d):

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
| --- | --- | --- | --- | --- |
| Palette with no device row | WRONG BEHAVIOR | `open_add_space` requires a device tab; devices always exist on the connected engine (`spaces.rs:1826-1833`) | `flow.deviceId: null` → `#loadFolders` returns silently (`add-space.ts:619-621`); body = eternal skeleton, no error (`add-space-palette.tsx:126,433`) | when `deviceId === null`, show the folder-level error row copy ("{device} didn't respond — is it online?" shape) with Retry re-running `open()`'s loads; or block `open()` until devices stream |
| Zero-spaces entry-point reachability | FIXED at HEAD | trigger renders unconditionally (`shell.rs:4935-4946` gate-free) | fixed by `3b14d2a1`; `space-filter.tsx:164-166` gates only on `!spaces.loaded` | verify the served dist is current; nothing to code |
| Folder-list timeout surface | MATCHES | 6s timeout → error string ("folder listing timed out on the device", `repos.rs:1330-1336`) | error row + Retry chip (`add-space-palette.tsx:445`) | none |

S3's actionable rows, verbatim from research §S3(d) (the only S3 rows that
are code gaps; everything else needs no ticket — see the note below):

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
| --- | --- | --- | --- | --- |
| Chats-before-spaces frame race | WRONG BEHAVIOR (transient) | one merged registry write; same dangling rule but spaces land with chats in one drive loop (`engine_registry.rs:792-793`) | separate streams; dangling spaceId rows hidden until the spaces frame (`view.ts:473-477`) | hide only when spaces are `loaded` and the id is truly missing |
| Sidebar remount on engine switch | MISSING | shell tree never keyed to engines | `app-shell.ts:561` `key={fleet.active}` | drop the key or key on a stable identity |

**The rest of S3 explicitly needs no ticket** (recorded so future agents
don't re-chase it): "Same-engine pairing assumption" and "Smoke fixture
honesty" are **environment** rows — data is engine-local per ADR 0004, the
desktop's loopback listener rejects browser Origin headers
(`ARCHITECTURE.md:11`), so the web only mirrors engines paired via their
opt-in remote bind (`remote_access.rs:189-232`), and `web_smoke` boots a
fresh tempdir with exactly one seeded chat (`web_smoke.rs:10-32`); verify
the pairing topology (or seed a persistent-profile engine), don't code.
"Projectless rows reachable" and "Filter clears on projectless canvas" are
S1's rows — ticket 39 owns them (send path + the projectless-canvas filter
clear). The mirroring pipeline itself MATCHES (consolidated row 7).

## 5. Do not

- Do not touch the send path or the id namespace — **ticket 39** (parallel,
  same research) owns S1 (the projectless `~` cwd and dropping both web
  guards), S5 (the scoped-id mint at navigation), the not-found page's fate,
  and the projectless-canvas sidebar-filter clear. Cross-reference only.
- Do not invent a native folder picker — **none exists on the desktop**
  (zero `rfd`/`pick_folder`/`FileDialog` hits in `crates/`); the ⌘K palette
  over `ListFolders`/`ListDrives`/`PrepareSpacePath` IS the desktop flow and
  serves remote web clients. No drag-and-drop folders, no `<input
  type="file" webkitdirectory>`, nothing.
- Do not rebuild, restyle, or re-spec the palette card — ticket 11 landed
  and live-verified it (§2.3 is a verification checklist, not a build spec).
  This ticket only adds the deviceless state.
- Do not add a `targetDeviceId` relay — a desktop multi-engine relay concept
  the web strips at the wire boundary; keep ticket 31's recorded decision.
- Do not re-chase S3's "no past chats" through the mirroring pipeline — it
  is complete at HEAD (research S3(a)); the cause is pairing topology (§1,
  §4's note). The smoke fixture's single chat is not a bug.
- Do not hide dangling-spaceId chats while the spaces RowSet is unloaded or
  errored — that indefinite hide is the bug (§2.4).
- Do not add INVENTED UI for the deviceless state — reuse the existing
  ErrorRow + Retry chip; no new dialogs, banners, or copy.

## 6. Acceptance

- [ ] `Mod+K` on an engine with **0 spaces** (device rows streamed) opens
      the palette on the local device's home with the folders list and
      drives rail — not a skeleton.
- [ ] All three entry points open the palette: the spaces-menu "New
      project…" row (menu closes first), the project-popover row, and
      `Mod+K` — manual pass against the current served dist; a stale dist
      is a deployment fix, not a code change.
- [ ] Opening the palette before device rows stream: the skeleton resolves
      to the folder list the moment a device row lands (no reopen needed).
- [ ] Device rows never landing: the error row + Retry appears within
      `ADD_SPACE_DEVICE_WAIT_MS`; Retry re-runs `open()`'s full pick (fresh
      identity) and re-arms the wait.
- [ ] Escape still closes the palette from the deviceless state; a stale
      async response never mutates state (the identity/revision guards
      hold).
- [ ] A chat whose spaces frame lags renders in the sidebar (no indefinite
      hide) and resolves its project label when the space lands; a
      spaces-stream error no longer blanks space-attached chats.
- [ ] Switching the active engine does not remount the sidebar: an open
      palette survives the switch, and group-collapse state survives.
- [ ] Unit tests: `openWithoutDeviceRowsWaitsThenResolvesWhenDevicesStream`,
      `openWithoutDeviceRowsTimesOutToErrorWithWorkingRetry`,
      `danglingSpaceChatsHiddenOnlyWhenSpacesAreLoaded` →
      `web/packages/app/tests/add-space.test.ts` + `tests/view.test.ts`.
- [ ] Screenshot pairs, desktop vs web, states: (a) palette open on home
      with 0 spaces; (b) the deviceless error + Retry (staged via a fake
      session in a test harness — the smoke engine always has a device);
      (c) the sidebar while a spaces frame lags, before and after it lands.
- [ ] `pnpm -r build` green; package vitest green.
- [ ] No new literal hex/px where a `--rb-*` token exists.

## Comments

(empty; appended during implementation)
