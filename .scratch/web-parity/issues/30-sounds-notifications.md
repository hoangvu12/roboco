# 30 — Sounds and notifications

**What to build:** A user running a chat in a background tab now hears the
same three chimes the desktop plays (a run finishing, an agent needing
input, a run failing or the connection degrading) and — once they grant
browser notification permission — sees the same banners, gated by the same
per-event toggles the Notifications settings page (ticket 29) exposes.
Clicking a banner focuses the tab and navigates to the chat it came from.
Nothing chimes or bannered on first load/replay of state already in
flight; nothing double-chimes when a session error and a connectivity drop
happen together.

**Blocked by:** 03 (Client settings store), 05 (State fixes: nav history, send ids, optimistic echo)

**Status:** ready-for-agent

**Research:** `../../web-client/research/14-state-behavior.md` §3.6, §4.4, §5 rows "Session/desktop notification chimes", "`WatchConnectivity` stream", "`sound.rs`/`notify.rs` settings keys", "Device online/presence... derivation" (the freshness-adjacent parts only), §6 (desktop-only delivery mechanics), §7.2.

**Desktop reference (for lookups only):** `crates/ui/src/sound.rs` (617 lines, full), `crates/ui/src/notify.rs` (378 lines, full), `crates/ui/src/shell.rs:1677-1766` (the only call site), `crates/ui/src/settings.rs` (the 6 `sound*`/`notifications*` fields), `crates/proto/src/entities.rs` (`Indicator`, `ConnectivityState`).

**Web files to touch:**
| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/lib/notifications.ts` | new | `SessionNotificationState`, `ConnectivityNotificationState`, `AttentionSoundGate`, `soundSince`, `connectivitySoundSince`, banner title/body builders, `Notification` API wrapper, `chatId` payload + click routing |
| `web/packages/app/src/lib/sounds.ts` | new | `Sound` enum/union, `playSound(sound)` (`<audio>` element pool, respects the `soundEnabled` master + per-kind toggles), env/query-flag kill-switch check |
| `web/packages/app/src/state/session-provider.tsx` | edit | wire the decision engine's call site: build baselines per session/connectivity change, call `soundSince`/`connectivitySoundSince`, gate on settings, play/post |
| `web/packages/app/src/state/hooks.ts` | edit (only if a new hook is the cleanest way to expose "is any Roboco tab/window focused" — otherwise reuse `document.visibilityState`/`document.hasFocus()` inline) | app-focus detection for the notifications gate |
| `web/packages/engine-client/src/methods.ts` | edit | add `WATCH_CONNECTIVITY` (does not exist yet — confirmed by grep of `methods.ts`) |
| `web/packages/engine-client/src/watch-cache.ts` | edit | add a `connectivity` `RowSet`-equivalent (or a dedicated single-value watch, since `Connectivity` is one object per engine, not a row collection) fed by `WATCH_CONNECTIVITY`, exposed on `WatchCacheSnapshot` |
| `web/packages/app/public/sounds/done.wav` | new (binary copy) | copied byte-for-byte from `crates/ui/assets/sounds/done.wav` |
| `web/packages/app/public/sounds/request.wav` | new (binary copy) | copied byte-for-byte from `crates/ui/assets/sounds/request.wav` |
| `web/packages/app/public/sounds/attention.wav` | new (binary copy) | copied byte-for-byte from `crates/ui/assets/sounds/attention.wav` |
| `web/packages/app/src/routes/settings-notifications.tsx` | read-only dependency | ticket 29 already builds this page's 6 toggles into the client settings store; this ticket reads those fields, does not re-add the UI |

## 1. Context a fresh session needs

- This is a state-machine port, not a visual surface — there is no
  desktop screenshot to match beyond "a banner appears" and "a chime
  plays". Focus on the decision logic being bit-for-bit faithful; the
  delivery mechanism (native OS calls vs. `Notification`/`<audio>`) is
  necessarily different and documented as such in §5.
- The desktop's decision engine lives in `sound.rs` as three small pure
  structs (`SessionNotificationState`, `ConnectivityNotificationState`,
  `AttentionSoundGate`) with their own unit tests — port these as plain
  TypeScript classes/functions with the same test names, the same
  pattern `lib/view.ts` already used successfully for `proto::view.rs`.
  Do not fold the decision logic into the call site; keep it pure and
  independently testable.
- There is exactly **one call site** on desktop
  (`shell.rs:1677-1766`) that turns a decision into an actual chime/
  banner, run on every render tick / state change. On web, the natural
  equivalent call site is wherever the app already observes
  `sessions`/`chats`/(new) `connectivity` changes per active engine —
  most likely inside `state/session-provider.tsx` or a sibling effect
  hook it owns. Whichever file it ends up in, it must run once per
  meaningful state change (not once per render) and must not re-fire for
  a state it has already reacted to.
- **Blocked by ticket 05, not just listed as a dependency**: correct
  optimistic-echo/pending-send handling matters here because the
  completion-chime rule (`!send_pending && fresh && ...`) needs a
  `send_pending` signal. If ticket 05 has not landed a `send_pending`
  concept yet, stub it as always-`false` and note the gap in Comments
  rather than blocking this entire ticket — but do not silently drop the
  check, since dropping it would make queued-message completions
  double-chime (see the `pending_send_consumes_completion_but_preserves_input_requests`
  test below).
- This ticket also needs `WatchConnectivity`, which does not exist
  anywhere in the web client today (confirmed: `methods.ts` has no
  `WATCH_CONNECTIVITY` constant, and no file calls it). Add the method
  constant and a minimal single-value watch/store for it — modeled on how
  `WatchQueue` or `WatchDevices` are already wired in `watch-cache.ts`,
  but note `Connectivity` is a single object per engine, not a row
  collection, so it does not need `RowSet`'s multi-row diffing machinery.
- The Notifications settings page (ticket 29, §2.4) is the source of
  truth for the six gating booleans: `soundEnabled`,
  `soundCompletionEnabled`, `soundInputEnabled`, `soundAttentionEnabled`,
  `notificationsEnabled`, `notificationsBackgroundOnly`. This ticket reads
  them from ticket 03's client settings store; it does not add its own
  settings UI.
- Vocabulary: chat (not session/thread) in all banner/UI text; "session"
  only refers to the pairing credential, never to a chat — the desktop's
  internal `SessionNotificationState`/`Session` naming here refers to a
  **chat's run state**, not the pairing credential; do not let that
  naming collision leak into any user-facing string.

## 2. Spec

This surface has no paintable layout (per research 14's own template
note: **Shape** replaces **Layout**, **Motion** covers only the
audio/visual attention cues).

### 2.1 Decision-engine shape

```ts
interface SessionNotificationState {
  indicator: Indicator; // "none" | "working" | "awaitingInput" | "errored"
  lastCompletedTurn: string | null;
  fresh: boolean;
}

