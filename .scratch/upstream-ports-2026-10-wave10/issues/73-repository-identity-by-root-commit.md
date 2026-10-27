# 73 — Projects: identify repositories by their trunk's root commit + subfolder (#799 deferred half, slice a)

**Lane:** 4b Features: desktop UI (engine files). **Effort:** M.

**What to build:** Today sibling folders of one monorepo merge into one
project, and identity is remote-or-local
(`crates/engine/src/repos.rs:437-478`). Port upstream's identity:
- `commit:<root sha>` from `trunk_root_commit`, plus a `--show-prefix`
  subfolder suffix, so `mono/app-a` and `mono/app-b` are distinct
  projects while two clones of one repo group together.
- On a transient git error, keep the last id instead of setting `None`
  (`spaces.rs:218-223`).
- Migrate existing groupings so pins, sections and per-project settings
  keyed by the old `repo:` id follow the project (engine-side sidebar
  state). Write the mapping test first.

**Upstream:** `dbb639be3` (#799) deferred half, slice (a) (merged
upstream; earlier reviews looked at the stale branch).

**Roboco files:** `crates/engine/src/repos.rs`,
`crates/engine/src/sidebar_state.rs` (key migration),
`crates/proto/src/view.rs:264` (group keys), `crates/ui/src/shell/spaces.rs`,
Android grouping (`crates/mobile-client/src/new_session.rs:139`) if it
reads the id.

**Excluded:** Zeron Icons, sidebar glyph morph, fork/worktree glyphs
(declined 2026-10-05).

**Proto change:** no new type. The id string format changes, so check
mobile-client consumers.

**Blocked by:** 21 (A1's `+` lookup by `project_key`).

**Status:** ready-for-agent

**Source:** `2026-10-28.md` §B #799 half; `part-05.md` `dbb639be3` (a).

- [ ] Two subfolders of one repo are two projects; two clones are one (engine tests with fixture repos)
- [ ] Existing pins/sections survive the id change (migration test)
- [ ] A git error keeps the last id
- [ ] Commit records `dbb639be3` (slice a)

## Comments
