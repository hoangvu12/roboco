# Orchestrator handoff: web removal, CI speed, upstream wave 10 (2026-10-28)

Written by the release orchestrator ("Fleet Follow Release v0.14.0", chat `4e28615f`) for the new
implementation orchestrator. Read this whole file, then `AGENTS.md`. Keep this file current as you
go (state, decisions, PRs). If you run low on context, update it and hand off the same way.

## The user's instruction (2026-10-28)

"spawn a new chat as orchestrator, then spawn side chats or subagents to implement all of them",
meaning all three specs below. The user prefers **short, plain-language** status updates, no jargon dumps.

## State at handoff

- **v0.14.0 is released** (`1ccaa3a8b` on `main`, tag pushed, all 13 assets verified, notes include the
  Tailcat re-pair warning). `main` = `origin/main` = `1ccaa3a8b`. Nothing is in flight on GitHub.
- The main checkout `C:\Users\HP MEDIA\Desktop\nguyenvu\roboco` is on `main`, clean apart from
  **untracked** planning files: `.scratch/web-removal/`, `.scratch/ci-speed/`,
  `.scratch/upstream-ports-2026-10-wave10/`, `.scratch/upstream-drift/2026-10-28*.md` +
  `audit-2026-10-28/`, this folder, and the old `.scratch/fleet-follow/handoff-2026-10-28.md` (fleet-follow is
  deleted since; readable at `42cbc82ac`).
  **Worktrees branched off `main` will NOT contain these untracked files.** First step: commit the
  planning docs (explicit-path `git add` only) so workers see them. Either push a docs-only commit to `main`
  or open a quick docs PR; `.scratch/` changes don't need the full CI.
- An empty locked folder `..\roboco-ff-08` is left over. Delete it when possible.

## The three specs (all decisions recorded at the bottom of each spec.md, "User decisions (2026-10-28)")

1. **`.scratch/web-removal/`**: remove the web client and the desktop in-app browser; keep previews
   ("Ports"). 11 tickets, **one PR**. Key trap: today only the in-app browser tab keeps the
   desktop's preview proxy (7332) alive, so ticket 07 must land before 08. Android's WebView preview
   flow (`PreviewFlowTest`) stays and must keep passing. Ticket 01 creates and pushes the `web-final`
   tag (approved).
2. **`.scratch/ci-speed/`**: 21 tickets (research in `research-2026-10-28.md` +
   `research-web-2026-10-28.md`). Target is a PR wait of about 22–25 min, down from 76. Ticket 06's
   web lines and ticket 10 wait for the web-removal PR. The `GRADLE_ENCRYPTION_KEY` repo secret already exists.
   **No paid anything.**
3. **`.scratch/upstream-ports-2026-10-wave10/`**: 78 tickets in lanes:
   - 0 setup (01 recreate the `zeron/main` mirror);
   - 1 fixes (02–28: crashes and security first, then Windows, agents, sidebar/chat);
   - 2 messages (29–32);
   - 3 Android (33–43);
   - 4 features (44–75).

   **76–78 are parked (needs-triage): do NOT implement them.** Plain-language overview:
   `.scratch/upstream-drift/2026-10-28-summary.md`; evidence: `.scratch/upstream-drift/2026-10-28.md` +
   `audit-2026-10-28/`.

## Suggested order and parallelism (your call within these constraints)

- **Web removal first** (one PR): it deletes about 206k lines and touches the engine listener, `crates/ui`,
  every workflow and the TS derives in `crates/proto`. Wave-10 work started before it merges will
  conflict, and wave-10 tickets assume wiregen is gone ("proto change", no TS).
- **CI tickets next**, or in parallel where they don't touch the same workflow lines as the web PR:
  01, 02, 07, 11, 12, 13 are good early candidates. Every PR before CI lands costs about 76 min of CI,
  so faster CI pays back immediately.
- **Wave 10** after web removal merges. Run one implementer side chat per lane branch (the wave-4–9
  rhythm: `wave10/<lane>` branches off `main`, one commit per ticket, ticket order, merge after
  end-of-pass verification). Lanes can run in parallel, but watch for file overlap. Fixes go first,
  crashes and security (02, 03) at the very top.
- GPUI pin sync (wave-10 57) gates the terminal overhaul and some UI tickets. **Pushing to
  `hoangvu12/zui` / `hoangvu12/gpui-component` needs the user's OK at that time**. Ask first.

## Authority (what you may do without asking)

- Create worktrees and branches, spawn side chats or subagents, commit (explicit-path adds only), push
  branches, open PRs on `hoangvu12/roboco`, and **merge a PR into `main` with a merge commit** once
  every required check is green AND you've verified the tickets' acceptance criteria.
