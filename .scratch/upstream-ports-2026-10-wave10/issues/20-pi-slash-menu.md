# 20 — Pi chats get a slash menu: commands, skills, built-ins (A43)

**Lane:** 1c Fixes: agents + orchestration. **Effort:** M.

**What to build:** Pi chats have no slash menu today. `crates/harness/src/pi/`
has no `commands`/`skills`, so the trait default (empty,
`harness/src/lib.rs:240-242`) applies. Port upstream's catalog on top of
Roboco's own native Pi driver (kept by the wave-4 #30 decision):
- Query Pi's `get_commands` over the existing RPC wire. It returns Pi's
  commands, skills, prompt templates and extension commands, mapped to
  `commands()` and `skills()`.
- Built-ins: `/compact [instructions]`, `/session` (stats),
  `/name <title>`, `/export [path]` (HTML), `/autocompact on|off`.
- Route invocations through Roboco's `steer`-queue driver like any
  other prompt or control.
- Ticket 17's cache (#666) keeps the menu instant after the first open.

**Upstream:** `df0cd2980` (#630) slice: `crates/harness/src/pi/catalog.rs:80-123`
(`builtin`), `pi/mod.rs:123,333-346,605` (`get_commands`).

**Roboco files:** `crates/harness/src/pi/{mod,wire,normalize}.rs`
(+ a `catalog.rs`), `crates/harness/tests/pi.rs`, the Rust `pi_rpc` peer
fixture (`--features native-fixture`).

**Excluded:** `/steering <mode>` and `/follow-up <mode>`. They change
Pi's global modes, which Roboco's driver owns (wave-4 #30 item 8). The
Pi native-RPC driver rewrite stays declined.

**Proto change:** no.

**Blocked by:** 01. Better after 17 (cached catalogs).

**Status:** ready-for-agent

**Source:** `2026-10-28.md` A43; `pass2-features-desktop.md` N6.

- [ ] A Pi chat's `/` menu lists Pi commands, skills and the five built-ins (pi_rpc peer test)
- [ ] `/compact` and `/name` work end to end (fixture test)
- [ ] `/steering` and `/follow-up` absent, with the reason recorded
- [ ] Commit records `df0cd2980` (catalog slice)

## Comments
