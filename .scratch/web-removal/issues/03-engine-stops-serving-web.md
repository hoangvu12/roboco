# 03 — Engine stops embedding and serving the web bundle

**What to build:** The engine binary no longer contains or serves a web app, and release builds no longer need one.

- `crates/engine/build.rs`: delete the web half, i.e. staging `web/packages/app/dist` or `ROBOCO_WEB_DIST` into `web-staging/` and the release-mode `panic!` when no bundle exists. Keep `embed_tailcat_adapter` and its `rerun-if` lines untouched.
- Delete `crates/engine/src/web.rs` and its `mod`/`use` sites. Remove `rust-embed` from `crates/engine/Cargo.toml` (`crates/ui` keeps its own `rust-embed`; it is not web). Remove the `web-staging/` line from `.gitignore`.
- Delete `crates/engine/assets/web/` (`index.html`, `pair.html`). The spec decides: no static landing page.
- `crates/engine/src/listener.rs`: remove `GET /`, `GET /pair`, the static asset routes, the SPA fallback, `web_page`, `web_static_asset`, `static_asset_cache_control` and `RESERVED_API_PATHS`. Unknown paths, including `/` (non-upgrade) and `/pair`, get the existing 404.
- Keep `ROBOCO_WEB_DIST` in the workflows for now, harmless and unread. Ticket 06 removes it together with the scripts that set it.
- Tests: in `crates/engine/tests/web_client.rs`, delete the embedded-page and static-asset cases. The CORS, Origin and first-frame cases go in 04. Delete the file in 04 once it is empty. Add one assertion (in `remote_listener.rs` or a new focused test) that `GET /` and `GET /pair` on the paired bind are 404, and that `GET /health` is unchanged.

**Blocked by:** 02

**Status:** ready-for-agent

**Parent:** `.scratch/web-removal/spec.md`

- [ ] `cargo build --release -p roboco-engine` succeeds with no `ROBOCO_WEB_DIST` set and no `web/packages/app/dist`
- [ ] `cargo tree -p roboco-engine -i rust-embed` is empty
- [ ] `GET /` and `GET /pair` → 404 on the paired bind. `/health`, `/pairing/redeem` and the Bearer WS are unchanged (`tests/pairing.rs`, `tests/remote_listener.rs` green).
- [ ] `build.rs` still embeds the Tailcat adapter (Tailcat pairing tests green on the platforms that run them)
- [ ] No `web-staging` reference remains outside `.scratch`

## Comments
