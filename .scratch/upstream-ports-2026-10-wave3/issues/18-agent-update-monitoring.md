# 18 — Agent update monitoring and controls

**What to build:** Device-local agent-CLI update lifecycle: periodic
version checks for installed agent CLIs with update decisions, update
leases/locking (no duplicate in-flight updates per harness — extends the
wave-2 `Installations` guard pattern), download+install reusing the
harness install methods, notification surface, and Settings controls
(per-agent enable/suppress). The update card in the shell matches the
Settings material. Per-device semantics only: upstream's cross-device
propagation maps onto Roboco's existing per-engine routing; nothing
forwards.

**Blocked by:** 09 (touches side-chat-adjacent shell code; run after 14).

**Status:** ready-for-agent

**Upstream SHAs:** `35a9139a` (#389) — all 57 files are desktop crates:
`crates/engine/src/harness_updates.rs` (new, 3054 lines — read in the
mirror worktree and carry the architecture, not verbatim), `registry.rs`
(319+), `rpc.rs` (156+), `sessions.rs`, `doc_host.rs`, `terminals.rs`,
`titles.rs`, `model_catalogs.rs`, `instance_lock.rs`, `chat_persistence.rs`,
tests (e2e 245+, message_queue, rich_composer_delivery, fixtures),
`crates/proto/src/agent.rs` (172+), `crates/rpc/src/lib.rs` (58+),
`crates/harness` trait bits (claude/codex/cursor/opencode/lib + tests +
fixtures incl. `fake-codex.sh`), `crates/ui/src/shell/harness_updates.rs`
(new, 1044), `settings/harnesses.rs` (499+ — carry into Roboco's wave-2
settings grammar), `settings/notifications.rs`, notify/settings/shell/
state/theme/spaces hunks. SKIP `.github/workflows/cursor-compatibility.yml`
(CI policy). Adaptations: no relay/forwardable routing; `edge_enabled`
gates → unconditional; web parity in the same branch (settings-agents
card + watch surface + methods.ts + wiregen regen).

**Verification budget:** `cargo check` engine/harness/ui/proto; targeted
nextest for harness_updates + ported tests; `wiregen --check` + `pnpm -r
build`; web vitest for the new card. This is the biggest ticket — budget
a full implementer session.

- [ ] Engine harness_updates lifecycle (checks, leases, install) ported
- [ ] RPC + proto + wiregen regen
- [ ] UI update card + settings controls (wave-2 grammar)
- [ ] Web parity (settings-agents card)
- [ ] Workflow file skipped + recorded
- [ ] Tests green
- [ ] Port commit records upstream SHA + exclusions
