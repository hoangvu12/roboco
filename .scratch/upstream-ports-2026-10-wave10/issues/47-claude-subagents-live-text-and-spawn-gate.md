# 47 — Claude subagents: live text and thinking, spawn gate (#1042, carries #1041) — PROTO

**Lane:** 4a Features: engine + agents. **Effort:** M.

**What to build:** A foreground Claude subagent's tab shows only tool
calls, with no text or thinking. The ● N badge can count spawns the
lanes card never lists. Port:
- `CLAUDE_CODE_FORWARD_SUBAGENT_TEXT=1` in the Claude run env;
  forwarded text and tagged thinking become subagent text/reasoning.
- The `is_chat_spawn` spawn gate in `engine/sessions.rs`: sinks only for
  this chat's spawn chips (#1041). This fixes a ● badge over an empty
  lanes card.
- A forked Skill becomes an `Agent: /name` spawn.
- Reconcile with Roboco's own background-agent logic in
  `claude/normalize.rs` (`:313-369`, `:598`). It stays authoritative for
  background agents. Port by intent; don't replace the file.
- Check open draft PR #952 (a foreground subagent's opening prompt
  doubled on CLI ≥ 2.1.296, `normalize.rs:800-819` + `:913-921`) against a
  live capture while here. Fix it if it reproduces.

**Upstream:** `978e8dacb` (#1042, with #1041's code): `doc/schema.rs`,
`engine/sessions.rs`, `harness/claude/{mod,normalize,wire}.rs`,
`proto/agent.rs`.

**Roboco files:** `crates/harness/src/claude/{mod,normalize,wire}.rs`,
`crates/engine/src/sessions.rs`, `crates/doc/src/schema.rs`,
`crates/proto/src/agent.rs`, `crates/harness/tests/claude.rs`,
`fixtures/fake-claude.sh`.

**Excluded:** cloud/edge hunks if any.

**Proto change:** yes (additive `proto/agent.rs` + doc schema).

**Blocked by:** 11, 18 (Claude files), 16 (`sessions.rs`). **Then:**
48 → 49.

**Status:** ready-for-agent

**Source:** `2026-10-28.md` §B Claude chain; `part-07.md` `978e8dacb`;
`part-prs-A.md` #952.

- [ ] A foreground subagent's tab streams text and thinking (fixture test)
- [ ] The ● N badge counts only this chat's spawn chips (engine test)
- [ ] A forked Skill shows as `Agent: /name`
- [ ] Roboco's background-agent tests still green
- [ ] #952 checked against a live capture: fixed or recorded as not reproducing
- [ ] Commit records `978e8dacb` (+#1041 code)

## Comments
