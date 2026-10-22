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

**Status:** done (ported in 4def484e4)

**Verification budget:** `cargo test -p roboco-harness -- cursor`.
Source: `.scratch/upstream-drift/2026-10-22.md` § account-adjacent pair.

- [x] Catalog defaults + adapter send + tests + shim ported
- [x] Account reads adapted to engine-local accounts
- [x] Commit records upstream SHA `3b2f78027` (#920)

## Comments

- 2026-10-24: Ported in 4def484e4. Carried `Catalog::cached_for`,
  `fill_catalog_defaults` (on `run` + steer reconfigure),
  `max_mode_default_ids`/`max_mode_defaults` (Max Mode default variants
  keep the bare id), all seven new tests and the shim fixture scenarios
  (model-default/override/legacy/switch/maxmode, grok-4.7 isMaxMode
  item; discovery count 2→3). "Account catalog" = the engine-local
  credential-scoped `models_cache` (catalog::Catalog +
  credential_context hash) — no adaptation needed, no cloud account
  service. The optional macOS dev hunks applied cleanly after the
  rebrand (`ROBOCO_AUTO_UPDATE=0` in Info-dev.plist + run-macos-dev.sh)
  and were taken. Drive-by rebrand fix in the same commit:
  `model_context` read unprefixed `CURSOR_SDK_SHIM_EXECUTABLE`; now
  `ROBOCO_CURSOR_SDK_SHIM_EXECUTABLE` (cache-key input only).
  Verification: `cargo test -p roboco-harness --test cursor` 18/18,
  `--lib catalog::` 20/20, `--test cursor_shim` 20/20.