interface ConnectivityNotificationState {
  previous: ConnectivityState | null;
  firstObservedAt: number | null; // epoch ms
  armed: boolean;
}

interface AttentionSoundGate {
  lastPlayed: number | null; // epoch ms
}
```

### 2.2 Embedded assets and low-level plumbing

| Item | Desktop value | Which outcome plays it | Web equivalent |
| --- | --- | --- | --- |
| `SOUND_DONE` | `crates/ui/assets/sounds/done.wav` | `Sound.Done` (completion chime) | `web/packages/app/public/sounds/done.wav`, played via `<audio src="/sounds/done.wav">` |
| `SOUND_REQUEST` | `crates/ui/assets/sounds/request.wav` | `Sound.Request` (input-needed chime) | `public/sounds/request.wav` |
| `SOUND_ATTENTION` | `crates/ui/assets/sounds/attention.wav` | `Sound.Attention` (run-error / connectivity-degradation chime) | `public/sounds/attention.wav` |
| `SOUND_APPSHOT` | `crates/ui/assets/sounds/appshot.wav`, macOS/Linux-only | not part of the `Sound` decision engine at all — the Appshot capture-confirmation shutter cue | **do not port** — Appshots is desktop-only (§5/§6) |
| `DISABLE_ENV` = `ROBOCO_DISABLE_SOUND` | env var kill-switch checked by both `play()` and the Appshot player | Web equivalent: a URL query flag (e.g. `?disableSound=1`) or a `localStorage` dev flag checked once at boot by `lib/sounds.ts::playSound` — pick one, document the choice in Comments, and make it a no-op in production builds unless explicitly set |
| `ROBOCO_DISABLE_NOTIFICATIONS` (`notify.rs:27`) | env var kill-switch for banners | Same pattern as above, applied in `lib/notifications.ts` before any `Notification` call |
| `TMP_COUNTER`, `SENDER` (macOS audio-worker mailbox) | desktop-only native playback plumbing | **do not port** — no process/thread model in a browser; `<audio>` elements handle concurrent playback natively |

Copy the three `.wav` files byte-for-byte from
`crates/ui/assets/sounds/` into `web/packages/app/public/sounds/` (binary
copy, not a re-encode) so the actual chime audio matches the desktop
exactly, not just its trigger logic.

### 2.3 Decision rules

| Rule | Condition | Result |
| --- | --- | --- |
| Error chime | `indicator === "errored" && prev.indicator !== "errored"` | `Sound.Attention` (checked before completion/request) |
| Input-needed chime | `indicator === "awaitingInput" && prev.indicator !== "awaitingInput"` | `Sound.Request` |
| Completion chime | `!sendPending && fresh && lastCompletedTurn !== null && lastCompletedTurn !== prev.lastCompletedTurn` | `Sound.Done` |
| No chime | none of the above (interrupted/cancelled runs that never advance `lastCompletedTurn`, stale frames where `fresh === false`, a `sendPending` overlay masking a completion) | `null` |
| Freshness window | `fresh = now - session.updatedAt <= SESSION_STALE_MS` (45,000ms — the same constant as the status-dot staleness rule elsewhere in the app; reuse it, do not redefine it) | governs whether a completion is allowed to chime at all |
| Connectivity chime | `degraded = current === "offline" || current === "reconnecting"`; fires only on the `false → true` edge of `degraded` | `Sound.Attention` |
| Connectivity boot quiet period | the first 5000ms (`STARTUP_QUIET`) after the app starts observing connectivity are silent — a cold connect into an already-existing outage must not alert; only the *next* transition alerts | governs `ConnectivityNotificationState.armed` |
| Runtime replacement reset | the connectivity watch is freshly (re)established (e.g. engine switch) | `ConnectivityNotificationState` resets to its default, re-arming the quiet period |
| Attention coalescing | two attention triggers (session error + connectivity) within 250ms (`COALESCE`) | only the first plays |

### 2.4 Call-site behavior (`shell.rs:1677-1766`, described as behavior — port the *behavior*, not GPUI's render-tick structure)

1. Build a `SessionNotificationState` per chat's live session row, plus
   whether that chat currently has a pending-send overlay, plus its
   title.
2. `appFocused` = whether any Roboco window/tab is focused — **app-level**
   focus (any tab, not "is this specific chat's route currently open").
   Web equivalent: `document.hasFocus()` (covers "this tab is the OS
   foreground"); if the app is a PWA/multi-tab in the future this may
   need broadening, but a single-tab check is the correct v1 port.
3. For each chat whose baseline changed since the last check: compute
   `soundSince`; if it returns a sound, gate on the settings-store master
   toggle AND the matching per-kind toggle (`soundCompletionEnabled` for
   `Done`, `soundInputEnabled` for `Request`, `soundAttentionEnabled` for
   `Attention`), and for `Attention` only, additionally gate on the
   250ms coalescing rule; if it should play, call `playSound(sound)`.
   Independently, if `notificationsEnabled && !(notificationsBackgroundOnly
   && appFocused)`, post a banner with `title = chat.title ??
   "New session"` and `body` per the table below, tagged with the chat id.
4. Same two-step (chime gate, then banner gate) for a connectivity
   transition, with `body = "Your device is offline"` (Offline) or
   `"Roboco is trying to reconnect"` (else-degraded), `title =
   "Connection unavailable"`, and no chat id on the banner.
5. A chat's **first appearance** (no prior baseline recorded) seeds the
   baseline silently — never chime or banner on boot or on first-ever
   sight of a session row. This is the single most important rule to get
   right; getting it wrong means every page load/reload chimes for every
   already-completed chat.

### 2.5 Banner click routing

- Every banner posted for a chat event carries the chat id as the
  `Notification`'s `data` payload (the desktop's `CHAT_ID_KEY` constant
  documents the *purpose* to preserve — the literal C-string value is
  irrelevant on web): `new Notification(title, { body, data: { chatId }
  })`.
- On `notificationclick`: focus/open the app window/tab, then navigate to
  `/chat/$chatId` using the router (mirrors desktop's
  `open_notified_chat`: reopen the window if needed, then
  `shell.open_chat(chat_id)`).
- Connectivity banners carry no chat id and, on click, simply focus the
  app (no navigation target).

### 2.6 Text (verbatim)

- Banner titles: `"New session"` (fallback when the chat has no title),
  the chat's own title, `"Connection unavailable"`.
- Banner bodies: `"Run finished"` (Done), `"Waiting on your input"`
  (Request), `"Run failed"` (Attention/session), `"Your device is
  offline"` (Offline), `"Roboco is trying to reconnect"`
  (Reconnecting/other degraded).

