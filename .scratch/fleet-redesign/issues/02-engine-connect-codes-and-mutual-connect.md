# 02 — Engine: short connect codes and a two-way connect

**What to build:** in `crates/proto`, `crates/engine` (pairing store, remote access, listener) and `adapters/roboco-tailcat`:

- **A compact code grammar** (`crates/proto/src/connect.rs`, the single decoder shared by engine, CLI, desktop and Android core):
  - `roboco:` + base64url-no-pad of a binary record:
    - a version byte;
    - the route kind;
    - for Tailcat: node public key, disco public key, pre-shared key, DERP region id;
    - for a custom DERP map or LAN: a length-prefixed route field;
    - a 128-bit one-time secret.
  - Target: ≤ 170 characters for a Tailcat region-id route.
  - `Debug` is redacted, and errors are shape-only (the `crates/proto/src/tailcat.rs` pattern).
  - The decoder also accepts old `roboco-tailcat:` invites and `…/pair#token=` links and reports them as `Legacy` (they make one-way controller pairings).
- **Region-id addresses.** The adapter persists and publishes a region **id** instead of the full `DERPRegion` when the default DERP map is in use (`adapter.go:452-467`; Tailcat `ConnInfo.RegionID`, about 140 bytes per Tailcat docs). Custom maps (`--tailcat-derp-map`) keep the full region. Existing state files still load, and the server keys don't change.
- **`MintConnectCode`** (session-authenticated, plus a local admin path for the desktop and CLI):
  - valid for 15 min, single use;
  - refuses with fixed text when remote access is off;
  - never switches remote access on.
- **`POST /connect`** (pre-session, throttled on failure like the pairing redeem). The body carries:
  - the one-time secret;
  - the caller's device id, name and kind (`server` | `controller`);
  - for a server caller: its routes and a fresh one-time code for this engine to redeem back.

  The engine mints a session for the caller and records the caller in `peers`. For a server caller, it then dials the caller's route and redeems the returned code, which gives a mutual connection. If the dial-back fails, the row becomes `one-way` and is retried by ticket 03's links.
- **The `peers` table** in `pairing.sqlite3` (IF-NOT-EXISTS, owner-only, WAL sidecars). One row per device id, with:
  - name and kind;
  - routes (secret);
  - our credential on the peer (secret);
  - the peer's session id here;
  - state (`connected` / `one-way` / `removed`), `removed_at`, `last_ok`.

  Re-connecting the same device id updates the row in place and revokes the superseded session.
- **Capability `connect-v1`.**

**Blocked by:** —

**Status:** ready-for-agent

**Parent:** `.scratch/fleet-redesign/spec.md`

- [ ] Codec: round-trip; ≤ 170 chars for a Tailcat region-id route; legacy forms decode as `Legacy`; malformed input gives shape-only errors; `Debug` redacts
- [ ] Two loopback engines: A redeems B's code and both hold a working session on each other; peer rows on both sides carry the right device ids
- [ ] Controller redeem: a session for the controller, no dial-back, row kind `controller`
- [ ] Single use, expiry, throttle after failures; `MintConnectCode` refuses while remote access is off and leaves settings untouched
- [ ] Re-connect the same device id: one row, the old session revoked
- [ ] Adapter: a v0.14 state file with a full region still loads; a fresh default-map server publishes a region-id address
- [ ] No code, route or credential in logs, `Debug` or error text

## Comments
