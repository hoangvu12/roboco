# 36 — Loopback sign-in callback relay: finish a Codex/ChatGPT/Antigravity login started from a phone or another fleet desktop (A42) — PROTO

**Lane:** 3 Android parity (engine + mobile). **Effort:** M–L.

**What to build:** A loopback OAuth login started on a computer from
elsewhere can't finish today, because the redirect
(`http://localhost:1455/...` for Codex) lands on the device holding the
browser, not the computer running the agent. That device might be the
phone, or a desktop managing another fleet engine. Port upstream's relay:
- **Host engine:** `RelayAgentLoginCallback`. It accepts only a live
  login **this requester started**, only for **its registered port**,
  and only a **plain path + query** (no scheme/host). It replays the
  callback as one GET to that loopback port, so the CLI or engine
  finishes the login as if the browser were local.
- **Phone side:** while a loopback login is open, listen on the
  provider's loopback port on the phone, capture the callback path, and
  relay it. If the port is busy, say so: "Port {port} is in use on this
  phone…".
- **Desktop managing a remote engine:** the same relay from the desktop
  client, for logins started on a non-local fleet engine.
- After this lands, ticket 34's interim message for loopback providers
  goes away.

**ADR note:** this is ordinary client→engine RPC on a direct pairing (or
the desktop's own client link). It isn't fleet-introduction data
exchange, so ADR 0011's "reachability only" holds. Recorded as a
question for the user in the spec. Security is the acceptance bar:
nothing outside the started login's port and path is reachable.

**Upstream:** `cc7da5c2a` (#966): `crates/rpc/src/lib.rs:243-246`,
`crates/engine/src/agent_accounts.rs:2552-2600` (`relay_callback`),
`crates/engine/src/rpc.rs:4587-4600`, phone side
`crates/client/src/providers.rs:155-215` (`relay_login_callback`),
which maps to `crates/mobile` FFI plus an Android listener.

**Roboco files:** `crates/rpc/src/lib.rs`,
`crates/engine/src/{agent_accounts,rpc}.rs`, `crates/proto` (request
type), `crates/mobile/src/client_ffi/*`, `apps/android/.../accounts/*`,
`crates/ui/src/settings/accounts.rs` (desktop-to-remote case).

**Excluded:** engine→engine login forwarding (`forward_agent_login`;
declined per ADR 0004, a different mechanism), cloud.

**Proto change:** yes (`RelayAgentLoginCallback` request/response).

**Blocked by:** 34.

**Status:** ready-for-agent (security review of the relay checks in the
commit; spec question 3)

**Source:** `2026-10-28.md` A42; `pass2-features-desktop.md` N4;
`pass2-features-android.md` N3.

- [ ] The engine refuses relays for logins it didn't start for this requester, other ports, and absolute URLs (unit tests)
- [ ] Codex login from Android against the fixture engine completes through the relay (flow test with a fake loopback CLI)
- [ ] A desktop managing a remote fleet engine completes the same login
- [ ] A busy phone port shows the error
- [ ] Commit records `cc7da5c2a` (relay slice)

## Comments
