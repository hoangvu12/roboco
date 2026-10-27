# 10 — `wait_for_turn` reports "interrupted"; stale status isn't progress (A31) — PROTO

**Lane:** 1c Fixes: agents + orchestration. **Effort:** M.

**What to build:** An orchestrating agent can't tell "stopped" from
"done" today. `TurnOutcome` has no `Interrupted`
(`crates/roboco-mcp/src/roboco.rs:97-106`), and an interrupted child
turn either reads as `Completed` (`:557-569`, `saw_working`) or waits
until the timeout. Port upstream's evidence model:
- The engine stamps `Session.last_turn_outcome = {status, finishedAt}`
  when a native turn ends (Completed / Interrupted / Errored) and clears
  it when a new turn starts.
- `wait_for_turn` returns `interrupted` (and `errored`), even when the
  watch coalesced the Working frame away. Repeated interrupts need
  fresh outcome evidence.
- A stale liveness heartbeat (`session_stale`) is not read as progress.
- Roboco's batch tools and the completion notices
  (`crates/engine/src/completion_notices.rs`) report the same outcome
  words, so "interrupted" means the same everywhere.

**Upstream:** `cc7da5c2a` (#966) slice: `crates/proto/src/entities.rs:357,371`
(`NativeTurnOutcome`), `crates/proto/src/view.rs:45` (`session_stale`),
`crates/engine/src/sessions.rs:2109-2120`, `crates/mcp/src/zeron.rs:113-121,565-600`,
tool description `crates/mcp/src/tools.rs:165`. Test
`coalesced_repeated_interrupts_need_fresh_native_outcome_evidence`.

**Roboco files:** `crates/proto/src/{entities,view}.rs`,
`crates/engine/src/sessions.rs`, `crates/engine/src/completion_notices.rs`,
`crates/roboco-mcp/src/{roboco,tools}.rs`, `docs/mcp.md`.

**Excluded:** cloud session fields in the same upstream hunks.

**Proto change:** yes (`Session.last_turn_outcome`,
`NativeTurnOutcome`). Additive with serde defaults. Old clients ignore
it. Check the UniFFI build if `Session` is exposed to mobile.

**Blocked by:** 01. Ticket 16 also edits `sessions.rs`; small overlap.

**Status:** ready-for-agent

**Source:** `2026-10-28.md` A31; `pass2-features-desktop.md` N3;
`pass2-big-2.md` gap 7.

- [ ] An interrupted child turn makes `wait_for_turn` return `interrupted` (MCP test with a coalesced watch)
- [ ] An errored turn returns `errored`; a stale heartbeat doesn't count as progress
- [ ] Completion notices use the same outcome
- [ ] Proto round-trip test for the new field; old-shape decode still works
- [ ] Commit records `cc7da5c2a` (MCP/outcome slice)

## Comments
