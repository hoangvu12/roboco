# Less re-coding per upstream change: measured on wave 10 (2026-10-29)

Research only. Nothing in the repo changed except this folder: the report plus two prototypes
(`residual-proto.py`, `renamed-mirror-proto.py`). All experiments ran in throwaway clones under
`C:\tmp\upstream-less-recoding\` (remotes removed, nothing pushed). Scripts: `C:\tmp\upstream-less-recoding\scripts\`;
raw results: `...\out\`.

## The short answer

- **We still can't "just merge" upstream, even through a renamed mirror.** The renamed mirror is easy to build. It is
  deterministic and append-only, takes about 2 minutes, and git's merge mechanics work on it (a one-time `-s ours`
  merge makes later merges bring only new upstream commits). But one real upstream week merged that way brought
  **249 conflicted files at once** and **~16,000 lines from commits we decline or skip**, plus ~3,900 lines we had already ported by hand.
  Merging gives the *same* three-way conflicts as cherry-picking, adds the unwanted code, and breaks ADR 0003.
- **Wave 10's remaining tickets are hard, and no option makes them mechanical.** Under today's `translate.py`, 56
  of the 58 measurable tickets are REAL. The best combination of the options below still leaves ~55 REAL.
  Classes barely move because a REAL ticket has many conflicted files, and fixing some of them doesn't flip the class.
  **Conflict regions** (the actual hand-work) do move: the recommended stack (residual + mergiraf + lockfile rule) removes **a third** of them (2,099 → 1,376), ~18–23% of the modelled porting hours.
- **What actually lowers the hand-work:**
  1. **Residual mode** (new, prototyped): before merging, drop the upstream hunks Roboco already has. 18 of the 67
     wave-10 commits are "remainders" of partial ports, so their conflicts are mostly code we already ported. Result: −25% regions.
  2. **mergiraf** as a fallback merge driver for Rust/TOML: it fully resolves 24% of conflicted files (−7% of regions), and its
     resolutions review sanely.
  3. **Regenerate `Cargo.lock`** instead of merging it: 105 regions (5%) for free.
  4. **Annotate each remaining conflict with *why* it conflicts.** Blame the upstream side to its origin commit
     and read the ledger: a pending wave-10 prerequisite, Roboco's own rewrite of pre-fork code, or declined/cloud code.
     This doesn't remove conflicts, but it gives the porting agent the decision rule for each region.
- **Not worth it:** taking upstream's files wholesale (3 of 414 conflicted files qualify). Big refactors of the
  five hot files: Roboco-only code there is ~9% of their lines, woven into the same functions. Only moving Roboco's own
  tests out is cheap, and it removes 13% of hot-file regions.

## What was measured

- **Set:** wave-10 tickets 02–32 and 44–75 (33–43 done, 01 setup, 76–78 parked). Upstream commits per ticket come from
  the ticket text plus ledger rows `ticketed wave10-NN`: **70 distinct upstream commits**, 67 still pending (3 rows were
  already ported or skipped: ALREADY). Five tickets (07, 15, 18, 19, 26) carry only *open* upstream PRs, which
  aren't on the mirror, so they are unmeasured (shown as —). Ticket 59 (Android first run) carries only the iOS slice of #966: a hand
  port by nature (REAL*).
- **Giant bundles are sliced.** Commits touching more than 40 portable files (#966 `cc7da5c2a`, `2e151ca65` "harden every provider",
  #484 PR board, #966's siblings) were cut to the files the ticket names, because each ticket carries only a slice. That gives 94
  ticket×commit "slices"; totals count distinct slices.
- **Target:** `main` = `origin/main` `e29d47767`. Upstream = `zeron/main` `ab189159f` (v0.2.128).
- **Classes** are those of `translate.py`: CLEAN, TRIVIAL (≤3 regions / ≤30 lines, or only `Cargo.lock`; no missing
  targets), REAL (anything else, including a file with no Roboco counterpart), ALREADY (in Roboco). **Regions** = conflict
  regions summed over distinct slices: the best proxy for hand-work.
- **Not done:** compile checks. 14 cargo/rustc processes from other workers were running throughout, so per the
  memory rule I skipped `cargo check`. "Clean" means the text merged.

## Baseline: why wave 10 is REAL

Under today's `translate.py` on `main`: **1 CLEAN, 1 TRIVIAL, 56 REAL tickets**; slices 5 CLEAN / 6 TRIVIAL / 80 REAL / 3
ALREADY; **2,099 conflict regions** in 414 conflicted files.

What sits inside the conflicts (`hunks.py`, `prereq.py`; 1,715 regions in diff3 form):

| Inside a conflict region | Regions | Share |
|---|---|---|
| Roboco-only code *and* upstream code Roboco lacks | 753 | 44% |
| Only Roboco-only code (upstream's change lands on Roboco's diverged text) | 440 | 26% |
| Only upstream code Roboco lacks (a prerequisite isn't there) | 401 | 23% |
| Neither (formatting or order drift of code both sides have) | 121 | 7% |

- **Roboco-only lines in conflicts** (16,101): Windows 275, rebrand leftovers 370, engine-local/pairing 202, Android 17, and
  **other 15,237 (95%)**. That last bucket is Roboco's own version of the same features: hand-ported code written
  differently, Roboco fixes such as completion notices and scheduled resumes, and settings and accounts. The Windows/brand/cloud seam is small.
- **Upstream lines Roboco lacks**, attributed by blame to their origin commit (18,544 lines):

| Origin of the missing upstream code | Lines | Share | What it means |
|---|---|---|---|
| another still-pending wave-10 commit | 9,339 | 50% | order matters: port the prerequisite first |
| …of which #966 `cc7da5c2a` (Cloud + switching + picker) | 1,004 | 5% | mostly the cloud half: never ported |
| pre-fork upstream code that Roboco rewrote or removed | 7,099 | 38% | keep ours, adapt by intent |
| upstream commits we ported differently | 1,771 | 10% | keep ours |
| declined / cloud / superseded / other skips | 535 | 3% | drop upstream's side |

- **Hot files:** 491 of the 1,715 regions (29%) are in the five hot files. `shell.rs` 180, `sessions.rs` 159, `transcript.rs`
  68, `spaces.rs` 66, `composer.rs` 18. `Cargo.lock` alone has 105.
- **18 of the 67 commits are remainders** of an earlier partial port (ledger note "audit PORTED (sha); residual slice
  named in the ticket", or several port commits). Replaying the whole commit re-conflicts on everything already ported.
  That is why residual mode is the biggest lever.

## Option 1: persisted renamed mirror (and "can we merge instead?")

**Built** with `git filter-repo` used as a library (`scripts/renamed_mirror.py`, kept as `renamed-mirror-proto.py`). It reuses
`rename-map.tsv` and `path-rules.tsv` through `upstream_common`, so it is the same translation `translate.py` does. It drops
iOS/edge/landing/upstream-Android/zatex paths, rewrites paths and text, and appends `Upstream-Commit: <original>` to every
message.

| Property | Result |
|---|---|
| Time, full rewrite of 1,901 upstream commits | 107 s (≈2 min) |
| Deterministic | yes: two independent runs → identical head `820afca05` |
| Append-only (weekly refresh keeps old SHAs) | yes: rewriting from last week's upstream head (`911ec56e4`) gives `6a63d5805`, the exact SHA that commit has in today's rewrite. A weekly full re-run is enough; filter-repo's incremental `--state-branch` is hidden/experimental and untested |
| Commits pruned (became empty: iOS/landing-only) | 165 of 1,901 |
| Leaked `zeron` tokens | none (only the protected `zeronsh`) |

**Plain `git cherry-pick -n` of mirror commits** reproduces `translate.py`: one slice differs by one class (`daf047b7f`
REAL→TRIVIAL), and there are 4% fewer regions (2,018 vs 2,099) from git's own rename and merge machinery. The mirror therefore
does **not** reduce conflicts by itself. What it adds is native git on translated history:

- `git cherry-pick -x` and rerere (below).
- mergiraf as a real git merge driver.
- `git log`/`blame`/`range-diff` in Roboco names. Blame is what powers the conflict annotation.

**Merge experiment** (`w2` clone):

1. On `main`: `git merge -s ours --allow-unrelated-histories <mirror as of 2026-10-21>`. This records that upstream up to
   that point is accounted for. git then reports the merge base of `main` and the mirror head as exactly that commit
   (`6a63d5805`), so later merges only bring newer upstream commits. **The mechanism works.**
2. `git merge renamed` (one real week, 86 upstream commits): runs in 7 s and produces **249 conflicted files** (176
   content, 59 modify/delete, 14 add/add). 666 paths change, including 215 new `docs/` files.
3. Per the ledger, that week holds 40 wave-10 commits, 8 already ported, 5 declined, and 33 skips or bumps. The merge carries
   their net additions anyway:

| Brought in by the merge, not wanted | Lines |
|---|---|
| declined commits (iOS terminals beside sessions, browser annotations, WebRTC terminals…) | 12,699 |
| `docs-only` skips | 2,698 |
| macOS-only | 772 |
| already ported by hand (would duplicate) | 3,889 |

Plus the cloud half of #966 (67k lines in one commit), which sits inside *shared* crates, so path filtering can't drop it.

Per-commit picks of the same week's 43 taken commits hit 442 conflicted-or-missing files (252 distinct). So the merge
batches the same conflicts into one resolution: fewer touches (249), but the same work, plus deletion of the unwanted
code, every week. After the merge, any upstream edit to a cloud seam we deleted comes back as a modify/delete conflict.
**Verdict:** an `-s ours` baseline plus weekly real merges doesn't revive removed *paths*, because the mirror filters them. It does
re-offer removed code inside shared files, and it gives up per-commit decisions. Not viable while ~30% of upstream is
declined or skipped. ADR 0003 stays right.

**Cost:** ~½ day to productionize (script, a `zeron-renamed/main` branch next to `zeron/main`, a weekly 2-minute refresh).
**Worth it only for rerere and blame-based annotation**, not for merging.

## Option 2: mergiraf (structural merge driver)

The mergiraf 0.20.0 Windows binary (Rust, TOML, Kotlin) ran on every baseline conflict triple (`mergiraf_run.py`, 22 min, 414 files).

| | Files | Fully resolved |
|---|---|---|
| all conflicted | 414 | **98 (24%)** |
| `.rs` | 355 | 83 |
| `.toml` | 26 | 14 |
| `Cargo.lock`, `.md`, `.mjs`, `.yml`, `.json`, `.svg` | 33 | 1 |

- Regions removed: **143 of 1,816 (8%)**, because the files it solves are the small ones. Where it can't fully solve a file,
  its partial output often has *bigger* markers, so use it only when it resolves the whole file ("best-of").
- **Correctness** (`mg_verify.py`): in 87 of the 98 resolved files, every line both sides added is present. For the other 11
  (1–11 lines each), the cases I read were right. For example, `crates/engine/src/lib.rs` for `94304283a` kept
  Roboco's version, which already does upstream's fix *without* upstream's `edge` code, and mergiraf recognized that. Spot-read
  resolutions (a TOML example list, `mod glass;` in `ui/src/lib.rs`) were what a human would write.
  Not compiled (see above).
- Slices changing class: 4 (`4879a4754` and a #966 slice → CLEAN; `ec0897feb`, `daf047b7f` → TRIVIAL).
- **Cost:** <½ day (download a binary, add an opt-in pass to `translate.py`, or a `.gitattributes` driver for mirror cherry-picks).
  **Risk:** low when it is fallback-only and every result is reviewed and built. Pre-1.0.

## Option 3: move Roboco-only code out of the five hot files

| File | Lines | Roboco-only lines (not in upstream head) | …in inline test modules | Wave-10 regions | …in test modules |
|---|---|---|---|---|---|
| `crates/ui/src/shell.rs` | 16,759 | 1,565 | 202 | 180 | 31 |
| `crates/engine/src/sessions.rs` | 7,005 | 2,015 | 46 | 159 | 8 |
| `crates/ui/src/transcript.rs` | 17,423 | 1,500 | 715 | 68 | 10 |
| `crates/ui/src/shell/spaces.rs` | 8,657 | 729 | 393 | 66 | 11 |
| `crates/ui/src/composer.rs` | 16,260 | 274 | 89 | 18 | 6 |
| **total** | 66,104 | **6,083 (9%)** | 1,445 | **491** | **66** |

- Sampled Roboco-only lines (`opt3.py`, sample in the session log) are mostly Roboco's version of shared logic. Examples:
  resume, completion-notice and scheduled-resume state in `sessions.rs`; settings sections, account slots and the
  `hoangvu12/roboco` repo URL in `shell.rs`; Roboco's typed-path flow in `spaces.rs`. They sit in the same functions as upstream's code,
  so moving them out means designing extension seams in the middle of upstream's functions. That is a large refactor
  that upstream's next rewrite of the same function would undo.
- **Realistic variant:** move only Roboco-only *tests* into sibling files (`#[cfg(test)] #[path] mod roboco_tests;`), leaving
  upstream's inline test modules as they are. It removes **66 of 491** hot-file regions (13%) and flips 2 slices (`35a9139a1` slice → CLEAN,
  `a75cf1a61` → CLEAN). One-time cost: about ½–1 day for ~1,445 test lines, plus a CI run. Per-port saving: small but permanent.
