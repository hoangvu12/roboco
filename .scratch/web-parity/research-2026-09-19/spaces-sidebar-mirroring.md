# Spaces, chat creation, and sidebar mirroring — researched (2026-09-19)

Read-only research at `web-parity/wave-1@bc3a3945`. Scope: SPACES/PROJECTS,
the chat-creation flow, the sidebar chat list (mirroring the desktop's data),
and the two small sidebar bugs (S4 archive pill, S6 stuck tooltip). Symptoms
S1–S6 are the user's quotes; M10 in `mobile-layer.md` is the same defect as
S5 and is owned here. Everything below was verified by reading the tree; no
servers or builds were run, no source modified. Line numbers are from this
HEAD.

Method notes that constrain the conclusions:

- Two pre-existing paint bugs in this area were **fixed before HEAD** by
  `3b14d2a1` ("post-review fixes — sidebar mask, space selector…"): the
  inverted sidebar edge-fade mask and the SpaceFilter unmount-on-zero-spaces
  guard documented in
  `.scratch/web-parity/research-2026-09-17/sidebar-issues.md`. Both are
  verified fixed at HEAD (below). Anything still "shows nothing" at HEAD has
  a different cause.
- The engine owns all data (ADR 0004, `ARCHITECTURE.md:15-31`). Clients only
  render via RPC. The desktop attaches to a local engine on loopback; the
  loopback listener **rejects browser Origin headers** (`ARCHITECTURE.md:11`),
  so a browser can only see an engine's data by pairing with that engine's
  opt-in remote bind (`crates/engine/src/remote_access.rs:189-232`).

---

## S1 — "This chat has no working directory yet — pick a space first." should send with no project

### (a) Web mechanism trace

1. `web/packages/app/src/routes/chat-page.tsx:141-157` builds the canvas stub
   chat: `cwd: target.space?.path ?? null` (line 147) — with no space picked
   the stub's cwd is `null`.
2. `web/packages/app/src/components/composer.tsx:1851-1869` — `send()` gates
   the whole send on that cwd BEFORE the new-chat mint:

   ```tsx
   const trimmed = typed.trim();
   if (chat.cwd === null || chat.cwd === undefined || chat.cwd.trim().length === 0) {
     setFailure({ message: "This chat has no working directory yet — pick a space first.", key: chat.id });
     return;
   }
   ```

3. The second copy of the guard: `web/packages/app/src/lib/composer-actions.ts:151-160`
   — `sendRun` re-checks and throws:

   ```ts
   if (chatCwd === null || chatCwd.trim().length === 0) {
     throw new Error("This chat has no working directory yet");
   }
   ```

   The throw is unreachable behind the component-level guard but would
   surface as `Send failed: This chat has no working directory yet`.

4. Nothing upstream ever supplies a cwd for the projectless case — the
   canvas target resolver `useNewThreadTarget`
   (`web/packages/app/src/components/composer/new-thread-selectors.tsx:58-89`)
   only produces `space` (null when nothing picked / "Don't work in a
   project"); it has no `~` fallback.

### (b) Desktop reference

The desktop **never refuses a projectless send**. Ordered flow
(`crates/ui/src/composer.rs::send`):

1. `composer.rs:6047-6050` — chat id: the selected chat, or a client-minted
   id scoped at mint (`ScopedId::encode(engine.key(), uuid)`).
2. `composer.rs:6063-6066` — the comment states the rule verbatim:

   > "The PROJECT fixes the new chat's device + base folder — sessions are
   > minted onto the project's device, not necessarily this one. With no
   > project ("Don't work in a project") the composer's device pick is the
   > host and the session runs from `~` there."

3. `composer.rs:6070-6081` — device: the target device pick, else
   `"local"`.
4. `composer.rs:6433-6440` — the cwd resolution, the exact rule to port:

   ```rust
   let mut cwd = if is_new {
       // Project-less sessions run from the host's home dir —
       // "~" is expanded on the host when the run spawns.
       space_path.clone().or_else(|| Some("~".to_string()))
   } else {
       existing_cwd
   }
   .unwrap_or_else(|| ".".to_string());
   ```

   i.e. **new chat: the space's path, else `"~"`; existing chat: its stored
   cwd, else `"."`**. There is no error path.
5. `composer.rs:6494-6544` — `Mutate {op: "createChat", chatId, spaceId |
   deviceId, config}`; "project-less chats name the host device outright"
   (`:6490-6493`).
6. Engine side, `~` is a real wire convention:
   - `crates/engine/src/sessions.rs:342-352` — `dispatch_inner` rewrites
     `request.cwd = expand_home(&request.cwd)` ("Project-less chats store
     cwd `~` … expand it here, on the host, where the run spawns");
     `expand_home` at `sessions.rs:1303-1313` maps `~`/`~/…` to the host's
     home.
   - `crates/engine/src/workspace_host.rs:317-328` — `claim_chat` treats
     `Some("~" | "~/")` as *no space* and preserves it without minting a
     project.
   - `crates/engine/src/rpc.rs:663-686` — `createChat` accepts `space_id` OR
     `device_id` + optional `cwd`; neither is required.
7. Sidebar: projectless chats are first-class rows —
   `crates/ui/src/state.rs:1432-1444` (`overview_chats`, `None => true` with
   the comment "Project-less sessions are first-class rows") and
   `crates/ui/src/shell/spaces.rs:1382-1389` ("project-less sessions read as
   their home-dir cwd `~`" — the row's line-1 label is `~`).
8. Desktop test pinning the behavior: `projectless_composer_allows_send_and_enter_submission`
   (`composer.rs:8281`).

Also note the smoke fixture's comment admits the web-only nature of the
guard: `crates/engine/examples/web_smoke.rs:53-56` — "A working directory is
what makes the chat runnable: without one the composer refuses to send" —
the fixture seeds a cwd specifically to work *around* the web guard; the
desktop needs no such workaround.

### (c) Root cause

The web invented a hard pre-send guard (`composer.tsx:1866`, doubled in
`composer-actions.ts:158`) that the desktop does not have, and never ported
the desktop's `~` projectless-cwd convention. With 0 spaces the web cannot
send at all — which also means a freshly paired engine with no spaces can
never bootstrap a chat (compounds S3).

### (d) Gap rows

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
| --- | --- | --- | --- | --- |
| Projectless send cwd | MISSING | new chat cwd = `space_path ?? "~"`, existing = `existing_cwd ?? "."` (`composer.rs:6433-6440`) | hard refusal "pick a space first" (`composer.tsx:1866-1869`) | compute `sendCwd = chat.cwd?.trim() ? chat.cwd : (isNew ? "~" : ".")` and drop both guards |
| `sendRun` cwd contract | WRONG BEHAVIOR | `~`/`.` are legal wire cwd values, host expands (`sessions.rs:342-352`) | throws on empty cwd (`composer-actions.ts:158-160`) | accept any non-empty string; delete the throw |
| Projectless chat visibility | MATCHES (once S1 lands) | first-class rows, label `~` (`state.rs:1432-1444`, `spaces.rs:1382-1389`) | `chatListRows` includes spaceId-null rows (`lib/view.ts:429-435`) | none — becomes reachable once sends work |
| Space-filter clearing on projectless canvas | MISSING | shell takes `space_filter` when the canvas is explicitly projectless ("retaining a project filter would hide the session on its first send", `shell.rs:1767-1774`) | `pickNoProject` only writes defaults (`composer-footer.tsx:395-399` → `lib/composer-draft.ts:357-359`); a retained filter hides the projectless chat from the active list (`chat-list.tsx:158-163`) | clear `sidebarStore.spaceFilter` when `noProject` is picked on the canvas |

---

## S2 — "clicking new project button doesnt work, shouldnt it show a way to add projects?"

### (a) Web mechanism trace — every "new project" entry point is wired

There are exactly three entry points, all present at HEAD:

1. **Spaces menu (sidebar header)** — `space-filter.tsx:223-229` renders the
   "New project…" row; `pick("new")` at `space-filter.tsx:144-150`:
   `setOpen(false); addSpaceStore.open();`.
2. **Project popover (composer footer / canvas chips)** —
   `composer-footer.tsx:462-474` renders the row; click runs
   `onClose(); addSpaceStore.open();`.
3. **Mod+K** — `state/shortcuts.ts:549-550` (`{ combo: "mod-k", event:
   "add-space-palette" }`) → `app-shell.tsx:339` → `toggleAddSpace()`
   (`state/add-space.ts:862-868`).

The palette itself mounts in the sidebar
(`components/sidebar-body.tsx:51`), binds the routed session
(`components/add-space-palette.tsx:81-92`), and `open()`
(`state/add-space.ts:150-183`) mints the flow, picks the local device (else
the first device, `:150-156`), and kicks `ListFolders`/`ListDrives`
(`:179-182`). The wire methods exist (`engine-client/src/methods.ts:42,45,49`).

Two degradation paths where "clicking does nothing visible":

- **Pre-3b14d2a1 builds / stale dist**: the SpaceFilter trigger (entry point
  1's host) unmounted entirely when the engine had zero spaces
  (`research-2026-09-17/sidebar-issues.md` Bug 1). At HEAD the trigger
  renders unconditionally (`space-filter.tsx:160-166` — only
  `!snapshot.spaces.loaded` gates it). If the served `dist/` predates
  `3b14d2a1`, the "new project button" area is exactly the leftover chrome
  that report described.
- **Palette open, eternal skeleton**: if `flow.deviceId === null` (no device
  rows streamed to the routed session yet), `#loadFolders` early-returns
  (`state/add-space.ts:616-620`) and the body shows the loading skeleton
  forever (`add-space-palette.tsx:126` `loading = !listing && loadError ===
  null`, `:433` `<SkeletonRows count={6} />`) — no error row, no retry. A
  slow/failed `ListFolders` shows the error row + Retry (`:445`), so the
  silent case is specifically the null-device one.

### (b) Desktop reference

There is **no native folder picker** — `rg rfd|pick_folder|FileDialog` over
`crates/` returns zero hits. The desktop's only add-space surface is the
⌘K palette, which is a *browser over engine-side folder listings*:

1. Trigger rows:
   - Spaces menu "New project…" (`spaces.rs:1223-1224`; row assembly
     `:1240-1248`) → `activate_spaces_menu_row` closes the menu then
     `open_add_space` (`spaces.rs:740-748`).
   - Project popover "New project…" (`pickers.rs:2084-2102`) dispatches the
     `AddSpacePalette` action after dismissing the popover; "Don't work in a
     project" is the row beneath it (`pickers.rs:2062-2082`).
   - Mod+K fixed binding (ticket 11 cites `shell.rs:362-364`).
2. `open_add_space` (`spaces.rs:1825-1904`): land on the local device's tab
   (else the first registered device, `:1826-1833`), mint a search input in
   the "PaletteSearch" key context (`:1834-1838`), kick the home browse and
   drives load concurrently (`:1899-1902`).
3. The RPCs are engine-side and serve remote (web) clients directly:
   - `ListFolders` → `repos.rs:1226-1233` (`list_folders_for_query`), worker
     + **6s wall-clock timeout** (`repos.rs:1305-1337`,
     `FOLDER_LIST_TIMEOUT` `repos.rs:34`) — a huge or dead directory fails
     loudly, not silently.
   - `ListDrives` → `repos.rs:1345-1354` (same disposable-worker + timeout
     shape).
   - `PrepareSpacePath` → `space_paths.rs:13` (`prepare`: `~` expansion,
     absolute-path and directory checks, `gitDetected`).
   - Handlers at `crates/engine/src/rpc.rs:1609-1642`.
   - `targetDeviceId` is a desktop *relay* concept; the web strips it at the
     wire boundary (`engine-client/src/request-routing.ts:48-49`), so folder
     browsing runs on the engine the browser is paired with — exactly the
     machine whose folders are meant.
4. Submit: `Mutate {op: "createSpace", spaceId, deviceId, path, gitDetected}`
   → `rpc.rs:687-696`, engine dedupes `(deviceId, path)`.

So: the "way to add projects" the user asks for exists on both sides and is
the same surface. No RPC gap — the engine's browse RPCs are remote-client
ready and the web already calls them.

### (c) Root cause

No missing wiring at HEAD — statically all three triggers open the palette.
The reported dead button is best explained by (ranked): (1) a served dist
older than `3b14d2a1`, where the zero-spaces trigger unmount left no
"New project…" entry reachable from the sidebar at all (the prior research's
Bug 1 — its fix landed in that commit); (2) the null-device eternal-skeleton
palette (`state/add-space.ts:619-621` returns silently; the body then never
leaves `loading`, `add-space-palette.tsx:126`) — reads as "opened and did
nothing"; (3) user confusion with the *space-filter trigger itself* on an
engine with no spaces (label "All projects", degenerate menu), which pre-fix
did not exist.

### (d) Gap rows

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
| --- | --- | --- | --- | --- |
| Palette with no device row | WRONG BEHAVIOR | `open_add_space` requires a device tab; devices always exist on the connected engine (`spaces.rs:1826-1833`) | `flow.deviceId: null` → `#loadFolders` returns silently (`add-space.ts:619-621`); body = eternal skeleton, no error (`add-space-palette.tsx:126,433`) | when `deviceId === null`, show the folder-level error row copy ("{device} didn't respond — is it online?" shape) with Retry re-running `open()`'s loads; or block `open()` until devices stream |
| Zero-spaces entry-point reachability | FIXED at HEAD | trigger renders unconditionally (`shell.rs:4935-4946` gate-free) | fixed by `3b14d2a1`; `space-filter.tsx:164-166` gates only on `!spaces.loaded` | verify the served dist is current; nothing to code |
| Folder-list timeout surface | MATCHES | 6s timeout → error string ("folder listing timed out on the device", `repos.rs:1330-1336`) | error row + Retry chip (`add-space-palette.tsx:445`) | none |

---

## S3 — "shouldnt the web mirror everything that happening on the desktop app? currently it doesnt show anything, not even past chats"

### (a) Web mechanism trace — the pipeline is complete at HEAD

Every stage of "engine data → sidebar rows" exists and is subscribed:

1. Pairing storage: `lib/engine-store.ts` (`fleetStore`, persisted
   `localStorage["roboco.fleet.v1"]`).
2. Supervisor: `state/fleet.ts:25-73` — the module-level `engineRegistry`
   (one `EngineClient` + one `EngineWatchCache` per paired engine, all
   supervised concurrently; `syncRegistry` follows the store).
3. Streams: `engine-client/src/watch-cache.ts:208-256` — each cache
   subscribes `WatchChats`/`WatchSpaces`/`WatchDevices`/`WatchSessions`/
   `WatchConnectivity`; whole-list frames applied by key with identity
   stability (`applyRows` `:374-392`); generation ghost-guard (`:258-261`).
4. Merge: `engine-client/src/registry.ts:365-386` (`#onRows` adopts live
   rows), `:413-434` (`#commit` notifies), `:504-548`
   (`projectRegistrySnapshot` — scopes every row id per engine and
   concatenates). `state/fleet.ts:116-135` (`useFleetSnapshot`) exposes it
   in a `WatchCacheSnapshot` shape.
5. Render: `components/chat-list.tsx:140-180` (`ChatList` reads the merged
   snapshot → `chatListRows` → `sidebarGroups`), rows/sections drawn at
   `:257-342`; empty/loading/error states at `:304-319` ("Pair an engine to
   see its chats." / "Loading chats…" / "No chats yet.").
6. The engine answers with the full list on subscribe:
   `crates/engine/src/rpc.rs:1167-1182` (the WATCH_* handlers) over
   `watch_stream` (`rpc.rs:783-798`) — "current value first, then every
   change". Chats created by the desktop land in the same engine workspace
   doc (`composer.rs:6494-6544` `Mutate createChat`), so a paired browser
   receives them with no extra wiring.
7. Offline continuity: registry entries re-seed from the IndexedDB cache
   before the first dial (`registry.ts:275-299`, `#seed`), so a reload
   paints last-known rows while connecting.

The two historical "shows nothing" paint bugs are verified fixed at HEAD:

- Edge-fade mask: `app.css:1213-1225` now uses the complement formula
  `1 − gate·(1−ramp)` (fixed by `3b14d2a1`; the 2026-09-17 research
  documented the inverted `gate × ramp` original).
- SpaceFilter unmount: `space-filter.tsx:160-166` renders the trigger
  whenever spaces are merely loaded (zero spaces included).

### (b) Desktop reference — the data path and the row spec

**Data path** (the web's registry is a faithful port):

- `crates/ui/src/engine_registry.rs:679-738` — `supervise`: 500ms initial
  backoff, ×2 growth, 15s cap, 0-255ms jitter, reset after a 10s-plus
  connection; park permanently on 401/403/identity-changed.
- `:739-793` — `drive`: 10s identity call → subscribe the four streams
  (15s ceiling, `:756-773`) → bump generation → loop over frames, writing
  rows into the entry snapshot (`:792-793`), 5s health ticks re-verifying
  identity.
- `crates/ui/src/engine_cache.rs:28-76` — per-engine `rows.json` + per-chat
  transcript files under `engine-cache-v1/<sha256(engineKey)>/`.
- `crates/ui/src/state.rs:1432-1462` — `overview_chats` (non-archived, live
  space or projectless) / `sidebar_chats` (narrowed by the space filter).
- Rendering: `crates/ui/src/shell.rs:4859-4933` (`render_chat_sidebar`:
  keyed rows + the 260ms FLIP resort + the archived shelf + user menu).

**The sidebar row spec the web must mirror** (`shell.rs::render_chat_row`
4435-4799, `spaces.rs::render_active_rows` 1362-1573):

| element | value | source |
| --- | --- | --- |
| Row frame | flex column, gap 2, radius 8, px 8, py 6; no selection ring | `shell.rs:4662-4673` |
| Rest/hover wash | `hover_blend("chat-row-{id}", wash(0.0)/selected → glass_hover())`; selected rows never drift toward hover | `shell.rs:4643-4671` |
| Line 1 | `"{project} @ {device}"` (no fragment when the device is unknown); project = space display name, `"~"` projectless, `"?"` dangling spaceId | `spaces.rs:1382-1398` |
| Line 1 corner (default) | status word + glyph: Working (animated mini glyph spinner + "Working"), Input (6px dot), Failed (6px dot), Done (check 11 + "Done"); Idle → `timeAgo`, no color | `shell.rs:4481-4492, 4562-4609` |
| Status dot color | Working `busy@0.55`, AwaitingInput `accent@0.6`, Errored `danger@0.65`, Completed `success@0.9`, Idle `ink(0.14)` | `spaces.rs:511-528` |
| Corner on row hover | Archive/Unarchive pill: h 18, px 4, mr −4, radius 5, `wash(0.10)`→`wash(0.18)`, icon 11, label 10px `text_muted` | `shell.rs:4522-4560` |
| Line 2 | harness brand mark 13px + title 13px/17px | ticket 08 §2.4; `shell.rs` line-2 assembly |
| Line 3 (structural) | branch icon 11 + name 11px/14px, spring, PR badge (16px, right) — omitted entirely when neither | `shell.rs:4494`, ticket 08 §2.4 |
| Row height | 45 / 61 (branch) / 63 (PR badge) | `shell.rs:666-679` (cited) |
| Grouping | ByDevice disclosures (28px headers, 12px section gap), local device promoted first; InOneList flat | `spaces.rs:1432-1443`, `:133-151` |
| Sort | `compareSidebarChats` on the user's `sidebarSort`, id tiebreak | `spaces.rs:25-38` (cited), applied `:1376-1378` |
| Time-ago | proto `format_time_ago` (the `"0y"` quirk is shared) | `crates/proto/src/view.rs:221-247` (cited) |
| Resort motion | 260ms `cubic-bezier(0.22,1,0.36,1)` paint-only glide; new-row 150ms fade | `shell.rs:4865-4922` |

### (c) Root cause

The web's mirroring code path is **complete and structurally identical to
the desktop's** at HEAD; there is no stub, no session-only filter, no
"chats created in this browser only" gate. The reported "shows nothing /
no past chats" therefore has its cause outside the render pipeline, in one
of these (all verifiable against the user's environment):

1. **Pairing topology (most likely).** Data is engine-local (ADR 0004). The
   browser only mirrors the engine(s) it has paired with —
   `localStorage["roboco.fleet.v1"]` — and the desktop's loopback engine
   refuses browser origins outright (`ARCHITECTURE.md:11`). Chats created in
   the desktop app appear in the web sidebar **only if the web paired with
   that same engine's remote bind** (`remote_access.rs:189-232`, opt-in).
   Testing against the smoke fixture guarantees the symptom:
   `crates/engine/examples/web_smoke.rs:10-32` boots with a **fresh
   tempdir** and exactly one seeded chat — no desktop session history can
   ever appear there.
2. **Stale served dist**: if `web/packages/app/dist` predates `3b14d2a1`,
   the list region paints nothing at rest (the inverted mask) and the
   space-filter trigger is missing — the exact "doesn't show anything" of
   the 2026-09-17 report.
3. **Bootstrap deadlock (real web gap)**: with zero spaces, S1's guard
   prevents creating any chat, so a freshly paired engine's sidebar stays
   "No chats yet." forever — the web cannot populate its own history
   without first adding a space (which needs S2's palette to work).

Secondary real gaps in the mirror (web-side, worth tickets):

- A retained space filter hides projectless chats from the active list
  (`chat-list.tsx:158-163` filters `chat.spaceId === filter`; projectless
  rows have `spaceId: null`) — the desktop explicitly clears the filter on
  an explicit projectless canvas (`shell.rs:1767-1774`).
- Transient frame race: `lib/view.ts:473-477` hides a chat whose `spaceId`
  names a space row that has not streamed yet (chats and spaces are separate
  streams, `watch-cache.ts:210-227`); if the spaces stream errors
  (`RowSet.error`), space-attached chats stay hidden indefinitely while the
  desktop's `overview_chats` does the same check against a merged in-memory
  registry that degrades differently (`state.rs:1435-1439`).
- `app-shell.tsx:561` keys `SidebarBody` on `fleet.active` — an active-engine
  change remounts the whole sidebar (force-closes the add-space palette,
  `add-space-palette.tsx:88-91`, and resets in-memory group-collapse state,
  `chat-list.tsx:168`). The desktop's sidebar is never keyed/remounted.
- First-navigation-after-pairing remounts the page subtree (ticket 32,
  `.scratch/web-parity/issues/32-pairing-remount.md`) — visible as a flash
  of nothing right after pairing.

### (d) Gap rows

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
| --- | --- | --- | --- | --- |
| Same-engine pairing assumption | environment | desktop attaches to its local engine automatically | browser mirrors only paired engines (`state/fleet.ts:25-73`); loopback refuses browsers (`ARCHITECTURE.md:11`) | not a code fix: verify the web is paired to the engine the desktop uses (its remote bind), or seed a real profile engine instead of `web_smoke` |
| Projectless rows reachable | MISSING (blocked by S1) | `None => true` (`state.rs:1435-1438`) | send guard prevents ever creating them (`composer.tsx:1866`) | close S1 |
| Filter clears on projectless canvas | MISSING | `shell.rs:1767-1774` | `rememberTarget` only (`composer-draft.ts:357-359`) | clear the filter on the no-project pick |
| Chats-before-spaces frame race | WRONG BEHAVIOR (transient) | one merged registry write; same dangling rule but spaces land with chats in one drive loop (`engine_registry.rs:792-793`) | separate streams; dangling spaceId rows hidden until the spaces frame (`view.ts:473-477`) | hide only when spaces are `loaded` and the id is truly missing |
| Sidebar remount on engine switch | MISSING | shell tree never keyed to engines | `app-shell.tsx:561` `key={fleet.active}` | drop the key or key on a stable identity |
| Smoke fixture honesty | environment | — | `web_smoke.rs:11` fresh tempdir, 1 chat | pair a persistent-profile engine for mirroring tests |

---

## S4 — archive button "always show the outline or sth"

### (a) Web mechanism trace

Two different row families, two different reveal mechanisms:

**Active rows** (React state): `components/chat-list.tsx:474-475` — the row
wrapper tracks `onMouseEnter/onMouseLeave` → `hovered`; the corner swaps to
the Archive pill only while hovered (`chat-list.tsx:486-497`). No CSS pin.
The pill: `app.css:2297-2313` — h 18, px 4, mr −4, radius 5, background
`rgb(var(--rb-wash) / 0.1)` → `0.18` on hover.

**Archived rows** (CSS swap, and the bug): the component keeps BOTH
right-slot children mounted and lets CSS pick —

```tsx
// components/archived-section.tsx:137-169 — the comment admits the design:
// "Both right-slot children stay mounted; CSS swaps them on row hover (and
// pins the pill on touch, where hover never fires)"
<span className="arch-row-time">{row.timeAgo}</span>
<button type="button" className="arch-row-unarchive" ...>
```

and the CSS:

```css
/* app.css:9338-9346 — the swap */
.arch-row:hover .arch-row-time,
.arch-row-item:focus-within .arch-row-time { display: none; }
.arch-row:hover .arch-row-unarchive,
.arch-row-item:focus-within .arch-row-unarchive { display: inline-flex; }

/* app.css:9352-9360 — the pin */
@media (hover: none) {
  .arch-row-unarchive { display: inline-flex; }
  .arch-row-time { display: none; }
}
```

`.arch-row-unarchive` carries the same visible `rgb(var(--rb-wash) / 0.1)`
fill (`app.css:9330`) — the "outline" the user sees. It is rendered
permanently whenever (a) the browser reports `hover: none` (touch-primary
pointer — Windows tablets/touch laptops, and every mobile-web visit), or
(b) the row's Link holds focus after a click (`:focus-within` — TanStack
Links keep focus on navigation), until focus moves elsewhere.

### (b) Desktop reference

The desktop renders **exactly one** of the two children and only while the
row is hovered; there is no focus pin and no touch variant:

- `crates/ui/src/shell/spaces.rs:1654` — `let hovered =
  self.archived_hover.as_deref() == Some(id.as_str());` (per-row hover state
  set by the listener at `:1729-1739`).
- `spaces.rs:1706-1712` — the right slot: `if hovered { …Unarchive pill… }
  else { …time_ago… }` — one element, chosen at render.
- Active row: `shell.rs:4461` `corner_hovered = self.chat_status_hover ==
  Some(id)`; the Archive pill branch `shell.rs:4522-4560`; the row's hover
  listener drives it (`shell.rs:4677-4690`); the corner wrapper adds a
  click handler *only while hovered* (`shell.rs:4634-4639`) and documents
  the no-occlude decision (`shell.rs:4622-4629`).
- Pill geometry (both families): h 18, gap 4, px 4, mr −4, radius 5,
  `wash(0.10)` → `wash(0.18)` on the pill's own hover, icon 11 `text_muted`,
  label 10px `text_muted` — `shell.rs:4523-4559` (active),
  `spaces.rs:1677-1704` (archived; the `right`-slot choice itself is
  `spaces.rs:1669-1672`).

### (c) Root cause

Web-only inventions: the `@media (hover: none)` permanent pin
(`app.css:9353-9360`) and the `:focus-within` pin (`app.css:9338-9346`).
Either leaves the wash-filled Unarchive pill painted at rest — "always show
the outline". The desktop's pill exists only during row hover.

### (d) Gap rows

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
| --- | --- | --- | --- | --- |
| Touch-pointer pill pin | INVENTED | no touch variant — pill is hover-only (`spaces.rs:1654,1706-1712`) | `@media (hover: none)` always displays the pill (`app.css:9353-9360`) | delete the pin; on `hover: none` devices surface unarchive via the row's context menu (already ported, `archived-section.tsx:122` `useChatMenu`) like the desktop's only affordance set |
| Focus keeps the pill | INVENTED | pill unmounts on un-hover; focus never pins it (`shell.rs:4522-4560`) | `:focus-within` pins it after a click until focus leaves (`app.css:9338-9346`) | restrict to `:focus-visible` (keyboard) or drop |
| Pill visuals | MATCHES | h18/px4/mr−4/r5/wash .10→.18 (`shell.rs:4523-4559`) | same (`app.css:9320-9350`, `2297-2313`) | none |

---

## S5 — "This chat is not on engine's list" after picking a new project and submitting

(Mobile report M10; the code path is shared with desktop-width web.)

### (a) Web mechanism trace — the create-then-send-then-navigate chain

1. `components/composer.tsx:1875-1892` — canvas send mints the chat:

   ```tsx
   chatId = await createChat(
     session.client,
     chat.spaceId != null ? { spaceId: chat.spaceId } : { deviceId: chat.deviceId },
   );
   await waitForChatRow(session.cache, chatId);
   onNewThreadLaunched?.(chatId);
   ```

   `createChat` (`lib/chat-actions.ts:37-46`) mints a **raw** uuid
   (`crypto.randomUUID()`, `:29-31`) and returns it unchanged.
   `waitForChatRow` (`chat-actions.ts:125-141`) waits for the RAW row on the
   session's own watch cache (raw rows) — this part works and is not the
   race.
2. `routes/chat-page.tsx:676-681` — `onNewThreadLaunched` navigates with the
   **raw** id:

   ```tsx
   void navigate({ to: "/chat/$chatId", params: { chatId: mintedId } });
   ```
3. `routes/chat-page.tsx:87-96, 127-129, 422-425` — the chat page reads the
   id from the pathname (`nav-history.ts:242-255`, verbatim) and looks the
   row up in the **merged fleet snapshot**: `useFleetSnapshot()`
   (`state/fleet.ts:116-135`) → `projectRegistrySnapshot`
   (`engine-client/src/registry.ts:504-548`) → `scopeChat`
   (`registry.ts:550-562`) — **every merged chat id is scoped**
   (`engine:v1:<base64url([engineKey, rawId])>`,
   `engine-client/src/scoped-id.ts:33-35`).
4. `lib/view.ts:455` — `chatPageRow`: `chats.find((candidate) =>
   candidate.id === chatId)` — a raw URL id never equals a scoped row id.
5. `routes/chat-page.tsx:702-711` — the miss renders the error state:

   ```tsx
   if (row === undefined && hasSelection) {
     return (
       <div className="empty-state">
         <p>That chat is not in this engine's list.</p>
   ```

6. The send itself still works: raw ids pass any engine's wire boundary
   (`engine-client/src/request-routing.ts:31-40` — unscoped ids decode to
   their raw form), so `QUEUE_COMMAND`/`WatchDocMessages` against the raw id
   succeed — "it actually does [exist]".
7. Re-clicking the sidebar row navigates with the **scoped** id
   (`chat-list.tsx:476-481` — `<Link params={{ chatId: row.chat.id }}>`),
   the lookup matches, and the page renders — the user's workaround.

So this is **not a timing race** — `waitForChatRow` already serialized
navigation behind the row's arrival. It is a **namespace mismatch**: raw
minted id in the URL vs scoped ids in the merged snapshot. It regressed
when ticket 31 (`7d81e18f`, merged at `5052ca5d`) made every merged row id
scoped while the canvas mint stayed raw; ticket 15's canvas-mint flow was
verified pre-31.

Adjacent, same path, owned elsewhere: on non-secure origins (http on a LAN
IP) `crypto.randomUUID` is undefined and `createChat` throws before any of
this — mobile-layer M11, owned by
`research-2026-09-19/composer-model-picker-send.md`.

### (b) Desktop reference

The desktop mints scoped and selects optimistically — there is no
not-found page at all:

1. `crates/ui/src/composer.rs:6047-6050` — the new chat id is scoped **at
   mint**: `ScopedId::encode(engine.key(), &uuid::Uuid::new_v4().to_string())`.
2. `composer.rs:6249-6258` — selection commits **before** the wire work:
   `cx.emit(ComposerEvent::NewThreadTransitionStarted)` (the dock starts),
   then `s.select_chat(Some(chat_id.clone()), cx)` — the `createChat`
   Mutate happens later in the same task (`:6494-6544`).
3. `crates/ui/src/state.rs:1740-1792` — `select_chat` sets the selection
   unconditionally; the row lookup at `:1760` is only used to imply the
   chat's space ("A chat implies its project (or the lack of one)") — a
   missing row simply skips that step. The transcript/queue watches spawn
   off the id (`:1775-1790`). No error state, no fallback page.
4. The only "not found" copy on the desktop is the deep-link notice
   "The linked conversation was not found" (`state.rs:1725-1729`) — a
   transient toast, not a page.
5. The sidebar row appears whenever the registry frame lands; the shell
   never gates the chat route on the row's presence
   (`shell.rs:5897-5978` outlet selection, cited by ticket 15 §2.7).

### (c) Root cause

`composer.tsx`'s new-chat mint navigates with a raw id while the merged
sidebar (ticket 31) keys every row by its scoped id; `chatPageRow`'s exact
string compare misses, and the page's not-found state (a web-only construct
with no desktop analogue) swallows the just-created chat until the user
re-enters via a sidebar row (scoped link).

### (d) Gap rows

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
| --- | --- | --- | --- | --- |
| New-chat id namespace | WRONG VALUE | scoped at mint (`composer.rs:6049`) | raw uuid in URL (`chat-page.tsx:678`, minted `chat-actions.ts:38`) | keep the raw id for the wire, navigate with `encodeScopedId(session.engine.baseUrl, rawId)` (desktop parity; note `state/add-space.ts:782-785` already does exactly this for optimistic space rows) |
| Selection timing | WRONG BEHAVIOR | select_chat commits before the RPC (`composer.rs:6249-6258`); missing rows are tolerated (`state.rs:1760`) | navigate only after `waitForChatRow`, then hard-fail on a row miss (`chat-page.tsx:702-711`) | with the scoped URL the miss disappears; independently, degrade the not-found page to the desktop's notice shape (transient, with the chat still opening) |
| Not-found page | INVENTED (web-only) | no such state; only the deep-link toast (`state.rs:1725-1729`) | full-page "That chat is not in this engine's list." + Back link (`chat-page.tsx:702-711`) | keep as last-resort for genuinely foreign ids, but never for a chat this client just minted |

---

## S6 — "Sidebar view options" tooltip shows up and gets stuck

### (a) Web mechanism trace

`components/space-filter.tsx:386-531` (`SidebarViewMenu`):

```tsx
const [tooltip, setTooltip] = useState(false);            // :390
const showTooltip = useCallback(() => {                    // :396-402
  const timer = setTimeout(() => setTooltip(true), TOOLTIP_VIEW_OPTIONS_MS);
  return () => { clearTimeout(timer); setTooltip(false); };
}, []);
…
<button …                                                   // :494-517
  onMouseEnter={showTooltip}                                // :499
  onFocus={showTooltip}                                     // :500
>
  <Icon name="sort" size={16} />
  {tooltip && !open && (                                    // :512
    <span className="space-filter-sort-tooltip" role="tooltip">
      Sidebar view options
    </span>
  )}
</button>
```

The bug: `showTooltip` is written in a cleanup-returning style, but React
event handlers ignore return values — **nothing ever calls the returned
function**. There is no `onMouseLeave`, no `onBlur`, and no other code path
that sets `tooltip` back to false (grep `setTooltip` → only `:397` and the
discarded closure). Consequences:

- Hovering ≥350ms (or focusing the button — click focus or Tab) sets the
  label; leaving the button does nothing → the label stays forever.
- Every re-enter arms *another* timer; none are tracked or cleared.
- The `!open` suppression (`:512`) hides it only while the view menu is
  open; closing the menu re-reveals the stuck label. Escape and
  outside-press do nothing (the label is not a popover and is not on the
  escape ladder, `state/escape.ts`).
- "Sometimes": the 350ms delay means quick pointer passes don't trigger it;
  resting on the button or tabbing to it does.

### (b) Desktop reference

- The label card: `crates/ui/src/shell/spaces.rs:63-80`
  (`SidebarViewOptionsTooltip`) — px 8, py 6, radius 6, `border_1
  border_strong`, `bg surface_raised`, `shadow_md`, text 11px `theme.text`,
  string "Sidebar view options".
- The trigger: `spaces.rs:1101-1156` — 29×29, radius 8, `role=button`,
  `aria-label "Sidebar view options"`, `aria-expanded`, hover/focus borders.
- The tooltip wiring: `spaces.rs:1150-1151` — gpui's built-in
  `.tooltip(…)` with `.tooltip_show_delay(350ms)`: shows after the delay
  **while hovered**, dismisses when the pointer leaves the element or the
  element unmounts; keyboard focus does not pin it. (Web parity note in
  `ui/Tooltip.tsx:9-19` already records the 350ms convention and that this
  one surface hand-rolls its span.)

### (c) Root cause

A hand-rolled show timer with no hide path: the cleanup closure is returned
from an event handler where nothing can invoke it. Once armed, the label is
unconditional and permanent.

### (d) Gap rows

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
| --- | --- | --- | --- | --- |
| Tooltip dismissal | MISSING | gpui hover tooltip auto-dismisses on pointer leave (`spaces.rs:1150-1151`) | no leave/blur handler; cleanup never invoked (`space-filter.tsx:396-402,499-500`) | keep the timer in a ref; bind `onMouseLeave`/`onBlur` to `clearTimeout + setTooltip(false)`; clear on unmount |
| Focus behavior | WRONG BEHAVIOR | focus never shows/pins the label | `onFocus` arms it (`:500`) and it never clears | drop `onFocus` (the trigger has `aria-label`; the visual label is hover-only on the desktop) |
| Re-arm hygiene | MISSING | n/a (library-managed) | repeated enters stack timers, none tracked (`:397`) | single ref, cleared before re-arm |

---

## Consolidated gap table

| # | item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
| --- | --- | --- | --- | --- | --- |
| 1 | Projectless send cwd `~` | MISSING | `composer.rs:6433-6440` | refusal at `composer.tsx:1866-1869` | `spacePath ?? "~"` (new) / `cwd ?? "."` (existing) |
| 2 | `sendRun` empty-cwd throw | WRONG BEHAVIOR | `~` legal, host expands (`sessions.rs:342-352,1303-1313`) | `composer-actions.ts:158-160` | delete the throw |
| 3 | Projectless rows first-class | blocked | `state.rs:1435-1438` | unreachable via send | lands with #1 |
| 4 | Filter clears on projectless canvas | MISSING | `shell.rs:1767-1774` | `composer-footer.tsx:395-399` | clear `spaceFilter` on the no-project pick |
| 5 | Palette null-device state | WRONG BEHAVIOR | devices always resolve (`spaces.rs:1826-1833`) | silent eternal skeleton (`add-space.ts:619-621`, `add-space-palette.tsx:126`) | error row + Retry |
| 6 | Folder browse RPC surface | MATCHES | `rpc.rs:1609-1642`, `repos.rs:1226-1354` | `methods.ts:42,45,49` wired | none |
| 7 | Mirroring pipeline | MATCHES | `engine_registry.rs:679-793` | `registry.ts` + `watch-cache.ts` + `fleet.ts:116-135` | none (verify pairing topology instead) |
| 8 | Same-engine pairing | environment | loopback attach | browser pairs via remote bind only (`ARCHITECTURE.md:11`, `remote_access.rs:189-232`) | environment check; persistent-profile engine for tests |
| 9 | Dangling-spaceId hiding gate | WRONG BEHAVIOR (transient) | merged single-drive rows (`engine_registry.rs:792-793`) | hidden until spaces frame (`view.ts:473-477`) | hide only when `spaces.loaded` |
| 10 | Sidebar remount on engine switch | MISSING | shell never remounted | `app-shell.tsx:561` key | drop/replace the key |
| 11 | Archive pill touch pin | INVENTED | hover-only (`spaces.rs:1654,1706-1712`) | `app.css:9353-9360` | delete the pin; context menu covers touch |
| 12 | Archive pill focus pin | INVENTED | focus never pins (`shell.rs:4522-4560`) | `app.css:9338-9346` `:focus-within` | `:focus-visible` only |
| 13 | New-chat id namespace | WRONG VALUE | scoped at mint (`composer.rs:6049`) | raw in URL (`chat-page.tsx:678`) | navigate with `encodeScopedId` |
| 14 | Not-found page | INVENTED | toast only (`state.rs:1725-1729`) | full page (`chat-page.tsx:702-711`) | last-resort only |
| 15 | View-options tooltip dismissal | MISSING | gpui hover tooltip (`spaces.rs:1150-1151`) | no leave/blur path (`space-filter.tsx:396-402`) | timer ref + leave/blur clear |
| 16 | View-options tooltip focus arm | WRONG BEHAVIOR | hover-only | `onFocus` arms (`space-filter.tsx:500`) | drop `onFocus` |

---

## Pure logic to port + desktop test names

1. `resolveSendCwd(isNew, spacePath, existingCwd)` — the rule of
   `composer.rs:6433-6440` (`spacePath ?? "~"` when new; `existingCwd ?? "."`
   otherwise). Web unit test mirroring the desktop's
   `projectless_composer_allows_send_and_enter_submission`
   (`composer.rs:8281`): a projectless canvas send reaches the
   `QUEUE_COMMAND` step with `cwd: "~"` and never surfaces the working-
   directory failure.
2. `expandHome` parity check (assert the contract, not the implementation):
   the engine at `sessions.rs:1303-1313` is authoritative; the web only
   needs to keep sending the literal `~` — a unit test pinning
   `buildRunRequest(draft, prompt, "~", …).cwd === "~"` suffices.
3. Projectless canvas filter-clearing — mirror
   `projectless_new_session_restores_opt_out_and_clears_sidebar_filter`
   (`shell.rs:9191`): picking "Don't work in a project" on the canvas clears
   the sidebar's space filter so the first send is visible in the list.
4. Projectless target persistence — mirror
   `projectless_preference_survives_restart_and_space_refreshes`
   (`state.rs:3300`) and
   `projectless_selection_clears_project_and_survives_device_switch`
   (`state.rs:3329`) against `composerDefaults` + `useNewThreadTarget`
   (the web already persists `noProject`; the tests pin the
   filter-clearing interplay).
5. Row-label derivation — `project_labels_from_cwd` (`state.rs:3580`):
   `~`/`~/` → `~` label, basename otherwise (web `projectLabel` at
   `lib/view.ts:601-607` already matches; the test belongs to whichever
   ticket touches line 1).
6. Scoped navigation mint — new web unit test (no desktop name; the
   desktop's equivalent is structural):
   `canvasSendNavigatesUnderScopedId`: mint → createChat (raw) →
   `waitForChatRow` (raw, session cache) → navigate
   `encodeScopedId(engine.baseUrl, rawId)` → `chatPageRow` resolves against
   `useFleetSnapshot`'s scoped rows.

## Desktop-only items NOT to port

- **Native folder picker** — none exists (zero `rfd`/`pick_folder`/
  `FileDialog` hits in `crates/`); do not add one for web. The ⌘K palette
  over `ListFolders`/`ListDrives`/`PrepareSpacePath` IS the desktop flow.
- **Relay `targetDeviceId` for folder browsing** — a desktop multi-engine
  relay concept; the web strips it at the wire
  (`request-routing.ts:48-49`) and browses the connected engine's host.
  Keep that (ticket 31's recorded decision).
- **gpui's tooltip library** — port the *contract* (hover-only, 350ms delay,
  auto-dismiss), not the mechanism.
- **The desktop's in-memory `archived_hover`/`chat_status_hover` fields** —
  the web's React hover state is the equivalent; only the two CSS pins are
  invented.

## Open design questions for the human

1. **Touch-primary devices (`hover: none`)**: dropping the always-visible
   unarchive pill (gap #11) leaves no one-tap unarchive affordance on
   touch; the row's context menu (long-press/right-click) already offers
   it. Is that acceptable, or should touch get a *visually quiet* rest-state
   affordance (e.g. the time-ago stays and the pill appears only on the
   row's own press)? The desktop has no touch story to copy.
2. **Mirroring verification environment**: S3's "no past chats" cannot be
   fixed in code if the browser is paired to `web_smoke` (fresh tempdir) or
   a second engine. Do we want a persistent-profile smoke engine (or a
   documented pairing topology for QA) so "web mirrors the desktop" is
   actually testable, or is this purely an ops instruction?
3. **The not-found page's fate** (gap #14): once the scoped mint lands it is
   unreachable for self-created chats. Keep it as the foreign-id last
   resort (current copy), or replace with the desktop's transient
   "The linked conversation was not found" notice shape?
