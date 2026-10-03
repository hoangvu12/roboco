# 29 — Ignore .claude and CLAUDE.md

**What to build:** Add `.claude/` and `CLAUDE.md` to `.gitignore` so
nested agent-tool scratch state doesn't dirty the tree.

**Blocked by:** None.

**Status:** ready-for-human

**Upstream SHAs:** `7d454cfe` — `.gitignore` only. Trivial carry; fold
into any open wave-4 branch or land standalone with the SHA recorded.

**Verification budget:** `git status` stays clean after creating a
`.claude/` dir and `CLAUDE.md`.

- [x] `.gitignore` entries added; status clean (created `.claude/` +
      `CLAUDE.md`, `git status --short` shows neither; removed after)
- [x] Commit records upstream SHA (`7d454cfe`)

## Comments

- Trivial carry of upstream `7d454cfe`: `.claude` and `CLAUDE.md` joined the
  existing "Local agent tooling scratch" block in `.gitignore` (next to
  `.agents/` and `agents.md`), so nested agent-tool scratch state never
  dirties the tree.
- Verification: `mkdir -p .claude && touch CLAUDE.md` → `git status --short`
  lists neither (only the lane's modified files); cleaned up afterwards.
  No cargo surface.

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
