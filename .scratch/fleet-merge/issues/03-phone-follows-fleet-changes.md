# 03 — Phone: re-fetch the fleet on reconnect and follow merges

**What to build:** In `crates/mobile-client/src/rooms.rs` (`fetch_fleet` `:786`, `join_fleet` `:827`, `heal_rooms` `:909`), make a paired phone notice when its engines' fleet changed.

- On each reconnect to a paired `rooms-v3` engine (rate-limited to once per engine per connection), call `GetFleetInvite`. If the fleet id differs from the phone's fleet record, adopt the new fleet: run the ordinary room join, so new members appear as paired engines. **Never drop** an existing paired engine.
- If two paired engines report different fleets (mid-merge), follow the bigger one; the other converges after the desktop's merge.
- No UI change apart from new engines appearing. If an Android caption mentions the fleet, keep it accurate.

**Blocked by:** 01

**Status:** ready-for-agent

**Parent:** `.scratch/fleet-merge/spec.md`

- [ ] `crates/mobile-client` test: an engine's fleet id changes → the phone joins the new members, keeps the old ones, and writes one fleet record
- [ ] Rate limit: one fetch per engine per connection (test)
- [ ] `cargo xtask android-core` builds; `:app:testDebugUnitTest` is green (CI)
- [ ] An Android flow test, if the `android_fixture` engines can stage a fleet change cheaply; otherwise a manual check in 07

## Comments
