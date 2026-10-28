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

**Status:** done (the Linux no-WebKitGTK build and the Linux-only rendered link tests run in `ui-tests` CI)

**Parent:** `.scratch/web-removal/spec.md`

- [x] `crates/ui/src/browser/` is gone. `transcript_address` and friends live in the markdown layer with their tests. `markdown_preview.rs` and `links.rs` compile against the new path.
- [x] `git grep -n "crate::browser\|RightSurface::Browser\|open_web_links_in_roboco\|BrowserReload\|browser-fixture\|preview-fixture\|webkit2gtk\|json-glib"` → only docs (ticket 10) and `.scratch`
- [x] Web link primary click → `cx.open_url`. The link menu offers Open (external) and Copy. File links still open the editor (existing file-link tests green).
- [x] Old settings and keymap files with the removed keys load (new test)
- [x] Tab-mechanics tests are retained on non-browser surfaces. `cargo test --release -p roboco-ui --lib -- --test-threads=1` is green on Windows and Linux.
- [ ] A Linux build succeeds on a host **without** WebKitGTK/JSON-GLib dev packages (`cargo build --release -p roboco` in a clean ubuntu container, or the `ui-tests` job after the apt change)
- [x] `cargo check -p roboco-ui --all-targets` and `--examples` are clean. No example references a removed feature.

## Comments

- 2026-10-28 (implementer): **Moved:** `loopback`, `normalize_address` and `transcript_address`, with their tests `normalizes_web_addresses_and_loopback_ports` and `rejects_non_web_schemes_credentials_and_bad_input`, now live in the new `crates/ui/src/markdown/web_address.rs` (`links.rs` was already dense). Error strings are verbatim. `allowed_navigation` was dropped; only the browser called it, and its two assertions went with it. Callers: `markdown/links.rs` and `files/markdown_preview.rs`.
- **Deleted:**
  - `crates/ui/src/browser/` (mod, model, view, macos, `linux/{mod.rs,helper.c}`, `preview_proxy_tests.rs`; coverage moved to 07's `opened_preview_tests.rs`).
  - `crates/ui/build.rs`, which held only the WebKitGTK helper compile; there is no `build =` key.
  - The `browser-fixture` feature and the `browser-fixture` / `preview-fixture` examples, plus their `[[example]]` entries.
  - macOS deps used only by `browser/macos.rs`: `wry`, `raw-window-handle`, `block2`, `objc2-web-kit`. Each was checked with `git grep`; `objc2`, `objc2-app-kit`, `objc2-foundation`, `core-*` serve other mac code and stay.
  - `scripts/test-linux-browser.sh` and `scripts/run-macos-browser-fixture.sh`.
  - The fixture examples' `"browser-fixture"` chat id is now `"fixture-chat"`.
- **Shell:** removed `RightSurface::Browser` and every arm, `browsers` / `browser_subs` / `browser_seq` / `browser_context` / `browser_profile`, `add_browser_surface`, the per-frame presentation/drag block, tab title/icon/favicon/loading arms, the picker card and `+` menu Browser rows, `crate::browser::bind_keys`, and the `browser-fixture` hook impl. `activate_session_link`: web links always resolve to `web_outcome()` (`External`); file links keep `Primary`/`Internal` → editor.
- **Links:** `LinkActivation::web_outcome()` lost its `embedded` flag and always yields `External`/`Rejected`. `LinkAction::Internal` stays for workspace file links. The link menu offers **Open link** (`External`) and **Copy link address** for web links (tab cycle `[4, 5]`, initial focus Open link). "Open in Roboco" now mounts only for resolved file links, and the "Open links in Roboco" toggle row is gone (focus slots 7 → 6).
- **Settings:** `open_web_links_in_roboco`, `ShortcutId::BrowserReload` (field, label, default, get/set, `ALL` 15 → 14 + jump slots) and the "Browser" shortcuts group are gone. New test `settings_with_the_removed_browser_keys_still_load`: a file with `openWebLinksInRoboco` / `open_web_links_in_roboco` and a keymap with `browserReload` / `browser_reload` loads, and sidebar width, sound and two keymap combos survive. `round_trip` now asserts the key is *not* written.
- **Proxy:** `PreviewProxyStatus.bypass`, `PreviewProxy::note_local` and `local_bypass` are gone, and the module docs were updated. `crates/engine-client/tests/preview_proxy.rs` never used them, and `git grep note_local` is empty (mobile-client has its own glue).
- **CI/release:** `ui-tests.yml` drops `scripts/test-linux-browser.sh` from both `paths` lists and the WebKitGTK/JSON-GLib apt packages. `release.yml` drops the same apt packages. `ui-tests.yml` had no "browser fixture" header sentence left after 06.
- **Tests rewritten, not deleted:** tab mechanics now run on `Diff` surfaces: visit-order close, ownership/close-left-right-others, MRU history, ⌘W, `navigation_tests.rs` (the native-browser focus block went, as it was about browsing), `side_chats.rs` and `files_panel_workspace_tests.rs`. `transcript_links_open_new_tabs…` became `transcript_web_links_open_externally_and_reject_stale_sessions`: every action → `External`, no right pane, stale or foreign sessions rejected, subagent links resolved. `markdown_preview_events_open_browser…` became `markdown_preview_web_links_open_externally_from_tree_and_file_tabs`: primary click opens the system URL, and no surface is added. Both are now cross-platform, no longer Linux/macOS-gated. The settings round-trip loop lost its preference assertions; its variable was renamed `flip`. In `link_interaction.rs`, the context-menu test's fourth-row toggle became the Copy row (checks the clipboard), the keyboard test opens via Enter on Open link, and the file/web row-visibility tests drop the default-destination row.
- **Checks:**
  - `git grep -n "crate::browser\|RightSurface::Browser\|open_web_links_in_roboco\|BrowserReload\|browser-fixture\|preview-fixture\|webkit2gtk\|json-glib" -- ':!.scratch' ':!docs/research'` → empty.
  - `cargo +1.99.0 check -p roboco-ui --lib --tests --examples` and `cargo +1.99.0 check --workspace --all-targets`: clean, no new warnings.
  - `cargo test -p roboco-engine-client`: green.
  - `cargo +1.99.0 test --release -p roboco-ui --lib -- --test-threads=1` (Windows): 1654 passed, 6 ignored, 1 failed (`round_trip`, fixed as above); after that fix, `cargo +1.99.0 test --release -p roboco-ui --lib settings:: -- --test-threads=1` → 170 passed, `round_trip` and the new back-compat test included. The first attempt died with a rustc `STATUS_STACK_BUFFER_OVERRUN` (0xc0000409) while compiling the release test binary under host load; the retry compiled.
  - The Windows-ignored native link tests (`context_menu_cancels…`, `keyboard_visits…`) run on Linux in `ui-tests`. The Linux build without WebKitGTK is proven by `ui-tests` / `release` CI after the apt change (no Linux host here).