- **Optimistic variant:** all Roboco-only code moved out, so every region whose Roboco side is Roboco-only with nothing lacked
  disappears. That removes 199 of 491 (41%) and flips only 4 slices. Cost: weeks, with high regression risk in the
  engine's run state machine. **Not recommended.**

## Option 4: re-baseline files to upstream's version ("take upstream wholesale")

Tree-wide divergence of `main` against the renamed mirror head (`divergence.py`, `out/divergence.tsv`):

| Class (files both sides have: 1,258) | Files |
|---|---|
| identical | 803 |
| behind only (Roboco untouched since the fork; already merges clean) | 38 |
| **port-only** (every Roboco line also exists upstream: candidates) | 42 |
| Roboco has its own lines | 372 |
| binary differs | 3 |

Roboco-only files: 4,432 (Android 2,205, `web/` 1,439, `.scratch` 486…). Upstream-only files: 670 (docs 294, `crates/orb`,
`crates/sync`, …).

**On wave 10:** of the 414 conflicted files, only **3** have zero Roboco-only lines (18 if ≤5 lines are tolerated). Re-baselining them
changes no ticket class and removes 3–23 regions. Every conflicted file is one where Roboco has its own code, by definition
almost. **Not worth a step.** The 42 port-only files could be re-synced opportunistically, but they rarely conflict anyway.

