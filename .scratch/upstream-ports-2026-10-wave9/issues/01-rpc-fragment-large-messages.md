# 01 — RPC: fragment large messages (upstream `acd7c9ba7`)

**What to build:** Port `acd7c9ba7`: messages over 1 MiB are written as
fragmented WebSocket frames so peers on default limits reassemble them; the
local IPC frame/message cap rises to 512 MiB for unfragmented frames from
older peers; transport errors are logged; `RpcClient::close_reason` is
exposed and shown in the desktop's failure message. Fixes the "engine keeps
stopping" loop when a long chat's transcript watch reply (~18 MiB) exceeded
tungstenite's 16 MiB cap.

**Files:** `crates/rpc/src/{client,lib,server,ws.rs}` (ws.rs new), `crates/ui/src/state.rs`.

**Blocked by:** none.

**Status:** ready-for-human

**Verification budget:** `cargo check -p roboco-rpc` + rpc tests; ui state
tests. Source: `.scratch/upstream-drift/2026-10-25.md` § A1.

- [x] Fragmented writes + cap + close_reason ported
- [x] Commit records upstream SHA `acd7c9ba7`

## Comments

- **2026-10-25 — ported on `wave9/engine`.** Reconciled with Roboco's
  earlier, stronger pump ports (a3c370b5/efa1a075): fragmentation was
  already in place (every write >16 KiB fragments at 4 KiB with interleaved
  pings — stronger than upstream's 1 MiB threshold), so the port carries
  the missing pieces only:
  - `MAX_MESSAGE_BYTES` 256 → 512 MiB (upstream's cap), now applied to the
    server **accept** path too (`accept_hdr_async_with_config` +
    `pump::transport_config()`), so an unfragmented 18 MiB+ frame from a
    peer on tungstenite defaults no longer drops the session.
  - `pump`/`pump_with_timing` now return the failure reason
    (`Exit::Failed(String)`, upstream's `Option<String>` shape);
    `RpcClient::close_reason()` exposed; the dial task logs
    `rpc: lost the connection` with reason+url, the server logs
    `rpc: lost a viewport connection` with the reason.
  - Upstream's `ui/state.rs` five-loss "engine keeps stopping" banner does
    not exist in Roboco (the engine registry reconnects transparently;
    the boot gate only fails with specific bootstrap/assembly messages),
    so the close reason is surfaced through Roboco's equivalent: the
    engine registry's supervise loop (log line + `snapshot.last_error`,
    read by desktop and mobile) in `crates/engine-client/src/registry.rs`.
- **Verification:** `cargo check -p roboco-rpc -p roboco-engine-client` clean;
  `cargo test -p roboco-rpc` 28 passed including the ported
  `websocket_carries_messages_past_the_default_frame_cap` (19 MiB echo over
  the pump + a raw tungstenite-default peer's unfragmented >16 MiB frame
  accepted and answered); `cargo test -p roboco-engine-client` 13 passed
  (incl. `in_process_engine_reconnects_after_its_transport_fails`, which
  exercises the changed supervise path). ui state tests: not run —
  `crates/ui/src/state.rs` is unchanged (no equivalent failure banner in
  Roboco's architecture; noted above); the lane-end workspace check covers
  the compile.
