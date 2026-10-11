# 06 — Sidebar: view options in the titlebar, ordered sections, searchable popovers (upstream 05f5f2bf + ae19fe31 + 672cc144)

**What to build:** Port the first half of the sidebar run as one ticket:
`05f5f2bf` (drop the ellipsis from New project in project menus),
`ae19fe31` (sidebar min width 224 → 242 so the options button fits
beside the titlebar controls), and `672cc144`:

- Every searchable popover (project, device, branch, spaces, changes
  ref, font) shares the model picker's search row and 32px rows, one
  tone, scroll edge fades and rails. Project rows in both project
  dropdowns wear the project icon; the device tag is gone from the
  dropdown and the sidebar filter.
- All sidebar section headings share one header; collapsed sections show
  a muted count badge cut like the PR badge; header glyphs share the
  label's tone. Custom sections keep a placed order, as Pinned does:
  Assign carries optional after/before anchors (older engines ignore
  them and append), the registry stores an order key per member, drags
  reorder in place.
- The view options move to the titlebar at the sidebar's trailing edge;
  View > Show gains a Project filter toggle (hiding it clears the
  filter).
- Polish: agent-update pill summary fits the card border; expanded list
  leaves no left strip; thinking/tool-call group headers roll as their
  summary changes.

**Why now (verified 2026-10-11):** this plus ticket 07 is the visible
screenshot gap the Zeron creator flagged. Roboco's `SIDEBAR_MIN` is
224 (`crates/ui/src/settings.rs:33`) vs upstream 242; the view options
still live in the sidebar body; the doc-side substrate is absent —
`crates/doc/src/registry/` holds only `tests.rs` (no `sidebar_sections`
persistence module), and proto has no `sidebar_pins.rs`/order keys
(pins ride `SetSidebarPins` in `crates/proto/src/entities.rs:1132`).

**Steps:**
1. `git show 672cc144` (18 files, +1001/−380), `05f5f2bf`, `ae19fe31`.
2. Port the popover-search unification and the section-header/count-badge
   work into `shell/spaces.rs` + the picker surfaces.
3. Adapt the ordered-sections persistence: upstream builds on
   `crates/proto/src/sidebar_pins.rs` + `crates/doc/src/registry/
   sidebar_sections.rs` from the (unported, cloud-coupled) pin-sync
   line — roboco must land the equivalent order-key substrate on its own
   shape (`proto/entities.rs` RPCs + doc registry), excluding
   device-sync semantics. **This is a wire change.**
4. Move the view options into the titlebar trailing edge; add the
   Project filter toggle to View > Show; widen `SIDEBAR_MIN` to 242;
   drop the New project ellipsis.
5. Port the registry tests (`crates/doc/src/registry/tests.rs` +76) for
   ordering/anchors.

**Excluded upstream hunks:** the device-sync transport/reconcile hunks
of #487's line (pin write transport, registry snapshots, cross-device
reconcile) — roboco keeps persistence engine-local. PR-repository
popover hunks ride #484 (parked, ticket 21).

**Blocked by:** None (07 sequences after this).

**Status:** ready-for-agent

**Verification budget:** `cargo check -p roboco-doc -p roboco-proto
-p roboco-ui` + registry/picker tests; **wiregen gate must run**
(`cargo run -p wiregen -- --check`, regenerate with
`pnpm -C web codegen` if it fires). Source:
`.scratch/upstream-drift/2026-10-11.md` § sidebar series.

- [ ] Searchable popovers unified on the model-picker search row
- [ ] Section headers + count badges; placed-order sections with anchors
- [ ] Order-key substrate adapted onto roboco's persistence (no
      device-sync semantics)
- [ ] View options in the titlebar; Project filter toggle; SIDEBAR_MIN 242
- [ ] Wiregen gate green; registry tests ported
- [ ] Commit records upstream SHAs `672cc144` + `05f5f2bf` + `ae19fe31`
