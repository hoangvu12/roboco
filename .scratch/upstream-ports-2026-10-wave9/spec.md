# Upstream ports 2026-10 wave 9: the v0.2.115–123 window (24 tickets)

Status: **PLANNED (2026-10-25) — not started.** Inventory, triage and the
four product decisions are settled: **21 tickets open**; 08 (Muse),
22 (mobile-core terminal), 23 (Android terminals), 24 (commit credit)
DECLINED by the user, 2026-10-25 — wontfix, kept for the record.
No ports run yet.

Source: `.scratch/upstream-drift/2026-10-25.md` — the drift review of the
`911ec56e4..534a5742c` window (tags v0.2.115–v0.2.123, 30 substantive
commits). Wave 8 (12/13) is verified complete on main; this wave takes the
new window only. Carry-over tickets stay in their waves (wave-7 08, 13–17,
20, 22, 21 PARKED; wave-8 13) — except **wave-7 ticket 19 (GPUI pin sync),
superseded by ticket 11 here** (upstream moved the pins in #966).

## Lanes

- **Engine/harness (01–07, 09, 10):** two trivial fixes, the npm/refresh substrates,
  the provider-hardening monster, CLI-update ownership, previews origin,
  provider switching.
- **UI (11–17):** pin sync first (11 gates 12 and 17), then rail, checkout
  pick, error cards, new-project-by-path, linux browser test fix, terminal
  overhaul.
- **Android (18–21):** iOS parity per ADR 0009 — error cards + delete, the
  one-row bottom bar (golden re-capture), tabbed model picker, smalls.
  Upstream's own Android app (#609/#1029) is reference-only — never a base.

## Decisions (settled 2026-10-25)

1. **Muse Code harness — DECLINED** (ticket 08, wontfix). Ticket 07 ports
   ownership + known-bad list alone.
2. **Android terminals / CompanionStrip — DECLINED** (ticket 23, wontfix);
   mobile-core terminal emulator (ticket 22) closed with it — substrate-only.
   Side chats + subagents keep the current shape.
3. **Commit credit — DECLINED** (ticket 24, wontfix). No `roboco[bot]`
   trailer.
4. Carried: #900 native visuals still parked; #912 zatex stays declined.

## Wire changes (each runs `cargo run -p wiregen -- --check`)

05 (#974 agent.rs), 09 (#1023 preview.rs), 10 (#966 agent.rs slice),
14 (#967 run_errors.rs, new file), 17 (#975 entities.rs host_replies).

## Defaults (the wave-4–8 rhythm; user may override)

1. One implementer session per lane branch off `main` (`wave9/engine`,
   `wave9/ui`, `wave9/android`), one commit per ticket, in ticket order.
   Merge into `main` only after end-of-pass verification. Never push
   branches; never move the mirror.
2. Targeted checks per ticket while working; one full end-of-pass batch at
   the end (`cargo check --workspace`, wiregen `--check`, ui suite, android
   unit tests + goldens for the android lane).
3. Every port commit records the upstream SHAs it carries and any
   deliberately excluded behavior, per `docs/reference/upstream-ports.md`.
   Renames follow AGENTS.md (`roboco-*`, `ROBOCO_*`). Cloud/account/
   sync-room/iOS-app hunks are excluded by intent.
4. Refresh the pristine mirror at wave start (ancestry check passed on
   2026-10-25): `git branch -f zeron/main upstream/main` + the binding
   checks from `docs/reference/upstream-ports.md`.
5. Ticket 11 (pin sync) syncs `hoangvu12/zui` to `0dc69aa25` on a
   `roboco/sync-*` branch first; pin revs and `[patch."…/zui"]` revs move
   together (AGENTS.md rule).

## Order

01–04 quick ports → 05 → 06 → 07; 09, 10 independent (10 after 06 — both
rework `engine/src/sessions.rs`). UI: 11 first, then 12/17; 13/14/15/16
anytime. Android: 18 after 14; 19 after 18; 20/21 anytime.
