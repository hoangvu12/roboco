# 26 — Notices and actions: dismiss agent-update notices, copy check, Retry a failed turn, scrollable review comments (#728, #747, #688, #581)

**Lane:** 1d Fixes: sidebar + chat. **Effort:** M.

**What to build:**
1. **#728: the Home agent-update notice can't be dismissed.** The engine
   has `dismiss` (`engine/src/harness_updates.rs:1585`, RPC
   `DismissHarnessUpdate` at `rpc.rs:2453`), but
   `ui/src/shell/harness_updates.rs` never calls it. Add the dismiss
   control. Roboco tracks the connected engine only (ADR 0004), so
   dismiss on that engine.
2. **#747: no ✓ after copying an error notice.**
   `ui/src/notice.rs:119-128` always shows `COPY`. Swap to a check
   briefly, as other copy buttons do.
3. **#688: no Retry on a failed turn.** Resend the failed turn's prompt
   (with its attachments) from the error card/notice. Reuse the queue's
   send path. Don't duplicate if the first send actually landed: check by
   message id.
4. **#581: long review comments in the diff/file viewer can't scroll.**
   `comment_ui.rs:258` `.h(px(46.0))` and `files/preview.rs:3091`
   `.h(px(48.0))` fix the height of a plain `ComposerInput`
   (`changes.rs:2858`). Grow up to a cap, then scroll.

**Upstream:** open PRs #728, #747, #688, #581.

**Roboco files:** `crates/ui/src/shell/harness_updates.rs`,
`crates/ui/src/notice.rs`, `crates/ui/src/transcript.rs` / error-card
actions (`run_errors` surfaces), `crates/ui/src/{comment_ui,changes}.rs`,
`crates/ui/src/files/preview.rs`.

**Excluded:** #728's multi-device notice (Roboco tracks one engine, ADR 0004).

**Proto change:** no.

**Blocked by:** 01.

**Status:** ready-for-agent

**Source:** `2026-10-28.md` §A smaller (Notices and actions).

- [ ] Dismissing the notice persists across restarts (engine-side dismiss)
- [ ] The copy button shows a check (UI test)
- [ ] Retry resends a failed turn once (test with a failed-send fixture)
- [ ] A long review comment scrolls (UI test)
- [ ] Commit records the four PR numbers

## Comments
