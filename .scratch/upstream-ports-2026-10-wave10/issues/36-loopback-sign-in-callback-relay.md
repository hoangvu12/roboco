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

**Status:** done (2026-10-28, `wave10/android`) — user decision 3 (fits
ADR 0011); proto change made with ts-rs derives because the web removal
had not merged (see comments)

**Source:** `2026-10-28.md` A42; `pass2-features-desktop.md` N4;
`pass2-features-android.md` N3.

- [x] The engine refuses relays for logins it didn't start for this requester, other ports, and absolute URLs (unit tests)
- [~] Codex login from Android against the fixture engine completes through the relay (no flow test — see comments; the relay path is covered by engine + helper tests)
- [~] A desktop managing a remote fleet engine completes the same login (wired; compiles; needs a two-machine manual check)
- [x] A busy phone port shows the error
- [x] Commit records `cc7da5c2a` (relay slice)

## Comments

2026-10-28 (wave10/android, lane 3): carried `cc7da5c2a` (#966) relay
slice by intent — `crates/rpc` `RELAY_AGENT_LOGIN_CALLBACK`,
`engine/agent_accounts.rs` `relay_callback`, `engine/rpc.rs` handler,
`crates/client/src/providers.rs` `relay_login_callback` (→ a shared
helper). User decision 2026-10-28: a direct paired client relaying the
callback fits ADR 0011 (reachability only, never data).

- **Security model.** Roboco's RPC service is not per-connection, so the
  engine can't see who called. Instead every Roboco client names a
  **fresh random `requesterDeviceId` (UUID v4) per sign-in** in
  `StartAgentLogin`; the engine publishes the login's callback route
  (`CallbackRoutes`, FLOW_TTL) for that value alone, and
  `RelayAgentLoginCallback` must present it. A relay is refused unless:
  the login is live and was started for that requester
  (`CallbackRoutes::port_for`), the target is that login's registered
  port (never a caller-chosen one), and the path is a plain
  `/path?query` (`relayable_callback_path`: no scheme/host, no `//`,
  no whitespace or control characters — so no header injection — ≤ 8 KiB).
  It is replayed as exactly one `GET` with `Connection: close`, 20 s
  timeout, ≤ 64 KiB of reply head read; only `{status, location}` come
  back. Nothing else on the computer is reachable.
- **Proto:** `RelayAgentLoginCallback {loginId, requesterDeviceId, path}`
  and `RelayedLoginCallback {status, location?}` in `entities.rs`, with
  `TS` like their neighbours (the web removal had **not** merged into
  `origin/main` when this landed — `origin/feature/web-removal` doesn't
  exist on the remote), registered in `wiregen` and regenerated
  (`web/packages/proto/src/generated/{RelayAgentLoginCallback,RelayedLoginCallback}.ts`,
  `index.ts`). The web-removal PR drops those with the rest.
- **Shared listener:** `roboco-engine-client::login_relay::listen`
  (upstream's phone listener): binds the login's port on this device's
  loopback, relays the caught request target, answers the browser with a
  "Signed in" page on a 2xx/3xx from the computer (and stops), 502 on a
  refusal (keeps listening), 404 for `/favicon…`; ten-minute wait; dropping
  it stops listening. A busy port is `Port N is in use on this phone, so
  the sign-in can't come back to it.` ("computer" on the desktop).
  Upstream redirects the browser to `zeron://signed-in` to close iOS's
  auth sheet; a Custom Tab can't be closed that way, so it shows a page.
- **Phone:** `roboco-mobile-client` names the requester, starts the
  listener for a Browser login with a `callback_port` (also for a page
  that arrives on a later poll — Antigravity), drops it when the login is
  done, failed, completed or cancelled; a bind failure cancels the login
  on the computer and returns the busy-port message. Ticket 34's interim
  "Finish on the computer" answer is gone: the Android page opens the
  sign-in page and polls as for any other login.
- **Desktop managing a remote engine:** `crates/ui/src/settings/accounts.rs`
  `start_login` names a requester when the page's target isn't the local
  engine and runs the same listener on the registry's runtime; a busy
  port cancels the remote login and shows the message; the relay drops
  with the login. (The other desktop sign-in entry points —
  `account_usage.rs`, `settings/harnesses.rs` — still start logins only
  on their own engine's terms; a follow-up if they gain remote targets.)
- **Not ported:** engine→engine `forward_agent_login` (ADR 0004), the P2P
  callback tunnel, cloud.
- **Tests:** `cargo test -p roboco-engine --lib relay` (path rule; the
  relay refuses another requester, an unknown login and an absolute URL,
  replays one GET to a fake CLI listener and returns its 302 + Location,
  refuses after the route goes) and `a_login_for_another_device…`;
  `-p roboco-engine-client --lib login_relay` (relay + browser answers +
  refusal retry + favicon; busy port message; drop frees the port);
  `-p roboco-mobile-client --test accounts_engine` (a real engine: the
  phone's Claude start on a shared loopback reports the busy port and
  cancels; an unknown attempt expired; the mock refused); JVM
  `AccountSignInTest` (a loopback start opens and polls; a busy port
  alerts the core's message).
- **Manual checks:** a real phone signing Codex (or Claude) in on a
  paired computer end to end, and a desktop signing a remote fleet
  engine's Codex in — neither can run on CI (no provider CLI/network, and
  phone and computer share one loopback in tests).
