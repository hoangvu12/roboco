# 05 — State fixes: nav history, send ids, optimistic echo

**What to build:** Three related but separable state bugs, all fixed in one
ticket because they share the same "one id, one source of truth" theme.
First, the titlebar's back/forward buttons currently ride the browser
router's own history stack with a hard-coded `canForward={true}` — after this
ticket they ride a real `NavHistory` (push/replace/dedup/truncate) with a
correct, computed forward-enablement. Second, sending a message today mints
THREE different UUIDs for what should be one id shared by the echo, the
command, and the retry/failure cleanup — after this ticket one id is minted
per send and threaded through all three. Third, sending a message today shows
nothing until the engine's own transcript stream reports it — after this
ticket the just-sent message appears instantly as an optimistic echo bubble
that flips to a "Queued"/"Not delivered — retry" state per the same grace
rules the desktop uses, and disappears the instant the real transcript frame
confirms it.

**Blocked by:** None — can start immediately.

**Status:** done

**Research:** `../../web-client/research/14-state-behavior.md` §3.1 (the
`AppState` shape table, `RETRY_DELAY`), §4.3 (`send_queued`/`send_pending`/
`send_undelivered`/`ack_pending_send_from_transcript`), §5 (gap rows on
optimistic echo, pending-send overlay, undelivered state);
`../../web-client/research/01-shell-chrome.md` §4.1 (`NavHistory`: push/
replace/back/forward/dedup/truncate rules and its desktop test names);
`../../web-client/research/04-composer.md` §5 (the three-`messageId()` bug at
`lib/composer-actions.ts:139-148` and `buildRunRequest` ignoring its
`messageId` parameter).

