# The web client is engine-served, not hosted

Roboco's browser client is served by the engine itself, at its remote access origin — visit the engine's URL, pair in the browser, use Roboco. t3code hosts its web app separately, but that works because it runs a cloud relay (T3 Connect) and requires HTTPS endpoints; Roboco has no relay and never will (ADR 0004). Engine-serving keeps one origin (no CORS/mixed-content class of bugs), works over plain HTTP on LAN, keeps client and engine version-locked (no skew), and preserves the single-binary release. Multi-engine support comes from a cross-origin "Add engine" flow (pairing-link redeem is CORS-open; the WebSocket authenticates by first-frame envelope). A separately-hosted deployment of the same bundle remains possible later as a deployment choice, not a code change.

## Consequences

- No PWA install / add-to-home-screen on plain-HTTP LAN (insecure context) — v1 is a browser tab.
- Browser-stored credentials are scoped to whichever engine origin you bookmarked; engine B's own page starts empty. Accepted for a single-user fleet.