### 2.7 Browser `Notification` permission flow

The desktop has no permission model to request (native OS banners are
always available once the app is installed); the web must request
`Notification` permission explicitly. Behavior:
- Never request permission automatically/silently on load — that is
  intrusive and browsers increasingly block auto-prompts anyway. Request
  it the first time `notificationsEnabled` is turned **on** from the
  Notifications settings page (ticket 29's toggle #5), via
  `Notification.requestPermission()`.
- If permission is denied or the API is unavailable, the toggle can still
  be "on" in the settings store (it is a preference, not a permission
  mirror), but `lib/notifications.ts` must silently no-op the banner half
  (chimes still play — sound and notification permission are
  independent) rather than throwing or repeatedly re-prompting.
- If permission was already denied by the browser before this ticket
  ships, do not attempt to re-prompt from anywhere other than the
  settings toggle interaction described above.

### 2.8 Motion

Not visual motion, but "motion of attention": chime playback is
fire-and-forget (an `<audio>` element's `.play()` promise, errors caught
and logged, never surfaced to the user); banner delivery is likewise
best-effort via the `Notification` constructor, failures caught and
logged. Neither blocks or is awaited by the caller.

### 2.9 Data

- Reads: per-chat session rows (indicator, `updatedAt`, `lastCompletedTurn`
  equivalent), chat titles, the chat's pending-send overlay flag (ticket
  05), the connectivity watch's current state (§2.2's new
  `WATCH_CONNECTIVITY`), and the six settings-store booleans (§1).
