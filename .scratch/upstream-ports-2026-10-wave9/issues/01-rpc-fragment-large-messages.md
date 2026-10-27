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

**Status:** ready-for-agent

**Verification budget:** `cargo check -p roboco-rpc` + rpc tests; ui state
tests. Source: `.scratch/upstream-drift/2026-10-25.md` § A1.

- [ ] Fragmented writes + cap + close_reason ported
- [ ] Commit records upstream SHA `acd7c9ba7`

## Comments
