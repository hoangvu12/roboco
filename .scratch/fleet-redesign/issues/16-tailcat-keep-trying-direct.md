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

**Status:** ready-for-human (built and tested; the real-link gate — Laptop ↔ PC, phone ↔ PC — needs the user's machines, checklist in Comments)

**Parent:** `.scratch/fleet-redesign/spec.md`

- [x] Adapter test with the test DERP (`cmd/roboco-tailcat-testderp`): two loopback peers start on the relay, and the pings upgrade them to direct; the status reports `Direct`
- [x] With UDP blocked between the peers: the status stays `Relay(<code>)`, and the cadence drops to every 60 s
- [x] Desktop and Android show the label; nothing secret appears in logs or status text
- [ ] Before/after numbers from the user's machines in Comments

## Comments

### 2026-10-28 — built; the real-link gate is the user's (feature/remote-speed)

**What shipped**

- **The push** (`adapters/roboco-tailcat/path.go`): every client runs a small prober. After a (re)connect it sends `DiscoPing` at 1, 3, 10 and 30 s, then every 60 s while pongs come back over a relay (or not at all), and stops pushing once a pong arrives over a direct path. While direct it re-checks only every 5 min, so a fall back to the relay is noticed and the push resumes. It re-arms on a tunnel reset, after any failed dial, on a network change (the machine's address set, compared every 15 s; never logged), and when the phone returns to the foreground. The health probe after a failed dial (existing) also feeds the status.
- **One switch:** `pushForDirectPath` in `path.go`. With it off, a client pings once per (re)connect and then every 5 min, which is just enough to keep the label current. The label ships either way.
- **The status:** `PathStatus` → `{"path":"direct","rttMs":35}` / `{"path":"relay","region":"tok","rttMs":160}`. Only the path kind, a region code (reduced to a short `[a-z0-9-]` token) and a latency. The pong's endpoint is dropped before it is stored, and nothing is logged.
  - **Desktop:** `roboco-tailcat connect` prints these lines after its readiness line when `ROBOCO_TAILCAT_STATUS=1` (an env var, not a flag, so an older adapter just stays silent instead of refusing to start). `roboco_engine::tailcat::TailcatClient::link_path()` keeps the latest line.
  - **Android:** two additive `capi` calls, `roboco_tailcat_path(handle, &json)` and `roboco_tailcat_set_background(handle, bool)`. ABI stays 1: the Rust loader resolves them optionally, and an older library just reports no path. `on_background` pauses the pings (ADR 0010) and `on_foreground` re-arms them.
- **The label** (`roboco_proto::tailcat::LinkPath::label`, shared): "Direct · 35 ms" / "Via relay (Tokyo) · 160 ms". Tailscale region codes map to cities, and an unknown code shows upper-cased.
  - Desktop: today's Settings → Devices row adds it after "Online" (`settings/devices.rs`, refreshed every 5 s while the page is open). `remote_access.rs` is untouched (ticket 01's file).
  - Android: the Settings engine list row shows it instead of "Connected" (`DevicesRows.kt`; `EngineView.linkPath`, filled by the core on every publish, and the link's 5 s probe publishes).

**Tests** (local, Windows; `CGO_ENABLED=0 go test ./...` in `adapters/roboco-tailcat`):

| test | what it shows | result |
|---|---|---|
| `TestPathPushUpgradesRelayToDirect` | two loopback peers on the test DERP (`integration.RunDERPAndSTUN`, what `roboco-tailcat-testderp` wraps) start relayed (direct UDP blocked while they come up: on loopback the very first pong is otherwise already direct); with UDP open, the pings alone (no tunnel traffic) move them to direct; status `Direct`, line `{"path":"direct",…}` with no address; no more pings after that | pass, 3/3 + full-suite runs |
| `TestPathPushStaysRelayedWhenUDPIsBlocked` | `TS_DEBUG_NEVER_DIRECT_UDP` (UDP blocked between the peers): status stays relayed with its region code; after the burst the gaps equal the relayed interval (shrunk to 1 s in the test; production is 60 s, asserted) | pass |
| `TestPathProberCadence` | scripted pings: burst, relayed cadence, no pings at all in the background, a fresh burst on return that finds direct and stops, re-arm | pass |
| `TestPathStatusHygiene`, `TestDiscoPingsKeepLiveConnections` | a failed ping keeps the last path; odd region codes are dropped; an open connection keeps carrying bytes across pings | pass (5/5) |
| capi `TestPathAndBackgroundIgnoreUnknownHandles` | the new exports on unknown handles | pass |
| Rust `link_paths_parse_the_adapter_lines_and_label_them` (proto) | parsing, labels, region hygiene | pass |
| Android JVM `DevicesRowsTest.aConnectedTailcatEngineShowsItsPath` | label on a connected Tailcat row only; a stale path never hides "Reconnecting…" | CI (no Android SDK here) |
| `mobile-client/tests/tailcat_engine.rs` (extended) | the real `libroboco_tailcat` + adapter + test DERP: the engine view gets a path, and its label has no address | CI `ui-tests.yml` (needs cgo; no C compiler here) |

Smoke, real binaries: `roboco-tailcat connect` with `ROBOCO_TAILCAT_STATUS=1` through `roboco-tailcat-testderp` printed `{"url":"http://127.0.0.1:…"}` then `{"path":"direct","rttMs":0}`.

**Pre-existing flake (not this change):** `TestLocalDERPTwoPeerProxy` / `TestLocalDERPClientStateRoundTrip` hang or see EOF when the machine is heavily loaded. They reproduced on an untouched copy of `adapter.go` from `HEAD` under the same load (a hang at 3m20s), and pass when the machine is quiet. CI does not run the Go tests.

**Deviation:** while direct, the ticket says "stop". The prober stops *pushing* but still checks every 5 min. Without that, a direct path that silently fell back to the relay would keep showing "Direct" and never re-arm the push.

**The gate: the user's manual checklist.** The pings stay only if they help a real link.

The user's slow link is **Laptop → PC** (160 ms, Tokyo relay, "PC on the laptop keeps reconnecting"). The comparison is **phone → PC** ("smooth on the phone"). Then PC ↔ SG/US if convenient.

1. **Before** (current release, still on the Laptop):
   - In a terminal: `tailcat ping --until-direct --timeout=60s <PC's tc address>`. Note whether it ever says direct, plus the relay and latency it reports.
   - Open a big chat on the PC from the Laptop and note roughly how long until it's all there.
2. Install this build on the **PC and the Laptop** (both run the new adapter).
3. **Laptop → PC:** open the Laptop app, Settings → Devices, PC row.
   - Within about 30 s it reads either "Direct · N ms" or "Via relay (Tokyo) · N ms".
   - Leave it open for 2 minutes. If it turns Direct, note N; it should be far below 160.
   - Open the same big chat again and note the time.
4. **Phone → PC:** install the APK, then Settings → engines list, PC row. Note Direct/relay and ms. The ticket guesses the phone is direct, which would explain "smooth on the phone".
5. Optional: **PC ↔ SG/US** the same way, from the PC's Devices page.
6. **Decide.** Paste the before/after table in this ticket.
   - If **no link ever turns Direct**, set `pushForDirectPath = false` in `adapters/roboco-tailcat/path.go` (one line) and keep the label.
   - If at least one does, keep it.
