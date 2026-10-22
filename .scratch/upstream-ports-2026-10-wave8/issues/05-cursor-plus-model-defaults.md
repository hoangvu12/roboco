# 05 — Cursor: send model defaults for plus subscriptions (upstream #920, `3b2f78027`)

**What to build:** Port `3b2f78027` (#920): the cursor adapter sends
every parameter the user left implicit as the account catalog's defaults
— on some plans (Plus) the server-side default is a variant the account
cannot use, so the adapter now sends explicit model defaults alongside
user selections.

**Why now:** roboco holds `harness/src/catalog.rs`, `cursor/mod.rs`,
`tests/cursor.rs` and the fake-cursor-shim fixture; the change is
self-contained adapter behavior with strong test coverage upstream
(+351).

**Steps:**
1. `git show 3b2f78027` — catalog.rs +10, cursor/mod.rs +96,
   tests/cursor.rs +190, `tests/fixtures/fake-cursor-shim.sh` +57.
2. Port the catalog defaults + the cursor adapter's parameter send, and
   the test suite with the shim fixture.
3. Adapt the "account catalog" reads to roboco's engine-local accounts
   model — no cloud account service; the tier-aware defaults come from
   whatever account state roboco's catalog tracks.

**Excluded upstream hunks:** `dist/macos/Info-dev.plist` (+2) and
`scripts/run-macos-dev.sh` (+1) — macOS dev-only, roboco keeps the files
but they are optional; take them only if they apply cleanly.

**Blocked by:** None.

**Status:** ready-for-agent

**Verification budget:** `cargo test -p roboco-harness -- cursor`.
Source: `.scratch/upstream-drift/2026-10-22.md` § account-adjacent pair.

- [ ] Catalog defaults + adapter send + tests + shim ported
- [ ] Account reads adapted to engine-local accounts
- [ ] Commit records upstream SHA `3b2f78027` (#920)

## Comments
