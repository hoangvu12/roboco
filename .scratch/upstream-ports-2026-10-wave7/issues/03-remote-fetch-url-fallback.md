# 03 — Engine: fall back to the fetch URL when a remote's push URL is disabled (upstream #863)

**What to build:** Port `73051ec7` (#863): when a git remote's push URL
is disabled but its fetch URL works, the engine's remote operations now
fall back to that remote's own fetch URL instead of falling back to
`origin`'s URLs or failing.

**Why now:** one file (`crates/engine/src/source_control.rs`, +46 with
tests), maps verbatim onto roboco's engine-local source control; no wire,
UI, or pin involvement. Newest commit of the window and the smallest
engine candidate.

**Steps:**
1. `git show 73051ec7` — single file, +46.
2. Port the fallback logic and its tests into roboco's
   `source_control.rs` near the existing remote-URL resolution.

**Excluded upstream hunks:** none — single file, wholly ours.

**Blocked by:** None.

**Status:** done (ported in fb08088f on wave7/ports)

**Verification budget:** `cargo check -p roboco-engine` + the ported
source-control tests. Source:
`.scratch/upstream-drift/2026-10-11.md` § `73051ec7`.

- [x] Fetch-URL fallback + tests ported
- [x] Commit records upstream SHA `73051ec7` (#863)

## Comments

Ported in fb08088f — roboco's remote-URL block matched upstream's
pre-change shape (including the pre-existing fetch-URL fallback), so the
port is the one `.filter(|url| parse_git_remote(url).is_some())` line
plus the test. Excluded: upstream's `repository_for_checkout` helper and
its assertion — #484 PR-board machinery, rides ticket 21. 28/28
source_control tests.
