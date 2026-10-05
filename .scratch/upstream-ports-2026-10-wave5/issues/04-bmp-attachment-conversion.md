# 04 — Convert BMP attachments to PNG at staging

**What to build:** Port upstream `f9a4a18b` (#739): Windows puts
clipboard screenshots on the clipboard as BMP (gpui turns CF_DIB into
`ImageFormat::Bmp`) and no harness inlines BMP, so agents fall back to
their Read tool, which cannot decode one. `stage_file` and
`stage_clipboard_image` re-encode BMP as PNG and rename `.bmp` →
`.png` at staging, so the queued `pending://` refs, the upload, and
the transcript all see PNG. A file that won't decode is refused; a
pasted image that won't decode stays as pasted. Wallpapers keep their
original bytes and name through the new `stage_file_verbatim`.
Staging (file read + BMP decode) moves off the UI thread via
`Composer::stage_in_background`, and the result lands in the draft
that was current when the image arrived, even if the user navigates to
another chat while it converts.

**Roboco gap (verified):** staging carries BMP end-to-end
(`crates/ui/src/attachments.rs:203-218`, engine
`crates/engine/src/uploads.rs:443`) but the harness layer inlines only
png/jpg/gif/webp (`image_media_type`,
`crates/harness/src/claude/mod.rs:635-668`; BMP rides the path-ref skip
branch, also `crates/harness/src/pi/mod.rs:1563`); decode/re-encode
exists only for previews (`crates/ui/src/image_media.rs:190-232`). The
`image` 0.25.10 crate with bmp is already a ui dependency
(`crates/ui/Cargo.toml:78`) — the conversion is ui-side, harness
untouched.

**Care point:** the background-staging threading change in
`composer.rs` (+115 upstream) — the draft-current-at-arrival semantics
matter when the user switches chats mid-convert.

**Web:** N/A — web uploads already accept bmp
(`web/packages/app/src/lib/attachments.ts:95-104`); conversion is
desktop staging-side.

**Blocked by:** None.

**Status:** ready-for-agent

**Upstream SHAs:** `f9a4a18b` (#739) — `crates/ui/src/attachments.rs`
(+113), `crates/ui/src/composer.rs` (+115),
`crates/ui/src/settings.rs`, `settings/wallpaper.rs` (trivia) → same
paths here. Source: `.scratch/upstream-drift/2026-10-05.md` § #739.

**Verification budget:** deferred — wave-final batched pass:
`cargo nextest run -p roboco-ui --lib` (staging/conversion tests).

- [ ] BMP files and clipboard images become PNG at staging
      (`pending://` refs, upload, transcript all see PNG)
- [ ] Undecodable file refused; undecodable paste kept as pasted
- [ ] Wallpapers verbatim via `stage_file_verbatim`
- [ ] Staging off the UI thread; result lands in the draft current at
      arrival
- [ ] Port commit records the upstream SHA
