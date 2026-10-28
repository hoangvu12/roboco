# 06 — ADR 0011 amendment "merge on meet", CONTEXT.md, docs

**What to build:**

- An amendment section in `docs/adr/0011-rooms.md`, dated 2026-10-28. It covers:
  - one fleet per user;
  - desktops fetch fleets (`GetFleetInvite`) and move engines (`RoomJoin { replace }`, `rooms-v3`);
  - bigger book wins, tie to the acting desktop, no prompt, and why (a paired session already grants more);
  - pending moves, phone re-fetch on reconnect, and the fleet-invite QR replaced by Copy.

  It says plainly that pairing someone else's computer makes it part of your fleet. It also lists what is unchanged (reachability only, plain rooms, revocable sessions, forward-only opt-out, `room leave`).
- CONTEXT.md: the fleet definition and "merge".
- `.scratch/fleet-follow/issues/03-desktop-fleet.md`: append a Comment listing which of its decisions this spec supersedes. Decision 5 (paste conflict) stays as a fallback; the "Show fleet invite" QR is removed; "desktops never fetch" is lifted.
- User-facing docs: the Remote access / pairing reference, and AGENTS.md's fleet sentence if it changes.

**Blocked by:** 01–05

**Status:** ready-for-agent

**Parent:** `.scratch/fleet-merge/spec.md`

- [ ] ADR amendment, CONTEXT.md and docs updated; the fleet-follow 03 comment is added
- [ ] No doc still says "desktops never fetch" or "there is no remote leave" without the amendment's qualification

## Comments
