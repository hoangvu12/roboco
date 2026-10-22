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

**Status:** ready-for-agent

**Verification budget:** `cargo test -p roboco-engine`. Source:
`.scratch/upstream-drift/2026-10-22.md` § engine tickets.

- [ ] `home_index.rs` + projectless folder routing ported
- [ ] Search hunks held (recorded) for wave-7 ticket 20
- [ ] Commit records upstream SHA `1bc74d5da` (#876)

## Comments
