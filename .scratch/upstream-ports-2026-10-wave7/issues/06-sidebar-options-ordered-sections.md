# 06 — Sidebar: view options in the titlebar, ordered sections, searchable popovers (upstream 05f5f2bf + ae19fe31 + 672cc144 + riders 26c8e8404 + 81e04e1c5)

**2026-10-22 re-scope:** two v0.2.112–114 riders fold into this ticket
(see `.scratch/upstream-drift/2026-10-22.md`): `26c8e8404` (#909,
reorder sidebar sections by dragging their header — per-section order
keys minted between neighbours' keys as pins are, engine capability
`sidebar-section-order-v1`) and `81e04e1c5` (#901, sidebar toggle for
the Archived section). A head start on `05f5f2bf` + `ae19fe31` is saved
as `ticket-06-foldins.patch` beside the wave-7 spec — apply it first
(`git apply .scratch/upstream-ports-2026-10-wave7/ticket-06-foldins.patch`).

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
0. `git apply` the saved fold-ins patch
   (`.scratch/upstream-ports-2026-10-wave7/ticket-06-foldins.patch`) —
   it carries the `05f5f2bf` ellipsis drop and the `ae19fe31`
   SIDEBAR_MIN 242 + titlebar-cluster test; fold them into this
   ticket's commit.
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
6. Fold in the rider `26c8e8404` (#909, drag-reorder): its proto hunk
   (+52 on the `sidebar_pins.rs` this ticket creates) adds per-section
   order keys; the doc-registry + drag-interaction hunks land on the
   substrate from step 3. Its `crates/client` hunks adapt to roboco's
   engine-client surfaces or drop if nothing consumes them; its
   `crates/mobile` FFI hunks are Android parity — hold them for a
   mobile follow-up. Wire change: run the wiregen gate again.
7. Fold in the rider `81e04e1c5` (#901, Archived-section toggle,
   `spaces.rs` +48 + `settings.rs` +7) on the same sections registry.

**Excluded upstream hunks:** the device-sync transport/reconcile hunks
of #487's line (pin write transport, registry snapshots, cross-device
reconcile) — roboco keeps persistence engine-local. PR-repository
popover hunks ride #484 (parked, ticket 21).

**Blocked by:** None (07 sequences after this).

**Status:** done (ported in ce4447c20)

**Verification budget:** `cargo check -p roboco-doc -p roboco-proto
-p roboco-ui` + registry/picker tests; **wiregen gate must run**
(`cargo run -p wiregen -- --check`, regenerate with
`pnpm -C web codegen` if it fires). Source:
`.scratch/upstream-drift/2026-10-11.md` § sidebar series.

- [x] Searchable popovers unified on the model-picker search row
- [x] Section headers + count badges; placed-order sections with anchors
- [x] Order-key substrate adapted onto roboco's persistence (no
      device-sync semantics)
- [x] View options in the titlebar; Project filter toggle; SIDEBAR_MIN 242
- [x] Riders: #909 drag-reorder + #901 archived toggle folded in
- [x] Wiregen gate green; registry tests ported (adapted: the upstream
      registry cross-device convergence tests are device-sync semantics —
      replaced by the engine-side placed-order restart test plus the UI
      placed-order/drag/anchor tests; see Comments)
- [x] Commit records upstream SHAs `672cc144` + `05f5f2bf` + `ae19fe31`
      + `26c8e8404` + `81e04e1c5`

## Comments

- 2026-10-23 (port): landed as one commit carrying `05f5f2bf` + `ae19fe31`
  (via the saved fold-ins patch) + `672cc144` + riders `26c8e8404` (#909)
  and `81e04e1c5` (#901).
- Order-key substrate adaptation: upstream's per-member/per-section
  fractional `orderKey`s + HLC clocks + registry rows exist only to make
  CONCURRENT CROSS-DEVICE writes converge. Roboco's engine-local store
  (`engine/sidebar_state.rs`, LWW ordered-list replaces over
  `SetSidebarSections`/`WatchSidebarState`, ADR 0004) already carries
  order in the arrays, so the port lands the placed-order CONTRACT:
  membership lists members in placed order (assign with after/before
  anchors; anchorless appends — the legacy shape), sections keep their
  placed order (drag-reorder splices the list + pushes the snapshot —
  roboco's equivalent of `SidebarSectionChange::Move`), and the sidebar
  renders section members in placed order instead of recency. The
  `SidebarSection` proto doc comment now states the array-order wire
  contract; wiregen regenerated (`SidebarSection.ts` doc — the type shape
  is unchanged) and the `--check` gate is green.
- Deliberately excluded: upstream's `SidebarSectionChange` wire enum +
  `sidebar-section-order-v1` engine capability (device-sync machinery —
  there is no older-engine gate to guard: the bulk replace has always
  preserved list order); `crates/client` hunks (cloud); PR-repository
  popover hunks (pull_requests.rs absent — rides parked #484/ticket 21);
  `crates/mobile` FFI `pin_session_at`/`place_session` (ticket step 6:
  Android parity held for a mobile follow-up); iOS hunks; the registry
  cross-device convergence tests (replaced by the engine-side placed-order
  restart test + the UI placed-order/drag tests).
- `ProjectIcons` (the shared artwork pipeline the pickers need) was
  extracted from Shell's inline cache into upstream's standalone-struct
  shape; roboco's engine resolution for a space (local engine, or the
  engine owning one of the project's chats) rides
  `project_icon::space_engine`.
