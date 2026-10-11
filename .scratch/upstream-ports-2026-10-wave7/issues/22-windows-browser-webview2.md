# 22 — Windows browser: WebView2, tool image previews, selectable tool output (upstream #813 UI half)

**What to build:** Port the UI half of `e5466c53` (#813) after the pin
sync: browser tabs render pages inside the app on **Windows** (WebView2
in visual-hosting mode, DirectComposition visual in the zui overlay
plane's native layer — base GPUI content, then the page, then deferred
content in a second swap chain), matching macOS/Linux: fail-closed
navigation, policed subframes and external schemes, user-initiated
popups only, denied permission requests, web messages/host objects
disabled, per-window InPrivate profiles, browser-process-exit recovery.
Expanded tool calls preview the images they touch (freed on collapse);
right-click any transcript image copies its path or bytes; paths,
commands and output in expanded tool calls become selectable and
copyable; multi-line answers to agent questions grow the card.

**Why now:** roboco is a Windows-first product and its browser module
has native hosts for linux and macOS only (`crates/ui/src/browser/mod.rs`
— no `windows_webview.rs`); no `tool_images.rs`; tool output selection
exists only for message text. The engine path-jail slice is already
ticket 01.

**Steps (when unblocked):**
1. Land ticket 19 (pin sync) — the whole Windows browser rides zui#15 +
   `361ff20b` (`enable_scene_overlay`/`draw_layered`,
   `Window::native_composition`).
2. Port the `windows_webview.rs` host + the WebView2 dependency set
   (`webview2-com`, `windows` features, `raw-window-handle`) into
   `crates/ui/Cargo.toml`.
3. Port tool-image previews (`tool_images.rs` + the fixture example) and
   the selectable-output/card-growth hunks in `transcript.rs`.
4. Windows verification happens on a Windows host (this linux checkout
   can only check compile-gated surfaces — record what was verified
   where).

**Excluded upstream hunks:** `apps/ios`, `crates/client` hunks; the
engine jail (ticket 01).

**Blocked by:** 19. Windows-side verification needs a Windows host.

**Status:** needs-info (pin sync + Windows verification path)

**Verification budget:** `cargo check --workspace` on linux (gated
surfaces) + Windows build/test on a Windows host. Source:
`.scratch/upstream-drift/2026-10-09.md` § `e5466c53`.

- [ ] Pin sync landed (19)
- [ ] `windows_webview.rs` host + deps ported
- [ ] Tool image previews + selectable output ported
- [ ] Windows host verification recorded
- [ ] Commit records upstream SHA `e5466c53` (UI half)
