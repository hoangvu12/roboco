# 17 — Explorer: running subagents first, longest-running on top

**What to build:** A subagent that started long ago used to sink below
everything spawned since, so the still-running one was the hardest to
find. Running subagents now lead the Subagents list, longest-running
first (which keeps the group still as new ones join at its foot); done and
failed ones follow, most recently updated first as before.

**Blocked by:** None.

**Status:** ready-for-human

**Upstream SHAs:** `f843f1ce` (#638) — 1 file,
`crates/ui/src/files/sections.rs` (Subagents section, ported in wave 3).
**Web parity (deliverable):** `web/packages/app/src/lib/explorer-sections.ts`
builds the same Subagents/Chats sections — port the running-first,
longest-running-top ordering there.

**Verification budget:** `cargo check -p roboco-ui -j 3`; targeted nextest
sections tests; web `pnpm -r build`.

- [x] Running subagents first, longest-running top; settled ordering
      unchanged (desktop + web)
- [x] Tests green (running_subagents_lead_longest_running_first ported to
      Rust + vitest; execution deferred to the wave-final batched pass —
      user directive)
- [x] Port commit records upstream SHA

## Comments

- Ported `f843f1ce` (#638) by intent. `subagent_rows`
  (crates/ui/src/files/sections.rs): after the existing newest-first sort,
  partition into running vs settled; the running group re-sorts
  oldest-first (ties keep spawn order — reverse then stable sort by
  spawned_at), then leads the settled tail, which keeps the newest-first
  order unchanged. Doc comment now states the full ordering (it also fixes
  a stale "in spawn order" first line left by the wave-3 port of bbd5f4dd).
- Tests: existing subagent_rows_list_only_stamped_spawn_chips… assertion
  order flipped to running-first; new running_subagents_lead_longest_running_first
  (90m running → 60m done → 30m running → 20m failed → 10m done → 5m running).
  subagent_rows_are_most_recently_updated_first needed no change (its one
  running row already led).
- Web parity: `subagentRows` in web/packages/app/src/lib/explorer-sections.ts
  mirrors the partition (filter/reverse/stable-sort); the vitest twin of the
  new Rust test added to tests/explorer-sections.test.ts, and the first
  case's expected order flipped like the Rust one.
- Exclusions: none — upstream's diff is sections.rs only, and the web side
  is this ticket's named deliverable.
- Verification: `cargo check -p roboco -j 3` (clean; one retry after the
  shared-target stale-proto quirk — touched this tree's proto/engine/ui
  sources per the spec remedy); `rustfmt --edition 2024 --check` on touched
  files (only pre-existing drift on untouched regions, left alone);
  `pnpm -r build` from web/ (tsc --noEmit + vite, clean). Test execution
  deferred to the wave-final batched pass (user directive).

- Wave-final batched verification (2026-10-03, merged main `cf94f415`): one
  batched pass over all lanes — ui lib 1521/1521; engine 529/530 (the one
  failure is the documented pre-existing
  `previews::preview_watch_follows_the_session_checkout_and_owning_device`
  baseline); harness 504/509 (the five failures are the documented
  environmental `#!/usr/bin/python3` fixture shebang and uid-1001
  user-database quirks; CI runs them); mcp 26/26; voice 18/18; theme 31/31;
  `wiregen --check` and `roboco-theme-export --check` fresh; web `pnpm -r
  build` green, app vitest 2122/2122, engine-client vitest green. The
  deferred test-execution criterion is demonstrated; closed by the
  wave-final pass.
