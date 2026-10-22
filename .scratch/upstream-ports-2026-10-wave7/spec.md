# Upstream ports 2026-10 wave 7: UI parity + backlog (v0.2.108–v0.2.111 window)

Status: batch 1 merged to main (tickets 01–04, 11, 18 — the quick-port set,
including both overdue 2026-10-09 recommendations; 3b71f9d3). Open: 05
(chips), 06–09 (sidebar/UI-parity series), 10 (after 05), 12–17, 19
(prerequisite-gated), 20 (after 19), 21 PARKED on a product decision,
22 (after 19). Continue on a fresh `wave7/ports` branch off main.

2026-10-22 update: batch 2 (ticket 06) was abandoned mid-flight on a
stale base — its uncommitted fold-ins (`05f5f2bf` ellipsis + `ae19fe31`
SIDEBAR_MIN 242) are saved as `ticket-06-foldins.patch` beside this
spec; re-apply them when ticket 06 ports. The 2026-10-22 drift note
(`.scratch/upstream-drift/2026-10-22.md`) triaged the v0.2.112–114
window and added riders: fold #909 (drag-reorder, wire change) + #901
(archived toggle) into ticket 06; port ticket 07 as the `ac4acbc2` +
`c8c717862` + `591824881` superset; ticket 12 is superseded by #895 —
fold its wire entity into 07's superset; #903 (hover card) follows 07.

Source: `.scratch/upstream-drift/2026-10-11.md` — the drift review of the
`1074bc54..54954e4d` window (tags v0.2.108–v0.2.111), plus the carry-over
backlog it re-triaged. The wave exists because the Zeron creator flagged
Roboco's outdated sidebar publicly ("why does ur sidebar look so different
— they all show on hover for me — ye its outsated", zx0002, 2026-10-11);
the visible delta is the unported UI run this wave ports. It also takes
the two quick ports the perf wave skipped (the #813 engine path-jail
security slice and #522), the now-unparked chips ticket, and the older
genuinely-missed fixes the 2026-10-11 note enumerated.

## Problem Statement

Roboco main (`955c2d05`) lacks, verified with file:line evidence in the
drift note:

1. **The sidebar/titlebar UI run** (the screenshot gap): no session card
   (`crates/ui/src/shell/session_info.rs` absent, `actions_ui.rs` still
   1290 lines), no titlebar view options / folder-tree explorer toggles
   (neither icon exists), `SIDEBAR_MIN` still 224 (`settings.rs:33` vs
   upstream 242), no toasts (`toast.rs` absent), panels clamp
   independently, no workspace search, and the view options never moved
   to the titlebar.
2. **The overdue security quick port**: `crates/engine/src/uploads.rs:335`
   canonicalizes requested paths *before* the root-jail comparison — the
   NTLM-leak shape upstream fixed in #813 — and `read_chunk` runs on the
   RPC worker (`crates/engine/src/rpc.rs:2920`).
3. **The parked chips ticket**: wave-6 ticket 03 sat behind the
   `git-identity-project-grouping` branch; that branch is deleted and
   #775/#816 are merged upstream — the cut condition is met, and #855
   (queue polish + drag-to-mention) queues behind it.
4. **Older genuinely-missed fixes** the 2026-10-11 note enumerated: #544
   (chat history exhausting engine resources), #552 (dormant-chat
   connection notices), the Cursor rich-reference trio, #400, #647, and
   the #528/#535 parallel-fix verification.

## Solution

22 tickets under `issues/`. Order = ticket number. Anchors and gates:

- **Tickets 01–04** are small independent quick ports (engine security,
  UX, engine git, shell input) — cut first.
- **Ticket 05** supersedes wave-6 ticket 03 (chips; supersedes, not
  continues — wave-6 03 is marked superseded in place).
- **Tickets 06–09** are the sidebar/UI-parity series answering the
  screenshot: 06 (options/sections/popovers, wire change) → 07 (session
  card + titlebar toggles) → 08 (panels width) → 09 (toasts).
- **Ticket 10** (#855) is blocked by 05 (it touches `composer/chip.rs`).
- **Tickets 11–18** are independent small/medium ports and the #528/#535
  verification; 16 (#544) is the big engine ticket of that set.
- **Ticket 19** is the GPUI pin sync (zui `0966d065 → 361ff20b`,
  gpui-component `4764fd00 → 701ca97a`) — prerequisite for 20 and 22,
  follows the AGENTS.md pin rule (pins and `[patch]` revs move together).
- **Ticket 20** (#856 workspace search, +7k lines, new `fff` dep) ports
  last on the UI stack, after 19.
- **Ticket 21** (#484 PR board, +20k lines) is **PARKED on a product
  decision** (ADR 0003 territory) — do not start without the user.
- **Ticket 22** (#813 Windows browser) is gated on 19; its engine slice
  is already ticket 01.

Defaults (per the wave-4/5/6 rhythm; the user can override in this spec):

1. **Execution:** one implementer session on branch `wave7/ports` off
   `main`, one commit per ticket, in ticket order (10 only after 5;
   20 only after 19; 21 parked; 22 after 19). Merge into `main` only
   after the end-of-pass verification is green. Never push the branch;
   never move the mirror.
2. **Verification:** targeted checks per ticket while working
   (`cargo check -p <crate>`, single test files), one full end-of-pass
   batch at the end — the implement skill's rhythm. Two tickets carry
   **wire changes** (06 sidebar order keys, 12 `Session.running_
   subagents`): each must run `cargo run -p wiregen -- --check` (and
   `pnpm -C web codegen` if the gate fires) inside the ticket.
3. Every port commit records the upstream SHAs it carries and any
   deliberately excluded behavior, per `docs/reference/upstream-ports.md`.
   Renames follow AGENTS.md (`roboco-*`, `ROBOCO_*`, `sh.roboco.*`).
   Cloud/account/sync-room/iOS hunks are excluded by intent, not by
   dropping files.
