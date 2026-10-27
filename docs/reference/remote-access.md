# Remote access

Every engine keeps its local client connection. Remote access is off by default.
Enable **Settings → Remote access → Allow remote connections**, create a pairing
link, and paste it into **Settings → Devices** on another client. Links expire after five
minutes and work once. Paired sessions remain valid until revoked from Settings
or the CLI. Disabling remote access closes the remote listener and its connections.

## Headless engines

On an engine without saved remote settings:

```sh
roboco headless --network
```

The remote listener defaults to `0.0.0.0:27655`; local IPC remains on its own
loopback port. Startup prints a fresh pairing URL using the machine's LAN address.
You can choose a bind or advertise an operator-managed tunnel:

```sh
roboco headless --network --network-address 0.0.0.0:27655 --pairing-base-url https://my-engine.example
```

A base URL must be the server root: tunnel URLs carrying a path prefix (for
example `https://host.example/roboco`) are refused, because the engine serves
pairing, health, and WebSocket routes at the listener root.

`ROBOCO_NETWORK=true` is the headless environment equivalent. Accepted values are
`true`, `false`, `1`, and `0`. The desktop reads its saved settings. Startup flags
do not rewrite the Settings file.

**Conflicting choices keep the engine local.** If saved settings disable remote
access, `--network` cannot override that choice. Change the saved setting first.
Conflicts between the flag and environment also disable the remote bind. The
engine logs the configuration source and the reason for any refusal.

## Tailcat transport

Tailcat publishes the same listener over Tailscale's data plane — WireGuard with
NAT traversal and public DERP relays — with no account and no control plane. It
uses the app-owned `roboco-tailcat` adapter, bundled beside Roboco in Linux
releases and embedded in the Windows `roboco.exe` (so in-app updates and the
portable `.exe` carry it). Source builds need to
[build that adapter too](../../adapters/roboco-tailcat/README.md) —
`cargo xtask tailcat-adapter` puts it beside `target/debug/roboco` — and
`ROBOCO_TAILCAT_ADAPTER` can point to a development build.

On each VM, run just:

```sh
roboco engine tailcat invite
```

This command starts a detached background engine if none owns the data directory,
explicitly enables and saves Tailcat remote access, waits for a live route, and
prints a `roboco-tailcat:…` invite. It switches previously disabled or `network`
settings to Tailcat; the remote listener is loopback-only. The command exits while
the engine stays running. Running it again issues a new invite without restarting
a healthy route. `--json` prints `{id, url, expiresAt}`; `--ttl-seconds` accepts
1–3600 (default 300). Use the same `ROBOCO_DATA_DIR` to target a custom engine.
An auto-started engine lasts until stopped or the VM reboots; install a daemon
with `roboco daemon install` separately if you need boot persistence.

On another **native desktop**, paste the invite into **Settings → Devices**.
The desktop owns its Tailcat connection; no `connect` command is required.

On **Android**, scan or paste the invite on the Pair page. The app keeps its
own Tailcat connection and reconnects after restarts and network changes.

For the **browser**, run this on the machine running the browser:

```sh
roboco engine tailcat browser-helper
```

Then paste the invite into the web client's **Settings → Devices**. Keep the helper
running. Alternatively, use the explicit forwarder described below.

Advanced foreground/service setup still supports:

```sh
roboco headless --network --network-transport tailcat
```

Unlike `invite`, these startup flags do not change saved settings: they must
agree with them. `ROBOCO_NETWORK_TRANSPORT=tailcat` is the environment equivalent.
In Tailcat mode the engine binds its remote listener to loopback: an unspecified
bind narrows to `127.0.0.1`, and a non-loopback `--network-address` is refused —
the network transport is the LAN-serving path.

An invite carries the route and a short-lived, single-use pairing code — the
client's listener port exists only on the client, so there is nothing to put in a
link. The address inside it can embed a pre-shared key: it is a secret, written
0600 to `tailcat-address.json` and never logged. `--tailcat-derp-map URL` (or
`ROBOCO_TAILCAT_DERP_MAP`) points the adapter at your own DERP relay instead of
its default map. For one-command setup, export `ROBOCO_TAILCAT_DERP_MAP` before
starting the engine. If the route cannot come up, remote access stays off and the
engine reports why.

### Administering a Tailcat route

