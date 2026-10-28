# Roboco — fork workflow

Roboco (repo/project/binary: `roboco`, app display name: `Roboco`) is an independent product derived from [zeronsh/zeron](https://github.com/zeronsh/zeron), with native Windows support. [ADR 0003](docs/adr/0003-product-not-fork.md) governs selected upstream ports; [ADR 0004](docs/adr/0004-engine-local-data.md) keeps data engine-local. It also ships a native Android client on a shared Rust mobile core ([ADR 0009](docs/adr/0009-android-client.md)). Pairing a phone to any engine of a fleet introduces it to every member — reachability only, never data ([ADR 0011](docs/adr/0011-rooms.md) and its fleet amendment). The wasimysaid Kratos line lives separately in `../Kratos`.

## Remotes

- `origin` → `hoangvu12/roboco` — our direct fork of `zeronsh/zeron` (holds the open zeron PRs)
- `upstream` → `zeronsh/zeron`
- `kratos` → local `../Kratos` checkout (reference only)
- `rerere` is enabled — keep it that way; rebrand conflicts repeat and get auto-resolved.

## Porting from zeron

`zeron/main` is the pristine, un-renamed mirror of `upstream/main`. Keep it content-identical to upstream; use temporary branches for cherry-picks. Never merge upstream or the mirror into Roboco `main`.

Before refreshing the mirror or porting a commit, follow [the upstream port workflow](docs/reference/upstream-ports.md). Review the selected upstream change on a mirror-derived branch, then carry its intent into a Roboco branch by hand across the rebrand and removed cloud code. Keep `rerere` enabled.

Syncing runs as a weekly loop. `docs/reference/upstream-ledger.tsv` records a decision for every upstream commit. The tools are in `scripts/upstream/`: `triage.py` builds the weekly page, and `translate.py` does the rename-translated cherry-pick. Each port commit carries an `Upstream-Commit: <sha>` trailer. `scripts/upstream/rename-map.tsv` mirrors the map below; change both together.

Rename mapping for retained code:

- `zeron-*` crates / `zeron_*` libs -> `roboco-*` / `roboco_*`
- `apps/zeron/` -> `apps/roboco/`
- `ZERON_*` env vars -> `ROBOCO_*`
- `CURSOR_SDK_SHIM_EXECUTABLE` -> `ROBOCO_CURSOR_SDK_SHIM_EXECUTABLE` (upstream ships it unprefixed; docs/research stays verbatim)
- `sh.zeron.*` bundle ids -> `sh.roboco.*`, `zeron://` links -> `roboco://`
- `sh.zeron.android` -> `sh.roboco.android`. UniFFI namespace `zeron_core` -> `roboco_core` (Kotlin package `uniffi.roboco_core`); library `libzeron_mobile.so` -> `libroboco_mobile.so`
- `Zeron*` Kotlin/Swift type names (`ZeronApplication`, `ZeronTheme`, `ZeronIcons`, …) -> `Roboco*`
- `zeron-file:` mention links -> `roboco-file:` (`crates/proto/src/file_mentions.rs`)
- `-PzeronSkipCore` -> `-ProbocoSkipCore`; `ZERON_ANDROID_ABIS` -> `ROBOCO_ANDROID_ABIS`

Preserve the engine-local pairing architecture; upstream ports must not restore edge, WorkOS, sync rooms, or iOS (Swift, Xcode, Apple CI). Upstream iOS *behaviour* and the mobile crates (`crates/mobile`, `crates/text`, `crates/markdown`) may be carried into the Android app (`apps/android/`) as [ADR 0009](docs/adr/0009-android-client.md) describes.

## PRing to zeron

Never branch off roboco `main` for zeron PRs — it carries the rebrand. Branch off upstream:

```bash
git checkout -b fix/whatever upstream/main
# ... hack ...
git push origin fix/whatever
gh pr create -R zeronsh/zeron --base main --head hoangvu12:fix/whatever
```

`windows-native-support` on `origin` is the head of open PR #313 — do not delete or rebase casually.

## GPUI forks

GPUI comes from our forks, pinned by rev in the root `Cargo.toml`:

- `hoangvu12/zui` (rev `0966d065…`) — synced from upstream `zeronsh/zui` main via branch `roboco/sync-2026-10`. All fork work the pin used to carry is merged upstream (backdrop renderer `c2d273dc` via zui#10, Windows drag threshold, per-edge fade bands `667d0aa`), so the fork now mirrors upstream exactly; `0966d06` adds transformed/blurred monochrome glyphs.
- `hoangvu12/gpui-component` (rev `4764fd00…`) — mirrors upstream `zeronsh/gpui-component` pin commits, no fork work.

`gpui-component` still declares its gpui crates against `zeronsh/zui`, so the `[patch."https://github.com/zeronsh/zui"]` section redirects them to `hoangvu12/zui`. **Rule: the patch rev must always equal the top-level `gpui` pin rev**, otherwise you get two GPUI copies and ~50 type-mismatch errors. To bump: push/verify the rev exists in `hoangvu12/zui`, then update the pins and the patch revs together.

Custom gpui work goes on branches of `hoangvu12/zui` first, then gets pinned here by rev.

## Rebrand boundaries

Still zeron-branded on purpose:

- `zeronsh` org references and PR/issue links
- `docs/research/` — historical research notes
- `ZERON_GPU_STATS` — env var owned by the zui fork, not this repo
- Upstream paths quoted in `.scratch/android-app/` tickets (`apps/ios/Zeron/…`, `ZeronUITests`, `uniffi.zeron_core`): read them with `git show`. They are references, not names to reproduce.

## CI (Windows + Linux runners)

Android builds and tests run on these runners ([ADR 0009](docs/adr/0009-android-client.md)); there are no macOS jobs.

- `windows.yml` — Windows `tests` (one nextest build, on `windows-11-arm`) and `app` (x64: package, installer, startup) jobs (PR + push); nightly, `tests` again on x64 (`windows-2022`, cold, no cache save)
- `ui-tests.yml` — ubuntu, nextest on the dev profile, one build per graph: `core-tests` (`scripts/ci/test-core.sh`: harness, engine-local suites, sync, update, MCP, preview, doc, Android asset/xtask tooling), `ui-tests` (`roboco-ui` + the `roboco` CLI tests), `mobile-core`, `tailcat-adapter`; nightly `release-backstop` runs core + UI with `--release`. Not run for `apps/android/**`-only changes
- `web-codegen.yml` — ubuntu: `wiregen --check` wire-type freshness gate + `pnpm -r build` typecheck of `web/`
- `theme-artifact.yml` — web theme artifact freshness gate (`roboco-theme-export --check`) + `@roboco/theme` typecheck
- `web-engine-client.yml` — ubuntu: `@roboco/engine-client` vitest suites (codec, scripted fake server, conformance against the real `web_conformance` engine example)
- `android.yml` — two tiers, picked by its `changes` job (`scripts/ci/android-changes.sh`). Every run: `android (ubuntu-24.04)`, the debug APK (`apps/android`, cargo-ndk core) + JVM and buildSrc tests. PRs add `android-emulator` (ubuntu + KVM, API 36 pixel_7: the instrumented flow suite against the `android_fixture` engines via `apps/android/scripts/flow-tests.sh`) only when they touch what the APK or the rig is built from, and `android (windows-2022)` (the bash-free Gradle proof) only when they touch the Gradle host build or the xtask. The full tier — pushes to main, nightly, dispatch, or a PR labelled `ci:full` (add it, then re-run) — runs all of them plus an unsigned x86_64 `assembleRelease` R8 smoke (the signed APKs come from `release.yml`). Nightly and dispatch only: `android-emulator-tablet` (the tablet split shell and the `@Tour` motion recordings; `@PhoneOnly` tests skip there). An engine/harness-only PR does not trigger the workflow; dispatch it on the branch for the full Android tier.
- `release.yml` — tag `v*`: linux x86_64+aarch64 tarballs + windows portable zip + signed Android APKs (arm64-v8a, x86_64) → GitHub Release with `manifest.json` (updater checksums, APKs included; R8 mapping attached outside the manifest). No macOS/iOS/R2/Play.
- Keep CI focused on Roboco engine, desktop and Android builds, tests, and GitHub releases. Removed cloud and iOS deployment workflows stay outside upstream ports. Android has no Play Store, Firebase or other Google-services deployment.

## Naming conventions

Like zeron/Zeron: lowercase `roboco` for repo, crates, binary, package names, env prefix (`ROBOCO_`), deep link (`roboco://`); capitalized `Roboco` for app display name, window titles, UI strings, prose.

## Windows development

See `docs/reference/windows-development.md`. Env vars use the `ROBOCO_` prefix (e.g. `ROBOCO_DATA_DIR`). Builds run through sccache automatically; when creating a worktree, add its root to `basedirs` in `%APPDATA%\Mozilla\sccache\config\config` and restart the server (`sccache --stop-server; sccache --start-server`) or dependency cache hits drop sharply.

## Android development

See `docs/reference/android-development.md`. The app builds from Windows and Linux hosts with no bash on the build path: `cd apps/android && ./gradlew :app:assembleDebug :app:testDebugUnitTest`. The sccache note above applies unchanged — the Android core builds into the same workspace `target/`.

## Agent skills

### Issue tracker

Issues and specs live as local markdown under `.scratch/<feature-slug>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

The five canonical roles, label string equal to role name (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `CONTEXT.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.
