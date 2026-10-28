# 15 — Agents: final verification on the user's machines

**What to build:** evidence, recorded as comments. (Moved from the deleted `fleet-agents` ticket 05.)

**Automated:** `cargo check --workspace --all-targets`; the engine peer suites, the MCP suites and `completion_notices`; PR CI fully green.

**Manual (the user's machines, after ticket 11):**
- From a chat on the PC, an agent calls `list_devices`: PC, Laptop, Linux SG and Linux US are listed with their online state.
- It creates a side chat on Linux SG and one on the Laptop (`create_chats`, `wait: false`). Both run there, both notices reach the PC chat, and `read_chat` returns their results.
- An offline server gives a fast, plain error, and in a mixed batch the others still succeed.
- Each server's session list shows the peer sessions under the right device names. A removed device can't be reached.
- More cases:
  - an engine restart in the middle of a forwarded stream;
  - a revoked peer session re-minting;
  - a server reachable over both LAN and Tailcat listed **once**;
  - a clock-skewed server;
  - no duplicate notice after `read_chat`;
  - a local process without the IPC token refused.
- Over Tailcat (including the relayed Tokyo path seen on 2026-10-28), check the per-method deadlines against real latency.

**Blocked by:** 11–14

**Status:** ready-for-human

**Parent:** `.scratch/fleet-redesign/spec.md`

- [ ] Automated checks green
- [ ] Cross-machine create, drive and notices work on the user's machines
- [ ] The offline, revoke and removed-device cases behave as above

## Comments
