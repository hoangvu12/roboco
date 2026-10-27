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

## Amendment (2026-10-27): fleet rooms

Rooms v0.13.0 worked as designed and still confused its only user: creating a
room did not put anyone in it, each member engine had to be joined by hand,
and the status vocabulary exposed three membership axes nobody asked about.
The user's model is simpler — "my desktop knows my fleet; pair a phone
anywhere and the fleet comes along." Fleet follow keeps the room machinery as
a protocol and drops the room concept from the UI.

**What changes.**

- A **fleet room** is a room of a new kind (`"kind":"fleet"` in the invite;
  absent means a plain room, so plain invites stay byte-identical and old
  invites decode as plain rooms). A data dir holds at most one fleet room.
- **Member engines retain the fleet secret at rest**, in a `fleet_rooms`
  row of the pairing database (`pairing.sqlite3`, owner-only on unix, WAL
  sidecars included). This amends "no usable room secret is ever at rest on
  an engine" for fleet rooms only. Justification: engines already mint and
  hold pairing credentials; a fleet secret is a group pairing token in the
  same data domain, protected the same way. Leaving the fleet erases the row
  (secure delete). Plain rooms keep the verifier-only invariant unchanged.
- **Introduction becomes symmetric**: after pairing to *any* member engine,
  a client calls the session-authenticated `GetFleetInvite` and silently runs
  the ordinary room join, so every member appears as an ordinary paired
  engine. There is no composite QR — the pairing link is unchanged.
- **Membership is hub-driven** from the desktop app: it pushes the fleet to
  each paired engine with the session-authenticated `RoomJoin` (idempotent;
  the book unions; the reply is the room id only) and maintains books
  through its existing silent heal. All engines are members by default, with
  a per-engine opt-out.
- The capability `rooms-v2` gates both RPCs; clients degrade silently on its
  absence. Neither RPC ever mutates remote-access settings.

**What is explicitly unchanged.** Rooms share reachability only, never data
(ADR 0004). Clients dial members directly and present the secret at
`POST /room/present`, which mints ordinary, individually revocable sessions.
Books merge as unions maintained by clients; there is no liveness probing.
Eviction is still rotate-then-revoke (now: `roboco engine room leave` on each
engine, re-create, re-pair; then revoke stale sessions per engine). Plain rooms
keep the full hostless semantics and the CLI/manual path. Secrets and invites
never appear in logs, `Debug` output, or error text (ADR 0008); stdout carries
an invite only where a human asked for it (`roboco engine room invite`).

**Considered and rejected for this amendment.** A composite pairing QR that
embeds the fleet (QR density, scanner changes, a second grammar); a
desktop-only introducer (the phone could not join through a headless server);
per-invite member scoping (deferred).

**Status note.** The trunk's wave-9 merge dropped the v0.13.0 rooms code and
this ADR; fleet follow re-landed the rooms core with the amendment. The
browser relay flow (`/pairing/room-redeem`) and the 100 MB attachment cap
described under Consequences were dropped by the same merge and are **not**
re-landed by fleet follow.
