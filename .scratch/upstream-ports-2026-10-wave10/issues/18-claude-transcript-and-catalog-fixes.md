# 18 — Claude: `/compact` and local slash-command replies render, live model names, `--thinking-display` gate (#802, #787, #711, #703)

**Lane:** 1c Fixes: agents + orchestration. **Effort:** S–M.

**What to build:**
1. **#802: a Claude `/compact` turn renders empty.** The ring reset
   already exists (`claude/normalize.rs:661-673`). Emit the "Context
   compacted." text delta plus `AssistantMessageCompleted`, as Codex does
   (`codex/mod.rs:1600`).
2. **#787: local slash commands (`/context`, `/cost`, `/usage`, `/mcp`)
   show nothing.** `normalize.rs:792-796` keeps only `tool_use` from
   top-level assistant frames and drops the `<synthetic>` text.
   `:841-860` zero usage resets the ring, and
   `crates/doc/src/parts.rs:542` ignores `Done.result`. Show the reply,
   and don't reset the ring on a zero-usage frame.
3. **#711: new Claude models are labelled "Default (recommended)".**
   `harness/src/claude/catalog.rs:262-266` pushes the `default` row under
   the concrete id, then skips the named row as a duplicate. Label
   resolved defaults with the live model name.
4. **#703: old Claude CLIs fail every run (and titles) with "unknown
   option".** `claude/mod.rs:422` always passes `--thinking-display
   summarized`. Gate it on what the resolved CLI accepts (version probe
   or help scan, cached per binary as in `executable.rs`).

**Upstream:** open PRs #802, #787, #711, #703.

**Roboco files:** `crates/harness/src/claude/{normalize,catalog,mod}.rs`,
`crates/doc/src/parts.rs`, `crates/harness/tests/claude.rs`.

**Excluded:** nothing known.

**Proto change:** no.

**Blocked by:** 11 (same files).

**Status:** ready-for-agent

**Source:** `2026-10-28.md` §A smaller (Claude); `part-prs-B.md`.

- [ ] `/compact` and `/context` turns show their text (normalize tests with captured frames)
- [ ] A zero-usage frame keeps the context ring (test)
- [ ] Catalog with a new default shows the live name (catalog test)
- [ ] An old-CLI fixture without `--thinking-display` runs (test)
- [ ] Commit records the four PR numbers

## Comments
