# 03 — Security: per-start IPC bearer token; sign-in links limited to https/loopback (A40, A9, A10)

**Lane:** 1a Fixes: crashes + security. **Effort:** M.

**What to build:**
1. **A40: localhost IPC requires a secret.** Today any local process,
   or another user on a shared Linux box, can drive the engine over
   `ws://127.0.0.1:{port}`: create chats, run agents, read transcripts.
   Roboco only refuses handshakes that carry an `Origin` header
   (`crates/rpc/src/server.rs:163-191`, `reject_cross_origin`). Port
   upstream's token:
   - On every start the engine mints a 32-byte token and writes it to
     `{data_dir}/ipc-token` (owner-only, atomic replace).
   - Handshakes without `Authorization: Bearer <token>` are refused.
     Keep the Origin rejection too.
   - Clients resolve the token from `ROBOCO_IPC_TOKEN`, then
     `ROBOCO_IPC_TOKEN_FILE`, then the file. The engine hands
     `ROBOCO_IPC_TOKEN_FILE` to the `roboco mcp` servers it injects
     into agents.
   - Every local IPC client is updated: the desktop client, `roboco
     mcp`, `roboco status`, the CLI pairing and Tailcat helpers, and the
     engine/rpc test fixtures that dial IPC. Paired remote clients
     (Android, fleet) use the remote listener and its Bearer auth, so
     they're unaffected.
   - Windows: the token file gets an owner-only ACL. Check how
     upstream's `ipc_auth.rs` does it and match it.
2. **A9: sign-in links.** The desktop opens any URL an engine sends,
   including one from a paired remote. Port
   `is_openable_login_url`/`open_login_url` (https, or http to loopback
   only) at every engine-supplied `cx.open_url` call site:
   `settings/accounts.rs:932,1527`, `account_usage.rs:332,825` and
   `settings/harnesses.rs:794`. Grep for any other call site.
3. **A10: Antigravity sign-in on Linux.** The Accounts page ignores
   `AgentLoginPoll.url` (`accounts.rs:1019-1075`), so a URL that arrives
   on a later poll never opens. Open it, through the same guard.

**Upstream:** `cc7da5c2a` (#966) IPC slice: `crates/rpc/src/ipc_auth.rs`,
`server.rs:139-191` (`serve_ws_listener(.., token)`, `bearer_matches`),
`client.rs` `connect_ipc`/`dial`. `b782d0437` (#449) sign-in slices.

**Roboco files:** `crates/rpc/src/{server,client}.rs` (new
`ipc_auth.rs`), engine startup (`crates/engine/src/lib.rs`), the mcp
injection env (`crates/engine/src/sessions.rs`, around `:3062`),
`crates/roboco-mcp`, `apps/roboco` (`status`, pairing, `tailcat_setup.rs`),
desktop engine client, `crates/ui/src/settings/{accounts,harnesses}.rs`,
`crates/ui/src/account_usage.rs`.

**Excluded upstream hunks:** cloud auth/WorkOS/edge. Web fixtures only
if `web/` still exists when this lands (Decision 1).

**Proto change:** no (handshake auth in `crates/rpc`; no wire type).

**Blocked by:** 01. **Blocks:** 44 (the managed background engine
connects to the token-protected IPC).

**Status:** ready-for-agent

**Source:** `2026-10-28.md` A40, A9, A10;
`pass2-features-desktop.md` N2; `part-03.md` `b782d0437`.

- [ ] An IPC handshake with no token, or the wrong one, is refused (rpc test); Origin is still refused
- [ ] The token rotates on every engine start; the file is 0600 (Unix) or owner-only (Windows)
- [ ] An agent's injected `roboco mcp` connects via `ROBOCO_IPC_TOKEN_FILE` (engine e2e)
- [ ] Desktop, `roboco status`, pairing and Tailcat helpers still connect
- [ ] URL guard tests: `javascript:`, `file:` and `http://remote` refused; `https://…` and `http://127.0.0.1:…` allowed
- [ ] Antigravity's later-poll URL opens (test on the Accounts page model)
- [ ] Commit records `cc7da5c2a` (IPC slice) and `b782d0437` (sign-in slices)

## Comments
