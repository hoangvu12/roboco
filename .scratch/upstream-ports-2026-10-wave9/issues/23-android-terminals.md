# 23 — Android: CompanionStrip + terminals (upstream #988/#1007/#1010) — DECISION

**What to build:** If the user says yes to Android terminals: 1:1 iOS-parity
port of the **CompanionStrip** — terminals, side chats and subagents beside
every session in one strip (`CompanionStrip`, terminal store/key bar/view,
`SubagentViewController` behaviour). Roboco already has side chats +
subagents in the older shape — this consolidates them into the strip and
adds terminals.

**Transport (the decision):** terminals stream from the engine's PTYs over
**roboco's paired-engine RPC over LAN/Tailcat** — never upstream's
WebRTC-to-host/cloud path. Likely needs an ADR note amending ADR 0009's
scope; the terminal glyph painter from `3c97a05cf` (#1010) ports with it.

**Excluded upstream hunks:** WebRTC link, `crates/client`, all `apps/ios`
files (behaviour only).

**Blocked by:** 22, and the user's decision.

**Status:** wontfix (declined by the user, 2026-10-25)

**Verification budget:** app unit tests + a terminal golden; e2e against a
`android_fixture` engine. Source: `.scratch/upstream-drift/2026-10-25.md` § D-a.

- [x] Decision recorded (transport + scope; ADR note if yes)
- [ ] If yes: strip + terminals ported 1:1 over the roboco transport
- [ ] Commit records upstream SHAs `fd7d268ca`, `dfbb6876c`, `3c97a05cf`

## Comments
- 2026-10-25: **DECLINED by the user** — no Android terminals /
  CompanionStrip. Ticket 22 closed with it. The side chats + subagents
  surfaces keep the current shape.
