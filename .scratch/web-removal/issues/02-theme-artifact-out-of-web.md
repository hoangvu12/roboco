# 02 — Move the theme artifact out of `web/`

**What to build:** `roboco-theme-export` writes into `web/packages/theme/src/generated/` today. Three Android JVM tests read `artifact.json` from there, and `TestRepoRoot` uses that file to find the repo root. Move the artifact first so deleting `web/` (ticket 06) cannot break Android.

- `crates/theme/src/bin/roboco-theme-export.rs`: default `--out` becomes `crates/theme/artifact/` (`concat!(env!("CARGO_MANIFEST_DIR"), "/artifact")`). Update the doc comment ("Export the theme artifact … pinned by the Android palette tests").
- `crates/theme/src/artifact.rs` `render_files`: emit only `artifact.json`. Drop the web-only outputs (the TS wrapper, `fonts.css`, the Geist font copies / `FONT_FACES` table) unless something outside `web/` reads them. Check with `git grep`. Bump the artifact schema version only if `artifact.json`'s JSON shape changes.
- Generate the new file: `cargo run -p roboco-theme --bin roboco-theme-export`. Commit `crates/theme/artifact/artifact.json`. Leave `web/packages/theme` untouched in this commit; ticket 06 deletes it.
- `crates/theme/tests/artifact.rs`: point at the new path and file set.
- Android (`apps/android/app/src/test/java/sh/roboco/android/design/`): `TestRepoRoot.kt` anchors on `crates/theme/artifact/artifact.json` (code and KDoc), `ThemeArtifactPinTest.kt` reads it there, and `ProjectTonesPinTest.kt`, `ProjectTileTest.kt` and `PaletteValuesTest.kt` get their comments and paths updated if they name the old path.
- `.gitattributes`: move the artifact's eol rule to the new path.
- `.github/workflows/theme-artifact.yml`: keep the `export-check` job, delete the `typecheck` job, swap `web/packages/theme/**` for `crates/theme/artifact/**` in the path triggers, and rewrite the header comment ("Android's palette pin tests read the artifact").
- `.github/workflows/android.yml`: add `crates/theme/**` to both `paths` lists so an artifact change runs the pin tests.

**Blocked by:** 01

**Status:** done (Android pin tests run in CI only: no Android SDK on the implementing host)

**Parent:** `.scratch/web-removal/spec.md`

- [x] `cargo run -p roboco-theme --bin roboco-theme-export -- --check` passes against `crates/theme/artifact/`
- [x] `cargo test -p roboco-theme` passes
- [ ] `cd apps/android && ./gradlew :app:testDebugUnitTest --tests '*ThemeArtifactPinTest*' --tests '*ProjectTonesPinTest*' --tests '*ProjectTileTest*' --tests '*PaletteValuesTest*'` passes
- [x] `git grep -n "web/packages/theme" -- ':!web' ':!.scratch' ':!docs/research'` shows only historical comments (or nothing)
- [x] `theme-artifact.yml` has one job. `android.yml` triggers on `crates/theme/**`.
- [ ] Simulated deletion: temporarily move `web/` aside and rerun the Android pin tests. They still pass. Restore before committing.

## Comments

- 2026-10-28 (implementer): `roboco-theme-export` now writes only `crates/theme/artifact/artifact.json`. The TS wrapper (`index.ts`) and the `fonts` manifest / `FONT_FACES` table are gone: `git grep` found no reader outside `web/` (Android syncs its fonts from `crates/ui/assets/fonts/` through `FontsSync.kt`, and the `GeneratedAssetsTest` font check reads that copy). Dropping `fonts` changed the JSON shape, so `ARTIFACT_SCHEMA_VERSION` is 2 → 3. The rest of the JSON is byte-identical to the old `web/packages/theme/src/generated/artifact.json` (`git diff --no-index` shows only `schemaVersion` and the removed `fonts` array). The round-trip test `fonts_manifest_files_are_bundled` went with the manifest.
- `cargo run -p roboco-theme --bin roboco-theme-export -- --check` → "theme artifact is fresh (1 files in …crates/theme/artifact)". `cargo test -p roboco-theme` → 25 + 5 passed.
- `git grep -n "web/packages/theme" -- ':!web' ':!.scratch' ':!docs/research'` → empty.
- `theme-artifact.yml`: `typecheck` job deleted, header rewritten. The `web/packages/theme/**` path trigger was removed rather than swapped for `crates/theme/artifact/**`, because the existing `crates/theme/**` trigger already covers it. `android.yml` gains `crates/theme/**` in both `paths` lists. `.gitattributes`: the eol rule moved to `crates/theme/artifact/*`.
- Simulated deletion: with `web/` moved aside, `cargo test -p roboco-theme` and the `--check` gate still pass. The Kotlin pin tests (`ThemeArtifactPinTest`, `ProjectTonesPinTest`, `ProjectTileTest`, `PaletteValuesTest`) cannot run on this host (no Android SDK); they run in `android.yml` on the PR. `TestRepoRoot` and `ThemeArtifactPinTest` now name `crates/theme/artifact/artifact.json`; the other three name no path.
- `cargo +1.99.0 check --workspace --all-targets` clean (only the host's pre-existing warnings).
