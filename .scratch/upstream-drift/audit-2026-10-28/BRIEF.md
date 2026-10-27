# Upstream gap audit — 2026-10-28 (auditor brief)

Goal: find EVERYTHING upstream `zeronsh/zeron` has that Roboco could still take — big features,
small bug fixes, one-line UI polish alike. Earlier drift reviews and ticket labels are NOT trusted:
many `.scratch` tickets still say `ready-for-*` even when shipped, and waves 1–2 left no records.
The code is the source of truth.

## Repo facts

- Roboco clone: `C:\Users\HP MEDIA\Desktop\nguyenvu\roboco`. Read `AGENTS.md` there first (rename
  mapping, rebrand boundaries, what Roboco removed). Then skim `docs/adr/0003-product-not-fork.md`,
  `docs/adr/0004-engine-local-data.md`, `docs/adr/0009-android-client.md`, `docs/reference/upstream-ports.md`.
- Roboco state to audit against = `origin/feature/fleet-follow` (= `HEAD`, commit `85be4485`; it is
  about to become `main`). `upstream/main` = `5d2b91901` (already fetched).
- Fork point (last upstream merge) = `b765f6ffb` (2026-09-15). Roboco has NOT merged upstream since;
  everything after that arrived (if at all) as hand ports, often with renamed paths/identifiers.
- Roboco = Windows + Linux desktop + engine + an Android app that is a 1:1 port of upstream's
  **iPhone** app (ADR 0009). No macOS builds, no iOS app, no cloud.

## HARD RULES (read-only audit)

- Do NOT modify the repo in any way: no checkout/switch, no branch/tag/worktree creation, no
  fetch/pull/push, no commits, no stash, no edits to tracked files, no cargo/gradle/pnpm builds.
  Other agents and a CI release run share this clone.
- Allowed: `git show`, `git log`, `git diff`, `git grep <pat> HEAD -- <paths>`, `git ls-tree`,
  Grep/Glob/Read on the working tree, `gh pr view/diff -R zeronsh/zeron`, `gh api` reads.
- Only write your own output file (named in your task) inside
  `.scratch/upstream-drift/audit-2026-10-28/`.
- PowerShell 5.1 mangles `gh --jq` quoting; use the Bash tool for `gh … --jq`.

## Evidence sources (in this folder)

- `roboco-log.txt` — every Roboco-only commit message since the fork (port commits usually record the
  upstream SHA, e.g. "Port upstream 92153025", "cherry picked from commit …", or "(#NNN)" / "zeron#NNN").
  CAUTION: Roboco has its own PR numbers #1–#34; a bare "#13" in Roboco history is usually Roboco's.
- `.scratch/` tickets: `grep -rn "<sha7>\|#<NNN>" .scratch` — records decisions (wontfix, parked,
  declined) and port SHAs. A recorded user decision (wontfix/DECLINED) => class DECLINED, cite it.
- THE CODE — decisive. For each candidate commit, take a distinctive piece of its diff (function,
  constant, string literal, test name, setting key, UI copy) and look for it in Roboco HEAD after
  applying renames (`zeron`→`roboco`, `Zeron`→`Roboco`, `ZERON_`→`ROBOCO_`, `apps/zeron/`→`apps/roboco/`,
  `sh.zeron`→`sh.roboco`). Roboco code may have moved/been refactored, so search by behaviour, not
  only by path. A message reference without the code present => PARTIAL or MISSING, say which part.

## Classes (one per commit / PR)

- `PORTED` — behaviour is present in Roboco (cite file:line or Roboco commit).
- `PARTIAL` — some of it present; state precisely what's missing.
- `MISSING` — portable and absent. These are what we're hunting.
- `DECLINED` — recorded user decision (cite the ticket file).
- `SUPERSEDED` — a later upstream commit replaced it (cite SHA), or Roboco solved it differently (cite).
- `SKIP:<reason>` — policy exclusion; reasons: `version-bump`, `landing` (website/zeron.sh),
  `ios` (Swift/Xcode/TestFlight/apps/ios only — BUT if it changes iPhone UI *behaviour* that our
  Android app mirrors, it is NOT a skip: classify the Android-parity slice), `macos-only`
  (AppKit/macOS-specific path with no Windows/Linux counterpart), `cloud` (edge, WorkOS, accounts,
  billing, sync rooms, Cloud machines, Vault-cloud), `upstream-android` (upstream's own Android app:
  reference-only, never a base), `upstream-ci` (macOS/Apple CI, upstream infra only), `docs-only`
  (unless the doc describes behaviour we have — then skip anyway).
  Mixed commits: split; classify the portable slice, mention the skipped slice.
- `N/A` — touches only code Roboco deleted, with no portable intent.

Windows-specific fixes are HIGH value (Roboco's main platform). Linux fixes too. Mobile-core crates
(`crates/mobile`, `crates/text`, `crates/markdown`) port like any crate. CI fixes for Windows/Linux/
Android jobs we also run can matter — check `.github/workflows/`.

## Output file format

```
# Part <N> — <range or PR list>
Summary: X commits — PORTED a, PARTIAL b, MISSING c, DECLINED d, SUPERSEDED e, SKIP f, N/A g

## Table
| SHA/PR | date | upstream PR | title | area | class | evidence | effort | notes |
...one row per item, no omissions, no "etc."...

## Port candidates (MISSING + PARTIAL), most valuable first
### <sha> (#PR) <title>
- What it does for users (plain words) / why it matters for Roboco (Windows? Android parity?)
- What exactly is missing in Roboco; key upstream files; Roboco files it would touch
- Dependencies (other upstream commits it needs), wire changes (`crates/proto` → wiregen), risks
- Effort S/M/L
```

Areas: engine, harness (claude/codex/cursor/opencode/pi/acp/…), proto/wire, ui-desktop, web,
android-parity (iPhone UI behaviour → our Android), mobile-core, terminal, browser/previews,
files/editor, git/source-control, settings, updater/install, ci, docs, other.

Your final chat reply: the summary line + a terse list of the MISSING/PARTIAL candidates
(one line each: sha, title, effort) + anything surprising. The full detail lives in your file.
