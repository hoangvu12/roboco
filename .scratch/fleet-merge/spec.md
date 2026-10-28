# Fleet merge: one fleet for all my machines, and QR codes that scan

Status: **superseded** by `.scratch/fleet-redesign/` (2026-10-28 design discussion). Its QR fixes carry into fleet-redesign 05/07. Do not implement.
Spec date: 2026-10-28
Diagnosis: orchestrator side chat `bde0242e` (2026-10-28), summarised under Problem Statement. Code refs are against `main` at `1ccaa3a8b`/`6126c05d6` (v0.14.0).

## Problem Statement

The user has four machines: PC (desktop app), Laptop (desktop app), Linux SG and Linux US (headless engines). After v0.14.0 they ended up in **three** fleets, and the app gave them no way out:

- **Every desktop mints its own fleet.** The desktop mints a fleet as soon as any paired engine looks reachable, before anything is pushed (`crates/ui/src/rooms.rs:575-584`). The PC minted one at 08:06 that holds only itself; the Laptop holds another; SG and US share a third (origin unknown, probably a CLI or earlier desktop mint).
- **An engine never gives up a fleet.** `RoomStore::join` refuses any different fleet with `FleetConflict` (`crates/engine/src/rooms.rs:206-228`), even a fleet with no other members. The RPC returns the refusal as fixed text (`crates/engine/src/rpc.rs:1537-1538`).
- **The desktop shows it as a dead end.** The refusal maps to `FleetState::OtherFleet`, "In a different fleet" (`crates/ui/src/rooms.rs:609-619`, `:227`). Only "Not in yet" rows get Retry (`:231-233`, `settings/remote_access/fleet.rs:311-319`). The only path that replaces a fleet is pasting an invite on the desktop (`rooms.rs:677-695`).
- **Phones miss machines.** `GetFleetInvite` returns the engine's own fleet only (`rpc.rs:1552-1570`), and the phone fetches it only after pairing (`crates/mobile-client/src/rooms.rs:786-822`). A machine in another fleet never reaches the phone. When fleets change later, the phone never notices.
- **The QR codes don't scan.** The desktop tiles assume a short LAN URL (`crates/engine/src/remote_access.rs:15-20`), but Tailcat pairing links and fleet invites are QR version 17–34 (93–161 modules incl. quiet zone). That leaves 1.5–2.7 px per module on a 176/248 px tile. GPUI rasterises the SVG at 2× regardless of display scale and stretches it with linear filtering, so module edges smear. The fleet invite grows ≈ 380 characters per member. CLI `--qr` output is wider than common terminals and dark-on-glyph, which reads inverted on a dark terminal; the Android decoder skips inverted codes (`QrDecoder.kt:21-22`).

## Solution

**One fleet per user, and pairing merges.** All of a user's machines form one fleet. Whenever a desktop meets an engine that holds a different fleet, the two fleets become one, with no prompt. The phone follows merges on reconnect. The QR codes get sized and drawn so they scan.

1. **Engines can be moved.** `RoomJoin` gains `replace: bool`. With it, an engine holding a different fleet leaves it (secure delete of the retained secret, as `room leave` does) and joins the pushed one. This is gated on a new capability, `rooms-v3`. Older engines keep refusing and show "Update that engine to bring it in".
2. **Desktops join before they mint.** Before minting, the desktop asks each paired, connected engine for its fleet (`GetFleetInvite`). If any engine has one, the desktop adopts the largest instead of minting. It mints only when no reachable engine holds a fleet. It never keeps a fleet that holds only itself while a reachable engine has another.
3. **Merging on meet.** When the desktop's fleet F and an engine's fleet G differ, the bigger book wins; on a tie, the desktop's own fleet wins. The desktop:
   - joins the loser's members through the loser's invite (dedupe already links engines it has paired);
   - pushes the winner with `replace: true` to every reachable member of both fleets, including its own local engine;
   - unions the books.

   Members that are offline stay in a persisted *pending move* list and are retried on every sync until they move. Then the loser's secret is erased from the desktop.
4. **Phones follow.** On every reconnect to a paired engine, the phone re-fetches its fleet. If the fleet id changed, the phone adopts the new fleet: it joins new members and keeps every existing paired engine. Phones never merge; desktops are the only hubs.
5. **QR codes that scan.**
   - Desktop tiles are sized by module count: a whole number of logical px per module, at least 4. Modules are painted as crisp quads, not a filtered SVG.
   - An **Enlarge** view shows the code full-window.
   - Invites use error-correction level L.
   - The **fleet-invite QR is removed**; Copy stays. Phones scan the pairing QR and the fleet follows.
   - CLI `--qr` prints dark modules on a light background (explicit colours), and prints a one-line hint when the code is wider than the terminal.
6. **Docs.** An amendment to ADR 0011 records merge-on-meet, `replace`, `rooms-v3`, phone re-fetch and the QR change. CONTEXT.md, the fleet-follow ticket 03 decisions it supersedes, and the user docs are updated.

What does **not** change:
- Rooms share reachability only, never data (ADR 0004).
- Plain (non-fleet) rooms keep their semantics, the verifier-only invariant and the CLI path.
- Sessions stay individually revocable.
- Secrets and invites never appear in logs, `Debug` or error text (ADR 0008).
- The per-engine opt-out stays forward-only: an opted-out engine is never pushed and never moved. `roboco engine room leave` stays the way to take a machine out.
- The paste-an-invite flow stays as a fallback.

## User Stories

