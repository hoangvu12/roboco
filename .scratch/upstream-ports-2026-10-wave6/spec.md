# Upstream ports 2026-10 wave 6: zeron v0.2.106 (9b377308 → 916cb1cc)

Status: ready-for-agent

Source: `.scratch/upstream-drift/2026-10-07.md` — the drift review of the
v0.2.106 window. The mirror was refreshed to `916cb1cc` after the ancestry
check passed (no-diff + tracking verified). Window: 13 commits, 13
non-merge, 0 merge; tags v0.2.104/v0.2.105/v0.2.106. Six port candidates;
four exclusions (three version bumps, iOS-only #826); three review-only
README commits.

## Problem Statement

Roboco main (`2493d2e1`) lacks, verified with file:line evidence in the
drift note:

1. **The sidebar project filter matches a single checkout** — wave-5
   ticket 11 landed the repository-identity substrate (`project_key`,
   `project_members`, `representative_space`), but the filter's rows,
   matching, and device tags are still per-checkout: `sidebar_chats`
   matches one space id (`crates/ui/src/state.rs:1959-1969`),
   `spaces_menu_rows` iterates `spaces_sorted()`
   (`crates/ui/src/shell/spaces.rs:2176`), row tags use
   `space_device_tag` (`spaces.rs:2864`, `:3100`). Upstream #811 fixed
   exactly this on top of the same substrate.
2. **Dated Claude snapshot IDs duplicate curated model rows** — our
   `with_discovered_models` dedups by exact id only
   (`crates/harness/src/claude/catalog.rs:224`, `:260`), so a CLI listing
   like `claude-haiku-4-5-20251001` adds a second "Haiku 4.5" row.
3. **Attachments are tiles, never chips** — no caret chips for staged
   files, no shared pill substrate (`crates/ui/src/composer/` has no
   `chip.rs`; `crates/proto/src/` has no `attachment_mentions.rs`),
   queue restore covers appshots only (`crates/ui/src/queue.rs:1368`),
   and the engine refuses to read back non-image uploads
   (`crates/engine/src/uploads.rs:357-358`).

## Solution

Three tickets under `issues/` (01–03). **Ticket 03 is PARKED** — do not
start it; see its cut condition. Tickets 01–02 are small, independent,
and can run off `main` any time.

Defaults (per wave-4/5 rhythm; the user can override in this spec):

1. **Execution:** one implementer session on branch `wave6/ports` off
   `main`, one commit per ticket, in ticket order (01, 02; 03 only after
   its re-triage). Merge into `main` only after the end-of-pass
   verification is green. Never push the branch; never move the mirror.
2. **Verification is deferred to one end-of-pass batched pass** — no
   cargo/pnpm/wiregen during ticket work; the tickets' "Verification
   budget" lines define WHAT the final pass covers. Build-cycle economy
   and box discipline as recorded in the wave-5 spec.
3. **Out of scope, by drift-note verdict:** #819 native voice (wave
   anchor behind a product decision — ADR 0003; #834 rides it and is not
   standalone-portable), the README trio (review-only), version bumps,
   iOS #826. No new `ZERON_*` identifiers appear in this window's
   retained code; the only rename mapping in play is crate paths
   (`zeron_proto::` → `roboco_proto::`).

## User Stories

1. As a multi-checkout user, I want the sidebar project filter to list
   one row per repository across devices — named for its representative
   checkout, tagged with every device holding a checkout — and filtering
   on it to show every checkout's sessions, so clones and worktrees of
   one repo filter together.
2. As a Claude user, I want dated snapshot model IDs to fold into their
   curated rows so the model picker shows one row per model.
3. As a composer user, I want pasted/dropped/attached files to appear as
   chips at the caret so my prompt can say which attachment it means,
   with removal, undo, and queued-message restore keeping chips and
   attachments in step — and pills that stay correctly padded and
   centered whatever interface font I use.

## Ticket table

| # | Ticket | Upstream | Size | Blocked by |
|---|---|---|---|---|
| 01 | Repository-identity project filter | `3d4bfd11` (#811) | 2 files, +210/−48 | — |
| 02 | Claude snapshot IDs fold into curated rows | `970f41fa` (#835) | 1 file, +24 | — |
| 03 | Composer attachment chips, font-robust | `73bd3c84` (#775) + `d5c1cdc1` (#816) | 33 files, +4286/−487 | PARKED — upstream settling |
