# Connect once: your devices, connected both ways, everyone together

Status: ready-for-agent
Spec date: 2026-10-28
Origin: design discussion with the user, 2026-10-28 ("I feel like it's still too buggy and I want a more reliable way. Something that's reliable, not buggy but still easy to use."). This spec replaces the fleet room model (ADR 0011 and its fleet amendment) and the deleted fleet-merge plan, and absorbs the fleet-agents plan (tickets 12–15). Older fleet specs (`rooms`, `fleet-follow`, `fleet-merge`, `fleet-agents`) were deleted on 2026-10-28; read them with `git show 42cbc82ac:.scratch/<folder>/…`. Code refs are against `main` at `42cbc82ac` (v0.14.0 plus specs).

## Problem Statement

The user has four servers: PC (Windows desktop app), Laptop (desktop app), Linux SG and Linux US (headless), plus a phone. Connecting them is hard, and once they are connected, they drift apart.

**Why connecting is hard**
- **Every pairing is one-way.** Pairing the PC to SG lets the PC drive SG, but SG has no way back. Every pair of machines needs its own step, sometimes in both directions.
- **Two separate jobs with four kinds of code.** You pair first, with a pairing link or a Tailcat invite. The fleet is a second layer with its own invite, copied from one desktop and pasted on another ("Join a fleet with an invite"). Plain rooms bring a fourth kind of code.
- **Codes are long and short-lived.** A Tailcat invite is about 475 characters: the address embeds a full DERP region description (this PC's saved address is 248 characters; `adapter.go:452-467` persists `state.Region`), and the JSON gets base64-encoded twice. Fleet invites grow by about 380 characters per member. The QR codes come out at version 17–34 and don't scan. Pair codes expire after 5 minutes.
- **The page is a maze.** Settings → Remote access mixes a remote-access switch, two kinds of link, a Fleet card with five states and include switches, a fleet invite paste box, and a session list that shows "Roboco desktop" twice. The page doesn't even scroll: `RemoteAccessPage::render` (`crates/ui/src/settings/remote_access.rs:226-235`) is the only settings page with no scroll host. `AccountsPage` uses `widgets::PageScroll` with `overflow_y_scroll` (`accounts.rs:2032-2043`).

**Why it drifts (the root causes behind "three fleets")**
1. **A fleet is a random secret, and whoever goes first mints it.** Two mints make two fleets permanently. Nothing picks a winner and nothing merges them (`crates/ui/src/rooms.rs:575-584`; `crates/engine/src/rooms.rs:206-228` refuses with `FleetConflict`).
2. **Nobody owns the member list.** Engines never talk to each other. Books are unions that a client pushes when a desktop happens to be open and members happen to be reachable.
3. **Four copies of the truth:** the desktop's `fleet-v1.json`, each engine's `fleet_rooms` row and book, the phone's room records, and each client's paired-engine list. The heal and merge logic is written three times (desktop, phone core, CLI).
4. **Machines are tracked by address, not identity.** An engine without Tailcat isn't in the book at all. When the desktop changed how it saves Tailcat routes in v0.14, SG and US quietly fell off the PC.
5. **Agents can't reach other machines** (rooms story 15 was never built), because engines hold no sessions on each other.

## Solution

**Connect once, and you're connected to everything.** One action and one kind of code, connections that work both ways, and the servers keep the member list among themselves.

1. **Two kinds of device.**
   - A **server** runs an engine: a desktop app or a headless machine.
   - A **controller** only drives servers (the phone; the desktop app's UI is also a controller, of its local engine and the rest).
   - Servers connect to each other both ways. Controllers connect to servers one way: nothing ever connects *to* a phone.
2. **One action: Connect.** Any server can show a **connect code**:
   - on a desktop: Settings → Devices → **Connect a device** (QR, Copy code, Copy command, Enlarge);
   - on a server: `roboco connect` (with `--qr` if wanted).

   Any device can use one:
   - on a desktop: **Enter a code**;
   - on the phone: scan or paste;
   - on a server: `roboco connect <code>`.

   Whichever side has a screen shows the code.
3. **Connecting goes both ways.** When server A uses server B's code, A gets a session on B, and B gets a session on A in the same handshake (A hands B a one-time code back). Both record each other as peers by device id.
4. **Connecting brings everyone along, and skips what you already have.** After A and B connect, each tells the other its peers. For every peer that one side lacks, a server that already holds a session on that peer introduces them: it asks the peer for a one-time connect code and hands it over. Devices are matched by device id, so already-connected devices are filtered out and never duplicated. Removed devices stay out (item 6), and nobody ever connects to itself. When peers change, servers tell their connected peers. A server that was offline catches up when its link comes back. No desktop needs to be open.
5. **Controllers follow.** A phone connects to any one server and asks it for its peers. That server introduces the phone to each one (controller sessions only). The phone watches the peer list on any connected server, so new servers appear live with no re-pairing. The desktop app does the same through its local engine. Its own saved list becomes a cache, and the local engine is the truth.
6. **Remove from anywhere.** **Remove** on any device, or `roboco devices remove <name>`, records a removal and spreads it to every server. Every server revokes the removed device's sessions, drops its credential on that device, and refuses to introduce it again. The removed device is told, best effort, to forget everyone. Only a direct, explicit connect with a fresh code brings it back (the Syncthing re-introduction lesson).
7. **Short codes that scan.** A connect code carries one server's route plus a one-time secret, packed in binary:
   - the Tailcat node key, disco key and pre-shared key;
   - a DERP **region id** instead of the full region;
   - a 128-bit one-time secret.

   That's about 160 characters (`roboco:` + base64url) instead of about 475, and it never grows with the number of devices. The QR is drawn crisp, large enough to scan, and has Enlarge. CLI QRs print dark on light and warn when the terminal is too narrow. Engines on a custom DERP map, and LAN-only engines, use the same grammar with a longer route field.
8. **Agents span connected servers** (tickets 12–15). The engine forwards an agent's Roboco tool calls over the peer sessions. `list_devices` shows every connected server with online status, chat tools route by device, and completion notices cross machines (push plus pull catch-up). Research: `research-agents-2026-10-28.md`.
9. **The settings page is rebuilt.** Remote access and Devices merge into one **Devices** page: this computer, Connect a device / Enter a code, your devices, and a collapsed Advanced section. It scrolls.
10. **Rooms are deleted.** Plain and fleet rooms, fleet invites, the Fleet card, `fleet-v1.json`, `/room/present`, `/pairing/room-redeem`, the room RPCs and `roboco engine room *` all go. The same goes for the fleet-secret-at-rest exception in the ADR 0011 amendment.

What does **not** change:
- Data stays engine-local (ADR 0004). Connecting shares reachability and control, never data. Each chat lives on one server, and nothing syncs.
- No accounts and no cloud. Transport is LAN or Tailcat (ADR 0008); Tailscale's public DERP relays carry bytes only, as today.
- Sessions stay ordinary, listed and individually revocable.
- Codes, routes and credentials never appear in logs, `Debug` output or error text (ADR 0008).
- Remote access is never switched on behind the user's back (see User decisions).

## User Stories

1. As the user, I connect the Laptop to SG with one code. The Laptop can now drive SG, SG can drive the Laptop, and both are connected to everything either one already had.
2. As the user, I run `roboco connect <code>` once on a new server and it joins all my devices.
3. As the user, I connect my phone to any one server and see every server. A server I add later appears on the phone without re-pairing.
4. As the user, connecting a device that brings along machines I already have never duplicates anything. Each device appears once.
5. As the user, an offline server catches up when it comes back. No desktop app has to be open for that.
6. As the user, I remove a lost phone or a retired machine from any device. It loses access everywhere and never comes back unless I connect it again myself.
7. As the user, the connect QR scans from a normal distance on a laptop screen and from a dark terminal, and I can paste the short code into an SSH session.
8. As the user, Settings → Devices shows me plainly what's connected, what's online, and how to add one. The page scrolls.
9. As the user, after upgrading, my existing pairings become two-way connections on their own, and my three old fleets end up as one set of devices.
10. As an agent user, an agent on the PC creates and drives a chat on SG and hears back when it finishes (fleet-agents).

## Implementation Decisions

### Settled by the user (2026-10-28)
- **Start from scratch** rather than patch the fleet model (fleet-merge stays on hold, superseded).
- **Connecting is mutual between servers.** "If I connect A to B, B also has access to A."
- **Two kinds of client:** "client only" (controllers: the phone, which has no server and just controls servers) and "server clients". Nothing connects to a controller.
- **Connecting brings everyone along**, and it **filters out devices already connected** (no duplicates).
- **No auto-switching of remote access.** "No need for that, cuz when I turn it on, I won't turn it off." A server with remote access off can't show a code: the dialog says "Turn on Let my devices connect first" and puts the switch right there.
- **No same-Wi-Fi auto-discovery** for now.
- **Delete plain rooms** (and the rest of the room machinery).
- **Shorter codes**: yes. A short typed code like `7-blue-cat` is impossible without a lookup server, so about 160 characters is the floor.
- **Agents across connected servers** are part of the purpose (fleet-agents).
- **Rework the Remote access UI** ("we gotta rework on it as well") and **fix its scrolling**.

### Decided in this spec (overrule in a ticket comment before building, never silently)
- **Words.**
  - The UI says **Devices** / **your devices** / **Connect** / **Remove**. It never says fleet, room, pairing or invite.
  - The glossary keeps *Fleet* as the internal word for "all servers connected to each other, transitively". *Room* is deleted from CONTEXT.md.
  - *Pairing* survives only as the internal name of the session mint.
- **Identity is the engine's device id** (`EngineInfo.deviceId`). Routes are properties of a peer and may change; peers refresh each other's routes on every link.
- **Engine store:** a `peers` table in `pairing.sqlite3` (owner-only, WAL sidecars included), with one row per device id:
  - name and kind (server/controller);
  - routes (secret);
  - our credential on that peer (secret);
  - the session id it holds on us;
  - state (`connected` / `one-way` / `removed`), `removed_at`, `last_ok`.
  The pairing store is the only truth. Clients cache.
- **Connect codes** are single-use and valid for **15 minutes** (up from 5). Codes are ADR 0008 secrets with redacted `Debug`, and `POST /connect` failures are throttled, like the pairing redeem. The grammar `roboco:<base64url(binary)>` has a version byte. Old `roboco-tailcat:` invites and `http…/pair#token=` links are still accepted. They make a one-way controller pairing, which the desktop then upgrades (next item).
- **Upgrading existing pairings.** With `connect-v1` on both ends, a desktop that holds a plain session on server B asks B for a one-time connect code (`MintConnectCode`, session-authenticated), and its local engine redeems it, making the pair mutual. The phone keeps its sessions as controller sessions and reconciles through `ListPeers`. Leftover room rows and secrets are securely erased on upgrade. Together, this turns the user's three fleets into one set the first time the PC and Laptop apps run.
- **Remote access off on one side.**
  - If B can't dial A back, because A serves no route, the peer is `one-way` and its row reads "Can't reach back: turn on Let my devices connect on PC".
  - Introductions still flow through B.
  - On a headless server, `roboco connect` behaves like `roboco engine tailcat invite` today: it starts a background engine if none is running, and it sets up Tailcat only when remote access has never been configured. If remote access was explicitly turned off, it refuses with a one-line hint.
- **Peer links** port upstream `LinkCache` by intent (fleet-agents research):
  - a lazy link per peer, with a dial lock and 3 attempts;
  - an `EngineInfo` readiness probe;
  - a 5→60 s cooldown;
  - cleared on network change;
  - an end-to-end keepalive.
  Each peer's status is `Live` / `Unreachable` / `Refused` / `Update needed`. Gossip, introductions, `list_devices` and the UI all read this.
- **Gossip** happens over peer links.
  - On link-up, each side pulls `ListPeers` from the other.
  - On any change, a server pushes `PeersChanged` (no payload) and receivers pull.
  - Removals are tombstones that win over adds for the same device id until a direct connect clears them. Tombstones are kept for 90 days.
- **Capability `connect-v1`** gates `POST /connect`, `MintConnectCode`, `ListPeers`, `WatchPeers`, `Introduce`, `RemovePeer` and `PeersChanged`. An engine without it stays a one-way paired engine, and its row reads "Update Roboco on SG to connect it both ways".
- **Admin, pairing and peer RPCs are never forwarded** to other machines (fleet-agents' allowlist rule).
- **Accepted consequence:** connecting any server to one of yours gives it access to all of yours, and every server holds working sessions on its peers. That was already true of fleets. Connect is an own-devices action, and the docs say so plainly.

## Testing Decisions

- **Engine** (loopback multi-engine harness, `crates/engine/tests/`, in the shape of `zeron/main:crates/engine/tests/device_routing.rs`):
  - A uses B's code → both hold a session on each other (mutual).
  - Three-component join: {PC}, {Laptop}, {SG, US} → connecting Laptop–SG and then PC–US converges to 4 mutual peers with no duplicates.
  - An offline peer catches up on link-up.
  - A removal spreads, revokes sessions on every server and stops re-introduction; a direct reconnect clears it.
  - `one-way` when the code-user serves no route.
  - Codes are single-use, expire, and redeem failures are throttled.
  - No code, route or credential in logs or `Debug`.
- **Codec** (`crates/proto`):
  - round-trips;
  - stays ≤ 170 characters for a Tailcat region-id route;
  - old invite and link forms still decode;
  - errors are shape-only.
- **CLI** (`apps/roboco/tests/`):
  - `roboco connect` with and without a code;
  - `roboco devices` (`--json`) and `devices remove`;
  - stable exit codes;
  - `--qr` output decodes with `rqrr` and has explicit colours.
- **Desktop** (`crates/ui`, fake dialer):
  - the local engine's peers drive the device list;
  - existing pairings are upgraded once;
  - the Devices page renders every row state and scrolls;
  - the painted QR decodes at its real logical size, with at least 4 px per module.
- **Android** (`crates/mobile-client` + JVM unit + emulator flow):
  - connect to one server → all servers appear;
  - a server added later appears live;
  - removing the phone from a desktop revokes it everywhere.
- **Manual (final ticket):** the user's exact layout (PC, Laptop, SG, US, phone) over LAN and Tailcat, starting from their real v0.14.0 three-fleet state.

## Out of Scope

- Same-Wi-Fi auto-discovery (mDNS) and short typed codes. Both may come later; typed codes need a lookup server.
- Multi-user setups and per-device permission scopes. Any connected server can drive any other.
- Syncing or mirroring chats (ADR 0004).
- The web client: it is being removed (`.scratch/web-removal/`). Rooms' web relay flow goes with it.

## Supersedes / keeps

All four older fleet folders were deleted on 2026-10-28 (still readable in git at `42cbc82ac`):

- **`fleet-merge`: superseded.** Ideas carried over: the QR fixes (its tickets 04 and 05 became ticket 07's QR part and ticket 05's QR part here), phones following changes, and convergence of the user's three fleets. Merge rules, `RoomJoin{replace}`, `rooms-v3` and pending moves are dropped.
- **`fleet-agents`: absorbed.**
  - Its ticket 01 (engine peer sessions via the fleet secret) is replaced by tickets 02–03.
  - Its tickets 02–05 became tickets 12–15, with "fleet member" read as "connected server".
  - Its research note moved to `research-agents-2026-10-28.md`.
- **`fleet-follow`, `rooms`: shipped history** (v0.14.0, v0.13.0). Their code is deleted by ticket 09.

## ADR changes to record (ticket 10)

- **New ADR 0013 "Connected devices"** (0012 is taken by the web-removal PR, which supersedes ADRs 0006/0007), superseding ADR 0011 and its fleet amendment. It records:
  - servers vs controllers;
  - mutual connect;
  - transitive introductions by servers that hold sessions on both sides;
  - engine-to-engine peer sessions and gossip;
  - removal tombstones;
  - identity by device id;
  - the deletion of rooms and the fleet secret at rest.

  It also records why this is now acceptable although ADR 0011 rejected "engines as clients" and "on-behalf-of" designs: attribution is per presenting engine and revocable, and there's no secret group credential. ADR 0011 gets a "Superseded by 0013" line.
- **ADR 0004:** a note that peer sessions are control over a session, not sync.
- **ADR 0008:** the compact `roboco:` connect-code grammar, region-id routes, the 15-minute TTL, and the old forms that are still accepted.
- **CONTEXT.md:** Fleet rewritten; Room deleted; Server, Controller and Connect code added.

## Tickets

| # | ticket | blocked by |
|---|---|---|
| 01 | `issues/01-settings-page-scrolls.md` | — |
| 02 | `issues/02-engine-connect-codes-and-mutual-connect.md` | — |
| 03 | `issues/03-engine-peer-links-introductions-gossip.md` | 02 |
| 04 | `issues/04-engine-remove-from-anywhere.md` | 03 |
| 05 | `issues/05-cli-connect-and-devices.md` | 02 (devices remove: 04) |
| 06 | `issues/06-desktop-local-engine-is-truth-and-upgrade.md` | 03 |
| 07 | `issues/07-desktop-devices-page.md` | 01, 06 (Remove: 04) |
| 08 | `issues/08-android-controller-follows.md` | 03 (Remove: 04) |
| 09 | `issues/09-delete-rooms.md` | 06, 08 |
| 10 | `issues/10-adr-and-docs.md` | 02–09 |
| 11 | `issues/11-final-verification.md` | 01–10 |
| 12 | `issues/12-agents-tools-span-connected-servers.md` | 03; wave-10 03 (IPC token) |
| 13 | `issues/13-agents-cross-machine-notices.md` | 12 |
| 14 | `issues/14-agents-adr-and-docs.md` | 10, 12, 13 |
| 15 | `issues/15-agents-final-verification.md` | 11–14 |

- Ticket 01 is a small, independent fix and can ship on its own right away.
- 02–11: one PR, one commit per ticket, branch `feature/connect-devices` off `main`, after the web-removal PR merges.
- 12–15 (agents): a second PR, `feature/connect-agents`, after the first merges.

## User decisions (2026-10-28)

- "I think we gotta think from the scratch… I want to connect all my devices in an easy way, no matter what's the host, and I think if I connect A to B, the B also have access to A also." "Agents between connected devices can interact with each other."
- Two kinds of clients: "client only and server clients, client only mean the phone itself don't have a server, it just there to control server clients."
- Auto-enable Tailcat when connecting: no ("when I turn it on, I won't turn it off").
- Same-Wi-Fi auto-discovery: no. Delete plain rooms: yes.
- Shorter invite codes: yes, after hearing that about 160 characters is the floor without a lookup server.
- "Make sure that the connecting bring everyone along able to filter out connected devices": recorded as matching by device id, skipping what's already connected, and keeping removed devices out (Solution 4, User Story 4).
- "About the UI… we gotta rework on it as well, also that remote access page isn't scrollable": tickets 01 and 07.
- "Sounds good" to the overall direction; asked for the write-up.