- Writes: none — this is a read-only reactive layer over existing state.

## 3. Pure logic to port

Every function below is pure (no I/O) and gets a TypeScript unit test
named after its desktop test. Test names are the checklist — do not
invent additional test names for these; use exactly these:

- `SessionNotificationState.soundSince(prev, sendPending) -> Sound | null`
  — the full rule table in §2.3. Tests:
  `interruptedAndExpiredActivityNeverChime`,
  `aRunErrorChimesOnceAndNeverMasqueradesAsCompletion`,
  `ordinaryQueueCompletionsSurviveCoalescedWorkingStates`,
  `pendingSendConsumesCompletionButPreservesInputRequests`,
  `staleCompletionIsConsumedWithoutReplayingOnAHeartbeat`.
- `connectivitySoundSince(current, previous) -> Sound | null` — fires
  `Attention` exactly on a Connected/Disabled → {Offline,Reconnecting}
  transition. Test: `durableConnectivityDegradationChimesOncePerOutage`.
- `ConnectivityNotificationState.update(...)` — the 5000ms
  (`STARTUP_QUIET`) boot grace before arming. Test:
  `connectivityBootOutagesSeedSilentlyThenLaterOutagesAlert`.
- `AttentionSoundGate.shouldPlay(now) -> boolean` — 250ms (`COALESCE`)
  debounce collapsing simultaneous session+connectivity attention
  triggers into one chime. Test:
  `attentionGateCoalescesSessionAndConnectivityWatchCallbacks`.
- `isSwitchActivation(key, isHeld) -> boolean` (only if this ticket's
  Notifications-page toggle interactions need it — otherwise this
  belongs to ticket 29's toggle component, not here; port it in
  whichever file actually implements toggle keyboard activation):
  `!isHeld && (key === "enter" || key === "space")`.

Full desktop test checklist to treat as the acceptance bar (from research
14 §4.4): `interrupted_and_expired_activity_never_chime`,
`a_run_error_chimes_once_and_never_masquerades_as_completion`,
`durable_connectivity_degradation_chimes_once_per_outage`,
`ordinary_queue_completions_survive_coalesced_working_states`,
`pending_send_consumes_completion_but_preserves_input_requests`,
`stale_completion_is_consumed_without_replaying_on_a_heartbeat`,
`connectivity_boot_outages_seed_silently_then_later_outages_alert`,
`attention_gate_coalesces_session_and_connectivity_watch_callbacks`.

## 4. Gaps this ticket closes

| item | kind | desktop value | web value (file:line) | fix |
| --- | --- | --- | --- | --- |
| Session/desktop notification chimes | MISSING | full `sound.rs` decision engine + native playback (`sound.rs`, `shell.rs:1677-1766`) | none (`Notification`/`Audio` unused anywhere in `web/packages/app/src`) | Port the three pure state machines as TS, wire to `Notification` API + `<audio>`, gated behind user permission and the settings toggles |
| `WatchConnectivity` stream | MISSING | drives `AppState.connectivity`, the connection pill, composer honesty, queued-send badges (`state.rs:867-870`, `methods::WATCH_CONNECTIVITY`) | not in `methods.ts`, not called anywhere | Add `WATCH_CONNECTIVITY` to `engine-client/src/methods.ts` and a minimal single-value watch/store |
| `sound.rs`/`notify.rs` settings keys consumption | MISSING (consumption side; ticket 29 builds the toggles) | `settings.rs:567-581` | ticket 29 persists the 6 fields; nothing reads them yet before this ticket | This ticket is the first reader — wire the gates in §2.4 |
| Banner click → chat navigation | MISSING | `notify.rs`'s `CHAT_ID_KEY` tagging + `open_notified_chat` (`lib.rs:241-262`) | none | Add via `Notification`'s `data`/`tag` + `notificationclick` → router navigate |
| `ROBOCO_DISABLE_SOUND`/`ROBOCO_DISABLE_NOTIFICATIONS` env equivalents | MISSING | `sound.rs:19`, `notify.rs:27` | none | Add a query-flag or dev-only `localStorage` equivalent per §2.2 |

