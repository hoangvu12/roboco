# 05 — Android preview panel + in-app browser surface

**What to build:** The phone's half of the session relay. A new `browser/` package in `apps/android` hosting a WebView composable, pushed as a `NavStack` entry from the session screen (or slotted into `session/SessionBottomStack.kt` — decide in-ticket; the slot composable is supplied by its owner). The preview list comes from the mobile core: `crates/mobile-client` already holds engine sessions (`Client::engine_handle`, `PairedEngine` records, `crates/mobile-client/src/lib.rs:226-234`); add a UniFFI surface for `WatchPreviews {chatId}` mirroring the desktop's per-tab subscription (2 s resubscribe, the `state/preview.ts` shape) plus the Rust client-proxy module from ticket 04 running in the core, binding `127.0.0.1:7332` in-app (pure tokio, no GPUI dependency — it must be compiled for the mobile core; `crates/mobile-client` is "UniFFI-free" so the proxy lives where the core can reach it and the FFI wraps the controls). The panel: list rows (name, port, device subtitle — the desktop `preview_body` vocabulary), an empty state matching the desktop's ("Start a dev server in this project on your other device. Its preview will appear here when that device is online."), and an Open that enters the browser entry loading `http://{hostname}:7332`.

**Resolution mechanism (verified, no interception layer):** Android WebView is Chromium, and Chromium synthesizes `*.localhost` → `127.0.0.1`/`::1` in-process before any system resolver or DNS (`net/dns/host_resolver_manager.cc` `ResolveLocalHostname`, RFC 6761 §6.3 behavior) — so `foo.localhost:7332` reaches the in-app proxy with zero OS configuration, and WebSockets/HMR work because it is a real socket (Android's `shouldInterceptRequest` cannot carry WS, redirect chains, or POST bodies — deliberately not used). Cleartext HTTP to loopback is already permitted by the app's `network_security_config.xml` base-config; if that ever tightens, the explicit shape is a `localhost` `<domain-config cleartextTrafficPermitted="true" includeSubdomains="true">` (the API 37 implicit localhost exemption does not cover `foo.localhost` subdomains).

**Why now:** phone access is the headline use case; everything it needs (sessions, snapshot, client proxy, resolution) exists after 03/04.

**Blocked by:** 03 — engine relay route; 04 — the shared client-proxy module (the Android core embeds it).

**Status:** ready-for-agent
**Parent:** `.scratch/agent-previews/spec.md`

**Verification budget:** `cargo test -p roboco-mobile-client` (core preview snapshot + proxy control surface), `:app:testDebugUnitTest` (panel state, NavStack wiring), the emulator flow suite gains a preview open against the `android_fixture` engine when the WebView surface lands.

- [ ] A remote chat's preview list renders on the phone with live updates and the honest empty state
- [ ] Open renders the remote dev server in-app at `http://{hostname}:7332` with HMR/WebSocket intact (real socket through the in-app proxy)
- [ ] Engine reachability follows the existing transport (LAN direct / Tailcat tunnel) — no new pairing or network setup
- [ ] Revoking the phone's session (or engine offline) degrades the panel to the reconnect state, matching chat behavior
- [ ] No preview surface ships on unpaired/unreachable engines beyond the honest note

## Comments

- 2026-10-26 (research): Chromium `*.localhost` synthesis and the cleartext findings are verified against Chromium source and the Android network-security-config docs (round-2 design research); the earlier ProxyController/shouldInterceptRequest candidates were dropped — the WebView's native resolution makes them unnecessary.

- 2026-10-26 (orchestrator): first worker attempt (chat `ap-05 android preview panel`) was killed by a second host disk-full event (ENOSPC) mid-build — the Android/gradle build consumed the remaining 19G; nothing persisted (worktree clean at base `3eb693a40`). Freed ~110G (killed the dying build's orphaned gradle/cargo processes holding deleted temp files + cleaned the integration target). Relaunched as a fresh worker (`ap-05 android preview panel (retry)`) in the same worktree with disk-headroom discipline (111G free at relaunch).