## Option 5: better ideas, measured

### 5a. Residual mode (recommended)

Prototype: `residual-proto.py` here, and `scripts/residual.py`. For each conflicted file, an upstream hunk counts as
already in Roboco when ≥80% of its significant added lines already exist in the Roboco file and its removed lines are
already gone. Such hunks are reverted to base before the 3-way merge, so the merge only carries what is still missing.

- Regions **1,816 → 1,318 (−27%)** on conflicted files; 78 files become clean, 67 of them because the whole "conflict"
  was code Roboco already had (now NOOP). Slice classes: `a75cf1a61` TRIVIAL→CLEAN, `987bb2f87` REAL→TRIVIAL (whole commit).
- Biggest effects on the remainder tickets: 06 32→7, 04 82→39, 08 84→50, 16 111→67, 12 71→45.
- Bonus: it prints, per file, how many hunks are "already in Roboco" vs "still to port", which answers the "what's left of this
  commit?" question that wave 10's audit spent days on.
- **Risk:** a hunk whose lines exist elsewhere in the file can be wrongly dropped (a false "already"). The threshold is
  conservative, and the dropped-hunk list is printed for review. Cost: ~½ day to fold into `translate.py` as `--residual`, with tests.

### 5b. Port lanes in upstream order

- Blame attribution: 50% of the upstream code missing from conflicts comes from *other pending wave-10 commits*.
- But only **162 of 1,715 regions (9%)** consist purely of a missing pending prerequisite (with #966 excluded, since its
  cloud half never ports). Another 241 regions get partial help.
- Ordering is already partly encoded in the tickets ("Needs 978e8dacb first", "Port together with d460e8fe1"). Recommendation:
  within a lane, run `translate.py --apply` on commits in **upstream order**, committing each before the next, so the
  next replay sees the prerequisite. Cost: nil. Gain: ~9% of regions, plus fewer "no Roboco target" files.

