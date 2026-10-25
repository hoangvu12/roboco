# 05 — Claude logins that stay signed in (upstream #974, `dd0c36fa3`) — WIRE

**What to build:** Port `dd0c36fa3`: serialized Claude token refresh (new
`harness/src/claude/refresh_lock.rs`) so a sign-in survives; sturdier agent
CLI updates (engine `harness_updates.rs` +419, `executable.rs` +143); engine
`agent_accounts.rs` +445; npm_repair follow-on (+48); proto `agent.rs` +15;
ui `settings/harnesses.rs` + `shell/harness_updates.rs`.

**Port note:** engine-local agent accounts; adapt any account-broker hunk
out. **Wire change — run `cargo run -p wiregen -- --check`.**

**Excluded upstream hunks:** cloud/client hunks if any ride the account
surface.

**Blocked by:** 03 (npm_repair substrate).

**Status:** ready-for-agent

**Verification budget:** `cargo check -p roboco-engine -p roboco-harness`,
engine + harness account tests, wiregen. Source:
`.scratch/upstream-drift/2026-10-25.md` § A5.

- [ ] Refresh lock + CLI update hardening + wire types ported
- [ ] wiregen fresh
- [ ] Commit records upstream SHA `dd0c36fa3` (#974)

## Comments
