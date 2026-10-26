# 13 — Composer: keep each project's checkout pick (upstream #992, `f6d4b1a01`)

**What to build:** Port `f6d4b1a01`: each project's checkout/branch pick in
the composer survives project switches — `crates/ui/src/pickers.rs` +153.

**Excluded upstream hunks:** none (single file, wholly ours).

**Blocked by:** none.

**Status:** done (ported in the wave9/ui commit "Composer: keep each project's checkout pick across project switches")

**Verification budget:** `cargo check -p roboco-ui` + pickers tests.
Source: `.scratch/upstream-drift/2026-10-25.md` § B1.

- [x] Checkout-pick persistence + tests ported
- [x] Commit records upstream SHA `f6d4b1a01` (#992)

## Comments

- 2026-10-25: Ported in the wave9/ui commit "Composer: keep each project's
  checkout pick across project switches" (upstream `f6d4b1a01`, #992; SHA
  recorded in the phase-end reference commit). `Pickers` gained
  `checkout_by_space: HashMap<String, CheckoutKind>`; the state observer
  now files the checkout kind away under the space it is leaving and puts
  the arriving space's remembered kind back — gated on
  `selected_space_row().git_detected`, so a project that lost git never
  gets "New worktree" back (upstream's third rule; the ref pick still
  resets, as before). Carried 3 of upstream's 4 tests
  (`project_round_trip_puts_the_canvas_checkout_back`,
  `canvas_reopens_with_its_checkout_after_another_projects_chat`,
  `a_project_that_lost_git_never_gets_a_worktree_back`); upstream's
  fourth (`a_cloud_pick_is_not_put_back_on_return`) is **excluded by
  intent** — Roboco has no `CheckoutKind::Cloud` and no
  `is_cloud_device` (cloud removed), so the "cloud pick is never put
  back" rule and its Cloud/git_detected match arms have no counterpart
  here; the filed-away insert is unconditional instead of the
  Cloud-remove/insert match. Verification: `cargo check -p roboco-ui`
  clean (pre-existing warnings only); `cargo test -p roboco-ui --lib
  pickers::tests` → 60 passed / 0 failed, including all three ported
  tests.
