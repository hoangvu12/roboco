# 06 — Attachments: raise the per-file cap to 100 MB

**What to build:** The "taildrop" payload half of the spec: no new file path,
just a bigger cap on the existing `UploadChunk`/`UploadCommit` pipeline.
Raise the client caps (`MAX_ATTACHMENT_BYTES`, 24 MB today) to 100 MB in
`crates/ui/src/attachments.rs` (desktop) and
`crates/mobile-client/src/attachments.rs` (Android), the web upload staging
(web/packages/app), and the engine cap (`MAX_BYTES`, 32 MB today) in
`crates/engine/src/uploads.rs` to 100 MB — all gated behind a new
`uploads-100mb-v1` capability string: clients use 100 MB only when the
engine advertises it, otherwise clamp to today's limits. Make the whole-file
deadline size-proportional (today's flat ≤900 s) so slow links still fit.
100 MB ≈ 200 chunks of ~510 KB — inside the 1 MiB relay frame budget and the
existing staging (`uploads/tmp`, `STAGING_TTL` sweep) and `pending://`
machinery, which are unchanged.

**Blocked by:** None — independent of the room tickets.

**Status:** claimed

**Parent:** `.scratch/rooms/spec.md`

- [ ] Engine `MAX_BYTES` 100 MB behind `uploads-100mb-v1`; capability added to the gate catalogue
- [ ] Desktop + mobile + web clients clamp to 100 MB only when the engine advertises the capability (24 MB otherwise)
- [ ] Whole-file deadline scales with size; slow-link upload of ~100 MB fits
- [ ] Resume/idempotency unchanged (positional `seq` writes); interrupted large uploads still resume within the staging TTL
- [ ] Engine upload tests at ~100 MB; mobile attachments tests (chunk count, concurrency); web upload health check included
- [ ] Staging sweep handles multi-hundred-MB dirs without blocking the listener

Research: `.scratch/research/2026-10-25-multi-engine-chats-and-taildrop.md`
(Q1 upload-path table), `.scratch/research/2026-10-21-video-attachments-and-web-upload.md`
(chunk/staging mechanics).
