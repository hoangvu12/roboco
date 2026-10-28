# 01 — Desktop: the Remote access settings page scrolls

**What to build:** `RemoteAccessPage` (`crates/ui/src/settings/remote_access.rs:226-235`) renders a bare `widgets::page_column()` with no scroll host. It is the only settings page like that, so content below the window edge can't be reached (user report, 2026-10-28).

- Give it the same scroll host as the other pages:
  - a `widgets::PageScroll` field;
  - `overflow_y_scroll().track_scroll(..)` around the column;
  - the floating rail through `popover::rail`;
  - `on_scroll_hovered`, as in `AccountsPage` (`crates/ui/src/settings/accounts.rs:536, 629-636, 1767-1774, 2032-2103`).
- Then check every settings page for the same omission (`devices.rs`, `harnesses.rs`, …) and fix any others found.

This ships on its own, ahead of the redesign. Ticket 07 later replaces the page's contents but keeps the scroll host.

**Blocked by:** —

**Status:** done (branch `fix/settings-scroll`; the screenshot check is left to the user)

**Parent:** `.scratch/fleet-redesign/spec.md`

- [x] GPUI test: with content taller than the window, a scroll wheel event moves the Remote access page and the last row becomes visible
- [x] Every settings page has a scroll host (grep or a test over the page list)
- [ ] Rail and fade look identical to the Accounts page (screenshot in the ticket comment)

## Comments

**2026-10-28 — implemented** (commit "settings: Remote access page scrolls (fleet-redesign 01)" on `fix/settings-scroll`, merged with `origin/main` 912d8e797).

- `RemoteAccessPage` now has the Accounts page's scroll host:
  - a `scroll: widgets::PageScroll` field;
  - an `impl popover::ScrollRailHost` that delegates to it;
  - `on_scroll_hovered`;
  - a `remote-access-page-host` root (`relative`, `size_full`, list-hover `on_hover`) around an `overflow_y_scroll().track_scroll(&self.scroll.scroll)` page;
  - the rail from `popover::rail(.., "remote-access-page-scrollbar", ..)`.

  Like Accounts, it has no `edge_faded` wrapper, so the rail and fade match Accounts. Each paired-session row gets a `remote-session-{index}` debug selector for the test.
- **Pages fixed:** only Remote access. Every other section in `SettingsSection::ALL` already had a host when audited:
  - General and Shortcuts (`shortcuts.rs`);
  - Appshots (`appshots.rs`);
  - Appearance;
  - Notifications;
  - Voice (`dictation/model.rs`);
  - Providers (`harnesses.rs`);
  - Accounts;
  - Devices;
  - Files;
  - Archived.

  `auto_resume.rs`, `thread_naming.rs`, `composer.rs`, `completion.rs`, `wallpaper*.rs` are cards embedded inside those pages, not pages.
- **Tests:**
  - `settings::remote_access::tests::the_page_scrolls_its_last_row_into_view`: a 900×500 window, 40 paired sessions. The last row starts below the window edge. One wheel event leaves the scroll offset below 0, and the last row lies inside the window.
  - `shell::tests::every_settings_page_has_a_scroll_host`: an exhaustive `SettingsSection` → source match over `SettingsSection::ALL`. Each page source must have:
    - one `.track_scroll(&self.scroll.scroll)` per `widgets::page_column()`;
    - `overflow_y_scroll`;
    - a floating rail;
    - list-hover wiring.

    A new section can't compile without an entry.
- **Run:**
  - `cargo check -p roboco-ui --all-targets`: ok.
  - `cargo test -p roboco-ui --lib settings -- --test-threads=1`: 194 passed, 0 failed (this includes both new tests and every `settings::` test).
- **Screenshot: not captured.** No test or fixture renders a settings page. `scripts/test-windows-capture-client.ps1` only captures the synthetic `windows-render-fixture`. Launching the real app would start an engine next to the user's running instance, so the rail/fade comparison with Accounts is left for the user's manual check.
