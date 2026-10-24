# Android: Limited banner → Resume on another account

Bring the desktop/web "Limited chat" experience (orchestration v2, tickets
07/09) to the Android app: when a Claude Code (or Codex) chat ends its turn
on a usage limit, the session screen shows an amber Limited banner above
the composer with **Resume** (send the continue prompt), **Resume at
reset** / **Cancel** (the durable continuation) and **Resume on another
account** (the engine-ranked failover picker). The engine already owns
everything; the phone chain drops it today (no `limit` in the mobile
composer state, no failover RPC wrappers, no UI).

## Contract (from the desktop, `crates/ui/src/shell.rs` + web `lib/limited.ts`)

- A Limited chat is an **errored session row carrying `limit`**
  (`SessionLimit { harness, window, resetsAt?, accountId? }`, additive).
  Presentation rule: `effective_indicator == Limited` (proto `view.rs`).
  Cleared when a new turn starts.
- `ListResumeAccountCandidates {chatId}` → ranked `candidates[]` (first =
  recommended; switchable, not API key, not signed out, not still limited,
  not the limited account; lowest usage, org-mates last) + `signedOut[]`
  rows (never switch targets).
- `ResumeOnAnotherAccount {chatId, accountId?}` →
  `Switched{accountId, accountEmail?} | NoCandidates | Failed{reason}` —
  `accountId` omitted takes the recommendation. The engine switches,
  retires the warm runtime and queues the continuation; a failure restores
  the previous account.
- Resume at reset: `Mutate {op:"scheduleResumeAtReset", chatId}` /
  `{op:"cancelScheduledResume", chatId}`.
- Continue prompt: `RESUME_CONTINUE_PROMPT` = "Continue where you left off."
  (one shared spelling).
- Banner strip actions (desktop `limited_strip_actions`): Resume at reset
  (when a reset is still ahead and none armed), Cancel (when armed),
  Resume (always), failover (Claude Code/Codex **and** the candidate list
  offers something), Retry (after a failed candidate load). Notice line:
  "Resumed on …" / "No other account is available right now." / reason.

## Tickets

- `01-mobile-client.md` — the phone-side chain: `ComposerState.limit` +
  `scheduled_resume_fire_at_ms`; failover + arm/cancel wrappers.
- `02-ffi.md` — UniFFI surface: records + methods + the prompt constant.
- `03-kotlin-banner.md` — chrome state, filler, bottom-stack slot, card UI
  and glass-menu picker; registration and screen wiring.
- `04-kotlin-tests.md` — pure derivation tests, filler model tests,
  fixtures, bottom-stack model test.

Out of scope: "Continue on another harness" (Android has no harness
dialog), signed-out rows' "Sign in again" action (no login flow on
Android — the rows render informational), Roborazzi goldens.
