# 06 — Phone sheet Escape returns focus to the composer

**What to build:** On the desktop-width arm, the picker's Escape returns
focus to the composer textarea through the popover's
`escapeFocusTarget` contract (`escapeFinalFocusTarget`,
positioning.ts:236-243, tested base-popover.test.ts:112-129; the composer
wires the textarea at composer.tsx:3637). The **phone sheet arm drops the
prop** (PickerCard.tsx:89-110 — ticket 49's spec skipped it), so phone
Escape returns focus to the trigger chip, not the textarea. Thread the
focus-return through the sheet: `PickerCard`'s phone branch passes
`escapeFocusTarget` into `RbDrawerSheet`, which forwards it as
`Drawer.Popup`'s `finalFocus` (the Drawer API supports it).

**Blocked by:** None (independent of ticket 01, though both touch the sheet
arm — land 01 first if sequencing matters, no hard edge).

**Status:** ready-for-agent

**Research:** `../research.md` §2 (focus-return item).

**Desktop reference (for lookups only):**
`crates/ui/src/pickers.rs:1130-1138` (Escape → `ReturnComposerFocus`).

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/components/ui/PickerCard.tsx` | edit | phone branch passes `escapeFocusTarget` to `RbDrawerSheet` |
| `web/packages/app/src/components/base/responsive-surface.tsx` | edit | `RbDrawerSheetProps` gains the prop; `Drawer.Popup` gets `finalFocus={props.escapeFocusTarget}` |
| `web/packages/app/tests/picker-card-phone.test.ts` | edit | assert focus lands on the escape target after sheet dismissal |

## 1. Notes

- Only thread the prop; do not change dismissal semantics
  (`drawerOnOpenChange` stays).
- Other sheet consumers are unaffected: the prop is optional and defaults to
  the current behavior (finalFocus undefined).

## 2. Tests

`picker-card-phone.test.ts` (the existing phone-arm idiom — read it first):
open a sheet with an `escapeFocusTarget` textarea, dismiss via Escape,
assert `document.activeElement` is the target. A case without the target
keeps the current behavior.

## 3. Acceptance checklist

- [ ] Sheet Escape focuses the composer textarea on phone
- [ ] Prop optional; no other sheet consumer changes
- [ ] Test green; full app suite green
