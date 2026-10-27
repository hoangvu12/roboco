# 69 — Workspace search, engine half: fff file + content search, Home folder search (wave-7 20 part 1, #856) — PROTO

**Lane:** 4b Features: desktop UI (engine files). **Effort:** M.

**What to build:** the engine half of #856. Roboco has no workspace or
content search, and no `fff-search` dependency.
- Add `fff-search`. Add `engine/src/workspace_search.rs`: file and
  content search per checkout, a warm-up off the interaction path
  (`WarmWorkspaceSearch`), and bounded results.
- **Home folder search** for the New project picker ("Anywhere under
  Home"): `SearchHomeFolders` / `ReleaseHomeFolderSearch`, `FolderRow`,
  `repos.rs` `search_home_folders`. Roboco's `home_index.rs:11-17` waits
  on this port with `allow(dead_code)`. Wire it up.
- The `workspace_search.rs` hunks held back by #876 (ticket 21) land
  here (projectless scratch folders are never indexed; home never
  indexed whole).
- Reuse ticket 05's `folder_path_key` for Windows paths.

**Upstream:** `3487b507f` (#856) engine/proto/rpc hunks.

**Roboco files:** `Cargo.toml` (`fff-search`),
`crates/engine/src/{workspace_search.rs (new),repos.rs,home_index.rs,rpc.rs}`,
`crates/proto/src/entities.rs`, `crates/rpc/src/lib.rs`.

**Excluded:** cloud surfaces; screenshots/docs binaries.

**Proto change:** yes (`SearchWorkspaceContent`, `WarmWorkspaceSearch`,
`SearchHomeFolders`, `ReleaseHomeFolderSearch`, `FolderRow`).

**Blocked by:** 57 (authored on #484's `361ff20b` tree; compat), 05, 21.
**Then:** 70.

**Status:** ready-for-agent

**Source:** wave-7 `issues/20-workspace-search-cmdk-palette.md`;
`part-06.md` `3487b507f`; `pass2-features-desktop.md` §3, §5.

- [ ] Content search returns ranked hits for a checkout; warm-up runs off-thread (engine tests)
- [ ] Home folder search finds nested folders and releases its index (test)
- [ ] Windows paths keyed with `folder_path_key` (test on Windows)
- [ ] Commit records `3487b507f` (engine half)

## Comments
