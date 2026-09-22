# Sidebar account control fixture

Native GPUI fixture from the isolated `sidebar-fixture` example. Roboco is
engine-local (ADR 0004): there is no signed-in account, so the avatar carries
the `Roboco` initial and the menu's identity line reads "Stored on this
device". Screenshots are not checked in for Roboco; run the fixture locally:

```sh
cargo run -p roboco-ui --example sidebar-fixture --features project-palette-fixture
ROBOCO_SIDEBAR_COMPACT=1 cargo run -p roboco-ui --example sidebar-fixture --features project-palette-fixture
```

The sidebar footer uses a circular 21px avatar button with a 13px circle
matching the remote icon and a centered monospace initial. It shows no account
name or status line. The account menu opens to the right, clamped within the
window, and retains its identity line and actions. Only the avatar button is
interactive; footer whitespace does not open the menu.
