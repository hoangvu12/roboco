# 10 — Provider switching between turns (upstream #966 slice, `cc7da5c2a`) — WIRE

**What to build:** Port the portable slice of the #966 mega-commit: the user
can switch a chat's agent account/provider between turns — engine
(`sessions.rs`/`runner.rs`/`deferred.rs`/`rpc.rs` + the new
`tests/provider_switch.rs` / `provider_switch_live.rs`), harness adapter
hunks (acp/claude/codex/cursor/opencode/pi `mod.rs`), and `proto/agent.rs`'s
`HarnessId::ALL`/`as_str`/`from_slug` helpers. **Wire change — wiregen gate.**

**Port note — reconcile by intent:** roboco's orchestration v2 just built
failure-driven account failover (`account_failover.rs`, resume-on-another-
account). Same substrate, different trigger (user-driven vs failure-driven);
port the switch as a sibling of the failover, not a replacement.

**Excluded upstream hunks:** everything else in #966 — durable-object token
vault, Stripe billing, cloud machines, credential vault + `env` broker
map, iOS app files (17), `crates/client` (16), the iOS model picker
(Android parity is ticket 20).

**Blocked by:** 06 (both rework `engine/src/sessions.rs`).

**Status:** ready-for-agent

**Verification budget:** engine provider_switch tests (non-live), wiregen.
Source: `.scratch/upstream-drift/2026-10-25.md` § A9.

- [ ] Between-turns switch + HarnessId helpers ported, reconciled with failover
- [ ] wiregen fresh
- [ ] Commit records upstream SHA `cc7da5c2a` (#966, slice)

## Comments
