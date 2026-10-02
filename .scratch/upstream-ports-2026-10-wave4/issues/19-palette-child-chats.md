# 19 — Command palette: exclude child chats

**What to build:** Child chats (agent-spawned side chats) no longer appear
as separate palette results — only their parents do.

**Blocked by:** None. (Side chats + child-chat concept shipped in wave 3.)

**Status:** ready-for-agent

**Upstream SHAs:** `c74978ab` (#651) — 1 file,
`crates/ui/src/shell/command_palette.rs`. Web parity:
`web/packages/app/src/components/command-palette.tsx` — verify the same
exclusion and extend only if a gap exists.

**Verification budget:** `cargo check -p roboco-ui -j 3`; targeted nextest
command_palette tests; web `pnpm -r build` if touched.

- [ ] Child chats excluded from palette results (desktop)
- [ ] Web gap check recorded
- [ ] Tests green
- [ ] Port commit records upstream SHA

## Comments
