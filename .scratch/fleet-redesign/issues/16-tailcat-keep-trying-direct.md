# 16 — Tailcat: keep trying for a direct path, and report which path is used

**Why:** measured on the user's **Laptop**, on its link to the **PC** (2026-10-28; the earlier "PC" wording in this ticket was wrong, the session ran on the Laptop):
- The Laptop (Wi-Fi) and the PC (wired) are on different networks in Ho Chi Minh City.
- The Laptop → PC Tailcat link runs at about **160 ms per round trip** and about **1.1–1.2 MB/s**. It was re-measured after a fresh re-pair at 10:10: stable, with no drops.
- It goes through Tailscale's rate-limited **Tokyo** relay; the Laptop reaches that relay at 85 ms.
- The user sees the PC "laggy and reconnecting" on the Laptop, but smooth on the phone. The phone→PC path should be checked the same way: it may be direct.

**The gap in our adapter:** Tailcat's docs say `Client.DiscoPing` "actively triggers direct path discovery, so pinging repeatedly upgrades the connection when NAT traversal is possible", while `Client.Ping` "always measures the DERP path". Our adapter calls `DiscoPing` only as a health probe after a failed dial (`adapters/roboco-tailcat/adapter.go:392-420`). After the startup `Ping` (`:241`), it never pushes for a direct path.

**What to build** (in `adapters/roboco-tailcat`; it covers the desktop forwarder and the Android `capi` client alike):

- **Push for a direct path.** After a client connects, send `DiscoPing`:
  - a few times in the first 30 s (e.g. at 1, 3, 10 and 30 s);
  - then every 60 s while the last pong came over a relay;
  - stop once a pong arrives over a direct path; resume if a later ping (or a failed dial) shows the relay again;
  - re-arm on network change and on tunnel reset.

  This mirrors `tailcat ping --until-direct`. Pings are tiny, but keep the cadence low on the phone when the app is in the background (ADR 0010).
- **Report the path.** Keep the last result, `Direct` or `Relay(<region code>)`, plus the measured round trip. Expose it:
  - from the `roboco-tailcat` child (a status line the engine or desktop already reads, or a small status query);
  - through the `capi` handle for Android.

  It is never logged with addresses or keys (ADR 0008).
- **Show it.** On today's Settings → Devices rows (desktop) and the Android engine list: "Direct · 35 ms" or "Via relay (Tokyo) · 160 ms". Ticket 07's new page keeps it.

**Gate (user decision: "if A works then go ahead"):**
1. Build it.
2. Measure on the user's real PC ↔ Laptop link (and PC ↔ SG/US).
3. Record before/after in Comments.

If no link ever goes direct, ship only the path label and the measurements, and say so plainly. The extra pings stay only if they help at least one real link.

**Blocked by:** —

**Status:** ready-for-agent

**Parent:** `.scratch/fleet-redesign/spec.md`

- [ ] Adapter test with the test DERP (`cmd/roboco-tailcat-testderp`): two loopback peers start on the relay, and the pings upgrade them to direct; the status reports `Direct`
- [ ] With UDP blocked between the peers: the status stays `Relay(<code>)`, and the cadence drops to every 60 s
- [ ] Desktop and Android show the label; nothing secret appears in logs or status text
- [ ] Before/after numbers from the user's machines in Comments

## Comments