### 5c. rerere seeded from past ports (needs the renamed mirror)

For the 12 remainder commits with a single known earlier port P (`rerere_seed.py`), I cherry-picked the mirror commit onto
P^, took P's files as the resolution, ran `git rerere`, then cherry-picked onto `main`:

- Regions **820 → 779 (−5%)**; 8 of 12 improve. `ea3dcc811` 11→1, `987bb2f87` 23→12, `01a399db1` 31→24,
  `1bc74d5da` 23→18. The rest gain 0–4.
- rerere replays *our earlier decision* on hunks that look the same today. For remainders, that is exactly "keep what we
  ported, add only the residue". It also makes re-runs free: rebasing a batch branch, or redoing a port after `main` moved.
- **Cost:** ~½ day on top of option 1. It overlaps partly with residual mode (both target already-ported hunks).

### 5d. Conflict annotation for the agent (recommended, cheap)

`prereq.py` already computes, per conflict region, where the upstream side's extra code came from. Written as a comment
above each region (or as a sidecar list), it gives the porting agent a decision rule:

| Annotation | Rule |
|---|---|
| from pending wave-10 commit X | port X first (or include it) |
| pre-fork code Roboco rewrote | keep ours; re-express upstream's change in our version |
| ported differently / declined / cloud | keep ours, drop upstream's side |
| Roboco-only lines, nothing missing | keep ours, apply upstream's diff onto it |

This doesn't change counts; it targets the time per region, which is the REAL cost. Cost: ~½ day (blame on `zeron/main`
works without the renamed mirror, by mapping paths back).

### 5e. `Cargo.lock`: regenerate, never merge

105 of the baseline regions (5%) are in `Cargo.lock`. `translate.py` already treats a lock-only conflict as TRIVIAL. Take
ours and run `cargo check` / `cargo update -p <crate>` instead: 0 hand regions. Cost: nil.

## PR #39 (web removal)

I synthesized `main` with PR #39 merged (`merge-tree`; PR #39 is behind `main` and conflicts on `ui/src/paired_fixture.rs`)
and re-ran the baseline:

- **Disappears:** the ts-rs conflicts in `crates/proto` (`entities.rs` 2→1 regions in three commits, `ed3df8fb1` `entities.rs` →
  clean). About 3 regions in all. Upstream never had `web/`, so no wave-10 conflict lives there.
- **Changes meaning:** upstream's in-app browser files (`crates/ui/src/browser/**`, `browser-fixture*`, `docs/reference/linux-browser.md`)
  become "no Roboco target". That is right, since the browser slices are out per the spec (decision 1). **Add `crates/ui/src/browser/` and
  `crates/ui/examples/browser-fixture` to `path-rules.tsv` as `skip … declined`** once #39 merges, so they're dropped and not shown as MISSING.
- **Appears:** a few new conflicts where #39 edits code next to browser hooks (`settings.rs`, `shell.rs`). Net: ±0.
- Ticket classes: no change, apart from one #484 slice that loses a file.

## Per-ticket table

Each cell: class (conflict regions). Columns:

- **Base**: `translate.py` on `main`.
- **O1**: plain `git cherry-pick` from the renamed mirror.
- **O2**: mergiraf best-of.
- **O3 tests / O3 all**: Roboco tests moved out / all Roboco-only code moved out of the hot files.
- **O4 tol0 / tol5**: re-baseline files with 0 / ≤5 Roboco-only lines.
- **O5 residual**: residual mode.
- **Stack**: residual + mergiraf + `Cargo.lock` regenerated.
- **PR39**: baseline after PR #39.

`—` = no merged upstream commit (open PRs only). REAL* = iOS→Android hand port.

