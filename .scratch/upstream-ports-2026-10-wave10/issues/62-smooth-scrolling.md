# 62 — Smooth scrolling: mouse-wheel notches glide, with a setting (#1005)

**Lane:** 4b Features: desktop UI. **Effort:** M.

**What to build:** Port `crates/ui/src/smooth_scroll.rs` (+979): a
Chrome-like wheel glide for line-based wheels, untouched for precise
touchpads. Wire it into the transcript's `on_scroll_wheel` (+157), and
add Appearance → "Smooth scrolling" (`smoothScrolling` in `UiSettings`).
Respect reduced motion. Make sure it composes with ticket 24's #794
(wheel direction decides tail-follow) and ticket 67's terminal wheel
routing (the terminal stays unsmoothed). Check the zui pin after 57
supplies everything `smooth_scroll.rs` uses.

**Upstream:** `3d2f1e64a` (#1005).

**Roboco files:** `crates/ui/src/{smooth_scroll.rs (new),transcript.rs,settings.rs,lib.rs,shell.rs}`,
`crates/ui/src/settings/appearance.rs`, `crates/ui/Cargo.toml` (one dep),
search anchors (`settings/search.rs`, `shell/settings_search.rs`).

**Excluded:** `browser/macos.rs` (+229) and `browser/view.rs` (in-app
browser removal; macOS); screenshots and GIF/MP4 media.

**Proto change:** no.

**Blocked by:** 57 (pin), 61 (search anchors), 24 (#794 tail-follow).

**Status:** ready-for-agent

**Source:** `2026-10-28.md` late window #1005; `pass2-features-desktop.md` §1, §4.

- [ ] A wheel notch glides; touchpad scrolling is unchanged (smooth_scroll unit tests)
- [ ] The setting toggles it; reduced motion disables the glide
- [ ] Tail-follow still re-latches correctly
- [ ] Commit records `3d2f1e64a` with the browser hunks excluded

## Comments
