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

Create and list accept `--json`. Create accepts `--ttl-seconds` from 1 to 3600.
Only create prints a secret; list shows session identifiers, labels, last-seen
timestamps, and revocation state. Revocation rejects the session's next connection.

## Rooms

Rooms (ADR 0011) are hostless reachability groups: a shared secret plus an
address book, copied to every member. A room shares reachability, never data —
each chat still belongs to exactly one engine, and nothing syncs between
members. Joining an engine stores only a SHA-256 verifier of the room secret
(the pairing invariant: no usable room secret is ever at rest on an engine)
plus the member addresses, persisted with the same 0600 discipline as relay
`connect.json` entries.

```sh
roboco engine room join 'roboco-room:…'
roboco engine room list
roboco engine room leave ROOM_ID
```

The commands work while the engine is running. An invite
(`roboco-room:<base64url(json)>`) is a secret — paste it, never commit it;
the CLI stores the verifier and discards the secret. Sessions minted through
a room are ordinary sessions: listed in `roboco engine pairing list`, revoked
the same way. Eviction is rotate-then-revoke — mint a new invite, `room join`
it, `room leave` the old room, then revoke the sessions it minted.

Clients join by presenting the secret: `POST /room/present {secret, label}`
on a member engine's listener (redeem-style, before any session) verifies
against the stored verifier and mints an ordinary session labeled by the
presenter. Presentations are repeatable — re-presenting with the same label
reuses the room's session — and failed presentations are throttled. The
session-authenticated `GetRoomBook` (which additionally requires presenting
the room secret, because member addresses are transport secrets) and
`UpdateRoomBook` (union-merge) maintain the book. Room code never mutates
remote-access settings: joining only works on engines that are already
reachable, so unlike `CreateTailcatInvite` there is no settings escalation.

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
spaces appear in the existing sidebar with device labels. Pick a device in the
space palette, then choose a folder; the composer space chip identifies where the
new chat will run. You can also enter an absolute path for that engine. An existing
folder opens on Enter; a missing folder offers **Create and add**.

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
| Attachments up to 100 MB per file | `uploads-100mb-v1` |
| Rooms (`/room/present`, `GetRoomBook`, `UpdateRoomBook`) | `rooms-v1` (older engines answer `unknown method`; clients hide room UI on its absence) |

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
