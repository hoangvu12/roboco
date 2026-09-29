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

## Comments

**Branch:** `wave3/07-side-chats-engine` → merged `Merge wave3/07`. Commit `47b70705` (64 files, +1537/−15): RunRequest.mcp + McpServer (wiregen fresh, TS regen); Chat.parent_chat_id; MessagePart::Fork seam; ForkSideChat RPC (sibling forks, idempotent, durable persist_fork); createChat parent linkage; fork-history bootstrap (Fresh/Continued provider session, warm on dispatch/steer, orphan owes); 6 side-chat tests (mcp assertions stripped → `mcp == None`, owed to 08). Post-merge re-verified: check clean, 9/9, wiregen fresh, pnpm -r build pass.

**Excluded (recorded in commit body):** upstream's interleaved steering/queue-order overhaul (~70% of doc_host/sessions diff: StepBoundary flips, steered_rows, command_drain_lock, defers_to_turn_end, mailbox reserve backpressure) — depends on harness capabilities not ported this wave; Roboco keeps wave-2 turn-boundary behavior. **Deferred to a future wave's consideration** (not dropped). Also: no forwardable() (no relay surface), no cross-device propagation (ADR 0004), mcp stamping → 08, ui/harness/mcp-crate → 08/09.

**For 08/09 (see implementer report):** stamping via Inner::roboco_mcp + set_ipc_port before retry_request; env ROBOCO_IPC_PORT/CHAT_ID/DEVICE_ID, name "roboco", args ["mcp"]; transcript.rs Fork arm is a no-op until 09; children listing = filter read_chats() by parent_chat_id; createChat takes parentChatId; FORK_SIDE_CHAT params {chatId, sourceChatId, parentChatId?}.

**Pre-existing failure noted:** `previews::preview_watch_follows_the_session_checkout_and_owning_device` fails at base 601db2af (verified via stash) — environmental/pre-existing, not this branch. Separate triage.