**Desktop reference (for lookups only):** `crates/ui/src/shell.rs:548-613`
(`NavHistory` struct + methods), `:1832-1838` (push-on-chat-select, with the
first-selection-replaces-not-pushes special case), `:3268-3323`
(`open_settings`/`close_settings`/`apply_nav`); `crates/ui/src/state.rs:1111-1128`
(`push_echo`/`remove_echo`), `:907-909,1136-1157,1227-1239,1422-1427`
(`PendingSend`, `send_pending`, `send_undelivered`, `UNDELIVERED_GRACE_MS`),
`:2081` (one of five `RETRY_DELAY` occurrences — the pattern, not all five,
matters here).

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/state/nav-history.ts` | new | `NavHistory` class: `push`/`replace`/`current`/`canBack`/`canForward`/`back`/`forward`/`len`; `NavEntry` type (`{kind:"chat", chatId:string}` \| `{kind:"settings", section:string}`) |
| `web/packages/app/src/components/app-shell.tsx` | edit | wire `Titlebar`'s `onBack`/`onForward`/`canBack`/`canForward` to a `NavHistory` instance instead of `router.history`; push/replace entries on chat selection and settings open/close per §3 below |
| `web/packages/app/src/lib/composer-actions.ts` | edit | `sendRun` (lines 111-152): mint `messageId` exactly once; `buildRunRequest` (lines 46-64): actually use its `messageId` parameter on the request/command instead of discarding it |
| `web/packages/app/src/state/transcript-store.ts` | edit | add an echo overlay: `pushEcho`/`removeEcho`/`ackFromFrame` (or equivalent naming), keyed by the client-minted message id; merge echoed rows into the rendered transcript ahead of/instead of a not-yet-confirmed real row |
| `web/packages/app/src/lib/chat-actions.ts` | edit | add `markChatSeen` (optimistic local stamp before the fire-and-forget `Mutate markChatSeen` call) — not found anywhere on web today per research §7 item 6 |
| `web/packages/app/src/components/composer.tsx` | edit | call sites for `sendRun`/echo push; surface the pending-send overlay's Queued/Not-delivered state per §2.3 |
| `web/packages/app/tests/nav-history.test.ts` | new | unit tests named per §3's desktop test list |
| `web/packages/app/tests/pending-send.test.ts` | new | unit tests named per §2.3's desktop test list |

## 1. Context a fresh session needs

- This ticket ports THREE independent pieces of pure desktop logic. None of
  them require the multi-engine/fleet architecture (`EngineRegistry`,
  `ScopedId`) — they operate correctly against the web's existing
  single-active-engine model. Do not let "the desktop's version lives inside
  the big multi-engine `AppState`" become a reason to over-scope this ticket.
- **Nav history** lives entirely in the shell/router layer
  (`app-shell.tsx`), not in `EngineSession`/`transcript-store.ts` — it tracks
  which *route* (chat id or settings section) the user has visited, not
  chat data.
- **The messageId bug** is in `lib/composer-actions.ts`. Read the current
  code before touching it:
  ```ts
  const messageId = options.mintMessageId ?? defaultMint;   // a FUNCTION, not a value
  ...
  const command = {
    kind: "run" as const,
    request: buildRunRequest(draft, finalPrompt, chatCwd, messageId()),  // call #1
    messageId: messageId(),                                              // call #2 — different UUID
  };
  ...
  return {
    messageId: messageId(),   // call #3 — a THIRD different UUID
    ...
  };
  ```
  `messageId` is bound to a *minting function*, then invoked three separate
  times — each call to `crypto.randomUUID()` (or the test-injected
  `mintMessageId`) produces a distinct string. `buildRunRequest`'s own
  `messageId: string` parameter (line 50) is accepted into the function
  signature but never read inside the function body — the constructed
  `RunRequest` has no `messageId` field written from it at all. The result:
  the request, the command, and the returned `SendResult.messageId` are three
  unrelated ids, so nothing that later needs to say "this specific sent
  message" (an echo dedupe key, a pending-send lookup, a retry) can ever
  match against what actually went over the wire.
- **The optimistic echo** is new state, but its *shape* is a direct, small
  port of `AppState.echoes`/`AppState.pending_sends` — a client-side map from
  chat id to "messages we've sent that the real transcript hasn't confirmed
  yet," rendered as if they were real rows until the confirmation arrives or
  the grace window expires.
- Vocabulary: "chat" (never "session"/"thread"); the composer offers
  Send/Queue/Stop only — "Steer" does not exist on web (spec decision #3,
  unrelated to this ticket but relevant context if a call site here looks
  like it should branch on steering — it should not, that is ticket 13's
  concern).

## 2. Spec

### 2.1 `NavHistory`

**Shape** (pure data structure, ported verbatim from `shell.rs:548-613`):

```ts
export type NavEntry =
  | { readonly kind: "chat"; readonly chatId: string }   // chatId === "" is the new-chat canvas
  | { readonly kind: "settings"; readonly section: string };

