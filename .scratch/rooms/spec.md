# Rooms: join once, reach every engine

Status: ready-for-agent
Spec date: 2026-10-25

## Problem Statement

My desktop app pairs with my PC's engine and my headless Linux server, so the
desktop sees both fleets in one sidebar. But every other client has to repeat
the per-engine pairing dance: on my phone I must separately pair with the
desktop engine and the server engine, pasting an invite at each. The user's
words: "when i connect to the desktop app, the linux server doesnt come with
it — i have to connect seperately." The same friction blocks the
"taildrop thingy": dropping a file from the phone into a chat that lives on
another server requires that server to be separately paired first.

There is no account, no cloud, and no cross-engine sync (ADR 0004), so the fix
must be a pairing-level convenience, not a data-level feature.

## Solution

A **room** is a named group of engines that a client joins once and is then
introduced to every member. Rooms are hostless: the room is a shared secret
plus an address book, copied to every member, with no engine in charge. Any
online member can welcome a joiner that presents the secret; engines store only
a SHA-256 verifier of it, so no usable room secret is ever at rest on an engine.
Native clients (desktop, Android) dial each member directly through their own
Tailcat tunnels; the web client redeems through the engine serving it, which
relays the presentation over its own tunnel and byte-pumps frames through the
existing relay.

Joining mints ordinary per-engine sessions — full client rights on every member
(chats, MCP, attachments, terminals). Rooms share reachability, never data
(ADR 0004): each chat still belongs to exactly one engine, and the room never
syncs, relays, or owns anything. Sleeping members show offline and are picked
up automatically when the phone next reconnects, because presentations are
repeatable.

Attachments already ride the connection to whichever engine owns the chat, so
"taildrop" reduces to reachability plus a bigger cap: the per-file attachment
cap rises 24 MB → 100 MB (client) and 32 MB → 100 MB (engine), gated by a
capability so old clients and old engines keep working together.

## User Stories

1. As a desktop user, I want to create a room in Settings and invite my other
   engines into it, so grouping my fleet is one action.
2. As a server operator, I want to join a room from the CLI
   (`roboco engine room join <invite>`) or Settings, so a headless engine
   becomes a member without a GUI.
3. As a phone user, I want to scan the room invite (QR) once and then see every
   member engine in my existing engines list, so I never repeat the per-engine
   pairing dance.
4. As a phone user, I want full client rights on every member — create chats,
   drive runs, use MCP — so a room member is indistinguishable from an engine I
   paired manually.
5. As a phone user, I want to create a chat on any member engine (the device
   picker the desktop already has), so "spawn chats on different servers" works
   from the phone.
6. As any client, I want to attach files into chats on any member engine, so
   the taildrop flow is just "pick a chat, attach, send".
7. As a web user, I want to paste the room invite once and reach every member
   through the engine serving the page, so the browser joins rooms too.
8. As a phone user, I want sleeping members to show offline while awake ones
   keep working, so a powered-off desktop is a status, not an outage.
9. As a phone user, I want a member that wakes to be joined automatically on my
   next reconnect (app open or network change), so half-joined rooms heal
   without me.
10. As a user who lost a phone, I want a guided "rotate room secret" flow that
    lists who still holds the old secret, so eviction is a checklist, not
    archaeology.
11. As a user, I want the rotation plus per-engine session revokes to fully cut
    off a leaked room secret, so a kicked member cannot rejoin or keep their
    existing logins.
12. As a user with an old engine in the fleet, I want room UI hidden (not an
    error toast) when the engine lacks `rooms-v1`, so version skew degrades
    gracefully.
13. As a user, I want engines to store only fingerprints of the room secret and
    treat invites as secrets (0600, never logged, shape-only errors), so room
    machinery inherits ADR 0008's secrecy rules.
14. As a user, I want address books to merge (union) from every reachable
    member, so a stale address on one member self-corrects from another.
15. As an MCP user, I want the MCP server to hold room memberships and address
    any member engine, so agent tooling spans the fleet like the desktop does.
16. As a user, I want rooms in Settings only in v1 — joined engines simply
    appear in my existing lists — so no new sidebar grouping layer ships before
    it's wanted.
17. As a user, I want to drop files up to 100 MB into chats on any member, so
    "drop a file" covers videos and archives, not just screenshots.
18. As a user, I want a room to never share chats between engines, so the
    engine-local guarantee I trust today is unchanged (ADR 0004).

## Implementation Decisions

- **Model (ADR 0011).** A room = `{id, label, secret, book}` where the book is
  `{member label, member address}` entries. The **invite** is the room
  serialized — grammar `roboco-room:<base64url(json{v,name,secret,members[]})>`
  — and is secret-bearing: written 0600 where files are involved, never logged,
  errors describe only its shape (ADR 0008 rules). QR codes render the invite
  string.
