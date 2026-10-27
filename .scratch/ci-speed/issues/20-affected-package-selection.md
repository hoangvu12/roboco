# 20 — PRs build and test only affected packages (Zed's `rdeps` pattern)

**What to build:** A `changes` job (shared with ticket 08's) computes the workspace packages a PR affects. The Rust test jobs then narrow their `-p` set to those packages' reverse dependencies on PRs. Main, nightly and `ci:full` always run the full set. Research: `research-web-2026-10-28.md` §5.2.

Zed's `run_tests.yml` `orchestrate` job is the model:

1. `git diff --name-only $(git merge-base origin/$BASE HEAD) HEAD`.
2. Map `crates/<dir>` to package names via `cargo metadata --no-deps`.
3. Run everything if `rust-toolchain.toml`, `.cargo/`, `.github/` or `Cargo.(toml|lock)` changed, or if any changed path maps to no package.
4. Otherwise build a nextest filterset `rdeps(a)|rdeps(b)`.

Roboco's suites are **build**-dominated, so a filterset alone saves little: nextest still builds every selected package. Translate the affected set into the `-p` list. Compute reverse dependencies with `cargo metadata` (or `cargo tree -i <pkg> --workspace -e normal,dev` / `cargo-guppy`'s `determinator`, if a tool is preferred; pin it), then intersect with each job's package set.

- Non-crate inputs that must map to "run all", or to a named package:
  - `crates/ui/assets/**` → ui, android-assets
  - `adapters/roboco-tailcat/**` → mobile-client, engine
  - fixtures and scripts used by tests
  - anything read through `build.rs` (`ROBOCO_TAILCAT_EMBED`, web assets until removal)

  Keep that table in one script (`scripts/ci/affected.sh`) with a comment per row.
- When the narrowed set for a job is empty, the job reports success without building, as a required-check-friendly "skipped" (ticket 09's aggregate job).
- Output the chosen `-p` list in the job summary so a reviewer sees what did not run.

**Blocked by:** 04, 06 (final job graphs), 19 (fewer binaries shrink the win; measure after), 08 (reuse its `changes` job)

**Status:** needs-triage (value depends on how many PRs touch only leaf crates; measure that first from the last 50 merged PRs' file lists, `gh pr list --state merged --json files`)

**Parent:** `.scratch/ci-speed/spec.md`

- [ ] Analysis: share of the last 50 merged PRs whose affected set would skip ≥ 1 test package per job, with the estimated minutes saved (from ticket 18's per-crate timings). Go only if the expected saving is ≥ 3 min p50 on the critical path
- [ ] False-green guard: main runs the full set; any main failure in a package a PR skipped is logged under Comments with the missing mapping fixed
- [ ] Measured over ≥ 10 PR runs: Windows `tests` and Linux `core-tests` p50 for narrowed vs full PRs

## Comments
