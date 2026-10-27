# 03 — Engine stops embedding and serving the web bundle

**What to build:** The engine binary no longer contains or serves a web app, and release builds no longer need one.

- `crates/engine/build.rs`: delete the web half, i.e. staging `web/packages/app/dist` or `ROBOCO_WEB_DIST` into `web-staging/` and the release-mode `panic!` when no bundle exists. Keep `embed_tailcat_adapter` and its `rerun-if` lines untouched.
- Delete `crates/engine/src/web.rs` and its `mod`/`use` sites. Remove `rust-embed` from `crates/engine/Cargo.toml` (`crates/ui` keeps its own `rust-embed`; it is not web). Remove the `web-staging/` line from `.gitignore`.
- Delete `crates/engine/assets/web/` (`index.html`, `pair.html`). The spec decides: no static landing page.
- `crates/engine/src/listener.rs`: remove `GET /`, `GET /pair`, the static asset routes, the SPA fallback, `web_page`, `web_static_asset`, `static_asset_cache_control` and `RESERVED_API_PATHS`. Unknown paths, including `/` (non-upgrade) and `/pair`, get the existing 404.
- Keep `ROBOCO_WEB_DIST` in the workflows for now, harmless and unread. Ticket 06 removes it together with the scripts that set it.
- Tests: in `crates/engine/tests/web_client.rs`, delete the embedded-page and static-asset cases. The CORS, Origin and first-frame cases go in 04. Delete the file in 04 once it is empty. Add one assertion (in `remote_listener.rs` or a new focused test) that `GET /` and `GET /pair` on the paired bind are 404, and that `GET /health` is unchanged.

**Blocked by:** 02

**Status:** done

**Parent:** `.scratch/web-removal/spec.md`

- [x] `cargo build --release -p roboco-engine` succeeds with no `ROBOCO_WEB_DIST` set and no `web/packages/app/dist`
- [x] `cargo tree -p roboco-engine -i rust-embed` is empty
- [x] `GET /` and `GET /pair` → 404 on the paired bind. `/health`, `/pairing/redeem` and the Bearer WS are unchanged (`tests/pairing.rs`, `tests/remote_listener.rs` green).
- [x] `build.rs` still embeds the Tailcat adapter (Tailcat pairing tests green on the platforms that run them)
- [x] No `web-staging` reference remains outside `.scratch`

## Comments

- 2026-10-28 (implementer): `build.rs` keeps only `embed_tailcat_adapter` (byte-for-byte, with its `rerun-if` lines). `src/web.rs`, `assets/web/{index,pair}.html`, the `rust-embed` dependency and the `web-staging/` gitignore line are gone. `crates/ui` keeps its own `rust-embed` (file icons).
- Listener: the page/asset/SPA block, `web_page`, `web_static_asset`, `static_asset_cache_control` and `RESERVED_API_PATHS` are gone. **Deviation, recorded:** the ticket says unknown paths get "the existing 404", but on the paired bind that 404 sat *behind* the session gate, so a credential-less `curl /pair` would have been a 401, while the spec and ticket 11 expect 404. The paired bind now answers 404 for anything that is not an engine route (`GET /health`, the `/` upgrade, the preview-relay upgrade) *ahead of* the gate. Session-gated routes are unchanged: `/health` without a session is still 401. The one visible side effect is a session-holding plain `GET /`, which was a 400 ("expected WebSocket upgrade") and is now a 404. The Local bind is untouched (`GET /` there is still 400).
- `cargo build --release -p roboco-engine` with no `ROBOCO_WEB_DIST` and no `web/packages/app/dist`: exit 0 (10m36s). Note: 04's edits were in progress while it compiled. `build.rs` was already in its final form, and ticket 11 re-runs the full release build at the head.
- `cargo tree -p roboco-engine -i rust-embed` → "package ID specification `rust-embed` did not match any packages" (not in the engine's graph).
- New test `remote_listener.rs::paired_bind_serves_no_pages`: `/`, `/pair`, `/index.html`, `/assets/app.js`, `/chats/abc` are 404 with and without a session. `/health` is 401 without a session and 200 with one. `web_client.rs` lost its embedded-page test, and its bare-`OPTIONS` assertion became 404 (the file goes in 04).
- `cargo test -p roboco-engine --test remote_listener --test pairing --test web_client --test preview_relay --test rooms --test room_relay` green. `cargo +1.99.0 check --workspace --all-targets` shows no errors and no new warnings vs the 02 baseline (the build script's "staged 2 entries" warning is gone).
- `git grep web-staging` outside `.scratch` → only the release.yml header comment, which ticket 06 rewrites.
