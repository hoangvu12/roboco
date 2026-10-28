# 10 — ADR 0012 and docs

**What to build:**

- **`docs/adr/0012-connected-devices.md`**, superseding ADR 0011 and its fleet amendment. It records:
  - servers vs controllers;
  - a mutual connect;
  - transitive introductions by a server that holds sessions on both sides;
  - engine-to-engine peer sessions, links and gossip;
  - removal tombstones;
  - identity by device id;
  - the deletion of rooms and of the fleet secret at rest;
  - the accepted consequence (connecting any server gives it all of yours).

  It also explains why engines as clients of each other is now acceptable although ADR 0011 rejected it: attribution is per presenting engine and revocable, and there's no group credential. Add a "Superseded by 0012" line to ADR 0011.
- **ADR 0004:** a note that peer sessions are control over a session, never sync.
- **ADR 0008:** the compact `roboco:` code grammar, region-id routes, the 15-min TTL, and the legacy forms that are still accepted.
- **`CONTEXT.md`:**
  - Fleet is rewritten ("all servers connected to each other, transitively; users see Devices");
  - Room is deleted;
  - Server, Controller and Connect code are added.
- **`docs/reference/remote-access.md`:** replace § Fleet sharing with § Connecting your devices:
  - quickstart (four steps for a PC + Laptop + 2 servers + phone);
  - `roboco connect`;
  - the Devices page;
  - removing a device;
  - what "Can't reach back" means;
  - security ("connect only your own devices").

  Also the CLI reference, `AGENTS.md` (the opening fleet sentence) and the release notes: existing pairings upgrade themselves, and old fleet invites stop working.

**Blocked by:** 02–09

**Status:** ready-for-agent

**Parent:** `.scratch/fleet-redesign/spec.md`

- [ ] No user-facing doc says room or fleet invite; links resolve
- [ ] ADR 0012 states every "Decided in this spec" item from the spec, or links to it
- [ ] `CONTEXT.md` entries follow ADR 0005's prescriptive style

## Comments
