# Rooms are hostless reachability groups

A room is a named group of engines a client joins once to be introduced to every
member. Rooms share **reachability, never data**: joining mints ordinary
per-engine sessions, and ADR 0004's engine-local rule is untouched — a room
never syncs, relays, or owns a chat.

The room is deliberately hostless. It is a shared secret plus an address book,
copied to every member, with no engine in charge; any online member can welcome
a client that presents the secret. Engines persist only a SHA-256 verifier of
the secret (the pairing invariant; no usable room secret is ever at rest on an
engine), while the secret itself lives with clients — Keystore on Android,
browser storage on the web, app config on the desktop. The book's member
addresses are 0600-class secrets exactly like relay `connect.json` entries.
Native clients dial members directly through their own Tailcat tunnels;
browsers, which cannot dial, redeem through the engine serving them: that
engine relays the presentation over its own tunnel and then byte-pumps frames
through the existing relay, never storing the secret. Books merge as unions,
maintained by clients; missing members are retried on reconnect, and a
presentation is repeatable, so interrupted joins self-heal.

V1 is deliberately small. A member must be online to welcome a joiner (pair
codes die within an hour by design, so nothing can be stashed anyway); there is
no delegated minting, no live liveness probing, no cross-engine revocation, and
no room-level chat sharing. Eviction is two steps — rotate the secret through a
guided re-invite that lists who still holds the old one, then revoke sessions
per engine — because a room gates future introductions, not existing sessions.
v1 UI keeps rooms in Settings; joined engines simply appear in the existing
engine lists. The glossary gains a Room entry with an explicit footnote
distinguishing it from zeron's removed sync rooms (replicated Loro documents —
the opposite architecture) and from the frozen legacy wire name `room_gen`.

## Considered Options

- **Host engine owns the member list** (bouncer/gateway precedent, e.g. ZNC/soju):
  rejected — a single point of failure; the host must act as a client of every
  member, a new engine-as-client dependency direction; and offline introduction
  would require storing usable member credentials on the host, breaking the
  verifier-only invariant.
- **On-behalf-of forwarding** (upstream's `targetDeviceId` cloud-relay shape):
  rejected — attribution and per-client revocation are lost, or pairing grows
  RFC 8693-style delegated credential semantics for no ADR-0004 advantage.
- **Purpose-built delegated introduction tokens** (short-lived, room-scoped, so
  offline members could be introduced without raw session credentials):
  deferred — a possible later addition if offline welcoming is ever needed.
- **Tailscale PeerAPI-style direct file channel** for drops: rejected — the
  vendored adapter deliberately exposes one fixed application port to one
  loopback target; attachments ride the existing upload path with a raised,
  capability-gated cap instead.

## Consequences

- The room surface is additive and capability-gated: engines advertise
  `rooms-v1`; old engines answer `unknown method`, and clients hide room UI on
  the capability's absence instead of showing error toasts.
- Engine room state is a new store following the `pairing.sqlite3` precedent
  (IF-NOT-EXISTS tables, no migration machinery); the RPC additions are
  `POST /room/present` (redeem-style, pre-session), plus session-authenticated
  `GetRoomBook`/`UpdateRoomBook`.
- Android's `PairedEngine` grows a room field — a UniFFI surface change shipped
  in a coordinated core+app release (same APK).
- Phone cost stays linear in members (2 sockets + probes per engine); the
  fleet-snapshot rebuild is quadratic in aggregate as engines grow — rooms v1
  targets personal fleets (2-5 members); cadence/cap work stays separate.
- Attachments: the per-file cap rises 24 MB → 100 MB client-side and 32 MB →
  100 MB engine-side, gated by a new upload capability string.
- Sessions minted via a room are ordinary sessions — listed, labeled, and
  revocable per engine exactly like paired ones; `GET_REMOTE_ACCESS` already
  enumerates them.
- Design research with sources: `.scratch/research/2026-10-25-multi-engine-chats-and-taildrop.md`
  and `.scratch/research/2026-10-25-room-model-catches.md`.