- **Engines hold verifiers only.** Joining an engine stores `{id, label,
  SHA-256 verifier, book}`; the secret itself is discarded after hashing.
  Presentations (`POST /room/present {secret, label}` on the member's listener,
  redeem-style and pre-session, over Tailcat or the relayed path) are verified
  against the verifier and mint an ordinary session labeled by the presenter.
  Presentations are **repeatable** (unlike pair codes) so retries and
  self-healing are free; failed presentations are throttled and logged
  shape-only.
- **Book maintenance is client-driven.** `GetRoomBook` and `UpdateRoomBook`
  are session-authenticated RPCs; `GetRoomBook` additionally requires a secret
  presentation because member addresses are transport secrets. Clients union
  the books of every member they can reach, dial-verify addresses, and push the
  merged book back. Engines never gossip with each other.
- **Platform paths.** Desktop and Android dial members directly via their
  existing per-engine Tailcat machinery (the desktop app holds the secret in
  its client config; Android stores it Keystore-encrypted beside the existing
  `cred:`/`tc:` blobs). The web client redeems per member through the
  origin engine: `POST /pairing/room-redeem {invite, target, label}` dials the
  target from the origin engine's book, relays the presentation over the
  tunnel, returns the target's session grant plus a `/tailcat-relay/{id}` base,
  and the existing byte pump carries traffic — the origin engine never stores
  the secret.
- **v1 is small (ADR 0011).** Online-member-only welcoming; no delegated
  minting; no liveness probing (offline = per-client dial state, as today); no
  cross-engine revocation; no room-level chat sharing or cross-engine file
  references; no inbox/no-chat drops. Deferred options are recorded in the
  ADR's Considered Options.
- **Eviction.** Rotation mints a new secret and re-invite; the flow tracks
  which members have re-presented. Existing sessions are ordinary sessions:
  the phone already has best-effort `revoke_own_session`; the kick checklist
  surfaces per-engine revokes.
- **Capability gating.** Engines advertise `rooms-v1` (UI hiding on old
  engines) and the upload-cap capability (working name `uploads-100mb-v1`);
  new methods otherwise need no gate per the catalogue, and the clients hide
  room UI on the capability's absence. Wire names introduced here are new and
  frozen at ship (ADR 0005): `roboco-room:`, `/room/present`,
  `/pairing/room-redeem`, `GetRoomBook`, `UpdateRoomBook`, room store tables.
- **Multi-room.** No hard limit: an engine may hold verifiers for several
  rooms; a client may join several. v1 UI lists rooms flat in Settings.
- **Storage.** Room state follows the `pairing.sqlite3` precedent
  (IF-NOT-EXISTS tables in the same data domain — who is allowed in); member
  addresses are persisted with the same 0600 discipline as relay
  `connect.json` entries.
- **Attachments.** No new file path: uploads ride the existing
  `UploadChunk`/`UploadCommit` pipeline to whichever engine owns the chat.
  Raise `MAX_ATTACHMENT_BYTES` 24 MB → 100 MB (desktop ui, mobile-client, web)
  and engine `MAX_BYTES` 32 MB → 100 MB behind the upload capability; the
  whole-file deadline becomes size-proportional so slow links still fit.
  100 MB ≈ 200 chunks of ~510 KB — well inside the 1 MiB relay frame budget.
- **Android.** `PairedEngine` grows a room field (UniFFI surface; coordinated
  core+app release, same APK); the room secret is a new Keystore blob class
  (`room:`); joins reuse the existing per-engine `TailcatRoute` + link
  supervision; retry of missing members rides the existing reconnect
  machinery (`Verify` on network change, supervisors on app start).
- **Performance envelope.** Personal fleets (2-5 members). Costs are linear
  per member (sockets, probes, tunnels); the known quadratic
  fleet-snapshot seam and relay-dir eviction hygiene are recorded as
  out-of-scope follow-ups, not blockers.

## Out of Scope

- Offline-member introduction (delegated minting / introduction tokens)
- Cross-engine revocation (one-click kicks everywhere) and live liveness probing
- Room-level chat sharing, cross-engine chat references, or any data crossing engines
- A no-chat drop inbox (user declined; chat attachments are the taildrop)
- A direct peer-to-peer file channel (Taildrop PeerAPI-style)
- Relay-directory eviction/TTL hygiene and the fleet-snapshot quadratic fix
- Sidebar grouping/room switcher UI (Settings-level presence only)
- Member caps, probe-cadence changes, multi-room sidebar UI

## References

- ADR 0011 (`docs/adr/0011-rooms.md`) — the decision record
- ADR 0004 (engine-local data), 0005 (glossary/wire names), 0008 (tailcat,
  invite secrecy), 0009/0010 (Android client, background service)
- `docs/reference/remote-access.md` — pairing, relay, capability gate catalogue
- `.scratch/research/2026-10-25-multi-engine-chats-and-taildrop.md` — models,
  Tailscale/precedent analysis, upload-path findings
- `.scratch/research/2026-10-25-room-model-catches.md` — catches, costs, the
  host-model rejection evidence
- Upstream context: zeronsh/zeron #730 (same user ask, answered upstream by
  the cloud relay we removed)