1. As the user, I pair my PC with my Laptop. Within one sync, both show the same fleet containing PC, Laptop, SG and US, and no row says "In a different fleet".
2. As the user, I pair my phone with any one of my machines. It gets every machine in my fleet.
3. As the user, after two of my fleets merge, my already-paired phone picks up the newly added machines the next time it reconnects, without re-pairing.
4. As the user, a machine that is offline during a merge joins the fleet when it comes back, after a Retry or the next sync.
5. As the user, I can scan the desktop's pairing QR with my phone from a normal distance on a laptop screen. If it's still hard, I press Enlarge.
6. As the user, a `--qr` printed in a dark terminal scans, and a too-narrow terminal tells me to widen it.
7. As the user, I retire a machine with the opt-out switch plus `roboco engine room leave` on it, and merges never pull it back.

## Implementation Decisions

### Settled by the user (2026-10-28)
- **Merge, don't pick.** All of a user's machines are one fleet. Pairing two machines merges their fleets automatically, **with no confirm prompt**: a paired session can already run agents on the engine, so moving it into the fleet grants nothing new.
- **Which fleet survives:** the bigger book; on a tie, the desktop doing the merge. This is invisible to the user.
- **Desktops may ask paired engines for their fleet** (`GetFleetInvite`) and **may move an engine between fleets** (`RoomJoin` with `replace`). This changes ADR 0011's amendment ("desktops never fetch", "there is no remote leave"). Approved; ticket 06 writes the amendment.
- **The fleet-invite QR goes; Copy stays.** This supersedes fleet-follow ticket 03's "Show fleet invite (QR + copy)".
- Accepted consequence: pairing someone else's computer to one of yours makes it part of your fleet. This is already true for phones today, so pairing stays an own-machines action. The docs say so plainly.

### Decided in this spec (overrule in a ticket comment before building, never silently)
- `replace` is honoured only for **fleet** rooms. Plain rooms ignore it. The engine erases the old fleet's secret before writing the new row, in one transaction.
- `rooms-v3` = `rooms-v2` + `RoomJoin.replace` + `GetFleetInvite` including the book member count. A rooms-v2-only engine is never merged; its row reads "Update that engine to bring it in".
- **Pending moves** persist in `fleet-v1.json` (the existing atomic, 0600 file) with the loser's invite, tagged so it never logs. They are retried on every sync, and the loser secret is dropped once every reachable loser member has moved, or after 30 days with a one-line caption on the card.
- The desktop's Fleet card states shrink to In / Not in yet (offline, Tailcat off, unconfirmed, with Retry) / Update that engine / Left out. `OtherFleet` survives only as a transient internal state during a merge.
- Phone re-fetch runs in the existing heal path on reconnect (`heal_rooms`/`fetch_fleet`). It is rate-limited to once per engine per connection.
- QR tiles are painted as GPUI quads, which avoids the SVG raster/filter blur without touching the zui fork. The QR crate stays `qrcode 0.14`.

## Testing Decisions

- **Engine** (`crates/engine/tests/rooms.rs`, store unit tests):
  - `replace` moves a fleet engine and erases the old secret (no row and no bytes in a fresh read);
  - `replace` on a plain room is a no-op;
  - a rooms-v2 request without `replace` keeps today's refusal;
  - `GetFleetInvite` carries the member count.
- **Desktop** (`crates/ui/src/rooms/tests.rs`, fake dialer/probe):
  - join-before-mint (an engine with a fleet → adopted, no mint);
  - merge on meet, bigger wins and tie → the desktop's own;
  - a three-fleet scenario matching the user's (PC solo, Laptop solo, SG+US) converges to one fleet of 4;
  - an offline loser member moves on a later sync (pending move persisted across a restart);
  - an opted-out engine is never moved;
  - no solo fleet left after a merge;
  - secrets absent from `Debug`/logs.
- **Phone** (`crates/mobile-client` tests + Android unit): re-fetch on reconnect adopts a changed fleet id, joins new members and drops nothing.
- **QR**:
  - `rqrr` decodes the painted tile at its real logical size for every payload shape (LAN link, Tailcat link, 4-member invite);
  - a px-per-module assertion of at least 4;
  - CLI output decodes after rendering with explicit colours;
  - the width hint triggers below the code's width.
- **Manual (ticket 07)**: the user's exact four-machine layout, plus a phone over LAN and over Tailcat.

## Out of Scope

- Multi-user fleets, per-invite member scoping, and a remote "leave" button. Leaving stays `roboco engine room leave` on the machine.
- Liveness probing, and rotate-then-revoke changes beyond what the merge needs.
- Changing the pairing link grammar or adding a composite QR.
- Fixing GPUI's SVG rasteriser scale (a zui fork change); quads avoid it.

## Tickets

| # | ticket | blocked by |
|---|---|---|
| 01 | `issues/01-engine-replace-and-rooms-v3.md` | web-removal PR merged |
| 02 | `issues/02-desktop-join-before-mint-and-merge.md` | 01 |
| 03 | `issues/03-phone-follows-fleet-changes.md` | 01 |
| 04 | `issues/04-desktop-qr-that-scans.md` | web-removal PR merged |
| 05 | `issues/05-cli-qr-polarity-and-width.md` | — |
| 06 | `issues/06-adr-and-docs.md` | 01–05 |
| 07 | `issues/07-final-verification.md` | 01–06 |

One PR, one commit per ticket, branch `feature/fleet-merge` off `main`.

## User decisions (2026-10-28)

- "Sounds good, make tickets of that" in reply to the merge proposal. Recorded above under *Settled by the user*: merge on pairing with no confirm; the bigger fleet wins with the tie going to the acting desktop; desktops may fetch and move fleets (ADR 0011 amendment approved); the fleet-invite QR is dropped in favour of Copy.
- Today's workaround (paste the SG/US fleet invite on the Laptop and the PC) was given to the user; nothing to build for it.
- Open: which QR the user failed to scan (Tailcat invite, pairing link, fleet invite or terminal). Ticket 04/05 fix all four either way.
