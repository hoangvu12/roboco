# 07 — Final verification: the user's four machines

**What to build:** Evidence, recorded as comments.

**Automated**
- `cargo check --workspace --all-targets`; the touched crates' suites; `roboco-ui --lib` (serial); `crates/engine/tests/rooms.rs`; the CLI room/pairing tests; Android unit tests (CI); the PR CI fully green.

**Manual (ready-for-human: needs the user's machines)**
- Start from the user's real state: PC solo fleet, Laptop solo fleet, SG + US fleet. Update all four to the PR build, then re-pair SG and US to the PC (Tailcat pairings from before v0.14 need re-pairing once).
- After one sync, the PC's card shows PC, Laptop, SG and US all as In; so does the Laptop's. `roboco engine room list` on each machine shows the same fleet id with 4 members.
- With one machine offline during the merge: it shows "Not in yet · offline", and joins after it comes back plus Retry.
- Phone: pair with the PC and get all four. Then, with the phone already paired, merge in a fifth engine (or reuse the offline case): the phone picks it up after reconnecting, without re-pairing.
- QR: the phone scans the PC's Tailcat pairing QR from about 30 cm on the laptop screen, and through Enlarge. A `--qr` in a dark terminal scans.
- Opt-out: switch an engine off, then run `roboco engine room leave` on it. The next merge doesn't pull it back.

**Blocked by:** 01–06

**Status:** ready-for-human

**Parent:** `.scratch/fleet-merge/spec.md`

- [ ] Automated checks green
- [ ] The four machines converge to one fleet; phones get all of them
- [ ] The offline and opt-out cases behave as above
- [ ] The QR scans (desktop and terminal)

## Comments
