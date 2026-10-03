# Handoff: wave-4 continuation (post engine-lane + drift fold)

Supersedes `handoff-engine-followups.md` — its three tasks (A: pi 120s
startup budget, B: dropped-input-resolver, C: Send-next queue promotion)
are **done, committed, verified**. Read this file first.

## Current state (2026-10-02 23:54 UTC)

- **Engine lane COMPLETE, unmerged:** `wave4/engine` at `977fac61`,
  8 commits = 5 tickets (`36222bb2` 02, `7b4ed76c` 03, `711b6ed2` 01,
  `55593697` 04, `f0bd0afd` 05) + 3 follow-ups (`37c485cb` pi 120s,
  `5aec52ef` dropped resolver, `977fac61` Send next). Suites at baseline:
  engine 505/506, harness lib 297/298, `cargo check -p roboco` clean on
  host. Ready to merge into `main` in ticket order — coordinator's call.
  Never push lane branches.
- **Drift review ran (first ritual run):** fetched upstream
  `9782693b → 69e64ef5`, mirror `zeron/main` refreshed after the
  ancestry check. 11 non-merge commits: 6 CI-only excluded, 5 folded.
  Committed as `397e4dca` on `main` (spec addendum + ticket updates +
  `.scratch/upstream-drift/2026-10-02.md`). All six unstarted lanes
  fast-forwarded to `397e4dca`; engine lane untouched.
- **The fold:** #744/#745/#749 (compact-picker follow-ups) ride
  **ticket 23** (composer lane, unstarted); #706 (`01832f2c`, MCP
  standalone sessions) is new **ticket 31** on the misc lane
  (30 → 31 → 28 → 29) — the "agents can't spawn standalone chats" gap
  the user found; #707 (Todo panel) deferred to wave-5.

## What to do next

1. **Lanes to run** (spec's staging guidance, engine slot now free):
   critical path `files` (13 → 25) + `composer` (23 → 24, now carrying
   the three drift follow-ups) + `harness` (09 → 08 → 07 → 06); then
   `appearance` (26 → 27), `ui` (11 → … → 20), `misc`
   (30 → 31 → 28 → 29); `tooltips` (22) LAST after engine/ui/composer
   merge. One `/implement` per lane, spec + that lane's ticket list,
   dedicated worktree. Worktrees already staged and ff'd — no setup
   needed beyond `source ~/.bashrc_pi`.
2. **Ticket 31 is the user-wanted feature** — if the user asks for
   standalone chats sooner, `misc` can run first or `31` can run alone:
   roboco-mcp has zero file overlap with every other lane.
3. **Merging:** lane branches merge into `main` when their tickets are
   done, in ticket order; rerere is enabled. `main` moved to `397e4dca`
   under the engine lane (program docs only — no code overlap, trivial
   merge).
4. **Wave-5 planning** opens from `69e64ef5`, NOT `9782693b`. Seed
   ticket: #707 Todo panel (proto + 6 harness normalizers + doc schema
   + UI + web — wave-anchor size). The drift ritual (`git fetch
   upstream` + triage into `.scratch/upstream-drift/`) runs again at
   wave open, and ideally weekly while a wave is in flight.

## Worker env + gotchas (carried from the engine lane)

- `source ~/.bashrc_pi`; `-j 3`; no `cargo clean`; touch the file before
  check (sccache); worktree `target` already symlinked to
  `~/roboco-dev/target`; `[profile.dev] debug = "line-tables-only"` is
  manifest-level — never override per-shell.
- nextest: bare filters match test NAMES, not binaries — use
  `-E 'binary(x) or test(y)'`. `cargo nextest run -p roboco-harness`
  WITHOUT `--lib`/`--test` fails on 3 pre-existing broken examples
  (`grok_subagent_probe`, `opencode_subagent_probe`,
  `opencode_turn_probe`; upstream fixed them in df0cd298 — ticket 30
  records the decision). Always select targets explicitly; `--lib`
  cannot repeat across packages.
- fmt: ~200 pre-existing drift hunks on main (no fmt gate) — keep only
  NEW code fmt-clean (`cargo fmt --check -p <pkg>`; compare hunks
  against your diff before blaming yourself).
- **Pre-existing failures on main — do NOT chase:** engine
  `previews::preview_watch_follows_the_session_checkout_and_owning_device`;
  harness `acp::tests::antigravity_named_home_settings_preserve_business_auth`
  (ticket 09's surface). Baselines: engine 505/506, harness lib 297/298.
- Windows cross-check on this box is blocked by an environmental `ring`
  failure (identical on base); windows.yml CI covers post-merge.

## Key refs

- Spec: `.scratch/upstream-ports-2026-10-wave4/spec.md` (drift addendum
  + lane table updated; misc now 30 → 31 → 28 → 29)
- Drift triage: `.scratch/upstream-drift/2026-10-02.md`
- Ticket 31: `.scratch/upstream-ports-2026-10-wave4/issues/31-mcp-standalone-sessions.md`
  (includes the `targetDeviceId` engine-local review flag)
- Port workflow: `docs/reference/upstream-ports.md`; rebrand + rules:
  `AGENTS.md`
