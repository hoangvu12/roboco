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

**Status:** done (manual smoke is in ticket 11)

**Parent:** `.scratch/web-removal/spec.md`

- [x] Ports-card click calls `cx.open_url` with the routed URL. No Browser surface is created on any platform.
- [x] A remote server keeps routing through 7332 after the card closes and the chat changes (test above)
- [x] The watcher releases (`watch(false)`) when services stop, the engine is forgotten, or the shell drops. The listener then idles out.
- [x] Local servers open their 7331 URL directly, with no watcher
- [ ] `cargo test --release -p roboco-ui --lib -- --test-threads=1` is green on Windows and in `ui-tests` (Linux)
- [ ] Manual smoke on Windows (full matrix in 11): a local Vite server and a LAN-paired engine's Vite server both open in Edge/Chrome. No blank tab.

## Comments

- 2026-10-28 (implementer): The row click is now `Shell::open_session_preview` (`shell/session_info.rs`): it closes the card, runs `route_session_preview` (which still ingests on click, so the shown and opened URLs agree), starts the opened-preview watcher for a remote chat, then calls `cx.open_url`. The `register_preview`, `set_surfaces_open` and `add_browser_surface` calls are gone. The tooltip reads `<detail> — opens in your browser`; GLOBE stays.
- Watcher (`OpenedPreviews` on `Shell`): one entry per `(EngineKey, chat)`, idempotent while running. Two deliberate shape choices:
  - **The task runs on the registry's tokio runtime, not as a gpui `Task`.** It needs no gpui state, and its idle timing is wall-clock. Under `TestAppContext`, gpui background timers use fake time, so a gpui-timer watcher never released in tests. The shell entry owns the `JoinHandle` and aborts it on drop, so the shell dropping ends it.
  - **The proxy's watch set is per engine, not counted** (`ClientProxy::watch_engine`). So the shell counts live watchers per engine, and only the last one calls `watch(engine, false)`. `ProxyWatch` is a drop guard, so every exit path (end, abort, shell drop) unwatches.
- Watcher behaviour: it subscribes `WatchPreviews {chatId}` and ingests every snapshot. Subscribe and recv are both bounded by a tick of `idle/2` (clamped to 50 ms…2 s), so an offline engine cannot stall the idle window. It ends when the chat's last snapshot has been empty, or the engine has been reported offline by the registry, for longer than the proxy idle window (`PreviewProxy::idle()`, new; 30 s in the app). It also ends when the engine leaves the registry. Retries after a stream end use the same tick.
- `PreviewProxy::start_on_port` (new): tests bind an ephemeral port, because this host's running Roboco holds 7332 (`netstat`: PID 9616). That was the first test failure.
- Tests: `crates/ui/src/shell/session_info/opened_preview_tests.rs` runs against a real paired engine (the `paired_fixture`, now served with the preview relay route). All four are green on Windows: `cargo +1.99.0 test --release -p roboco-ui --lib opened_preview -- --test-threads=1` → 4 passed, 20.4 s. They also run on Linux; Windows uses `started_at = 0` for the relay's process check, as the engine relay suite does.
  - `a_remote_row_opens_externally_and_keeps_routing_until_its_servers_stop`: the opened URL is `…localhost:7332` and no right tab exists. A GET through the bound port returns the remote dev server's body. After closing the card, switching chats and waiting 2× the idle window, it still serves with the same port. Reopening keeps one watcher. Emptying the catalog ends the watcher and the listener idles out (`status().port` → `None`).
  - `revoking_the_session_ends_the_watcher`: revoking alone does not cut a live connection, so the test also drops the bind (as the old pane test did), waits for the registry to report the engine offline, and then sees the watcher and listener release.
  - `forgetting_the_engine_ends_the_watcher`.
  - `a_local_row_opens_its_engine_proxy_url_without_a_watcher`: opens `service.url(proxy_port)` (7331), and no watcher is created.
- The old `browser/preview_proxy_tests.rs` pane test is superseded by these and is deleted with the browser in 08. Comment touch-ups: the 7333 browser-helper mentions in `proto/src/preview.rs` and `preview-relay/src/client.rs`.
- `cargo +1.99.0 check --workspace --all-targets` clean (see below). The full `roboco-ui --lib` suite runs after 08 (ticket 08 / 11), because 08 rewrites the shell surfaces this touches. Manual Windows smoke is in 11.
