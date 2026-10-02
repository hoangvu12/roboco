# Upstream ports 2026-10 wave 4: zeron v0.2.97 → v0.2.102 (9782693b)

Status: ready-for-agent

## Problem Statement

Roboco main carries the wave-3 ports (`ports/2026-10-wave3`, fully merged) and
the roboco-first Pi work (native RPC driver `1ef237d6`, live catalog
`86cb3339`, MCP extension bridge `70e8e8d0`), released as v0.6.0. Upstream
has since advanced from v0.2.97 (`50cf9e97`) to v0.2.102 plus one trailing
UI commit (`9782693b`) — 51 non-merge commits in the window.

After excluding version bumps (5), CI-only commits (6), the landing-page
footer (1), and the iOS commit (1), 38 commits remain. Of those, `df0cd298`
(#630, the native Pi RPC driver) is **not a port**: Roboco shipped its own
roboco-first implementation first, and ticket 21's recorded plan — "when they
write it, their MCP wiring will port cleanly onto our native PiHarness" — is
already satisfied (`70e8e8d0` is the same per-run mjs-extension shape as
upstream's `pi/mcp.mjs`). #630 becomes a comparison review (ticket 30) that
adopts only missing behaviors. The remaining **37 commits are portable**
across 29 tickets:

1. **Engine correctness/perf (5):** subagent lifecycle identity + restart
   recovery (#676), idle reaper vs. live subagents (#637), run-loop spin on
   empty subagent events (#604), diff-sync churn on file reads (#605), glibc
   malloc arena trim (#635).
2. **Harness fixes (6):** OpenCode 2.x cold-probe/start failures (#686) and
   2.x context usage (#634), Cursor user MCP settings (#616), Homebrew CLI
   updates (#661), antigravity detection/updates/sign-in (#617), message
   queue command/skill labels (#682).
3. **Small UI fixes (15):** right-panel tab close (#587), transcript
   drag-selection trio (#632/#556/#681), transcript file links (#606/#633),
   question panel frost (#669), queue attachment rows (#650), file-tree edge
   fades (#665), explorer subagent ordering (#638), sidebar jump hints (#641),
   palette child-chat exclusion (#651), fractional terminal scroll (#615),
   terminal/composer overlap (#620), icon-button tooltips (#628).
4. **Feature stacks (9):** compact model picker + effort controls (#471,
   fixes #721), on-device dictation (#591, fixes #740), file-tree mutations
   + drag-and-drop (#514, side-chat drops #612), wallpaper shuffle + adaptive
   colours (#598) with positioning/zoom (#660), reduce motion + background
   pause (#642).
5. **Packaging/chore (2):** Linux launcher-entry hardening (#627), `.gitignore`
   for `.claude`/`CLAUDE.md`.

Every touched upstream path maps onto a Roboco file that exists or is born in
this window (verified per commit; the markdown parser lives at
`crates/ui/src/markdown/parser.rs` here vs upstream's `crates/markdown`, and
our `pickers.rs`/`workspace_files.rs` gain subdirectory modules). Roboco has
the full substrate for all of it: SubagentSink/drive_run reaper machinery,
agent-CLI update lifecycle (`harness_updates.rs` from wave 3), side chats and
tabs, composer pickers, new-thread backgrounds, `dist/roboco.desktop` +
`scripts/package-linux.sh`.

## Solution

A ticketed port program under ADR 0003 and `docs/reference/upstream-ports.md`
carrying the intent of the 37 selected commits across the rebrand, excluding
iOS, landing, edge (the `edge/src/install.sh` curl-installer hunks inside
#617/#627/#591), macOS packaging bits, and upstream CI workflows. Tickets
live in `issues/` (01–30). Decisions resolved up front:

- **#630 is a review, not a port (ticket 30).** Our native driver already
  ships. The review compares upstream's implementation against ours and
  adopts only behaviors we lack: candidates are the 120s cold-extension
  startup budget (ours defaults 60s), the dropped-input-resolver→error
  semantics for harness questions, and `pi/PROTOCOL.md` as an internal
  reference. Output is a decision record; no structural convergence.
- **Branch base: `main`.** Wave 3 is fully merged; no stacking. Ticket
  branches are `wave4/NN-slug`. Driven by `/implement-feature`, the
  orchestrator's integration branch is the source of truth between waves
  and fast-forwards `main` at the end; a manual run may merge tickets
  into `main` as they complete. Either way: never push ticket branches,
  never merge the mirror.
- The mirror `zeron/main` was refreshed to `upstream/main` (`9782693b`)
  after the ancestry check passed.

## User Stories

1. As a subagent user, I want spawned subagents to keep their identity
   across resumes, stale chips recovered, and 100MB+ claude transcripts
   scanned off the runtime, so restarts are fast and correct.
2. As a long-running-session user, I want the idle reaper to spare a parked
   session's live subagents (bounded 8× grace, last-activity clocked), so a
   background agent is never killed mid-task nor pins a chat forever.
3. As a resource-conscious user, I want no idle CPU spin on empty subagent
   events, no diff-sync churn on file reads, and glibc arenas trimmed, so
   idle Roboco stays quiet and small.
4. As an OpenCode 2.x user, I want cold starts retried with a real budget,
   start failures shown in the transcript, and context usage attributed from
   step.ended, so runs don't insta-fail and the context ring is truthful.
5. As a Cursor user, I want my `~/.cursor/mcp.json` and plugin servers
   loaded (but not unapproved project sources), so the agent sees my tools.
6. As a Homebrew user, I want brew-installed CLIs updated via `brew upgrade`
   with idle auto-update waiting for Homebrew.
7. As an antigravity user, I want detection, registry-checked updates with
   pinned digests/signatures, and sign-in prompts suppressed without
   retry storms.
8. As a queuing user, I want command and skill rows labeled, attachment
   thumbnails named in tooltips, and rounded hover corners intact.
9. As a side-chat/tab user, I want the tab close button on the right with
   tooltips and a11y labels, drag-select working per-transcript-surface,
   selection isolated behind popups, and table-column selections correct.
10. As a transcript reader, I want encoded/out-of-folder file links opened
    read-only with a context menu, and unlabeled links named by their file.
11. As a question-panel answerer, I want a frosted opaque panel so the
    transcript doesn't bleed through.
12. As a sidebar/explorer user, I want Ctrl+N jump hints on one line and
    running subagents listed first, longest-running on top.
13. As a palette user, I want child chats excluded from command results.
14. As a terminal user, I want fractional scroll preserved and the terminal
    constrained below the composer, with update notices behind it.
15. As a keyboard learner, I want tooltips on every icon-only button.
16. As a model switcher, I want the compact model picker with an effort
    slider, fast-mode toggle, per-model effort memory, and a starred-first
    provider page — with the panel and chip agreeing on the model name.
17. As a dictation user, I want opt-in on-device transcription in the
    composer with a waveform, mic selection, and per-composer hover state.
18. As a file-tree user, I want context actions (rename/delete/copy path/
    add to chat) and drag-and-drop moves that coordinate across open
    surfaces, including side-chat drops, on Windows and Linux.
19. As an appearance user, I want wallpaper shuffling with preloading and
    adaptive colours, background positioning/zoom, reduce-motion following
    the OS, and animations paused while backgrounded.
20. As a Linux user, I want the launcher entry and icon installed with
    absolute paths that survive in-app updates.
21. As a web client user, I want every ported surface that exists on web
    mirrored there in the same ticket — labels, tabs, palette, queue rows,
    pickers, file tree, appearance — with wire types regenerated for any
    RPC surface change.
22. As a contributor, I want each port commit to record its upstream SHA
    and any deliberately excluded behavior.

## Implementation Decisions

- Process: `docs/reference/upstream-ports.md` by intent — implementers read
  upstream SHAs with `git show <sha>` (upstream fetched in `~/roboco-dev`),
  carry into a `wave4/NN-slug` branch off `main` in a worktree. Never merge
  the mirror; never push ticket branches.
- Wave-3's box discipline carries over: `source ~/.bashrc_pi`, worktree
  `target` symlinked to `~/roboco-dev/target`, touch-ritual before
  verification, `-j 3`, no `cargo clean`, no dev servers, no visual
  verification. The uid-1001 passwd shim and python3-shebang quirks are
  environmental (CI runs them).
- **Build-cycle economy (measured on this box: 3 cores).** `cargo check
  -p roboco-ui` with dep drift: 9m41s; warm no-op: 0.8s; crate-root
  touch: 4.4s; the ui TEST binary at default `debug = 2`: 15min+ per
  build. Per-ticket test builds are the wave's real cost — the same
  wall the last run hit. Countermeasures, all mandatory for this run:
  - `[profile.dev] debug = "line-tables-only"` (committed as prep;
    upstream CI's own `CARGO_PROFILE_DEV_DEBUG=0` trick, one notch
    gentler so panic line numbers survive test triage). Profile is
    manifest-level — never override it per-shell: one fingerprint for
    every shell and worktree sharing the target, or the shared cache
    thrashes.
  - Worker env stays exactly `~/.bashrc_pi` (sccache,
    `CARGO_INCREMENTAL=0`, shared `CARGO_TARGET_DIR`): sccache makes a
    fresh worktree cheap; no-incremental keeps the shared target stable
    across parallel worktrees.
  - **Bundle verification.** `cargo check` per edit iteration (seconds
    warm); `nextest` ONCE per ticket BUNDLE, not per ticket — the
    tickets' per-ticket "Verification budget" lines define WHAT to run
    (the filters); this spec defines WHEN (once per bundle, all filters
    in one build). The full suite runs once, at the skill's finish
    step.
  - **Ticket bundling is the default.** A worker claims a BUNDLE of
    2–6 consecutive frontier tickets in the same file domain — one
    worktree, one branch, one commit per ticket, one shared
    verification pass at the end — instead of one-worker-per-ticket.
    This amortizes the test-binary build, worktree spin-up, and
    sccache/target warm-up, and is the single biggest wall-clock lever
    on this box. Tickets still advance one at a time in the tracker
    (Status + Comments per ticket, commits reference the upstream SHA).
  - Wave-1 pre-warm: one background `cargo nextest run --workspace
    --no-run` under the worker env before the first wave spawns, so
    every worker starts warm.
- Renames: `ZERON_SESSION_IDLE_MS` → `ROBOCO_SESSION_IDLE_MS` (ticket 02
  introduces the override; our reaper currently hardcodes 30min),
  `~/.zeron/app/current` → `~/.roboco/app/current`, "Open in Zeron" →
  "Open in Roboco", `zeron mcp` → `roboco mcp` (OpenCode MCP block, ticket
  06). Proto type names stay neutral (`McpServer` etc. per ADR 0005).
- Wire surface changes regenerate wire types: tickets 13 (outsideWorkspace
  read-only reason) and 25 (workspace mutation contracts) run `wiregen` and
  `pnpm -r build` before landing (web-codegen gate parity).
- **Web parity is a deliverable, not a gap check.** Every ticket whose
  surface exists in `web/packages/app` implements the web side in the same
  ticket: right-tab strip (11), queue rows (10, 15), transcript file links
  (13), file tree (16, 25), explorer sections (17), chat list/jump hints
  (18), command palette (19), composer pickers (23), settings appearance +
  new-thread background lib (26, 27). Each ticket names its web files.
  Wire-surface changes regenerate wire types FIRST (tickets 13, 25: run
  `wiregen`, then build the web side on the fresh types; `pnpm -r build` +
  engine-client vitest where the RPC surface moves). Desktop-only by
  design: 12 (web renders transcripts through native DOM selection — no
  registry machinery to port; the outcome already holds) and 24 (no
  browser dictation surface — MediaRecorder → server-side parakeet would
  be a fresh design, not a port; recorded out of scope). 14/20/21/22 port
  the desktop mechanics and verify the web outcome, fixing only an actual
  visual/behavioral gap.
- Windows-first where upstream landed it incidentally: ticket 25's Windows
  drag handles and canonical-path comparisons are core (Roboco is
  Windows-native), not an afterthought; ticket 09's noop-browser fallback
  maps to our engine-side `ensure_noop_browser` (no `apps/roboco` noop
  browser test exists — port the assertion into an engine test).
- Upstream's `crates/harness/src/code_signature.rs` (ticket 09) is created
  here; its macOS cfg blocks are carried verbatim (compile-gated, no
  macOS ship) and Linux stays digest-only as upstream.
- Ticket 24 skips `dist/macos/*` entitlements, `scripts/package-macos.sh`,
  `scripts/run-macos-dev.sh`, and the voice CI workflows; ports
  `scripts/package-linux.sh` ALSA detection and the Windows/Linux surface.
- Ticket 28 ports the tarball-installer hardening + `dist/roboco.desktop`
  template + offline test; upstream's `edge/src/install.sh` curl-installer
  copy does not exist here (edge removed) — the spec records the equivalent
  behavior is already inherent to our tarball `install.sh` once hardened.
- Upstream version bumps (v0.2.98…v0.2.102) are skipped; Roboco versions
  independently (currently v0.6.0). `docs/research/` and zeronsh org-link
  boundaries untouched.
- **Orchestration capacity (this box: 3 cores, 22GB, warm shared
  target).** Cap build-active workers at 4 plus the orchestrator's own
  slot; concurrent `cargo` invocations serialize on the target-dir lock
  through the worktree target symlink, which is acceptable because
  edit/read time dominates builds. Ticket 30 is read-only and costs no
  build slot. The per-candidate review phase should stay proportionate
  to the diff — a one-file port does not get the same review depth as
  tickets 23–25.
- **Dependency edges encode file-overlap lanes.** The tickets' `Blocked
  by:` lines carry the shared-file chains (`02→03→01` sessions.rs,
  `09→08` harness_updates.rs, `14→12` composer.rs, `23→24` composer.rs,
  `13→25` workspace_files+proto+files, `26→27` settings/appearance.rs) and
  gate `22` behind the composer/terminal rewrites (`12, 14, 21, 23, 24`)
  so the tooltip sweep covers the final button surfaces. Everything else
  is unblocked — coexistence within a wave is the orchestrator's call
  (smaller wave when uncertain; rerere eats the residual conflicts).
- Wave order: F (30, read-only, can start immediately) → A (01–05) →
  B (06–10) → C (11–22) → D (23–27) → E (28–29), refined by the blocked
  edges above. The big feature tickets (13, 23, 24, 25) start in the
  first waves, not the last — they are the wall-clock critical path.
  ~4 concurrent implementers max on this box.

## Out of scope (recorded)

- `df0cd298` (#630) as a production port — roboco-first native Pi driver
  already shipped; see ticket 30's comparison record.
- iOS: `e9e00e21` (#636, `crates/mobile/`) and the `apps/ios` hunks of
  `df0cd298`.
- Landing page: `fd3b2c06` footer (no `apps/landing` here).
- Version bumps: `8f62632a`, `ed3b1aae`, `a2a593a5`, `b42fc2b8`,
  `64ad6f6e`.
- Upstream CI: `9cb1de50`, `878307a3`, `c168ba5b`, `f4abe69b`, `710b06ff`,
  `5d5d9359`, the CI hunks of `df0cd298`/`80b946b1`/`11c91089`, and the
  merge PRs (#691–#694). Roboco's 7 policy workflows stay the complete set.
- `edge/` code in all forms (curl installer, install.sh hardening copy,
  voice activation); macOS packaging and voice CI.
- Upstream's `docs/design/desktop-parakeet-v3.md` research voice: ticket 24
  ports `docs/reference/desktop-dictation.md` behavior notes only.
