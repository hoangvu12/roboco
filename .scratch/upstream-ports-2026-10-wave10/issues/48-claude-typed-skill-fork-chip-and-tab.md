# 48 — Claude: a typed `/skill` that runs in a fork gets a chip and a tab (#1043)

**Lane:** 4a Features: engine + agents. **Effort:** M.

**What to build:** A typed `/skill` that Claude runs in a fork shows
nothing until it ends. Port upstream's handling:
- the typed-fork chip from `task_started` without a tool id;
- `forked-command-<name>` routing in `claude/normalize.rs`, so the fork
  gets its own subagent tab and lanes row.

Add `crates/engine/tests/claude_subagents.rs` and the `fake-claude.sh`
additions.

**Upstream:** `95b294658` (#1043).

**Roboco files:** `crates/harness/src/claude/normalize.rs`,
`crates/engine/tests/claude_subagents.rs` (new),
`crates/harness/tests/fixtures/fake-claude.sh`.

**Excluded:** none expected.

**Proto change:** no (beyond 47's).

**Blocked by:** 47.

**Status:** ready-for-agent

**Source:** `part-07.md` `95b294658`.

- [ ] A typed `/skill` fork appears as a chip and opens a tab while it runs (engine test)
- [ ] Commit records `95b294658`

## Comments
