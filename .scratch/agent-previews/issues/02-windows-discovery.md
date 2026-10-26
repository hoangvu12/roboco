# 02 — Windows discovery: TCP table + process env join (roboco-original)

**What to build:** The Windows `listeners()` in `crates/preview/src/discovery.rs` (today it returns nothing). Two joined sources, no new crates in the graph:
- **Listening sockets with owning pid:** `GetExtendedTcpTable` with `TCP_TABLE_OWNER_PID_ALL` for `AF_INET` and `AF_INET6` via `windows-sys` (already pinned in the workspace; types in `Win32::NetworkManagement::IpHelper`). Filter `MIB_TCP_STATE_LISTEN` rows; `dwLocalAddr == 0` means wildcard (loopback-reachable); explicit `127.0.0.1`/`::1` binds are distinguished directly. No elevation needed for same-user enumeration.
- **Process metadata (parent, start_time, cwd, cmd, environ):** promote `sysinfo` (0.31.4, already in the lockfile via the zui stack) to a direct dependency of `roboco-preview` — its Windows implementation provides all six fields (`NtQuerySystemInformation` for the list, `GetProcessTimes` for start time, PEB/`ReadProcessMemory` for cmd/cwd/environ, WOW64-aware). Mind the 0.31→0.39 API drift if the pin ever moves.
- **Degradation semantics:** processes whose PEB can't be read (elevated/other-user) keep pid+parent but lose start_time/cwd/env — origin attribution (ticket 01) still resolves through the nearest *marked, readable* ancestor; start-time-less rows skip the probe-identity memoization and the dial-time `same_process` check degrades to pid-only (document the stale-reuse window). A failed table/process read keeps the previous list (the #1023 durability rule, platform-independent).
- Feed the same `Listener`/`Process` shapes the Linux/macOS scanners produce; the auth-command exclusion, origin attribution, and HTTP gating are shared code and must not grow `#[cfg(target_os = "windows")]` copies.

**Why now:** Windows is roboco's flagship platform; without this the feature is macOS/Linux-only (upstream never shipped it — `a9e6c48b7`'s Windows `listeners()` is a stub).

**Blocked by:** 01 — origin stamping (Windows attribution rides the same `ROBOCO_ORIGIN` marker; landing 02 first would ship attribution-less cwd matching only to redo it).

**Status:** ready-for-agent
**Parent:** `.scratch/agent-previews/spec.md`

**Verification budget:** `cargo test -p roboco-preview --test discovery` (Windows: run on the Windows runner — `windows.yml`; the tests spawn real marked/unmarked loopback listeners), `cargo test -p roboco-engine --test previews` on Windows.

- [ ] A loopback HTTP listener started by a marked child of the engine lists as a preview on Windows; a user-started (unmarked) one in the same project does not
- [ ] Wildcard (`0.0.0.0`/`::`) and explicit-loopback binds both classify as loopback-reachable; non-loopback binds are excluded
- [ ] Elevated/unreadable PEB rows degrade to pid+parent attribution without failing the scan; a failed table read keeps the previous list
- [ ] Windows `roboco-preview` discovery tests are green on `windows.yml` (Linux/macOS behavior unchanged — their tests are the regression gate)
- [ ] No `sysinfo`/`netstat2`-style new crates in the graph; `windows-sys` and `sysinfo` both resolve to already-pinned versions

## Comments

- 2026-10-26 (research): sysinfo 0.31.4 Windows internals verified from the pinned crate source — unopenable processes degrade silently (start_time 0, empty cmd/environ, None cwd); `netstat2` 0.11.2 rejected (not in lock, duplicates windows-sys types, ~14-month-old release). `GetExtendedTcpTable` documents no privilege requirement (Microsoft Learn). Design research: `.scratch/agent-previews` basis is `docs/research/agent-port-forwarding.md`; round-2 web findings were returned by the research agent (sysinfo/netstat2/GetExtendedTcpTable citations) and are summarized in this ticket's verification section.
