# 06 — Harden every provider (upstream #977, `2e151ca65`)

**What to build:** Port `2e151ca65`: crash and stall detection on every
adapter (acp +935, claude, codex, cursor + shim, opencode, pi); **Windows-safe
process launching** (`harness/src/process/cmd_shim.rs` new, `process/windows/*`,
`windows_process.rs`); `lossy_lines.rs` new; jsonrpc stall handling;
engine run recovery (`sessions.rs` +1305, `run_journal.rs`, new
`tests/run_recovery.rs` +1412); `windows.yml` +5; the fake-process
fixtures for the test suites.

**Port note — reconcile by intent:** roboco just landed session process
hygiene (`03800d7c7`: zombie reaper, crash net, warm-park budget). Diff the
upstream run-recovery journal against roboco's crash net before carrying;
resolve overlaps by behaviour, never by replacing roboco's fresh design.

**Excluded upstream hunks:** none expected — engine + harness only (no
cloud/iOS files in the commit).

**Blocked by:** 03, 05 (npm_repair + executable/refresh substrates).

**Status:** ready-for-agent

**Verification budget:** harness suite (incl. new acp_hardening, fake
fixtures), engine run-recovery tests, `windows.yml` run. Source:
`.scratch/upstream-drift/2026-10-25.md` § A6.

- [ ] Adapter hardening + Windows process safety + run recovery ported
- [ ] Reconciliation with `03800d7c7` recorded in the commit
- [ ] Commit records upstream SHA `2e151ca65` (#977)

## Comments
