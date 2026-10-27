# 01 — Port upstream #1023: origin stamping, chat attribution, Ports card (upstream `a9e6c48b7`)

**What to build:** Port `a9e6c48b7` (PR #1023, "Previews: only discover ports Zeron started, and list them beside the chat") across the rebrand per `docs/reference/upstream-ports.md`. The engine stamps `ROBOCO_ORIGIN` (`chat:<id>` for agent runs, `terminal:<id>` for terminals) into the environment of everything it launches (`engine/src/sessions.rs`, `engine/src/terminals.rs`, proto `ORIGIN_ENV`/`chat_origin`/`terminal_origin` — `crates/proto/src/preview.rs`); discovery replaces the `mark_descendants` ancestry walk with marker-based attribution (`resolve`/`attribute`/`origin_marker` in `crates/preview/src/discovery.rs`, Linux `/proc` env read once per process identity, macOS `procargs`); `PreviewService` gains `chat_id` (wire change — **wiregen gate**); the harness gains the agent-CLI control-port reservation (`crates/harness/src/agent_ports.rs`, excludes e.g. `opencode serve`); terminals remember their chat after close; the session card gains the Ports section (`crates/ui/src/shell/session_info.rs` — `SessionPreviews`, `PORTS_INITIAL: 3`, `PORTS_PAGE: 10`, rows per server this chat can preview, Open opens a browser tab; macOS `register_preview` + `add_browser_surface` flow) and `browser/mod.rs` gains `register_preview`. Roboco's existing auth-command exclusion (wave5 ticket 05 port) is preserved.

**Excluded upstream hunks:** `edge/src/preview-room.ts` +2 and `edge/src/preview-room.test.ts` +3 (cloud territory). Refresh `docs/preview-networking.md`'s stale validation section (remove `ROBOCO_PREVIEW_TEST_EDGE` / `--test coordinator` references — removed with signaling) and adapt `docs/PARITY.md`/`ARCHITECTURE.md` hunks to the rebrand.

**Why now:** unlocks honest attribution (user-started servers stop listing), gives previews chat identity (required by ticket 03's authorization scoping), and delivers the visible feature (Ports card).

**Blocked by:** none.

**Status:** implemented
**Parent:** `.scratch/agent-previews/spec.md`

**Verification budget:** `cargo test -p roboco-preview --test discovery`, `cargo test -p roboco-engine --test previews`, `cargo test -p roboco-ui`, wiregen (`pnpm -r build` gate), clippy.

- [x] Only listeners carrying a `ROBOCO_ORIGIN` owned by a hosted chat list; user-started servers in a project cwd do not
- [x] Marker survives `nohup`/`setsid`/double-fork and engine restart; `env -i` launcher attributed via marked ancestor
- [x] Unreadable process table keeps the previous list; marker read once per (pid, start-time)
- [x] Agent CLI control ports (e.g. `opencode serve`) excluded via the reservation registry
- [x] `chat_id` on the wire, TS types regenerated, wiregen gate green
- [x] Ports card renders beside the chat (3 + paging 10), absent while nothing runs, opens a browser tab
- [x] Edge hunks verifiably absent; commit records upstream SHA `a9e6c48b7` (#1023)

## Comments

## Comments

### 2026-10-27 — implemented (worktree `ap/01-origin-stamping`, base `f9bdc6402`)

**Commands and results** (all from the worktree root; `RUSTC_WRAPPER=sccache` on a shared runner):

- `cargo test --locked -p roboco-preview --test discovery` → **4 passed, 0 failed** (`only_agent_started_http_processes_are_exposed_and_removals_are_live`, `a_discovered_server_is_probed_once_not_every_cycle`, `authentication_callbacks_receive_no_discovery_connections`, `backgrounded_scrubbed_terminal_and_out_of_tree_servers_stay_attributed`).
- `cargo test --locked -p roboco-preview --lib` → **23 passed, 0 failed** (includes the ported origin tests: nearest-marked-process/never-the-engine, read-once-per-identity + recycled pid, unreadable-env retried, whole-name matching, procargs parsing; plus the harness `agent_ports` reservation test lives in roboco-harness: `cargo test --locked -p roboco-harness --lib agent_ports` → 1 passed).
- `cargo test --locked -p roboco-preview --test leak --test proxy --test transport` → **3 passed, 0 failed** (touched literals).
- `cargo test --locked -p roboco-engine --test previews --test project_action_commands` → **1 + 2 passed, 0 failed** (incl. the new `actions_mark_what_they_start_with_their_terminal`).
- `cargo test --locked -p roboco-ui` → **1673 passed, 0 failed**.
- `cargo run --locked -p wiregen` + `cargo run --locked -p wiregen -- --check` → regenerated `web/packages/proto/src/generated/PreviewService.ts` (`chatId?: string | null` added; `RunRequest.ts` unchanged — its `env` field is `#[serde(skip)] #[ts(skip)]`); `--check` reports fresh. `pnpm -r build` from `web/` → 0 errors.
- `cargo clippy --locked -p roboco-proto -p roboco-preview -p roboco-engine -p roboco-harness -p roboco-ui --lib` → per-file warning sets identical to the base commit (verified by stashing the port, re-running clippy, and diffing `-->` file:line sets; only line numbers shifted). No new warnings.
- Compile surfaces: `cargo check --locked -p roboco-harness --all-targets`, `-p roboco-engine --all-targets`, `-p roboco-mcp -p roboco-mobile-client --all-targets`, `-p roboco-ui --features browser-fixture --example preview-fixture` → all clean.

**Not met / pre-existing red, verified identical at the base commit via `git stash`:**

- `roboco-doc` `--all-targets` fails at base (`schema.rs` test-only missing `NoticeSide`/`NoticeOutcome` imports + a moved-value error) — untouched by this port.
- `roboco-ui --example browser-fixture --features browser-fixture` fails at base (`gpui::Window::present_if_needed` gone) — untouched.
- `roboco-harness --lib`: `antigravity_named_home_settings_preserve_business_auth` fails at base on this runner (GEMINI_HOME not resolvable through the user database — environment-dependent); 353 others pass.
- The Ports card itself has no automated UI test: upstream #1023 carries none (its UI diff is render-path only); the card is a 1:1 port of `render_session_ports`/`SessionPreviews` (`PORTS_INITIAL: 3`, `PORTS_PAGE: 10`, absent while `services.is_empty()`, row click → `register_preview` + `add_browser_surface`), verified by compile + the `cargo test -p roboco-ui` suite that exercises `session_info.rs`'s card geometry tests.

**Deviations from the ticket (each with a reason):**

1. `RunRequest::env` had to be **restored** (serde-`skip` both ways, `#[ts(skip)]`) plus `apply_run_env` and per-driver application (claude/codex/cursor/pi/acp/opencode) — roboco had dropped upstream's env plumbing with the cloud credential broker, and the chat origin cannot reach the spawned agent CLI without it. This is the mechanism upstream #1023 reuses; no wire change (serde-skip), `RunRequest.ts` regenerated identical. ~79 mechanical `env: Default::default()` additions to existing `RunRequest` literals across 57 files.
2. The upstream commit has **no docs/PARITY.md or ARCHITECTURE.md hunks** (verified: `git show a9e6c48b7 --stat` lists 24 files, none under `docs/`); nothing to adapt there. `docs/preview-networking.md` was refreshed as instructed: validation section no longer mentions `ROBOCO_PREVIEW_TEST_EDGE`/`--test coordinator`/`npm --prefix edge test`, the discovery section now describes origin-marker attribution (replacing the removed `mark_descendants` ancestry walk), and the Presence/P2P section gained a note that it describes the dormant in-tree peer stack (no coordinator is deployed).
3. `crates/engine/tests/previews.rs` was **red at the base commit** (its `targetDeviceId:"peer"` watch trips roboco's fail-closed cross-engine routing guard, added when the cloud relay was removed; the test was never in any CI suite). Rewritten to the engine-local equivalent that keeps both asserted properties: a locally-hosted chat whose watch follows its checkout across `catalog.replace_local` updates, with a peer's `set_remote` services proving the owning-device filter. Verified red-at-base first (`git stash` + rerun).
4. `crates/ui/examples/preview-fixture.rs` was also **red at base** (drift from the Sep-15 `EngineTarget` refactor: `watch_previews` signature changed, the fixture kept passing `EngineHandle`; plus its `previews.start(…, None)` two-arg call predates the signaling removal). Fixed minimally in passing (`EngineRegistry::test_local(...).local()` target + the upstream-ported `PreviewScope` closure) so the example builds again as the docs instruct.
5. `terminals::associate_chat` records the remembered-chats map only (roboco's `LiveTerminal` has no per-session `chat_id` and no activity publisher — the upstream fields those feed don't exist here); `chats()` feeds the discovery `Scope`. Same semantics for attribution.
6. Excluded as cloud territory: `edge/src/preview-room.ts`/`preview-room.test.ts` hunks (roboco has no `edge/`) and `crates/preview/tests/coordinator.rs` (removed with signaling in roboco; its literal update has no target). Roboco's auth-command exclusion (wave5, PR #763 heritage) is preserved — `is_authentication_command` still filters in `candidates()`, and its test now runs with a marked callback process.

**Final commit:** `b6b7c8aa2` ("Previews: only discover ports roboco started, and list them beside the chat (ticket 01; upstream a9e6c48b7, PR #1023)") on `ap/01-origin-stamping`.

- 2026-10-26 (orchestrator): merged `ap/01-origin-stamping` into `feat/agent-previews` (merge `7aceb4384`, port commit `b6b7c8aa2` with upstream SHA recorded, record commit `54be09344`; clean merge with ticket 06 — only web overlap is the generated `PreviewService.ts`). Verification re-run at the merged tip: `cargo test -p roboco-preview --test discovery` 4/4; `cargo test -p roboco-engine --test previews` 1/1; `cargo test -p roboco-engine --test project_action_commands` 2/2; `cargo test -p roboco-ui` 1673/0; `cargo run -p wiregen -- --check` fresh; `pnpm -r build` (web/) all green. Clippy no-new-warnings evidence accepted from the worker's stash-based base comparison (merged tip adds only web/TS changes beyond it). Unblocks Wave 2 (02, 03, 07).
