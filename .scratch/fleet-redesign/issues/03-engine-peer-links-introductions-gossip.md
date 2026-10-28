# 03 — Engine: peer links, introductions and gossip (connecting brings everyone along)

**What to build:** in `crates/engine`, servers keep live links to their peers, tell each other who they know, and introduce what's missing. No client needs to be open. This replaces fleet-agents ticket 01.

- **Peer links**, ported by intent from upstream `LinkCache` (`zeron/main:crates/rpc/src/device_room.rs:783-1080`; notes in `.scratch/fleet-agents/research-2026-10-28.md`):
  - one lazy link per peer, with a dial lock and 3 attempts (1.5 s, 3 s);
  - an `EngineInfo` readiness probe that also checks the device id matches the row;
  - a 5→60 s cooldown, cleared on network change;
  - an end-to-end keepalive.
  - Dial LAN routes directly and Tailcat routes through the engine's adapter (the client forwarder machinery).
  - The engine becomes a client of other engines here. Reuse `crates/engine-client` where it fits.
  - **Status per peer:** `Live` / `Unreachable` / `Refused` / `Update needed`.
  - A `one-way` row retries the dial-back on every link-up.
- **`ListPeers`** (session-authenticated) returns, per peer: device id, name, kind, status and `last_ok`.
  - **Server** sessions also get routes.
  - **Controller** sessions get routes too (they need them to dial), never credentials.
- **`WatchPeers`** streams changes to controllers.
- **Gossip:**
  - on link-up, each side pulls `ListPeers`;
  - on any change in `peers` (add, remove, route or name change), the server sends `PeersChanged` (no payload) to every live peer, and receivers pull.
- **Introductions:** for each server or controller P that a peer B knows and we don't (matched by **device id**; skip self, skip known, skip `removed`), call `Introduce{target: P}` on B. B asks P for a one-time code (`MintConnectCode` over B's session on P) and returns it, and we redeem it at P (ticket 02's mutual connect).
  - Controllers call the same `Introduce` to follow (tickets 06, 08).
  - **Only a server holding a live session on both sides can introduce.**
- **Routes refresh:** a peer whose Tailcat or LAN route changed gets the new one through `ListPeers`. Rows are keyed by device id, so an address change never orphans a device (the v0.14 failure).
- **Fan-out bounds:** introductions run concurrently with a small cap, are idempotent, and are retried on the next link-up. This targets personal setups of 2–10 servers.
- Gated by `connect-v1`. An older peer stays `one-way`/`Update needed` and is never introduced through.

**Blocked by:** 02

**Status:** ready-for-agent

**Parent:** `.scratch/fleet-redesign/spec.md`

- [ ] Loopback: {PC}, {Laptop}, {SG, US}; connect Laptop–SG, then PC–US → all four hold mutual sessions, each device appears once on every server
- [ ] A server offline during the join converges when it comes back, with no client involved
- [ ] A controller connected to one server gets introduced to all, and `WatchPeers` delivers a server added later
- [ ] A route change on SG reaches PC through gossip; PC's link recovers without re-connecting
- [ ] A `connect-v1`-less peer is never used as an introducer and shows `Update needed`
- [ ] No route or credential in logs, `Debug` or error text; controllers never receive credentials

## Comments
