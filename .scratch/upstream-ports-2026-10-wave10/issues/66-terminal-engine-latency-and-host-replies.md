# 66 — Terminal, engine half: no added latency, host-answered queries, size on open (wave-9 17 part 1, #975) — PROTO

**Lane:** 4b Features: desktop UI (engine files). **Effort:** M.

**What to build:** the engine and transport half of #975's local slice:
- Keys are written the moment they land (one write in flight per tab).
  PTY output is sent as soon as it's read, with 4 ms flood pacing.
- `TCP_NODELAY` on the local IPC socket.
- **Host-answered terminal queries:** a grid-only emulator beside each
  PTY (`engine/src/terminals/replies.rs`, +184) answers device-attribute
  and cursor-position queries at once. `TerminalSession.host_replies`
  keeps viewers silent, so two windows (or a fleet viewer) don't answer
  twice. This matters for fleet remote terminals: agent CLIs don't read
  stray escapes.
- Resize-on-open fix: the size is sent when the terminal opens.
- **Windows:** Roboco has `engine/src/terminals/windows.rs` (ConPTY). Test
  the write path and the reply path on Windows.

**Upstream:** `71033cc96` (#975) engine/proto/rpc hunks.

**Roboco files:** `crates/engine/src/terminals/{mod,replies (new),windows}.rs`,
`crates/engine/src/terminals.rs`, `crates/proto/src/entities.rs`,
`crates/rpc` (socket options).

**Excluded:** **the WebRTC P2P slice:** `remote_terminals.rs` (+927),
`terminals/link.rs`, `preview/{peer,service}` hunks (sync-room
territory; declined). `docs/PARITY.md` / `ARCHITECTURE.md` hunks adapt
or drop.

**Proto change:** yes (`TerminalSession.host_replies`, additive).

**Blocked by:** 57 (pin compat for the shared terminal types), 05 (the
`terminals/windows.rs` cwd fix lands first). **Then:** 67, 68.

**Status:** ready-for-agent

**Source:** wave-9 `issues/17-terminal-overhaul.md`; `part-07.md` `71033cc96`.

- [ ] Key-to-PTY latency has no batching delay (engine test with a timestamped echo)
- [ ] DA/CPR queries are answered once by the host; viewers stay silent (test with two viewers)
- [ ] Size is applied on open (test); ConPTY path verified on Windows
- [ ] P2P files verifiably absent
- [ ] Commit records `71033cc96` (engine half, P2P excluded)

## Comments
