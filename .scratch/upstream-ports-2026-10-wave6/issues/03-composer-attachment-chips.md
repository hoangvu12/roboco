# 03 — Composer attachment chips, font-robust from the start (upstream #775 + #816) — PARKED

**PARKED — do not start.** Upstream is still iterating chips on the
unmerged `zeron/git-identity-project-grouping` branch (tip `c3af3110`,
15 commits ahead of upstream/main; "Keep composer chips steady across
project switches" is one of them). **Cut condition:** the next drift
review re-triages that branch; once its chips follow-ups have merged
(or the branch is declared settled), re-triage this ticket to
`ready-for-agent` and port the *merged superset* — not `73bd3c84`
blind. Until then this ticket is a scope record, not work.

**What to build (when cut):** Port `73bd3c84` (#775) together with
`d5c1cdc1` (#816) as one ticket so chips arrive already font-robust.
Pasting, dropping or attaching a file stages it and drops a chip at the
caret, so a prompt can say which attachment it means: images read
"Image N"; any other regular file (up to the existing
`MAX_ATTACHMENT_BYTES`) stages as opaque bytes and its chip carries the
file name and the file theme's icon. Chips and attachments stay in step
both ways: removing the last chip for an attachment unstages it (undo
brings both back), removing a tile removes its chips; the `@` list pins
staged attachments above project files. One pill style is shared across
files, folders, skills, commands and attachments in the composer, the
transcript and queue rows. Non-image attachments render as file pills
in sent messages and icon tiles in queue rows, and a queued message
with files can be restored for editing — the engine's `read_chunk`
serves back uploads of whatever type (workspace roots stay image-only).
The chip text flattens to the plain label when a prompt reaches a
provider, pairing with the upload of the same name.

#816 rides along (all its hunks land in #775's new files): the padding
runs are shaped in bundled Geist — counts tuned to Geist's 0.25em space
grow ~2.4× under monospaced UI fonts — while the label keeps the
interface font, and each chip row centers its pill on the label face's
cap middle from the shaped line's metrics, so pills sit right under
Noto Sans Mono and friends too.

**Scope:** 33 files, +4286/−487 combined upstream. Retained (~22 files,
~+3600 after exclusions):
- proto: new `attachment_mentions.rs` (+314), `file_mentions.rs` (kind
  now determines prefix char + is_dir — our `FileMentionLink`
  (`crates/ui/src/composer.rs:875-943`) carries both redundantly),
  `invocation.rs` (+22), `lib.rs`.
- engine: `uploads.rs` (+53 — the any-type read-back for the uploads
  dir only; today `crates/engine/src/uploads.rs:357-358` refuses every
  non-image), tests `queued_attachments.rs` (+88) and
  `m5c_accounts_uploads_titles.rs` (+16).
- ui: new `composer/chip.rs` (+153) and
  `composer/attachment_chip_tests.rs` (+792) — with #816's
  `CHIP_PAD_FAMILY`/Geist shaping/cap-centering folded in from the
  start; `composer.rs` (+1069 + #816's +167: chip insertion at the
  caret on stage, chip↔attachment sync, undo, `@`-list pinning,
  draft round-trips); `attachments.rs` (+429: regular files staged as
  opaque bytes, chip metadata); `transcript.rs` (+617 + #816's +97:
  sent-bubble file pills, upload strip); `queue.rs` (+247:
  restore-for-editing with files, icon tiles — today only appshots
  restore, `crates/ui/src/queue.rs:1368`); `loaders.rs` (+69),
  `file_icons.rs` (+23), `icons.rs` (+2), `files/mod.rs`, `pickers.rs`,
  `shell.rs`, `appshots.rs` (+10), `shell/chat_dropzone_tests.rs`
  (+121 — extends tests we hold from `a6322f2a`), `assets/icons/
  gallery.svg` (new).

**Excluded upstream hunks:** `crates/client/*` (2), `crates/mobile/*`
(5), `apps/ios/*` (5), `.github/workflows/macos.yml` (~10 files,
+282/−38 total). **No web changes exist upstream** (zero `web/` files
in either commit) — the web gets nothing and that is recorded, not an
omission.

**Care points:**
1. `transcript.rs` is our most-diverged UI file (drag previews, jump
   hints, mermaid rows from wave 5) — carry by intent, never by patch.
2. The prompt flattening (chips → plain labels) ports where our
   mention-prompt building lives (composer-side
   `file_mention_links`/invocation plumbing), not wherever upstream's
   `harness_prompt` helper ended up.
3. Wire surface: `invocation.rs` changes may trip `wiregen --check` —
   regenerate in the end-of-pass batch only, never during ticket work.

**Blocked by:** upstream settling (external) — see cut condition above.

**Status:** needs-triage

**Verification budget (when cut):** end-of-pass batched pass: `cargo
check --workspace --examples`, `cargo nextest run -p roboco-ui --lib`
(chip/attachment/dropzone suites), `-p roboco-engine` (uploads),
`-p roboco-proto`, `wiregen --check`. Source:
`.scratch/upstream-drift/2026-10-07.md` § `73bd3c84` (#775), §
`d5c1cdc1` (#816), § Follow-ups 2/4.

- [ ] Cut condition checked at next drift review (branch merged/settled)
- [ ] Merged superset ported (not blind `73bd3c84`)
- [ ] chip.rs + tests with #816 font-robustness folded in
- [ ] Engine any-type uploads read-back + tests
- [ ] Queue restore-for-editing with files
- [ ] Exclusions recorded in the commit (client/mobile/ios/macos.yml;
      web deliberately untouched)
- [ ] Commit records upstream SHAs `73bd3c84` (#775) + `d5c1cdc1` (#816)
      (+ any follow-up SHAs folded in at cut time)
