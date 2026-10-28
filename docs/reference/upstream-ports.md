# Porting selected upstream changes

Roboco uses the cherry-pick policy in [ADR 0003](../adr/0003-product-not-fork.md):
selected upstream commits are ported by hand-reviewed cherry-picks, never by
merging or rebasing on upstream. The `zeron/main` mirror is a reference, never
a source of merges into Roboco main, and nothing is ever committed to it.

Since wave 10, syncing is a **weekly loop over a permanent ledger**: each
upstream commit is decided once and recorded, and each port starts from a
rename-translated merge, not a blank branch. The research behind this is
`.scratch/upstream-sync-research/report-2026-10-28.md`.

## What never ports

- Cloud: `edge/`, WorkOS, accounts, billing, sync rooms, Cloud machines, and
  their slices in shared crates. Roboco's data is engine-local
  ([ADR 0004](../adr/0004-engine-local-data.md)) and pairing is direct
  ([ADR 0011](../adr/0011-rooms.md)).
- iOS code (Swift, Xcode, Apple CI) and upstream's own `apps/android`
  (reference only). An iPhone *behaviour* may still be carried into
  `apps/android` ([ADR 0009](../adr/0009-android-client.md)), so iOS-only
  commits go to the Android lane, never to auto-skip. `crates/mobile`,
  `crates/text` and `crates/markdown` port like any other crate.
- The website (`apps/landing`, `apps/www-redirect`), upstream release bumps,
  macOS-only code, translated READMEs, and recorded declines (ledger rows with
  status `declined`).

`scripts/upstream/path-rules.tsv` encodes the path part of this list.

## Records and tools

