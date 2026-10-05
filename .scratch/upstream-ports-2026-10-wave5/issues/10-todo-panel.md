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

**Status:** claimed

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

- [x] Todo items extracted per harness (all six normalizers, shared
      shape)
- [x] Doc schema + registry entries ported
- [x] Dedicated panel UI (composer/transcript/queue wiring)
- [x] `docs/todo-panel.md` ported (rebranded)
- [x] Web panel components in `web/packages/app`
- [x] Edge/iOS/sync hunks excluded, recorded in the commit
- [x] Port commit records the upstream SHA + exclusions

## Comments

Ported upstream `1f7b74a7` (#707) — the wave-4 carry-over. File map
from `git show` matched the ticket's inventory; carried per the actual
diff with the recorded exclusions.

- `crates/proto/src/agent.rs` (+132): `TodoStatus` (Pending/
  InProgress/Completed, `parse` for harness strings), `TodoItem.status`
  additive serde-defaulted Option (written ONLY for in-progress, so old
  docs are byte-identical), `TodoItem::new` + `status()` effective
  derivation, `lenient_todo_status` (unknown status falls back to done,
  never fails the call). OUR TodoItem also derives TS + `#[ts(optional)]`
  on status (our proto generates web wire types; wiregen refresh deferred
  to the wave-final pass per spec). 5 upstream tests ported.
- `crates/doc/src/schema.rs` (+58): the two doc tests (status survives
  sync + in-place refresh by LIVE_PLAN_TOOL_ID; legacy part without
  status still reads). The doc SCHEMA itself needed no change —
  `MessagePart::Tool` is serialized via `serde_json::to_value(call)`, so
  the additive field flows through; upstream's diff confirms (schema.rs
  tests only).
- Six harness normalizers: acp/claude/cursor/opencode/codex all map via
  `TodoItem::new` + `TodoStatus::parse` (cursor keeps its
  `completed`-bool union); codex gains `turn/plan/updated` ->
  `plan_update_events` (LIVE_PLAN_TOOL_ID singleton, in-progress steps)
  wired in run_session; mock.rs gains the `ROBOCO_MOCK_TODO` knob
  (8-item walkthrough, rebranded env var; ROBOCO_MOCK_DELAY_MS pacing).
  Test updates: acp normalize (InProgress), claude (in-progress +
  unknown/missing = pending), codex (plan_update test), opencode tests
  (cancelled -> pending), integration acp.rs/codex.rs.
- `crates/ui/src/todo_panel.rs` (NEW 1142): upstream verbatim modulo
  rebrand (`roboco_doc`/`roboco_proto`). Pure half (latest_todo /
  TodoSummary / focus_window / rows / signature / TodoPanelState /
  TodoCache) + the Composer rendering (queue tray surface, edge-faded
  30vh list, check/active/pending glyphs, fold rows, per-chat open
  state, dismissal) + both test modules (12 pure + 3 composer tests).
- Composer wiring: `todo_cache`/`todo_panels`/`todo_scroll` fields,
  `render_todo_panel` mounted above the queue tray
  (`motion::fade_quick("composer-todo")`), queue.rs's PANEL_RADIUS +
  queue_panel_surface made pub(crate). transcript.rs: the chip detail
  marks in-progress `[~]`.
- `docs/todo-panel.md` ported (rebranded commands + the edge-reference
  sentence adapted: no edge client here, ADR 0003); the three
  screenshots carried from upstream.

**Excluded (recorded per the ticket):** `crates/client/*`
(demo transcripts), `edge/*` (control-types/render-parts/todo-items
tests — no edge here), `CONTRIBUTORS.md` (+1 knob table row — the repo
carries no such file; the knob is documented in docs/todo-panel.md).

Web parity (roboco-side build, since upstream's web work was edge):
- `lib/todo-panel-logic.ts`: the pure view model (effectiveTodoStatus,
  latestTodo, summary/headline, focus window, rows, signature) —
  structural TodoItem read so the code typechecks before AND after the
  wave-final wiregen refresh.
- `components/todo-panel.tsx`: the tray — collapsed header
  (Todo 2/5 + headline / All done), expanded fold-window list with
  check/spinner/still-ring glyphs, dismiss-while-idle, per-chat state.
- `state/todo-panel.ts`: the in-memory per-chat presentation store
  (subscribe/bump so interactions re-render).
- Composer gains `todoSlot` (rendered above the queue tray, one step
  narrower like the desktop); the chat page passes
  `<TodoPanel store={liveTranscript} chatId live={row.status === "working"}>`.
  Side chats get no panel (their composer takes no queue either — the
  desktop reads selected_chat, the main chat).
- CSS mirrors the queue tray conventions; tests/todo-panel.test.ts
  ports the pure-half tests.

Verification deferred to the wave-final batched pass: `nextest -p
roboco-harness --lib` (six normalizer suites), `-p roboco-ui --lib`,
`-p roboco-doc`; `wiregen` (regenerate — TodoItem.status + TodoStatus
are new wire surface) + `pnpm -r build` + app vitest there.