```sh
roboco engine tailcat address          # the address this engine published
roboco engine tailcat invite --json    # a fresh invite, code included
roboco engine tailcat connect --invite roboco-tailcat:…
```

The first two run beside the engine. `connect` runs on the *client* machine: it
dials the invite and prints that engine's loopback pairing URL, for example
`http://127.0.0.1:43627/pair#token=…`. Paste it where a pairing link goes — a
browser tab, or **Settings → Devices** — and keep the forwarder running; it
dialled out through Tailcat, so there is no inbound port and no tunnel account.
Everything after the redeem is unchanged: sessions live until revoked, and
`roboco engine pairing list` / `revoke` work the same over either transport.

## Pairing administration

These commands work while the engine is running and use its normal data directory
(`ROBOCO_DATA_DIR` can select another engine installation):

```sh
roboco engine pairing create --base-url http://192.168.1.20:27655 --label Laptop
roboco engine pairing list
roboco engine pairing revoke SESSION_ID
```

Create and list accept `--json`. Create accepts `--ttl-seconds` from 1 to 3600,
and `--qr` prints the link as a terminal QR code under the URL line.
Only create prints a secret; list shows session identifiers, labels, last-seen
timestamps, and revocation state. Revocation rejects the session's next connection.

## Fleet sharing

A fleet is every engine you use, introduced to each other once: pair your phone
with any engine in it, and the rest arrive as ordinary paired engines. A fleet
shares reachability, never data. Each chat still belongs to exactly one engine
(ADR 0004), and nothing syncs or relays between members. Under the hood a fleet
is a fleet room ([ADR 0011](../adr/0011-rooms.md) and its fleet amendment). The
app never says "room".

### Quickstart

1. Pair your servers to the desktop as usual (**Settings → Devices**).
2. The desktop pushes its fleet to each of them automatically. **Settings →
   Remote access → Fleet** shows each one as **In**.
3. On the phone, scan the ordinary pairing QR of the desktop or of any server.
4. The rest of the fleet comes along: "Adding the rest of your fleet…", then
   "Added N engines from your fleet".
5. That's it. The new engines sit in the phone's engine list like any other.

**Prerequisite:** an engine joins the fleet only while it serves its Tailcat
route (Remote access on, Tailcat transport; `roboco engine tailcat invite`
sets that up on a server). Members reach each other over the addresses in the
fleet's book. An engine without a Tailcat route shows "Not in yet · turn on
Remote access with Tailcat on that engine". The desktop's own engine is in the
book only when it serves Tailcat too. Otherwise a phone that pairs with a
server gets the servers but not the desktop.

The desktop creates the fleet the first time it has someone to share it with,
named "Fleet". There is one fleet per data directory. Nothing happens, and no
fleet exists, until then.

### Pairing a phone through a server

Introduction is symmetric: any engine in the fleet can introduce a phone. On a
server that serves Tailcat:

```sh
roboco engine tailcat invite --qr
```

On an engine reachable over the LAN or your own tunnel, the plain pairing link
works the same way:

```sh
roboco engine pairing create --base-url http://192.168.1.20:27655 --qr
```

Scan either QR on the phone's Pair page. After the pairing is saved, the phone
asks that engine for its fleet (`GetFleetInvite`) and joins it silently. An
engine with no fleet, or one too old to have fleets, answers nothing, and the
pairing looks exactly like it always did. The pairing QR is unchanged: the
fleet never rides in it.

### The Fleet card

**Settings → Remote access** shows a Fleet card when the local engine supports
fleets. It has one row per engine:

- **In**: the engine has the fleet.
- **Not in yet**: it does not have it yet. The caption says why when it is
  known: "offline", or "turn on Remote access with Tailcat on that engine".
  **Retry** runs the push again.
