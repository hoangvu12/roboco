# 08 — Muse Code harness (upstream #978 part 2, `987bb2f87`) — WIRE, DECISION

**What to build:** If the user takes the harness: port the Muse half of
`987bb2f87` — `HarnessId::Muse` (11th harness) everywhere an agent is named:
`engine/src/agent_accounts/muse.rs` (+769), `harness/src/muse/*` (~2.6k: MSP
protocol over `muse serve` NDJSON: initialize, session start/resume, turns,
steer, interrupt), installer/updater, skills discovery, brand marks
(`ui/assets/icons/muse-mark.svg`, settings/pickers hunks), fixtures + tests
(~1.4k). **Wire change — wiregen gate.**

**Decision needed (spec § Decisions):** take an 11th harness at all? If
declined, close this ticket wontfix — ticket 07 stands alone.

**Excluded upstream hunks:** `crates/client/src/catalog.rs`, iOS files
(BrandMarks/CoreSessionSource), edge references.

**Blocked by:** 07, and the user's decision.

**Status:** wontfix (declined by the user, 2026-10-25)

**Verification budget:** `cargo check -p roboco-harness` + muse tests +
fixtures, wiregen. Source: `.scratch/upstream-drift/2026-10-25.md` § A7.

- [x] Decision recorded (yes → port / no → wontfix)
- [ ] If yes: harness + accounts + wire types ported, wiregen fresh
- [ ] Commit records upstream SHA `987bb2f87` (#978, muse slice)

## Comments
- 2026-10-25: **DECLINED by the user** — no Muse harness. Ticket 07
  (ownership + known-bad list) stands alone, as planned.
