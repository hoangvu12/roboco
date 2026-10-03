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

**Status:** ready-for-human

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

- [x] Sheet Escape focuses the composer textarea on phone
- [x] Prop optional; no other sheet consumer changes
- [x] Test green; full app suite green

## Comments

**Implemented (ticket branch `ticket/mp-06-sheet-escape-focus`, commit
`d24e3226` + review pass).** `PickerCard`'s phone branch threads
`escapeFocusTarget` into `RbDrawerSheet`; the sheet's `sheetFinalFocus`
resolves the element-or-getter form into `Drawer.Popup`'s `finalFocus`
(Base UI's `finalFocus` union takes no plain element, so the adapter is the
type-correct form of the spec's `finalFocus={props.escapeFocusTarget}`;
a null/unset target falls back to Base UI's default, and the dialog arm's
`finalFocus` passes through verbatim). `drawerOnOpenChange` and every
dismissal path untouched.

Deliberate, spec-mandated divergence from the desktop reference: the sheet
forwards the target on every close (scrim/swipe/trigger press included),
not Escape-only like the popover arm's reason-aware split — the ticket's
mechanism line and "only thread the prop" note mandate plain forwarding.

**Verification evidence** (web/packages/app):

- `pnpm exec vitest run tests/picker-card-phone.test.ts` — 9/9: the
  getter-form case (the composer's exact wiring, `() => textareaRef.current`)
  and the plain-element case both land `document.activeElement` on the
  textarea after Escape dismissal; the no-target case keeps the default
  trigger-chip return.
- Sheet-family suites (`responsive-surface`, `settings-dialogs`,
  `settings-dialog`, `section-menu`, `right-pane`, `composer-reasoning`,
  `base-popover`) — 129/129.
- `pnpm exec tsc --noEmit` — exit 0.
- Full app suite `pnpm exec vitest run` — 146 files / 2196 tests, all green.
