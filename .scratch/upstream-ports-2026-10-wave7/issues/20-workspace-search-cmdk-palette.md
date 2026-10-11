# 20 — Unified workspace search on fff, content search, tabbed cmd+K palette (upstream #856)

**What to build:** Port `3487b507` (#856): one tabbed cmd+K palette over
commands, thread content search, and fff-driven file/content search.
`command_palette.rs` grows +2401; new `shell/search_warm.rs` (+174, warm
up the index off the interaction path) and `shell/thread_search.rs`
(+166); `spaces.rs` +1046; docs + screenshots. 29 files, +7129/−755 —
the largest non-#484 candidate.

**Why now (verified):** roboco has `command_palette.rs` but no
workspace/content search anywhere (no `workspace_search`/`content_search`
symbols in `crates/ui`) and **no `fff` dependency in the workspace yet**
— the port adds it. It stacks on the whole UI state of tickets 06–09;
port it last on the UI stack.

**Steps:**
1. `git show 3487b507` — port the palette tabs, the fff integration
   (add the dep), the content/thread search, and warm-up scheduling.
2. Adapt onto the post-06/07 sidebar state (the palette's spaces
   surfaces overlap `spaces.rs` hunks from ticket 06).
3. Port the tests and the docs (`docs/performance-workspace-search.md`
   and the command-palette README section — skip binary screenshots).

**Excluded upstream hunks:** screenshots (binary); any cloud surfaces.

**Blocked by:** 19 (pin — its tree pins zui `361ff20b`), and effectively
06–09 (UI-state stacking).

**Status:** ready-for-agent (sequence last)

**Verification budget:** `cargo check --workspace` + palette/search
tests + `cargo nextest run -p roboco-ui --lib`. Source:
`.scratch/upstream-drift/2026-10-11.md` § `3487b507`.

- [ ] `fff` dep added; palette tabs + workspace/content search ported
- [ ] Warm-up scheduling ported
- [ ] Tests ported; docs carried (no binaries)
- [ ] Commit records upstream SHA `3487b507` (#856)
