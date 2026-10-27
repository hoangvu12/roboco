# 06 — Delete `web/`, the web CI workflows, and Node/pnpm from release and packaging

**What to build:** The tree, CI and release pipeline stop knowing a web client existed.

- `git rm -r web/` (about 1,437 files, including `pnpm-lock.yaml`, `pnpm-workspace.yaml` and `.gitattributes` under `web/`).
- Delete `.github/workflows/web-codegen.yml` and `.github/workflows/web-engine-client.yml`.
- `.github/workflows/release.yml`: in both the linux and windows jobs, remove the Node 24 setup, corepack/pnpm, `pnpm install` and the `@roboco/app` build, plus the header comment that explains them. Keep `libwebkit2gtk-4.1-dev libjson-glib-dev` in the apt line until ticket 08, which removes the helper that needs them.
- `.github/workflows/windows.yml` and `ui-tests.yml`: remove the `ROBOCO_WEB_DIST` env block and its comment. Drop `-SkipWebBuild` from the `package-windows.ps1` step and its comment (`windows.yml` ~88-91).
- `scripts/package-windows.ps1`: remove the `-SkipWebBuild` parameter and the pnpm install/build. `scripts/package-linux.sh`: remove `SKIP_WEB_BUILD` and the pnpm steps. `scripts/generate-app-icons.py`: stop writing the web favicon.
- Root `.gitattributes`: remove web-only rules.
- `git grep -n -i "pnpm\|corepack\|setup-node\|ROBOCO_WEB_DIST\|SkipWebBuild\|SKIP_WEB_BUILD\|web/packages"` over `.github scripts crates apps Cargo.toml` → only historical comments may remain (see the spec's reference-comment decision). Anything that would *execute* is gone.

**Blocked by:** 05

**Status:** ready-for-agent

**Parent:** `.scratch/web-removal/spec.md`

- [ ] `web/` is gone. The two web workflows are gone.
- [ ] `release.yml`, `windows.yml`, `ui-tests.yml` and both packaging scripts contain no Node/pnpm/web-build steps and no `ROBOCO_WEB_DIST`
- [ ] `./scripts/package-windows.ps1 -ReleasesUrl <url>` produces the portable zip locally with Node off `PATH` (Windows host). `scripts/package-linux.sh` runs the same way on a Linux host or in CI.
- [ ] `actionlint` (or a careful read) passes on every edited workflow. No job references a deleted file, test or example.
- [ ] Android unit tests still pass with `web/` gone (re-run of ticket 02's pin tests)
- [ ] `cargo build --release -p roboco` passes on a clean checkout with no Node installed

## Comments
