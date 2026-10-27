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

**Status:** ready-for-agent

**Source:** `pass2-features-android.md` N3; `2026-10-28.md` §C, summary §3.

- [ ] FFI exposes the login rules and the start/poll/complete/cancel calls (mobile tests against a fixture engine)
- [ ] Add account works for a device-code provider and for Claude's paste flow (instrumented flow test against the `android_fixture` engine)
- [ ] Loopback providers show the interim message until 36
- [ ] Cancel works mid-flow
- [ ] Commit records the `cc7da5c2a` slice

## Comments
