# 61 — Settings search: find any setting from the sidebar, Ctrl+F (#1018)

**Lane:** 4b Features: desktop UI. **Effort:** M–L. Its new files are
self-contained; the anchors touch every page.

**What to build:** Port `settings/search.rs` (+851),
`settings/search/reveal.rs` (+813), `shell/settings_search.rs` (+704) and
the per-page search anchors. Typo-tolerant matching, a landing that
reveals and highlights the setting, no-results state, light/dark. ⌘F
becomes **Ctrl+F** on Windows/Linux (check against existing Ctrl+F
bindings in the transcript; settings focus wins only inside Settings).
Include open PR #1075's fix for #1018's Windows test. Anchors go only
on Roboco's pages: Remote access instead of Cloud and Billing; Worktrees
(60); Native subagents (55); Roboco's grouped Accounts page
(`has_escape_surface`).

**Upstream:** `bf6d71aaa` (#1018); open PR #1075 (Windows test fix).

**Roboco files:** `crates/ui/src/settings/{search.rs,search/reveal.rs}` (new),
`crates/ui/src/shell/settings_search.rs` (new), `crates/ui/src/shell.rs`,
each `crates/ui/src/settings/*.rs` page, `crates/ui/src/dictation/model.rs`,
`crates/ui/examples/settings-search-fixture.rs`.

**Excluded:** anchors in `billing.rs`/`cloud.rs` (cloud), PR-board
settings (parked), math settings (declined), browser anchors (removal);
screenshots.

**Proto change:** no.

**Blocked by:** 60, 55 (their pages get anchors).

**Status:** ready-for-agent

**Source:** `2026-10-28.md` late window #1018; `pass2-features-desktop.md` §1.

- [ ] Ctrl+F in the sidebar searches settings; a hit reveals and highlights the row (fixture test)
- [ ] Every Roboco settings page has anchors (a test enumerates sections)
- [ ] #1075's Windows test passes on Windows
- [ ] Commit records `bf6d71aaa` and PR #1075

## Comments
