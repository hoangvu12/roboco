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

**Status:** ready-for-human

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

- [x] `kind` argument: `chat` / `side` / legacy default, flows into
      `create_chats` requests too
- [x] Standalone chats land in the main Sessions list (parentless)
- [x] Guards intact: side chats cannot create chats; no child-of-side
- [x] `wait` id-based reply matching
- [x] Engine-local decision recorded for the `targetDeviceId` hunks
- [x] Tests green (tools + device_routing + smoke example builds) — written
      and reviewed; execution deferred to the wave-final batched pass (user
      directive). The smoke example builds under the lane's `cargo check`.
- [x] Port commit records the upstream SHA + exclusions

## Comments

- Ported `01832f2c` (#706) by intent into `crates/roboco-mcp`: the `kind`
  argument (`ChatKind` chat/side) on `create_chat` and every `create_chats`
  request — `chat` forbids a parent and writes no `parentChatId` (row lands in
  the main Sessions list); `side` requires an explicit parent or the origin;
  omitted keeps the legacy default (side when invoked from a chat, standalone
  otherwise, e.g. a bare `roboco mcp`). All guards run before any write;
  child-of-side and side-origin guards unchanged. The result reports the
  effective `kind`.
- Id-based reply matching: `send_message` snapshots the transcript's message
  ids BEFORE the send (`TurnStart`); `await_turn` returns assistant replies
  not in that baseline and polls until one is non-streaming or the same
  deadline expires (a completed session with no new reply → `timedOut`, never
  an old response). `pending_turns` remembers each successful send
  (`PendingTurn`: baseline session row + id baseline) so `wait_for_turn` after
  `wait:false` waits for THAT send even before a session row appears (new
  chats included); a completed wait retires its entry unless a newer send
  superseded it. `now_millis` is now test-only; `create_chat`'s model
  validation fails hard on catalog errors (the `let Ok(models)` swallow and
  the empty-catalog→ClaudeCode default-harness fallback are gone).
- `resolve_target` ported to `roboco.rs` (device-first, project within the
  device, local default) plus the `resolve_space_in` hardening: id-only exact
  match, exact-path priority over display-name over suffix, ambiguity errors
  listing ids AND devices.
- jsonrpc `INSTRUCTIONS` and `docs/mcp.md` gained the kind semantics, the
  discovery/launch recipes, and the wait semantics sections (rebranded,
  engine-local wording).
- New `crates/engine/examples/mcp_standalone_smoke.rs` (1:1 upstream port:
  temp profile, scripted Codex adapter `smoke-1`, origin coordinator,
  `serve_stdio`) and new `crates/engine/tests/device_routing.rs`.
- **Engine-local decision (the `targetDeviceId` hunks):** upstream routes
  `ListHarnesses`/`ListModels`/`WatchDocMessages` with `targetDeviceId` to a
  *remote* engine (synced spaces + device relays — removed code, ADR 0004).
  Our `EngineRpc` fails closed on any target that is not this engine
  ("requests must use its own connection"), and `WorkspaceHost` boot retains
  only this device's rows, so `WatchDevices` reports exactly one device.
  Decision: TRIMMED — no `harnesses_on`/`models_on`/`transcript_on` variants,
  and `roboco-mcp` never sends `targetDeviceId`; the `device` tool arguments
  are validated through `resolve_device_id` (foreign ids fail "no device
  matches"), catalog validation is the local one with device-tagged error
  chains, and `resolve_target`'s host scoping stays as a guard (a project row
  naming another device is rejected, not re-hosted). The fail-closed half of
  the routing surface is pinned by the new
  `target_device_ids_must_name_this_engine` engine test; the two-engine
  `mcp_standalone_session_executes_on_the_selected_device` machinery (device
  rooms, relays, `LinkCache`) is upstream-only and excluded.
- Exclusions: upstream's remote-catalog test machinery in `tools.rs`
  (`remote` branch on `targetDeviceId`, `beta_remote`, `reads` assertions,
  `remote_send_selects_the_remote_default_harness`) and the
  `crates/engine/tests/device_routing.rs` relay/registry-sync pre-existing
  body (~1900 lines of upstream-only machinery) — our file is new and carries
  the engine-local equivalents instead.
- Verification: `cargo check -j 3 -p roboco -p roboco-engine --examples` (the
  lane's single check; 2m13s, clean — warnings are pre-existing dead-code in
  untouched engine files) and `rustfmt --edition 2024 --check` over the five
  touched Rust files. `bash scripts/test-linux-desktop-entry.sh` is ticket
  28's. Test execution (roboco-mcp tools tests, device_routing) deferred to
  the wave-final batched pass (user directive).

- Wave-final batched verification (2026-10-03, merged main `cf94f415`): one
  batched pass over all lanes — ui lib 1521/1521; engine 529/530 (the one
  failure is the documented pre-existing
  `previews::preview_watch_follows_the_session_checkout_and_owning_device`
  baseline); harness 504/509 (the five failures are the documented
  environmental `#!/usr/bin/python3` fixture shebang and uid-1001
  user-database quirks; CI runs them); mcp 26/26; voice 18/18; theme 31/31;
  `wiregen --check` and `roboco-theme-export --check` fresh; web `pnpm -r
  build` green, app vitest 2122/2122, engine-client vitest green. The
  deferred test-execution criterion is demonstrated; closed by the
  wave-final pass.
