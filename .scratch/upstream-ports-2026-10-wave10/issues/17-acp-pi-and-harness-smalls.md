# 17 — ACP/Pi and harness smalls: Grok/Hermes stop, Pi model-change bound, shim versions, cached slash catalogs, git PATH, test races (A37 ACP/Pi, #710, #666, A23, #869)

**Lane:** 1c Fixes: agents + orchestration. **Effort:** M (six S pieces).

**What to build:**
1. **Grok/Hermes stops wait the full kill grace.**
   `crates/harness/src/acp/mod.rs:3495-3496` uses
   `exits_on_stdin_eof = id != Opencode`. Upstream:
   `!matches!(id, Grok | Hermes)`. Hermes never exits on EOF, and Grok
   takes over 2 s. ACP unit tests to port:
   `only_a_missing_session_is_unrestorable`,
   `restore_prefers_what_the_agent_advertises`,
   `a_run_signs_in_only_with_the_agents_own_default_method`, the Grok
   cancel test, and `an_unknown_session_starts_fresh_with_a_notice`.
2. **Pi `set_model` has no bound.** An unanswered mid-session model change
   freezes the run loop, Stop included (`pi/mod.rs:2663,2942` await
   `configure_live` unbounded). Add a 30 s bound: "Pi did not apply the
   model change within 30s".
3. **#710: mise/asdf/Volta shims share one version.**
   `harness/src/executable.rs:259-261` keys versions by canonical path,
   so Pi and Codex behind one shim report each other's version. Key by
   the path actually run.
4. **#666: the slash menu re-runs discovery on every open**
   (`engine/src/registry.rs:177-211`; 2–4 s for Pi once ticket 20
   lands). Serve the cached `ListCommands`/`ListSkills` while refreshing.
5. **A23 (open PR #955): git runs without the login-shell PATH,** so
   worktree hooks can't find `npx` and similar tools
   (`engine/src/repos.rs:304` bare `Command::new("git")`). Use
   `roboco_harness::compose_login_shell_path` (`harness/src/lib.rs:311`,
   already used by `source_control.rs:791`). On Windows this also merges
   the registry user PATH.
6. **#869: CI races in shipped code.** `doc/src/queue.rs:144,162` lacks
   the Loro `OutOfBound` retry. `ui/src/settings/composer.rs:81` reads
   with plain `read_to_string`, so the Windows rename window yields
   defaults (write-temp-then-rename, or retry on sharing violation).
   Make the `diff_sync/turn_snapshot.rs:681` test helper skip
   `maintenance.lock`.

**Upstream:** `2e151ca65` (#977) ACP/Pi slices; open PRs #710, #666,
#955, #869.

**Roboco files:** `crates/harness/src/{acp/mod.rs,pi/mod.rs,executable.rs}`,
`crates/engine/src/{registry,repos}.rs`, `crates/doc/src/queue.rs`,
`crates/ui/src/settings/composer.rs`, `crates/engine/src/diff_sync/turn_snapshot.rs`.

**Excluded:** #869's edge/doc_host_delivery parts (N/A).

**Proto change:** no.

**Blocked by:** 01.

**Status:** ready-for-agent

**Source:** `2026-10-28.md` A37 (Engine/ACP, Pi), A23, §A smaller
(Engine); `pass2-big-1.md` gaps 5, 6.

- [ ] A Hermes or Grok stop finishes without the kill grace (acp_hardening-style test)
- [ ] An unanswered Pi model change errors at 30 s and Stop still works (pi fixture test)
- [ ] Two tools behind one shim report their own versions (test)
- [ ] The second slash-menu open is served from cache (registry test)
- [ ] A worktree hook that calls a login-shell-only tool succeeds (engine test with a stub PATH)
- [ ] #869 race fixes have tests; the settings read is retried on Windows
- [ ] Commit records `2e151ca65` (ACP/Pi slices) and PRs #710/#666/#955/#869

## Comments