| # | Ticket | Upstream commits | Base | O1 mirror | O2 mergiraf | O3 tests | O3 all | O4 tol0 | O4 tol5 | O5 residual | Stack | PR39 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2 | markdown-and-editor-crash-fixes | c02739da3 85006f4b4 46d4e42b3 | REAL (34) | REAL (34) | REAL (27) | REAL (32) | REAL (31) | REAL (34) | REAL (34) | REAL (34) | REAL (25) | REAL (35) |
| 3 | ipc-token-and-sign-in-url-guard | b782d0437 cc7da5c2a | REAL (134) | REAL (131) | REAL (134) | REAL (130) | REAL (119) | REAL (134) | REAL (134) | REAL (125) | REAL (125) | REAL (134) |
| 4 | windows-shell-captions-banners-shortcu | a73fa8fe4 eabad36a2 | REAL (82) | REAL (73) | REAL (80) | REAL (80) | REAL (78) | REAL (81) | REAL (80) | REAL (39) | REAL (17) | REAL (81) |
| 5 | windows-path-handling | 88a07863f 35a9139a1 3487b507f 77a3c0ff1 | REAL (95) | REAL (69) | REAL (88) | REAL (90) | REAL (79) | REAL (95) | REAL (92) | REAL (78) | REAL (43) | REAL (88) |
| 6 | windows-file-links-in-replies | 2a38baa4e | REAL (32) | REAL (32) | REAL (26) | REAL (29) | REAL (29) | REAL (32) | REAL (32) | REAL (7) | REAL (7) | REAL (40) |
| 7 | command-titles-without-shell-wrappers | (open PRs / Roboco-only) | — | — | — | — | — | — | — | — | — | — |
| 8 | windows-agent-launch-and-process-fixes | 35a9139a1 d1dc29f6c 2e151ca65 | REAL (84) | REAL (87) | REAL (82) | REAL (84) | REAL (84) | REAL (84) | REAL (80) | REAL (50) | REAL (47) | REAL (84) |
| 9 | mcp-orchestration-timeouts-and-harness | 01832f2ca cc7da5c2a | REAL (113) | REAL (129) | REAL (113) | REAL (109) | REAL (98) | REAL (113) | REAL (113) | REAL (85) | REAL (85) | REAL (113) |
| 10 | wait-for-turn-interrupted-outcome | cc7da5c2a | REAL (49) | REAL (50) | REAL (47) | REAL (45) | REAL (34) | REAL (49) | REAL (49) | REAL (44) | REAL (42) | REAL (48) |
| 11 | claude-hardening-residuals | 2e151ca65 | REAL (102) | REAL (94) | REAL (102) | REAL (102) | REAL (102) | REAL (102) | REAL (102) | REAL (80) | REAL (80) | REAL (102) |
| 12 | codex-hardening-residuals | 91fd851c7 2e151ca65 | REAL (71) | REAL (68) | REAL (71) | REAL (71) | REAL (71) | REAL (71) | REAL (71) | REAL (45) | REAL (45) | REAL (71) |
| 13 | opencode-crash-and-stall-detection | 2e151ca65 | REAL (34) | REAL (35) | REAL (34) | REAL (34) | REAL (34) | REAL (34) | REAL (34) | REAL (35) | REAL (35) | REAL (34) |
| 14 | opencode-delivery-and-stop | 2e151ca65 | REAL (27) | REAL (28) | REAL (27) | REAL (27) | REAL (27) | REAL (27) | REAL (27) | REAL (28) | REAL (28) | REAL (27) |
| 15 | opencode-smaller-fixes | (open PRs / Roboco-only) | — | — | — | — | — | — | — | — | — | — |
| 16 | engine-run-lifecycle-fixes | ed3df8fb1 731697b66 2e151ca65 | REAL (111) | REAL (107) | REAL (107) | REAL (110) | REAL (67) | REAL (111) | REAL (110) | REAL (67) | REAL (64) | REAL (110) |
| 17 | acp-pi-and-harness-smalls | 2e151ca65 | REAL (54) | REAL (44) | REAL (54) | REAL (54) | REAL (54) | REAL (54) | REAL (54) | REAL (43) | REAL (43) | REAL (54) |
| 18 | claude-transcript-and-catalog-fixes | (open PRs / Roboco-only) | — | — | — | — | — | — | — | — | — | — |
| 19 | usage-limits-context-and-auth-fixes | (open PRs / Roboco-only) | — | — | — | — | — | — | — | — | — | — |
| 20 | pi-slash-menu | df0cd2980 | REAL (9) | REAL (11) | REAL (9) | REAL (9) | REAL (9) | REAL (9) | REAL (9) | REAL (9) | REAL (9) | REAL (9) |
| 21 | sidebar-and-new-project-fixes | b782d0437 e2a7706fa dbb639be3 1bc74d5da 81adbdabf | REAL (101) | REAL (96) | REAL (94) | REAL (93) | REAL (82) | REAL (101) | REAL (100) | REAL (56) | REAL (53) | REAL (107) |
| 22 | pending-send-adoption | ec0897feb | REAL (4) | REAL (4) | TRIVIAL (2) | REAL (4) | REAL (4) | REAL (4) | REAL (4) | REAL (4) | TRIVIAL (2) | REAL (4) |
| 23 | forks-settled-chips-and-fleet-fork-tab | ae1ec6ad8 00cc2e059 | REAL (27) | REAL (26) | REAL (23) | REAL (21) | REAL (21) | REAL (27) | REAL (27) | REAL (27) | REAL (23) | REAL (30) |
| 24 | transcript-and-layout-small-fixes | 5f60293c0 ac4acbc21 ea3dcc811 4f465702d 4879a4754 | REAL (56) | REAL (56) | REAL (45) | REAL (56) | REAL (51) | REAL (56) | REAL (56) | REAL (46) | REAL (38) | REAL (55) |
| 25 | composer-fixes | a75cf1a61 | TRIVIAL (1) | TRIVIAL (1) | TRIVIAL (1) | CLEAN (0) | CLEAN (0) | TRIVIAL (1) | TRIVIAL (1) | CLEAN (0) | CLEAN (0) | TRIVIAL (1) |
| 26 | notices-and-actions | (open PRs / Roboco-only) | — | — | — | — | — | — | — | — | — | — |
| 27 | generated-and-viewed-images-on-desktop | 9601a2440 189115f36 5d2b91901 | REAL (89) | REAL (87) | REAL (71) | REAL (87) | REAL (77) | REAL (87) | REAL (84) | REAL (54) | REAL (46) | REAL (88) |
| 28 | tool-call-images-selectable-output-ans | e5466c533 | REAL (32) | REAL (34) | REAL (31) | REAL (31) | REAL (31) | REAL (32) | REAL (32) | REAL (31) | REAL (8) | REAL (34) |
| 29 | agent-message-header-and-desktop-capti | 4a5fb8b44 325384d8b f65c5bc60 | REAL (42) | REAL (38) | REAL (38) | REAL (36) | REAL (29) | REAL (42) | REAL (42) | REAL (41) | REAL (37) | REAL (40) |
| 30 | markdown-in-message-bubbles | ddf931624 d460e8fe1 325384d8b 46d4e42b3 | REAL (40) | REAL (39) | REAL (38) | REAL (37) | REAL (35) | REAL (40) | REAL (40) | REAL (39) | REAL (37) | REAL (40) |
| 31 | android-captions-and-switch-seam | cc7da5c2a f65c5bc60 | REAL (7) | REAL (7) | REAL (3) | REAL (7) | REAL (7) | REAL (7) | REAL (7) | REAL (7) | REAL (3) | REAL (7) |
| 32 | sent-messages-as-one-line | d8a14b35b | REAL (6) | REAL (6) | REAL (5) | REAL (5) | REAL (5) | REAL (6) | REAL (6) | REAL (6) | REAL (3) | REAL (6) |
| 44 | managed-background-engine | cc7da5c2a | REAL (28) | REAL (25) | REAL (28) | REAL (28) | REAL (28) | REAL (28) | REAL (28) | REAL (28) | REAL (28) | REAL (28) |
| 45 | background-engine-update-handoff | cc7da5c2a | CLEAN (0) | CLEAN (0) | CLEAN (0) | CLEAN (0) | CLEAN (0) | CLEAN (0) | CLEAN (0) | CLEAN (0) | CLEAN (0) | CLEAN (0) |
| 46 | engine-posted-desktop-notifications | cc7da5c2a | REAL (28) | REAL (25) | REAL (28) | REAL (28) | REAL (28) | REAL (28) | REAL (28) | REAL (28) | REAL (28) | REAL (28) |
| 47 | claude-subagents-live-text-and-spawn-g | 978e8dacb 2514e3edf | REAL (3) | REAL (3) | REAL (2) | REAL (3) | REAL (3) | REAL (3) | REAL (3) | REAL (3) | REAL (2) | REAL (3) |
| 48 | claude-typed-skill-fork-chip-and-tab | 95b294658 | REAL (4) | REAL (4) | REAL (2) | REAL (3) | REAL (3) | REAL (4) | REAL (4) | REAL (4) | REAL (4) | REAL (4) |
| 49 | claude-typed-fork-live-tail | d5a015226 | REAL (11) | REAL (11) | REAL (10) | REAL (11) | REAL (11) | REAL (11) | REAL (11) | REAL (11) | REAL (11) | REAL (11) |
| 50 | show-image-engine-and-mcp | 5d2b91901 | REAL (41) | REAL (40) | REAL (35) | REAL (40) | REAL (40) | REAL (39) | REAL (38) | REAL (38) | REAL (32) | REAL (41) |
| 51 | markdown-images-in-replies | 5d2b91901 | REAL (41) | REAL (40) | REAL (35) | REAL (40) | REAL (40) | REAL (39) | REAL (38) | REAL (38) | REAL (32) | REAL (41) |
| 52 | questions-record-answers-and-decline | 64a26d359 | REAL (16) | REAL (16) | REAL (6) | REAL (13) | REAL (13) | REAL (16) | REAL (16) | REAL (16) | REAL (12) | REAL (16) |
| 53 | questions-answer-chips-and-close-ui | 64a26d359 | REAL (16) | REAL (16) | REAL (6) | REAL (13) | REAL (13) | REAL (16) | REAL (16) | REAL (16) | REAL (12) | REAL (16) |
| 54 | question-prefill-and-multiline | df0cd2980 | REAL (5) | REAL (5) | REAL (5) | REAL (5) | REAL (4) | REAL (5) | REAL (5) | REAL (4) | REAL (4) | REAL (5) |
| 55 | native-subagents-switch | b8ed9d9c2 | REAL (28) | REAL (30) | REAL (24) | REAL (28) | REAL (26) | REAL (28) | REAL (28) | REAL (25) | REAL (20) | REAL (28) |
| 56 | devin-fusion-picker | 731697b66 | REAL (21) | REAL (21) | REAL (21) | REAL (21) | REAL (21) | REAL (21) | REAL (21) | REAL (20) | REAL (20) | REAL (21) |
| 57 | gpui-pin-sync | afca583c1 cc7da5c2a c31124aa9 | REAL (51) | REAL (49) | REAL (47) | REAL (51) | REAL (51) | REAL (51) | REAL (48) | REAL (50) | REAL (25) | REAL (34) |
| 58 | first-run-setup-desktop | cc7da5c2a | REAL (41) | REAL (35) | REAL (41) | REAL (33) | REAL (24) | REAL (41) | REAL (41) | REAL (35) | REAL (35) | REAL (39) |
| 59 | first-run-setup-android | cc7da5c2a | REAL* (0) | REAL* (0) | REAL* (0) | REAL* (0) | REAL* (0) | REAL* (0) | REAL* (0) | REAL* (0) | REAL* (0) | REAL* (0) |
| 60 | settings-worktrees-page | 01a399db1 | REAL (31) | REAL (31) | REAL (28) | REAL (31) | REAL (31) | REAL (31) | REAL (29) | REAL (18) | REAL (17) | REAL (30) |
| 61 | settings-search | bf6d71aaa | REAL (17) | REAL (17) | REAL (14) | REAL (17) | REAL (16) | REAL (17) | REAL (16) | REAL (17) | REAL (14) | REAL (15) |
| 62 | smooth-scrolling | 3d2f1e64a | REAL (6) | REAL (5) | REAL (5) | REAL (5) | REAL (5) | REAL (6) | REAL (6) | REAL (5) | REAL (4) | REAL (7) |
| 63 | transcript-rail | ddf931624 a4187fd51 d460e8fe1 46d4e42b3 | REAL (42) | REAL (40) | REAL (41) | REAL (40) | REAL (38) | REAL (42) | REAL (42) | REAL (41) | REAL (40) | REAL (42) |
| 64 | session-hover-card | 840fda748 | REAL (7) | REAL (8) | REAL (6) | REAL (7) | REAL (7) | REAL (7) | REAL (7) | REAL (6) | REAL (5) | REAL (7) |
| 65 | sidebar-and-session-card-polish | 0c4835d2b 591824881 4443e6b58 bc892abd0 daf047b7f | REAL (63) | REAL (63) | REAL (57) | REAL (61) | REAL (58) | REAL (63) | REAL (63) | REAL (28) | REAL (26) | REAL (63) |
| 66 | terminal-engine-latency-and-host-repli | 71033cc96 | REAL (24) | REAL (24) | REAL (22) | REAL (24) | REAL (24) | REAL (24) | REAL (24) | REAL (22) | REAL (20) | REAL (24) |
| 67 | terminal-view-overhaul | 71033cc96 | REAL (24) | REAL (24) | REAL (22) | REAL (24) | REAL (24) | REAL (24) | REAL (24) | REAL (22) | REAL (20) | REAL (24) |
| 68 | terminal-program-status | cc7da5c2a | REAL (20) | REAL (12) | REAL (18) | REAL (18) | REAL (17) | REAL (20) | REAL (20) | REAL (20) | REAL (18) | REAL (19) |
| 69 | workspace-search-engine | afca583c1 3487b507f 1bc74d5da | REAL (101) | REAL (69) | REAL (89) | REAL (101) | REAL (92) | REAL (101) | REAL (100) | REAL (78) | REAL (41) | REAL (95) |
| 70 | tabbed-command-palette | 3487b507f | REAL (67) | REAL (37) | REAL (62) | REAL (67) | REAL (58) | REAL (67) | REAL (67) | REAL (63) | REAL (29) | REAL (60) |
| 71 | shared-width-panels-layout-model | dace1a8ce | REAL (28) | REAL (23) | REAL (26) | REAL (26) | REAL (20) | REAL (28) | REAL (28) | REAL (28) | REAL (26) | REAL (27) |
| 72 | shared-width-panels-surfaces | ac4acbc21 dace1a8ce | REAL (69) | REAL (64) | REAL (60) | REAL (67) | REAL (57) | REAL (69) | REAL (69) | REAL (59) | REAL (53) | REAL (67) |
| 73 | repository-identity-by-root-commit | dbb639be3 | REAL (21) | REAL (17) | REAL (20) | REAL (18) | REAL (16) | REAL (21) | REAL (21) | REAL (17) | REAL (17) | REAL (21) |
| 74 | rolling-labels-and-compact-picker-tabs | dbb639be3 | REAL (21) | REAL (21) | REAL (21) | REAL (20) | REAL (18) | REAL (21) | REAL (21) | REAL (6) | REAL (6) | REAL (22) |
| 75 | small-leftovers | c87daf244 1de3b4bae 94304283a e7ddbbe72 8646bde07 591824881 cc7da5c2a 987bb2f87 187b40243 e16cc3e99 | REAL (229) | REAL (230) | REAL (207) | REAL (223) | REAL (212) | REAL (229) | REAL (228) | REAL (113) | REAL (105) | REAL (229) |

