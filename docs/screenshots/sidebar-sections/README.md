# Sidebar sections fixture

Native GPUI fixture from the isolated `sidebar-fixture` example. Screenshots are
not checked in for Roboco; run the fixture locally:

```sh
cargo run -p roboco-ui --example sidebar-fixture --features project-palette-fixture
ROBOCO_SIDEBAR_COMPACT=1 cargo run -p roboco-ui --example sidebar-fixture --features project-palette-fixture
```

The sidebar's three-dot menu offers Create Section. Sections appear below Pinned
and above Sessions/project/device groups. Empty sections say "Drop sessions here".
Section names, membership, and collapsed state persist per profile on this device
(engine-local per ADR 0004 — never synced; Roboco has no signed-in account).

Interactions (run the fixture to review):

- View menu → Create Section: create action and named-section dialog.
- Empty and populated sections.
- Moving the same session between pinned, sections, and regular groups without
  duplicating it.
- Hover menu on a section header: Edit, Archive all, Delete.
- Collapsed section bodies; deleting a section returns its sessions to the
  normal groups; empty sections are retained.

Regression coverage: the four section tests in
`crates/ui/src/shell/sidebar_sections.rs` — movement and mutual exclusion,
persistence/profile isolation, non-destructive deletion, every Archive all
request (including a failed one), and the pin-settle ledger (rejection restores
membership, an older ack keeps a newer move). Archive all is tested with a mock
in-memory RPC connection.
