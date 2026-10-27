# Web removal: no web client, no in-app browser, previews stay

Status: ready-for-agent (ticket 09 is needs-info: one label decision is open)
Spec date: 2026-10-28
Research: `.scratch/web-removal/impact-2026-10-28.md` (web inventory, dependency map, Option C checklist). Desktop-browser inventory below was gathered on `main` at `1ccaa3a8b` (fleet-follow merged).
Gate: **planning only.** Implementation starts when the user says go, on a branch cut from `main` after v0.14.0 publishes.

## Problem Statement

Roboco maintains three clients and two browsers, but uses only two clients and needs no browser:

- **The web client** (`web/`, ADR 0006/0007) is a third client kept in parity by hand. It costs about 203k lines, a ts-rs codegen layer on every wire type, three CI workflows, Node/pnpm in every release build, and roughly 1.7k lines of security-relevant browser plumbing in the engine (CORS, first-frame auth, server-side Tailcat and room relays). Nothing on the desktop or Android depends on it. The user does not use it.
- **The desktop in-app browser** (`crates/ui/src/browser/**`, 4.4k lines plus a C WebKitGTK helper) renders pages only on Linux (WebKitGTK) and macOS (WKWebView). Roboco ships no macOS build. On Windows, the primary desktop platform, it never rendered a page: no WebView2 host exists. Clicking a server in the ports card on Windows opens the system browser *and* leaves a blank Browser tab behind (`crates/ui/src/shell/session_info.rs:606` → `add_browser_surface` → `browser/mod.rs:507` `cx.open_url`, and `view.rs:616` renders an empty body). On Linux it forces a WebKitGTK/JSON-GLib build dependency onto CI, release and every developer box. Every upstream browser port (#813 WebView2, #1039 WPE, #814 annotations, #573 profiles) is large and pins on a GPUI sync.
- **Previews themselves are valuable and stay.** Engine port detection, the ports card, the engine-to-engine preview relay, the client preview proxy and Android's WebView preview flow all keep working.
- The user-facing label "Previews" (Android ⋯ menu, panel title) is misleading. The list shows every server that any chat's agent or terminal started, not agent "previews".

## Solution

One PR, sequential commits (one per ticket), two removals plus one behavior change:

1. **Remove the web client entirely** (impact report Option C). Delete `web/`, `crates/wiregen` and every ts-rs derive, the engine's web bundle embedding and asset serving, every browser-only engine route (CORS preflight, `/pairing/tailcat-redeem`, `/pairing/room-redeem`, `/tailcat-relay/*`, first-frame auth on RPC and preview relay), `roboco engine tailcat browser-helper`, `capabilities::WEB_CLIENT`, the web CI workflows, and Node/pnpm from release and packaging. Before any of that, move the theme artifact out of `web/` so Android's pin tests stay green.
2. **Desktop ports card opens servers in the system browser.** Clicking a server calls `cx.open_url` with the routed URL. For a local chat that is `http://<host>.localhost:7331`. For a paired engine's chat it is the client proxy URL from `route_session_preview`, `http://<host>.localhost:7332`. The shell, rather than a browser tab, now holds the client proxy's watcher lifetime, so the external tab keeps loading.
3. **Remove the desktop in-app browser**: the `browser` module (Linux and macOS hosts, chrome, `BrowserContext`), the right-pane Browser surface and its `+`/picker entries, browser key bindings and the `BrowserReload` shortcut, the "Open links in Roboco" preference and link-menu rows, the `browser-fixture`/`preview-fixture` examples and feature, `scripts/test-linux-browser.sh`, `scripts/run-macos-browser-fixture.sh`, the WebKitGTK build step in `crates/ui/build.rs`, and the WebKitGTK apt packages in CI and release. Web links in transcripts open in the system browser. Keep `browser::model::transcript_address` (with `normalize_address`, `loopback` and their tests) by **moving** it to the markdown link layer.
4. **Rename the "Previews" label** on desktop, Android and in docs. "Ports" vs "Running servers" is the user's choice (ticket 09). Wire names, crate names, RPC methods and the glossary concept **Preview** stay (ADR 0005).
5. **Docs**: a new ADR 0012 supersedes 0006 and 0007. Amend ADRs 0008 and 0011, and update CONTEXT.md, the AGENTS.md CI list and the reference docs.

What does **not** change: `/pairing/redeem`, `/room/present`, `/health`, Bearer WebSocket RPC at `/`, Bearer `/preview-relay`, the `/pair#token=` link grammar (it is a parseable link format, not a page), `uploads-100mb-v1`, `rooms-v1`/`rooms-v2` RPCs (`GetRoomBook`, `UpdateRoomBook`, `RoomJoin`, `GetFleetInvite`), Local-bind Origin rejection, `roboco engine tailcat connect` (re-worded), every `--qr` CLI, `crates/preview`, `crates/preview-relay`, `crates/engine-client` (the Rust desktop registry), the Android WebView (`apps/android/.../browser/*`) and `PreviewFlowTest`.

## User Stories

1. As the user on Windows, I click a running server in the ports card and it opens in my default browser (Chrome/Edge). No blank tab appears in Roboco.
2. As the user, I click a server that a chat on a paired engine started (LAN or Tailcat). It opens in my default browser at `http://<device>.<project>.localhost:7332` and loads through the session relay, including HMR. The tab keeps working after I close the card or switch chats.
3. As the user, I click a web link in a transcript and it opens in my default browser. There is no "Open in Roboco" choice to make.
4. As the user on Android, I open the ⋯ menu's servers row (renamed), pick a server, and it loads in the in-app WebView exactly as today.
5. As the user, I pair the phone and desktops the way I already do: the in-app scanner, or a CLI QR (`roboco engine pairing create --qr`, `tailcat invite --qr`, `room invite --qr`), over LAN or Tailcat. Fleet follow still introduces the rest of the fleet.
6. As the maintainer, wire changes are Rust-only (no ts-rs, no wiregen regen), releases build without Node, Linux builds need no WebKitGTK, and upstream ports carry intent into two clients, not three.
7. As the maintainer, I can recover the web client and its browser plumbing from the `web-final` tag if a hosted client ever comes back (ADR 0006 already called that a deployment choice).

## Implementation Decisions

### Settled by the user (2026-10-28)

- **Option C**, the full removal. No static `/pair` page: `GET /pair` and `GET /` on the paired bind return 404 like any unknown path. Scanning a desktop pairing QR with the phone's *system camera* now lands on a 404 page. That is accepted: pairing goes through the in-app scanner or a CLI QR.
- **Desktop browser removed on all platforms**. The Android WebView stays.
- **One PR**, sequential commits per ticket. Tag `web-final` at the branch point before the first deletion. The implementer pushes the tag when the user says go.
- **Drop browser-dependent upstream ports** (see Out of scope).

### Decided in this spec (overrule in a ticket comment before building, never silently)

- **Theme artifact home: `crates/theme/artifact/artifact.json`.** `roboco-theme-export` writes only `artifact.json`. The TS wrapper, `fonts.css` and font copies were web-only, so drop them from `render_files` and from `artifact.rs`'s font table if nothing else reads them. Bump the artifact schema version only if the JSON shape changes. Android's `TestRepoRoot` anchors on the new path. `android.yml` gains `crates/theme/**` in its path triggers so an artifact change runs the pin tests.
- **Paired-bind Origin rejection.** Option C's hardening: any request on the paired bind that carries an `Origin` header gets a 403, the same treatment the Local bind already gives. Native clients (tungstenite, reqwest, the mobile core) send no Origin. Ticket 04 adds a test proving the desktop registry, the mobile client's redeem and the preview-relay dialer still pass.
- **A WebSocket upgrade without a Bearer header gets a 401** before the upgrade, on both `/` and `/preview-relay`. There is no first-frame fallback.
- **Stale on-disk state stays put.** Old data dirs may hold `tailcat-relays/` and browser-paired sessions in `pairing.sqlite3`. No migration code: the sessions stay listed and revocable, and the directory is inert. ADR 0012 records this.
- **`roboco engine tailcat connect` stays.** Its help text calls the printed URL a "loopback pairing link for a Roboco app", not a browser address.
- **Client proxy watcher lifetime moves from the browser pane to the shell.** Today only `BrowserSurface::watch_previews` (`browser/mod.rs:312`) calls `PreviewProxy::watch(engine, true)`. The ports card only ingests on click (`route_session_preview`), so without a pane the 7332 listener would idle out 30 s later and the external tab would die. New rule: opening a paired engine's server from the ports card starts a shell-owned *opened-preview watcher* keyed by `(engine, chat)`. It calls `watch(engine, true)`, keeps a `WatchPreviews {chatId}` subscription that ingests every snapshot into the proxy (so restarts and collision labels keep routing), and unwatches when that chat's service list has been empty for the proxy's idle window, the engine leaves the registry, or the app quits. Local chats need no watcher, because 7331 is the engine's own always-on proxy.
- **Dead proxy plumbing goes with the browser.** `PreviewProxyStatus.bypass` and `PreviewProxy::note_local` exist only to feed WebKitGTK's proxy bypass list. Remove them and adjust `crates/engine-client/tests/preview_proxy.rs`. Android uses `crates/mobile-client`'s own glue and is unaffected.
- **Link routing.** Web links have exactly two actions: open externally (primary click and the menu) and copy. `LinkAction::Internal` stays for **workspace file links**, which open in the editor. Only the web arm of `Internal` goes, along with `web_outcome`'s embedded flag.
- **Settings stay backward-readable.** Removing `open_web_links_in_roboco` and the `browser_reload` keymap entry must not break loading an existing settings file that still contains them. Serde ignores unknown keys today (no `deny_unknown_fields`). A test pins that.
- **Right-pane tab tests are rewritten, not deleted.** About 20 shell tests use `RightSurface::Browser` as a generic tab to exercise close-left/right, MRU and ownership (`shell.rs` ~13672-15734, `navigation_tests.rs:390`, `side_chats.rs:494`, `files_panel_workspace_tests.rs:393`). Port them to `Diff` or `Terminal` surfaces so tab mechanics keep their coverage.
- **Reference comments.** The 47 code comments that cite `web/packages/...` stay as written. ADR 0012 states that such paths resolve at tag `web-final`. `.scratch/web-parity*` is left alone.
- **Glossary.** **Preview** and **Client preview proxy** stay canonical terms (ADR 0005). Only the UI label changes, and the proxy's definition is updated to "while an opened remote preview's server runs" in place of "while viewing".

### Order of commits (one PR)

01 tag and branch → 02 theme artifact → 03 engine stops serving web → 04 browser-only engine surfaces and CLI → 05 ts-rs/wiregen/`WEB_CLIENT` → 06 delete `web/` + CI/release/scripts → 07 ports card opens the system browser → 08 remove the desktop browser → 09 label rename (after the user answers) → 10 docs/ADR → 11 final verification.

Every commit must pass `cargo check --workspace --all-targets` plus the tests its ticket names. CI runs once, on the PR head. A workflow may reference a deleted file only between commits, never at the head.

## Testing Decisions

- **Deleted tests are deleted only with their feature**: `crates/engine/tests/web_client.rs`, `tests/room_relay.rs` (and its entry in `ui-tests.yml`'s engine test list), `examples/web_conformance.rs` and `examples/web_smoke.rs`, `crates/ui/src/browser/preview_proxy_tests.rs` (its remote-preview round trip moves to ticket 07's test), and the browser fixtures.
- **Mixed tests are edited**: the browser-path cases in `tests/pairing.rs` (~188 CORS), `tests/preview_relay.rs` (~360-420, ~452-570 first-frame), `tests/remote_listener.rs` (~141) and `tests/rooms.rs` (~558) become negative tests. Each asserts the browser path is now refused (401/403/404), and the native path is unchanged.
- **New tests**: paired-bind Origin → 403, with native clients passing (ticket 04). The ports card opens the routed URL externally, the routed URL serves a remote dev server through 7332 after the card closes and the chat changes, and the watcher releases once services stop (ticket 07). Old settings with removed keys still load (ticket 08). The theme pin tests read the new path (ticket 02).
- **Previews, desktop (manual, ticket 11)**:
  - Windows 11, Chrome and Edge: run a Vite dev server from a local chat's terminal. Clicking it in the ports card opens `http://<device>.<project>.localhost:7331` in the default browser, no Roboco tab appears, and HMR updates on save.
  - Same for a chat on a paired engine over LAN (a Linux box), then over Tailcat. The URL is on `:7332`, it loads and HMR works. Close the card, switch chats, wait more than 30 s, then reload the tab: it still loads. Stop the dev server: the tab fails within the idle window, and `127.0.0.1:7332` stops listening afterwards.
  - Collision case: two paired engines with the same device name. Both URLs route (persistent label).
  - Linux desktop: the same flow with the system browser via `xdg-open` (Chrome or Firefox).
- **Previews, Android (CI + manual)**: `android.yml`'s `android-emulator` job runs `PreviewFlowTest` against `android_fixture` (unchanged). Manually on a phone: the renamed ⋯ row opens the panel, a LAN engine's server loads in the WebView, and so does one reached over Tailcat.
- **Pairing (manual, ticket 11)**:
  - LAN: `roboco engine pairing create --qr` → the Android in-app scanner pairs. The desktop pastes a `/pair#token=` link → it pairs. Fleet follow still introduces the other engines.
  - Tailcat: `roboco engine tailcat invite --qr` → the phone pairs over Tailcat. The desktop pairs with the same invite. `roboco engine tailcat connect` prints a loopback link the desktop accepts.
  - Rooms: `roboco engine room invite --qr` → the phone joins.
  - Refusals: `curl http://<engine>/` and `/pair` → 404. A WS upgrade without Bearer → 401. A request with an `Origin` header on the paired bind → 403.
- **Build/CI**:
  - `cargo build --release -p roboco` on a clean checkout with no Node, no `ROBOCO_WEB_DIST` and (on Linux) no WebKitGTK dev packages installed.
  - `cargo tree -i ts-rs` and `cargo tree -i rust-embed -p roboco-engine` both come back empty.
  - `cd apps/android && ./gradlew :app:testDebugUnitTest` passes.
  - One PR CI run with every remaining workflow green, plus a `release.yml` `workflow_dispatch` dry run that does not publish (only when the user says so; the v0.14.0 release must be finished first).

## Out of Scope

- **A hosted or static web client, a `/pair` landing page, or iOS/zero-install browser access.** All recoverable from `web-final`.
- **Upstream ports dropped from the to-do** (the wave-10 upstream spec must exclude them):
  - #813: the **Windows WebView2 in-app browser** slice only. #813's tool-call image previews, Copy path/image, selectable tool output, output-line cap and question-card slices stay in scope, and the path-jail slice has already landed.
  - #1039: Linux browser on WPE WebKit with dma-buf frames.
  - #814: Browser element annotations, including its proto change.
  - #573: persistent embedded browser profiles.
  - #1012: the **Browser half** only (refusing `C:\…` / `\\server\…` with a hint). The Codex `view_image` half stays in scope.
  - A34: the Linux `browser-fixture` compile/capture/flake fix (upstream #777's fixture part).
  - A35's web half: the web "Update check failed" copy. Android's "Sending…" wording stays in scope.
- **Android's WebView preview flow and `PreviewFlowTest`**: untouched apart from the label.
- **Wire, crate and RPC renames** (`WatchPreviews`, `PreviewSnapshot`, `crates/preview*`, `PREVIEW_CLIENT_PROXY_PORT`): frozen (ADR 0005).
- **Cleaning stale `tailcat-relays/` directories and browser sessions** from old data dirs.
- **Rewriting the 47 `web/…` reference comments** and archiving `.scratch/web-parity*`.

## Open questions

1. **The label for the servers list (ticket 09, needs-info): "Ports" or "Running servers"?**
   - **"Ports"**: the desktop card heading already says "Ports" (`session_info.rs:573`), so only Android (the ⋯ row, panel title and TalkBack pane title in `PreviewsCopy.kt`) and the docs change. It is short enough for a menu row and the "Ports · <chat>" title. It reads a little technical.
   - **"Running servers"**: more descriptive for non-developers, and it matches the rows (server name + `:port`). It also changes the desktop heading, and the panel title gets long ("Running servers · <chat>").
   - Recommendation: **"Ports"**. It is one word, it already ships on desktop, and it makes no claim about who started the server.

## User decisions (2026-10-28)

- **Label:** the servers list is called **"Ports"** everywhere (desktop already; Android ⋯ row + panel title; docs). Ticket 09 is no longer needs-info.
- The other spec choices stand as written: 403 for `Origin` on the paired bind (verified safe for previews: the relay dial carries only `Authorization`, see `crates/preview-relay/src/client.rs:123-136`), no cleanup of old browser-pairing data, `web/…` comments resolve at `web-final`.
- Implementation starts only on the user's explicit go.
