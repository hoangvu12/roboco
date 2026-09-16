# Roboco — fork workflow

Roboco (repo/project/binary: `roboco`, app display name: `Roboco`) is an independent product derived from [zeronsh/zeron](https://github.com/zeronsh/zeron), with native Windows support. [ADR 0003](docs/adr/0003-product-not-fork.md) governs selected upstream ports; [ADR 0004](docs/adr/0004-engine-local-data.md) keeps data engine-local. The wasimysaid Kratos line lives separately in `../Kratos`.

## Remotes

- `origin` → `hoangvu12/roboco` — our direct fork of `zeronsh/zeron` (holds the open zeron PRs)
- `upstream` → `zeronsh/zeron`
- `kratos` → local `../Kratos` checkout (reference only)
- `rerere` is enabled — keep it that way; rebrand conflicts repeat and get auto-resolved.

## Porting from zeron

`zeron/main` is the pristine, un-renamed mirror of `upstream/main`. Keep it content-identical to upstream; use temporary branches for cherry-picks. Never merge upstream or the mirror into Roboco `main`.

Before refreshing the mirror or porting a commit, follow [the upstream port workflow](docs/reference/upstream-ports.md). Review the selected upstream change on a mirror-derived branch, then carry its intent into a Roboco branch by hand across the rebrand and removed cloud code. Keep `rerere` enabled.

Rename mapping for retained code:

- `zeron-*` crates / `zeron_*` libs -> `roboco-*` / `roboco_*`
- `apps/zeron/` -> `apps/roboco/`
- `ZERON_*` env vars -> `ROBOCO_*`
- `sh.zeron.*` bundle ids -> `sh.roboco.*`, `zeron://` links -> `roboco://`

Preserve the engine-local pairing architecture; upstream ports must not restore edge, WorkOS, sync rooms, or iOS.

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

- `hoangvu12/zui` (rev `aa009411…`) — currently identical to `zeronsh/zui` main
- `hoangvu12/gpui-component` (rev `94c1bbaf…`)

`gpui-component` still declares its gpui crates against `zeronsh/zui`, so the `[patch."https://github.com/zeronsh/zui"]` section redirects them to `hoangvu12/zui`. **Rule: the patch rev must always equal the top-level `gpui` pin rev**, otherwise you get two GPUI copies and ~50 type-mismatch errors. To bump: push/verify the rev exists in `hoangvu12/zui`, then update the pins and the patch revs together.

Custom gpui work goes on branches of `hoangvu12/zui` first, then gets pinned here by rev.

## Rebrand boundaries

Still zeron-branded on purpose:

- `zeronsh` org references and PR/issue links
- `docs/research/` — historical research notes
- `ZERON_GPU_STATS` — env var owned by the zui fork, not this repo

## CI (Windows + Linux only)

- `windows.yml` — Windows tests (PR + push)
- `ui-tests.yml` — ubuntu jobs only (engine-local recovery, UI regressions, linux browser)
- `preview-tests.yml` — ubuntu (preview/proto tests)
- `release.yml` — tag `v*`: linux x86_64+aarch64 tarballs + windows portable zip → GitHub Release with `manifest.json` (updater checksums). No macOS/iOS/R2.
- Keep CI focused on Roboco engine/app builds, tests, and GitHub releases. Removed cloud and iOS deployment workflows stay outside upstream ports.

## Naming conventions

Like zeron/Zeron: lowercase `roboco` for repo, crates, binary, package names, env prefix (`ROBOCO_`), deep link (`roboco://`); capitalized `Roboco` for app display name, window titles, UI strings, prose.

## Windows development

See `docs/reference/windows-development.md`. Env vars use the `ROBOCO_` prefix (e.g. `ROBOCO_DATA_DIR`). Builds run through sccache automatically; when creating a worktree, add its root to `basedirs` in `%APPDATA%\Mozilla\sccache\config\config` and restart the server (`sccache --stop-server; sccache --start-server`) or dependency cache hits drop sharply.

## Agent skills

### Issue tracker

Issues and specs live as local markdown under `.scratch/<feature-slug>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

The five canonical roles, label string equal to role name (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `CONTEXT.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.
