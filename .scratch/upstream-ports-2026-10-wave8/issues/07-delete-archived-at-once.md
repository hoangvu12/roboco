# 07 — Settings: delete every archived session at once (upstream #861, `4141c7619`)

**What to build:** Port `4141c7619` (#861): the Settings → Archived page
gains a "delete all" action — confirmation flow, per-row state, and the
engine mutations that clear every archived session at once.

**Why now:** three files (+319): `crates/ui/src/settings/archived.rs`
(+318), `settings/widgets.rs` (+10), `shell.rs` (+4). Roboco holds
`settings/archived.rs` from the earlier archived-chat ports.

**Steps:**
1. `git show 4141c7619`.
2. Port the delete-all UI (confirmation, progress, empty state) and the
   engine mutations onto roboco's archived-session surfaces, adapting to
   roboco's engine-local session store names.

**Excluded upstream hunks:** none expected.

**Blocked by:** None.

**Status:** ready-for-agent

**Verification budget:** `cargo check -p roboco-ui` + archived settings
tests. Source: `.scratch/upstream-drift/2026-10-22.md` § engine tickets.

- [ ] Delete-all flow + engine mutation ported
- [ ] Commit records upstream SHA `4141c7619` (#861)

## Comments
