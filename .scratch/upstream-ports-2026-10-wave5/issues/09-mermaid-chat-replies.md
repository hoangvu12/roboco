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

**Status:** ready-for-human

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

- [x] Mermaid fences in replies render as diagrams (frame, source
      toggle, Copy, lightbox)
- [x] Streaming-safe: growing fences never start renders; completed
      replies and superseded blocks do
- [x] Render-on-layout; scrolled-away requests dropped; one render at
      a time off the UI thread
- [x] Byte-budgeted LRU cache (`mermaid_cache.rs`), failed renders
      keep source + diagnostic marker
- [x] Swap remeasure preserves bottom pin + own-turn runway
- [x] `decode_image` accounts SVGs at current raster (media budgets
      fixed for files preview too)
- [x] Web divergence record extended; no web renderer added
- [x] Port commit records the upstream SHA

## Comments

Ported upstream `9e1a1115` (#760) — diff stat matches upstream per file
(mermaid_cache.rs NEW 366, transcript.rs +512, mermaid.rs +255,
render.rs +89, image_media.rs +88, markdown_preview.rs restructure ±175,
image_viewer.rs +13, attachments.rs +19, image_preview.rs +1, mod.rs +1,
docs +14). Our files were byte-identical to upstream's pre-change
modulo rebrand, so the port is near-mechanical.

- `markdown/mermaid_cache.rs`: the byte-budgeted LRU cache verbatim
  (64 MiB / 64 entries, latest-two-passes eviction protection, theme
  generation invalidation, row-tracked requests, source toggles,
  `frame_row` id scheme). Linux-gated test helpers preserved.
- `markdown/mermaid.rs`: Roboco diagram style — transparent canvas
  (`Palette::plate` = bg blended with ink 0.035), node/group/label/
  line/border/grid/accent_line/accent_wash palette, denser layout
  (node_spacing 36 / rank 40 / padding 18x10), gantt accent hues,
  `restyle` finishing pass (transparent canvas rects dropped, rx 3->8,
  accent-tinted default diamonds, themed gantt gridlines, explicit
  style/classDef colors preserved), `diagram_keyword`. Two tests
  ported (rebranded name). `ROBOCO_MERMAID_ARTIFACTS` env var kept.
- `markdown/render.rs`: `DiagramView` (Source/Failed/Diagram) replaces
  the direct `DiagramUi` return; `MediaUi.image` is now `Option` (None
  keeps chat's inline-image text rendering); the failed marker
  (`code_notice`, DANGER_TRIANGLE + max-w-360 tooltip);
  CodeBlockTooltip carries SharedString.
- `transcript.rs`: the whole streaming-safe pipeline — settled-row
  media (later row of the same entry proves block completion; tail
  keeps source; per-token commits start no render), the shared
  `diagram_media` handler, the one-at-a-time background worker with
  between-render job selection, `finish_diagram` ->
  `diagram_layout_changed` (row-scoped remeasure + viewport revision +
  pin wake + own-turn kick), theme/style-generation invalidation,
  paint-pass-scoped requests with column/density rasters, the diagram
  lightbox (enlarged within the retained budget, plate fill, natural
  size framing, raster release on close), `cx.on_release` cleanup, and
  source toggles retained by row identity in sync(). The three
  upstream transcript tests ported (streaming tail, pinned bottom
  through swap, runway stability + lightbox) — our transcript test
  module's with_window/tick/feed helpers match upstream's shapes.
- `image_media.rs`: SVGs account their CURRENT raster
  (`svg_retained_bytes`), not the largest-possible (900x480@4x);
  `preview_within` re-checks the budget before a larger re-raster;
  `preview_element` extracted from markdown_preview. Budget-upgrade
  test ported. This fixes the files preview too (~8-diagram stop).
- `files/markdown_preview.rs`: `resize_media` budget-aware
  re-rasterization; `media_element` plate parameter + preview_element
  reuse; DiagramUi -> DiagramView::Diagram; image handler Optional.
- `attachments.rs`/`image_viewer.rs`/`image_preview.rs`: `PreviewImage
  .plate` + `with_plate`; the viewer draws the plate behind
  transparent-canvas images; the lightbox passes it through.

Web — divergence record extended, no renderer added (per the wave
spec): `markdown.tsx`'s mermaid source-only doc comment now covers
chat replies, and `.scratch/web-parity-fixes/spec.md`'s Out of Scope
"mermaid rendering" entry names both surfaces.

Docs: `docs/markdown-preview.md` — the style paragraph, the
media-accounting paragraph, and the new "## Mermaid in chat" section
(rebranded "Zeron's own style" -> "Roboco's own style"). Upstream's
two process-record "follow-up" sections were NOT carried: they record
upstream's verification commands/results, which our wave-final pass
replaces with our own.

Verification deferred to the wave-final batched pass
(`cargo nextest run -p roboco-ui --lib`).
