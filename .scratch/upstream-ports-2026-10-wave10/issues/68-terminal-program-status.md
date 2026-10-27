# 68 — Terminal programs report idle/working/blocked to the sidebar and MCP (OSC 7501, #966 slice b) — PROTO

**Lane:** 4b Features: desktop UI (engine + UI). **Effort:** M.

**What to build:** A program running in a Roboco terminal (e.g. an agent
CLI the user started by hand) can report its state with OSC 7501. The
engine parses it (`engine/src/terminals/program_status.rs`, +513;
`proto/program_status.rs`) and publishes `Session.terminal_activity`.
The sidebar shows the state on the chat's row (working spinner, blocked
= needs you). The MCP `get_chat`/`list_chats` expose it. Unreported
programs show nothing new.

**Upstream:** `cc7da5c2a` (#966) slice b.

**Roboco files:** `crates/engine/src/terminals/program_status.rs` (new),
`crates/proto/src/{program_status.rs (new),entities.rs}`,
`crates/ui/src/shell/spaces.rs` (row status),
`crates/roboco-mcp/src/{roboco,tools}.rs`.

**Excluded:** cloud.

**Proto change:** yes (`Session.terminal_activity`, `ProgramStatus`).

**Blocked by:** 66 (terminal engine files).

**Status:** ready-for-agent

**Source:** `2026-10-28.md` §B unticketed #966 slices; `part-06.md` `cc7da5c2a` (b);
`pass2-features-desktop.md` §5.

- [ ] OSC 7501 parse tests (valid, malformed, interleaved with other output)
- [ ] Row status follows the program; MCP shows it (engine + MCP tests)
- [ ] Commit records the `cc7da5c2a` program-status slice

## Comments
