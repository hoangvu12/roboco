# 07 — Ports card opens servers in the system browser (shell owns the proxy watcher)

**What to build:** Clicking a server in the desktop ports card opens it in the default browser and creates no Roboco tab. A paired engine's server keeps loading through the client preview proxy after the card closes. This lands **before** ticket 08 so previews never regress mid-PR.

**Today** (`crates/ui/src/shell/session_info.rs:600-607`): the row's click handler routes the URL (`route_session_preview`), calls `browser_context.register_preview`, opens the right pane and calls `add_browser_surface`. On Windows that opens the system browser *and* leaves a blank tab. Only `BrowserSurface::watch_previews` (`browser/mod.rs:312-464`) calls `PreviewProxy::watch(engine, true)`. Without a pane, nothing holds the 7332 listener, and it idles out after 30 s (`state.rs:2418`, `Duration::from_secs(30)`).

**Build**
- Row click: `close_session_info_overlay`. Then `let url = route_session_preview(...)`. For a remote chat, `ensure_opened_preview(engine, chat)` (below). Then `cx.open_url(&url)`. Remove the `register_preview`, `set_surfaces_open` and `add_browser_surface` calls.
- **Opened-preview watcher** on `Shell`: `opened_previews: HashMap<(EngineKey, String /*chat*/), OpenedPreview>`, where `OpenedPreview` holds a `Task`. Starting one is idempotent per key. The task:
  1. calls `proxy.watch(&engine, true)`;
  2. subscribes `WatchPreviews {chatId}` on that engine and `proxy.ingest`s every snapshot, keeping effective hostnames current across dev-server restarts and collision labels; it retries every 2 s on stream end, like `ensure_session_previews`;
  3. ends, calling `proxy.watch(&engine, false)`, when the chat's service list has been empty for longer than the proxy idle window (30 s), or the engine leaves the registry snapshot, or the shell drops.
  Unwatch must run on every exit path (a drop guard, not only the happy path).
- Local chats: no watcher. The URL is the engine's 7331 proxy URL as rendered today.
- Row affordance: the tooltip reads `<detail> — opens in your browser`. Keep the GLOBE glyph. Update the `render_session_ports` and `route_session_preview` doc comments ("opening it in your default browser", no "browser tab").
- The ports card already ingests on click, so the URL shown and the URL opened agree. Keep that.
- Leave `browser/` itself for ticket 08. Only the call sites in `session_info.rs` change here.

**Tests** (`crates/ui`, real engines over loopback like `browser/preview_proxy_tests.rs`, which ticket 08 deletes: move its `dev_server` / `listener` / paired-registry setup here)
- Clicking a remote row: no `RightSurface` is added, the opened URL (captured through a test seam on `open_url`, or by asserting the URL `route_session_preview` returns plus the watcher map entry) is `http://<effective-host>:7332/…`, and an HTTP GET to it through `127.0.0.1:<bound port>` returns the remote dev server's body.
- After closing the card and selecting another chat, plus waiting past the idle window (use a short test idle), the GET still succeeds.
- After the remote chat's services go empty (stop the fake listener), the watcher ends and the proxy's `status().port` returns to `None` after the idle window.
- Local row: no watcher is created, and the URL uses the snapshot's `proxy_port`.
- Revoking the engine's session or forgetting the engine ends the watcher.

**Blocked by:** 06 (ordering only; it touches no web code)

**Status:** ready-for-agent

**Parent:** `.scratch/web-removal/spec.md`

- [ ] Ports-card click calls `cx.open_url` with the routed URL. No Browser surface is created on any platform.
- [ ] A remote server keeps routing through 7332 after the card closes and the chat changes (test above)
- [ ] The watcher releases (`watch(false)`) when services stop, the engine is forgotten, or the shell drops. The listener then idles out.
- [ ] Local servers open their 7331 URL directly, with no watcher
- [ ] `cargo test --release -p roboco-ui --lib -- --test-threads=1` is green on Windows and in `ui-tests` (Linux)
- [ ] Manual smoke on Windows (full matrix in 11): a local Vite server and a LAN-paired engine's Vite server both open in Edge/Chrome. No blank tab.

## Comments
