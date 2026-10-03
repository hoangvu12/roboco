# 29 — Ignore .claude and CLAUDE.md

**What to build:** Add `.claude/` and `CLAUDE.md` to `.gitignore` so
nested agent-tool scratch state doesn't dirty the tree.

**Blocked by:** None.

**Status:** ready-for-agent

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
