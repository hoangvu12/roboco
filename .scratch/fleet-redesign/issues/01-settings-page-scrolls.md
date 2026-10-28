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

**Status:** ready-for-agent

**Parent:** `.scratch/fleet-redesign/spec.md`

- [ ] GPUI test: with content taller than the window, a scroll wheel event moves the Remote access page and the last row becomes visible
- [ ] Every settings page has a scroll host (grep or a test over the page list)
- [ ] Rail and fade look identical to the Accounts page (screenshot in the ticket comment)

## Comments
