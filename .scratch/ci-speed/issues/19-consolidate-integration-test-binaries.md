# 19 — Consolidate integration-test binaries (engine, harness, mobile-client)

**What to build:** Each `tests/*.rs` file is its own test crate, statically linked against its package and the whole dependency graph. Counts:

| crate | `tests/*.rs` |
|---|---|
| `crates/engine` | **58** |
| `crates/harness` | **23** |
| `crates/mobile-client` | 10 |
| `crates/text` | 5 |
| `crates/preview` | 4 |
| `apps/roboco` | 4 |

CI links ≈ 30 engine test binaries on Linux and ≈ 7 on Windows (each with the full engine graph), plus ≈ 9 harness binaries.

Precedents for moving them into one binary per crate (modules instead of files): [matklad, "Delete Cargo Integration Tests"](https://matklad.github.io/2021/02/27/delete-cargo-integration-tests.html) (cargo's test build 3× faster), jj (test recompile 32–50 s → ≈ 4 s), codex-rs. Research: `research-web-2026-10-28.md` §2.3.

- Per crate: `tests/it/main.rs` with `mod engine_local; mod pi_resume; …`, the files moved to `tests/it/`, and in `Cargo.toml` `autotests = false` plus one `[[test]] name = "it" path = "tests/it/main.rs"`.
  - Unix-only or Windows-only files keep their gating as `#[cfg(unix)] mod x;`.
  - Shared helpers (`tests/common/…`) become a sibling module once.
- **Keep separate binaries where there is a reason**, each listed in the PR with that reason:
  - the live-agent suites that CI deliberately does not run (ticket 06: "listed, not globbed, to avoid compiling the unrun live-agent binaries"). Either keep them as their own `[[test]]` targets or mark the tests `#[ignore]`;
  - any test that needs `harness = false` or a custom `main`.
- CI filters change from `--test engine_local` to nextest filtersets, e.g. `-E 'binary(it) & test(/^engine_local::/)'`. Update `windows.yml`/`ui-tests.yml` (as restructured by 04/06), `.config/nextest.toml` test groups, and `docs/reference/*` that name `--test` targets.
- Process isolation: nextest runs every test in its own process, so env-var, cwd and global state cannot leak between former binaries in CI. Local `cargo test` would run them as threads in one process. Find tests that mutate process-global state (`std::env::set_var`, `set_current_dir`, global loggers), then either keep them in their own binary or document that the suite is run with nextest.
- Do one crate per commit (engine first: the largest), measuring each.

**Blocked by:** 04 and 06 (nextest everywhere, final job graph), 18 (go/no-go on the measured link share)

**Status:** ready-for-agent once 18 says go

**Parent:** `.scratch/ci-speed/spec.md`

Baseline: ticket 18's timings report (test-binary unit time per crate) and the 04/06 after-numbers for the test-build steps.

- [ ] Coverage list: `cargo nextest list` before/after has the same test names (prefixed by module), count equal; intentional exceptions named
- [ ] Every remaining separate `[[test]]` target has a one-line reason in `Cargo.toml`
- [ ] Measured: `cargo --timings` before/after for each consolidated crate (sum of its test units) and the Windows `tests` / Linux `core-tests` job p50 over ≥ 5 green runs (expect −2…−5 min Windows, −1…−3 Linux; record actuals)
- [ ] A local clean `cargo test -p roboco-engine` on Windows and Linux is no slower (developer loop check)

## Comments
