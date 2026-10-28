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

**Status:** done (local packaging and the Linux/Android legs run in CI; see Comments)

**Parent:** `.scratch/web-removal/spec.md`

- [x] `web/` is gone. The two web workflows are gone.
- [x] `release.yml`, `windows.yml`, `ui-tests.yml` and both packaging scripts contain no Node/pnpm/web-build steps and no `ROBOCO_WEB_DIST`
- [ ] `./scripts/package-windows.ps1 -ReleasesUrl <url>` produces the portable zip locally with Node off `PATH` (Windows host). `scripts/package-linux.sh` runs the same way on a Linux host or in CI.
- [x] `actionlint` (or a careful read) passes on every edited workflow. No job references a deleted file, test or example.
- [ ] Android unit tests still pass with `web/` gone (re-run of ticket 02's pin tests)
- [ ] `cargo build --release -p roboco` passes on a clean checkout with no Node installed

## Comments

- 2026-10-28 (implementer): `git rm -r web/` (1,437 files, including `web/.gitattributes`, `pnpm-lock.yaml` and `pnpm-workspace.yaml`). `web-codegen.yml` and `web-engine-client.yml` are deleted. `release.yml` loses both jobs' Node 24 / corepack / `pnpm install` / `@roboco/app` build steps and the header paragraph that explained them; the WebKitGTK apt packages stay until 08. `windows.yml` and `ui-tests.yml` lose the `ROBOCO_WEB_DIST` env (ui-tests' whole `env:` block, which held only that variable) and its comment, and windows.yml drops `-SkipWebBuild`. `package-windows.ps1` loses `-SkipWebBuild` and the pnpm block. `package-linux.sh` loses `SKIP_WEB_BUILD` and the pnpm block. `generate-app-icons.py` no longer writes the favicon (`rounded()` stays; the other icons use it). The root `.gitattributes` holds no web rules after 02.
- `git grep -I -n -i "pnpm\|corepack\|setup-node\|ROBOCO_WEB_DIST\|SkipWebBuild\|SKIP_WEB_BUILD\|web/packages" -- .github scripts Cargo.toml '*.toml'` → empty. `web/packages/…` survives only in reference comments under `crates/` and `apps/android/` (spec: they resolve at `web-final`), and nothing that would execute.
- Workflow read (no `actionlint` on this host): `ls .github/workflows` → android, preview-tests, release, theme-artifact, ui-tests, windows. No remaining job names `web/`, `room_relay`, `web_conformance`, `web_smoke`, `wiregen` or `assets/web` (`git grep` over `.github`).
- `cargo +1.99.0 check --workspace --all-targets` is clean, and `cargo test -p roboco-theme` passes with `web/` gone.
- **Not run on this host:** `./scripts/package-windows.ps1`. It needs PowerShell 7 (`Set-Content -Encoding utf8NoBOM`) and Inno Setup, and this box has neither (only Windows PowerShell 5.1). I did not install them. The `windows.yml` "Verify portable and installer packaging" step runs the script on the PR with no Node step left. The Node-free release build itself is verified at the branch head in ticket 11. `package-linux.sh` and the Android pin tests run in CI (`release.yml` dry run only on the user's word; `android.yml` on the PR).