- Update ticket `Status:` lines and append `## Comments` evidence as work lands.
- **Standing user instruction (2026-10-28): "just clean up everything once it's merged."** After a PR
  merges (verify with `git merge-base --is-ancestor origin/<branch> origin/main`), do all of this without asking:
  - remove its worktree;
  - delete the local and the **remote** branch;
  - drop its sccache basedir;
  - archive its worker chat.
- **Ask the user first** before:
  - tagging or publishing any release;
  - pushing to the zui / gpui-component forks;
  - deleting remote branches that are **not** merged (`windows-native-support` = PR #313 on zeron: never);
  - anything paid;
  - anything that changes a recorded decision;
  - acting on parked tickets.
- Some steps can't be automated (manual checks of desktop previews in Chrome/Edge over LAN and
  Tailcat, Android previews, pairing; tickets marked ready-for-human). Do everything automatable, then
  give the user a short checklist.

## Hard rules (AGENTS.md + lessons)

- Explicit-path `git add` only. **rerere stays on.** **No force pushes.** Never merge upstream or the mirror
  into `main`.
- **Every push to a PR branch cancels its in-flight CI runs.** Batch fixes; don't push while a run you
  need is still going unless a check has actually failed and the fix is ready.
- **Worktrees:**
  - Every side chat that edits files gets its own `git worktree add ../roboco-<slug> -b <branch> main`.
    Pass that path as its `cwd`. Never switch branches in the main checkout.
  - Add each worktree root to `basedirs` in `C:\Users\HP MEDIA\AppData\Roaming\Mozilla\sccache\config\config`.
    Restart sccache (`sccache --stop-server; sccache --start-server`) only when no builds are running.
    The user's other project (`kyoko` lanes) shares this sccache.
  - When a worker finishes: `git worktree remove`, delete its merged branch, drop its basedir, archive its chat.
- **Host notes:**
  - Windows 11, 20 threads. Anything building gpui needs `cargo +1.99.0` until CI ticket 12 pins the
    toolchain; default stable is 1.93.
  - `roboco-ui --lib` tests need `--test-threads=1` locally.
  - No Android SDK locally: Kotlin compiles only in CI.
  - No nextest locally.
  - PowerShell 5.1 mangles `gh --jq` quoting, so use the Bash tool.
