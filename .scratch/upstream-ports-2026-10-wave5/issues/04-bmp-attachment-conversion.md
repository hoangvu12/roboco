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

**Status:** ready-for-human

**Upstream SHAs:** `f9a4a18b` (#739) — `crates/ui/src/attachments.rs`
(+113), `crates/ui/src/composer.rs` (+115),
`crates/ui/src/settings.rs`, `settings/wallpaper.rs` (trivia) → same
paths here. Source: `.scratch/upstream-drift/2026-10-05.md` § #739.

**Verification budget:** deferred — wave-final batched pass:
`cargo nextest run -p roboco-ui --lib` (staging/conversion tests).

- [x] BMP files and clipboard images become PNG at staging
      (`pending://` refs, upload, transcript all see PNG)
- [x] Undecodable file refused; undecodable paste kept as pasted
- [x] Wallpapers verbatim via `stage_file_verbatim`
- [x] Staging off the UI thread; result lands in the draft current at
      arrival
- [x] Port commit records the upstream SHA

## Comments

Ported upstream `f9a4a18b` (#739) near 1:1 — same four files, no
exclusions, no rebrand needed (identifiers unchanged).

- `crates/ui/src/attachments.rs`: `stage_file` now converts BMP->PNG
  (via new `bmp_to_png`, image 0.25.10 with bmp already a ui dep) and
  renames `.bmp`->`.png`; new `stage_file_verbatim` stages bytes as
  stored (wallpapers); `stage_clipboard_image` converts BMP pastes but
  keeps undecodable ones as pasted; undecodable BMP FILES are refused
  with "{name} is not a valid image.".
- `crates/ui/src/composer.rs`: `add_staged` replaced by
  `stage_in_background` (background executor; the draft current when
  the image arrived owns the result — key captured at spawn; failures
  surface in that draft's failure notice; queue-edit finishing still
  gates the insert). `PastedImages` and `add_paths` route through it;
  the file-drop regression test gained `cx.run_until_parked()`, and
  the new `staged_files_land_in_the_draft_they_were_added_to` test
  covers the navigate-mid-convert semantics (BMP -> "shot.png" in
  chat-a, nothing in chat-b, notes.txt filtered).
- `crates/ui/src/settings.rs` +
  `crates/ui/src/settings/wallpaper.rs`: background/wallpaper
  consumers switched to `stage_file_verbatim` (exact bytes + name).
- Roboco-specific `stage_file` callers (dev knobs
  `ROBOCO_DEMO_UPLOAD` in shell.rs, `ROBOCO_ATTACH` boot staging in
  composer.rs) intentionally keep the CONVERTING `stage_file`: they
  simulate user staging, not wallpaper reads.
- All four upstream tests ported verbatim (clipboard BMP fixture,
  pasted/file/verbatim, non-BMP untouched, undecodable refused/kept).

Web: N/A per ticket (web uploads already accept bmp). Verification
deferred to the wave-final batched pass.
