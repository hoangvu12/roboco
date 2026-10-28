# 01 — Tag `web-final` and cut the removal branch

**What to build:** The safety net and the workspace. This ticket deletes nothing.

- Wait for both of these: the user has said go, and v0.14.0 has published (release workflow green, GitHub Release live). Do not touch GitHub before then.
- `git fetch origin`. Cut `feature/web-removal` from `origin/main` (the merge base everyone agrees on). Record that sha in a comment below.
- `git tag -a web-final <that sha> -m "Last commit with the web client, the browser plumbing and the desktop in-app browser"`. Push the tag (`git push origin web-final`) only on the user's go. The tag is outward-facing.
- If working in a worktree: add its root to sccache `basedirs` (`%APPDATA%\Mozilla\sccache\config\config`) and restart the server, per AGENTS.md.
- Sanity-check that the tag carries everything ADR 0012 will point at: `git show web-final:web/package.json`, `git show web-final:crates/engine/src/tailcat_relay.rs`, `git show web-final:crates/ui/src/browser/mod.rs`.

**Blocked by:** none (gated on the user's go and the v0.14.0 release)

**Status:** done

**Parent:** `.scratch/web-removal/spec.md`

- [x] v0.14.0 is published. The user has said go.
- [x] `feature/web-removal` is cut from `origin/main`. Sha recorded below.
- [x] Annotated tag `web-final` exists on that sha and is pushed to `origin`.
- [x] The three `git show web-final:…` checks succeed.
- [x] Worktree (if any) is registered in sccache `basedirs`.

## Comments

- 2026-10-28 (orchestrator, recorded by the implementer): the user said go. `feature/web-removal` was cut from `origin/main` = `6126c05d686b2a81d3acc1a3a93fdc15d913ceee` in the worktree `roboco-web-removal`. Annotated tag `web-final` (tag object `11362242760084b915f9552051ac550d6e147055`) points at that commit and is pushed to `origin` (`git ls-remote origin refs/tags/web-final` returns it). `git show web-final:web/package.json`, `git show web-final:crates/engine/src/tailcat_relay.rs` and `git show web-final:crates/ui/src/browser/mod.rs` all succeed. The worktree root is in sccache `basedirs`; the server restart was deferred because other builds were running on the host.
