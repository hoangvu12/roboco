# 09 — Previews: only ports the engine started (upstream #1023, `a9e6c48b7`) — WIRE

**What to build:** Port `a9e6c48b7`: the engine stamps an origin
(`ZERON_ORIGIN` → **`ROBOCO_ORIGIN`**) into everything it launches
(`chat:<id>`, `terminal:<id>`, project actions); preview discovery lists a
listener only when its process (or nearest ancestor) carries a hosted-chat
origin — inherited through nohup/setsid/double-fork, read once per process
identity, scan failures keep the previous list, `opencode serve` excluded.
The session card gains a **Ports card** (ui `shell/session_info.rs` +245).
New `harness/src/agent_ports.rs`; `crates/preview/src/discovery.rs` rework
(+519) + tests; `proto/preview.rs` +19.

**Excluded upstream hunks:** the two `edge/preview-room*` hunks.

**Blocked by:** none.

**Status:** ready-for-agent

**Verification budget:** preview discovery/coordinator tests, engine
previews tests, wiregen. Source: `.scratch/upstream-drift/2026-10-25.md` § A8.

- [ ] Origin stamping + discovery + Ports card ported
- [ ] wiregen fresh
- [ ] Commit records upstream SHA `a9e6c48b7` (#1023)

## Comments
