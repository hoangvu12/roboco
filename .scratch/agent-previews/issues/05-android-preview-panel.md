# 05 — Android preview panel + in-app browser surface

**What to build:** The phone's half of the session relay. A new `browser/` package in `apps/android` hosting a WebView composable, pushed as a `NavStack` entry from the session screen (or slotted into `session/SessionBottomStack.kt` — decide in-ticket; the slot composable is supplied by its owner). The preview list comes from the mobile core: `crates/mobile-client` already holds engine sessions (`Client::engine_handle`, `PairedEngine` records, `crates/mobile-client/src/lib.rs:226-234`); add a UniFFI surface for `WatchPreviews {chatId}` mirroring the desktop's per-tab subscription (2 s resubscribe, the `state/preview.ts` shape) plus the Rust client-proxy module from ticket 04 running in the core, binding `127.0.0.1:7332` in-app (pure tokio, no GPUI dependency — it must be compiled for the mobile core; `crates/mobile-client` is "UniFFI-free" so the proxy lives where the core can reach it and the FFI wraps the controls). The panel: list rows (name, port, device subtitle — the desktop `preview_body` vocabulary), an empty state matching the desktop's ("Start a dev server in this project on your other device. Its preview will appear here when that device is online."), and an Open that enters the browser entry loading `http://{hostname}:7332`.

