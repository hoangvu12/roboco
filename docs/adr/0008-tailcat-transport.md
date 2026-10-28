# Tailcat is the account-free transport

Roboco's remote listener is plain HTTP/WebSocket, so reaching it from outside a
LAN needs a transport the operator owns. Cloudflare Tunnel stays supported: it
terminates TLS, satisfies a browser's HTTPS expectations, and needs no software
on the client. Tailcat (`tailscale/tailcat`, BSD-3) is the second transport:
WireGuard over NAT traversal with DERP bootstrap and fallback, no account and no
control plane, driven by one app-owned adapter (`roboco-tailcat`) that the engine
supervises for `serve` and a client supervises for `connect`. It carries bytes
only — pairing codes, sessions, and revocation stay Roboco's, exactly as over a
tunnel (ADR 0004, ADR 0006), and the engine's listener stays loopback-bound in
both cases. The transport is a saved setting (`transport`) plus a headless flag
and environment equivalent, and the two never mix: a conflict keeps remote access
local and logs why.
The explicit `roboco engine tailcat invite` setup command is different from a
startup override: it saves Tailcat settings through the owning engine and ensures
a live route before minting. It starts a background engine when necessary.

## Consequences

- A Tailcat handle is an invite (`roboco-tailcat:…`) carrying the route and a
  short-lived, single-use pair code, because the client's loopback port only
  exists on the client. Invites, and the addresses inside them, are secrets: the
  address can embed a pre-shared key, so it is written 0600, never logged, and
  errors describe only its shape.
- `roboco engine tailcat connect` runs a loopback forwarder and prints
  `http://127.0.0.1:<port>/pair#token=…`, a loopback pairing link for a Roboco
  app. (It used to serve the browser client there; see the amendment below.)
- The adapter is app-owned and vendored: `adapters/roboco-tailcat` builds the
  `roboco-tailcat` binary against upstream `tailscale/tailcat` (pinned at
  `fd101889796a947ac514e9d86ec731af2965fad3`). Linux tarballs, Windows ZIPs, and
  the Windows installer bundle it beside the Roboco binary; redistribution
  notices live under `adapters/roboco-tailcat/licenses/` and in
  THIRD_PARTY_NOTICES.md.
- DERP defaults to the adapter's own map; `--tailcat-derp-map` (or
  `ROBOCO_TAILCAT_DERP_MAP`) points a fleet at a relay it owns.
- A route that cannot come up disables remote access instead of leaving an
  unpublishable listener behind.
- Clients: the desktop and **Android**. The phone redeems an invite through its own in-process
  Tailcat tunnel (`libroboco_tailcat.so`, ADR 0009), stores the address and
  its own client identity Keystore-encrypted (never in plain metadata), and
  rebuilds the route on every reconnect: after app restarts, failed dials
  and network changes.

## Amendment (2026-10-28): no browser clients

[ADR 0012](0012-no-web-client-no-embedded-browser.md) removed the web client.
Browsers no longer pair over Tailcat: the engine-side browser relay
(`/pairing/tailcat-redeem`, `/tailcat-relay/*`) and
`roboco engine tailcat browser-helper` are gone. `tailcat connect` stays as a
loopback pairing link for an app. Cloudflare Tunnel stays supported, but it no
longer means "no client software": the desktop and Android apps pair to its
`https://` URL like any other.