- **Host-only test failures (not real):**
  - `source_control` git tests (global `url.https://github.com/.insteadof`);
  - `codex_availability_finds_npm_cmd_shim` (Codex on this PC's registry PATH).
- Known CI facts: Windows `tests` takes about 63 min warm and up to about 95 cold (timeout 120);
  `ui-tests` timeout 45. The Android tablet suite has never been green on `main` (CI ticket 11 triages it).
- Port commits record the upstream SHAs they carry and any excluded slices
  (`docs/reference/upstream-ports.md`). Renames per AGENTS.md.
- Side chats you create report back to you automatically. Use `wait: false` and end your turn; don't
  block on `wait_for_turn` for long work.

## Reporting

After each PR merges, tell the user in 2–4 plain sentences what changed for them and what's next.
Keep a running "PRs" list in this file.

## Orchestrator #3 log (chat `aed16e2d`, from 2026-10-29)

| chat | worktree / branch | scope | state |
|---|---|---|---|
| `82cbdf25` | `..\roboco-web-removal` / `feature/web-removal` | #39: merge main, strip #38's TS derives, fix `engine-local-regressions` + `ui-tests`, push once, watch CI | running (my turn; notice comes to me) |
| `bdd1bbe1` | `..\roboco-ci-windows` / `ci/windows-split` | #43 CI 03→04→05 | **merged `060f7e3b0`**, cleaned up (empty locked folder may remain). Remote scratch branch `ci/scratch-startup-broken` (unmerged; ticket 03's broken-expectation proof): delete needs the user's OK. After-merge: first main run must log `Cache saved` for `windows-tests` + `windows-app`; then warm p50s (≥5 PR + ≥3 main runs) and cache size ≤ 3.5 GB → tickets 04/05 boxes (CI 09). 03's cursor_shim Linux box closes with 06 (#44). |
| `a7b1639c` | `..\roboco-ci-linux-android` / `ci/linux-android-tiers` | #44 CI 06, 08, 23 | **merged `787a048cf`**, cleaned up. Red core-tests was a stale harness test (fixed). New ticket 25 (tablet picker / failed-open, needs-triage; tests tagged `@PhoneOnly`). Deviation accepted: engine/harness-only PRs don't trigger android.yml at all (APK doesn't link them; dispatch for full tier). `ci:full` label not created yet. After-merge: caches seeded, ≥10-run measurements, 2 green nightly backstops, 3 green nightly tablets. Web overlap (ROBOCO_WEB_DIST/webkit apt in ui-tests.yml, room_relay in test-core.sh, crates/theme in android-changes.sh) handed to `82cbdf25` (steer sent). |
| `4879f773` | `..\roboco-ci-linux-arm64` / `ci/linux-arm64-glibc` | CI 14 (Linux tests on arm64) + 21 (glibc 2.35 floor via 22.04 container), one PR | running (turn ended while waiting on its background CI monitors; they re-invoke it). Dispatched release.yml on its branch: verified `publish` is tag-only, so nothing publishes. Pushed scratch A/B branches (`ci/scratch-arm64-ab` + a GLIBC_MAX=2.30 negative-test branch): unmerged, delete only with the user's OK |
| `b170d20f` | `..\roboco-ci-android-speed` / `ci/android-emulator-speed` | CI 22 → 16 → 17, one PR; 22's named-test retry rule = separate commit, needs the user's OK on ticket-11 defaults before merge | running |
| `6798cc35` | main checkout, read-only (prototypes `C:\tmp\upstream-less-recoding\`) | research: less re-coding per upstream port (renamed mirror via filter-repo, mergiraf, hot-file split, re-baseline) measured on wave-10 02–32/44–75 → `.scratch/upstream-sync-research/report-2026-10-29-less-recoding.md` | **done** (`505d60bb4`), archived. Result: merging even a renamed mirror = 249 conflicted files/week + revives declined code → ADR 0003 stands. Remaining wave-10 tickets are hard leftovers (56/58 REAL); best combo (translate.py residual mode + mergiraf only when it fully resolves a file + regenerate `Cargo.lock` + per-conflict notes; lane-ordered porting) cuts conflict spots 2,099→1,376 (−34%), ≈ −20–25% porting time (≈35–55 h). Wholesale-take and hot-file refactors don't pay. **Asked the user** decisions 1–5 (report §decisions); not yet answered. |

| `d72f1cd9` | `..\roboco-ci-windows-arm64` / `ci/windows-tests-arm64` | CI 15 (Windows `tests` on arm64; nightly x64 = option b) | running |
| `8539230d` | `..\roboco-upstream-translate` / `tools/translate-residual` (no sccache basedir: Python only) | translate.py: residual mode, Cargo.lock regenerate, conflict notes, opt-in mergiraf; docs (lane order, hot-file test moves) | running |

- **User decisions 2026-10-29 ("do ur rec"):** CI 15 = option (b) nightly x64; CI 11 defaults OK (22's retry
  rule approved); upstream less-recoding recs 1, 2, 4, 5 yes, 3 (renamed mirror) later; delete scratch branches:
  `ci/scratch-startup-broken` deleted; `ci/scratch-arm64-ab` + `ci/scratch-glibc-floor` (from `4879f773`) delete
  once that worker is done (covered by the same OK).
| `955e487b` | main checkout, read-only | research (user ask 2026-10-29): where the time goes + how to speed up ticket throughput and Roboco dev in general → `.scratch/dev-velocity/report-2026-10-29.md` | running |

- Ticket count 2026-10-29: 131 total (web-removal 11, ci-speed 25, wave-10 78, fleet-redesign 17): ≈26 done,
  ≈17 in flight, ≈83 not started (+3 parked).
- #12 closed (user OK 2026-10-29; branch `web-pierre-adoption` kept). #16 Mimir stays open (user: not web-related;
  it's mostly engine/harness/ui/`integrations/mimir`, plus 18 `web/` files + wiregen that #39 deletes; last
  updated 2026-10-03, so it needs a big refresh if revived).
- Next: review #39 when `82cbdf25` reports; review/merge #43/#44 when green; relay the research
  recommendation to the user in one short message.

## >>> HANDOFF #3 (2026-10-29, from `efcef7d0` to the next orchestrator): START HERE <<<

The user asked `efcef7d0` to hand off to a new orchestrator chat. New orchestrator: **`aed16e2d`** ("Roboco Orchestrator #3"). Everything below (handoff #2, the
`efcef7d0` log, the specs) still applies; this section is the exact state now. `main` = `07b155573`.

**Merged since handoff #2** (all cleaned up: worktree, local + remote branch, basedir, chat archived):
#35, #36 (CI 01/02/07/12/13), #37 (fleet-redesign 01), #40 (fleet-redesign 17 + 16 built), #41 (upstream
sync ledger + `scripts/upstream/` tools), #38 (wave-10 33–43; 41 core only), #42 (completion_notices race;
CI 24). Ticket count: 19 of 127 done, 2 mostly done (wave-10 41 gesture; fleet-redesign 16's user gate).

**Open PRs, what each needs**
1. **#39 web removal** (`feature/web-removal`, worktree `..\roboco-web-removal`, worker `82cbdf25` idle).
   Finished 2026-10-28 05:41Z; its completion notice went to the archived `00743a45`, so **nobody has
   reviewed it**. Read its final report with `read_chat 82cbdf25`. State: `engine-local-regressions` and
   `ui-tests` red (the first is very likely the completion_notices race fixed by #42); it's far behind main
   (#38 #40 #41 #42 merged since). **#38 added two proto structs with `#[derive(TS)]` (`RelayAgentLoginCallback`
   etc. in `crates/proto/src/entities.rs`) plus wiregen/`web/` generated edits, which #39 deletes: the merge
   must strip those derives and drop the generated files.** Also: golden PNGs still show "Previews"; ADR
   number is 0012 (fleet-redesign takes 0013). Send `82cbdf25` a message (mode run; it's idle, so the new
   turn's notice comes to you) to merge `origin/main`, fix the TS derives + both red jobs, push once, wait for
   CI, report. Then review against `.scratch/web-removal/` tickets and merge. Manual ticket-11 checks are
   the user's (checklist in the PR body).
2. **#43 CI 03→04→05** (`ci/windows-split`, `..\roboco-ci-windows`, worker `bdd1bbe1`, running).
3. **#44 CI 06, 08, 23** (`ci/linux-android-tiers`, `..\roboco-ci-linux-android`, worker `a7b1639c`,
   running; one check was red at handoff).
   Workers `bdd1bbe1`/`a7b1639c` were created by `efcef7d0`, so their notices come to `efcef7d0`, not you.
   To take them over, queue a message to each (`send_messages`, `mode: "queue"`) asking it to repeat its
   final report after its current turn; that queued turn's notice then comes to you. Do the same for anything
   else still running from `efcef7d0` (`list_chats parent=efcef7d0`).
4. Old PRs **#16** (Mimir, desktop+web) and **#12** (web: @pierre/diffs): web-era, likely obsolete after
   #39. Ask the user before closing them.
- A stray worktree `C:\Users\HP MEDIA\AppData\Local\Temp\scratch-startup` on `ci/scratch-startup-broken`
  exists (probably a CI worker's scratch). Leave it unless its owner is done.
- Locked empty folders of removed worktrees free up once their chats are archived (`roboco-fix-notices`).

**Order now (user, 2026-10-29: "do all the CI speed first so we can do other stuff faster")**
CI first: #43, #44 → then CI 09 (docs + final measurement), 10 (pin actions + Dependabot; after #39),
14/15 (arm64), 16–21, 22. Web removal #39 merges as soon as it's green. Then fleet-redesign 02–11 (one PR,
`feature/connect-devices`), 12–15 after wave-10 03; wave-10 lanes (fixes first: 02, 03) using the new sync
tools. Max ~3 building workers at once (the laptop runs out of memory: "paging file too small").

**Upstream sync (decided 2026-10-28, tools merged in #41):** weekly batches after wave 10; small
CLEAN/TRIVIAL ports skip tickets (`Upstream-Commit:` trailer + ledger row); ledger + tools in the repo;
NO upstream-first PRs. Research: `.scratch/upstream-sync-research/report-2026-10-28.md` (rebase/merge
ruled out by evidence; renames are ~1.5% of upstream's changed lines; real conflicts concentrate in five
UI files rewritten on both sides; options deferred to "later": a persisted renamed mirror via filter-repo so
plain cherry-pick/rerere/mergiraf work, mergiraf trial, moving Roboco-only code out of the hot files).
**New user ask (2026-10-29), the first job of the new orchestrator:** "see how we could rebase or merge the
upstream tickets easily without having to recode, to save time". Build on that research; don't redo it.

**Waiting on the user**
1. CI 11 triage defaults (retries for named flakes; tablet non-blocking until 3 green nights).
2. Fleet-redesign 16 gate: after an update, Laptop→PC and phone→PC rows show Direct / Via relay; if never
   Direct, set `pushForDirectPath = false` in `adapters/roboco-tailcat/path.go`.
3. Manual looks: Remote access page rail/fade vs Agents; phone checklist from #38 (add account, bottom-bar
   search, model picker, agents on/off, tablet split, delete open session on tablet; search capsule is flat now).
4. First weekly triage: zeron #1089 (bubble fade + rail perf) and #1088 (access modes + plan mode).
5. Later: OK to push to the zui/gpui-component forks (wave-10 57); self-hosted DERP on SG (undecided).
6. Optional: `~/.bashrc` starts with a UTF-8 BOM (every Bash call prints a harmless `command not found`).
- Git: the restored `.git/config` has no `kratos` remote (AGENTS.md lists it; `../Kratos` doesn't exist
  locally). Leave it.

## >>> HANDOFF #2 (2026-10-28, from `00743a45` to the next orchestrator): superseded by #3 above <<<

The user asked `00743a45` to hand off to a new orchestrator chat and to open a separate standalone
**fleet design discussion chat**. New chats: orchestrator **`efcef7d0`** ("Roboco Orchestrator #2");
fleet design discussion **`9a104962`** (writes its outcome under `.scratch/fleet-redesign/`).
`00743a45` stopped its own watchers and is idle. State at handoff (≈ 02:30 local):

**GitHub**
- **PR #35** (`ci/caches-windows`, CI 01/12/13/02): every check green except `android-emulator`,
  which is re-running (attempt 2 of run 40609187561; attempt 1 = the known `KeyboardFlowTest` flake,
  CI ticket 22). Reviewed OK (see PRs below). **Merge with a merge commit when it's green.** Then
  remove worktree `..\roboco-ci-caches`, delete local branch `ci/caches-windows` (the remote branch
  only with the user's OK), drop its sccache basedir, and archive chat `8ccd84a5`.
- **PR #36** (`ci/mobile-bindgen`, CI 07): every PR check green. It also needs the **dispatched
  android.yml run 40609763310** (attempt 2) to pass `android-benchmark-apk` (R8 + mobile-dist core =
  the release path this PR changes). Attempt 1 failed in `sdkmanager` (corrupt NDK zip), which is not
  code. Its `android-emulator`/`-tablet` jobs in that dispatch are known flakes/red and not required.
  Reviewed OK (`HostCore.needed` is conservative). Merge when the benchmark job is green; same cleanup
  (`..\roboco-ci-bindgen`, chat `0bf1abec`).
- Merge order: either first. The second gets an update from `main` only if GitHub says it conflicts
  (both touch `Cargo.lock`? #36 does, #35 doesn't; likely no conflict).
- After each merge: tell the user in 2–4 plain sentences; record measurements later (CI tickets
  say "measure after merge": the first main push seeds the caches, and the next PR must restore).

**Workers still running**
- `82cbdf25`, web removal, `..\roboco-web-removal` / `feature/web-removal`: tickets 02–06
  committed (`3fa2fc34e..68ed1bfd0`), working on 07 (ports card → system browser + shell-owned
  watcher), then 08–10 and 11's automatable parts. It will push, open ONE PR and reply. **Its
  completion notice goes to `00743a45`, not to you.** To receive it, queue a message to it
  (`send_messages`, `mode: "queue"`) asking it to repeat its final report after its current turn. The
  queued turn is then yours, and its notice comes to you. Don't steer it mid-turn.
- Every other side chat of `00743a45` is done and archived.

**Background watchers** of `00743a45` die with it. Set up your own `gh pr checks --watch` (Bash tool,
`run_in_background`) for #35/#36 and the dispatch run.

**Fleet: DECIDED (design discussion done).** The plan is **`.scratch/fleet-redesign/`** ("connect
once"; spec + tickets 01–15). The old fleet specs (`fleet-merge`, `fleet-agents`, `fleet-follow`,
`rooms`) are deleted (readable at `42cbc82ac`); fleet-agents became fleet-redesign 12–15. Schedule:
01 alone now (in flight); 02–11 one PR on `feature/connect-devices` after web removal merges; 12–15
their own PR after that (blocked by wave-10 03, IPC token). Its ADR is **0013** (web removal takes 0012).

**Upstream sync (user decisions 2026-10-28):** weekly batches after wave 10; small CLEAN/TRIVIAL ports
skip tickets (trailer + ledger row); ledger + tools in the repo; NO upstream-first PRs to zeron. Wave-10
ports use `translate.py` as the first step once the tooling PR lands.

**Queue after web removal merges** (unchanged): CI 03→04→05, 06, 08, then 09; CI 10 after web
removal; CI 22–24 (from the 11 triage); wave 10 lanes (fixes first: 02, 03; wave-10 03 is also a
prerequisite of fleet-redesign 12–15); fleet-redesign 02–11, then 12–15.

**Pending user questions** (ask when relevant; don't block on them):
- CI 11 triage defaults (tablet nightly + non-blocking until 3 green nights; nextest retries with
  flaky reporting; JUnit retry rule on named phone flakes). Awaiting OK.
- Wave-10 57: pushing to `hoangvu12/zui` / `gpui-component` needs the OK at that time.
- Fleet speed tickets (show direct vs relayed Tailcat, prefer LAN, reuse the open connection): proposed
  to the user by `9a104962` (Tailcat measured ≈ 160 ms RTT, ≈ 1.2 MB/s via the Tokyo DERP). Not filed
  until the user says yes.
- Upstream #1088 (access modes + plan mode) is not in wave 10 and needs its own drift review + ticket.

**Housekeeping**
- sccache server not restarted since the basedirs were added (other builds were running). Restart
  only when no `cargo`/`rustc` is running (`tasklist | grep -i -E "cargo|rustc"`).
- `rust-toolchain.toml` becomes 1.99.1 on `main` once #35 merges. After that, plain `cargo` works and
  `cargo +1.99.0` notes go stale.
- Nit for the next CI PR (03/04): `package-windows.ps1` uses `[IO.Path]::IsPathFullyQualified`
  (missing in Windows PowerShell 5.1).

## Orchestrator #2 log (chat `efcef7d0`, from 2026-10-28 ≈ 02:50)

- Took over. Queued a takeover message to `82cbdf25` (its next turn, and its completion notice, are now mine).
- Watchers (Bash background): #35 `android-emulator` job 122579187946; dispatch run 40609763310 `android-benchmark-apk` job 122578974077.
- **Started wave-10 lane 3 (Android) early**: it barely overlaps web removal (only the Android "Ports"
  label, which the worker is told not to touch; ticket 36, the only proto change, goes last after
  merging `origin/main`). Worker `66cf7296`, `..\roboco-w10-android` / `wave10/android` (sccache
  basedir added). Scope 33–43 (36 last); not 59; ends with ONE PR, I merge.

- **#36 merged** (`d7b6887d7`; dispatch benchmark APK green, its emulator red = known flaky suite) and
  **#35 merged** (`6ee2e4731`, emulator re-run green). Worktrees `roboco-ci-bindgen`/`roboco-ci-caches`
  removed, local branches deleted (remote branches kept: deleting needs the user's OK), basedirs
  dropped, chats `0bf1abec`/`8ccd84a5` archived. `rust-toolchain.toml` = 1.99.1 on main now: plain
  `cargo` works in worktrees that have merged main. **Still to do: measure** (CI 01/12/13/02/07 say
  "measure after merge": the first main push seeds caches, the next PR must restore).
- **Fleet design outcome landed** (from `9a104962`): `.scratch/fleet-redesign/` ("connect once"),
  committed to main (`912d8e797`, then `28440047b` by `9a104962`: old fleet folders deleted,
  fleet-agents absorbed as fleet-redesign 12–15, research note moved, ADR renumbered **0013**).
  Fleet-redesign 01 (settings page scrolls) ships alone now; **02–11 = one PR on
  `feature/connect-devices` after web removal merges; 12–15 their own PR after that.**
- **Crash, 2026-10-28 ≈ 09:10 local (unclean shutdown / engine restart).** `.git/config` was zero-filled
  (another chat restored it: core, origin, upstream, `branch.main`, `branch.zeron/main`, `rerere.enabled`;
  the `kratos` remote isn't in it and `../Kratos` doesn't exist). 13 files of the #36 fast-forward in the
  main checkout were zero-filled → restored from git. Half-made `roboco-settings-scroll` worktree
  recreated. Lesson: after a crash, scan worktrees for NUL-filled files before trusting them.

| chat | worktree / branch | scope | state |
|---|---|---|---|
| `82cbdf25` | `..\roboco-web-removal` / `feature/web-removal` | web-removal 02–10 + automatable 11 | running (07) |
| `66cf7296` | `..\roboco-w10-android` / `wave10/android` | wave-10 33–43 | **PR #38** open (all 11 committed; 41 partial: drag gesture follow-up; 36 carries a TS derive since web removal hadn't merged); review batch applied (`b5b0ce766`); JVM/compile green. **`android-emulator` device loss** (emulator drops off adb 8–10 min in, every run incl. the first push; 60-min cancel = cleanup adb with no timeout). 13:00: diagnostics patch + parallel bisect via `bisect/pr38-NN` dispatch branches (delete them after; they're unmerged → tell the user). Earlier: review done → 1 blocker (`OneRowBarShellTest` unplaced-node assert; same in `SessionFlowTest`) + 9 should-fix + nits sent back as one batch (11:50); 36's requester = per-sign-in UUID secret, documented as deviation |
| `8b19126f` | `..\roboco-settings-scroll` / `fix/settings-scroll` | fleet-redesign 01, own PR | **PR #37** open, reviewed OK; worktree removed (branch pushed); chat archived; mobile-core flake `busy_sends_queue_steer_and_stop_and_send` re-run |
| `1e9159f7` | main checkout (report only, prototype in `C:\tmp`) | research: faster upstream sync → `.scratch/upstream-sync-research/report-2026-10-28.md` (+ user decisions) | done, archived |
| `2f7a0fd7` | `..\roboco-upstream-tools` / `tools/upstream-sync` | ledger (`docs/reference/upstream-ledger.tsv`), `scripts/upstream/{translate,presence,triage}.py`, weekly-loop docs; one PR | running |
| `3f08770e` | `..\roboco-remote-speed` / `feature/remote-speed` | fleet-redesign 17 then 16 (user-approved speed tickets, `1e9bc22a5`), one PR | running |

- This machine is the user's **Laptop** (not the PC): 16's gate checks Laptop↔PC first, phone↔PC to compare.
- Cleanup (user request, 10:30): archived `8b19126f`, `00743a45` and the done spec/research chats
  `e1d7dca3`, `0f6f111d`, `95b2ff67`, `9f7fb723`. Merged remote branches `ci/caches-windows`,
  `ci/mobile-bindgen` deleted (user OK, 10:40). Main checkout `target/` kept (user didn't approve deleting it).
- 16's gate needs the user's real Laptop↔PC / PC↔SG/US measurements: the worker leaves a checklist;
  pings stay only if at least one link goes direct (path label ships either way). Self-hosted DERP on
  SG: undecided, not ticketed.

## Implementation orchestrator log (chat `00743a45`, from 2026-10-28)

### Done by the orchestrator

- `6126c05d6` on `main`: planning docs committed (specs, tickets, research, scripts, audit `.md`).
  Raw CI data (`.scratch/ci-speed/data/`) and audit `*.txt`/`*.tsv` dumps stay **untracked**
  in the main checkout only; workers read them by absolute path.
- Web-removal ticket 01: annotated tag **`web-final`** on `6126c05d6`, pushed; `git show` checks OK.
- Wave-10 ticket 01: `zeron/main` recreated, tip **`636b16516`** (one commit past the audit window:
  #1089 bubble fade + rail perf; not taken, needs its own drift review after 30/63).
- `..\roboco-ff-08` removed.
- sccache `basedirs` += `roboco-web-removal`, `roboco-ci-caches`, `roboco-ci-bindgen`.
  **Server not restarted yet** (other builds were running) — restart at a quiet moment.

### Workers (side chats of `00743a45`)

| chat | worktree / branch | scope | state |
|---|---|---|---|
| `82cbdf25` | `..\roboco-web-removal` / `feature/web-removal` | web-removal 02–10 + automatable 11, one PR | running (02–06 committed) |
| `8ccd84a5` | `..\roboco-ci-caches` / `ci/caches-windows` | CI 01, 12, 13, 02, one PR | **PR #35** open; reviewed OK; emulator re-run pending |
| `0bf1abec` | `..\roboco-ci-bindgen` / `ci/mobile-bindgen` | CI 07, one PR | **PR #36** open; PR checks green; dispatch benchmark re-run pending |
| `bde0242e` | main checkout, read-only | user bug: two desktops → two fleets + QR too dense to scan; diagnosis | done → spec `fleet-merge` (superseded by `fleet-redesign`, deleted) |
| `a53396d3` | main checkout, read-only | research: how upstream drives other devices | done → research now at `fleet-redesign/research-agents-2026-10-28.md`; spec + tickets updated; fleet-agents now also blocked by wave-10 03 (IPC token); archived |
| `76731858` | main checkout, read-only | CI 11 triage (report only) | done → `ed9cd3e19` (ticket 11 + follow-ups 22–24); archived |

### Next

- Review + merge the CI PRs as they go green; web-removal merges `origin/main` before pushing.
- After web removal merges: CI 03→04→05, 06, 08, then 09, 10 (10 waits for web removal);
  wave 10 lanes start (fixes first: 02, 03).
- CI 11 triage defaults recorded in ticket 11 (tablet nightly + non-blocking until 3 green nights;
  nextest retries with flaky reporting; JUnit retry rule on named phone flakes; follow-ups in
  `ci-speed/issues/22–24`). **Awaiting the user's OK**; ticket 11's decision box stays open until then.
  22–24 are new CI work items (23 rides with 08; 06 must turn on flaky-pass reporting).
- Ask the user: pushing to zui forks (wave-10 57).
- *(Superseded by `.scratch/fleet-redesign/`; kept as history.)* **New effort (user-approved 2026-10-28): `fleet-merge`**, a fourth spec. Pairing merges
  fleets automatically (bigger wins, tie → acting desktop, no prompt); `RoomJoin { replace }` +
  `rooms-v3`; phones re-fetch on reconnect; QR tiles sized/crisp + Enlarge; fleet-invite QR dropped
  (Copy only); CLI QR dark-on-light + width hint. ADR 0011 amendment approved. One PR,
  `feature/fleet-merge`, starts after the web-removal PR merges (05 could start earlier). The user
  was given a paste-the-SG-invite workaround for today. Open: which QR they failed to scan.
- *(Absorbed into fleet-redesign 12–15.)* **New effort: `fleet-agents`** (5 tickets). Agents' MCP tools create/drive chats on any
  fleet member: engines hold fleet sessions on each other, tools route by device, completion notices
  cross machines. This is rooms spec **user story 15**, never ticketed or built (I wrongly called it
  new at first). The user expects it; it runs right after the fleet-merge PR.

- 2026-10-28 ~00:00: an engine restart interrupted all three workers; each was told to resume.
- Upstream moved: mirror now `ab189159f` (v0.2.128). #1088 access modes + plan mode is **not** in
  wave 10 (noted in wave-10 01; needs its own drift review + ticket).
- Nit for the next CI PR (03/04): `package-windows.ps1` uses `[IO.Path]::IsPathFullyQualified`,
  which Windows PowerShell 5.1 lacks; it only runs when `-AdapterPath`/`ROBOCO_TAILCAT_EMBED` is set.

## PRs

- #35 merged `6ee2e4731`, #36 merged `d7b6887d7` (2026-10-28, orchestrator #2).
- #38 merged `3395065c0` (wave-10 33–43; 41 core only, drag gesture follow-up; 36 carries TS derives → web
  removal must strip them). Emulator device loss root cause: ticket 37's live-blurred search capsule froze the CI
  emulator's software GPU → capsule on `GlassTier.Fallback` (follow-up: blur only on hardware GPU). Flow-test
  diagnostics added (bounded cleanup adb, live logcat, mem/CPU log). Golden PNGs need a re-record (no CI runs
  Roborazzi). Cleaned up (empty locked `..
oboco-w10-android` folder left). 16 `bisect/pr38-*` branches deleted (user OK).
- **CI moved up (user, 2026-10-29: "do all the CI speed first"):** started without waiting for web removal.
  `bdd1bbe1` `..\roboco-ci-windows` / `ci/windows-split` = CI 03→04→05; `a7b1639c`
  `..\roboco-ci-linux-android` / `ci/linux-android-tiers` = CI 06, 08, 23. Web-line conflicts: whichever lands
  second resolves. Then 09 (docs/final measurement), 10 (after web removal), 14/15 (arm64), 16–21, 22, 24.
- **#42 merged `1c3d80b41`**: the `completion_notices` timeouts were NOT from #40. `SessionDoc::queue_command`
  wrote `id` before `originChatId`, so the drain worker could run a chat's first command without its origin
  (no notice owed). Fix: write `id` last (also queue rows). CI ticket 24 = same bug (its "20 green CI runs"
  box is checked after merge). Cleaned up. Locked empty worktree folders free up once the worker chat is archived.
- *(superseded)* **Main regression from #40:** `engine-local-regressions` (`crates/engine/tests/completion_notices.rs:293`
  timeouts) red since `9ce048640`. Fix worker `26b1ca3b`, `..
oboco-fix-notices` / `fix/completion-notices`.
- #41 merged `2edb72a7a` (upstream sync: ledger 576 rows `docs/reference/upstream-ledger.tsv`, PR table, `scripts/upstream/`
  translate/presence/triage + 31 unit tests, weekly-loop docs; no CI covers it, tests run locally). Cleaned up;
  an empty, locked `..
oboco-upstream-tools` folder is left: remove it later. **Wave-10 ports now start with
  `scripts/upstream/translate.py`; small CLEAN/TRIVIAL ports need no ticket.** First weekly triage after wave 10
  (pending: zeron #1089 bubble fade, #1088 access modes + plan mode).
- #40 merged `9ce048640` (fleet-redesign 17 done; 16 built, its gate = the user's Laptop↔PC / phone↔PC check,
  checklist in ticket 16; `pushForDirectPath` in `adapters/roboco-tailcat/path.go` turns pings off). Cleaned up.
- #37 merged `f1e6ae5fa` (fleet-redesign 01, settings page scrolls; mobile-core flake re-run green). Remote
  branch `fix/settings-scroll` kept (deleting needs the user's OK). Manual: rail/fade vs Accounts page.
- **#35** `ci/caches-windows` (CI 01, 12, 13, 02): toolchain pinned to 1.99.1; main-only cache
  writers; Windows on push:main; AVD cache dropped; Windows dedupe with `-AdapterPath`. Worker
  deviations accepted: per-run concurrency group for non-PR runs; `android-emulator` is the Linux
  `android` cache writer; Go cache kept on Linux jobs; preview-tests on ubuntu-24.04. After merge:
  measure (first main push seeds keys; next PR must restore).
- **#36** `ci/mobile-bindgen` (CI 07): new `roboco-mobile-bindgen` crate; Gradle `HostCore.needed`
  skips the host core for APK/device-only task graphs; `--check` byte-identity guard. Kotlin
  byte-identical locally (both profiles). It also changes the **release** core path, so besides PR
  CI I dispatched `android.yml` with `perf_apk=true` on the branch (run 40609763310: benchmark
  APK = R8 + mobile-dist core) as the pre-merge proof. Merge needs both green.
  Attempt 1: benchmark failed in `sdkmanager` (corrupt NDK zip on the runner, before any build);
  re-run (attempt 2). Its emulator/tablet failures there are the known flakes/tablet red.
- #35 attempt 1: every check green except `android-emulator` = `KeyboardFlowTest.interactiveDismiss…`
  10 s timeout (known flake, CI ticket 22; #35 touches no app code) → re-ran failed jobs.
