# 07 — Side chats: engine, doc, proto, rpc foundation

**What to build:** The engine-side machinery of upstream #498: side-chat
sessions (chats spawned under a parent, listed as its children), forked
side chats (`ForkSideChat` with optional parent so a side chat's own fork
lands as a sibling), the `fork` seam part appended after copied history
(rendered "This chat was forked from <title>"), registry child listing
(forks + agent-spawned side chats), and the `RunRequest.mcp` proto field
(`McpServer` type, additive + serde-default) that ticket 08 will stamp.

**Blocked by:** None.

**Status:** ready-for-agent

**Upstream SHAs:** `731697b6` — engine/doc/proto/rpc subset:
`crates/engine/src/{doc_host,lib,registry,rpc,sessions,titles}.rs`,
`crates/doc/src/{commands,parts,registry,schema,workspace}.rs` + doc tests,
`crates/proto/src/{agent,entities}.rs`, `crates/rpc/src/lib.rs`, engine
`tests/side_chats.rs` (new) + the side-chat/fork hunks of the other engine
tests. Skip `apps/ios/**` hunks; skip the `RunRequest.mcp` STAMPING (engine
lib wiring that fills the field) — that lands in ticket 08 with the server;
only the proto field + plumbing land here so wiregen stays fresh.
Note: upstream #498 includes follow-up fixups (surface-host opening,
paging "Show N more", composer dock frame) that land with the UI ticket.

**Verification budget:** `cargo check -p roboco-engine -j 3` (doc/proto too);
targeted nextest: `test(side_chat) or test(fork)` + the ported test files;
`cargo run --locked -p wiregen -- --check` (RunRequest is a wire type — the
new field must appear in generated TS) + `pnpm -r build` typecheck.

- [ ] Side-chat sessions + registry children (engine)
- [ ] ForkSideChat command + fork seam part (doc)
- [ ] RunRequest.mcp proto field, serde-default, wiregen fresh
- [ ] Ported engine tests green
- [ ] Port commit records upstream SHA + exclusions