- **Update that engine to add it**: the engine predates fleets.
- **In a different fleet**: the engine already belongs to another fleet (see
  [Leaving and evicting](#leaving-and-evicting)).
- **Left out**: you switched it off.

Each engine the desktop has reached has an include switch. Turning it off
leaves that engine out of future pushes. See
[Leaving and evicting](#leaving-and-evicting) for what it does not do.

**Show fleet invite** shows the fleet as a QR code plus a copyable
`roboco-room:…` string (and creates the fleet first if there is none). The
invite is a secret: anyone who has it can reach every engine in your fleet.
Share it only with your own devices, and never commit or paste it into a chat.

### Joining from another computer

A second desktop joins your fleet with the invite: copy it from **Show fleet
invite** on the first desktop, then paste it into **Join a fleet with an
invite** on the second. Pairing the second desktop to a fleet engine is not
enough: desktops push their own fleet and never fetch one.

If the second computer already has a fleet of its own:

- A fleet with no other engines in it is replaced silently.
- A fleet shared with other engines is not replaced without asking: the card
  says "This computer is already in a fleet with other engines. Joining this
  one leaves it." **Leave it and join** switches. Leaving revokes the sessions
  that fleet created and forgets only the engines it added. Engines you paired
  by hand are never touched.

The field accepts fleet invites only. A plain room invite is refused with
"That invite is for a room, not a fleet. Join rooms from a terminal: roboco
engine room join" (see [Plain rooms](#plain-rooms)).

### Starting a fleet on a server

A fleet can also start on a headless engine, with no desktop involved:

```sh
roboco engine tailcat invite             # serve Tailcat first, so the server is in its own fleet
roboco engine room create                # prints the fleet's room id, never its secret
roboco engine room invite ROOM_ID --qr   # the invite (a secret) plus a terminal QR
```

`create` names the fleet "Fleet" unless you pass `--name`. It adds this
engine to the book under its device name (`--label` overrides) when the engine
has recorded a Tailcat address. With no address it still creates the fleet,
with an empty book, and says so on stderr. A data directory holds one fleet;
a second `create` is refused.

From there, a phone pairing with this server follows the fleet as usual. A
desktop joins by pasting the invite into **Join a fleet with an invite**, then
pushes the fleet to the engines it has paired. Another engine can join with
`roboco engine room join 'roboco-room:…'`, but a CLI join does not put that
engine's own address in the book. Pairing it to a desktop in the fleet does:
the desktop adds every engine it pushes to.

### Duplicates

Re-pairing an engine, re-running a join, or receiving a fleet that contains
engines you already paired never creates a second entry. The desktop and the
phone match members to the engines they already know by identity (and the
phone by address too), and an engine re-joining a fleet it holds refreshes the
same row.

One leftover is possible: when an already-paired engine is matched only after
presenting the fleet secret (for example, it was paired over the LAN but the
book lists its Tailcat address), the old pairing session stays valid but
unused on that engine. Revoke it from that engine's **Settings → Remote
access**, or with `roboco engine pairing revoke`, if you want a tidy list.

### What "Not in yet" means

Nothing probes engines in the background, by design. Fleet state is computed
when the desktop runs its fleet pass. That happens when the app opens, the first
time each engine connects after the app starts (which covers new pairings), on
**Retry**, and when you flip an include switch. A sleeping or offline engine
stays "Not in yet" until one of those runs while it is reachable. Phones heal
the same way: a member that was asleep during the join is retried on the next
app start or network change. Books merge as unions, so a stale address on one
engine is corrected from another.

### Leaving and evicting

The include switch is forward-only. Turning an engine off stops future pushes
and stops sharing its address. An engine that already joined keeps the fleet
until you run, on that engine:

```sh
roboco engine room list                 # find the fleet's room id
roboco engine room leave ROOM_ID        # forget the fleet and erase its secret
```

The card has no Leave button; a desktop leaves its fleet only by switching to
another one (above).

A fleet gates future introductions, not existing sessions. Every session it
minted is an ordinary session, valid until revoked on the engine that issued
it. To evict a device, such as a lost phone:

1. **Leave.** Run `roboco engine room leave ROOM_ID` on every engine in the
   fleet, the desktop's own engine included. The old secret then matches
   nothing.
2. **Re-create.** Reopen the desktop app and it creates a fresh fleet and
   pushes it to your paired engines (or `roboco engine room create` on a
   server).
3. **Re-pair.** Pair your remaining phones again; they follow the new fleet.
4. **Revoke.** On each engine, `roboco engine pairing list` shows the old
   sessions by label, and `roboco engine pairing revoke SESSION_ID` (or that
   engine's **Settings → Remote access**) rejects each one's next connection.

### Fleet for agents

An agent running on any fleet engine can introduce a device and inspect the
fleet with the CLI. Every command works while the engine is running (the CLI
shares the pairing database through WAL) and honours `ROBOCO_DATA_DIR`.

**Introduce a phone.** Print a pairing QR. The phone pairs, then fetches and
joins the fleet on its own. No fleet flag is needed:

```sh
roboco engine tailcat invite --qr          # Tailcat engines; --json: {id, url, expiresAt}, plus qrSvg with --qr
roboco engine pairing create --base-url URL --qr   # LAN/tunnel engines; --json: {id, url, expiresAt}, plus qrSvg with --qr
```

**Start and share a fleet:** `roboco engine room create` and
`roboco engine room invite ROOM_ID [--qr]`, as above. Only `invite` ever prints
an invite. Print one only when a human asked for it.

**Inspect:** `roboco engine room list --json`.

Every room command accepts `--json`. The shapes are stable:

```text
room create --json   {"id":"<room id>","label":"<room name>","joinedAt":<unix ms>,"kind":"fleet","memberCount":<n>,"book":{"members":[{"label":"<engine label>","address":"<tc… address>"}]}}
room invite --json   {"roomId":"<room id>","invite":"roboco-room:…"}
                     with --qr adds "qrSvg":"<svg>" (null when the invite is too long for a QR code)
room join --json     {"id":"<room id>","label":"<room name>","joinedAt":<unix ms>,"kind":"room"|"fleet","memberCount":<n>,"book":{"members":[{"label":"<engine label>","address":"<tc… address>"}]}}
room list --json     [{"id":"<room id>","label":"<room name>","joinedAt":<unix ms>,"kind":"room"|"fleet","memberCount":<n>,"book":{"members":[{"label":"<engine label>","address":"<tc… address>"}]}}]
                     (empty: [])
room leave --json    {"roomId":"<room id>","left":true}
```

The book's addresses are transport secrets. Read them, but do not echo them
into chats or logs. The exit codes are a contract:

```text
0  success
1  failure: a malformed invite, an unreadable store, an I/O error
2  usage error: unknown flag or missing argument
3  refused: this engine already belongs to a fleet, the room is not a
   fleet room, or no such room is joined
```

Errors go to stderr, starting with `Error:`; no JSON is printed on failure.
Refusals are one line of fixed text (`this engine already belongs to a fleet;
leave it first`, `not a fleet room`, `no such room`). They never echo the
command's input, so a mistakenly pasted invite stays off stderr. No room error
ever quotes an invite or a secret.

### Plain rooms

Plain rooms are the original, hostless form, kept for power users. An engine
that joins one stores only a SHA-256 verifier of its secret plus the book, so
no engine can re-share it. Clients hold the secret. Plain rooms have no desktop
UI: engines join with `roboco engine room join 'roboco-room:…'`, the phone's
Pair page accepts the invite directly, and the desktop's fleet field refuses
it. Nothing in this release mints a plain room (`room create` makes fleets
only), so plain rooms come from existing invites. `room list` and `room leave`
treat both kinds alike, and `room invite` refuses plain rooms with `not a fleet
room`.

### Under the hood

An invite is the room serialized: `roboco-room:` followed by base64url-no-pad
of `{"v":1,"name":"…","secret":"…","members":[{"label":"…","address":"…"}],"kind":"fleet"}`.
`kind` is absent for a plain room. It carries the secret and every member
address, so it is an ADR 0008 secret: never logged, written 0600 wherever files
are involved, and decode errors describe only its shape.

A client joins a member by presenting the secret at `POST /room/present`, which
mints an ordinary session labeled by the presenter. Re-presenting with the same
label reissues that session, so retries never pile up sessions. Failed
presentations are throttled. Clients union the books they can reach and push
the merged book back (`GetRoomBook`, `UpdateRoomBook`); engines never talk to
each other. Fleet engines also keep the fleet secret, in the pairing database
(`pairing.sqlite3`, owner-only on unix), so any of them can hand the fleet to a
newly paired phone (`GetFleetInvite`). The desktop pushes the fleet to its
engines with `RoomJoin` (idempotent; the book unions). Both RPCs need a paired
session and the `rooms-v2` capability, and no room code ever changes
remote-access settings.

The web client has no fleet join in this release. Its **Join room** flow
(beside the pair flow) takes plain room invites only and refuses fleet
invites. The browser cannot dial members itself, so for each member it calls
the origin engine's `POST /pairing/room-redeem {invite, target, label}`. The
origin engine dials the target from its own book, relays the presentation over
its tunnel, and returns the target's session grant with its base URL rewritten
to `…/tailcat-relay/{id}`; the existing relay byte pump then carries the
traffic. The origin engine relays the secret in transit but never stores it,
and the browser keeps each member's credential and the room secret in browser
storage, scoped to the origin.

## Saved settings

`remote-access.json` lives beside the engine's other settings:

```json
{
  "enabled": true,
  "bindAddress": "0.0.0.0:27655",
  "publicUrl": null,
  "transport": "network"
}
```

The Settings toggle takes effect immediately. Manual file edits are read at the
next startup or Settings change. Invalid files keep remote access off.

The engine serves plain HTTP/WebSocket on trusted networks. For internet access,
use your own TLS tunnel (or Tailcat, above). Pairing links carry the code in a
URL fragment; the native client submits it in the Authorization header, never in
a request URL.

## Using several engines

Paired engines reconnect automatically when the desktop starts. Their chats and
spaces appear in the existing sidebar with device labels. Engines that arrived
with a fleet (see Fleet sharing) appear here too, exactly like paired ones. Pick
a device in the space palette, then choose a folder; the composer space chip
identifies where the new chat will run. You can also enter an absolute path for
that engine. An existing folder opens on Enter; a missing folder offers
**Create and add**.

Settings → Devices shows connection state and lets you forget a paired engine.
Forgetting removes its saved connection and client cache. Revoke the session on
the engine when you also want to invalidate its credential.

When an engine is unreachable, cached chat lists and previously opened transcripts
remain readable. A compact strip marks the offline view, and sending is refused.
The client retries with capped backoff and restores live updates after reconnecting.
Each engine owns its files, terminals, queue and history; pairing does not copy
engine data between machines.

## Protocol compatibility

The first call on every connection is `EngineInfo`. Besides the engine's
identity (`deviceId`, `workspaceScope`) it is the compatibility handshake: the
engine advertises the release that built it, its wire protocol generation, and
the features it supports. Old clients ignore fields they do not know; new
clients read missing fields as "protocol 1, version unknown".

| Field | JSON | Meaning | Absent means |
|---|---|---|---|
| `version` | `"version":"0.9.2"` | The Roboco release that built this engine (`CARGO_PKG_VERSION`). For display and the newer-engine notice only. Never gate features on it. | An engine older than the field; the client may read the engine's `Device.version` row instead |
| `protocolVersion` | `"protocolVersion":1` | The wire protocol *generation*. Bumped only for an incompatible change (a removed or renamed method or field, a changed frame or auth meaning). ADR 0005 freezes wire names, so expect this to stay 1 for a long time. | Treat as `1` |
| `minClientProtocolVersion` | `"minClientProtocolVersion":1` | The oldest client protocol generation this engine still serves correctly. Raised only together with a bump of `protocolVersion` that drops the old generation. | Treat as `1` |

Clients assess the handshake with `roboco_proto::compat::assess` (a pure
module any client can call):

1. `minClientProtocolVersion` above the client's protocol generation → the
   engine refuses this client: **park the connection** (no reconnect loop),
   re-assess on app start or manual "Try again".
2. Engine `protocolVersion` below the client's oldest drivable generation →
   the engine predates what the client can drive: park, same as above.
3. Engine `protocolVersion` above the client's generation → a newer engine:
   keep working, decode leniently, show a non-blocking notice once per engine
   version.
4. The engine's `major.minor` is newer than the client's → a newer engine
   (rule 3 applies to the notice). A newer *patch* is silent; an unparseable
   version never blocks.
5. Otherwise compatible: features gate on capabilities, never on version
   numbers. An older engine is never flagged as a whole — each missing feature
   says so individually when its capability is absent.

The web client is served by its engine (ADR 0006), so it cannot skew and does
not park on these rules. The Android app is installed separately and follows
them in full (client-side lenient decoding lands with it).

### When a change needs a capability

| Change on the engine side | Gate | Why |
|---|---|---|
| New RPC method | none: older engines answer `unknown method: X`. Clients hide or degrade that feature | engine RPC dispatch |
| New param on an existing method, new `Mutate` op, new command kind or enum variant clients send, or changed semantics | **new capability string `<feature>-vN`** | older engines ignore unknown params silently or fail with `bad params` |
| New field in a reply or stream item | none: additive and serde-defaulted, and clients ignore unknown fields | ADR 0005 |
| New enum variant engines *send* (`MessagePart`, `ToolCall`, status) | none on the engine side; clients must decode leniently | unknown variants fail decoding in strict clients |
| Removing or renaming anything on the wire | forbidden (ADR 0005). If ever unavoidable: bump `PROTOCOL_VERSION` and decide `MIN_CLIENT_PROTOCOL_VERSION`, in an ADR | — |

Capability strings are never reused or removed within a protocol generation.

### Gate catalogue (Android app)

| Feature | Gate |
|---|---|
| Queue a message, watch the queue | `message-queue-v1` |
| Queue send-now, steer-now, remove | `message-queue-actions-v1` |
| Queue with attachments | `message-queue-attachments-v1` |
| Clean attachment text in queued rows | `message-queue-clean-attachment-text-v1` |
| Edit a queued message (60 s lease, renew every 20 s) | `message-queue-edit-lease-v1` |
| Mid-run steer (`QueueCommand{command:{kind:"steer"}}`) | per harness, not per engine: `HarnessDescriptor.supportsSteering` / `steeringMode` from `ListHarnesses` |
| `roboco-file:` mentions decoded by the host | `composer-references-v1` |
| Transcript resume on reconnect | `transcript-resume-v1` |
| `WatchDocMessages{openingTail:true}` | none (older engines ignore it and send a full reset, which is safe) |
| Folder browser (`ListDrives`, `PrepareSpacePath`), sidebar pins/sections (`WatchSidebarState`) | none (`unknown method` → hide the feature) |
| Transcript images by any reference (`ReadImageChunk{reference, chatId?}`: relative/`~`/`file:`/`roboco-file:`/`data:` references, engine-side URL fetch, SVG → PNG) | none (`unknown method` → the core falls back to `ReadAttachmentChunk` with the reference as a path; web and `data:` images still load on the phone) |
| Attachments up to 100 MB per file (legacy caps without it: 24 MB client-side, 32 MB engine-side) | `uploads-100mb-v1` |
| Rooms (`/room/present`, `GetRoomBook`, `UpdateRoomBook`) | `rooms-v1` (an engine without it answers 404 or `unknown method`; the join skips that member silently) |
| Fleet follow after pairing (`GetFleetInvite`) | `rooms-v2` (absent, or a `null` invite → no fleet, and the pairing stays exactly as it was) |

## Transcript resume

A phone loses its transcript socket constantly (Doze, network handoffs, going to
background), and every reconnect used to re-download the whole transcript: a
`WatchDocMessages` stream always opens with a full `reset` (7.6 MB on a large
chat — minutes over a slow relay). Resume is an opt-in, capability-gated fix:

- The engine advertises `transcript-resume-v1` in `EngineInfo.capabilities`.
- A client that kept a transcript copy sends a summary of what it holds with its
  next `WatchDocMessages {chatId}` call.
- When the summary matches, the first stream item is an ordinary **delta** that
  brings the client's copy up to date; otherwise it is the usual `reset`, exactly
  as before. Clients apply either with the existing `apply_transcript_frame`.

### Subscribe params

| Param | Type | Meaning |
|---|---|---|
| `digests` | `bool`, default `false` | Attach `digests` to every frame of this stream |
| `resume` | `TranscriptResume?` | Try to resume from the client's copy. Implies `digests: true`. Takes precedence over `openingTail` |

```jsonc
// TranscriptResume
{ "count": 412,                                  // entries the client holds
  "prefix": "e3b0c44298fc1c149afbf4c8996fb924", // digest of the first count − tail.length entries
  "tail": [ { "id": "e380", "digest": "9f2c0a1b44d0e7aa" } ] } // the last W held entries; W ≤ 512
```

A malformed `resume` object never fails the call: the engine logs it at debug
level and serves the stream as `digests: true` without resume, so a corrupt
cache can never block a transcript. Engines that predate the capability ignore
both params entirely.

### The `digests` frame field

`TranscriptUpdate` gains one optional field, present only when the stream opted
in:

| Field | Type | Meaning |
|---|---|---|
| `digests` | `{ [entryId]: string }` | **Reset**: the digest of every entry. **Delta**: the digest of every entry this frame upserts or appends to; removed ids drop out client-side. An empty delta carries `{}` |

The `historyPending` preview frames of `openingTail` never carry digests: they
are partial, and clients must never store them as resumable state.

### Digest definitions

| Function | Definition |
|---|---|
| `entry_digest(entry)` | lowercase hex of the first 8 bytes of `SHA-256(serde_json::to_vec(entry))` — 16 chars |
| `prefix_digest(digests)` | lowercase hex of the first 16 bytes of `SHA-256(d₀ ‖ "\n" ‖ d₁ ‖ "\n" ‖ …)`, each entry digest followed by one `\n` — 32 chars; empty input is `e3b0c44298fc1c149afbf4c8996fb924` |

Digests are opaque to clients: they store what the engine sent and send it
back, never recompute it. A different engine build may serialize entries
differently (for example after adding a field). Its digests then differ, the
prefix does not match, and the client gets a full reset — that costs bandwidth
but is always correct. Digests are deterministic within one build, so resume
also works across an engine restart on the same data dir.

### Client rules

- Store `{entries, digests}` per chat only from **complete** state: after a
  reset or delta on a `digests` stream. Never store an `openingTail` preview
  (`historyPending: true`) as resumable state.
- On (re)subscribe: if the engine advertises `transcript-resume-v1` and a
  resume summary can be built from the held copy, send `resume`. Otherwise
  send `digests: true`, plus `openingTail: true` when nothing is cached.
  The reference builder is `roboco_doc::TranscriptResume::from_held` (the
  default client window is the last 64 entries).
- After a `TranscriptDesync`, resubscribe **without** `resume` (a plain reset),
  so a bad summary cannot loop.
- If `roboco_doc::apply_update_digests` returns `false`, drop the stored
  digests: the update changed entries but carried none (an older engine).
- A subscription outlives any engine outage: it waits for the engine to come
  back (however long that takes) and resubscribes as soon as it does, never
  sitting out a backoff that grew during the outage. Only closing the chat
  ends it.
- A send acked by the engine but still missing from the transcript after its
  grace period (120 s on Android) forces one plain-reset resubscribe; the send
  shows as undelivered only if that reset arrives without it.

Resume never changes `contextUsage`, the `replayBaseline` watermark (the first
frame of a stream always carries it, so resumed catch-up content is treated as
history and not animated), or `diff_transcript`'s behaviour on streams that did
not opt in — a plain subscription is byte-for-byte what it was before the
capability existed.

## Mobile link liveness

A phone's process is frozen in the background and its sockets die with it, so
the Android core (`crates/mobile-client`) treats coming back as the common case.
None of this changes the wire.

- **Coming back to the foreground** probes a connected engine at once, with a
  short budget (4 s), instead of waiting for the next 5 s probe. A link that
  fails the probe reconnects; a link that is down dials immediately.
- **One reconnect per event.** Lifecycle commands are stamped with the time they
  were issued, and a command issued before the current dial started is ignored,
  so one foreground or network event never redials the link it just produced.
  A network-online report doesn't touch a connected engine.
- **Network changes** first verify a connected Tailcat link with that same
  probe; the tunnel is rebuilt only if the probe fails.
- **Slow isn't dead.** A health probe or transcript lag check that times out
  while bytes are still arriving (a large reset queued over a relay) keeps the
  connection.
- **Transcript connections end with their link.** When a link ends, the
  transcript connections opened under it close too, and the open chat
  resubscribes on the next link.
- **Clean closes.** Both sides send a WebSocket Close (bounded at 500 ms)
  whenever they drop a session on purpose, and the Tailcat adapter drains
  in-tunnel TCP before it tears a tunnel down. The engine no longer waits out
  its 45 s silence lease for sockets a client abandoned. The engine log tells
  `peer closed without Close frame` apart from `silent past lease`, and each
  line carries a `conn` id.
- **Tailcat dials** get 15 s for the WebSocket upgrade, since the loopback
  accepts at once and then dials the engine through the tunnel. A failed tunnel
  dial resets the shared Tailcat client only if a health ping to the engine
  also fails.
- **Sending while the agent works queues.** When the rows say a chat is idle
  but can't vouch for it (its Working row went stale, or the link hasn't proved
  itself within 10 s), a send asks the engine first, with a one-shot
  `WatchSessions` read. If a turn is running, the message follows the busy
  policy (queue by default) instead of starting a run that would cut the reply
  off.
