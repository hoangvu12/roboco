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

**Status:** ready-for-human

**Verification budget:** `cargo check -p roboco-engine` + harness_updates
tests. Source: `.scratch/upstream-drift/2026-10-25.md` § A7.

- [x] Ownership detection + built-in compatibility policy ported, no edge fetch
- [x] Commit records upstream SHA `987bb2f87` (#978, ownership+compat slice)

## Comments

- **2026-10-25 — ported on `wave9/engine`.**
  - `harness_updates/ownership.rs` (new, 525): who owns an installed CLI,
    judged from where its launcher resolves and lives — npm into the exact
    prefix it lives in (that prefix's own npm, `--allow-scripts`), pnpm
    (approving builds on 10.4+), bun, yarn, Volta; mise-managed installs
    (shim/install/wrapper, incl. Homebrew's mise) and project `node_modules`
    copies are left to the user with the reason.
  - `harness_updates/compatibility.rs` (new, 254) + built-in
    `compatibility.json`: per-CLI known-bad release policy — a held-back
    release is never reported available (so neither Update nor an automatic
    install reaches it), an explicit apply at one is refused, and an
    installed one is flagged in Settings with the release to use. The
    **edge fetch is dropped by design** (roboco has no edge, ADR 0003/0004):
    the built-in list is the only source, exactly upstream's offline
    fallback. No `set_compatibility_feed`, no cache, no hourly refresh.
  - Wiring: `UpdatePlan::Package` (owner + launcher + package + tag) with
    the pnpm `--version` probe and `package_update` install arm;
    `PlanContext` threads the installed version (OpenCode's package
    generation) and Claude's dist-tag; `update_plan` refuses
    mise-managed/project installs with the owner's instruction;\    `manual_update_command` says who owns an install instead of a command
    that would update some other copy; `apply` refuses a held-back release,
    re-checks when the checked binary's canonical path changed, and
    Homebrew no longer claims a mise keg; `apply_compatibility` folds the
    policy into every checked row (even a failed check keeps the installed
    verdict).
  - proto: `HarnessUpdateStatus.compatibility`/`held_back` (additive,
    serde-defaulted) + `HarnessCompatibility`/`HarnessCompatibilityStatus`
    — **wire: regenerated** (`HarnessCompatibility{,Status}.ts` +
    `HarnessUpdateStatus.ts`), `wiregen -- --check` fresh. (The wave spec's
    wire list missed #978's proto slice; the gate would have caught it.)
  - ui: Settings renders the compatibility verdict (danger tone, above
    every phase but an update in flight) and a held-back release ("held
    back: not supported yet").
  - engine deps: `semver = "1"`.
- **Verification:** `cargo test -p roboco-engine --lib harness_updates` 44
  passed (incl. the 4 new compatibility tests, ported verbatim after my
  first paraphrased test-feed transcription failed on semver's caret
  semantics for bare ranges); `--lib ownership` 4 passed;
  `cargo check -p roboco-engine` clean; `wiregen --check` fresh.
- **Deliberately excluded:** the `{edge}/releases/harness-compatibility.json`
  hourly fetch + offline cache (no edge), everything Muse (ticket 08,
  declined: `muse/*` harness + accounts, `HarnessId::Muse`, PlanUsage,
  registry lazy-descriptor/RunEnvironment wiring, skills roots, marks),
  `crates/client/src/catalog.rs`, iOS files.
