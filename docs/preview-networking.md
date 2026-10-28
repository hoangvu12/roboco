# Project previews

Run an HTTP development server in a project and the **Ports** card beside the
chat lists it — one row per server this chat can preview. On the desktop,
clicking a row opens it in your default browser at
`http://<device>.<project>.localhost:7331`; additional services receive
persistent names such as `<device>.<project>-api.localhost:7331`. Roboco has no
in-app browser on the desktop ([ADR 0012](adr/0012-no-web-client-no-embedded-browser.md)).
A chat on *another* engine opens the same way through the client-side preview
proxy (`127.0.0.1:7332`, below): the browser opens
`http://<device>.<project>.localhost:7332`, and the client relays it over the
session relay. Android's Ports panel (the ⋯ menu) loads the same URLs in its
in-app WebView.

`*.localhost` resolution is the browser's: Chrome, Edge and Firefox resolve
every `*.localhost` name to loopback without touching DNS or the hosts file, so
no system change is needed.

The daemon owns this listener, discovery and peer connections. Closing a
browser tab does not stop discovery or change an address. Account teardown cancels the
proxy, discovery, signaling, and existing peer streams before the next profile
starts. A busy proxy port produces an inline error instead of changing the URL.

## Discovery and identities

On Linux, the scanner joins the current user's `/proc` process cwd, ancestry,
creation time and socket descriptors to listening TCP sockets. On macOS it uses
`lsof` and `ps` for equivalent metadata. Only loopback-reachable listeners started by a Roboco agent run or terminal
are probed: the engine stamps `ROBOCO_ORIGIN` (`chat:<id>` for agent runs,
`terminal:<id>` for its terminals, which project actions run in) into the
environment of everything it launches, and a listener is attributed to its
nearest marked process — through `nohup`, daemonizing, a scrubbing launcher
(`env -i`) and an engine restart alike. A marker naming a chat this device
does not host owns nothing. The deepest matching checkout wins; a server
started outside it lists under the chat that started it. Commands with
explicit authentication arguments (`login`, `auth`,
`authenticate`, `signin`, `sign-in`, `sso`, `oauth`, or `oauth2`, before `--`)
are excluded before any connection, even when started by a Roboco terminal or
agent. Their listeners may be one-shot browser callbacks: for example,
`infisical login` fails with EOF if it receives a discovery HEAD without the
browser's JSON body. This command check does not identify custom callback
servers without an authentication argument. Agent CLIs' own control servers
(`opencode serve`) are excluded by a harness-side port reservation.

Unrelated listeners and non-HTTP services are excluded. Discovery runs every
two seconds; bounded HEAD probes accept valid HTTP status responses (including
authentication and application errors). Confirmed HTTP listeners are not
probed again during that socket's lifetime; non-HTTP results use a capped
exponential backoff. Each process's marker is read once per process identity
(pid + start time), and a scan that cannot read the process table keeps the
previous list instead of clearing it (macOS `lsof`/`ps` calls time out after
10 seconds).

Each listed service carries the chat whose agent or terminal started it.
Framework commands identify Vite, Next.js, Astro, Miniflare and Node servers;
otherwise the list uses a generic HTTP label. Before a local backend
connection, the daemon rechecks the listener's process identity to reject
stale port reuse.

The profile's `previews.json` stores project and service IDs and hostname labels
separately from observed PIDs and ports. Command identities omit common port
options. Device-name collisions get persisted suffixes. The first service keeps
the project hostname; additional services get descriptive suffixes. Two
indistinguishable instances of the same command in the same cwd get separate
slots; their individual identities cannot be inferred across simultaneous
restarts without additional application-provided identity.

`WatchPreviews` resolves the session's current cwd on each catalog or workspace
change, and lists the chat's own services: those started in its checkout, plus
any this chat's agent or terminals started elsewhere. Only Roboco-started
services list — a server the user started themselves in the same project never
appears. Changing the active checkout therefore changes the list without
copying paths or entering ports.

## HTTP and stream transport

The proxy's HTTP CONNECT endpoint admits only known preview names at the proxy
port, and its tunnels share bounded limits and account cancellation.

The loopback proxy validates the Host against its catalog, then opens an opaque
service ID through a transport-independent multiplexer. Locally, framed streams
travel over a socket pair. Remotely, the identical frames travel over the
session relay's WebSocket (see "Session relay" below); the dormant DataChannel
path stays in tree as the direct-data-plane upgrade. Remote metadata never
authorizes an arbitrary TCP address: the hosting device resolves only its own
live service IDs.

