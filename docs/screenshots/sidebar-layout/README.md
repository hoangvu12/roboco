# Sidebar layout fixture

Native GPUI fixture from the isolated `sidebar-fixture` example. All projects,
chats, icons and PR metadata are synthetic; no engine or account is connected.
Screenshots are not checked in for Roboco; run the fixture locally instead:

```sh
cargo run -p roboco-ui --example sidebar-fixture --features project-palette-fixture
ROBOCO_SIDEBAR_COMPACT=1 cargo run -p roboco-ui --example sidebar-fixture --features project-palette-fixture
```

Set `ROBOCO_SIDEBAR_HIDE_LABEL=1` to start with the project/device label hidden.
The sidebar view menu persists all three display preferences and project grouping.
Ungrouped rows live in a collapsible Sessions accordion; project/device groups
remain separate accordions. All sidebar accordion headers have no divider rules.
Archived project artwork uses the same dimmed treatment as archived harness icons.

Repository artwork follows [Conductor's documented filename priority](https://www.conductor.build/docs/faq#where-does-conductor-get-the-repo-icon).
The first existing file wins; missing or invalid artwork uses the supplied code
icon. Local reads and bounded image decoding run off the UI thread. Remote
projects use the owning device's workspace file RPC, including ICO support.
Artwork is shared across a project's rows, refreshed after five minutes, and
released from the image atlas when its cache entry expires. Raster thumbnails
are bounded to 64 pixels; SVGs retain their original colors.

Headless regression checks cover compact and detailed rows, independently
hidden labels, project icons and fallback icons, project groups, borderless
accordions, hover controls, and dragging pinned sessions. Compact rows place
status on the left and elapsed time on the right. Hover replaces the remote
icon with Archive (or reveals it for local sessions), keeping status, PR, and
time visible. Pin/unpin, pin reordering, cancellation, actual row-height hit
testing, small pointer movements, project grouping and keyboard order, icon
lookup priority, SVG/ICO decoding, and settings persistence are all covered
headlessly.
