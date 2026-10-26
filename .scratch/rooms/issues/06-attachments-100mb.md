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

**Status:** resolved

**Parent:** `.scratch/rooms/spec.md`

- [x] Engine `MAX_BYTES` 100 MB behind `uploads-100mb-v1`; capability added to the gate catalogue
- [x] Desktop + mobile + web clients clamp to 100 MB only when the engine advertises the capability (24 MB otherwise)
- [x] Whole-file deadline scales with size; slow-link upload of ~100 MB fits
- [x] Resume/idempotency unchanged (positional `seq` writes); interrupted large uploads still resume within the staging TTL
- [x] Engine upload tests at ~100 MB; mobile attachments tests (chunk count, concurrency); web upload health check included
- [x] Staging sweep handles multi-hundred-MB dirs without blocking the listener

Research: `.scratch/research/2026-10-25-multi-engine-chats-and-taildrop.md`
(Q1 upload-path table), `.scratch/research/2026-10-21-video-attachments-and-web-upload.md`
(chunk/staging mechanics).

## Implementation evidence — 2026-10-25

- Branch `rooms-06` (commits `4e5ebcc40`, `4ba44e09e`, `32644102f`), merged
  into `rooms` as `743635d43`. Diff: 23 files, +1119/−130.
- Engine: `MAX_BYTES` 32 MB → 100 MB in `crates/engine/src/uploads.rs`,
  advertised via the new `uploads-100mb-v1` capability
  (`crates/proto/src/workspace.rs`, `CURRENT` + gate catalogue row in
  `docs/reference/remote-access.md`). Boundary fix found while testing:
  `append`'s streaming base64 estimate counted the final chunk's `=` padding
  as data, rejecting an exactly-100 MB file at its last chunk — the check now
  carries the 2-char slack; `commit` remains the exact authority (test:
  `append_enforces_the_100mb_cap_at_the_exact_decoded_boundary`).
- Clients (all three, same rule): `attachment_cap(engine_info)` returns
  100 MB iff `uploads-100mb-v1`, else the legacy 24 MB — enforced at send
  pre-flight AND inside the upload path (desktop `crates/ui`, mobile-client
  incl. `session/commands.rs` + FFI constant, web `attachments.ts`/
  composer). Whole-file deadline: 15 s/chunk slope kept, flat ceiling
  900 s → 3600 s (100 MB ≈ 206 chunks → ≈53 min, ~34 KB/s links fit);
  read-back guard `MAX_READ_CHUNKS` 1000 → 2400 so a max-size attachment
  reads back. Engine-client per-call deadline parity: upload RPCs route to
  the 900 s budget (the desktop's 30 s inner default would have fired first).
- Chunking/staging/resume untouched: 680 k b64-char chunks (~510 KB),
  positional `seq` idempotency, `STAGING_TTL` 10 min, `pending://` refs.
  Appshots deliberately decoupled at their own 24 MB cap (a screenshot is
  never the taildrop payload).
- Tests (implementer, worktree): engine 27 binaries green incl. a real
  100 MB upload round-trip (206 chunks, idempotent re-send, 2333-chunk
  hash-verified read-back) and a multi-hundred-MB sweep-timing test;
  mobile-client 194 lib + session_engine 21 (33 MB past the old engine cap,
  100 MB+1 rejected); ui 1678 headless; mobile 55 (FFI constant); web
  `pnpm -r build` green, app vitest 2535 passed (6 failures pre-existing,
  verified via stash on the clean tree), engine-client 36 unit + 7/7
  conformance against the real `web_conformance` engine incl. a 33 MB
  upload round-trip; wiregen `--check` fresh.
- Orchestrator gates (main checkout, merged tree): workspace `cargo build`
  green; 8-crate `cargo test` green apart from the environmental set
  (pi_*/previews/completion_notices — verified failing identically on the
  base commit `d13360e8f`; m5_repos stack-overflow flake passes in
  isolation); `pnpm -r build` green; app vitest 2535 + the same 6
  pre-existing; engine-client vitest (unit + conformance) 48/48; wiregen
  `--check` fresh. Android gate (FFI change): `:app:assembleDebug` builds
  the APK with the core + Go tailcat adapter on this aarch64 host via the
  provisioned toolchain; `:app:testDebugUnitTest` 2242/2242 non-Robolectric
  tests pass — the 2871 Robolectric-based tests cannot run on
  linux-aarch64 at all (Robolectric ships no aarch64 natives; host
  limitation, green in CI).
- Deviations (accepted): appshots keep 24 MB; desktop clamps at send time
  rather than drop time (staging is engine-agnostic); `STAGING_TTL` stays
  10 min — a stalled upload must resume within it, documented for the
  follow-up parking lot.
