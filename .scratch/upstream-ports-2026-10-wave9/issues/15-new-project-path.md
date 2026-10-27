# 15 — New project: type a folder's path with ⇥ completion (upstream #998, `77a3c0ff1`)

**What to build:** Port `77a3c0ff1`: the new-project flow accepts a typed
folder path with tab completion and ghost text — `crates/ui/src/shell/spaces.rs`
(+1639), `pickers.rs` (+229), `shell.rs`, `state.rs`; the 887-line fixture
example ports for tests.

**Port note:** upstream's path field lives on the **device palette**
(multi-device grouping) — rebase it onto roboco's single-device
`spaces.rs` (current through wave-7 06). Windows path completion needs a
sanity pass (separators, drive letters). docs/media screenshots optional.

**Excluded upstream hunks:** none of substance; skip the media binaries if
desired.

**Blocked by:** none.

**Status:** ready-for-agent

**Verification budget:** `cargo check -p roboco-ui` + spaces/navigation
tests + the fixture example. Source: `.scratch/upstream-drift/2026-10-25.md` § B3.

- [ ] Path field + completion + fixture ported, single-device palette
- [ ] Windows path behavior verified
- [ ] Commit records upstream SHA `77a3c0ff1` (#998)

## Comments
