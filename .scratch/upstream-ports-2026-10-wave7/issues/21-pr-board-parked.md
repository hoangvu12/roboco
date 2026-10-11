# 21 — Repository-scoped PR board and native inspection (upstream #484) — PARKED

**PARKED — do not start.** This is the largest single candidate since
the fork point (47 files, +20160/−314) and a whole product surface, not
a mechanical port. ADR 0003 governs: take it to the user first. `a408a814`'s
picker-style repository menu (excluded from ticket 09) and `d84ef73a`'s
focus-ring polish ride this ticket when it cuts.

**What to build (when cut):** Port `afca583c` (#484): a
repository-scoped PR board with native inspection — PR lists per local
remote, review states, and native code inspection against local
checkouts. Engine-local in spirit (git-native, no cloud pairing), but
20k lines across proto/engine/ui/docs. It is also the commit that bumped
the GPUI pin (zui `0966d065 → 361ff20b`) — ticket 19 is a prerequisite.

**Why parked:** product decision (does Roboco want a PR board?), plus
size. Roboco has no `pull_requests.rs` at all (verified 2026-10-11).
The drift review's verdict: "a 'do we want the feature' decision, not a
mechanical port — take it to the user before any wave includes it."

**Cut condition:** the user says yes/no on the feature. If yes, plan it
as its own wave (not a ticket in this one); if no, record the decision
here and mark `wontfix`.

**Status:** needs-triage (parked on user decision)

**Verification budget (when cut):** full workspace check + wiregen gate
(proto shapes move) + ui tests. Source:
`.scratch/upstream-drift/2026-10-11.md` § `afca583c`.
