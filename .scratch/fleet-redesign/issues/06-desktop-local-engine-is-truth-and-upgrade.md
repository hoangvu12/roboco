# 06 — Desktop: the local engine is the truth; existing pairings upgrade themselves

**What to build:** in `crates/ui` (engine registry, `tailcat_pairing.rs`, `rooms.rs`), the desktop app becomes a controller that follows its local engine.

- **Device list from the engine.**
  - The registry's set of remote engines comes from the local engine's `WatchPeers` (ticket 03). For each server peer the app lacks a session on, it calls `Introduce` on the local engine and redeems the code as a controller.
  - The app's saved paired-engine list stays as a cache for offline display only. Dedupe is by device id.
  - Hand-paired engines without `connect-v1` stay as they are, one-way, and show "Update Roboco on X to connect it both ways".
- **Enter a code** goes to the local engine (`POST /connect` as a server, ticket 02), not to the app. The app then follows through `WatchPeers`.
- **Upgrade, once and silently.** For every saved pairing to a `connect-v1` server that isn't yet a peer of the local engine:
  1. ask that server for `MintConnectCode` over the app's session;
  2. hand the code to the local engine to redeem (mutual).

  Run this on app start and on each first connect. Together with ticket 03, the user's three v0.14 fleets converge into one set the first time the PC and Laptop apps run.
- **Retire the fleet client:**
  - stop `FleetSync` and the mint/push/heal paths;
  - the leftover `fleet-v1.json` is erased (secure delete) after the upgrade succeeds.

  The code deletion itself is ticket 09.
- Labels: the desktop presents to other engines under the computer's device name, never "Roboco desktop" (the duplicate rows in the user's screenshot).

**Blocked by:** 03

**Status:** ready-for-agent

**Parent:** `.scratch/fleet-redesign/spec.md`

- [ ] Fake dialer: the local engine reports peers {SG, US, Laptop} → the app holds one session on each, introduced, with no duplicates of already-paired ones
- [ ] Upgrade: an app with plain pairings to SG and US makes the local engine mutually connected to both; a second run does nothing
- [ ] The user's layout: PC app (paired SG, US, own fleet) + Laptop app (own fleet) + SG/US fleet → after both apps run once and the Laptop enters one code from any of them, every server shows four peers
- [ ] A non-`connect-v1` engine keeps working one-way with the update caption
- [ ] `fleet-v1.json` is erased after the upgrade; no secrets in logs or `Debug`

## Comments
