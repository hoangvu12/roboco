# 11 — Settings: quieter Providers page, stable rows when toggling (upstream 54954e4d)

**What to build:** Port `54954e4d`: the Providers settings page gets
quieter rows and no layout shift when toggling a provider. 2 files,
+204/−94: `crates/engine/src/harness_updates.rs` +
`crates/ui/src/settings/harnesses.rs`.

**Why now:** roboco holds both files (from the #389 agent-updates
port); small, independent, closes the last non-PR-board settings gap of
the window.

**Steps:**
1. `git show 54954e4d` — port row rendering and the toggle stability fix.
2. Port the tests.

**Excluded upstream hunks:** none expected.

**Blocked by:** None.

**Status:** done (ported in a3aa1d69 on wave7/ports)

**Verification budget:** `cargo check -p roboco-engine -p roboco-ui` +
harness-update tests. Source:
`.scratch/upstream-drift/2026-10-11.md` § `54954e4d`.

- [x] Providers page rows ported; toggling is layout-stable
- [x] Commit records upstream SHA `54954e4d`

## Comments

Ported in a3aa1d69. Roboco's engine side matched upstream's pre-change
`refresh_enabled` verbatim; the UI side matched except roboco's
call-site shape (engine clients called directly, roboco-specific sign-in
slots kept between install and toggle). Adaptations: the old label test
`update_label_names_versions_and_manual_guidance` rewritten to the new
quiet semantics as `update_label_stays_quiet_and_names_versions`
(manual commands no longer ride the label), and the failure fixture
uses `HarnessUpdateFailure { message, retryable }`. 35/35 engine
harness_updates, 177/177 ui settings tests.
