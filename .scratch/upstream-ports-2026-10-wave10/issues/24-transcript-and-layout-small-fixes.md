# 24 — Transcript and layout small fixes (A38, #907, #1025, #949, #794, #643, `5f60293c0`, #1026 slice, #940 slice, #986)

**Lane:** 1d Fixes: sidebar + chat. **Effort:** M (ten S pieces, most a few lines).

**What to build:**
1. **A38: diagram rasters ignore the docked card.** `transcript.rs:10392`
   becomes `self.content_width.min((list_width - self.right_reserve).max(1.0))`
   (upstream `ac4acbc21`'s own slice).
2. **#907 (`4f465702d`): the fork marker title paints over popovers.**
   `transcript.rs:9251-9261`: swap `.w_full().truncate().text_center()`
   for flex centring.
3. **#1025 (`4879a4754`): compact mode clips subagent cards in the
   tool fold.** In the fold's row-height budget (`transcript.rs:~8241-8260`),
   add the `is_spawn_link → CHIP_HEIGHT` early return (helper at `:395`),
   plus the test. The `subagent-fold-fixture` is optional.
4. **#949: the lightbox flashes for one frame on open.**
   `image_viewer.rs:201-202` resets bounds to zero, and `:217-218` fits
   to 0×0 on the first frame.
5. **#794: the wheel direction decides tail-follow.**
   `transcript.rs:4082-4083,4152` compare a stale distance. Use `wheel_dy`.
6. **#643: a double-click-then-drag selection degrades to characters.**
   `markdown/selection.rs:109` `begin_with_span` carries no unit. Keep
   word/line units while dragging.
7. **`5f60293c0` (#529) remainder: a code block's scrollbar shows an
   I-beam.** Add `.cursor(CursorStyle::Arrow)` on the hit strip
   (`markdown/render.rs:2489-2497`).
8. **#1026 slice (`ea3dcc811`): an outside press should only hide the
   link card.** `markdown/link_interaction.rs:259,273` still bumps `epoch`.
9. **#940 slice: titlebar content sits 2 px low.**
10. **#986: the titlebar toggles are in the opposite order to their
    columns.** `shell/tabs.rs:495` `toggle-files-panel` comes before
    `:510` `toggle-changes`.

**Upstream:** `ac4acbc21` (own slice), `4f465702d`, `4879a4754`,
`5f60293c0` (remainder), `ea3dcc811` (outside-press slice only); open
PRs #949, #794, #643, #940 (layout slice), #986.

**Roboco files:** `crates/ui/src/transcript.rs`,
`crates/ui/src/image_viewer.rs`,
`crates/ui/src/markdown/{selection,render,link_interaction}.rs`,
`crates/ui/src/shell/tabs.rs`, `crates/ui/src/shell.rs`.

**Excluded:** #1026's PR-link destinations (in-app browser + parked PR
view). #940's other slices (the audit marks the PR STALE/RISKY overall).
#793 (rail's last bucket) goes with the rail rewrite (ticket 63).

**Proto change:** no.

**Blocked by:** 01.

**Status:** ready-for-agent

**Source:** `2026-10-28.md` A38, §A smaller (UI Transcript, Layout), §B
(#1025, #907); `part-07.md`; `pass2-big-1.md` gap 9; `part-03.md` `5f60293c0`.

- [ ] Each item has a unit/UI test where upstream had one (A38, #1025, #949, #794, #643 at minimum)
- [ ] `cargo nextest run -p roboco-ui --lib` green
- [ ] Commit records the SHAs and PR numbers above

## Comments
