# 07 — CLI updates through the real owner + known-bad list (upstream #978 part 1, `987bb2f87`)

**What to build:** Port the ownership/compatibility half of `987bb2f87`:
new `engine/src/harness_updates/ownership.rs` (+525) — an npm-packaged CLI
is updated by the package manager that installed it (npm into its exact
prefix with `--allow-scripts`, pnpm/bun/yarn/Volta; mise-managed and
node_modules copies left to the user with an explanation); new
`compatibility.rs` (+254) + built-in `compatibility.json` — per-CLI
known-bad releases are never offered or auto-installed; lazy harness
registry touches.

**Port note:** **drop the `{edge}/releases/harness-compatibility.json`
hourly fetch** — roboco has no edge; keep the built-in list as the only
source (upstream treats it as the offline fallback anyway).

**Excluded upstream hunks:** the edge fetch, everything Muse (ticket 08),
`crates/client/src/catalog.rs`, iOS files.

**Blocked by:** 05, 06.

**Status:** ready-for-agent

**Verification budget:** `cargo check -p roboco-engine` + harness_updates
tests. Source: `.scratch/upstream-drift/2026-10-25.md` § A7.

- [ ] Ownership detection + built-in compatibility policy ported, no edge fetch
- [ ] Commit records upstream SHA `987bb2f87` (#978, ownership+compat slice)

## Comments
