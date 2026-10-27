# 10 — Pin every action to a commit SHA, Dependabot, bump the `@v4` actions

**What to build:** Carry upstream `56dc5ff3a` by hand, plus drift report §D's "`actions/*@v4` → current before the Node 20 removal" (#976).

- Every `uses:` in `.github/workflows/*.yml` and `.github/actions/*/action.yml` names a full commit SHA with the version in a trailing comment (`# v4.4.0`). This covers `actions/*`, `Swatinem/rust-cache`, `taiki-e/install-action`, `gradle/actions/*`, `reactivecircus/android-emulator-runner`, `softprops/action-gh-release`, and anything 04/05/08 add (`samypr100/setup-dev-drive`, paths-filter).
- Before pinning, move `actions/checkout`, `upload-artifact`, `download-artifact`, `cache`, `setup-java`, `setup-go` and `setup-node` (if any is left after web removal) to their **current** majors, checking at implementation time which majors run on a supported Node runtime. Read each changelog for breaking input changes. `download-artifact` `merge-multiple`/`pattern` in `release.yml` and `upload-artifact` hidden-file defaults are the likely ones.
- `.github/dependabot.yml`: `github-actions` ecosystem, weekly, one grouped PR. Optionally also `cargo`/`gradle`; ask the user, since Roboco pins gpui by rev.
- Matters most for `release.yml`, which runs next to the Android signing secrets.

**Blocked by:** 04, 06, 08, and the **web-removal PR** (`.scratch/web-removal/`). Pin the final set of workflows once, rather than pinning `web-codegen.yml`/`web-engine-client.yml`/`setup-node` that are about to be deleted.

**Status:** ready-for-agent

**Parent:** `.scratch/ci-speed/spec.md`

- [ ] `grep -rn "uses:" .github | grep -v "@[0-9a-f]\{40\}"` returns only local `./.github/actions/*` references
- [ ] Dependabot config validates (Insights → Dependency graph → Dependabot shows it) and opens its first grouped PR
- [ ] One full-tier run (main push) and one `release.yml` `workflow_dispatch` green after the bump
- [ ] Measured before/after: no job p50 regresses by > 1 min (pinning is not a speed change; this guards the major bumps)

## Comments

**2026-10-28 (web research round 2):**

- Dependabot has a `rust-toolchain` ecosystem (GitHub changelog 2025-08-19). Ticket 12 adds it when it pins the toolchain; keep one `dependabot.yml`.
- The emulator runner's README (v2.38.0) now shows `gradle/actions/setup-gradle@v5` and `actions/cache@v5`. Use them as current-major references when bumping. Ticket 13 removes the AVD `actions/cache` steps, so pin what remains after it.
- New actions the later tickets may add: `taiki-e/install-action` with `cargo-binstall` (17). Container jobs (21) use no new action.
