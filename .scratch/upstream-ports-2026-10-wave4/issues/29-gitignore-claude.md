# 29 — Ignore .claude and CLAUDE.md

**What to build:** Add `.claude/` and `CLAUDE.md` to `.gitignore` so
nested agent-tool scratch state doesn't dirty the tree.

**Blocked by:** None.

**Status:** ready-for-agent

**Upstream SHAs:** `7d454cfe` — `.gitignore` only. Trivial carry; fold
into any open wave-4 branch or land standalone with the SHA recorded.

**Verification budget:** `git status` stays clean after creating a
`.claude/` dir and `CLAUDE.md`.

- [ ] `.gitignore` entries added; status clean
- [ ] Commit records upstream SHA

## Comments
