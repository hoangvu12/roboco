# 10 — Todo panel: a dedicated panel for the agent's checklist

**What to build:** Port upstream `1f7b74a7` (#707) — carried over from
wave 4's deferral (its drift note: "Wave-5 ticket, not a quick add").
The agent's todo/checklist output becomes a dedicated UI panel rather
than prose in the transcript: proto surface (upstream touches
`crates/proto/src/agent.rs`), a doc schema + registry entries
(`crates/doc`), todo extraction normalizers in all six harnesses
(upstream touches acp/claude/codex/cursor/opencode/mock — ours live in
`crates/harness/src/<name>`), the panel itself (upstream adds
`todo_panel.rs` + wiring in composer/transcript/queue),
`docs/todo-panel.md`, and web parity (todo panel components in
`web/packages/app`).

**First step:** `git show 1f7b74a7` in `~/roboco-dev` and build the
real file map — the 2026-10-02 drift note recorded the inventory
(~1.7k lines, 26 files) but not the hunks; the port follows the actual
diff. Map upstream paths per the rebrand (`crates/doc` is ours
un-prefixed; harness names map 1:1).

**Exclusions (recorded):** upstream's **edge web client** hunks — no
`edge/` exists here (ADR 0003); any iOS hunks; any sync-room plumbing
if the diff carries it — todo state is engine-local per ADR 0004.
Web parity means `web/packages/app` components, not edge.

**Wire surface:** the proto/doc surface changes mean wire types
regenerate — at the wave-final pass only (`wiregen --check` +
`pnpm -r build` there), per the no-build directive.

**Blocked by:** None. Biggest ticket alongside 09/11 — budget reading
time for the six normalizers; they share one shape (parse checklist
items → doc rows → panel state).

**Status:** ready-for-agent

**Upstream SHAs:** `1f7b74a7` (#707) — proto `agent.rs`, doc schema +
`registry.rs`/`registry/tests.rs`/`workspace.rs`, six harness
normalizers, UI `todo_panel.rs` (new) + `composer.rs`/`transcript.rs`/
queue wiring, `docs/todo-panel.md`, edge web client (excluded).
Source: `.scratch/upstream-drift/2026-10-02.md` § #707 (carry-over
confirmed un-ported in `2026-10-05.md`).

**Verification budget:** deferred — wave-final batched pass:
`cargo nextest run -p roboco-harness --lib` (six normalizer suites),
`-p roboco-ui --lib` (panel), `-p roboco-doc`; `wiregen --check`;
`pnpm -r build` + app vitest (web panel).

- [ ] Todo items extracted per harness (all six normalizers, shared
      shape)
- [ ] Doc schema + registry entries ported
- [ ] Dedicated panel UI (composer/transcript/queue wiring)
- [ ] `docs/todo-panel.md` ported (rebranded)
- [ ] Web panel components in `web/packages/app`
- [ ] Edge/iOS/sync hunks excluded, recorded in the commit
- [ ] Port commit records the upstream SHA + exclusions