## 5. Do not

- Do not port any native OS delivery mechanics: `NSUserNotificationCenter`/
  `osascript` (macOS), `notify-send` (Linux), `afplay`/PowerShell
  `Media.SoundPlayer`/`paplay`+fallbacks, or the macOS bundle-identity
  adoption hack (`notify.rs`'s `identity` module). These have no browser
  equivalent; only the WAV bytes and the decision logic are shared.
- Do not port the Appshot capture-confirmation chime (`SOUND_APPSHOT`,
  `play_appshot()`) — Appshots is desktop/Linux-only and has no web
  surface at all.
- Do not build the Notifications settings page's toggles here — that is
  ticket 29; this ticket only *reads* the six fields it persists.
- Do not build `WatchTransfers` or the upload-progress percent clamp —
  those are a different research-14 gap (composer/queue surfaces), not
  this ticket's concern, even though they are watched via the same
  registry machinery `WatchConnectivity` uses.
- Do not auto-prompt for `Notification` permission on page load — only
  prompt from the explicit settings-toggle interaction described in
  §2.7.
- Do not build the Fleet/multi-engine registry — ticket 31. This ticket's
  connectivity watch targets the single active engine, same as every
  other watch today.

## 6. Acceptance

- [ ] The three WAV assets exist at `web/packages/app/public/sounds/` and
      are byte-identical to `crates/ui/assets/sounds/{done,request,attention}.wav`.
- [ ] A chat transitioning to a completed run plays `Sound.Done` exactly
      once, only when `soundEnabled && soundCompletionEnabled`, only
      while fresh (within 45s), and never while a send is pending for
      that chat.
- [ ] A chat transitioning to `awaitingInput` plays `Sound.Request`
      exactly once, gated on `soundEnabled && soundInputEnabled`.
- [ ] A chat transitioning to `errored`, or the connection transitioning
      into offline/reconnecting, plays `Sound.Attention`, gated on
      `soundEnabled && soundAttentionEnabled`; two such triggers within
      250ms produce exactly one chime.
- [ ] No chime or banner fires on first page load for chats already in a
      completed/errored/awaiting state before the app finished
      connecting.
- [ ] With `notificationsEnabled` on and the app backgrounded (or
      `notificationsBackgroundOnly` off), a completion/request/error
      event posts a `Notification` with the correct title/body from
      §2.6; clicking it focuses the app and navigates to that chat.
- [ ] A connectivity degradation posts a `Notification` titled
      "Connection unavailable" with no chat-navigation target on click.
- [ ] Turning on the Notifications settings toggle triggers exactly one
      `Notification.requestPermission()` call, not a repeat prompt on
      every toggle or every reload.
- [ ] Unit tests (exact names): `interruptedAndExpiredActivityNeverChime`,
      `aRunErrorChimesOnceAndNeverMasqueradesAsCompletion`,
      `durableConnectivityDegradationChimesOncePerOutage`,
      `ordinaryQueueCompletionsSurviveCoalescedWorkingStates`,
      `pendingSendConsumesCompletionButPreservesInputRequests`,
      `staleCompletionIsConsumedWithoutReplayingOnAHeartbeat`,
      `connectivityBootOutagesSeedSilentlyThenLaterOutagesAlert`,
      `attentionGateCoalescesSessionAndConnectivityWatchCallbacks` — all
      in `lib/notifications.ts`'s test file.
- [ ] Screenshot/recording pair not applicable in the usual sense (no
      persistent visual state); instead capture: (a) a browser
      `Notification` banner rendered for a completed run, matching the
      desktop's `notify.rs` banner text; (b) the Notifications settings
      page (from ticket 29) with all six toggles visible for reference.
- [ ] `pnpm -r build` green; package vitest green.
- [ ] No new literal hex/px where a `--rb-*` token exists (this ticket
      has minimal CSS surface, but any banner-adjacent UI must still
      comply).

## Comments

(empty; appended during implementation)
