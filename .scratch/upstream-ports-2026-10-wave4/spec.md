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
- **Branch base: `main`.** Wave 3 is fully merged; no stacking. Tickets run
  in `wave4/NN-slug` branches off `main` and merge as they complete.
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
- Wave order: F (30, read-only, can start immediately) → A (01–05) →
  B (06–10) → C (11–22) → D (23–27) → E (28–29). Within C, tickets are
  independent. Within D, no cross-ticket deps (each stack is contained in
  one ticket). ~3 concurrent implementers max.

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
