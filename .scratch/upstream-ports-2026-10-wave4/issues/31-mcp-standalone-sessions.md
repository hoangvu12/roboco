# 31 — MCP standalone sessions (`create_chat` kind)

**What to build:** Port upstream #706: the MCP `create_chat` tool (and
each `create_chats` request — they share the arg struct) gains a `kind`
argument. `"chat"` = standalone session: no parent allowed, lands in the
main Sessions list. `"side"` = child chat: requires an explicit `parent`
or the origin chat. Omitted = legacy default (side when invoked from a
chat, standalone otherwise, e.g. a bare CLI). This closes the
"agents can't spawn standalone chats" gap: our port still hard-wires
parent = origin chat, so every agent-made chat is a side chat. Existing
guards stay: side chats cannot create chats, no child-of-side nesting.
Also from #706: id-based reply matching for `wait`, and an MCP stdio
smoke example.

**Engine-local review flag (decide before porting the resolution
hunks):** #706's remote-project/device resolution rides
`targetDeviceId` params (`harnesses_on`, `models_on`, `transcript_on`,
`resolve_target` in `crates/mcp/src/zeron.rs`) — catalog validation on a
*target host*, written against upstream's synced-spaces machinery. We
are engine-local (ADR 0004) and our `roboco-mcp` already carries
`project`/`device` args from `20fbbab5`. Check what our RPC layer does
with `targetDeviceId` today; likely most of the resolution collapses to
"this device" (the engine is the device) and remote-catalog validation
either trims away or validates locally. Adapt or exclude deliberately;
record the decision in the port commit — same treatment the cloud code
got in `20fbbab5`.

**Blocked by:** None. `crates/roboco-mcp` is untouched by every other
lane — zero file overlap; the new engine test/example files collide with
nothing in `wave4/engine` (all new files).

**Status:** ready-for-agent

**Upstream SHAs:** `01832f2c` (#706) — `crates/mcp/src/tools.rs` (~900
insertions → `crates/roboco-mcp/src/tools.rs`), `crates/mcp/src/zeron.rs`
(137 → `roboco.rs`), `crates/mcp/src/jsonrpc.rs`, `docs/mcp.md` sections
(`kind` semantics, standalone sessions), new
`crates/engine/tests/device_routing.rs` + new
`crates/engine/examples/mcp_standalone_smoke.rs` (both map 1:1 — our
`crates/engine/examples/` exists), `crates/engine/Cargo.toml` +
`Cargo.lock`. Desktop-only: no web surface (no edge/ hunks). Source: the
2026-10-02 drift review — #706 merged 20:02 UTC, ~4h after the wave's
closing fetch at 17:34 UTC, so it was never a wave-window candidate
(`.scratch/upstream-drift/2026-10-02.md`).

**Verification budget:** `cargo check -p roboco-mcp -p roboco-engine -j
3`; nextest the roboco-mcp tools tests (inline `mod tests` in tools.rs —
existing style: `create_chat_writes_the_row_and_validates_model`) + the
ported `device_routing` engine test; build the smoke example
(`cargo check -p roboco-engine --examples`); `cargo check -p roboco`.

- [ ] `kind` argument: `chat` / `side` / legacy default, flows into
      `create_chats` requests too
- [ ] Standalone chats land in the main Sessions list (parentless)
- [ ] Guards intact: side chats cannot create chats; no child-of-side
- [ ] `wait` id-based reply matching
- [ ] Engine-local decision recorded for the `targetDeviceId` hunks
- [ ] Tests green (tools + device_routing + smoke example builds)
- [ ] Port commit records the upstream SHA + exclusions

## Comments
