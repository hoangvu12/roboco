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

**Status:** ready-for-agent

**Parent:** `.scratch/web-removal/spec.md`

- [ ] `cargo run -p roboco-theme --bin roboco-theme-export -- --check` passes against `crates/theme/artifact/`
- [ ] `cargo test -p roboco-theme` passes
- [ ] `cd apps/android && ./gradlew :app:testDebugUnitTest --tests '*ThemeArtifactPinTest*' --tests '*ProjectTonesPinTest*' --tests '*ProjectTileTest*' --tests '*PaletteValuesTest*'` passes
- [ ] `git grep -n "web/packages/theme" -- ':!web' ':!.scratch' ':!docs/research'` shows only historical comments (or nothing)
- [ ] `theme-artifact.yml` has one job. `android.yml` triggers on `crates/theme/**`.
- [ ] Simulated deletion: temporarily move `web/` aside and rerun the Android pin tests. They still pass. Restore before committing.

## Comments
