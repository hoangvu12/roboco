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
Archived sessions share active-session metadata and layout preferences, with muted
project/harness artwork and an Unarchive action in the same hover slot.

Repository artwork follows [Conductor's documented filename priority](https://www.conductor.build/docs/faq#where-does-conductor-get-the-repo-icon).
The first existing file wins; missing or invalid artwork uses the project initial on a muted, colored
frosted background using the composer's backdrop-blur helper. A stable hash of the project path chooses from eight fixed colors: slate, blue, violet, rose, amber, emerald, teal,
and orange, each with an explicit light/dark variant independent of theme accent. Like PR badges, the background
uses 8% of the tone and the monospaced letter uses 85%. Local reads and bounded image decoding run off the UI thread. Remote
projects use the owning device's workspace file RPC, including ICO support.
Artwork is shared across a project's rows, refreshed after five minutes, and
released from the image atlas when its cache entry expires. Raster thumbnails
are bounded to 64 pixels; SVGs retain their original colors.

Headless regression checks cover compact and detailed rows, independently
hidden labels, project icons and fallback icons, project groups, borderless
accordions, hover controls, and dragging pinned sessions. Compact rows place
status on the left, followed by harness and project icons, the name,
remote/archive control, PR badge, and elapsed time on the right. Hover replaces the remote
icon with Archive (or reveals it for local sessions), keeping status, PR, and
time visible. Local rows reserve no empty action slot at rest,
so the title uses that space until Archive appears on hover. Compact Archive and
Unarchive are background-free, with the same 13px size as the remote icon.
Pin/unpin, pin reordering, cancellation, actual row-height hit
testing, small pointer movements, project grouping and keyboard order, icon
lookup priority, SVG/ICO decoding, and settings persistence are all covered
headlessly.

Compact mode defaults on when no preference is saved; explicit detailed-mode
preferences remain unchanged. Icon discovery keeps root-level artwork first,
then checks web/frontend/client/site/website/app/desktop/docs and one-level apps,
packages, services, and crates workspaces. It includes Next-style src/app and
static favicon SVG/PNG/ICO locations. Common web apps rank first, then roots sort
lexically. Local lookup caps app roots at 64; remote lookup uses two file-index
searches capped at 200 results each, excludes ignored files, and accepts only the
same supported layouts. The existing 30-second lookup timeout still applies.
