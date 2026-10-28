# 05 — Final verification: an agent on the PC runs chats on Linux SG and the Laptop

**What to build:** Evidence, recorded as comments.

**Automated:** `cargo check --workspace --all-targets`; the engine rooms suites, the MCP suites and the completion-notices suite; PR CI fully green.

**Manual (ready-for-human: the user's machines, after fleet merge)**
- From a chat on the PC, an agent calls `list_devices`: PC, Laptop, Linux SG and Linux US are listed with their online state.
- It creates a side chat on Linux SG and one on the Laptop (`create_chats`, `wait: false`). Both run on those machines. Both completion notices reach the PC chat, and `read_chat` returns their results.
- An offline member gives a fast, plain error.
- Each member's session list shows the "(fleet)" engine sessions. Revoking one makes the next call re-mint it or fail plainly. A machine that left the fleet is unreachable.

**Extra cases from the research:**
- an offline member inside a `create_chats` batch (the others succeed);
- an engine restart in the middle of a forwarded stream;
- a revoked fleet session re-minting;
- a member reachable over both LAN and Tailcat listed **once**;
- a clock-skewed member;
- no duplicate notice after the parent already ran `read_chat`;
- a local process without the IPC token refused.

Over Tailcat, check the per-method deadlines against real latency.

**Blocked by:** 01–04

**Status:** ready-for-human

**Parent:** `.scratch/fleet-agents/spec.md`

- [ ] Automated checks green
- [ ] Cross-machine create, drive and notices work on the user's machines
- [ ] The offline, revoke and left-machine cases behave as above

## Comments
