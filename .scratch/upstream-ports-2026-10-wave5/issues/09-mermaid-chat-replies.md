# 09 — Render Mermaid diagrams in chat replies

**What to build:** Port upstream `9e1a1115` (#760): assistant replies
draw ```mermaid fences as diagrams with the file preview's engine,
fence frame, source toggle, Copy action, and lightbox. Inline images
in chat keep their text rendering.

**Streaming safety (the core of the upstream design — carry it
faithfully):** never render a fence that may still be growing — only
blocks a later row of the same reply follows, or blocks of a completed
reply, request a diagram, so per-token commits start no render work.
Until a diagram is ready the fence shows its source; a failed render
keeps the source with the engine's diagnostic in a header marker.
Rows request their fences while laying out, so only painted diagrams
render, one at a time off the UI thread; requests whose rows scrolled
away are dropped. Results are retained under a byte budget,
least-recently-painted evicted first — the new
`crates/ui/src/markdown/mermaid_cache.rs` (366 lines upstream). A swap
remeasures only the rows painting it, using the existing layout
signals, so the bottom pin follows and the own-turn runway absorbs the
change without moving the sent prompt.

**Second hunk (bundle it — it fixes our files preview too):**
`decode_image` accounts prepared SVGs at their current raster, not the
largest possible (900×480 at 4x density) — upstream found this made a
177×348 class diagram count 7.6 MiB against the 64 MiB media budgets,
stopping the file preview at ~8 diagrams and starving the lightbox.
Port into `crates/ui/src/image_media.rs`.

**Roboco state (verified):** the mermaid substrate is already ours —
`crates/ui/src/markdown/mermaid.rs`, `mermaid-rs-renderer = "=0.3.1"`
pinned with `default-features = false`
(`crates/ui/Cargo.toml:80`, `THIRD_PARTY_NOTICES.md:21`), Rust-native,
embedded, offline; the render hook is `render_code_block`'s mermaid
branch gated on `opts.media.diagram` (`markdown/render.rs`). The gap
is transcript-side: `RenderOptions { media: None }` at
`transcript.rs:6713, 6774, 6791` — fences in replies render as plain
code blocks, and no `mermaid_cache.rs` exists.

**Web — extend the recorded divergence, do NOT add a renderer:** web
deliberately renders mermaid as source (`web/packages/app/src/
components/markdown.tsx:759-763`, recorded in
`.scratch/web-parity-fixes/spec.md:303`). Extend that divergence
record to cover chat replies; no Mermaid.js, no web diagram engine.

**Blocked by:** None (no lane overlap — wave 4 merged; the transcript
machinery is the work).

**Status:** ready-for-agent

**Upstream SHAs:** `9e1a1115` (#760) — 11 files, +1399/−142:
`transcript.rs` (+512), `markdown/mermaid_cache.rs` (NEW, 366),
`markdown/mermaid.rs` (+255), `markdown/render.rs` (+87),
`files/markdown_preview.rs` (restructure), `image_media.rs` (+88),
`image_viewer.rs` (+13), `attachments.rs` (+19),
`files/image_preview.rs`, `markdown/mod.rs`, `docs/markdown-preview.md`
→ same paths here. Source: `.scratch/upstream-drift/2026-10-05.md`
§ #760 (commit message quoted in full there).

**Verification budget:** deferred — wave-final batched pass:
`cargo nextest run -p roboco-ui --lib` (mermaid/cache tests); the
media-budget accounting fix rides the same suite.

- [ ] Mermaid fences in replies render as diagrams (frame, source
      toggle, Copy, lightbox)
- [ ] Streaming-safe: growing fences never start renders; completed
      replies and superseded blocks do
- [ ] Render-on-layout; scrolled-away requests dropped; one render at
      a time off the UI thread
- [ ] Byte-budgeted LRU cache (`mermaid_cache.rs`), failed renders
      keep source + diagnostic marker
- [ ] Swap remeasure preserves bottom pin + own-turn runway
- [ ] `decode_image` accounts SVGs at current raster (media budgets
      fixed for files preview too)
- [ ] Web divergence record extended; no web renderer added
- [ ] Port commit records the upstream SHA
