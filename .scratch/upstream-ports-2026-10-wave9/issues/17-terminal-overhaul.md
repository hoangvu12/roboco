# 17 — Terminal: no added latency, predictive echo, host-answered queries (upstream #975 slice, `71033cc96`) — WIRE

**What to build:** Port the local-terminal slice of `71033cc96`: keys are
written the moment they land (one write in flight per tab); PTY output sent
as soon as it is read; TCP_NODELAY on the local IPC socket; **predictive
echo** (`ui/src/terminal/predict.rs` new, +405); platform text input (IME,
dead keys), mouse + focus reporting, DECSCUSR cursors with typing-aware
blink, synchronized output, pixel-smooth scrollback
(`panel.rs` +918, `view.rs` +608, `emulator.rs`); **host-answered terminal
queries** — engine `terminals/replies.rs` new (+184): a grid-only emulator
beside each PTY answers device-attribute/cursor-position queries at once,
`host_replies` keeps viewers silent, resize-on-open fix.
`proto/entities.rs` +5. **Wire change — wiregen gate.**

**Excluded upstream hunks:** **`remote_terminals.rs` (+927) and
`terminals/link.rs` — the WebRTC P2P-to-other-devices slice stays out**
(sync-rooms territory; a roboco remote terminal would ride Tailcat —
separate decision). The `preview/{peer,service}` hunks ride that exclusion;
`docs/PARITY.md`/`ARCHITECTURE.md` hunks adapt.

**Blocked by:** 11 (terminal view work may need the new zui APIs).

**Status:** ready-for-agent

**Verification budget:** terminal ui tests, engine terminal tests,
wiregen. Source: `.scratch/upstream-drift/2026-10-25.md` § B6.

- [ ] Latency + predictive echo + input/scrollback + host replies ported
- [ ] Remote/P2P hunks verifiably absent
- [ ] wiregen fresh
- [ ] Commit records upstream SHA `71033cc96` (#975, local slice)

## Comments
