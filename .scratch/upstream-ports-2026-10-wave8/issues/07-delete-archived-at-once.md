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

**Status:** done (ported in 3e34ed018)

**Verification budget:** `cargo check -p roboco-ui` + archived settings
tests. Source: `.scratch/upstream-drift/2026-10-22.md` § engine tickets.

- [x] Delete-all flow + engine mutation ported
- [x] Commit records upstream SHA `4141c7619` (#861)

## Comments

- Ported in commit 3e34ed018. The Delete all button (Danger tone),
  confirmation dialog (popover card, count-naming copy), captured-id
  semantics with live recheck (mid-run unarchives spared), one
  deleteChat mutation per chat, failure counting, the copy helpers and
  both tests all carried. Escape routing goes through the Archived
  page first (shell.rs `dismiss_settings_escape_surface` gains the
  Archived arm).
- Adaptations (not exclusions): per-id engine targeting via
  `target_for_id` (engine-local pairing) instead of upstream's single
  engine; failures surface on the page's error strip — roboco has no
  toast module — so the test asserts `page.error` directly; the test
  pumps the registry's current-thread runtime like
  `sidebar_state_sync::pin_writes_route_through_the_engine_rpc` does
  for `EngineTarget::call`. Upstream's pagination and subtitle copy
  hunks ride roboco's existing page shape (no pagination here).
- Verified: cargo check -p roboco-ui clean; settings::archived 4/4,
  settings::widgets 4/4.
