# 11 — GPUI pin sync: zui `0dc69aa25` / gpui-component `6ffe5e575` (supersedes wave-7 ticket 19)

**What to build:** Sync the GPUI pins to the revs upstream moved to in #966:
zui `0dc69aa254841738b1e84ac743fa0aa435ad71b1`, gpui-component
`6ffe5e575bd0db31d69764773a823cdeb8913d8e`.

**Steps:**
1. Sync the `hoangvu12/zui` fork to `zeronsh/zui` `0dc69aa25` (branch
   `roboco/sync-2026-10-…` off the fork's current `roboco/sync-2026-10`,
   fast-forward to upstream's rev), push, verify the rev exists.
2. Update the root `Cargo.toml` gpui pins **and** every
   `[patch."https://github.com/zeronsh/zui"]` rev together — the patch rev
   must equal the top-level pin rev (AGENTS.md rule: two GPUI copies
   otherwise, ~50 type-mismatch errors). Pin `gpui-base` to
   `hoangvu12/gpui-component` at the mirror-equivalent rev.
3. Fix any API drift in roboco's tree; our `0966d06` custom glyphs (zui
   `0966d065` added transformed/blurred monochrome glyphs — fork work
   beyond upstream) must survive or be re-carried on top of `0dc69aa25`.

**Why:** wave-7 ticket 19's target (`361ff20b`/`701ca97a`) is stale — #966
moved it. Tickets 12 and 17 (and #966's ui hunks) may need the new APIs.

**Blocked by:** none. **Supersedes:** wave-7 ticket 19 (pin sync); its
downstream tickets 20/22 stay gated on this one.

**Status:** ready-for-agent

**Verification budget:** `cargo check --workspace` (one GPUI copy), ui
suite. Source: `.scratch/upstream-drift/2026-10-25.md` § GPUI pins moved.

- [ ] zui fork synced + pushed; pins and patch revs moved together
- [ ] Workspace checks green on the new pins
- [ ] Commit records the new revs and the supersession of wave-7 19

## Comments
