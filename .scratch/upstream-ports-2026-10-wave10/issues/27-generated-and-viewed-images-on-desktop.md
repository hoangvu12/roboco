# 27 — Images in the transcript: Codex-generated images on desktop, Codex `view_image` shown (A18, #1012 harness half)

**Lane:** 1d Fixes: sidebar + chat. **Effort:** M.

**What to build:**
1. **A18: Codex-generated images never render on desktop.** Android
   shows them (`crates/mobile/src/layout/rows.rs:431`). On desktop,
   `crates/ui/src/transcript.rs:1725-1728` is a no-op
   `MessagePart::Image { .. } => {}` ("not ported yet"). The engine,
   harness, proto and doc parts were ported in `010747d03`.
   `db407106e` left `decode_generated_image` out with no user decision.
   Port the desktop renderer: `render_generated_image` plus
   `image_media.rs`, decoding off the UI thread, aspect-correct scaling,
   opening in the lightbox, and eviction like other transcript images.
2. **#1012 harness half: Codex `view_image` calls are invisible.** The
   model "sees" an image the transcript never shows. Add the `imageView`
   thread-item arm in `crates/harness/src/codex/normalize.rs`, mapped to a
   ReadFile-style tool, plus the `fake-codex.sh` fixture arm and test.

**Upstream:** `9601a2440` (#367) desktop slice; `189115f36` (#1012)
Codex half.

**Roboco files:** `crates/ui/src/transcript.rs`, new
`crates/ui/src/image_media.rs` (or the existing attachments image
path), `crates/harness/src/codex/normalize.rs`,
`crates/harness/tests/{codex.rs,fixtures/fake-codex.sh}`.

**Excluded:** #1012's browser half (`local_file_address`; in-app browser
removal). #367's iOS/edge slices.

**Proto change:** no.

**Blocked by:** 01. Ticket 12 also edits `codex/` (separate functions).
Ticket 51 (#1036 Markdown images) reuses this renderer.

**Status:** ready-for-agent

**Source:** `2026-10-28.md` A18, §B #1012; `part-00.md` `9601a2440`;
`part-07.md` `189115f36`.

- [ ] A Codex image-generation turn shows the image on desktop; the lightbox opens it (UI test with a fixture image)
- [ ] `view_image` shows as a tool row (codex fixture test)
- [ ] Commit records `9601a2440` (desktop slice) and `189115f36` (Codex half; browser half excluded)

## Comments
