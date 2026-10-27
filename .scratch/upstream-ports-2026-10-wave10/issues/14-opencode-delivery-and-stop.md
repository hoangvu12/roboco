# 14 — OpenCode: deliveries survive a lost answer, Stop ends subagents, Windows-safe attachment URLs (A37 OpenCode slice, part 2)

**Lane:** 1c Fixes: agents + orchestration. **Effort:** M.

**What to build** (`crates/harness/src/opencode/mod.rs`):
1. **Prompt delivery that survives a lost answer** (`post_prompt`,
   `:3019-3158`): check by message id whether the prompt landed; re-post
   once; a 4xx fails at once; carry a cancel token. Today any POST
   failure fails the turn.
2. **Permission/question replies retried 3 × 10 s.** An undeliverable
   reply aborts and errors the turn (`:3645-3679` does one detached
   POST, which strands the agent).
3. **After a reconnect, sweep pending permissions and questions.** A
   reconnect doesn't settle a turn whose prompt is unanswered (the
   `Connected` handler at `:2467-2481` only re-syncs idle).
4. **Stop also aborts subagent child sessions** (`abort_children`,
   15 s). Today only the root is aborted, and children keep spending
   tokens.
5. **Attachment file URLs:** percent-encoded, `file:///C:/…`, UNC
   `file://server/share/…`, `\\?\` stripped (`:2922,2947` use
   `format!("file://{path}")`). Tests:
   `attachment_urls_survive_reserved_characters_and_windows_paths`,
   `windows_directories_encode_like_encode_uri_component`.
6. **First-start migration race is retried** ("Unexpected error" /
   "Failed query:" + DDL). Tests:
   `startup_retries_a_lost_migration_race`,
   `a_lost_migration_race_is_transient`.

**Upstream:** `2e151ca65` (#977) OpenCode slice (`post_prompt`,
permission/question around upstream `:3600-3770`, startup retries).

**Roboco files:** `crates/harness/src/opencode/{mod,tests}.rs`,
`crates/harness/tests/opencode.rs`.

**Excluded:** none beyond 13's.

**Proto change:** no.

**Blocked by:** 13.

**Status:** ready-for-agent

**Source:** `2026-10-28.md` A37 (OpenCode); `pass2-big-1.md` §OpenCode, gaps 1, 1a.

- [ ] A dropped prompt answer doesn't fail the turn; a duplicate isn't posted (test)
- [ ] Reply retries and the undeliverable-reply error (test)
- [ ] Questions asked during a reconnect reach the user (test)
- [ ] Stop aborts child sessions (test with a subagent fixture)
- [ ] Attachment URL table test passes, including on Windows
- [ ] Migration-race tests ported
- [ ] Commit records `2e151ca65` (OpenCode delivery half)

## Comments
