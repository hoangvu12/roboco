# 01 — Repository-identity project filter (upstream #811)

**What to build:** Port `3d4bfd11` — the sidebar's project filter lists
one row per **repository** across devices (named for its representative
checkout, tagged with the devices holding it: "@ work-metal ·
work-laptop", or "@ N devices"), and filtering on it matches every
checkout's sessions — the sidebar list, the Archived shelf, and pin-drag
validation alike. The stored setting keeps one checkout's space id (this
device's first), so existing saved filters keep working and simply widen
to their repository; new sessions from the filter still land on that
local checkout.

**Why now (verified 2026-10-07):** this is the direct follow-up to
wave-5 ticket 11 (`5e178823`, #799 port) — the substrate it builds on is
exactly what we already ported: `space_row`
(`crates/ui/src/state.rs:1822`), `representative_space`
(`state.rs:1841`), `project_members` (`state.rs:1847`), `project_key`
(`crates/proto/src/view.rs:128`). The gaps it closes are verified:
`sidebar_chats` matches a single checkout
(`crates/ui/src/state.rs:1959-1969`); `spaces_menu_rows` iterates
`state.spaces_sorted()` and emits one `SpacesMenuRow::Space` per
checkout (`crates/ui/src/shell/spaces.rs:2168-2186`); the menu
highlight starts on the raw saved id, not a repository key
(`spaces.rs:2207`); row tags use per-checkout `space_device_tag`
(`spaces.rs:2864`, `:3100`); `project_filter`/`projects()`/
`project_device_tag` exist nowhere in our tree.

**Steps:**
1. `git show 3d4bfd11` — two files, `crates/ui/src/state.rs` (+139)
   and `crates/ui/src/shell/spaces.rs` (+71/−48), plus tests at both.
2. Port state.rs: `project_filter` (a `Fn(&Chat) -> bool` closure
   matching any checkout of the filter's repository via `project_key`;
   a filter naming an unsynced space matches its own id),
   `projects()` (one entry per repository, members in `project_members`
   order — this device's checkout first — sorted by representative name
   then id), `project_device_tag` (one device → existing
   `space_device_tag`; several → device names joined with `" · "` or
   `"N devices"`, offline only when every member device is offline),
   and reroute `sidebar_chats` through `project_filter`. Rebrand:
   `zeron_proto::` → `roboco_proto::` at every call site.
3. Port spaces.rs: `spaces_menu_rows` over `state.projects()`; new
   `space_filter_key`/`space_row_key` helpers; menu highlight and
   selected-row checks by repository key; the pinned-row header and
   menu row details through `project_members` +
   `project_device_tag`; pin-drag validation through the shared
   `in_filter` closure. Our `spaces.rs` is diverged from upstream
   (drag previews, jump hints from `f779045e`, palette namespacing,
   remote rows) — carry by intent, not by patch.
4. Port the tests: state.rs grouping/filter/device-tag tests (upstream
   `+88` at its `mod tests`) and the spaces.rs test updates
   (`sidebar_pins_for_profile` validation via `in_filter`).

**Excluded upstream hunks:** none — both files are wholly in scope.
No web counterpart exists upstream (#811 touches no `web/` files), so
no web parity work.

**Blocked by:** None.

**Status:** ready-for-agent

**Verification budget:** deferred to the end-of-pass batched pass:
`cargo check --workspace --examples` + `cargo nextest run -p roboco-ui
--lib` (state/spaces suites). Source:
`.scratch/upstream-drift/2026-10-07.md` § `3d4bfd11` (#811).

- [ ] state.rs: `project_filter` / `projects` / `project_device_tag`
      ported, `sidebar_chats` rerouted, tests ported
- [ ] spaces.rs: menu rows, highlight, row details, pin-drag
      validation by repository key
- [ ] Rebrand applied (`roboco_proto::`), zero `zeron_` identifiers
- [ ] Commit records upstream SHA `3d4bfd11` (#811) + exclusions
