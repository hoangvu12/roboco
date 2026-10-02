# 17 — Explorer: running subagents first, longest-running on top

**What to build:** A subagent that started long ago used to sink below
everything spawned since, so the still-running one was the hardest to
find. Running subagents now lead the Subagents list, longest-running
first (which keeps the group still as new ones join at its foot); done and
failed ones follow, most recently updated first as before.

**Blocked by:** None.

**Status:** ready-for-agent

**Upstream SHAs:** `f843f1ce` (#638) — 1 file,
`crates/ui/src/files/sections.rs` (Subagents section, ported in wave 3).
**Web parity (deliverable):** `web/packages/app/src/lib/explorer-sections.ts`
builds the same Subagents/Chats sections — port the running-first,
longest-running-top ordering there.

**Verification budget:** `cargo check -p roboco-ui -j 3`; targeted nextest
sections tests; web `pnpm -r build`.

- [ ] Running subagents first, longest-running top; settled ordering
      unchanged (desktop + web)
- [ ] Tests green
- [ ] Port commit records upstream SHA

## Comments
