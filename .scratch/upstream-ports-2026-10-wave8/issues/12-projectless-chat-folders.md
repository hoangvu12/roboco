# 12 — Projectless chats get their own folder; home is never indexed (upstream #876, `1bc74d5da`)

**What to build:** Port `1bc74d5da` (#876): projectless chats get their
own data folder instead of living under the home workspace, and the home
folder is never indexed by fff — new `crates/engine/src/home_index.rs`
(+333) plus `sessions.rs` (+24), `repos.rs` (+25), `rpc.rs` (+66),
`workspace_host.rs` (+72), `workspace_files.rs` (+12), `doc_host.rs`
(+10).

**Why now:** engine-only hygiene with real correctness payoff for
roboco's engine-local model (projectless chats are roboco's default mode
of use); roboco holds every engine file touched.

**Steps:**
1. `git show 1bc74d5da` — read `home_index.rs` and the workspace_host
   changes first; they are the heart.
2. Port `home_index.rs` + the sessions/repos/rpc/workspace_host hunks.
3. Port the `device_routing.rs` (+23) test updates.
4. If the port introduces new engine RPC surface types, run
   `cargo run -p wiregen -- --check` inside the ticket.

**Excluded upstream hunks:**
- `crates/client/src/client.rs` (+18) and `rpc.rs` (+2) — roboco has no
  `crates/client` (cloud); adapt whatever the desktop/web needs onto
  roboco's engine-client surfaces, or skip if nothing consumes it.
- `crates/engine/src/workspace_search.rs` (+183) — **hold for wave-7
  ticket 20** (#856 workspace search): roboco lacks the file until that
  port lands; record the held hunks in the port commit.

**Blocked by:** None (the held search hunks make it pair naturally with
wave-7 ticket 20, but do not block).

**Status:** done (ported in b6ab205e5 on wave8/engine)

**Verification budget:** `cargo test -p roboco-engine`. Source:
`.scratch/upstream-drift/2026-10-22.md` § engine tickets.

- [x] `home_index.rs` + projectless folder routing ported
- [x] Search hunks held (recorded) for wave-7 ticket 20
- [x] Commit records upstream SHA `1bc74d5da` (#876)

## Comments

Ported in b6ab205e5. Carried: proto `projectless.rs`
(`~/.roboco/scratch`, scratch_cwd/is_* predicates),
`PROJECTLESS_SCRATCH_V1` capability (wire constants regenerated,
wiregen --check green), view.rs "No project" tightening, engine
`home_index.rs`, `repos::resolve_chat_cwd`/`scratch_root`, sessions
scratch-root OnceLock + run-path resolution, doc_host claim→scratch
redirect, rpc terminal/file-search resolution, workspace_files
resolve_target, workspace_host create/claim/hosts_scratch_folders,
and the projectless/workspace_files/rich_composer_delivery test
reworks. The upstream device_routing relay test is adapted
single-device as `projectless_terminal_runs_in_the_chats_scratch_folder`.

**Held for wave-7 ticket 20:** the workspace_search.rs hunks (+183)
that consume HomeIndex. Roboco lacks the file, so home_index.rs ships
standalone (module tests verify it; `#![allow(dead_code)]` with a
note, since it has no consumer yet) and the name-search vocabulary
(NameKinds/NameMatch/normalize_name_query) it imported from
workspace_search is defined locally — ticket 20's port should unify
them into workspace_search.rs.

**Excluded:** `crates/client` (cloud, no roboco counterpart — per
ticket); `crates/ui` hunks (UI lane); `crates/mcp` tools.rs (not in
this engine ticket's scope — behavior preserved: MCP create_chat
sends no cwd so the engine mints the folder, and its runs send `~`
which the doc_host redirect follows);
`docs/performance-workspace-search.md` (documents the search path
roboco doesn't hold).

Verified: proto projectless 2/2, workspace 9/9; engine lib
home_index 2/2 + open_terminal_cwd + workspace_host;
`--test projectless` 4/4, `--test workspace_files` 8/8,
`--test device_routing` 4/4, `--test rich_composer_delivery` 5/5,
plus unarchive_on_send/queued_attachments/side_chats/engine_local/
local_first spot checks and `cargo check -p roboco-engine
-p roboco-mobile-client` clean; `cargo run -p wiregen -- --check`
green.
