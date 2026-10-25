# 03 — Codex: npm optional-deps repair (upstream #969 slice, `d6849cee9`)

**What to build:** Port the portable slice of `d6849cee9`: new
`crates/harness/src/codex/npm_repair.rs` (+281) — before its first Codex
run, the engine checks that Codex starts, and if its native binary is
missing (npm install without optional dependencies) reinstalls the same
version with optional dependencies (passwordless sudo when the global
prefix is root-owned). Substrate for tickets 05/06/07 (they build on it).

**Excluded upstream hunks:** everything else in #969 — cloud engine-on-wake,
cloud project grouping (`engine/{credentials,spaces,workspace_host}.rs`),
`proto/view.rs`, `crates/client`.

**Blocked by:** none.

**Status:** ready-for-human

**Verification budget:** `cargo check -p roboco-harness` + codex tests.
Source: `.scratch/upstream-drift/2026-10-25.md` § A3.

- [x] `npm_repair.rs` ported + wired into the codex run path
- [x] Commit records upstream SHA `d6849cee9` (#969, slice)

## Comments

- **2026-10-25 — ported on `wave9/engine`.** `crates/harness/src/codex/npm_repair.rs`
  ported verbatim (281 lines, rebrand-neutral: no zeron identifiers in the
  file). Wiring adapted from upstream's cloud `CredentialBroker::prepare_run`
  (excluded) to Roboco's engine-local run path: `SessionsEngine`'s `Inner`
  gains a `codex_check: tokio::sync::Mutex<CodexCheck>`
  (Unchecked/Passed/FailedAt, retry after 10 minutes — upstream's
  semantics, including "later runs wait on a repair in progress" via the
  held mutex) and `drive_run` calls `ensure_codex_starts()` right before
  the first Codex `harness.run()` (after the execution lease, so
  concurrent Codex runs serialize behind a repair). The chat reads as
  Working while a repair runs, instead of blocking the dispatch RPC.
- **Verification:** `cargo test -p roboco-harness --lib codex` — 41 passed
  including the three ported npm_repair tests
  (`reinstalls_the_same_version_with_optional_dependencies`,
  `a_root_owned_prefix_retries_through_sudo`, `other_failures_are_left_alone`);
  `cargo check -p roboco-engine -p roboco-harness` clean (no new warnings);
  `cargo test -p roboco-engine --test e2e
  pending_update_does_not_block_dispatch_or_other_harnesses` passes — it
  dispatches to `HarnessId::Codex` through the new check (host codex is
  installed and healthy here, so the probe settles `NotNeeded`).
