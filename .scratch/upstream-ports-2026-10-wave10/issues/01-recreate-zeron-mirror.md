# 01 — Recreate the pristine `zeron/main` mirror

**Lane:** 0 Setup. **Effort:** XS.

**What to build:** The local `zeron/main` branch no longer exists (the
audit header noted it). Recreate it with the first-time setup in
`docs/reference/upstream-ports.md` before any wave-10 port:

```sh
git fetch upstream
git branch --track zeron/main upstream/main
git diff --exit-code zeron/main upstream/main
git rev-parse --abbrev-ref 'zeron/main@{upstream}'   # → upstream/main
```

Then:
- Record the mirror tip in a comment here. The audit's window ends at
  `31d26eeb6` (v0.2.127). If upstream has moved past it, list the new
  commits (`git log --oneline 31d26eeb6..zeron/main`) in the comment
  and stop there. A newer window needs its own drift review before
  anything from it ports. Wave 10 doesn't take it silently.
- Confirm `git config rerere.enabled` is `true` (AGENTS.md: keep it
  that way).
- No Roboco commits on the mirror. Don't check it out in any worktree.
  Port branches start at `<sha>^` in a temporary worktree, as the
  workflow doc says.

**Upstream:** none (branch bookkeeping).

**Excluded:** n/a. **Proto change:** no.

**Blocked by:** none. Every other port ticket assumes this one landed.

**Status:** ready-for-agent

**Source:** `.scratch/upstream-drift/2026-10-28.md` header ("Mirror
note"); `docs/reference/upstream-ports.md` § Refresh the pristine mirror.

- [ ] `zeron/main` exists, tracks `upstream/main`, no diff against it
- [ ] Mirror tip recorded; any commits after `31d26eeb6` listed for a
      follow-up drift review
- [ ] `rerere.enabled` = true
- [ ] Nothing committed or checked out on the mirror

## Comments
