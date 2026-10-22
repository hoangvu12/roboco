# 02 — MCP bridge on two worker threads (upstream #843, `50e33711a`)

**What to build:** Port `50e33711a` (#843): the `roboco mcp` bridge runs
its tokio runtime as `new_multi_thread().worker_threads(2)` instead of
`Runtime::new()` — one bridge per agent session relaying stdio ↔ engine
IPC; two workers keep bursts of parallel tool calls (large replies to
encode) near per-core speed without a worker per core in every agent's
bridge.

**Why now:** nine lines in `apps/roboco/src/main.rs`; verified this run
that roboco's `Some(Command::Mcp)` block (main.rs:258) matches upstream's
pre-change shape exactly, so the hunk ports verbatim after the rename.

**Steps:**
1. `git show 50e33711a` — single hunk in `apps/zeron/src/main.rs`.
2. Apply to `apps/roboco/src/main.rs` at the `Some(Command::Mcp)` arm,
   keeping the comment's intent.

**Excluded upstream hunks:** none.

**Blocked by:** None.

**Status:** ready-for-agent

**Verification budget:** `cargo check -p roboco` (app binary). Source:
`.scratch/upstream-drift/2026-10-22.md` § quick ports.

- [ ] Worker-threads hunk ported with its comment
- [ ] Commit records upstream SHA `50e33711a` (#843)

## Comments
