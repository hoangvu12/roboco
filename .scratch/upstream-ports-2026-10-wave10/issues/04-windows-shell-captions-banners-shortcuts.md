# 04 — Windows shell: Win10 caption icons, notification banners, Ctrl (not Win) picker shortcuts (A3, A4, A28)

**Lane:** 1b Fixes: Windows. **Effort:** S–M (three S pieces + a manual check).

**What to build:**
1. **A3 (open PR #781): blank caption buttons on Windows 10.** The
   minimize, maximize and close glyphs come from `Segoe Fluent Icons`
   (`crates/ui/src/shell.rs:6751`), which is a Windows 11 font. Paint the
   bundled caption SVGs (`crates/ui/src/icons.rs:198-201`, already used
   for Linux captions) when the font is missing, or always. Keep the
   hover/pressed states and the Maximize/Restore swap.
2. **A4 (open PR #662): no notification banners on Windows, only the
   chime.** `crates/ui/src/notify.rs:338-339` `post_impl` does nothing on
   Windows. The pinned zui (`0966d06`) already has
   `show_system_notification`, `set_app_identity` and
   `on_system_notification_response`. Post a banner, set the app
   identity (AppUserModelID, using a `sh.roboco.*` id per AGENTS.md),
   and make a click open the chat. Respect the existing Notifications
   settings and the quiet-while-focused rule. Engine-posted banners
   (window closed) are ticket 46, not this one.
3. **A28: model picker shortcuts check Win/Super instead of Ctrl.**
   "Star highlighted model" and the row picks fire on Win+…, and the
   hints show Mac ⌘ glyphs: `crates/ui/src/pickers.rs:3296,3362,3372,5020`
   and `crates/ui/src/pickers/compact.rs:211`. Use
   `modifiers.secondary()` and `settings::display_combo` /
   `badge_combo_on`. Upstream has the same bug, so this is Roboco's own fix.
4. **Manual check (`eabad36a2` #1013 note):** on a French AZERTY Windows
   layout, does Ctrl+1…9 jump-to-session fire? zui reports keys through
   `MapVirtualKeyW`. Record the result. If it fails, the fix is
   `KeyBinding::load(.., use_key_equivalents, keyboard_mapper)`, not
   upstream's macOS table. File that as a follow-up rather than growing
   this ticket.

**Upstream:** open PRs #781 and #662 (read per spec Default 4); A28 is
Roboco's own (pass2-big-3 on `a73fa8fe4` #471).

**Roboco files:** `crates/ui/src/{shell.rs,icons.rs,notify.rs,pickers.rs}`,
`crates/ui/src/pickers/compact.rs`.

**Excluded:** #662's macOS hunks, if any.

**Proto change:** no.

**Blocked by:** 01.

**Status:** ready-for-agent. The Win10 look and the AZERTY check need a
human on real hardware; the boxes say so.

**Source:** `2026-10-28.md` A3, A4, A28; `part-prs-B.md` #781/#662;
`pass2-big-3.md` gap 1; `pass3-skips-2.md` notes.

- [ ] Caption buttons render on Windows 10 (human check on a Win10 machine or VM) and Windows 11 is unchanged
- [ ] A Windows banner shows on done/input/failed; clicking it opens the chat; settings respected
- [ ] Picker star/row shortcuts use Ctrl on Windows/Linux; hints show `Ctrl` (unit test on the combo helper)
- [ ] AZERTY Ctrl+1…9 result recorded (human)
- [ ] Commit records PRs #781, #662 (head commits read) and the A28 own-fix note

## Comments
