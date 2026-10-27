# 25 — Composer: paste hardening, drafts survive restart, screen-reader value, live branch label (A20, #557, #430, #780) — PROTO

**Lane:** 1d Fixes: sidebar + chat. **Effort:** M.

**What to build:**
1. **A20 (`a75cf1a61` remainder): paste hardening.**
   - Bound parse *attempts* in `pasted_reference_tokens` (32), not only
     accepted chips (`composer.rs:5623`). Text full of rejected
     `@`/`$`/`/` look-alikes stalls the UI.
   - Resolve `/command` and `$skill` pastes in projectless chats.
     `resolve_pasted_references` (`:7463`) bails on
     `file_search_params(...) = None`.
2. **#557: half-written prompts are lost on restart.**
   `composer.rs:5964` `drafts: HashMap` lives only in memory. Persist
   per chat, and clear on send or archive. Store it where UI settings
   live; don't put drafts in the engine.
3. **#430: the composer isn't exposed to screen readers.** It sets
   `aria_placeholder` but not `aria_value` (`composer.rs:5233`). The zui
   pin has it (`div.rs:1333`).
4. **#780: the branch label goes stale after a terminal checkout.** It
   reads `chat.branch` (`pickers.rs:4033`), which is stamped only on
   create/switch/rename/worktree. The "HEAD watcher" claimed at
   `workspace_host.rs:748` doesn't exist. Add `branch` to
   `CheckoutGitStatus` (`proto/src/entities.rs:899`), fill it from the
   status poll, and prefer it for the label.

**Upstream:** `a75cf1a61` (remainder); open PRs #557, #430, #780.

**Roboco files:** `crates/ui/src/composer.rs`, `crates/ui/src/pickers.rs`,
`crates/proto/src/entities.rs`, `crates/engine/src/workspace_host.rs`
(git status), settings persistence.

**Excluded:** nothing known.

**Proto change:** yes (#780 `CheckoutGitStatus.branch`, additive). Check
the UniFFI build if mobile reads the struct.

**Blocked by:** 01.

**Status:** ready-for-agent

**Source:** `2026-10-28.md` A20, §A smaller (Composer); `part-03.md`
`a75cf1a61`; `pass2-big-2.md` gap 8.

- [ ] Pasting 10 000 rejected candidates stays responsive (bounded-attempts test); projectless `/cmd` and `$skill` pastes chip (test)
- [ ] A draft survives an app restart; it clears on send (test)
- [ ] The composer exposes its value to accessibility (test on the element's aria value)
- [ ] `git checkout other` in a terminal updates the label on the next status poll (engine + UI test)
- [ ] Commit records `a75cf1a61` (remainder) and PRs #557/#430/#780

## Comments
