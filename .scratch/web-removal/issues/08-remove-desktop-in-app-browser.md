# 08 — Remove the desktop in-app browser

**What to build:** Delete the browser pane on every platform. Web links open in the system browser. Move the link-parsing helpers that markdown still needs.

**Move first (same commit)**
- `browser::model::{transcript_address, normalize_address, loopback, allowed_navigation}` and their unit tests (`normalizes_web_addresses_and_loopback_ports`, `rejects_non_web_schemes_credentials_and_bad_input`) move to `crates/ui/src/markdown/links.rs`, or a new `crates/ui/src/markdown/web_address.rs` if `links.rs` gets crowded. Drop `allowed_navigation` if nothing outside the browser calls it after the move. Callers: `files/markdown_preview.rs:81` and `markdown/links.rs:13`. Error strings stay verbatim, since tests and the link menu may match on them.

**Delete**
- `crates/ui/src/browser/` entirely: `mod.rs`, `model.rs` (after the move), `view.rs`, `macos.rs`, `linux/{mod.rs,helper.c}` and `preview_proxy_tests.rs` (its coverage moved in 07). Remove `pub mod browser` from `lib.rs`.
- `crates/ui/build.rs`: the WebKitGTK helper compile. If nothing else remains, delete `build.rs` and its `build =` key (check the Cargo manifest).
- `crates/ui/Cargo.toml`: the `browser-fixture` feature, the `[[example]]` entries `browser-fixture` and `preview-fixture`, and the macOS-only `wry`, `objc2-web-kit` and any other mac deps used *only* by `browser/macos.rs`. Check each with `git grep` before removing, because `objc2-app-kit` etc. may serve other mac code. Remove the dev-deps comment "browser pane preview tests" (07's tests may keep `hyper`/`bytes`).
- `crates/ui/examples/browser-fixture.rs`, `crates/ui/examples/browser-fixture/` (`linux.rs`, `transcript_links.rs`, `transcript-links.md`) and `crates/ui/examples/preview-fixture.rs`. Also strip `browser-fixture` mentions from `command-palette-fixture.rs`, `new-project-fixture.rs` and `sidebar-fixture.rs`.
- `scripts/test-linux-browser.sh`, `scripts/run-macos-browser-fixture.sh`.

**Shell (`crates/ui/src/shell.rs` and friends)**
- `RightSurface::Browser(u64)` and every arm: `browsers`, `browser_subs`, `browser_seq`, `browser_context`, `add_browser_surface`, `active_browser`-style helpers (~15708-15734), the presentation/drag passthrough (~12164-12193), tab icon/label arms (~11143-11163) and the close/MRU arms.
- The `+` menu / surface picker "Browser" rows (~10993, ~11511).
- `crate::browser::bind_keys` (~649) and the browser key context (`mod-l`, `mod-t`, `mod-w`, `mod-[`, `mod-]` under `"Browser"`).
- `activate_session_link` (~3444): web links resolve to `LinkAction::External` always (Primary → External). File links keep `Internal` → editor.
- Rewrite every test that used `RightSurface::Browser` as a generic tab (~13672-13777, ~15139-15598, `navigation_tests.rs:390`, `side_chats.rs:494-504`, `files_panel_workspace_tests.rs:393`) onto `Diff` or `Terminal` surfaces. Keep the assertions about close-left/right, MRU and ownership. Delete only tests that are about browsing.

**Settings and links**
- `settings.rs`: remove `open_web_links_in_roboco` (field, default, load/merge ~1750, tests ~2069/~2954). Remove `ShortcutId::BrowserReload` (`browser_reload` field, label, default combo, getters/setters, `ALL`). Remove `settings/shortcuts.rs:897`'s "Browser" group.
- **Back-compat test:** a settings JSON containing `"open_web_links_in_roboco": false` and a keymap with `"browser_reload": "mod-shift-r"` loads without error, and the other values survive.
- `markdown/link_interaction.rs`: remove the "Open in Roboco" action row for web links and the "Open links in Roboco" toggle (~480-530) and their tests (~989-995, ~1286 for web links). Outside-file links keep their rows; rename that test if its name mentions Roboco-opening of web links. `markdown/render.rs` / `links.rs`: `web_outcome` loses its `embedded` flag. A web `Internal` is no longer produced.
- `shell.rs` ~14461-14560 and ~15144-15199: the open-links-in-Roboco settings tests go. Retarget their terminal/code font-family assertions if those were incidental.

**Dead proxy plumbing (`crates/engine-client/src/preview_proxy.rs`)**
- `PreviewProxyStatus.bypass` and `PreviewProxy::note_local` fed only WebKitGTK's bypass list. Remove them and the `local_bypass` field, update the module docs (lines 1-13 mention "the Linux WebKitGTK proxy configuration"), and adjust `crates/engine-client/tests/preview_proxy.rs`. Android uses `crates/mobile-client`'s own glue, so check that it never calls these (`git grep note_local`).

**CI and release**
- `.github/workflows/ui-tests.yml`: remove `scripts/test-linux-browser.sh` from both `paths` lists, `libwebkit2gtk-4.1-dev libjson-glib-dev` from the apt line, and the header comment's "browser fixture" sentence.
- `.github/workflows/release.yml`: remove `libwebkit2gtk-4.1-dev libjson-glib-dev` from the linux apt line.
- Leave `docs/` to ticket 10.

**Blocked by:** 07

**Status:** ready-for-agent

**Parent:** `.scratch/web-removal/spec.md`

- [ ] `crates/ui/src/browser/` is gone. `transcript_address` and friends live in the markdown layer with their tests. `markdown_preview.rs` and `links.rs` compile against the new path.
- [ ] `git grep -n "crate::browser\|RightSurface::Browser\|open_web_links_in_roboco\|BrowserReload\|browser-fixture\|preview-fixture\|webkit2gtk\|json-glib"` → only docs (ticket 10) and `.scratch`
- [ ] Web link primary click → `cx.open_url`. The link menu offers Open (external) and Copy. File links still open the editor (existing file-link tests green).
- [ ] Old settings and keymap files with the removed keys load (new test)
- [ ] Tab-mechanics tests are retained on non-browser surfaces. `cargo test --release -p roboco-ui --lib -- --test-threads=1` is green on Windows and Linux.
- [ ] A Linux build succeeds on a host **without** WebKitGTK/JSON-GLib dev packages (`cargo build --release -p roboco` in a clean ubuntu container, or the `ui-tests` job after the apt change)
- [ ] `cargo check -p roboco-ui --all-targets` and `--examples` are clean. No example references a removed feature.

## Comments
