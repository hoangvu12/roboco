# 04 — Engine: remove a device from anywhere

**What to build:** `RemovePeer{deviceId}` (session-authenticated, plus the local admin path) on any server removes a server or controller from every server.

- The receiving server:
  - marks the row `removed` with `removed_at`;
  - revokes the removed device's session(s) here;
  - deletes our credential on it, after a best-effort `ForgetPeers` call telling the removed server to drop its rows;
  - sends `PeersChanged`.
- Receivers of the gossip apply the same steps. **A tombstone wins over any add for that device id**, so introductions skip it, and so does an incoming `POST /connect` that arrives via `Introduce`.
- Only a **direct** connect clears the tombstone: the user hands the device a fresh code from this server.
- Tombstones are kept for 90 days, then pruned.
- Removing yourself ("Disconnect this computer") = `ForgetPeers` locally, plus `RemovePeer(self)` sent to every live peer first.
- Messages are fixed text, with no names of secrets.

**Blocked by:** 03

**Status:** ready-for-agent

**Parent:** `.scratch/fleet-redesign/spec.md`

- [ ] Four loopback servers plus a controller: remove the controller from PC → its sessions are revoked on all four within one gossip round
- [ ] Remove US from Laptop → no server re-introduces US, even when SG was offline during the removal and comes back holding a `connected` row
- [ ] A direct reconnect with a fresh code brings US back on all servers
- [ ] Tombstone pruning after 90 days (fake clock)
- [ ] "Disconnect this computer" leaves no peer rows locally and none pointing at it elsewhere

## Comments
