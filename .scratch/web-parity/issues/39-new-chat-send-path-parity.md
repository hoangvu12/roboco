# 39 — New-chat send path parity

**What to build:** Sending the first message in a new chat works exactly like
the desktop, 1:1. Sending with **no space picked is legal** (the run's cwd is
`"~"`, expanded by the engine on the host — the web-only "pick a space first"
refusal is deleted); `createChat` carries the **full payload** (the resolved
config, plus the checkout plan's branch/cwd when present); navigation uses the
**scoped chat id** and selection is optimistic, so "This chat is not on
engine's list" never shows for a chat this client just minted; and every id
mint runs through one shared `mintId()` helper with a
`crypto.getRandomValues` v4 fallback, so sends work on **plain-HTTP LAN
origins** where `crypto.randomUUID` is undefined. After this ticket, a
freshly paired engine with zero spaces can bootstrap its first chat, the
just-sent chat opens immediately, and `Send failed: crypto.randomUUID is not
a function` is gone. Picking "Don't work in a project" on the canvas also
clears the sidebar's space filter, so the first projectless send is visible
in the active list.

**Blocked by:** None — can start immediately. (43, the add-space palette, is
parallel, not a dependency: projectless sending needs no space. Note that
this ticket unblocks the zero-spaces bootstrap deadlock the spaces research
documents — S3 root cause 3.)

**Status:** ready-for-agent

**Research:** `../research-2026-09-19/composer-model-picker-send.md` S5
(send-path states), S6 ((a)–(d)), "Consolidated gap table" rows 12–15,
"Pure logic to port + desktop test names" item 3;
`../research-2026-09-19/spaces-sidebar-mirroring.md` S1 ((a)–(d)), S5
((a)–(d)), "Consolidated gap table" rows 1–4 and 13–14, "Pure logic to port
+ desktop test names" items 1, 2, 3, 6.

**Desktop reference (for lookups only):**
`crates/ui/src/composer.rs::send` (6032) — the new-chat id mint scoped at
mint (`composer.rs:6047-6050`), the project-fixes-device rule
(`composer.rs:6063-6066`), device resolution (`6070-6081`), the cwd
resolution (`6433-6440`), the checkout-plan refinement (`6451-6487`), the
createChat `Mutate` (`6494-6544`), the optimistic select
(`6249-6258`), and the desktop test
`projectless_composer_allows_send_and_enter_submission`
(`composer.rs:8281`); `crates/ui/src/state.rs::select_chat` (1740-1792) and
the deep-link notice (`state.rs:1725-1729`);
`crates/engine/src/sessions.rs::dispatch_inner` (342-352) + `expand_home`
(1303-1313); `crates/engine/src/workspace_host.rs::claim_chat` (317-328);
`crates/engine/src/rpc.rs::MutateParams::CreateChat` (663-686);
`crates/engine/examples/web_smoke.rs` (53-56) — the fixture comment that
documents the web-only guard.

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/components/composer.tsx` | edit | `send()` — delete the cwd guard (composer.tsx:1866-1869), compute the resolved `sendCwd`, thread config/branch/cwd into `createChat` (1876-1892), move `mintMessageId()` inside the try (1907 vs the try at 1951) |
| `web/packages/app/src/lib/chat-actions.ts` | edit | `createChat` (chat-actions.ts:37-46) — payload gains `cwd?`, `branch?`, `config?`; `defaultMintId` (29-31) → `mintId` |
| `web/packages/app/src/lib/composer-actions.ts` | edit | delete the empty-cwd throw (composer-actions.ts:158-160); `defaultMint` (123-125) → `mintId` |
| `web/packages/app/src/lib/id.ts` | new | `mintId()` — the shared v4 fallback helper (§2.5) |
| `web/packages/app/src/routes/chat-page.tsx` | edit | `onNewThreadLaunched` (chat-page.tsx:676-681) navigates with the scoped id; the not-found state (702-711) becomes last-resort only |
| `web/packages/app/src/components/composer-footer.tsx` | edit | `pickNoProject` (composer-footer.tsx:395-399) — also clears the sidebar's space filter (§2.4) |
| `web/packages/app/src/lib/queue-actions.ts` | edit | `mintEditorInstanceId` (queue-actions.ts:186-188) → `mintId` |
| `web/packages/app/src/state/transcript-store.ts` | edit | `defaultMintEchoId` (transcript-store.ts:263-265) → `mintId` |
| `web/packages/app/src/lib/review-comments.ts` | edit | `mintId` (review-comments.ts:45-47) → the shared `mintId` |
| `web/packages/app/src/lib/attachments.ts` | edit | `finalizeStage`'s id (attachments.ts:271-282) → `mintId` |
| `web/packages/app/src/state/add-space.ts` | edit | `mintId` (add-space.ts:111-113) → the shared `mintId` |
| `web/packages/app/tests/id.test.ts` | new | the `mintId` three-arm tests |
| `web/packages/app/tests/chat-actions.test.ts` | edit | the createChat payload + scoped-navigation tests |
| `web/packages/app/tests/composer-send.test.ts` | edit | `resolveSendCwd` + the projectless send-path mirror of the desktop test |

---

## 1. Context a fresh session needs

- The composer's send path is `Composer::submit` → `send(typed, queue)` in
  `components/composer.tsx` (1851-2043): the capability gate (1855-1864), the
  cwd guard (1866-1869), the new-chat branch (1875-1892: `createChat` →
  `waitForChatRow` → `onNewThreadLaunched`, all before the wire call — the
  port of the desktop's `NewThreadTransitionStarted` → `select_chat` chain),
  snapshot-and-clear (1893-1940), the optimistic echo (1929-1937), then the
  queue branch (`queueMessage`, 1952-1966) or the run branch (`sendRun`,
  1968-1978) inside a try (1951+) with the full failure recovery
  (2008-2036). Queue/echo/interrupt semantics are ticket 13/16's, landed and
  green — this ticket changes the new-chat preamble, the payload, the id
  mint, and the navigation, nothing else.
- **The guard being deleted** (web-only, no desktop counterpart):
  `composer.tsx:1866-1869` refuses the whole send when
  `chat.cwd` is null/empty — "This chat has no working directory yet — pick a
  space first." — and a second copy throws in `sendRun`
  (composer-actions.ts:158-160). Nothing upstream supplies a cwd for the
  projectless case: the canvas target resolver `useNewThreadTarget`
  (components/composer/new-thread-selectors.tsx:58-89) only produces `space`
  (null when nothing picked / "Don't work in a project"), and the canvas stub
  chat builds `cwd: target.space?.path ?? null` (chat-page.tsx:141-157, line
  147). With 0 spaces the web cannot send at all.
- **The createChat wire call** today sends only
  `{op, chatId, spaceId | deviceId}` (chat-actions.ts:37-46) — no config, no
  branch, no cwd. `waitForChatRow` (chat-actions.ts:125-141) then waits for
  the RAW row on the session's own watch cache (raw rows) — that part works
  and is not the race.
- **The navigation/lookup chain:** `onNewThreadLaunched` navigates with the
  raw minted id (chat-page.tsx:676-681); the chat page reads the id from the
  pathname and looks the row up in the **merged fleet snapshot**:
  `useFleetSnapshot()` (state/fleet.ts:116-135) →
  `projectRegistrySnapshot` (engine-client/src/registry.ts:504-548) →
  `scopeChat` (registry.ts:550-562) — every merged chat id is **scoped**
  (`engine:v1:<base64url([engineKey, rawId])>`,
  engine-client/src/scoped-id.ts:33-35). `chatPageRow` does an exact string
  compare (`chats.find((candidate) => candidate.id === chatId)`,
  lib/view.ts:455), so a raw URL id never equals a scoped row id; the miss
  renders the error state "That chat is not in this engine's list."
  (chat-page.tsx:702-711). This regressed when ticket 31 made every merged
  row id scoped while the canvas mint stayed raw; ticket 15's canvas-mint
  flow was verified pre-31. It is a **namespace mismatch, not a timing race**
  — `waitForChatRow` already serialized navigation behind the row's arrival.
  Re-clicking the sidebar row navigates with the scoped id
  (chat-list.tsx:476-481) and everything renders — the user's current
  workaround.
- **The wire boundary is id-agnostic:** raw ids pass any engine's wire
  boundary (engine-client/src/request-routing.ts:31-40 — unscoped ids decode
  to their raw form), so `QUEUE_COMMAND`/`WatchDocMessages` against either
  form succeed. `state/add-space.ts:782-785` already scopes optimistic space
  rows exactly this way (`encodeScopedId(session.engine.baseUrl, id)`) — the
  precedent to follow.
- **The secure-context failure:** `crypto.randomUUID` is only exposed in
  secure contexts (HTTPS or localhost) and needs Chrome 92+/Safari 15.4+/
  Firefox 95+. The engine serves the web client itself over **plain HTTP**
  (crates/engine/src/web.rs:1-16 rust-embed; the remote paired listener
  serves the React app at `/` and `/pair`, listener.rs:2, 148-151; pairing
  accepts plain `http` origins, crates/engine/src/pairing.rs:200); the store
  models it first-class (`engineWsEndpoint` maps `http→ws, https→wss`,
  lib/engine-store.ts:85-91). Opening the client at
  `http://<lan-host>:<port>` from any other machine is a non-secure context →
  `crypto.randomUUID` is undefined → every id mint breaks. localhost dev/
  smoke never sees it, which is why tickets 13/15's live verifications
  passed.
- **The exact failure paths** (S6 (b)): new-chat send — `createChat` calls
  `defaultMintId()` synchronously (chat-actions.ts:38); when
  `crypto.randomUUID` is undefined it throws `TypeError: crypto.randomUUID is
  not a function`, the async call rejects, and the catch renders the user's
  string: `` setFailure({ message: `Send failed:
  ${describeSendError(error)}`, key: null }) `` (composer.tsx:1887-1889;
  `describeSendError` returns `error.message` for Errors, chat-actions.ts:111-
  113). The draft survives (the snapshot-and-clear happens later,
  composer.tsx:1893+). Existing chat — the crash site is `mintMessageId()`
  at composer.tsx:1907, **outside** the try that starts at 1951, so the same
  missing function produces an *uncaught* async rejection (the click path is
  `void submit()`, composer.tsx:2285, 2296): no notice, no clear, a silently
  dead send. Worse UX, same root cause.
- No id helper exists in `@roboco/engine-client` (grep `uuid|makeId|newId|
  generateId|nonce` → no matches; the closest is `scoped-id.ts`'s
  `encodeScopedId`, an encoder, not a mint). All seven bare
  `crypto.randomUUID` call sites live in `web/packages/app/src` (§2.5 table).
- Vocabulary: **chat** (not session/thread), **space** (not project),
  **engine**, **harness** (not provider).

---

## 2. Spec

### 2.1 The send path — projectless send, `~`/`.` cwd

**Desktop reference — the ordered flow the web must match**
(`crates/ui/src/composer.rs::send`; copied from the spaces research S1 (b)):

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
8. Desktop test pinning the behavior:
   `projectless_composer_allows_send_and_enter_submission`
   (`composer.rs:8281`).

Also note the smoke fixture's comment admits the web-only nature of the
guard: `crates/engine/examples/web_smoke.rs:53-56` — "A working directory is
what makes the chat runnable: without one the composer refuses to send" —
the fixture seeds a cwd specifically to work *around* the web guard; the
desktop needs no such workaround.

**Web changes.**

- Delete the component-level guard (composer.tsx:1866-1869) and the
  `sendRun` throw (composer-actions.ts:158-160 — `sendRun`'s `chatCwd`
  parameter becomes a resolved non-empty `string`).
- Compute the resolved cwd once in `send()` with the pure `resolveSendCwd`
  (§3): for the canvas (`chat.id === ""`) `sendCwd = spacePath ?? "~"` (the
  stub chat's `chat.cwd` already carries `target.space?.path ?? null`,
  chat-page.tsx:147, so `chat.cwd?.trim() ? chat.cwd : "~"` is the same
  rule); for an existing chat `sendCwd = chat.cwd?.trim() ? chat.cwd : "."`.
  Pass `sendCwd` where `chat.cwd` was passed (composer.tsx:1973).
- The RunRequest carries the literal `~` — **the engine expands it
  host-side; the web never expands it client-side** (§3's contract pin).

**States.** The send-path state machine (research S5 audit — the GAP row is
this ticket's; every "match" row must stay a match; documented seams are
ticket 13's recorded deviations and stand):

| state | desktop | web | verdict |
|---|---|---|---|
| idle chat | Send | Send (composer.tsx:2414-2416) | match |
| working + content | Queue — `QueueMessage {holdForTurnEnd: true}` (composer.rs:573-579, 6547-6577) | queueMessage (composer.tsx:1952-1966; lib/composer-actions.ts:216-235) | match |
| working + empty | Stop → interrupt (composer.rs:573-579, 6000) | stop → interrupt (composer.tsx:2092-2094, 1838-1848) | match |
| send blocked | 4 conditions; Stop never blocked (composer.rs:5944-5966) | sendBlocked mapping (composer.tsx:2417-2424, 2099-2113; deviation 6 in ticket 13 Comments) | match (documented seams) |
| queue capability gate | MESSAGE_QUEUE_V1/(ATTACHMENTS) pre-checked (composer.rs:6096-6110) | ported, engine-only check (composer.tsx:1822-1864; deviation 2) | match (documented) |
| new chat send | mint id → createChat **with config/branch/cwd** → select → run; projectless cwd = "~" (composer.rs:6433-6440, 6494-6539) | `createChat` sends only `{op, chatId, spaceId\|deviceId}` (chat-actions.ts:37-46); **blocks** projectless sends with "This chat has no working directory yet" (composer.tsx:1866-1869) | **GAP** — web blocks the desktop's legal projectless send; createChat drops config/branch (row below) |
| failure recovery | typed text + attachments back by id, new chat deleted, route back (ticket 13 §2.12) | ported (composer.tsx:2008-2036) | match |
| optimistic echo | before RPC, never for queued, refreshed post-upload (composer.rs:6188-6233, 6401-6423) | ported (composer.tsx:1929-1937, 1979-1990) | match |
| interrupt tracking | idempotent set, released on settle (composer.rs:6707-6741) | ported (composer.tsx:972-980, 1838-1848) | match |
| Mod+Enter empty | activates latest queued row, never Stop (composer.rs:6012-6014) | ported (composer.tsx:2279-2289) | match |
| IME | isComposing never submits (ticket 13 §2.9 note) | `event.nativeEvent.isComposing` (composer.tsx:2203) + composition pause (composer.tsx:336-340) | match |
| Enter policy | ComposerSendBehavior enter/modEnter, exactly two bindings (composer.rs:1364) | `messageEnterBindings` (composer.tsx:2120-2124) | match |
| Escape | completion → wizard back → queue-edit cancel → bubble (composer.rs:7473-7483; ticket 14 §2.7) | same ladder (composer.tsx:2214-2234, 2353-2361) | match |
| keyboard history (ArrowUp) | none exists on the desktop | none on the web | match (do not invent) |

### 2.2 `createChat` — the full wire payload

Desktop assembly (`composer.rs:6494-6544`, read at HEAD):

```rust
let mut mutate = serde_json::json!({ "op": "createChat", "chatId": chat_id });
// then, only when present:
//   spaceId — when a project is picked; else deviceId (the host device outright)
//   cwd     — worktree_cwd only (the checkout plan's ReuseWorktree override)
//   branch  — chat_branch (CurrentCheckout branch / ReuseWorktree branch / NewWorktree base)
//   config  — resolved.chat_config() when Some
```

**Web change.** `createChat` (chat-actions.ts:37-46) gains optional
`cwd`, `branch`, `config` fields, inserted only when present, exactly like
composer.rs:6515-6533:

- `config` — the composer's resolved draft config
  (`buildChatConfig(draft)`, composer-actions.ts:41-49): harness, model,
  reasoning, sandbox, modelOptions. Only a genuinely NEW chat writes a
  config (the desktop never `setChatConfig`-on-send for existing chats —
  ticket 13 deleted `maybePersistConfig` for exactly that reason).
- `branch` — the checkout plan's picked ref where the web models one
  (ticket 10's footer ref pick); absent otherwise.
- `cwd` — a worktree-reuse path when the checkout plan provides one; absent
  otherwise. **The projectless `"~"` does NOT go into createChat** — on the
  desktop it rides the `RunRequest` (§2.1); a projectless createChat is
  `{op, chatId, deviceId, config?}`, "project-less chats name the host
  device outright" (composer.rs:6490-6493).

The engine accepts the shape either way: `MutateParams::CreateChat` takes
`space_id` OR `device_id` + optional `cwd` (rpc.rs:663-686).

**Data / RPC:** `methods.MUTATE` with the payload above; the caller
(`composer.tsx`'s new-chat branch, 1882-1885) passes the resolved options;
`waitForChatRow` (raw id, session cache) is unchanged.

### 2.3 Navigation + optimistic select — scoped ids, no not-found page

**Desktop reference** (spaces research S5 (b)) — the desktop mints scoped
and selects optimistically; there is no not-found page at all:

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

**Web changes.**

- **Scoped navigation (the primary fix):** `onNewThreadLaunched`
  (chat-page.tsx:676-681) navigates with
  `encodeScopedId(session.engine.baseUrl, mintedId)` — the same call
  `state/add-space.ts:782-785` makes for optimistic space rows. The raw id
  stays on the wire (`createChat`, `waitForChatRow`,
  `QUEUE_COMMAND` — request-routing decodes either form), and
  `chatPageRow`'s exact compare (view.ts:455) then matches the merged row.
- **Optimistic select (already the shape, keep it):** the web already hands
  the page the navigation BEFORE the wire call (composer.tsx:1870-1892,
  the `NewThreadTransitionStarted` → `select_chat` port) and
  `waitForChatRow` serializes the route behind the row's arrival. Keep that
  ordering; do not move navigation after the send.
- **The not-found page becomes last-resort only:** with the scoped URL the
  miss disappears for self-created chats; keep the page (and its verbatim
  copy "That chat is not in this engine's list.") for genuinely foreign ids
  only — never for a chat this client just minted. The desktop's
  alternative shape (a transient notice, state.rs:1725-1729) is a judgment
  call to record in Comments, not a requirement.
- **Audit the minted id's consumers after the route flips:** store keys that
  derive from the route param become scoped (the chat page's transcript/
  queue/draft keys key off the URL id — that is how existing chats work
  today via scoped sidebar links, chat-list.tsx:476-481), while the
  composer's wire calls keep the raw id. In particular the optimistic echo
  (`echoStore.pushEcho({ chatId, … })`, composer.tsx:1930-1936) must key the
  chat the way the page's transcript store reads it, so the just-sent
  bubble renders on the scoped route — verify against the existing-chat
  path (where `chat.id` is already the scoped row id) and make the new-chat
  path agree.

### 2.4 The projectless canvas clears the sidebar's space filter

Desktop (`shell.rs:1767-1774`): the shell **takes** `space_filter` when the
canvas is explicitly projectless — the comment states the rule: "retaining a
project filter would hide the session on its first send". Projectless rows
carry no spaceId, and the active list is narrowed by the filter
(`chat-list.tsx:158-163` filters `chat.spaceId === filter`; projectless rows
have `spaceId: null`), so a retained filter hides a just-created projectless
chat from the active list.

**Web change.** `pickNoProject` (composer-footer.tsx:395-399) currently only
writes the remembered defaults (`rememberTarget(snapshot.device, null,
true)` → lib/composer-draft.ts:357-359). It must ALSO clear the sidebar's
space filter: `sidebarStore.setSpaceFilter(null)`
(lib/sidebar-store.ts:77-85 — "null = All projects"; the setter persists
through the ui-settings store, so the clear survives refresh). Ticket 43
(the add-space palette, parallel) cross-references this row to this ticket —
the filter clear is owned here, with the send path it protects.

### 2.5 `mintId()` — the shared id mint

**Every `crypto.randomUUID` call site in web/packages** (research S6 (a),
grep-verified):

| # | file:line | function | runs when |
|---|---|---|---|
| 1 | `web/packages/app/src/lib/chat-actions.ts:29-31` | `defaultMintId` | **every new-chat send** — `createChat` mints the chat id (`options.mintId ?? defaultMintId` at chat-actions.ts:38) |
| 2 | `web/packages/app/src/lib/composer-actions.ts:123-125` | `defaultMint` | every send's message id — `mintMessageId()` (composer.tsx:1907) and `sendRun`'s `(options.mintMessageId ?? defaultMint)` (composer-actions.ts:166) |
| 3 | `web/packages/app/src/lib/queue-actions.ts:186-188` | `mintEditorInstanceId` | queue-row edit leases |
| 4 | `web/packages/app/src/state/transcript-store.ts:263-265` | `defaultMintEchoId` | echo id fallback |
| 5 | `web/packages/app/src/lib/review-comments.ts:45-47` | `mintId` | new diff comments |
| 6 | `web/packages/app/src/lib/attachments.ts:271-282` | `finalizeStage` | staging every dropped/pasted image |
| 7 | `web/packages/app/src/state/add-space.ts:111-113` | `mintId` | the add-space palette |

No id helper exists in `@roboco/engine-client`. The desktop has no such
problem: it mints `Uuid::new_v4()` in Rust (e.g. the chat id and message id
in the send path, composer.rs:6249-6258 area / doc commands), which has no
secure-context gate.

**Web change.** One shared helper, all seven sites routed through it
(grep-replace; delete the seven local mints). The research's algorithm
(`crypto.getRandomValues` IS available in non-secure contexts, so a
standards-shaped v4 is possible everywhere):

```ts
// web/packages/app/src/lib/id.ts (or engine-client/src/id.ts)
export function mintId(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  if (typeof crypto !== "undefined" && typeof crypto.getRandomValues === "function") {
    const b = crypto.getRandomValues(new Uint8Array(16));
    b[6] = (b[6]! & 0x0f) | 0x40; // v4
    b[8] = (b[8]! & 0x3f) | 0x80; // variant
    const hex = [...b].map((x) => x.toString(16).padStart(2, "0")).join("");
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  }
  return `id-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
}
```

Home: `web/packages/app/src/lib/id.ts` — all seven sites live in
`app/src`, so an app-local lib is the single home (the research offered
`@roboco/engine-client` as the alternative for cross-package sharing; no
second consumer exists, so app-local wins — record the choice in Comments).

**Also (the silent-crash half):** move the existing-chat `mintMessageId()`
(composer.tsx:1907) inside the try (or wrap `send`'s body) so ANY future
pre-flight throw surfaces as the failure notice instead of a silent
uncaught rejection. Optionally a dev console warning when
`crypto.randomUUID` is missing (research S6 (d) row 4) — judgment call.

---

## 3. Pure logic to port

### 1. `resolveSendCwd(isNew, spacePath, existingCwd)` — the rule of composer.rs:6433-6440

`spacePath ?? "~"` when new; `existingCwd ?? "."` otherwise (blank/whitespace
counts as absent — the deleted guard's trim rule folds in). Web unit test
mirroring the desktop's
`projectless_composer_allows_send_and_enter_submission`
(`composer.rs:8281`): a projectless canvas send reaches the `QUEUE_COMMAND`
step with `cwd: "~"` and never surfaces the working-directory failure.

### 2. `expandHome` parity check — assert the contract, not the implementation

The engine at `sessions.rs:1303-1313` is authoritative; the web only needs
to keep sending the literal `~` — a unit test pinning
`buildRunRequest(draft, prompt, "~", …).cwd === "~"` suffices. **Never
expand `~` client-side.**

### 3. `mintId()` fallback — pure

Unit-test the three arms (randomUUID present / getRandomValues only /
neither — inject a fake `crypto`). The getRandomValues arm must produce a
standards-shaped v4 (version nibble `4`, variant bits `10`) and the
neither-arm a collision-unlikely fallback.

### 4. Scoped navigation mint — new web unit test (no desktop name; the desktop's equivalent is structural)

`canvasSendNavigatesUnderScopedId`: mint → createChat (raw) →
`waitForChatRow` (raw, session cache) → navigate
`encodeScopedId(engine.baseUrl, rawId)` → `chatPageRow` resolves against
`useFleetSnapshot`'s scoped rows.

### 5. Projectless canvas filter-clearing — mirror the desktop test

Mirror `projectless_new_session_restores_opt_out_and_clears_sidebar_filter`
(`shell.rs:9191`): picking "Don't work in a project" on the canvas clears
the sidebar's space filter so the first send is visible in the list (§2.4).
The web already persists `noProject` (ticket 10's `composerDefaults`); the
test pins the filter-clearing interplay.

### 6. Keep the landed machinery

Nothing else is new: queue/echo/interrupt/failure-recovery semantics are
ticket 13/16's, landed and green (`tests/composer-send.test.ts`,
`tests/composer-actions.test.ts`, `tests/queue-actions.test.ts`,
`tests/pending-send.test.ts`); this ticket must not disturb them.

---

## 4. Gaps this ticket closes

From the spaces research S1 (d), verbatim (all four rows are this ticket's —
row 4 closes with the send path it protects; ticket 43 cross-references it
here):

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
| --- | --- | --- | --- | --- |
| Projectless send cwd | MISSING | new chat cwd = `space_path ?? "~"`, existing = `existing_cwd ?? "."` (`composer.rs:6433-6440`) | hard refusal "pick a space first" (`composer.tsx:1866-1869`) | compute `sendCwd = chat.cwd?.trim() ? chat.cwd : (isNew ? "~" : ".")` and drop both guards |
| `sendRun` cwd contract | WRONG BEHAVIOR | `~`/`.` are legal wire cwd values, host expands (`sessions.rs:342-352`) | throws on empty cwd (`composer-actions.ts:158-160`) | accept any non-empty string; delete the throw |
| Projectless chat visibility | MATCHES (once S1 lands) | first-class rows, label `~` (`state.rs:1432-1444`, `spaces.rs:1382-1389`) | `chatListRows` includes spaceId-null rows (`lib/view.ts:429-435`) | none — becomes reachable once sends work |
| Space-filter clearing on projectless canvas | MISSING | shell takes `space_filter` when the canvas is explicitly projectless ("retaining a project filter would hide the session on its first send", `shell.rs:1767-1774`) | `pickNoProject` only writes defaults (`composer-footer.tsx:395-399` → `lib/composer-draft.ts:357-359`); a retained filter hides the projectless chat from the active list (`chat-list.tsx:158-163`) | clear `sidebarStore.spaceFilter` when `noProject` is picked on the canvas |

From the spaces research S5 (d), verbatim:

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
| --- | --- | --- | --- | --- |
| New-chat id namespace | WRONG VALUE | scoped at mint (`composer.rs:6049`) | raw uuid in URL (`chat-page.tsx:678`, minted `chat-actions.ts:38`) | keep the raw id for the wire, navigate with `encodeScopedId(session.engine.baseUrl, rawId)` (desktop parity; note `state/add-space.ts:782-785` already does exactly this for optimistic space rows) |
| Selection timing | WRONG BEHAVIOR | select_chat commits before the RPC (`composer.rs:6249-6258`); missing rows are tolerated (`state.rs:1760`) | navigate only after `waitForChatRow`, then hard-fail on a row miss (`chat-page.tsx:702-711`) | with the scoped URL the miss disappears; independently, degrade the not-found page to the desktop's notice shape (transient, with the chat still opening) |
| Not-found page | INVENTED (web-only) | no such state; only the deep-link toast (`state.rs:1725-1729`) | full-page "That chat is not in this engine's list." + Back link (`chat-page.tsx:702-711`) | keep as last-resort for genuinely foreign ids, but never for a chat this client just minted |

From the composer research S6 (d), verbatim:

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
|---|---|---|---|---|
| id minting | MISSING | `Uuid::new_v4()` — no context gate (composer.rs send path) | 7 × bare `crypto.randomUUID()` (table above) | shared `mintId()` fallback helper |
| new-chat send crash | BUG | — | chat-actions.ts:29-31 → composer.tsx:1888 "Send failed: crypto.randomUUID is not a function" | the helper |
| existing-chat send crash (silent) | BUG | — | composer.tsx:1907 outside the try at 1951; `void submit()` at 2285/2296 | the helper + move the mint inside the try |
| plain-HTTP LAN serving | CONTEXT | desktop app is native (no browser) | engine serves the bundle over http (web.rs:1-16; listener.rs:2, 148-151; pairing.rs:200; engine-store.ts:85-91) | the fallback covers it; optionally a dev console warning when `crypto.randomUUID` is missing |

Consolidated (composer research), filtered — rows 12–15:

| # | item | kind | desktop value (file:line) | web value (file:line) | fix |
|---|---|---|---|---|---|
| 12 | Projectless new-chat send | WRONG | cwd `"~"` host-expanded (composer.rs:6433-6440) | blocked with a failure notice (composer.tsx:1866-1869) | allow the send (device home dir) once the engine accepts it |
| 13 | createChat payload | PARTIAL | `{op, chatId, spaceId\|deviceId, cwd?, branch?, config}` (composer.rs:6494-6533) | `{op, chatId, spaceId\|deviceId}` only (chat-actions.ts:37-46) | thread the resolved config (+ worktree branch/cwd) from the draft/checkout plan |
| 14 | id minting | MISSING | `Uuid::new_v4()` | 7 × `crypto.randomUUID` (S6 table) | shared fallback helper |
| 15 | existing-chat mint outside try | BUG | — | composer.tsx:1907 vs try at 1951 | move inside |

Consolidated (spaces research), filtered — rows 1, 2, 3, 4, 13, 14:

| # | item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
| --- | --- | --- | --- | --- | --- |
| 1 | Projectless send cwd `~` | MISSING | `composer.rs:6433-6440` | refusal at `composer.tsx:1866-1869` | `spacePath ?? "~"` (new) / `cwd ?? "."` (existing) |
| 2 | `sendRun` empty-cwd throw | WRONG BEHAVIOR | `~` legal, host expands (`sessions.rs:342-352,1303-1313`) | `composer-actions.ts:158-160` | delete the throw |
| 3 | Projectless rows first-class | blocked | `state.rs:1435-1438` | unreachable via send | lands with #1 |
| 4 | Filter clears on projectless canvas | MISSING | `shell.rs:1767-1774` | `composer-footer.tsx:395-399` | clear the filter on the no-project pick |
| 13 | New-chat id namespace | WRONG VALUE | scoped at mint (`composer.rs:6049`) | raw in URL (`chat-page.tsx:678`) | navigate with `encodeScopedId` |
| 14 | Not-found page | INVENTED | toast only (`state.rs:1725-1729`) | full page (`chat-page.tsx:702-711`) | last-resort only |

---

## 5. Do not

- **INVENTED — do not re-add the cwd guard in any form:** not the
  component-level notice "This chat has no working directory yet — pick a
  space first." (composer.tsx:1866-1869), not the `sendRun` throw
  "This chat has no working directory yet" (composer-actions.ts:158-160),
  not a softer disabled-send variant. The desktop has **no error path** for a
  projectless send (composer.rs:6433-6440). The smoke fixture's comment
  (web_smoke.rs:53-56) documents the workaround, not a spec.
- **Do not** expand `~` client-side — the engine expands it host-side
  (`sessions.rs:342-352`, `1303-1313`); the web sends the literal `"~"` (and
  `"."`). No home-dir guessing, no `navigator`/env probing.
- **Do not** put the projectless `"~"` into `createChat`'s payload — it rides
  the `RunRequest` (the desktop inserts `cwd` into createChat only for the
  worktree-reuse override, composer.rs:6515-6521).
- **Do not** build the NewWorktree/`CreateWorktree`/`WorktreeSpec` flow —
  the web's checkout plan executes `SwitchRef` at pick time (ticket 10
  deviation 2) and `buildRunRequest`'s `worktree` seam stands; threading a
  worktree spec into the Run command is a checkout-plan ticket's scope. This
  ticket only adds createChat's `cwd`/`branch`/`config` fields, populated
  when present.
- **Do not** fix transcript or scroll issues — tickets 40/41 own those; the
  send path is this ticket's only surface.
- **Do not** build the add-space palette or its deviceless-open state —
  ticket 43 (parallel; same research — it owns the palette's null-device
  skeleton, the S3 mirroring residue: the dangling-spaceId hide gate and the
  sidebar remount on engine switch). Projectless sending needs no space,
  and this ticket does not need the palette.
- **Do not** touch the archived-row pill pins or the view-options tooltip —
  ticket 44 (sidebar row and tooltip polish; same research).
- **Do not** alter the route-change/dock transition choreography — ticket 36
  (transition matrix 1:1) owns it; this ticket changes only what the
  send hands the route (the scoped navigation id and its timing), which 36's
  matrix consumes.
- **Do not** touch the composer layout (37) or the picker chip/card loading
  states (38).
- **Do not** change queue/interrupt/echo/failure-recovery semantics —
  tickets 13/16 landed them; the S5 "match (documented seams)" rows stand
  (ticket 13 deviations 2, 3, 6: engine-only capability check,
  engine-state caption stand-in, send_blocked mapping).
- **Do not** persist drafts across refresh — accepted divergence (the
  desktop loses them on app restart too); already recorded in ticket 13
  Comments (research consolidated row 16).
- **Desktop-only, do not attempt:** the deep-link toast mechanism
  ("The linked conversation was not found" wording is the desktop's
  `state.rs:1725-1729`; the web keeps its own last-resort copy),
  `boot_focus_pending` re-claim loop, appshots, `frost::*`, `ROBOCO_*` env
  knobs.
- **Do not** re-add a per-site id mint — all seven call sites route through
  the one shared `mintId()` (grep `randomUUID` under `web/packages` after
  implementation: zero bare call sites).

---

## 6. Acceptance

- [ ] Send from the new-chat canvas with **no space selected** ("Don't work
      in a project" or nothing picked): the chat is created on the host
      device, the run's cwd is `"~"` (delivered literally on the
      RunRequest; expanded by the engine host-side), the message is
      delivered, and no "pick a space first" failure appears.
- [ ] Send from the canvas with a space selected: `createChat` carries
      `{op, chatId, spaceId, config}` (config = the resolved draft config);
      with a picked ref, `branch` rides too; a worktree-reuse path rides
      `cwd` when the checkout plan provides one (unit-test the payload
      assembly, only-when-present inserts included).
- [ ] Existing chat with a blank stored cwd: send succeeds with
      `cwd: "."`.
- [ ] Navigate to the just-created chat: **no "That chat is not on engine's
      list" page** — the chat opens immediately with its transcript and
      queue; the URL carries the scoped id
      (`engine:v1:<base64url([engineKey, rawId])>`).
- [ ] With a space filter retained in the sidebar, picking "Don't work in a
      project" on the canvas clears the filter (`sidebarStore.setSpaceFilter(
      null)`; the trigger reads "All projects") — the first projectless send
      is visible in the active list, not just in "All projects".
- [ ] The optimistic echo renders on the scoped route (the just-sent
      bubble appears without a re-click), and the sidebar row for the new
      chat navigates to the same chat (no duplicate/second route state).
- [ ] New-chat send on a **plain-HTTP LAN origin**
      (`http://192.168.x.x:<port>`, served by the engine's remote bind)
      succeeds — no "Send failed: crypto.randomUUID is not a function".
- [ ] An existing-chat send whose pre-flight mint throws surfaces as the
      failure notice (the mint is inside the try), never a silent dead
      send.
- [ ] `grep randomUUID web/packages` → zero bare call sites; all seven
      mint sites import the shared `mintId()` (queue edit leases, echo ids,
      diff comments, staged attachments, add-space rows included).
- [ ] Unit tests: `projectless_composer_allows_send_and_enter_submission`
      → `web/packages/app/tests/composer-send.test.ts` (web mirror of
      composer.rs:8281 — the projectless send reaches QUEUE_COMMAND with
      `cwd: "~"`); `mintId` three arms (randomUUID / getRandomValues v4
      shape / neither) → `tests/id.test.ts`; `buildRunRequest(…, "~").cwd
      === "~"` pin; the createChat payload assembly →
      `tests/chat-actions.test.ts`; `canvasSendNavigatesUnderScopedId`
      (mint → createChat raw → waitForChatRow raw → scoped navigate →
      `chatPageRow` resolves) → `tests/chat-actions.test.ts`.
- [ ] The landed suites stay green: `tests/composer-send.test.ts`,
      `tests/composer-actions.test.ts`, `tests/queue-actions.test.ts`,
      `tests/pending-send.test.ts`, `tests/add-space.test.ts`,
      `tests/review-comments.test.ts`, `tests/attachments.test.ts`.
- [ ] Screenshot pair, desktop vs web, states: (a) the new-thread canvas
      with no space picked, a first message typed; (b) immediately after
      send — the chat page with the user bubble and the reply streaming,
      URL scoped; (c) the sidebar after the send — the projectless row
      (label `~`) present and clickable; (d) the same flow over a LAN
      http origin.
- [ ] `pnpm -r build` green; `web/packages/app` vitest green.
- [ ] No new literal hex/px where a `--rb-*` token exists (no UI styling
      changes at all, ideally).

## Comments

### Implementer note (2026-09-19)

Landed on `wp2r2/39-new-chat-send-path-parity`, one commit
`fix(web): ticket 39 new-chat send path parity`.

**§2.1** — both guards deleted (the component notice at composer.tsx
1872-1875 pre-edit and the `sendRun` throw at composer-actions.ts:158-160);
`sendRun`'s `chatCwd` is now a resolved non-empty `string`. `resolveSendCwd`
lives in `lib/composer-send.ts` (the send-path pure-logic module its test
file already imports); `send()` computes `sendCwd` once and passes it where
`chat.cwd` went. The RunRequest carries the literal `"~"`/`"."` — nothing
client-side expands them.

**§2.2** — `CreateChatOptions` gains `config?`/`branch?`/`cwd?`, inserted
only when `undefined` (composer.rs:6515-6533 order). The composer's
new-chat call passes `config: buildChatConfig(draft)` always (a genuinely
new chat); `branch`/`cwd` ride only when present — **deviation, recorded**:
the web's picked ref is component-local state inside
`NewThreadGitSelectors`/`RefChip` (ticket 10 deviation 2 — the checkout
executes `SwitchRef` at pick time) and no worktree-reuse path is modeled,
so neither is reachable from `send()` today. The payload assembly (all
combos, only-when-present inserts) is pinned in
`tests/chat-actions.test.ts`; lifting the pick is the checkout-plan
ticket's scope, per this ticket's Do-not #4. The projectless `"~"` never
rides createChat (asserted).

**§2.3** — `onNewThreadLaunched` (chat-page) scopes at navigation:
`encodeScopedId(session.engine.baseUrl, mintedId)`; the composer keeps the
raw id on the wire and computes the same scoped `pageChatId` for the stores
that derive from the route param. Audited consumers: the echo
(`pushEcho`/refresh — now keyed `pageChatId`, so `forChat(docId)` and
`ackFromFrame` match on the scoped route), the draft map, the staged stash,
the review-comment restore key, and the failure-notice key — all now key
the page id; `createChat`/`waitForChatRow`/`QUEUE_COMMAND` keep the raw id.
`waitForChatRow` waits on the registry's own watch cache (session.cache IS
`engineRegistry.watchCacheFor`), so the merged scoped row and the raw
session row land together — no new race. The not-found page stays verbatim
and last-resort only (a scoped URL id now matches the merged row); **the
desktop's transient-notice shape was NOT adopted** (judgment call the
ticket left to Comments — the page is kept for genuinely foreign ids).

**§2.4** — `pickNoProject` routes through a new exported
`rememberNoProject(device, sidebar)` (lib/composer-draft.ts, next to
`rememberTarget`): persists the opt-out target AND calls
`sidebar.setSpaceFilter(null)`. The footer passes the singleton; the
sidebar parameter is the test seam (vitest env is `node` — no render
harness exists), used by the §3.5 mirror in
`tests/composer-draft.test.ts`
(`projectless_new_session_restores_opt_out_and_clears_sidebar_filter`).

**§2.5** — `lib/id.ts` `mintId()` (app-local home, per the ticket's stated
preference — no second consumer exists); all seven sites route through it
(`chat-actions`, `composer-actions` message ids, `queue-actions` edit
leases, `transcript-store` echo ids, `review-comments`, `attachments`
`finalizeStage`, `add-space`); `grep randomUUID web/packages` → zero bare
call sites. The optional dev console warning is implemented (warn-once,
DEV only). The existing-chat mint moved inside the try — the try now opens
BEFORE the mint (its first statement), so any pre-flight throw runs the
full failure recovery (echo cleanup via a `pushedEchoId` that stays null
until an echo publishes; text/attachments/comments hand-back keyed the
page id) and surfaces as the notice, never a silent dead send.

**§3 tests** — `resolveSendCwd` + the
`projectless_composer_allows_send_and_enter_submission` mirror (reaches
QUEUE_COMMAND with `cwd: "~"`) + the `buildRunRequest(…, "~").cwd === "~"`
pin → `tests/composer-send.test.ts`; `mintId` three arms → new
`tests/id.test.ts` (vi.stubGlobal fake crypto); createChat payload
assembly + `canvasSendNavigatesUnderScopedId` (mint raw → createChat raw →
waitForChatRow raw → scoped navigate → `chatPageRow` resolves, raw misses)
→ `tests/chat-actions.test.ts`; the §2.4 filter-clearing mirror →
`tests/composer-draft.test.ts`. Updated the old-guard test
(`rejects when the chat has no cwd` → `~`/`.` are legal wire values).
1249 green (1235 at base + 14 new); `pnpm -r build` green.

**Verification gaps** — the live-verification acceptance rows (screenshot
pair incl. the LAN-HTTP origin, zero-spaces bootstrap) need a paired
engine; unit mirrors landed instead. Pre-existing seam noticed and left
alone (out of this ticket's surface, matches existing-chat behavior
today): the session-notification driver reads
`echoStore.forChat(<raw session-cache chatId>)` while echoes key the
page (scoped) id — ticket 13/16 own those semantics.

**Line-number drift** — the ticket's citations predate the wave-2 merges;
verified against the worktree before editing (guard at composer.tsx
1872-1875, throw at composer-actions.ts:158-160, pickNoProject at
composer-footer.tsx:395-399 — all matched; chat-page's
`onNewThreadLaunched` had moved to :808-813).
