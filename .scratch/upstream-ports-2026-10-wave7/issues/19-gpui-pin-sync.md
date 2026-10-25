# 19 — GPUI pin sync: zui 0966d065 → 361ff20b, gpui-component 4764fd00 → 701ca97a

**What to build:** Sync the GPUI pins per the AGENTS.md rule: upstream
moved zui `0966d065 → 361ff20b` (the jump rides `afca583c` #484) and
gpui-component `4764fd00 → 701ca97a`; zeronsh/zui main confirms
`361ff20b` as tip. Our fork `hoangvu12/zui` sits at `0966d065`
(fork == upstream pin of an earlier era), so the sync is a fast-forward
over at least zui#15 `dce5c1f` (Windows native-content overlay plane)
plus whatever `361ff20b` itself carries.

**Steps:**
1. Fetch `zeronsh/zui`; verify `0966d065` is an ancestor of `361ff20b`
   and count the delta commits.
2. In `hoangvu12/zui`: fast-forward (or merge) the `roboco/sync-*`
   branch to `361ff20b` — the fork carries no divergent work (AGENTS.md:
   all fork work is merged upstream; the fork mirrors upstream exactly).
3. Push the sync branch; verify the rev exists on the remote.
4. In roboco: update the top-level `gpui`/`gpui_platform`/`gpui_tokio`
   pins to `361ff20b…` and `gpui-base` to `701ca97a…`, **and every
   `[patch."https://github.com/zeronsh/zui"]` rev together** — the patch
   rev must equal the top-level `gpui` pin rev or two GPUI copies
   appear (~50 type-mismatch errors).
5. Full `cargo check` (workspace) + build the ui examples; run the ui
   test suite slice; watch for the backdrop renderer / drag-threshold /
   fade-band regressions AGENTS.md calls out as fork-sensitive.

**Why now:** prerequisite for tickets 20 (#856) and 22 (#813 Windows
browser) — both build on `361ff20b`. Doing it as its own commit keeps
any fallout attributable to the pin, not the ports riding it.

**Excluded upstream hunks:** n/a (dependency pins, not a content port).

**Blocked by:** None (but do it in one sitting with a build — never
land a half-synced pin state).

**Status:** ready-for-agent

**Verification budget:** `cargo check --workspace` + `cargo nextest
run -p roboco-ui --lib` + one gpui example build. Source:
`.scratch/upstream-drift/2026-10-11.md` follow-up 4.

- [ ] `hoangvu12/zui` fast-forwarded to `361ff20b` and pushed
- [ ] Top-level pins + all `[patch]` revs bumped together
- [ ] Workspace check + ui tests green
- [ ] Commit records the pin SHAs and the sync branch used

- 2026-10-25: **Superseded by wave-9 ticket 11**
  (`.scratch/upstream-ports-2026-10-wave9/issues/11-gpui-pin-sync.md`) —
  upstream #966 moved the pins again (zui `0dc69aa25`, gpui-component
  `6ffe5e575`); the wave-9 ticket carries the new target and the fork-sync
  steps. Tickets 20/22 below this one stay gated on the wave-9 ticket.