| File | What |
|---|---|
| `docs/reference/upstream-ledger.tsv` | One row per upstream commit since the fork point `b765f6ffb`, with its decision. The source of truth: nobody re-audits what it records. |
| `docs/reference/upstream-prs.tsv` | Open and closed-unmerged upstream PRs the wave-10 audit tracked, with their decision. |
| `scripts/upstream/rename-map.tsv` | The AGENTS.md rename map as data, used by every script. Change it together with AGENTS.md; a unit test checks every AGENTS.md pair. |
| `scripts/upstream/path-rules.tsv` | Auto-skip paths, the iOS → Android-lane rule, version-bump subjects. |
| `scripts/upstream/translate.py` | The translated cherry-pick: maps paths and names, then 3-way merges each file onto a Roboco rev. It reports CLEAN, TRIVIAL or REAL per commit and a state per file. `--apply --worktree` writes the merge, and `--commit` commits a CLEAN one with the trailer. Its passes (residual mode, the `Cargo.lock` rule, mergiraf, conflict hints) are described [below](#translatepy-passes); they live in `residual.py`, `lockfile.py`, `mergiraf.py` and `annotate.py`. |
| `scripts/upstream/presence.py` | "Is this already in Roboco?": the share of a commit's translated added lines found in Roboco. |
| `scripts/upstream/triage.py` | `scan` (the weekly triage page), `apply` (page decisions → ledger), `sync` (trailers → `ported`), `mark`, `check-rules`, `stats`. |

The scripts are Python 3 with the standard library only. They run the same on
Windows and Linux, with no bash. Anything that writes is a dry run until you
pass `--write`, or `--apply` for `translate.py`. `translate.py` refuses to write
into a worktree on `zeron/main` or on any upstream commit. Tests:
`python -m unittest discover scripts/upstream/tests` (they need `git`, not
mergiraf or cargo; the one test that runs a real mergiraf skips without it).

### translate.py passes

The research behind these is
`.scratch/upstream-sync-research/report-2026-10-29-less-recoding.md`. None of
them makes a REAL port mechanical; they shrink it and say what is left.

**`Cargo.lock`: regenerate, never merge.** Always on. When Roboco's lock
differs from upstream's, the file is kept as Roboco's (state `REGENERATE`,
class at least TRIVIAL) and upstream's change becomes a plan: the registry
crates upstream bumped, as `cargo update -p <name>@<old> --precise <new>`
(only where Roboco is still on upstream's old version), after one
`cargo metadata --format-version 1` that settles the lock against the merged
manifests. Git pins (the GPUI forks) are listed, never updated: Roboco pins
its own forks. `--apply --refresh-lock` runs the plan in the worktree (it
needs cargo and the registry index); otherwise run the printed commands
before committing. CI builds Android with `--locked`, so a stale lock fails.

**Residual mode: skip what Roboco already has.** On by default
(`--no-residual` turns it off for a plain translated cherry-pick). For
remainders of earlier partial ports (ledger notes like "audit PORTED (sha);
residual slice"), replaying the whole commit re-conflicts on everything
already ported. Residual mode splits each conflicted file's upstream change
into hunks and reverts the ones Roboco provably carries before merging. Its
worst case is the plain merge. It is conservative:

- a hunk is skipped only when every significant line it adds is in the Roboco
  file, as many times as upstream has it after the commit, and every
  significant line it removes is gone. A line repeated elsewhere in the file
  can't vouch for a second copy, so a new enum variant or a second `#[test]`
  is never skipped because its neighbours exist;
- a file keeps the plain merge when the residual one has more conflict
  regions (or as many and more conflict lines);
- every skipped hunk is listed in the report (upstream line range and first
  line). Read the list: a hunk that matches by text can still sit in a
  different function in Roboco.

`--residual-threshold 0.8` (opt-in) skips a hunk when 80% of its lines are
present (the research prototype's rule). It removes about twice as many regions, but it
drops lines Roboco doesn't have: the report lists them as `lacking`, and
`--commit` refuses such a result. Measured on the wave-10 conflict set
(414 files, 1,816 regions): strict 1,579 regions and nothing dropped without
evidence; 0.8 gives 1,358 regions but 571 unverified lines.

Where a hunk was skipped, the upstream side of a remaining conflict shows the
upstream^ text there. Don't take upstream's side of such a region wholesale.

**Possible duplicates in clean merges.** Always on, report-only. When Roboco
ported an upstream change somewhere else in a file, git can merge upstream's
copy cleanly beside it, and nothing conflicts. For every file git merged
cleanly, a hunk is flagged as `possible duplicate` when Roboco already has
all of it (the strict rule above) and the merge leaves more copies of its
lines than either side has. Check each one and delete the extra copy. The
flag never changes the merge, the class or `--commit`. Example: `9601a2440`
merges a second `*scenario:image-*)` arm into
`crates/harness/tests/fixtures/fake-codex.sh`.

**`--mergiraf [PATH]`: optional structural fallback.** Opt-in, never
required: [mergiraf](https://mergiraf.org) is an external, pre-1.0, GPL-3
binary that the scripts never vendor or install. With the flag (or
`ROBOCO_MERGIRAF=<path to the binary>` in the environment; `--no-mergiraf`
overrides it), each file still conflicted after the other passes, in Rust,
TOML or Kotlin, is merged again by mergiraf. Its result is used only when it
resolves the whole file; a partial result, crash or timeout
(`--mergiraf-timeout`, default 300 s per file) keeps git's conflict. Files it
resolved show as `CLEAN (mergiraf)`, are listed in the report, and `--commit`
won't commit them unreviewed: read each one and build. To install it, take
the release archive for your platform from
<https://codeberg.org/mergiraf/mergiraf/releases> (or
`cargo install --locked mergiraf`), unpack it outside the repo, and pass its
path or put it on `PATH`. 0.20.0 is the version measured.

**Conflict hints.** Each conflict region left gets one line saying why it
conflicts and what to do, from the three sides, blame on the upstream file at
`<sha>^`, and the ledger at the target rev:

| Hint | Meaning | Action |
|---|---|---|
| `already` | upstream's new lines here are all in the Roboco file | keep ours |
| `prerequisite` | upstream^ code here comes from an upstream commit the ledger hasn't ported | port that commit first (or include it), then re-run |
| `removed` | upstream's side carries cloud (edge, WorkOS, sync rooms), iOS or in-app browser code, or code from a skipped/declined/superseded commit | drop upstream's side |
| `rename` | the sides differ only by the zeron → roboco rename | keep ours |
| `ported` | the upstream^ code came from a commit Roboco ported differently | keep ours, re-apply upstream's change by intent |
| `prefork` | Roboco rewrote this pre-fork upstream code | keep ours, re-apply upstream's change by intent |
| `roboco-only` | Roboco-only lines sit where upstream changed the code | keep them and carry upstream's diff over |
| `lacked` | upstream^ code Roboco lacks, origin unknown | check for a missing prerequisite (`presence.py`, `git log -S`) |
| `drift` | both sides carry the same code, ordered or formatted apart | either side |

Hints are advice, not proof: moved code blames wrong, and the evidence
(commits, line counts) is printed next to each one. `--annotate` prints them
in the report; `--no-blame` skips blame (faster, no `prerequisite` or
`ported` hints).

**The sidecar report.** `--apply` writes `<sha>.md` and `<sha>.json` to the
worktree's git dir (`git rev-parse --git-path roboco-translate`, for example
`.git/worktrees/<name>/roboco-translate/`), never into the tree, so it can't be
committed by accident and the merged files carry only git's own markers. It
holds the hints per region (lines refer to the file as written), the skipped
residual hunks, the possible duplicates, the mergiraf files, the `Cargo.lock`
plan and the files with no Roboco target. `--report-dir DIR` writes it elsewhere, also without `--apply`.
A committed CLEAN result writes no report.

### Ledger format

Tab-separated, one header row, LF line endings, rows in upstream history order:

| Column | Content |
|---|---|
| `upstream_sha` | full 40-char upstream sha |
| `date` | upstream author date |
| `pr` | upstream PR (`#1067`), from `(#N)` in the subject or the merge that brought it; empty for direct pushes |
| `subject` | upstream subject |
| `status` | the decision, below |
| `audit` | where the decision came from: the wave-10 audit class and part (`PORTED p03`, `MISSING late`), or `triage <date>` |
| `note` | one short line: what's left, why it was skipped, a port note |

Statuses:

- `ported <roboco sha>`: in Roboco; the sha is the port commit. A bare
  `ported` means the code is present but no port commit names the upstream sha.
- `skipped:<reason>`: policy exclusion. Reasons include `landing`, `cloud`,
  `ios`, `upstream-android`, `upstream-ci`, `macos-only`, `docs-only`,
  `version-bump`, `declined`, `n/a`, or a reason given at triage.
- `superseded`: a later upstream commit, or Roboco's own design, replaced it.
- `declined`: a recorded user decision.
- `ticketed <spec>-<NN>[,<NN>]`: open work in a ticket, for example
  `ticketed wave10-27` (`.scratch/upstream-ports-2026-10-wave10/issues/27-*.md`),
  `ticketed ci-speed-03`, or `ticketed upstream-sync-04`. Audit rows that
  were PORTED, but whose ticket carries a residual slice, are ticketed too.
  The note names the port commit.
- `missing` / `partial`: portable, not (fully) in Roboco, no ticket yet.
- `android-lane`: iOS-only; waiting for an Android-parity decision.
- `new`: recorded by `triage.py scan --write`, not decided yet.
- `later`: looked at, deferred; it reappears on the next triage page.
- `queued <batch>`: taken for that week's batch; `sync` flips it to `ported`.

`upstream-prs.tsv` has `pr`, `state` (`open`/`closed`), `date`, `title`,
`status`, `audit` and `note`. Its statuses are those above, plus `watch` (an
open PR with no decision; revisit it if it merges) and `already` (Roboco has
the behaviour). When a tracked PR merges, its commits show up in the weekly
scan, and the page's PR heading repeats the PR's earlier status.

## The weekly loop

Roughly 95 upstream commits land a week. After path rules, the ledger and
presence, only the uncertain ones need a person.

1. **Refresh the mirror** (next section). Never commit to `zeron/main`.
2. **Triage.** From an up-to-date Roboco checkout:
   ```sh
   python scripts/upstream/triage.py scan --out .scratch/upstream-sync/2026-11-04.md --write
   ```
   This lists every upstream commit after the fork point that the ledger
   lacks, plus rows still `new`, `later` or `android-lane`. It routes each one:
   auto-skip by path or subject (recorded as `skipped:<reason>`), iOS-only to
   the Android lane, and everything else to review. Each review commit gets a
   presence score and a translated cherry-pick onto `main`. The page groups
   them by upstream PR, with the CLEAN/TRIVIAL/REAL class, conflicted and
   missing files, and any earlier decision on the PR.
3. **The user marks the page.** Each decision cell gets `take`,
   `skip:<reason>`, `decline`, `later` or `ported <sha>`, plus an optional
   note. Then run
   `python scripts/upstream/triage.py apply .scratch/upstream-sync/2026-11-04.md --write`.
   `take` becomes `queued 2026-11-04`.
4. **One batch branch** for the week, from `main`, in its own worktree:
   `git worktree add ../roboco-sync -b sync/2026-11-04 origin/main`.
5. **CLEAN and TRIVIAL takes: one commit each, no ticket.**
   ```sh
   python scripts/upstream/translate.py <sha> --apply --worktree ../roboco-sync --commit
   ```
   This commits a CLEAN result as `<subject> (zeron#N)` with an
   `Upstream-Commit: <full sha>` trailer. A TRIVIAL result is written with
   `diff3` conflict markers: resolve them, review, and commit with the
   message the script prints. The trailer and the ledger row are the whole
   record. Several CLEAN shas can be passed at once; the run stops at the
   first one that isn't CLEAN. "Clean" means the text merged, not that it
   builds: build and test the batch. A commit whose only leftover is
   `Cargo.lock` is TRIVIAL; `--refresh-lock` regenerates the lock with cargo,
   and the commit then goes through as CLEAN.
6. **REAL takes: seed from the translated merge.** `translate.py <sha> --apply
   --worktree ../roboco-sync` writes the merge, conflict markers
   included, lists the files with no Roboco counterpart, and writes the
   sidecar report with a hint per conflict region
   ([translate.py passes](#translatepy-passes); add `--mergiraf` if it is
   installed). The porting agent starts from that and the report, not from a
   blank branch, and resolves by intent (next section). Take the commits in
   upstream order (see [Lanes](#lanes-in-upstream-order)). Write
   a ticket (`.scratch/upstream-sync/issues/NN-<slug>.md`, ledger
   `ticketed upstream-sync-NN`) only when the port needs a design decision.
   Otherwise the commit trailer is the record. A port that spans several
   commits carries the trailer on the commit that completes it. Anything
   deliberately left out goes in the commit message and the ledger note
   (`partial`).
7. **One PR, one CI run.** Before opening it, flip the ledger in the batch
   branch:
   `python scripts/upstream/triage.py sync --rev HEAD --write`. This turns
   `queued` rows into `ported <sha>` and lists any taken commit still
   missing. Commit the ledger and the triage page with the batch. Roboco
   merges PRs with merge commits, so the recorded shas stay valid on `main`.

`triage.py check-rules` replays the path rules over the whole ledger. On the
seed, it auto-skips 89 commits with no disagreement and sends 19 to the
Android lane. Run it after editing `path-rules.tsv`.

## Wave 10 (in flight)

Wave 10 (`.scratch/upstream-ports-2026-10-wave10/`) keeps its tickets. Each
ticket that cites a merged upstream SHA starts by running
`python scripts/upstream/translate.py <sha> --annotate` against its branch
to see what lands cleanly and what is left. Most wave-10 commits are
remainders of partial ports, which residual mode (the default) is for. The
ticket then uses `--apply` to seed the port, and adds the
`Upstream-Commit:` trailer to the commit that completes it. Wave-10 rows
are `ticketed wave10-NN` in the ledger. Each merged wave-10 PR runs
`triage.py sync --write` to flip them. The weekly loop starts once wave 10
is done.

### Lanes in upstream order

Within a lane (a ticket, or tickets that share files), replay the upstream
commits **in upstream commit order** (`git log --reverse` on the mirror, or
the ledger's row order), and **commit each port before replaying the next**.
`translate.py` merges onto the worktree's HEAD, so the next replay sees the
prerequisite already in place instead of conflicting on it. In the wave-10
measurement, half of the upstream code missing from conflicts came from other
pending wave-10 commits. When a region's hint says `prerequisite <sha>`, and
that commit belongs to the lane, port it first and re-run. Ordering is often
written in the ticket already ("Needs 978e8dacb first").

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

## Porting by hand

For a REAL port, or when `translate.py` can't place a file (renamed or deleted
on the Roboco side):

1. Inspect the upstream commit and its dependencies with `git show <sha>`.
   The translated merge flags missing prerequisites as files with no Roboco
   target; `presence.py` shows whether an earlier upstream commit they need
   is in Roboco.
2. To review the change against pristine Zeron, create a temporary worktree
   at its upstream parent (an ancestor of the mirror):
   `git worktree add ../zeron-port -b upstream-port/topic <sha>^`, then
   `git -C ../zeron-port cherry-pick -x <sha>`. For several commits, start
   before the first and cherry-pick the selected sequence. The mirror itself
   doesn't change.
3. Carry the change into the Roboco worktree, starting from `translate.py
   --apply`. Adapt what the rename map can't: renamed or moved files and
   identifiers (see AGENTS.md). Exclude the cloud, account and sync portions
   and iOS code, and adapt retained behaviour to engine-local RPCs. Resolve
   conflicts by intent, never by replacing a whole Roboco file with the
   upstream one.
4. Review `git diff`, run checks that fit the retained behaviour, and record the
   `Upstream-Commit:` trailer and any excluded behaviour in the commit. Finish
   only when the patch keeps Roboco's architecture and the checks pass.
   Regenerate `Cargo.lock` with cargo (the report's commands, or
   `--refresh-lock`); never hand-merge it.
5. Remove clean temporary worktrees, and delete their temporary branches once
   the port commit or its evidence is kept.

### The five hot files: move Roboco-only tests out when you touch them

Five files carry about 29% of the conflict regions in wave 10:
`crates/ui/src/shell.rs`, `crates/ui/src/composer.rs`,
`crates/ui/src/shell/spaces.rs`, `crates/ui/src/transcript.rs` and
`crates/engine/src/sessions.rs`. When a port touches one of them, move the
Roboco-only tests in it (tests upstream doesn't have) into a child module
in the same PR: `#[cfg(test)] mod roboco_tests;` at the end of `shell.rs`
lives in `crates/ui/src/shell/roboco_tests.rs` (`spaces.rs` →
`shell/spaces/roboco_tests.rs`, `sessions.rs` → `sessions/roboco_tests.rs`). Leave
upstream's own inline test modules where they are, so upstream's later edits
to them still merge. Don't move Roboco-only production code: it sits inside
upstream's functions, and moving it means designing seams upstream's next
rewrite would undo. Measured: moving the tests removes 13% of the hot-file
regions.

A commit already present in Roboco needs no production port: record it as
`ported` (with the commit that brought it, if one exists). A historical replay
may prepare a pre-change baseline on a throwaway branch to exercise the
process. That baseline and replay must never be integrated into main.
