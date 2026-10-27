# 01 — Tag `web-final` and cut the removal branch

**What to build:** The safety net and the workspace. This ticket deletes nothing.

- Wait for both of these: the user has said go, and v0.14.0 has published (release workflow green, GitHub Release live). Do not touch GitHub before then.
- `git fetch origin`. Cut `feature/web-removal` from `origin/main` (the merge base everyone agrees on). Record that sha in a comment below.
- `git tag -a web-final <that sha> -m "Last commit with the web client, the browser plumbing and the desktop in-app browser"`. Push the tag (`git push origin web-final`) only on the user's go. The tag is outward-facing.
- If working in a worktree: add its root to sccache `basedirs` (`%APPDATA%\Mozilla\sccache\config\config`) and restart the server, per AGENTS.md.
- Sanity-check that the tag carries everything ADR 0012 will point at: `git show web-final:web/package.json`, `git show web-final:crates/engine/src/tailcat_relay.rs`, `git show web-final:crates/ui/src/browser/mod.rs`.

**Blocked by:** none (gated on the user's go and the v0.14.0 release)

**Status:** ready-for-human

**Parent:** `.scratch/web-removal/spec.md`

- [ ] v0.14.0 is published. The user has said go.
- [ ] `feature/web-removal` is cut from `origin/main`. Sha recorded below.
- [ ] Annotated tag `web-final` exists on that sha and is pushed to `origin`.
- [ ] The three `git show web-final:…` checks succeed.
- [ ] Worktree (if any) is registered in sccache `basedirs`.

## Comments
