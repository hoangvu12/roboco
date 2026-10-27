# 49 — Claude: fill a typed fork's tab live while the CLI holds it (#1044)

**Lane:** 4a Features: engine + agents. **Effort:** M.

**What to build:** While the CLI holds a typed fork, its tab stays empty
until the fork ends. Port the live tail:
- Extract the Grok ACP `TailReader` (private in
  `crates/harness/src/acp/subagent.rs`) to a shared
  `crates/harness/src/jsonl_tail.rs`. Grok keeps working through it.
- `claude/fork_tail.rs` tails
  `<project>/<session>/subagents/agent-<task>.jsonl` and streams it into
  the fork's tab.
- Windows: tail with shared read access, so the CLI's writes aren't
  blocked (open with `FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE`).
  Handle path separators in the subagents dir.

**Upstream:** `d5a015226` (#1044), plus its three live fixtures.

**Roboco files:** `crates/harness/src/{jsonl_tail.rs,claude/fork_tail.rs}`
(new), `crates/harness/src/acp/subagent.rs`,
`crates/harness/src/claude/mod.rs`.

**Excluded:** none expected.

**Proto change:** no.

**Blocked by:** 48.

**Status:** ready-for-agent

**Source:** `part-07.md` `d5a015226`.

- [ ] The tab fills live from the jsonl (test with a growing fixture file), including on Windows
- [ ] Grok subagent tails unchanged (acp tests green)
- [ ] Commit records `d5a015226`

## Comments