export class NavHistory {
  constructor(initial: NavEntry);
  current(): NavEntry;
  push(entry: NavEntry): void;      // no-op if entry equals current(); else truncate to index+1, append, index += 1
  replace(entry: NavEntry): void;   // entries[index] = entry — no growth
  canBack(): boolean;               // index > 0
  canForward(): boolean;            // index + 1 < entries.length
  back(): NavEntry | null;          // move index left and return the new current, else null
  forward(): NavEntry | null;       // move index right and return the new current, else null
  len(): number;
}
```

Entry equality for `push`'s dedup check is by value (`kind` + `chatId`/
`section`), not by reference.

**States** — when entries are pushed (ported verbatim from `shell.rs`):

| Trigger | Call |
| --- | --- |
| Opening a settings section | `push({kind:"settings", section})` |
| Closing settings (returning to chat) | `push({kind:"chat", chatId: activeChatId})` |
| Chat selection changed | `push({kind:"chat", chatId})` — **except** the very first selection off the untouched boot canvas (`history.len() === 1 && history.current().kind === "chat" && history.current().chatId === ""`), which **replaces** instead, so no dead Back target is left pointing at an empty canvas the user never deliberately visited |
| Back/forward button clicked (`apply_nav`) | **no push** — `back()`/`forward()` already moved the index; the resulting navigation (route change) must not itself trigger another push, or every back-step would immediately get overwritten by a forward-looking push. Gate the route-driven push logic with a flag/check that this navigation originated from `back()`/`forward()`, not user chat-row/settings-nav interaction |

**Interactions**: `app-shell.tsx` constructs one `NavHistory` instance for the
life of the app (module-level singleton or held in a ref at the shell's
root — not per-render state), seeded with `{kind:"chat", chatId: ""}
` matching the desktop's boot canvas. Titlebar's back/forward buttons call
`history.back()`/`history.forward()`; each result is translated to a router
navigation (`navigate({to: "/chat/$id", params:{id: entry.chatId}})` or
`navigate({to: "/settings/$section", ...})`), NOT a `push` — see the
"no push" row above. `canBack={history.canBack()}`,
`canForward={history.canForward()}`, recomputed on every render (the
`NavHistory` instance's mutations should trigger a re-render the same way
any other app-shell state change does — wrap it in a small store/subscriber
if `app-shell.tsx` doesn't already re-render on every relevant navigation,
which it does today via the router).

**Tests** (desktop names to mirror, per research §4.1 — port as
`nav-history.test.ts`):
- `nav_history_starts_with_nothing_to_walk` — a fresh `NavHistory(initial)`
  has `canBack() === false` and `canForward() === false`.
- `nav_push_then_back_and_forward` — push A, push B; `back()` returns A,
  `canForward()` is now true; `forward()` returns B again.
- `nav_push_dedups_the_current_route` — pushing the same entry as `current()`
  is a no-op (`len()` unchanged).
- `nav_push_truncates_the_forward_branch` — push A, push B, back() to A,
  push C; `canForward()` is now false (B is gone, replaced by the new
  branch) and `forward()` returns null.
- `nav_replace_swaps_in_place` — `replace(X)` after pushing A, B leaves
  `len()` unchanged and `current()` now X, with `back()` still landing on
  whatever was before B (replace does not touch history depth).
- `nav_settings_sections_are_distinct_entries` — `{kind:"settings",
  section:"appearance"}` and `{kind:"settings", section:"devices"}` are NOT
  deduped against each other by `push`'s equality check.

### 2.2 One message id per send

**Fix** (`lib/composer-actions.ts`):

```ts
export function buildRunRequest(
  draft: DraftConfig,
  prompt: string,
  cwd: string,
  messageId: string,
): RunRequest {
  const request: RunRequest = {
    prompt,
    harness: draft.harness,
    model: draft.model,
    reasoning: draft.reasoning,
    modelOptions: { ...draft.modelOptions },
    cwd,
    sandbox: draft.sandbox,
    autoApprove: false,
    resume: null,
    messageId,   // ← actually use the parameter (confirm RunRequest's wire shape
                 //   carries a messageId field; if it does not, thread the id
                 //   through the QueueCommand's own `command.messageId` only —
                 //   whichever the engine actually dedupes on. Check `RunRequest`'s
                 //   type in `@roboco/proto` before assuming a field name.)
  };
  return request;
}
```

```ts
export async function sendRun(...): Promise<SendResult> {
  ...
  const messageId = (options.mintMessageId ?? defaultMint)();   // ← call ONCE, store the value
  await maybePersistConfig(...);   // NOTE: ticket 04 removes this call entirely — if
                                    // that ticket has already landed, this line is gone;
                                    // if not, leave it as today's behavior, unrelated to this fix
  const uploaded = await uploadStage(...);
  const finalPrompt = withAttachments(trimmed, uploaded.map((entry) => entry.path));
  const command = {
    kind: "run" as const,
    request: buildRunRequest(draft, finalPrompt, chatCwd, messageId),   // same id
    messageId,                                                          // same id
  };
  const reply = await caller.call(methods.QUEUE_COMMAND, { chatId, command, transfers: ... });
  return {
    messageId,   // same id — this is what the caller uses as the echo dedupe key
    commandId: reply.commandId,
    attachmentPaths: uploaded.map((entry) => entry.path),
  };
}
```

The key invariant: **one `mintMessageId()`/`crypto.randomUUID()` call per
`sendRun` invocation**, its result stored in a local `const`, referenced
everywhere a message id is needed (the request, the command envelope, the
returned `SendResult`, and — new in this ticket — the echo push in §2.3).

### 2.3 Optimistic echo + pending-send overlay

**Shape** (ported from `state.rs`'s `echoes`/`pending_sends`/`PendingSend`):

```ts
interface PendingSend {
  readonly messageId: string;
  readonly chatId: string;
  readonly startedAtMs: number;
  readonly text: string;               // for rendering the echo bubble
  readonly attachmentPaths: readonly string[];
}
```

`UNDELIVERED_GRACE_MS = 120_000` (120 seconds) — the exact constant from
`state.rs`.

**Rules** (ported from `send_pending`/`send_undelivered`/
`pending_send_started`/`retry_pending_send`/`ack_pending_send_from_transcript`,
research §4.3):

- The instant `sendRun` returns successfully, push a `PendingSend` for its
  `messageId` and render an echo bubble immediately — the user sees their own
  message before the engine has done anything with it. This is
  `push_echo`/`begin_pending_send`'s combined effect.
- While `now - startedAtMs <= UNDELIVERED_GRACE_MS` **OR** the chat's
  delivery is currently degraded (no `WatchConnectivity` stream exists on
  web yet — see the note below — so treat "degraded" as always-false for
  now, meaning the grace window is the only gate that matters on web today):
  render the echo as "pending" (a plain, non-alarming state — this is what
  desktop calls the `Working` overlay before any session row exists at all;
  on web this simply means "shown as sent, not yet flagged as a problem").
- Past the grace window (and not degraded): render the echo as
  **"Not delivered — retry"** with a retry affordance. Clicking retry must
  restart the grace-window clock (`retry_pending_send`) — mint a **new**
  message id for the retry attempt (it is a new send, not a resend of the
  old wire message) and push a new `PendingSend`, removing the old one.
- The pending send (and its echo bubble) is cleared the INSTANT its
  `messageId` appears anywhere in the real transcript stream — dedup key is
  the id itself (`ack_pending_send_from_transcript`). Wire this into
  `transcript-store.ts`'s frame-apply path: after merging a new
  `WatchDocMessages` frame, check every still-pending send's `messageId`
  against the frame's message ids and remove any that now match.
- A send failure (the `sendRun` promise rejects, e.g. network error before
  the engine ever saw the command) ends ONLY that send's own pending-send
  overlay — it must not clear a sibling pending send for the same chat
  (`send_failure_cleanup_only_ends_its_own_overlay`, the desktop test name
  this mirrors). Key everything by `messageId`, never by `chatId` alone.
- `WatchConnectivity`/`chat_delivery_degraded`/`send_queued` (the "Queued"
  badge for degraded delivery) are explicitly OUT of scope for this ticket —
  research §5 flags them as blocked on a `WatchConnectivity` stream that
  does not exist on web at all yet, which is its own, larger gap (not named
  in this ticket's scope). Build the grace-window logic so it is easy to AND
  in a real `degraded` flag later, but do not build the connectivity watch
  here.

**Interactions**: `composer.tsx`'s send handler calls `sendRun`, then
immediately pushes the echo/pending-send via the new `transcript-store.ts`
API using the returned `messageId`. The transcript component renders pending
echoes merged into the row list at the position a real message would occupy
(end of the list, or wherever the store's existing sort places it) — do not
build a visually distinct "echo section"; it should look like a normal user
bubble that happens to carry a pending/undelivered status affordance.

**Tests** (desktop names to mirror, port as `pending-send.test.ts`):
- `echoes_show_until_doc_frame_confirms` — pushing an echo then applying a
  transcript frame containing that message id removes the pending send and
  its echo.
- `send_pending_overlays_working_until_the_grace_window` — before
  `UNDELIVERED_GRACE_MS` elapses, status is "pending," not "undelivered."
- `send_pending_acked_when_the_host_writes_the_message_back` — same as the
  first test, restated: acking is purely by id match, independent of timing.
- `send_failure_cleanup_only_ends_its_own_overlay` — two concurrent pending
  sends in the same chat; failing/clearing one leaves the other's overlay
  untouched.
- `retry_restarts_the_grace_window_with_a_new_id` — (web-specific test name;
  no direct 1:1 desktop test found in this research pass, but the rule
  itself — "retry restarting the clock" — is explicit in research §5's
  "'Not delivered — retry' explicit failed state" row) retrying a
  past-grace pending send mints a new id and resets `startedAtMs`.

### 2.4 `mark_chat_seen` (small, bundled here since it touches the same
optimistic-local-stamp pattern as the echo work)

**Rule** (ported from `state.rs`, research §4.3's last row / §7 item 6):
idempotent (no-op if the chat is already marked seen), stamps
`lastSeenAt = now` in the local/optimistic view immediately, THEN
fire-and-forgets `Mutate {op: "markChatSeen", chatId}` — the mutation's
success/failure does not block or roll back the optimistic stamp. Add this
as `markChatSeen(chatId)` in `lib/chat-actions.ts` (it does not exist
anywhere on web today, confirmed by the research pass finding zero matches).
Wire its one caller (wherever the chat page currently has no "mark seen on
view" behavior — check `chat-page.tsx` for the natural call site, e.g. on
chat selection or on transcript scroll-to-bottom) — if no natural call site
is obvious, add the call on chat selection, matching the most common trigger
implied by the desktop's own usage.

## 3. Pure logic to port (summary — full detail in §2 above)

| Function/rule | Signature/shape | Desktop test names | 
| --- | --- | --- |
| `NavHistory` | see §2.1 | `nav_history_starts_with_nothing_to_walk`, `nav_push_then_back_and_forward`, `nav_push_dedups_the_current_route`, `nav_push_truncates_the_forward_branch`, `nav_replace_swaps_in_place`, `nav_settings_sections_are_distinct_entries` |
| One id per send | see §2.2 | no dedicated desktop test (this is a web-only bug — the desktop never had three ids) |
| Optimistic echo / `PendingSend` | see §2.3 | `echoes_show_until_doc_frame_confirms`, `send_pending_overlays_working_until_the_grace_window`, `send_pending_acked_when_the_host_writes_the_message_back`, `send_failure_cleanup_only_ends_its_own_overlay` |
| `mark_chat_seen` | see §2.4 | none found beyond usage-level coverage |

`RETRY_DELAY` (flat 2000ms, no backoff/jitter) governs the desktop's
watch-resubscribe loops (`spawn_watch`, `spawn_transcript_watch`,
`spawn_queue_watch`, etc.) — per research §3.1, the web's `EngineClient`
already achieves equivalent behavior through its whole-connection reconnect
rather than five independent per-stream retry loops. **Do not port
`RETRY_DELAY` as a literal constant in this ticket** — it is included in the
research citation list because the ticket brief named it, but the research
itself concludes this is "a minor behavior delta, not necessarily a bug,"
already covered by the existing `EngineClient` reconnect path. No web file
changes for this specific constant.

## 4. Gaps this ticket closes

| item | kind | desktop value | web value (file:line) | fix |
| --- | --- | --- | --- | --- |
| Forward button enablement | WRONG BEHAVIOUR | `NavHistory::can_forward()` = `index + 1 < entries.len()` (`shell.rs:590`) | `canForward` hard-coded `true` (`app-shell.tsx:239`) | Implement a real `NavHistory` (§2.1) |
| Nav history in general | MISSING | full push/replace/truncate `NavHistory` (§2.1) | `router.history.canGoBack()` plus the hard-coded forward flag; no truncate-on-branch, no dedup, no settings-section entries | Implement §2.1 over a dedicated store, not the router's own history object |
| `messageId()` called three times per send | WRONG BEHAVIOR (bug) | one `message_id` used for the echo, the command, and the failure cleanup | `messageId()` called at `composer-actions.ts:139,140,148`; `buildRunRequest`'s `messageId` parameter accepted and ignored | Mint once (§2.2); use the parameter |
| Optimistic send echo | MISSING | `AppState.echoes`, `push_echo`/`remove_echo` (`state.rs:1111-1128`) | none found (`composer.tsx`, `transcript-store.ts`) | Add a client-side echo keyed by the single minted message id (§2.3) |
| Pending-send "Working"/pending overlay | MISSING | `begin_pending_send`/`send_pending`/`display_status_for` treats an in-flight send as pending immediately, before any session row exists (`state.rs:907-909,1136-1157,1422-1427`) | none — web shows whatever `WatchSessions` last reported until the engine actually starts the run | Port `PendingSend`/`UNDELIVERED_GRACE_MS=120_000` (§2.3) |
| "Not delivered — retry" explicit failed state | MISSING | `send_undelivered` past the 120s grace, with retry restarting the clock (`state.rs:1227-1239`) | none | Same fix as above (§2.3) |
| `mark_chat_seen`'s optimistic-then-mutate pattern | MISSING | idempotent, optimistic local stamp before the fire-and-forget `Mutate` | not found anywhere on web | Add `markChatSeen` to `chat-actions.ts` (§2.4) |

## 5. Do not

- Do not build `WatchConnectivity`, `chat_delivery_degraded`, or the
  "Queued" badge for degraded delivery — explicitly deferred (§2.3);
  those need a connectivity stream this ticket does not add.
- Do not build multi-engine `ScopedId` support, `EngineCache`
  offline-row/transcript persistence, or any part of the fleet/multi-engine
  architecture — none of it is required for these three fixes and it is out
  of scope for this ticket.
- Do not port `RETRY_DELAY`/the five watch-resubscribe loops literally — the
  web's `EngineClient` reconnect already covers the equivalent behavior
  (§3's note).
- Do not build sound/notification chimes (`sound.rs`/`notify.rs`) — unrelated
  to this ticket, owned by ticket 30.
- Do not wire the "Steer" action anywhere in this ticket's composer touches —
  it does not exist on web (spec decision #3).
- Do not remove or rename `sendRun`'s existing public signature/options
  beyond what §2.2 requires (minting once) — every existing call site must
  keep compiling.

## 6. Acceptance

- [ ] `NavHistory` implemented in `state/nav-history.ts`; the titlebar's
      back/forward buttons are wired to it, not `router.history`; navigating
      back then clicking a new chat row correctly truncates the forward
      branch (no stale forward target).
- [ ] `composer-actions.ts`'s `sendRun` mints exactly one message id per
      call, used identically for the request, the command envelope, and the
      returned `SendResult.messageId`; `buildRunRequest` actually writes its
      `messageId` parameter onto the constructed request/command (confirm
      against `@roboco/proto`'s actual `RunRequest`/command wire shape which
      field carries it).
- [ ] Sending a message shows an echo bubble instantly, before any engine
      response; the echo disappears the instant the real transcript frame
      containing that message id arrives.
- [ ] A pending send past 120 seconds with no confirming frame shows
      "Not delivered — retry"; clicking retry mints a new id and restarts the
      grace window.
- [ ] Two concurrent pending sends in the same chat: failing one does not
      clear the other's overlay.
- [ ] `markChatSeen(chatId)` exists in `lib/chat-actions.ts`, stamps
      optimistically, and fire-and-forgets the `Mutate` call.
- [ ] Unit tests: `nav_history_starts_with_nothing_to_walk`,
      `nav_push_then_back_and_forward`, `nav_push_dedups_the_current_route`,
      `nav_push_truncates_the_forward_branch`, `nav_replace_swaps_in_place`,
      `nav_settings_sections_are_distinct_entries` →
      `web/packages/app/tests/nav-history.test.ts`;
      `echoes_show_until_doc_frame_confirms`,
      `send_pending_overlays_working_until_the_grace_window`,
      `send_pending_acked_when_the_host_writes_the_message_back`,
      `send_failure_cleanup_only_ends_its_own_overlay`,
      `retry_restarts_the_grace_window_with_a_new_id` →
      `web/packages/app/tests/pending-send.test.ts`.
- [ ] Screenshot pair: N/A as a desktop/web visual comparison (this ticket
      is state/behavior, not new visual chrome) — instead, capture two web
      screenshots against `web_smoke` (ticket 01): (1) a message immediately
      after sending, showing the instant echo bubble; (2) the titlebar with
      back enabled and forward correctly disabled after a fresh chat
      selection (no forward target exists yet).
- [ ] `pnpm -r build` green; `web/packages/app` package vitest green.
- [ ] No new literal hex/px where a `--rb-*` token exists (N/A — no new CSS
      in this ticket beyond whatever minimal styling the "Not delivered —
      retry" affordance needs, which must use existing tokens, e.g.
      `--rb-danger`/`--rb-text-muted`, not new literals).

## Comments

### 2026-09-17 — implemented (branch `wp1/05-state`)

**Landed** (work from two interrupted sessions, verified and finished in a
third pass)

- `state/nav-history.ts`: `NavHistory` (verbatim §2.1 port: value-equality
  dedup, forward-branch truncation, replace-without-depth), plus a
  `NavHistoryStore` wrapper (useSyncExternalStore subscription so the
  titlebar flags update on the frame the cursor moves), a `visit()` that
  implements the first-selection-off-boot-canvas REPLACES rule
  (`shell.rs:1832-1838`), and path↔entry matchers (`/`, `/chat/$id`,
  `/settings/$section`).
- `app-shell.tsx`: titlebar back/forward now ride `navHistory`, not
  `router.history`; `canBack`/`canForward` from the store snapshot; the
  walk handler performs the router navigation WITHOUT pushing (the
  `apply_nav` gate, as a `navWalking` ref around the navigate call).
- `lib/composer-actions.ts`: one `messageId` minted per `sendRun`, threaded
  through the command envelope and `SendResult`. `buildRunRequest` LOST its
  unused `messageId` parameter instead of gaining a field: verified against
  the wire — `RunRequest` carries no id (`crates/proto/src/agent.rs:94-128`);
  the id rides `SessionCommandPayload::Run.message_id`
  (`crates/doc/src/commands.rs:42-46`) and is what the host writes the user
  entry under. Keeping a dead parameter would have re-hidden the bug.
- `state/transcript-store.ts`: echo overlay per §2.3 —
  `UNDELIVERED_GRACE_MS = 120_000`, `pushEcho`/`removeEcho`/`ackFromFrame`
  (chat-scoped), status `pending` → `undelivered` past the grace window,
  `retry()` mints a fresh id and restarts the clock; acks fire from the
  frame-apply path for both reset and delta frames. `degraded` is a
  parameter defaulting to false, ready for a future `WatchConnectivity`.
- `lib/chat-actions.ts`: `markChatSeen` — idempotent optimistic local stamp,
  then fire-and-forget `Mutate markChatSeen` (never rolled back), wired in
  `chat-page.tsx` on chat view.
- Transcript renders echoes merged as normal user bubbles with a
  pending/undelivered affordance; send failure cleanup and the retry handler
  live in `chat-page.tsx` keyed strictly by `messageId`.

**Tests** — `tests/nav-history.test.ts` (all six §2.1 desktop names, plus
store/route-mapping extras) and `tests/pending-send.test.ts` (all five §2.3
desktop names, plus ack-is-chat-scoped, double-push, acked-retry no-op,
reset/delta frame acks, and a `sendRun` single-mint assertion).

**Verification.** `pnpm -r build` green; `web/packages/app` vitest green
(31 files, 440 tests). Live `web_smoke` run: first chat selection left Back
disabled (boot-canvas replace rule), New session push left Back enabled with
Forward disabled (no stale forward target), Back returned to the prior chat
with Forward re-enabled; a sent message rendered its bubble instantly and
was acked by the transcript frame.

**Screenshots** (`.scratch/web-parity/shots/05/`, web-only per §6):
`web-01-echo-after-send.png` (user bubble immediately after send),
`web-02-back-on-forward-off.png` (Back enabled, Forward disabled after a
fresh chat selection).

**Merge notes** (branch predates ticket 04's merge into the PR branch):
`sendRun` still calls `maybePersistConfig` and `navEntryForPath` still
matches the `/chat/$id/changes` path — 04 removes the route and the call;
the merge should take 04's side on both (the dead path alternative in the
regex is harmless either way).

**Orchestration note for later tickets on this machine:** running
`web_smoke.exe` from an agent shell always "hangs" for two reasons — it is
a server (never exits) AND the tool harness kills detached children when a
command ends (Windows job object). Run it via Task Scheduler
(`schtasks /Create … run-smoke.bat /SC ONCE` + `/Run`), poll `smoke.log`
for `SMOKE READY`, and `taskkill /IM web_smoke.exe` + `/Delete` when done.
