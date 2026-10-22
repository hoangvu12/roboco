# 13 — Sidebar: session hover card beside each chat row (upstream #903, `840fda748`)

**What to build:** Port `840fda748` (#903): hovering a sidebar chat row
shows a session hover card beside it — new
`crates/ui/src/shell/session_hover_card.rs` (+729) with the card layout,
plus `shell.rs` (+126), `shell/spaces.rs` (+36) and `change_requests.rs`
(+17) wiring.

**Why now:** rides the session-card and sidebar-sections state that
wave-7 tickets 06/07 introduce — porting it before those lands means
building against surfaces that don't exist yet.

**Steps:**
1. Wait for wave-7 ticket 07 (session card superset) to land.
2. `git show 840fda748` — session_hover_card.rs, shell.rs, spaces.rs,
   change_requests.rs.
3. Port the card + wiring onto roboco's post-07 shell, adapting to
   roboco's session-card state names.

**Excluded upstream hunks:** none expected; anything touching the PR
board adapts out (roboco has no `pull_requests.rs`).

**Blocked by:** wave-7 ticket 07
(`.scratch/upstream-ports-2026-10-wave7/issues/07-session-card-titlebar-toggles.md`
— now the ac4acbc2 + c8c717862 + 591824881 superset).

**Status:** ready-for-agent (gated)

**Verification budget:** `cargo check -p roboco-ui` + shell/spaces
tests. Source: `.scratch/upstream-drift/2026-10-22.md` § sidebar-series
riders.

- [ ] Hover card + wiring ported after 07 lands
- [ ] Commit records upstream SHA `840fda748` (#903)

## Comments
