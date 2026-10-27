# 13 — OpenCode: notice crashes and stalls in seconds, not 31 minutes (A37 OpenCode slice, part 1)

**Lane:** 1c Fixes: agents + orchestration. **Effort:** M.

#977's OpenCode runtime hardening is the largest missing slice (~428/641
added lines absent). It's split in two by concern. This half is about
detection: a crashed or hung server must end the turn quickly with a
reason.

**What to build** (`crates/harness/src/opencode/mod.rs`):
1. **Spec-compliant incremental SSE decoder.** CR, LF and CRLF line
   ends; comment frames; joined `data:` lines; lossy UTF-8 (today
   `:4900-4914` splits on `\n\n` and drops a frame with invalid UTF-8
   whole).
2. **Bus liveness:** `BUS_SILENCE` 45 s, then a 5 s `BUS_PROBE_TIMEOUT`
   probe, then a 150 s wedge budget. Today a hung server leaves the turn
   Working for about 31 minutes.
3. **The reconnect budget resets only after a framed stream**
   (`:4852` resets on any 2xx).
4. **Crash noticed within 500 ms** (`SERVER_POLL`), plus between-turn
   crash handling. Today Roboco learns of a crash only at
   `BusMsg::Disconnected`, after the reconnect budget (`:2482`).
5. **A crash during Stop settles Interrupted** (`:2496-2504`).
6. **The stall clock starts at prompt acceptance,** not at
   `TurnState::begin`.
7. **Stall/retry aborts are bounded at 5 s.**

Port the matching tests from upstream `opencode/tests.rs` (27 fns are
missing; take the ones covering items 1–7) and `tests/opencode.rs`.

**Upstream:** `2e151ca65` (#977) OpenCode slice (`bus_task`/`stream_bus`
around upstream `opencode/mod.rs:4826-4930`, `run_session`).

**Roboco files:** `crates/harness/src/opencode/{mod,tests}.rs`,
`crates/harness/tests/opencode.rs`.

**Excluded:** the opt-in live test (keep `#[ignore]` if taken). The
per-run env map stays out (Roboco has none).

**Proto change:** no.

**Blocked by:** 01. **Then:** 14, 15 (same file, in that order).

**Status:** ready-for-agent

**Source:** `2026-10-28.md` A37 (OpenCode); `pass2-big-1.md` §OpenCode, gaps 1, 1b.

- [ ] SSE decoder tests: CR/CRLF, comments, multi-line data, invalid UTF-8
- [ ] A hung server ends the turn within the wedge budget with a reason (test with a fake that stops writing)
- [ ] A killed server ends the turn within about 1 s; a crash during Stop reads Interrupted (tests)
- [ ] `--lib opencode` and `--test opencode` green, including on Windows
- [ ] Commit records `2e151ca65` (OpenCode detection half)

## Comments
