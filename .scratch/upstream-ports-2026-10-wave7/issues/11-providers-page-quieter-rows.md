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

**Status:** ready-for-agent

**Verification budget:** `cargo check -p roboco-engine -p roboco-ui` +
harness-update tests. Source:
`.scratch/upstream-drift/2026-10-11.md` § `54954e4d`.

- [ ] Providers page rows ported; toggling is layout-stable
- [ ] Commit records upstream SHA `54954e4d`
