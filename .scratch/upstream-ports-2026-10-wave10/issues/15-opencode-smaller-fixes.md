# 15 — OpenCode smaller fixes: V2 shell chips, background subagents, command catalog + `/compact`, Unix leak, titles (#773, #847, #959, #765, #614)

**Lane:** 1c Fixes: agents + orchestration. **Effort:** M (five S/M open-PR fixes).

**What to build:**
1. **#773: V2 `shell` and Code Mode `execute` tools render as
   "Tool · shell".** Map them to Exec chips (`opencode/mod.rs:4282` maps
   only `"bash"`).
2. **#847: a background subagent is settled early.** `task_completion`
   (`:3826-3846`) treats any completed task part as the child's end and
   doesn't check `metadata.status == "running"`. `:3544` latches `done`,
   and the child's `session.idle` isn't handled. Fixes upstream issue #845.
3. **#959: the command catalog is read before it settles,** and there's
   no `/compact` route. `:499-510`, `:1884-1891` read `/api/command`
   once; route `/compact` through `native_command_request` (`:3167`).
4. **#765: a cancelled startup orphans the npm/bun server on Unix.**
   Add `process_group(0)` plus a group kill in Drop (`:790-794` has only
   `kill_on_drop`). Copy the `acp/child.rs:19,75-109` pattern.
5. **#614: OpenCode chats never get generated titles.**
   `harness/src/lib.rs:695` `supports_titles` excludes OpenCode, and
   `engine/src/titles.rs:184` guesses `cheapest_model` instead of using
   the session's own harness and model. This touches title generation
   for every harness, so keep the existing harnesses' behaviour unless
   the PR's change is clearly better, and say which in the commit.

**Upstream:** open PRs #773, #847, #959, #765, #614 (read per spec
Default 4).

**Roboco files:** `crates/harness/src/opencode/mod.rs`,
`crates/harness/src/lib.rs`, `crates/engine/src/titles.rs`, tests.

**Excluded:** #755 (OpenCode credentials/discovery bundle, L,
unreviewed). Its claims need checking first; it's listed in ticket 78.

**Proto change:** no.

**Blocked by:** 14 (same file).

**Status:** ready-for-agent

**Source:** `2026-10-28.md` §A "Smaller confirmed fixes" (OpenCode);
`part-prs-B.md` rows #773/#847/#959/#765/#614.

- [ ] Each fix has a unit or fixture test
- [ ] An OpenCode chat gets a generated title; other harnesses' titles are unchanged (or the change is recorded)
- [ ] Commit records the five PR numbers (head commits read)

## Comments
