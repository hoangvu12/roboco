# 04 — Engine update ownership + Claude credentials fallback (upstream #970, `f725384bd`)

**What to build:** Port `f725384bd`: (1) a superseded engine stops instead
of relaunching itself into an update while the app runs — the app's
reconnect starts the new version as its child (`engine/src/managed.rs`,
`crates/update/src/lib.rs`); (2) Claude Code login writes fall back to
`~/.claude/.credentials.json` when the Keychain write fails
(`engine/src/agent_accounts.rs`).

**Port note:** the orphan-successor bug applies to roboco's app-spawned
engine; keep the macOS Keychain rationale out of comments, keep the logic.

**Excluded upstream hunks:** none (all three files are ours).

**Blocked by:** none.

**Status:** ready-for-agent

**Verification budget:** `cargo check -p roboco-engine -p roboco-update` +
restart_resume/managed tests. Source: `.scratch/upstream-drift/2026-10-25.md` § A4.

- [ ] Stop-don't-orphan + credentials fallback ported
- [ ] Commit records upstream SHA `f725384bd` (#970)

## Comments
