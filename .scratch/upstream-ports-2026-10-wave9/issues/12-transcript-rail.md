# 12 — Transcript rail: prompts below the titlebar (upstream #958, `ddf931624`)

**What to build:** Port `ddf931624`: the transcript rail lands prompts below
the titlebar with one tick per prompt — `crates/ui/src/rail.rs` rework
(+385) + `transcript.rs`.

**Port note:** may lean on newer gpui APIs — run after ticket 11 and fix
against the new pin if needed.

**Excluded upstream hunks:** none.

**Blocked by:** 11 (compat check).

**Status:** ready-for-agent

**Verification budget:** `cargo check -p roboco-ui` + rail/transcript
tests. Source: `.scratch/upstream-drift/2026-10-25.md` § B2.

- [ ] Rail rework ported
- [ ] Commit records upstream SHA `ddf931624` (#958)

## Comments