## Totals

| Totals | Base | O1 mirror | O2 mergiraf | O3 tests | O3 all | O4 tol0 | O4 tol5 | O5 residual | Stack | PR39 |
|---|---|---|---|---|---|---|---|---|---|---|
| tickets CLEAN | 1 | 1 | 1 | 2 | 2 | 1 | 1 | 2 | 2 | 1 |
| tickets TRIVIAL | 1 | 1 | 2 | 0 | 0 | 1 | 1 | 0 | 1 | 1 |
| tickets REAL | 56 | 56 | 55 | 56 | 56 | 56 | 56 | 56 | 55 | 56 |
| tickets ALREADY | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| tickets — | 5 | 5 | 5 | 5 | 5 | 5 | 5 | 5 | 5 | 5 |
| commit-slices CLEAN | 5 | 5 | 7 | 7 | 8 | 5 | 5 | 6 | 9 | 5 |
| commit-slices TRIVIAL | 6 | 7 | 8 | 5 | 5 | 6 | 6 | 6 | 7 | 5 |
| commit-slices REAL | 80 | 79 | 76 | 79 | 78 | 80 | 80 | 79 | 75 | 81 |
| commit-slices ALREADY | 3 | 3 | 3 | 3 | 3 | 3 | 3 | 3 | 3 | 3 |
| conflict regions (distinct slices) | 2099 | 2018 | 1953 | 2020 | 1858 | 2096 | 2076 | 1565 | 1376 | 2084 |

