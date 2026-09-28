# Upstream ports 2026-10 wave 3: zeron v0.2.91 → v0.2.97 (50cf9e97)

Status: ready-for-agent

## Problem Statement

Roboco carries the wave-2 ports (PR #13, `ports/2026-09-wave2` @ `787f635c`,
zeron v0.2.80 → v0.2.91, base `03b67beb`). Upstream has since advanced to
v0.2.97 (`50cf9e97`, 2026-09-28) — 36 commits in the window. After excluding
iOS (10 commits incl. the 1,160-file Rust-core rewrite), landing page (2),
version bumps (6), the contributor guide (1), and push notifications (1 —
iOS/edge/client only, zero desktop files), 15 commits are portable:

1. **Side chats + explorer sections + MCP injection (#498, `731697b6`)** —
   the foundation: right-sidebar side chats (synced forks, agent-spawned
   chats), fork seams, Subagents/Chats explorer sections, and the host
   engine stamping its own MCP server onto every run. Roboco has NONE of
   this stack (`shell/side_chats.rs`, `files/sections.rs`,
   `shell/navigation_focus.rs`, `crates/mcp` are all born here or in its
   precursors).
2. Seven side-chat follow-ups (#567, #568, #571, #572, #588, #590, #599)
   that build on #498.
3. Five independent UI fixes (PR badges, providers update-policy details,
   settings-focus session shortcuts, Star-on-GitHub banner, faster archive).
4. **Agent update monitoring (#389, `35a9139a`)** — all 57 changed files are
   desktop crates (engine `harness_updates.rs` 3054 lines, ui
   `shell/harness_updates.rs` 1044): device-local agent-CLI update
   lifecycle with controls.
5. **Durable desktop updates (#595, `109b39f3`)** — hourly wall-clock
   schedule + backoff, activation re-check, app-level checker, background
   download, install-on-quit, Check-for-Updates UI, staged-payload
   `--version` gating, Linux self-updating layout, Windows Inno installer.
   Partially overlaps Roboco's own `crates/update` (main `6b735e1b`,
   ported from upstream's `e492cd79` era): we already have staged Windows
   swaps, `--version` gates on Windows, the restart strip, the
   quiescent/idle auto-apply daemon path. The gap list is in ticket 19.

The wave-2 spec excluded the zeron MCP server as "a new cloud crate; an
engine-local variant, if ever wanted, is a fresh design, not a port". That
assessment is **obsolete**: at #498 the server is fully engine-local —
`zeron mcp` proxies into the engine's loopback IPC WebSocket ("the same
`zeron_rpc` WebSocket the headed app dials. Nothing here talks to the
edge"), and the engine injects it as the `mcp` subcommand of this same
binary with `ZERON_CHAT_ID`/`ZERON_DEVICE_ID` env. This wave ports it
(ticket 08) with the standard renames.

## Solution

A ticketed port program under ADR 0003 and `docs/reference/upstream-ports.md`
carrying the intent of the 15 selected commits across the rebrand, excluding
iOS, landing, edge/cloud, and upstream CI workflows. Tickets live in
`issues/` (01–20). Two decisions resolved up front:

- **MCP injection: port it** (ticket 08). Engine-local, additive,
  serde-defaulted, self-re-exec via `current_exe()` — no cloud surface.
- **Branch base: stacked.** `ports/2026-10-wave3` branches off the PR #13
  tip (`787f635c`), and its PR bases on `ports/2026-09-wave2` so PR #13
  stays pure wave-2; retarget to `main` after #13 merges.

The mirror `zeron/main` was refreshed to `upstream/main` (`50cf9e97`) after
the ancestry check passed.

## User Stories

1. As a user mid-turn, I want steering to hold for a live turn instead of
   interrupting it, and queued image rows to wait for their bytes, so a
   steer never kills my turn and agents never see `pending://` strings.
2. As a reviewer, I want PR badges always visible without the `#` prefix.
3. As a provider user, I want the update policy shown in expanded details
   with a stable chevron.
4. As a keyboard user, I want session navigation shortcuts to work while
   Settings is focused (desktop and web).
5. As a user, I want a dismissible "Star on GitHub" banner pointing at
   hoangvu12/roboco (desktop and web).
6. As a sidebar user, I want faster chat archiving from the Spaces view.
7. As a power user, I want side chats: forked chats and agent-spawned chats
   in the right sidebar, with tab close/restore, focus-following navigation,
   first-send persistence, harness choice, and a proper composer width.
8. As an agent, I want the roboco MCP server injected into my session so I
   can spawn, read, and message side chats attributed to my originating
   chat.
9. As an explorer user, I want Subagents/Chats sections under the file tree
   (newest first), with a "+" to start a side chat.
10. As a transcript reader, I want Cmd/Ctrl+C to copy my markdown selection
    even when the transcript just took focus.
11. As a multi-agent user, I want agent-CLI updates monitored and applied
    with per-agent controls, so installed CLIs stay current.
12. As a desktop user, I want durable app updates: hourly checks with
    backoff, activation re-check, background download, install-on-quit, and
    a Check-for-Updates menu with a live result.
13. As a Windows user, I want a per-user installer with Start-menu and
    uninstall entries (ticket 20 — release-process change).
14. As a web client user, I want the applicable behaviors mirrored on web
    and wire types regenerated for any RPC surface change.
15. As a contributor, I want each port commit to record its upstream SHA
    and any deliberately excluded behavior.

## Implementation Decisions

- Process: `docs/reference/upstream-ports.md` by intent — implementers read
  upstream SHAs with `git show <sha>` (upstream ref fetched in
  `~/roboco-dev`), carry into a `wave3/NN-slug` branch off the wave-3 tip
  in a worktree. Never merge the mirror; never push ticket branches.
- Wave-2's box discipline carries over: `source ~/.bashrc_pi`, worktree
  `target` symlinked to `~/roboco-dev/target`, touch-ritual before
  verification, `-j 3`, no `cargo clean`, no dev servers, no visual
  verification. The uid-1001 passwd shim and python3-shebang quirks are
  environmental (CI runs them).
- Per-device semantics: upstream's "across devices" surfaces map onto
  Roboco's existing per-engine routing (`request_routing::device_target`,
  web strips `targetDeviceId` at the socket); no cross-device propagation
  is ported (ADR 0004).
- Upstream CI workflows (e.g. `cursor-compatibility.yml` in #389) are not
  ported; Roboco's 7 policy workflows stay the complete set.
- Renames for the MCP port: crate `zeron-mcp` → `roboco-mcp` (`zeron_mcp`
  → `roboco_mcp`), subcommand `zeron mcp` → `roboco mcp`,
  `ZERON_IPC_PORT`/`ZERON_CHAT_ID`/`ZERON_DEVICE_ID` → `ROBOCO_*`, codex
  override key `mcp_servers.zeron.*` → `mcp_servers.roboco.*`; proto
  `McpServer` type name stays (neutral).
- The Star banner rebrands to `https://github.com/hoangvu12/roboco`.
- Upstream version bumps (v0.2.92…v0.2.97) are skipped; Roboco versions
  independently. The `docs/research/` and zeronsh org-link boundaries are
  untouched.
- Ticket 19 ports #595 selectively against Roboco's diverged
  `crates/update` (manifest+sha256 feed, WindowsPortable install kind,
  engine-owned `UpdateStatus` RPC must stay — the web client's strip reads
  it); macOS-only bits and the Inno installer split out (ticket 20).
- Wave order: A (01–06, independent) → B (07 foundation, then 08+09, then
  10) → C (11–17 follow-ups) → D (18–20). ~3 concurrent implementers max.

## Out of scope (recorded)

- `fe46a971` iOS rewrite + all iOS/TestFlight/mobile-only commits (9
  others): `29f1076a`, `61b8abb3`, `b89bafbe`, `d81ced91`, `7a4343b0`,
  `52047c95`, `433aa148`, `119b5ec0`, `eff95280`, `198777a4`.
- `50cf9e97`, `c2744c2f` — landing page (no `apps/landing` here).
- `24d5e491` contributor guide — upstream-specific.
- Version bumps: `68ef78bb`, `8ed3d25e`, `e2923bf0`, `65f77b65`,
  `747a3fd6`, `77b0ce3c`.
- Upstream's `crates/client`, `edge/`, APNs push machinery.
- macOS-only #595 hardening (App Translocation, disk-image blockers, macOS
  app-menu placement) — Roboco ships no macOS.
