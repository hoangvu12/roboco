# 64 — Sidebar: session hover card beside each chat row (wave-8 13, #903)

**Lane:** 4b Features: desktop UI. **Effort:** M.

**What to build:** Carried from wave-8 ticket 13. Its gate (wave-7 07,
the session-card superset) landed in `189c3d5a3`, so it's unblocked.
Hovering a sidebar chat row shows a session hover card beside it. Port
the new `crates/ui/src/shell/session_hover_card.rs` (+729) and the
wiring in `shell.rs` (+126), `shell/spaces.rs` (+36) and
`change_requests.rs` (+17), adapted to Roboco's session-card state names.
Fleet: the card names the chat's engine for non-local chats.

**Upstream:** `840fda748` (#903).

**Roboco files:** `crates/ui/src/shell/{session_hover_card.rs (new),spaces.rs}`,
`crates/ui/src/shell.rs`, `crates/ui/src/change_requests.rs`.

**Excluded:** anything that reaches into the PR board (Roboco has no
`pull_requests.rs`). The PR badge stays as `change_requests.rs` renders it.

**Proto change:** no.

**Blocked by:** 01.

**Status:** ready-for-agent

**Source:** wave-8 `issues/13-session-hover-card.md`; `part-06.md` `840fda748`.

- [ ] Hover shows the card; it hides on leave and scroll; keyboard focus doesn't trigger it (UI tests)
- [ ] Remote-engine chats show their engine
- [ ] Commit records `840fda748`; absorbs wave-8 13

## Comments
