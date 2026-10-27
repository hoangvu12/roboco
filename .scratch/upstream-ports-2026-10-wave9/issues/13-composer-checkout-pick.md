# 13 — Composer: keep each project's checkout pick (upstream #992, `f6d4b1a01`)

**What to build:** Port `f6d4b1a01`: each project's checkout/branch pick in
the composer survives project switches — `crates/ui/src/pickers.rs` +153.

**Excluded upstream hunks:** none (single file, wholly ours).

**Blocked by:** none.

**Status:** ready-for-agent

**Verification budget:** `cargo check -p roboco-ui` + pickers tests.
Source: `.scratch/upstream-drift/2026-10-25.md` § B1.

- [ ] Checkout-pick persistence + tests ported
- [ ] Commit records upstream SHA `f6d4b1a01` (#992)

## Comments
