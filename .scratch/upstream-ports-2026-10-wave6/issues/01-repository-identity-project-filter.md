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

**Status:** ready-for-human

**Verification budget:** deferred to the end-of-pass batched pass:
`cargo check --workspace --examples` + `cargo nextest run -p roboco-ui
--lib` (state/spaces suites). Source:
`.scratch/upstream-drift/2026-10-07.md` § `3d4bfd11` (#811).

- [x] state.rs: `project_filter` / `projects` / `project_device_tag`
      ported, `sidebar_chats` rerouted, tests ported
- [x] spaces.rs: menu rows, highlight, row details, pin-drag
      validation by repository key
- [x] Rebrand applied (`roboco_proto::`), zero `zeron_` identifiers
- [x] Commit records upstream SHA `3d4bfd11` (#811) + exclusions

## Comments

Ported upstream `3d4bfd11` (#811) onto the wave-5 ticket 11 substrate
already in our tree. All ten upstream hunks carried; rebrand is only
`zeron_proto::` → `roboco_proto::` (state.rs doc link + spaces.rs
helpers/details).

**state.rs** (`crates/ui/src/state.rs`):

- `sidebar_chats` rerouted through a new `project_filter` closure —
  near-verbatim.
- `project_filter` — verbatim (rebranded): a filter naming one checkout's
  id matches any checkout of the same `project_key`; unsynced ids match
  only themselves; project-less chats stay out.
- `projects()` — verbatim: one entry per repository, members in
  `project_members` order (this device's checkout first), sorted by
  representative name then id.
- `project_device_tag` — verbatim: single device → `space_device_tag`;
  two devices → names joined `" · "`; more → `"N devices"`; offline
  only when every member device is offline.
- Test `project_filter_and_projects_span_a_repository_across_devices`
  ported verbatim, placed at the upstream slot (after
  `chats_in_space_filters_and_orders`, before
  `apply_chats_drops_vanished_selection`). Upstream's commit carries NO
  spaces.rs test hunks — the ticket's "spaces.rs test updates" is the
  pin-drag validation reroute below; our existing
  `sidebar_project_groups_preserve_pin_order_and_keyboard_order`
  (spaces.rs:325) covers the filter→visible-order path and still passes
  (spaces "a"/"b" share no `repository_id`, so grouping is unchanged).

**spaces.rs** (`crates/ui/src/shell/spaces.rs`), adapted to our diverged
tree (plain menu rows, no `sidebar_pins_for_profile` helper):

- `pinned_session_drag_is_valid`: filter step swapped for the shared
  `in_filter` closure. Kept our single-pass `pinned ∩ visible` shape
  (upstream recomputes via `sidebar_pins_for_profile`, which we don't
  have — our pins come straight from
  `settings.sidebar_pins(&drag.profile_key)`).
- `spaces_menu_rows`: iterates `state.projects()`, searches by
  representative name, emits one `SpacesMenuRow::Space` per repository
  carrying the local-first checkout's id (`projects[ix][0].id`). Doc
  comment updated per upstream.
- New `space_filter_key`/`space_row_key` helpers — verbatim (rebranded),
  placed after `spaces_menu_rows` as upstream did.
- `open_spaces_menu`: highlight starts on `space_filter_key`; start
  position matched by `space_row_key` — verbatim.
- `render_spaces_filter` (the pinned trigger row): label from
  `representative_space`, tag from `project_device_tag` — verbatim;
  upstream kept its comment unchanged, so did we.
- Menu-card rows: `filter_key` computed outside the block; `selected` by
  repository key; label via representative; offline via
  `project_device_tag`; the stale-id `None` branch keeps upstream's raw
  id comparison. **Deviation:** upstream's rows carry the tag TEXT
  (`Some(tag.into())`, 5-tuple) — Roboco's menu rows deliberately render
  no host-tag text (plain label-row treatment, introduced in `3edfe53b`),
  so our tuple stays `(row, label, offline, selected)` and the tag string
  is unused (`let (_, offline)`). The devices info reaches the row only
  through the offline glyph, per our standing style; the multi-device tag
  itself still lands on the sidebar trigger via `render_spaces_filter`.
- `archived_sidebar_chats`: rerouted through `project_filter` — verbatim
  modulo the rebrand.

No `web/` counterpart exists upstream; none touched. No exclusions: both
upstream files are wholly in scope. Verification deferred to the
end-of-pass batched pass per spec decision 2 (no cargo run during ticket
work).

- [x] End-of-pass verification green (ui 1578/1578 incl. the new
      regression test; fmt clean) — completion record in spec.md