Frames have a one-byte kind, a big-endian 32-bit stream ID, and a bounded payload.
Kinds are OPEN, DATA, END, CANCEL, WS_OPEN, WS_DATA, WS_CLOSE, READY and CREDIT.
Socket transport prefixes frames with a big-endian 32-bit length; DataChannel
messages already provide framing. Peers allocate opposite stream-ID parity.
DATA payloads are at most 8 KiB. Each stream has a 64 KiB receive window; connection
queues and the SCTP send buffer are bounded. Up to 64 streams share a connection.
END half-closes; CANCEL tears down both directions. Graceful shutdown drains the
last buffered bytes, whereas dropping an unfinished request cancels promptly.

Hyper streams request and response bodies. The proxy removes hop-by-hop headers,
preserves Host and Origin together (including Next.js Server Actions), sets
X-Forwarded-Host, and rewrites absolute localhost redirects. Set-Cookie and other
end-to-end headers remain intact. WebSocket upgrades retain their byte stream,
subprotocol and close handshake, allowing Vite HMR through the same URL.

## Session relay

A remote device opens a preview over the client's own engine session — no cloud,
no signaling, no second credential. The engine serves
`GET /preview-relay/{chatId}/{serviceId}` as a WebSocket upgrade on its existing
listener: an opaque binary pump in the `serve_relay_websocket` shape. Both ids ride the path, never a query string —
the listener rejects query parameters on every route, so neither scope nor
credentials can end up in a URL. There is no new RPC method, no RPC envelope
change and no base64: each WebSocket binary message is exactly one mux frame,
bounded by the mux's own frame-size check.

Authentication is the existing session gate, exactly as for the RPC socket: a
`Bearer` session credential on the upgrade, or a 401 before the upgrade. There
is no browser path: the paired bind refuses any request carrying an `Origin`
(403), as the local (native IPC) bind always has; the local bind stays
credential-free, and the paired bind requires the credential. Revocation is inherited from the same machinery: a revoked
session cannot dial again, and listener teardown — what disabling remote
access and engine shutdown run — cuts open relay sockets with every other
connection.

The upgrade hands the socket to `Mux::start(transport, connector, initiator:
false)` with a small WebSocket-backed mux transport. The connector is the
preview service's `LocalConnector` scoped to the request: an incoming OPEN
resolves only the path's service id, only while the live catalog lists it, and
only while that catalog entry is attributed to the path's chat — checked per
OPEN, so a service that dies or moves to another chat stops resolving on an
already-open relay. Process identity is re-verified before every dial. The
invariant the whole chain keeps: **remote metadata never authorizes an
arbitrary TCP address** — the client names a service id, and only the engine's
own live catalog turns that into an address.

## Client-side preview proxy

A client opening another engine's previews binds `127.0.0.1:7332` —
unclaimed (the engine owns 7331) — and serves that
engine's preview hostnames by dialing its relay route over the session
transport the client already holds: the registry's saved endpoint and
credential (a LAN WebSocket, or a Tailcat tunnel's loopback base; tunneled
engines work with no new network setup). The proxy is one GPUI-free Rust
module in `roboco-preview-relay`, linked by `roboco-engine-client` so the
desktop and the Android mobile core share it; it never links the engine,
GPUI, or WebRTC.

Routing is by Host header against the hostnames of the `WatchPreviews`
snapshots the client already receives — the dormant `catalog.set_remote`
ingestion semantics applied on the client (validation, bounded counts,
distinct labels when two engines' device names collide, so hostnames stay
unique and URLs stay stable). A hostname maps to exactly one (engine, chat,
service); a stale one answers 502 with the "not running" shape. A service
without chat attribution is skipped: the relay scopes every dial to the chat
that started the server, so it could not be relayed at all.

