# 05 — Composer attachment chips, font-robust from the start (upstream #775 + #816) — supersedes wave-6 ticket 03

**What to build:** Port `73bd3c84` (#775) together with `d5c1cdc1`
(#816) as one ticket so chips arrive already font-robust. Pasting,
dropping or attaching a file stages it and drops a chip at the caret, so
a prompt can say which attachment it means: images read "Image N"; any
other regular file (up to the existing `MAX_ATTACHMENT_BYTES`) stages as
opaque bytes and its chip carries the file name and the file theme's
icon. Chips and attachments stay in step both ways: removing the last
chip for an attachment unstages it (undo brings both back), removing a
tile removes its chips; the `@` list pins staged attachments above
project files. One pill style is shared across files, folders, skills,
commands and attachments in the composer, the transcript and queue rows.
Non-image attachments render as file pills in sent messages and icon
tiles in queue rows, and a queued message with files can be restored for
editing — the engine's `read_chunk` serves back uploads of whatever type
(workspace roots stay image-only). The chip text flattens to the plain
label when a prompt reaches a provider, pairing with the upload of the
same name.

#816 rides along (all its hunks land in #775's new files): the padding
insets are pinned to Geist so they don't follow the UI font. Also
diff-check `1390e621` ("Keep composer chips steady across project
switches", from the deleted `git-identity-project-grouping` branch) and
fold its intent if the hunks are not already covered by the merged
superset.

**Why now:** wave-6 ticket 03's cut condition is met — the
`git-identity-project-grouping` branch is deleted from upstream, #775
and #816 are merged onto `upstream/main`, and the drift review
re-triaged the ticket to ready. Ticket 10 (#855 queue polish +
drag-to-mention) builds directly on this chip substrate
(`composer/chip.rs`, proto `chat_mentions.rs`).

**Steps:**
1. `git show 73bd3c84` (11 files, +652/−71… plus its own size) and
   `git show d5c1cdc1` — port as one unit: proto `chat_mentions.rs` /
   `invocation.rs`, `composer/chip.rs`, `composer.rs`, `queue.rs`,
   `shell/chat_dropzone.rs`, `shell/side_chats.rs`, `shell/spaces.rs`,
   `todo_panel.rs`.
2. Adapt: roboco's composer is a single `composer.rs` (no
   `composer/` module yet — create it for `chip.rs`), proto file names
   follow roboco's layout (`crates/proto/src/` has `file_mentions.rs`
   already; add `chat_mentions.rs` alongside), exclude any cloud/sync
   hunks by intent.
3. Engine: extend `read_chunk` serving to non-image staged uploads
   (workspace roots stay image-only) — coordinate with ticket 01, which
   moves `read_chunk` to the blocking pool; land 01 first.
4. Port the tests (chip lifecycle, undo, queue restore, font-robust
   insets).

**Excluded upstream hunks:** anything in `crates/client`, `apps/ios`,
`edge/*` (cloud/mobile surfaces); anything device-sync coupled.

**Blocked by:** None (ticket 01 should land first for the read_chunk
blocking move; not a hard edge).

**Status:** done (ported in 1f9ed06)

**Verification budget:** `cargo check -p roboco-proto -p roboco-engine
-p roboco-ui` + chip/queue tests; proto additions may trip the wiregen
gate — run `cargo run -p wiregen -- --check`. Source:
`.scratch/upstream-ports-2026-10-wave6/issues/03-composer-attachment-chips.md`
(scope record), `.scratch/upstream-drift/2026-10-11.md` follow-up 2.

- [x] Chips + pills substrate ported (composer, transcript, queue rows)
- [x] `@` list pins staged attachments; both-ways removal sync + undo
- [x] Engine serves non-image uploads through `read_chunk`
- [x] #816 font-robust insets and `1390e621` intent folded
- [x] Tests ported and passing; wiregen gate green
- [x] Commit records upstream SHAs `73bd3c84` + `d5c1cdc1` (+ `1390e621` if folded)

## Comments

Ported #775 + #816 as one unit on `wave7/chips` (off main, which already
carries wave 8). Carried: proto `invocation.rs` (`attachment_mention_prompt`
applied ahead of `file_mention_prompt` in `harness_prompt`/
`invocation_prompt`, plus the
`image_chips_reach_every_provider_as_their_plain_label` test), engine
`uploads.rs` `read_chunk` (files in the uploads dir read back as
`application/octet-stream`; workspace roots stay image-only) + the
`m5c_accounts_uploads_titles`/`queued_attachments` test deltas, and the
roboco-ui set: `composer/chip.rs` (new), `composer/attachment_chip_tests.rs`
(new, 24 tests), `composer.rs` (chip substrate: `AttachmentDraft` numbering,
`AttachmentRow` in the `@` list, chips↔attachment both-ways sync + undo,
queued-edit reconciliation with its own numbering/stash, sent-chip upload
progress, `OpenAttachment` event, `set_attachment_chips`/`insert_reference`
API), `attachments.rs` (`AttachmentContent`, file staging, `pair_with_chips`,
`unchipped_attachments`, `read_attachment_file`), `queue.rs` (icon tiles for
non-image attachments, chip rows, tiled = unchipped only), `transcript.rs`
(sent chips with overlays, `user_file_pill` for non-images, chip-driven
protected-attachment keys), `file_icons.rs` `raster`, `loaders.rs`
`upload_progress_arc`, `appshots.rs`, `shell.rs` (`seed_staged`), `pickers.rs`,
`files/mod.rs`, `icons.rs` + `gallery.svg`, `shell/chat_dropzone_tests.rs`
(external FileDropEvent coverage). #816's Geist-pinned padding +
cap-height chip centering came along (composer, chip.rs, transcript user
bubbles, queue rows).

