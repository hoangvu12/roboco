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

**Status:** ready-for-agent

**Verification budget:** `cargo check -p roboco-ui` + toast tests.
Source: `.scratch/upstream-drift/2026-10-11.md` § `a408a814`.

- [ ] `toast.rs` ported with the stacked presentation
- [ ] Transcript nested Copy menu ported
- [ ] `pull_requests.rs` hunks excluded (recorded in the commit)
- [ ] Commit records upstream SHA `a408a814`
