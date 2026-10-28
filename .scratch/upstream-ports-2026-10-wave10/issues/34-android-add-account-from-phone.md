# 34 — Android: Add account, i.e. sign an agent in on a computer from the phone (N3)

**Lane:** 3 Android parity. **Effort:** M–L. If it runs long, split it
into FFI (`crates/mobile`) and app UI commits within the ticket.

**What to build:** Today Android's Accounts footer says the opposite of
the iPhone: "To add an account, sign in from Roboco on $deviceName."
(`apps/android/.../accounts/AgentAccountsModel.kt:58`). Port the iPhone
flow:
- One **"Add account" row per login option** (`agent_login_rules`) on
  the Accounts & Usage page, per computer.
- The computer starts the login (`StartAgentLogin`). The phone opens the
  provider page in a browser sheet (Custom Tab).
- **Device-code** logins show "Enter the code ABCD-1234 when asked".
  **Claude's paste fallback** asks "Paste the code". While waiting:
  "Finish signing in in the browser…", and a tap cancels
  (`CancelAgentLogin`). Polling uses `PollAgentLogin`; completion
  `CompleteAgentLogin`.
- FFI: add `device_login_{start,poll,complete,cancel}` and
  `agent_login_rules` to `crates/mobile/src/client_ffi/` (today
  `accounts.rs` has list/activate/forget only). The engine calls exist
  already (`crates/rpc/src/lib.rs:264-267`).
- Loopback-redirect providers (Codex/ChatGPT, Antigravity) can't finish
  until ticket 36's relay lands. Until then, show the honest "finish
  on the computer" message for those options only.

**Upstream:** `cc7da5c2a` (#966) iOS + FFI slice:
`apps/ios/Zeron/Cloud/AgentAccountsViewController.swift:193-205,480-590`
(`DeviceSignIn`; behaviour spec), `crates/mobile/src/client_ffi/providers.rs:90-118,213-281`.

**Roboco files:** `crates/mobile/src/client_ffi/accounts.rs` (or a new
`providers.rs`), `crates/mobile-client` (if a session-scoped call is
needed), `apps/android/.../accounts/*`.

**Excluded:** cloud accounts, billing, API-key cards, "Turn Cloud on…".

**Proto change:** no wire type (existing RPCs; FFI additions).

**Blocked by:** 01. **Blocks:** 35, 36.

**Status:** done (2026-10-28, `wave10/android`) — Kotlin compiles in CI only

**Source:** `pass2-features-android.md` N3; `2026-10-28.md` §C, summary §3.

- [x] FFI exposes the login rules and the start/poll/complete/cancel calls (mobile tests against a fixture engine)
- [~] Add account works for a device-code provider and for Claude's paste flow (JVM model tests; no instrumented flow — see comments)
- [x] Loopback providers show the interim message until 36
- [x] Cancel works mid-flow
- [x] Commit records the `cc7da5c2a` slice

## Comments

2026-10-28 (wave10/android, lane 3): carried `cc7da5c2a` (#966) by intent.

- Rules: upstream moved the sign-in rules to `zeron_proto::providers`;
  Roboco now does the same (`crates/proto/src/providers.rs`: `PROVIDERS`,
  `signs_in`, `login_options`, `switches_accounts`, …, plus upstream's
  `add_account_label`/`add_option_label(harness, …)`). The desktop
  re-exports them from `crates/ui/src/settings/accounts.rs` (its own
  `add_option_label(option, empty)` stays), so there is one copy.
- Core: `roboco-mobile-client` `start/poll/complete/cancel_agent_login`
  over the engine handle (no relay, ADR 0004); FFI
  `client_ffi/providers.rs`: `agent_login_rules(harness)`,
  `device_login_{start,poll,complete,cancel}` with `DeviceLoginInfo`
  (`paste_code`, `callback_port`) / `DeviceLoginPollInfo`. No wire change.
- Finding: Roboco's Claude login is itself loopback (engine-served PKCE
  on `localhost:<random>`; paste only when no port binds), so "loopback"
  is decided from the start reply's `callback_port`, not a static
  provider list. Until 36, a loopback start answers "Finish on the
  computer" and cancels on the computer.
- Android: `AccountSignIn` seam (separate from `AccountsCore`, so the
  read-only surfaces' fakes stay), `AgentAccountsModel` sign-in runner
  (iOS `DeviceSignIn.run`: page in a Custom Tab, 1.5 s polls for 10 min,
  the device-code note shown and copied, "Paste the code" alert →
  `CompleteAgentLogin`, done → re-read, error → "Couldn't sign in"),
  page sections for every offered agent that signs in, one quiet
  "Add account"/"Connect a … account" row per login option, the waiting
  row "Finish signing in in the browser… · Tap to cancel", leaving the
  page cancels. The about footer now says sign-in runs on the computer.
- Tests: `cargo test -p roboco-proto --lib providers`,
  `cargo test -p roboco-mobile --lib client_ffi::providers`,
  `cargo test -p roboco-mobile-client --test accounts_engine`
  (start → poll → cancel against a real in-process engine, Claude's
  engine-served loopback start, offline). JVM: `AccountSignInTest`.
- Deviation: no instrumented flow. A successful device-code or paste
  sign-in needs a real provider CLI (Grok/Hermes) or Anthropic's
  network on the CI runner, and the fixture engine's Claude start is
  loopback (36's case). Recorded as a manual phone check.
