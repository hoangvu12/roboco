# 14 — Question panel: frost it like the composer pill

**What to build:** The question panel stood in for the composer pill but
was the only input surface painting a translucent fill without the
backdrop blur, so the transcript showed through unblurred. Wrap it in the
frosted surface (our `frost::frosted` + `MENU_BLUR`, upstream's glass
equivalent) and use the pill's composer-surface background so it matches
the pill and queue tray on every material and wallpaper tint; drop the
separate solid-input background token if one was added for it.

**Blocked by:** None.

**Status:** ready-for-agent

**Upstream SHAs:** `0fab030f` (#669) — 1 file, `crates/ui/src/composer.rs`.
Our frost module is `crates/ui/src/frost.rs` (upstream names it
`glass.rs` from #471; we already have the frosted helpers — map onto
them, do not create a second glass module).

**Verification budget:** `cargo check -p roboco-ui -j 3`; existing
question-panel/composer tests. Web: the question wizard
(`web/packages/app/src/lib/wizard.ts` + its panel styling) — verify the
panel is opaque/frosted against the transcript and fix the styling only
if it actually bleeds.

- [ ] Question panel frosted, matching the pill on all materials
- [ ] Token cleanup (no orphaned solid-input token)
- [ ] Tests green
- [ ] Port commit records upstream SHA

## Comments
