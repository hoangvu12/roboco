# 07 — Desktop: one Devices page (Remote access rework) and a QR that scans

**What to build:** in `crates/ui/src/settings/`, merge `remote_access.rs`, `remote_access/fleet.rs` and `devices.rs` into one **Devices** page. Remove the Remote access entry from the settings nav. Follow the settings widgets and theme. Use the `impeccable` skill for the visual pass.

Layout (top to bottom):

```text
Devices
Connect your computers, servers and phone. Connected devices can use each other.

┌ This computer ─────────────────────────────────────────────┐
│ 🖥  PC                                   Let my devices connect  [on] │
│     Reachable over Tailcat                                   │
└──────────────────────────────────────────────────────────────┘

[ Connect a device ]   [ Enter a code ]

Your devices
┌──────────────────────────────────────────────────────────────┐
│ 🖥  Laptop      ● Online · Tailcat                       ⋯   │
│ 🗄  Linux SG    ● Online · Tailcat                       ⋯   │
│ 🗄  Linux US    ○ Offline · last seen 2h ago             ⋯   │
│ 📱 S23 Ultra   ● Online · controls your devices          ⋯   │
└──────────────────────────────────────────────────────────────┘

▸ Advanced
```

- **This computer:**
  - The switch is today's remote-access toggle, renamed.
  - The caption is one of "Reachable over Tailcat" / "Reachable on your network" / "Only this computer can use it".
  - Errors show inline under the card.
- **Connect a device** opens a dialog with:
  - a large crisp QR;
  - **Copy code**;
  - **Copy command** (`roboco connect roboco:…`, for servers);
  - an expiry countdown with **New code**;
  - **Enlarge** (the full-window QR).

  While the switch is off, the dialog instead says "Turn on Let my devices connect first" and shows the switch.
- **Enter a code**: a paste field and **Connect**. Progress reads "Connecting to SG…" → "Connected. 2 more devices joining…".
- **Your devices**: one row per device, by device id.
  - Servers come first, then controllers.
  - The icon shows the kind.
  - The status is one of: Online · Tailcat / LAN; Offline · last seen; Connecting…; "Can't reach back: turn on Let my devices connect on PC"; "Update Roboco on SG to connect it both ways".
  - The row menu (⋯) has **Remove from my devices** (with a confirm: "SG will lose access to all your devices") and **Copy name**.
  - An empty state points at Connect a device.
- **Advanced** (collapsed):
  - the transport choice (network vs Tailcat), with the bind address;
  - the LAN pairing link (for your own tunnel);
  - the raw session list with Revoke, revoked ones included;
  - "Disconnect this computer" (ticket 04).
- **Gone:** the Fleet card, include switches, Show fleet invite, Join a fleet, and the separate Pairing link / Create Tailcat invite buttons on the main surface.
- **QR (from fleet-merge ticket 04):**
  - painted as GPUI quads (no SVG raster blur);
  - the tile sized to a whole number of logical px per module, at least 4;
  - error correction L;
  - white quiet zone.
- **The page scrolls** (keep ticket 01's scroll host).

**Blocked by:** 01, 06 (Remove menu: 04)

**Status:** ready-for-agent

**Parent:** `.scratch/fleet-redesign/spec.md`

- [ ] GPUI tests for every row state, the switch-off dialog, Enter-a-code progress, the remove confirm, and the empty state
- [ ] `rqrr` decodes the painted tile at its real logical size for a Tailcat code and a LAN code; ≥ 4 px per module
- [ ] Before/after screenshots in the ticket comment, light and dark
- [ ] No route, code or credential in logs or `Debug`; the code is shown only in the dialog

## Comments
