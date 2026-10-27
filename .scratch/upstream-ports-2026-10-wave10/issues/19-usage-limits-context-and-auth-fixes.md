# 19 — Usage, limits and context: Claude weekly per-model limits, `[1m]` context window, Cursor usage bar, first auth retry (#808, #800, #428, #759)

**Lane:** 1c Fixes: agents + orchestration. **Effort:** M.

**What to build:**
1. **#808: Claude's per-model weekly limits are ignored.**
   `engine/src/agent_accounts.rs:4438-4455` reads only
   `five_hour`/`seven_day` and ignores `limits[].weekly_scoped`, and
   `ui/src/account_usage.rs:704` shows only two rows (`.take(2)`). Show
   the per-model weekly meters. No wire change: the usage rows already
   carry labels.
2. **#800 (closed-unmerged): Claude context window.**
   - A folded `[1m]` model runs at 200K although the picker shows 1M.
   - A gateway's all-zero usage frame pins the ring at 0%.
   - The result lookup ignores `[1m]`.
   Port the three fixes by intent; the PR is closed, so read it and
   re-derive against current code.
3. **#428 (closed-unmerged): Cursor usage.** One combined bar can read
   100% while both pools still have room. Show the pools separately, or
   combine them correctly.
4. **#759: the first Claude authentication failure is never retried.**
   Every `authentication_failed` reads as signed out
   (`claude/normalize.rs:18,57`). Retry once on the run host before
   declaring sign-out. Roboco's `refresh_lock.rs` already removes the main
   cause, so keep this narrow: one retry, then the existing sign-in
   error card (`run_errors`).

**Upstream:** open PRs #808, #759; closed PRs #800, #428.

**Roboco files:** `crates/engine/src/agent_accounts.rs` (+
`agent_accounts/usage.rs`), `crates/ui/src/account_usage.rs`,
`crates/harness/src/claude/{normalize,mod,catalog}.rs`, the Cursor
usage reader.

**Excluded:** none.

**Proto change:** no (check: if per-model rows need a new field, it's
additive. Flag it in the commit).

**Blocked by:** 18 (Claude files).

**Status:** ready-for-agent

**Source:** `2026-10-28.md` §A smaller (Claude), closed-unmerged #800/#428.

- [ ] Weekly per-model meters show (usage parse test with a captured response)
- [ ] A `[1m]` model runs with 1M context; an all-zero frame doesn't pin 0% (tests)
- [ ] The Cursor bar matches the pools (test)
- [ ] A one-off auth failure is retried once, then the sign-in card appears (fixture test)
- [ ] Commit records PRs #808, #759, #800, #428

## Comments