## Time-saved estimate

Assumptions (no port was timed; the 2026-10-28 report found no timings either):
- per distinct commit-slice: CLEAN 15 min, TRIVIAL 30 min, REAL 60 min fixed (read the change, build, test, commit),
  plus **2 or 4 minutes per conflict region** (agent resolution + human review);
- ticket overhead (writing up, PR, CI) is the same under every option and left out.
- Sanity check against history: the baseline comes to 154–224 h for 58 tickets, i.e. ~4–6 working weeks. Waves 4
  and 7 ran ~22–31 tickets in ~2 weeks each, so wave 10 at that pace is ~4–5 weeks: same order.

The stack column is a real run (residual, then mergiraf on what still conflicts, `Cargo.lock` regenerated), not a sum.
Not in the stack: lane ordering (−9% of regions, estimated), rerere seeding (−5% on remainders), the test move (−3% of
all regions) and the annotation (time per region). Adding those, a realistic total is **~25% less porting time on wave 10:
roughly 35–55 hours saved of 154–224**. Classes barely move (REAL tickets 56 → 55), so the saving is per-region effort,
not "no recoding".

| Hours (model) | Base | O1 mirror | O2 mergiraf | O3 tests | O3 all | O4 tol0 | O4 tol5 | O5 residual | Stack | PR39 |
|---|---|---|---|---|---|---|---|---|---|---|
| 2 min/region | 154 (+0%) | 151 (-2%) | 147 (-5%) | 151 (-2%) | 144 (-6%) | 154 (-0%) | 153 (-0%) | 136 (-12%) | 127 (-18%) | 154 (+0%) |
| 4 min/region | 224 (+0%) | 218 (-3%) | 212 (-5%) | 218 (-3%) | 206 (-8%) | 224 (-0%) | 223 (-1%) | 188 (-16%) | 172 (-23%) | 224 (-0%) |

