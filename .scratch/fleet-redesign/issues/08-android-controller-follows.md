# 08 — Android: connect to one server, see them all

**What to build:** in `crates/mobile-client` (replacing `rooms.rs`), `crates/mobile` FFI and `apps/android/app/src/main/java/sh/roboco/android/pairing/`:

- **Connect** (the Pair page, renamed): scan or paste a connect code (ticket 02 grammar; the old link and invite forms still work). The phone redeems as a **controller**.
- **Follow.** After any connect, and on every reconnect:
  - call `ListPeers` on a connected server;
  - for each server it lacks (by device id), call `Introduce` and redeem;
  - subscribe to `WatchPeers` on one live server (fail over to another), so a server added later appears live.

  This replaces `GetFleetInvite`/room join and the heal paths (`crates/mobile-client/src/rooms.rs:786-822`). Rate-limit it to once per server per connection.
- **Removed:** when a server reports this phone as removed, or every session is refused, drop the engines and show "This phone was removed from your devices".
- **The devices list** shows each server with online state. **Remove from my devices** works on servers, and "Disconnect this phone" is under the list.
- **Strings:** "Room" and "fleet" go from every Android string ("Pair-page strings still say Room", fleet-follow handoff).
- The UniFFI surface changes (the room field on `PairedEngine` is removed) ship as a coordinated core+app release (ADR 0009).

**Blocked by:** 03 (Remove: 04)

**Status:** ready-for-agent

**Parent:** `.scratch/fleet-redesign/spec.md`

- [ ] `mobile-client` tests: connect to one of three loopback servers → all three are paired, no duplicates; a server added later appears through `WatchPeers`; removal drops everything
- [ ] JVM unit: Connect page parsing of the new code and the legacy forms; no "Room" or "fleet" strings remain
- [ ] Emulator flow (`apps/android/scripts/flow-tests.sh`, `android_fixture` engines): scan → all engines visible
- [ ] Keystore storage unchanged for credentials and routes; nothing secret in logcat

## Comments
