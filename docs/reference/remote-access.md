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
uses the app-owned `roboco-tailcat` adapter, bundled beside Roboco in releases.
Source builds need to [build that adapter too](../../adapters/roboco-tailcat/README.md);
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

Resume never changes `contextUsage`, the `replayBaseline` watermark (the first
frame of a stream always carries it, so resumed catch-up content is treated as
history and not animated), or `diff_transcript`'s behaviour on streams that did
not opt in — a plain subscription is byte-for-byte what it was before the
capability existed.