`1390e621` diff-check: NOT covered by the 73bd3c84→d5c1cdc1 diff itself —
its intent reached upstream via dbb639be3 (#799, an ancestor of #775),
which roboco never ported — so its hunks were folded here: same-device
project switches keep the model/harness catalogs (no blanking chip), device
switches revalidate through `harnesses_stale`/`stale_models`, and the
checkout/branch chip labels hold the previous ref (`held_ref`) until the new
project's refs land; three new pickers tests cover it. (Roboco uses
`FOLDER_WITH_FILES` where upstream had `WORKTREE` in the two icon hunks —
kept roboco's icon.)

Adaptations beyond renames: roboco's `read_attachment_image` lacks
upstream's `expected_raster_mime` generated-image validation machinery (a
separate upstream feature roboco does not carry), so `read_attachment_bytes`
keeps roboco's 4-arg shape while still gaining #775's unconditional
read-size bound and the split image/file read-backs. `EngineTarget` (roboco)
replaces upstream `EngineHandle` in the attachments test; the `FileRpc`
read-back test builds an `EngineRegistry::test_local` target kept alive for
the call.

Excluded by intent: `crates/client` (cloud client), `apps/ios`, `.github/
workflows/macos.yml` (Apple CI), and the `crates/mobile` layout hunks — the
mobile chip rendering and `attachment_mentions.rs` proto file already landed
in roboco via the roboco-mobile layout port (b07a14767, android-app#05), so
nothing was missing on that side. `attachment_mentions.rs` itself needed no
changes (already identical modulo the `roboco-image:`/`roboco-attachment:`
scheme renames).

Verification: `cargo check -p roboco-proto -p roboco-engine -p roboco-ui`
clean; `cargo run -p wiregen -- --check` green (no wire types moved — the
proto delta is function-level); targeted suites green:
`composer::attachment_chip_tests` (24), `composer::` (201), `shell::
chat_dropzone_tests` (4, incl. the new external-drop coverage), `queue::
tests` (18), `transcript::` (138), `pickers::` (60, incl. the three 1390e621
tests), `attachments::` (28), proto lib (104), engine uploads (14),
`queued_attachments` (3), `m5c_accounts_uploads_titles` uploads test.
Note: `queued_row_waits_for_attachment_bytes_then_sends_resolved_paths` is
intermittently flaky on clean HEAD as well (verified via stash), so the rare
red there is pre-existing, not from this port.
