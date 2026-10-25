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

**Status:** ready-for-agent

**Verification budget:** `cargo check -p roboco-harness` + codex tests.
Source: `.scratch/upstream-drift/2026-10-25.md` § A3.

- [ ] `npm_repair.rs` ported + wired into the codex run path
- [ ] Commit records upstream SHA `d6849cee9` (#969, slice)

## Comments