**Resolution mechanism (verified, no interception layer):** Android WebView is Chromium, and Chromium synthesizes `*.localhost` → `127.0.0.1`/`::1` in-process before any system resolver or DNS (`net/dns/host_resolver_manager.cc` `ResolveLocalHostname`, RFC 6761 §6.3 behavior) — so `foo.localhost:7332` reaches the in-app proxy with zero OS configuration, and WebSockets/HMR work because it is a real socket (Android's `shouldInterceptRequest` cannot carry WS, redirect chains, or POST bodies — deliberately not used). Cleartext HTTP to loopback is already permitted by the app's `network_security_config.xml` base-config; if that ever tightens, the explicit shape is a `localhost` `<domain-config cleartextTrafficPermitted="true" includeSubdomains="true">` (the API 37 implicit localhost exemption does not cover `foo.localhost` subdomains).

**Why now:** phone access is the headline use case; everything it needs (sessions, snapshot, client proxy, resolution) exists after 03/04.

**Blocked by:** 03 — engine relay route; 04 — the shared client-proxy module (the Android core embeds it).

**Status:** implemented
**Parent:** `.scratch/agent-previews/spec.md`

**Verification budget:** `cargo test -p roboco-mobile-client` (core preview snapshot + proxy control surface), `:app:testDebugUnitTest` (panel state, NavStack wiring), the emulator flow suite gains a preview open against the `android_fixture` engine when the WebView surface lands.

- [x] A remote chat's preview list renders on the phone with live updates and the honest empty state
- [ ] Open renders the remote dev server in-app at `http://{hostname}:7332` with HMR/WebSocket intact (real socket through the in-app proxy)
- [x] Engine reachability follows the existing transport (LAN direct / Tailcat tunnel) — no new pairing or network setup
- [x] Revoking the phone's session (or engine offline) degrades the panel to the reconnect state, matching chat behavior
- [x] No preview surface ships on unpaired/unreachable engines beyond the honest note

**Checkbox honesty (2026-10-27, the implementing worker):** boxes 1/3/4/5 are
demonstrated at the layers this host can run — the Rust state machine against
a **real in-process engine** (pairing, `WatchPreviews`, snapshot updates,
link-loss degrade, fast-fail on unpaired engines), and the Compose screen
through Robolectric (rows, empty state, reconnect, error footer, Open pushing
the browser entry; the menu row's wiring). Box 2 is left **unticked**: the
Open→push→WebView-loads-`http://{hostname}:7332` path and the proxy's data
plane (a real HTTP round trip through the phone-side 7332 proxy over the
paired session's relay to a real dev server) are both proven in-process, but
**HMR/WebSocket passthrough in a live WebView needs an emulator/device with
the fixture engine — no KVM on this box, so the runtime proof is the flow
suite's job on CI** (`PreviewFlowTest`, wired and compiling; it polls the
fixture's `/preview-hits` to prove the WebView really reached the engine's
dev server). No interception layer is used anywhere — the resolution is the
WebView's own Chromium `*.localhost` synthesis.

## Comments

- 2026-10-26 (research): Chromium `*.localhost` synthesis and the cleartext findings are verified against Chromium source and the Android network-security-config docs (round-2 design research); the earlier ProxyController/shouldInterceptRequest candidates were dropped — the WebView's native resolution makes them unnecessary.

## Implementation record (2026-10-27)

**What landed where.**

- `crates/mobile-client/src/previews.rs` (new, UniFFI-free): the preview hub
  and the per-chat panel state machine. `PreviewsHub` embeds ticket 04's
  `roboco-preview-relay` `ClientProxy` (lazy 7332 bind, idle shutdown,
  hostname routing from `WatchPreviews` ingestion, persistent collision
  labels under the client's data dir) with a `MobileDialer` that dials each
  engine's `/preview-relay` route through **the engine entry's own route**
  (LAN `ws://` endpoint, or a Tailcat tunnel's loopback opened on demand)
  plus the paired session credential — reachability follows the existing
  transport with zero new pairing or network setup, and fleet hygiene drops
  forgotten engines' routes. `PreviewPanel`/`PanelState`/`PanelPhase` mirror
  the desktop's per-tab subscription: `Loading` → `Live`, a stream that ends
  mid-connection clears the rows to the reconnect state and retries after
  the desktop's 2 s beat (`state/preview.ts`' shape and wording), an engine
  too old for `WatchPreviews` degrades once (`Unsupported`), and engine
  offline/parked/unpaired all land in the reconnect state. The panel holds
  the proxy's watcher while open (what keeps 7332 bound) and `Drop` releases
  it. `engines/handle.rs` gained `subscribe_now` (a one-shot subscription on
  the live feed/calls connection for callers that own their resubscribe
  loop — the `spawn_watch` twin never retries a mid-connection stream end on
  the same link generation); `lib.rs` gained `Client::watch_previews`,
  `preview_proxy_status`, `preview_proxy_set_watching` and the
  `new_with_preview_knobs` test seam (ephemeral port + short idle).
- `crates/mobile/src/client_ffi/previews.rs` (new): the UniFFI surface —
  `CoreClient.watchPreviews(chatId, listener)` returning the `PreviewPanel`
  object (a `PreviewPanelListener` callback receives every state on a client
  runtime thread: `PreviewPanelState { phase, engineId, services, error }`
  with `PreviewServiceRow` rows carrying the desktop `preview_body`
  vocabulary plus the `http://{hostname}:7332` URL), `previewProxyStatus()`
  and `previewProxySetWatching(engineId, watching)` (the ticket's
  start/stop/status controls). The panel's stop method is named `stop`, not
  `close` — UniFFI's generated object already carries `AutoCloseable.close`
  and a same-named export collides with it.
- `apps/android/…/browser/` (new package): `PreviewPanelRoute` (pushed from
  the session ⋯ menu's new "Previews" row — the slot decision is a NavStack
  entry, **not** a `SessionBottomStack` slot: the bottom stack's slot set is
  fixed by its owner tickets and the WebView needs a full-screen NavStack
  entry anyway, so the panel lives one push beneath the browser entry, which
  also keeps the proxy's watcher held while a preview is viewed because a
  NavStack composes every entry), `PreviewPanelScreen` (grouped rows: name +
  `device · localhost:port` subtitle; the loading/reconnect/unsupported
  notes and the desktop's honest remote empty state; the inline error
  footer; Open pushes `BrowserRoute`), `PreviewPanelModel` (the state flow
  the FFI callback feeds, the start/stop lifecycle on the entry's
  composition, the unpaired-engine degradation), `PreviewsCore` (the FFI
  seam, fakeable on the plain JVM), `BrowserRoute`/`BrowserScreen` (the
  WebView entry: script + DOM storage enabled — a dev server's page and HMR
  need them — a back/reload chrome, and the `BrowserPage` state machine
  driven by the `WebViewClient`; **no `shouldInterceptRequest`, no
  ProxyController** — the WebView's Chromium synthesizes `*.localhost`
  itself), `PreviewsCopy` (the desktop vocabulary, verbatim).
- `ShellRoutes.kt` registers both routes; `SessionMenu.kt` gains the
  "Previews" row (globe icon, never disabled — the panel owns its honest
  states), wired in `rememberSessionMenu` to push the panel with the
  session's chat id and title.
- `crates/engine/examples/android_fixture.rs`: the fixture now serves the
  preview relay (`serve_engine_remote_with_previews`, both at start and in
  `/listener/start`), seeds a `chat-preview` chat, and runs a real loopback
  dev server attributed to that chat through the origin marker (Linux/macOS:
  the `LocalConnector`'s process-identity inputs come from `/proc`/`ps`;
  CI's emulator job is the ubuntu runner) with a hit counter exposed at
  `GET /preview-hits` on the control endpoint.
- `apps/android/…/flows/PreviewFlowTest.kt` (new instrumented test): pairs
  with the fixture, routes into `chat-preview`, opens the ⋯ menu's Previews
  row, waits for the panel's row, taps Open, waits for the browser entry,
  then polls `/preview-hits` until the engine's dev server saw the request —
  the end-to-end proof that the WebView reached the engine's server through
  the in-app 7332 proxy and the session relay. Skips without the rig, like
  every flow test; **runs on CI's emulator job (no KVM on this box)**.

**Verification (2026-10-27, host `roboco-wt-ap-05`, linux aarch64).**

- `cargo test --locked -p roboco-mobile-client` — **green on retry**:
  lib 197 passed (incl. all 6 `previews::tests`: unknown chat fails fast
  without binding anything; Loading → Live with effective hostnames + the
  lazily-bound listener; close releases the watcher and the listener unwinds
  after the idle window; engine offline degrades to the reconnect state;
  **a real HTTP round trip through the phone-side proxy over the paired
  session's relay**; a second snapshot frame updates the rows), and every
  integration binary green (`real_engine` 19, `session_engine` 21 + 3
  ignored-by-design, accounts/images/notify/workspace/side_chats/tailcat).
  One run of the full sweep hit `real_engine`'s pre-existing dial-count
  flake (3 counting-listener tests) — **demonstrated at base with the
  changes stashed** (base runs: 3/3/1 failures across three runs; this
  host's load-sensitive races, not ticket-05 code; each failure passes
  alone).
- `cargo test --locked -p roboco-mobile` — 56 passed (incl. the FFI
  previews test: an unpaired engine refuses over the FFI error surface, the
  controls are inert-safe).
- `cargo check --locked -p roboco-mobile-client --target aarch64-linux-android`
  via `cargo ndk` (the xtask's env shape) — green; the gradle core build
  then built `libroboco_mobile.so` (arm64-v8a, 165 MB mobile profile) and
  regenerated the UniFFI Kotlin.
- `cd apps/android && ./gradlew :app:testDebugUnitTest -ProbocoAbis=arm64-v8a
  -ProbocoSkipTailcat` — **the whole Robolectric suite cannot run natively
  on this host** (Robolectric's native runtime has no linux-aarch64 build;
  every one of the 2768 failure reports in a full native run is exactly
  that environment error, zero others — CI's ubuntu/windows runners are the
  real gate). To still get runtime proof I ran the touched classes with the
  test JVM under `qemu-x86_64` + the x86_64 JDK (a local-only harness:
  a fake JDK at `/tmp/qemu-jdk-21` pointed at by
  `-Dorg.gradle.java.installations.paths`, nothing committed): **browser
  18/18** (`PreviewPanelModelTest` 5 — also green natively, plain JVM;
  `PreviewPanelScreenTest` 6 — rows + vocabulary + empty/reconnect/
  unsupported + error footer + Open pushes `BrowserRoute` and the panel
  stays composed beneath it; `BrowserScreenTest` 7 — WebView created with
  script + storage, `loadUrl("http://{hostname}:7332")`, the page state
  machine, the hidesBottomChrome flags), **SessionMenuTest 19/19**,
  **SessionMenuScreenshotTest 3/3** (the panel-bounds worked example updated
  for the fifth row), **SideChatsEntryTest 9/9**. One flake observed once
  (`copyBuildsTheTextOffMain…` thread-name assertion) — passed on rerun.
- `:app:compileDebugAndroidTestKotlin` — green (the flow suite with
  `PreviewFlowTest` compiles; its runtime is CI's emulator job).
- `cargo clippy` on the touched crates — zero warnings in any touched file
  (A/B against the stashed base; the one `android_fixture` warning,
  `too_many_arguments`, pre-existed at base with 9 args and remains 1
  warning with 11).

**Not met / known gaps.**

- The HMR/WebSocket runtime item of box 2 (above): needs an emulator or
  device with the fixture engine — CI's `android-emulator` job runs
  `PreviewFlowTest`, which proves the load end-to-end via `/preview-hits`;
  HMR's long-lived WS through the relay is additionally covered engine-side
  by ticket 03's upgrade-tunnel tests, but a live Vite page in the WebView
  was not observed on this box.
- The `session_menu_*` Roborazzi goldens now differ (a fifth menu row):
  they are recorded on CI only (ubuntu is the source of truth) — a
  `record_goldens` dispatch is needed for `session_menu_light_unpinned`,
  `session_menu_dark_pinned`, `session_menu_light_readonly`. The
  android-screenshots verify job is non-blocking per AGENTS.md; the
  in-test bounds assertion is already updated for the five-row panel.
- The Tailcat leg of the relay dial is the route module's existing
  (independently tested) path reused verbatim; no Tailcat-specific relay
  test was added here (the dialer has no Tailcat-specific code).

**Relaunch notes.** The first worker attempt died to a host disk-full
(ENOSPC) mid-build (orchestrator note below); the **second** attempt also
died to a second ENOSPC mid-gradle-build but left its Rust half in
`stash@{0}` ("wip2") — this run applied that stash as a starting point after
reviewing it, verified it from scratch (its tests are the ones above), and
built the entire missing Kotlin half on top. A third interruption: the
engine self-updated and restarted at 01:22 UTC mid-turn (nothing lost — the
worktree kept all uncommitted progress; verification resumed cleanly).
