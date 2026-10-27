# 52 — Questions: record what was answered; "close without answering" tells the agent (#1022, part 1: data + harness) — PROTO

**Lane:** 4a Features: engine + agents. **Effort:** M.

**What to build:** the data and harness half of #1022.
- Doc `Input` parts gain `answers`, and `resolve_input(request_id,
  answers)` records them (`crates/doc/src/schema.rs:968` takes no
  answers today; `parts.rs`).
- `proto/agent.rs` `answers: Option<Vec<UserInputAnswer>>`. `proto/view.rs`
  (+270): the resolved-question view model with answer lines and
  "header → Not answered".
- **Decline semantics:** empty answers mean "the user closed without
  answering". The harness tells the agent that the questions went
  unanswered instead of leaving the run blocked (`harness/src/lib.rs`,
  `claude/mod.rs`, `opencode/mod.rs` with its `!answers.is_empty()`
  guard → `/question/{id}/reject` at `mod.rs:3739`). Map it for the
  other harnesses that ask questions (Codex elicitation, ACP permission
  questions, Pi dialogs: cancel).
- `engine` doc_host/rpc/sessions carry the answers. `mcp/transcript.rs`
  renders them in `read_chat`.

**Upstream:** `64a26d359` (#1022), data/engine/harness hunks.

**Roboco files:** `crates/doc/src/{schema,parts}.rs`,
`crates/proto/src/{agent,view}.rs`,
`crates/engine/src/{doc_host,rpc,sessions}.rs`,
`crates/harness/src/{lib,claude/mod,opencode/mod,codex/elicitation,acp/mod,pi/ui}.rs`,
`crates/roboco-mcp/src/transcript.rs`.

**Excluded:** `muse/*`, `edge/*`.

**Proto change:** yes (`answers` on Input parts/agent, `view.rs`).

**Blocked by:** 15 (opencode file), 16 (`sessions.rs`). **Then:** 53.

**Status:** ready-for-agent

**Source:** `2026-10-28.md` §B #1022; `part-07.md` `64a26d359`;
`pass2-big-1.md` (#1022 OpenCode guard).

- [ ] Answers recorded and visible via `read_chat` (engine test)
- [ ] Declining unblocks each question-asking harness, and the agent is told (per-harness fixture tests)
- [ ] Proto round-trip, plus old-shape decode
- [ ] Commit records `64a26d359` (data/harness half)

## Comments
