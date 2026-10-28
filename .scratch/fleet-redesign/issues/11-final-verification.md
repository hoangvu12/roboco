# 11 — Final verification on the user's machines

**What to build:** nothing new. Prove the spec on real hardware, starting from the user's actual v0.14.0 state: PC solo fleet, Laptop solo fleet, SG+US fleet, phone.

1. Install the build on PC, Laptop, SG and US.
2. Open the PC and Laptop apps once.
3. Expected: every server shows the other three as connected both ways, with no duplicates and no "different fleet".
4. If two groups remain, one code from Laptop → SG joins them. Record the number of steps needed.
5. Phone: connect to SG only, over Tailcat. All four servers appear. Then add a fifth test engine (`roboco connect <code>` on a scratch VM or a second data dir). It appears on the phone without re-pairing.
6. Remove the test engine from the phone's list. It loses access on all servers. Restart SG while it is gone; it stays gone.
7. QR: the desktop dialog QR scans from about 50 cm on the laptop screen; `roboco connect --qr` scans from a dark terminal over SSH.
8. Settings → Devices scrolls, and every row state seen during the run reads correctly (screenshots).
9. LAN-only check: one pair connected over the LAN route.
10. Record each step's evidence (dated) in this file under Comments. Then hand over to `.scratch/fleet-agents/` (re-based on this spec).

**Blocked by:** 01–10

**Status:** ready-for-human

**Parent:** `.scratch/fleet-redesign/spec.md`

- [ ] Steps 1–9 pass, with evidence
- [ ] Nothing secret appears in any captured log or screenshot

## Comments