## One-time costs and risks

| Step | One-time cost | Per-port effect | Risk |
|---|---|---|---|
| Residual mode in `translate.py` (`--residual`) | ½ day + tests | −25% regions on remainder-heavy tickets | false "already" drops a hunk: print dropped hunks, review |
| mergiraf fallback (`--mergiraf PATH`, whole-file only) | ½ day | −7% regions | pre-1.0 tool: review and build |
| `Cargo.lock` regenerate rule | 1 hour | −5% regions | none (cargo re-resolves) |
| Conflict annotation (origin commit + ledger status per region) | ½ day | faster per-region decisions | wrong blame on moved code: hint only |
| Lane ordering (upstream order within a lane) | 0 | −9% regions | none |
| Renamed mirror branch + weekly refresh | ½ day | enables rerere seeding (−5%), native cherry-pick, mergiraf driver | a second mirror to keep in sync; never merge it into `main` |
| rerere seeding from trailers | ½ day after the mirror | −5% on remainders, free re-runs | stale resolutions replay a past mistake: rerere output is reviewed like any merge |
| Move Roboco-only tests out of hot files | ½–1 day + CI | −13% hot-file regions, permanent | test churn |
| Merge the renamed mirror (`-s ours` base + weekly merges) | ADR change | none on conflicts; +16k unwanted lines/week to delete | high: re-offers removed cloud code every time upstream touches it |
| Re-baseline wholesale | — | 3 files | — |

## Recommendation

1. **Keep cherry-pick (ADR 0003). Don't merge the renamed mirror.** It doesn't lower conflicts and imports what we decline.
2. **Upgrade `translate.py`, in this order:** residual mode, the `Cargo.lock` regenerate rule, mergiraf as a whole-file fallback,
   and a conflict annotation. Together: −34% of the conflict regions in wave 10 (measured), ~18–23% of porting hours (modelled), and a per-file "already ported vs still
   to port" readout.
3. **Run each wave-10 lane in upstream order**, committing each port before the next replay.
4. **Optional, later:** the renamed mirror as a generated, local-only branch, plus rerere seeded from `Upstream-Commit:` trailers.
   Worth it once weekly batches start, because re-running a batch after `main` moves then costs nothing. Move
   Roboco-only tests out of the five hot files when one of them is next touched anyway.
5. **Expect wave 10 to stay mostly hand work.** Its tickets are the remainders of large upstream rewrites that Roboco
   re-did its own way. The tools shrink each one, but they don't make them mechanical. The big saving is still the weekly loop:
   porting within days of upstream, when the target code hasn't moved yet.

## Decisions for the user

1. Fold residual mode, the lockfile rule and the conflict annotation into `scripts/upstream/translate.py`? (Recommended: yes, before the
   wave-10 lanes start.)
2. Allow mergiraf (an external pre-1.0 binary, GPL-3) as an *optional* fallback in the tools? (Recommended: yes, opt-in flag,
   never required.)
3. Keep a generated renamed mirror branch (`zeron-renamed/main`) for rerere/blame? (Recommended: later, local-only, not pushed.)
4. One-time move of Roboco-only tests out of the five hot files? (Recommended: yes, opportunistically.)
5. ADR 0003 stays as is: no merging. (Recommended. The measurement backs it.)

## User decisions (2026-10-29)

The user accepted every recommendation ("do ur rec"):

1. Yes: residual mode, the `Cargo.lock` regenerate rule and the conflict annotation go into `scripts/upstream/translate.py` before the wave-10 lanes start.
2. Yes: mergiraf as an *optional*, opt-in fallback (used only when it fully resolves a file).
3. Later: the generated renamed mirror (local-only, not pushed) for rerere/blame.
4. Yes, opportunistically: move Roboco-only tests out of the five hot files the next time a port touches one of them.
5. ADR 0003 stays: no merging of upstream or the renamed mirror.

## Scripts (`C:\tmp\upstream-less-recoding\scripts\`)

| Script | What |
|---|---|
| `tickets.py` | ticket → upstream commits (ticket text + ledger `ticketed wave10-NN`) → `out/tickets.json` |
| `baseline.py` / `bl_lib.py` | `translate.py`'s `replay()` per ticket, slicing giant bundles to the ticket's files; dumps conflict triples |
| `renamed_mirror.py` | the filter-repo renamed mirror (kept here as `renamed-mirror-proto.py`) |
| `cp_renamed.py` | option 1: plain `git cherry-pick -n` from the mirror |
| `mergiraf_run.py`, `mg_best.py`, `mg_verify.py` | option 2 |
| `hunks.py`, `test_regions.py`, `opt3.py` | conflict contents; option 3 |
| `divergence.py`, `opt4.py` | option 4 |
| `residual.py` (kept here as `residual-proto.py`), `prereq.py`, `rerere_seed.py`, `stack.py` | option 5 |
| `week_compare.py` | merge vs pick for one upstream week |
| `table.py` | the tables above |
