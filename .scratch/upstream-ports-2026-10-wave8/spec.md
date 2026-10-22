# Upstream ports 2026-10 wave 8: standalone ports (v0.2.112–v0.2.114 window)

Status: **COMPLETE (2026-10-22)** — 12/13 tickets ported, reviewed and
merged to main. Executed as three parallel lane branches instead of one
`wave8/ports` session (user override): `wave8/harness` (01, 05, 10),
`wave8/engine` (06, 11, 12 + a dispatcher stack-bounding fix), `wave8/ui`
(02, 03, 04, 07, 08, 09) — each lane ran the implement skill in its own
worktree, ran its crate's full test suite and a two-axis code review.
End-of-pass on merged main: `cargo check --workspace` green, wiregen
`--check` fresh (engine lane added the `WorktreeSettings`/`projectless`
wire types + regenerated the TS), m5 stack test 37/37. Open: 13 (session
hover card), blocked by wave-7 ticket 07. Known baseline: the pi/previews
engine suites and two proto test targets fail identically on pristine
main in this sandbox (live pi CLI fixtures / user-database homes) —
pre-existing, not wave-8. Carry-over: the engine rpc dispatcher's ~830 KB
debug poll frame sits within ~30 KB of the 2 MB worker stack at base;
`9aee6edfd` restores base parity but a dispatcher split deserves its own
ticket.

Source: `.scratch/upstream-drift/2026-10-22.md` — the drift review of the
`54954e4d..911ec56e4` window (tags v0.2.112–v0.2.114). Wave 8 takes the
window's standalone items only; the riders that build on wave-7's open
series are recorded in the wave-7 spec's 2026-10-22 update (#909/#901
fold into ticket 06, #867/#895 into ticket 07, #573 into ticket 22) or
filed here as ticket 13 (#903, sequenced after 07).

## Problem Statement

The drift note triaged 21 port candidates. Eight ride wave-7's open
tickets and are recorded there. The standalone items — two quick ports,
five smalls, five mediums, one post-07 rider — had no tickets. The
window's decisions are settled: #912 zatex **DECLINED** (user,
2026-10-22), #900 native visuals **recommended park** (user confirmation
pending), #866 stays parked with #484 (wave-7 ticket 21), the macOS pair
(#842/#812) is optional dev-only with no ticket.

## Solution

13 tickets, order = ticket number. 01–02 quick ports; 03–07 smalls;
08–12 mediums; 13 after wave-7 ticket 07. **No proto wire changes are
planned in this wave** — every wire-touching item in the window is a
wave-7 rider — so no wiregen-gated tickets; if a port (11, 12)
introduces new engine RPC surface types anyway, run
`cargo run -p wiregen -- --check` inside the ticket.

Defaults (the wave-4/5/6/7 rhythm; the user can override in this spec):

1. **Execution:** one implementer session on branch `wave8/ports` off
   `main`, one commit per ticket, in ticket order (13 only after wave-7
   ticket 07 lands). Merge into `main` only after the end-of-pass
   verification is green. Never push the branch; never move the mirror.
2. **Verification:** targeted checks per ticket while working
   (`cargo check -p <crate>`, single test files), one full end-of-pass
   batch at the end.
3. Every port commit records the upstream SHAs it carries and any
   deliberately excluded behavior, per `docs/reference/upstream-ports.md`.
   Renames follow AGENTS.md (`roboco-*`, `ROBOCO_*`). Cloud/account/
   sync-room/iOS/macOS hunks are excluded by intent, not by dropping
   files.
