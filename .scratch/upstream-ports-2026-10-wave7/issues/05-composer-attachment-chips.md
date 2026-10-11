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

**Status:** ready-for-agent

**Verification budget:** `cargo check -p roboco-proto -p roboco-engine
-p roboco-ui` + chip/queue tests; proto additions may trip the wiregen
gate — run `cargo run -p wiregen -- --check`. Source:
`.scratch/upstream-ports-2026-10-wave6/issues/03-composer-attachment-chips.md`
(scope record), `.scratch/upstream-drift/2026-10-11.md` follow-up 2.

- [ ] Chips + pills substrate ported (composer, transcript, queue rows)
- [ ] `@` list pins staged attachments; both-ways removal sync + undo
- [ ] Engine serves non-image uploads through `read_chunk`
- [ ] #816 font-robust insets and `1390e621` intent folded
- [ ] Tests ported and passing; wiregen gate green
- [ ] Commit records upstream SHAs `73bd3c84` + `d5c1cdc1` (+ `1390e621` if folded)