Each accepted HTTP connection opens a mux stream on a per-(engine, chat,
service) relay — the client is always the initiator and never answers OPENs
— and relays HTTP with the same header hygiene the engine's 7331 proxy
applies (one implementation, in `roboco-preview-relay`'s `http` module):
hop-by-hop stripping, Host/Origin preserved, `X-Forwarded-Host`, localhost
redirect rewrites, bounded upgrade tunnels, and WebSocket passthrough for
HMR. The system browser resolves `*.localhost` to loopback itself and sends
origin-form requests that route by Host.

The lifecycle is lazy: 7332 binds only while a watcher holds some engine,
and it shuts down once the idle window (30 s) passes after the last watcher
leaves. On the desktop the shell owns the watchers. Clicking a paired
engine's server in the ports card starts an *opened-preview watcher* for
that (engine, chat). It marks the engine watched, keeps a
`WatchPreviews {chatId}` subscription that ingests every snapshot into the
proxy (so dev-server restarts and collision labels keep routing), and ends,
unwatching on every exit path, when that chat's servers have been gone for
the idle window, the engine leaves the registry, or the app quits. So the
browser tab keeps loading after the card closes or the chat changes, and
stops within about a minute of the dev server stopping. A revoked session
behaves the same way once its connection drops: nothing can be seen, so the
idle window runs out. Local chats need no watcher, because 7331 is the
engine's own always-on proxy. On Android, the Ports panel holds the watcher
while it is open.

A busy port surfaces an inline error — mirroring the 7331 failure mode — and
never changes the URL: the rendered port is 7332 either way. Revocation is
inherited from the session the relay rides: a revoked credential cannot dial
again, and listener teardown or an engine going offline cuts open relays.

Android's WebView resolves `*.localhost` natively (Chromium), so it loads
`http://<hostname>:7332` against the in-app proxy directly.

## Presence and P2P

This section describes the dormant peer stack kept in tree (`peer.rs`, `mux.rs`;
no coordinator is deployed — Roboco removed the edge and its signaling, so
`Peers::signal()` has no production caller). It is the upgrade path if relayed
previews ever need a direct data plane.

The Worker authenticates the existing access token, verifies the organization
claim, and derives a PreviewRoom name from the verified organization and user.
Clients cannot select another user's room. The coordinator stamps device sender
identity and accepts only bounded service catalogs and pairing SDP. Gathered ICE
candidates are included in SDP, along with the DTLS fingerprint. Binary frames
and arbitrary proxy protocol messages are rejected.

Presence has a heartbeat lease, hibernation-safe catalog storage and periodic
credential refresh. Disconnects clear advertised routes and peer connections.
Only the lexicographically lower device creates offers, avoiding simultaneous
offer collisions. DataChannels are opened lazily when a preview is requested.
DTLS authenticates the fingerprint exchanged through the authenticated signaling
connection. Application requests, response bodies and WebSocket messages never
pass through the Durable Object.

The initial transport uses host candidates and public STUN, without a TURN
service or a cloud byte-relay fallback. Networks that prohibit direct ICE
connectivity return a bounded connection error. Deploy the Worker with its v4
PreviewRoom migration to enable cross-device coordination; local previews work
independently of edge availability. macOS and Linux currently provide discovery.

## Validation

`cargo test --locked -p roboco-preview` covers real process/cwd isolation,
origin attribution (including backgrounded, env-scrubbed and out-of-tree
servers), non-HTTP exclusion, live disappearance, persistent aliases, port
changes, concurrent streams, slow readers, cancellation, large bodies,
streaming HTTP headers and redirects, WebSocket traffic, and the mux frame
path in both directions.

`cargo test --locked -p roboco-engine --test preview_relay` covers the relay
route against the real listener: the session gate (Bearer, the missing-Bearer
401, the `Origin` 403), real HTTP round-trips through the mux including large
bodies, slow readers, half-close and cancellation, chat/service scoping
refusals, and listener teardown cutting an open relay.

`cargo test --locked -p roboco-preview-relay` covers the client proxy against
a test relay speaking the engine's exact wire shape: hostname routing from a
snapshot (collisions, validation, removal), the origin/absolute/CONNECT entry
shapes, large bodies, the localhost redirect rewrite, the stale-hostname 502,
the bind-conflict inline error, the lazy/idle listener lifecycle, and the
refused-credential degradation.

`cargo test --locked -p roboco-engine-client` covers the manager against a
real paired engine: `WatchPreviews`-driven ingestion, a full HTTP round-trip
through the registry's dial (`/preview-relay` over the saved endpoint and
credential), revocation cutting the open relay, unpairing dropping routes,
and the busy-port inline error. The desktop's ports card is covered in
`roboco-ui` (`shell/session_info/opened_preview_tests.rs`) with a real paired
engine. A row opens the routed 7332 URL externally and adds no tab. That URL
serves the remote dev server through the proxy after the card closes and the
chat changes. The watcher ends, and the listener idles out, when the servers
stop, the session is revoked or the engine is forgotten. A local row opens
its 7331 URL with no watcher.
