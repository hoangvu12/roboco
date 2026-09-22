# Sidebar view menu fixture

Native GPUI fixture from the isolated `sidebar-fixture` example. Screenshots
are not checked in for Roboco; run the fixture locally:

```sh
cargo run -p roboco-ui --example sidebar-fixture --features project-palette-fixture
ROBOCO_SIDEBAR_COMPACT=1 cargo run -p roboco-ui --example sidebar-fixture --features project-palette-fixture
```

Organize, Sort, and Show open nested menus using the same popup component as
the model selector. Organize and Sort summarize their current choices; the
ungrouped choice is named None. Show has its own section without a count, and
Compact has a separate section with a switch. Radio choices close the child;
Show and Compact toggles stay open for repeated changes.

Hover intent is shared with the model selector through `popover::HoverIntent`.
It protects diagonal travel through the trigger-to-child corridor, renews the
300ms grace period while the pointer advances, and cancels pending switches on
exit, clicks, keyboard input, and dismissal. The helper's module documents how
to wire it into future menus.

Regression coverage: the shared `popover::HoverIntent` unit tests plus the
model-selector mouse-path test (hover traversal, grace renewal, click
cancellation, corridor exit on both popup sides).
