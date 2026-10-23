# 09 — Sonner-style toasts and the nested Copy menu (upstream a408a814, standalone half)

**What to build:** Port the standalone half of `a408a814`: a new
`crates/ui/src/toast.rs` (+712) with Sonner-style stacked toasts
replacing the prior transient notification surface, and the nested Copy
menu in the transcript. 16 files, +1232/−500 total.

**Why now (verified 2026-10-11):** roboco has no `toast.rs` — the drift
review verified its absence. The toast + Copy-menu halves port
standalone; the third half (picker-style repository menu in
`pull_requests.rs`) rides #484 and is explicitly out of scope here.

**Steps:**
1. `git show a408a814` — take `toast.rs` (+712), the transcript Copy-menu
   hunks, and the settings/shell call-sites that swap the old transient
   surface for toasts.
2. Skip every hunk in `pull_requests.rs` (rides ticket 21, PARKED).
3. Port the toast tests.

**Excluded upstream hunks:** all `pull_requests.rs` repository-menu
hunks (ticket 21); any cloud surfaces.

**Blocked by:** None (independent of 06–08 by file overlap, but sequence
after 07/08 if `shell.rs` conflicts bite).

**Status:** done (ported in 99194ae5 on wave7/toasts)

**Verification budget:** `cargo check -p roboco-ui` + toast tests.
Source: `.scratch/upstream-drift/2026-10-11.md` § `a408a814`.

## Comments

- **2026-10-24, ported in `99194ae5`:** carried the standalone half of
  upstream `a408a814` — `toast.rs` verbatim (no zeron-branded identifiers
  in it), the nested Copy submenu (the chat context menu's Copy row, per
  upstream; the ticket's "in the transcript" phrasing was loose — upstream
  has no transcript.rs hunks in this commit), and every sidebar/settings
  call-site that swapped the old transient surface for toasts. Adaptations
  by intent: `chat-copy-roboco`/`copy_roboco_conversation_link` rebrand;
  devices keeps the durable registry `configuration_error` inline
  (roboco-specific engine-local state) while `self.error` became a toast;
  harnesses' `update_error` and accounts' `Loadable::Error` Retry strips
  stay inline (upstream kept both). `sidebar_notice` survives as the
  toast-message mirror the pin-write seam matches, per upstream. The
  archived delete-all test was re-pointed at the toast surface; a new
  `copying_from_the_copy_submenu_shows_a_toast` test covers the nested
  menu. Excluded: all `pull_requests.rs` hunks (rides ticket 21), the cloud
  sign-in notice hunks (no roboco counterpart). Code-review note: two
  upstream-stale doc comments (sidebar_notice field, sidebar section list)
  were corrected beyond upstream. `cargo test -p roboco-ui`: 1617 passed;
  one unrelated transcript tween test flaked under parallel load once and
  passes in isolation/8-thread runs.

- [x] `toast.rs` ported with the stacked presentation
- [x] Transcript nested Copy menu ported
- [x] `pull_requests.rs` hunks excluded (recorded in the commit)
- [x] Commit records upstream SHA `a408a814`
