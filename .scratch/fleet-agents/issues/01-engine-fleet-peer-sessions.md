# 01 — Engines hold sessions on their fellow fleet members

**What to build:** In `crates/engine` (rooms + pairing store), every engine in a fleet keeps an ordinary session on every other member, minted with the fleet secret it already retains.

- For each book member other than itself, the engine presents the fleet secret at the member's `POST /room/present` (the existing path, as clients do; reuse the `crates/engine-client` presentation code where it fits). The session is labelled with this engine's device name plus "(fleet)".
- Dial book addresses like clients do: LAN directly, Tailcat through the engine's adapter (the forwarder machinery clients use).
- Store the credentials in a `fleet_peer_sessions` table in `pairing.sqlite3` (owner-only, same protection as `fleet_rooms`, WAL sidecars). They are refreshed when the book changes or a credential is refused, and erased on `room leave` and on a fleet replace (fleet-merge 01).
- A member that refuses (left the fleet, old engine) is skipped with a plain status. An offline member is retried with backoff and never blocks.
- Credentials and secrets never appear in logs, `Debug` or error text.

**Blocked by:** fleet-merge 01, 02

**Status:** ready-for-agent

**Parent:** `.scratch/fleet-agents/spec.md`

- [ ] Loopback test: three engines in one fleet each hold a session on the other two; each member's session list shows them with the right labels
- [ ] Leaving the fleet / a fleet replace erases the credentials and revokes them on the members (best effort)
- [ ] An opted-out or left machine gets no new session
- [ ] No secret or credential in logs/Debug (existing assertion pattern)

## Comments
