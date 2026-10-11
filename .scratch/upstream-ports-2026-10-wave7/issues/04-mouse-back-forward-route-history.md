# 04 — Shell: mouse back/forward buttons walk the route history (upstream #872)

**What to build:** Port `488c0358` (#872): auxiliary mouse buttons 8/4
(back/forward) walk the shell's route history in `crates/ui/src/shell.rs`.
Over the browser view they are **inert** (`crates/ui/src/browser/view.rs`
stop_propagation) — so side buttons used inside a web page never move the
app's history; the browser's own toolbar back/forward (its `history`
actions) are unchanged and stay the only browser-history controls.

**Why now:** 2 files, +16/−2 — the smallest UI candidate of the window,
and a Windows-first product ships mice with these buttons everywhere.
Roboco holds both surfaces.

**Steps:**
1. `git show 488c0358` — two files.
2. Port the button handling into roboco's `shell.rs` route history and
   the browser-view forwarding.

**Excluded upstream hunks:** none.

**Blocked by:** None.

**Status:** done (ported in 3daee261 on wave7/ports)

**Verification budget:** `cargo check -p roboco-ui`. Source:
`.scratch/upstream-drift/2026-10-11.md` § `488c0358`.

- [x] Back/forward buttons walk route history; inert over the browser view
- [x] Commit records upstream SHA `488c0358` (#872)

## Comments

Ported in 3daee261, near-verbatim: roboco's shell has `navigate_back`/
`navigate_forward` and the browser view has the same action slots.
`MouseButton::Navigate`/`NavigationDirection` exist in our pinned zui
(0966d06). No tests upstream for this hunk (input-only); ui lib suite
ran green in ticket 02's pass and the crate check is clean.

**Wording correction (code-review pass):** this ticket's original "What
to build" said the browser view "forwards its own navigation history
into the same walk" — that was wrong, and the code-review pass caught
it. Upstream's actual behavior (and ours, matching): the buttons are
INERT over the browser (`stop_propagation`) so a page's side-button use
never moves the app's history. The port was correct; only the ticket
text misdescribed it. Corrected above.
