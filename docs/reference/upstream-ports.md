# Porting selected upstream changes

Roboco uses the cherry-pick policy in [ADR 0003](../adr/0003-product-not-fork.md).
The mirror is a reference, never a source of merges into Roboco main.

## Refresh the pristine mirror

```sh
git fetch upstream
# First-time setup only:
git branch --track zeron/main upstream/main
# Subsequent refreshes, with zeron/main not checked out in any worktree:
git merge-base --is-ancestor zeron/main upstream/main
git branch -f zeron/main upstream/main
git diff --exit-code zeron/main upstream/main
git rev-parse --abbrev-ref zeron/main@{upstream}
```

Run the force-update only after the ancestry check succeeds. If it fails, inspect
upstream's rewritten history before replacing the mirror. In PowerShell, quote
`'zeron/main@{upstream}'`. The final checks must report no diff and `upstream/main`.
No Roboco commits belong on the mirror.

## Select and port

1. Inspect the upstream commit and its dependencies with `git show <sha>`.
2. Create a temporary worktree at its upstream parent (an ancestor of the mirror):
   `git worktree add ../zeron-port -b upstream-port/topic <sha>^`.
   Cherry-pick with `git -C ../zeron-port cherry-pick -x <sha>`. This reconstructs
   the change against pristine Zeron without changing the mirror. Check the
   resulting tree against `<sha>`; for several commits, start before the first
   and cherry-pick the selected dependency sequence.
3. Create a Roboco worktree from `main`:
   `git worktree add ../roboco-port -b port/topic main`.
4. Carry the change into that worktree. A `cherry-pick -x` can supply an initial
   patch; manually adapt renamed paths, crates, identifiers and environment
   variables using AGENTS.md. Exclude cloud/account/sync portions and iOS code
   (Swift, Xcode) and adapt retained behavior to engine-local RPCs. An upstream
   iOS UI change may instead be carried into `apps/android` as target behaviour
   ([ADR 0009](../adr/0009-android-client.md)); changes to `crates/mobile`,
   `crates/text` and `crates/markdown` port like any other crate. Resolve
   conflicts by intent, not by replacing complete Roboco files with upstream
   files.
5. Review `git diff` and run checks appropriate to the retained behavior. Record
   the upstream SHA and any deliberately excluded behavior in the port commit.
   Finish only when the patch preserves Roboco's architecture and checks pass.
6. Integrate the reviewed Roboco commit through the normal local issue workflow.
   Remove clean temporary worktrees; delete their temporary branches once the
   port commit or its evidence has been retained. The mirror stays unchanged.

A commit already present in Roboco needs no production port. A historical replay
may prepare a pre-change baseline on a throwaway branch to exercise the process;
that baseline and replay must never be integrated into main.
