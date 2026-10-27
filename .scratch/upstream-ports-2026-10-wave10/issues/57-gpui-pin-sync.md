# 57 — GPUI pin sync to upstream's current zui/gpui-component (supersedes wave-9 11 and wave-7 19)

**Lane:** 4b Features: desktop UI. **Effort:** M.

**What to build:** Upstream moved its pins again after wave-9 11 was
written. upstream/main `Cargo.toml` now pins zui
`b564a34413bb7ef1da04121c4a287758f94993d9` (zeronsh/zui#18: atlas tiles
updated in place / dma-buf import, display-rate frame callbacks in
inactive windows, Wayland frames after a forced draw) and gpui-component
`59f89243cea79dd70eb7a619b3930c374ef6958a`. Wave-9 11's target
`0dc69aa25` is an ancestor. Roboco pins `hoangvu12/zui` `0966d065…` and
`hoangvu12/gpui-component` `4764fd00…`. The local cargo cache shows
`0966d06` lacks `361ff20` (per-pixel rounded clip,
`Window::with_rounded_clip`), `0dc69aa` (drags need the button held + 4 px
of travel) and `b564a34`.

**Steps:**
1. On `hoangvu12/zui`, branch `roboco/sync-2026-10-wave10` from upstream
   `b564a34` (or, per spec question 2, from `0966d06` with only `361ff20`
   + `0dc69aa` cherry-picked). Re-apply Roboco's `0966d06`
   (transformed/blurred monochrome glyphs) on top. Its earlier fork work
   (`18a89af` drag threshold, `667d0aa` fades) is upstream already, so
   check it's not duplicated.
2. Mirror `zeronsh/gpui-component` `59f89243` to `hoangvu12/gpui-component`.
3. **Push both (outward action; needs the user's go-ahead)** and verify
   the revs exist.
4. Move the root `Cargo.toml` gpui pins **and** every
   `[patch."https://github.com/zeronsh/zui"]` rev together. The patch rev
   must equal the top-level pin rev (AGENTS.md: two GPUI copies give ~50
   type-mismatch errors).
5. Fix API drift in Roboco's tree. Update AGENTS.md § GPUI forks and the
   Cargo.toml comment block to the new revs.

**Upstream:** the zui/gpui-component revs above; upstream commits that
moved them: `cc7da5c2a` (#966) → `0dc69aa`, `afca583c1` (#484) →
`361ff20`, `c31124aa9` (#1039) → `b564a34`.

**Roboco files:** `Cargo.toml`, `Cargo.lock`, `AGENTS.md` (GPUI forks
section), any drifting call sites in `crates/ui`.

**Excluded:** nothing in Roboco consumes the dma-buf/WPE API. The
browser is being removed, so it's carried, not used.

**Proto change:** no.

**Blocked by:** 01. **Blocks:** 62, 63, 66, 67, 69, 70, 71, 72
(compat checks).

**Status:** ready-for-human (fork pushes; spec question 2)

**Source:** wave-9 `issues/11-gpui-pin-sync.md`; upstream/main
`Cargo.toml:78-116`; the local `~/.cargo/git/db/zui-*` history (checked
2026-10-28).

- [ ] Fork branches pushed; revs verified
- [ ] Pins and patch revs moved together; one GPUI copy (`cargo tree -d | grep gpui` empty)
- [ ] `cargo check --workspace` + ui suite green; `0966d06` glyph work survives (its tests or a fixture)
- [ ] AGENTS.md GPUI section updated
- [ ] Commit records the new revs and supersedes wave-9 11 / wave-7 19

## Comments
