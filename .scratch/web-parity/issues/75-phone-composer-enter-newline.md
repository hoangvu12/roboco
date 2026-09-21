# 75 — Phone composer Enter inserts a newline

**What to build:** Pressing the phone keyboard's Enter/return key inserts a line break in the focused composer text field instead of sending a message. Sending remains available through the existing Send/Queue button. Desktop-width Enter preferences, completion acceptance, IME composition and modified shortcuts keep their existing behavior.

**User report (verbatim):** "when I press enter on my phone using the web version, it should breakline, not actual submit"

**Blocked by:** None — can start immediately. Coordinate the shared `composer.tsx` file with 74; this ticket owns only Enter handling, phone policy and the textarea keyboard hint.

**Status:** done
**Type:** task
**Baseline:** `web-parity/wave-2` @ `37c354ff`.
**Research:** `../research-2026-09-20/followup-composer-input.md` §§1–5. Required tables are copied below.
**Desktop reference (for lookups only):** `crates/ui/src/composer.rs::message_enter_bindings` (:1364-1389), `enter_outcome` (:1407), `message_enter_bindings_cover_both_platform_modifiers` (:8330). Desktop has no phone layout; phone newline is the user's explicit web behavior requirement.

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/components/composer.tsx` | edit | Existing media-hook import/use, Enter branch :2391-2439, shared textarea :2727-2745, phone wizard explicit advance wiring :1756-1813,2471-2475,2825 |
| `web/packages/app/src/lib/composer-send.ts` | edit if extracting decision logic | Small pure Enter-action decision or phone policy alongside `messageEnterBindings`; preserve existing public default behavior |
| `web/packages/app/tests/composer-send.test.ts` | edit | Table-driven phone/desktop Enter regression, preserving desktop binding guards |
| `web/packages/app/tests/wizard.test.ts` | edit | Commit-before-advance regression for multiline phone input and typed overrides using the production action helper if extracted |
| `web/packages/app/src/state/media.ts` | read only | Existing `useIsPhone`, not a new breakpoint owner |
| `web/packages/app/src/components/composer/wizard.tsx` | read only | Existing explicit wizard advance control; no new button/UI needed |
| `.scratch/web-parity/issues/75-phone-composer-enter-newline.md` | append | Browser/phone evidence and validation in Comments |

## 1. Context a fresh session needs

- `Composer` owns one controlled textarea and its key handler. The same text field is reused by the harness's wizard UI, so a phone policy inserted after the wizard branch would still submit some phone text fields.
- Current ordering is IME guard, Escape/Tab/arrows, Enter completion acceptance, wizard Enter behavior, modified submit, and bare Enter preference.
- `onKeyDown` exits for `nativeEvent.isComposing` at :2342. A selected completion consumes Enter at :2394-2398. Keep both before the phone newline decision.
- The ordinary bare Enter path at :2430-2435 calls `preventDefault()` and `submit()` at every width. This directly explains the report; the fix is not a server/RPC issue.
- The app already has one responsive definition: `useIsPhone()` returns the live `(max-width: 768px)` media match. Use this contract rather than UA, `keyCode`, pointer type or an inferred mobile OS.
- This boundary also means a narrow desktop window gets the phone layer and a phone landscape viewport above 768px gets the desktop layer. Record those outcomes honestly; changing the global breakpoint/device classification is outside this ticket.
- `ComposerSendBehavior` is a stored preference. Its desktop choices are `enter` and `modEnter`. Derive the phone behavior without overwriting that setting.
- Modified submit sends content, or activates the latest queued row if the composer is truly empty. It must never turn into Stop.
- Explicit Send/Queue/Stop buttons already implement eligibility, uploads, optimistic echo, queue edits and requests. Reuse those paths without alteration. The wizard advance button needs a phone-specific correction: its current callback advances without first committing the shared draft to the wizard.
- Native textarea default handling performs newline insertion and cursor replacement correctly. Returning without `preventDefault` is preferable to appending "\n" to React state.
- An `enterKeyHint` attribute describes the virtual keyboard label; the actual key handler must also change. Source: [MDN enterkeyhint](https://developer.mozilla.org/en-US/docs/Web/HTML/Reference/Global_attributes/enterkeyhint).
- Runtime mobile keyboard behavior was not exercised during this research. A synthetic event returning the right branch is necessary evidence for logic, not proof of native newline insertion.

## 2. Spec

### 2.1 Complete keyboard policy (verbatim research table)

| Focus / condition | Phone layer (<=768px) target | Desktop-width target | Evidence / basis |
| --- | --- | --- | --- |
| IME composing | Leave event to IME; never submit | Existing behavior | `composer.tsx:2341-2343`; user report 8 |
| Enter with selected completion | Accept completion once; never submit as a second action | Existing behavior | `composer.tsx:2391-2398`; `composer.rs:1407,8384` |
| Bare Enter in ordinary textarea, no completion | Native newline, regardless of saved Enter-to-send preference | Saved `enter` submits; saved `modEnter` inserts newline | User report 8; existing policy `composer.tsx:2415-2438`, `lib/composer-send.ts:181-194` |
| Bare Enter while editing a queued draft | Native newline; leave edit open | Existing desktop policy | Same shared textarea and user report 8 |
| Bare Enter in focused wizard borrowed textarea, no completion | Native newline; do not advance wizard | Existing wizard submit policy | Shared input at `composer.tsx:2722-2745,2820-2827`; current wizard Enter at `2400-2413` |
| Shift+Enter / Alt+Enter, no completion | Native newline | Existing native newline | `composer.tsx:2437-2438` |
| Ctrl/Cmd+Enter in message textarea, no completion | Preserve ModifiedSubmit: send content, or activate latest queued row when truly empty | Same | `composer.tsx:2418-2428`; `lib/composer-send.ts:83-88` |
| Ctrl/Cmd+Enter in wizard input | Preserve existing suppression | Same | `composer.tsx:2403-2406` |
| Explicit phone wizard advance (tap or unfocused panel Enter) | Commit current trimmed text to wizard before advancing; cancel pending auto-advance timer so the action occurs once | Preserve current desktop behavior | `composer.tsx:1756-1767,1806-1812,2471-2475,2825`; current button path otherwise discards uncommitted text |
| Enter on other focused buttons / outside textarea | Existing button behavior | Same | No global keyboard interception |
| Explicit Send / Queue / Stop button | Existing action, eligibility and RPC path | Same | `lib/composer-send.ts:25-30,64-71`; `components/composer.tsx:2897-2920` |
| Phone media query changes while input remains mounted | Recompute policy immediately, retain draft/focus/selection and saved preference | Resume saved desktop policy above 768px | `state/media.ts:25-46` |
| Virtual keyboard action label | `enterKeyHint="enter"` on phone textarea | Preserve current desktop attribute behavior | MDN enterkeyhint is a label hint, not an event-policy replacement |

The focused-wizard row is a consistent interpretation of the user's request for phone text entry: Enter adds text; the existing wizard advance button remains the explicit action. Its phone callback must commit the current text first. This ticket does not alter the wizard's desktop context or other buttons' Enter behavior.

### 2.2 Handler order and native behavior

Within the existing handler, implement this order:

1. IME composing: return, preserving its existing native event handling. No submit, completion acceptance or manual newline injection.
2. Existing Escape/Tab/arrows remain as-is.
3. Enter with a selected completion: accept it exactly once, consume the event, return.
4. Phone textarea bare Enter, with no Ctrl/Meta/Alt/Shift: allow native newline and return before both wizard and message submit branches. Stop propagation if needed to isolate parent actions, but do not prevent the newline default.
5. Remaining wizard and modified-submit policy stays as it is.
6. Desktop-width bare Enter follows the saved preference; Shift/Alt Enter remains native newline.

The same rule applies to new-chat drafts, established chats, queue-edit drafts, empty drafts and drafts containing attachments/comments. A return key is never a send/queue/edit-finish action on the phone in these no-completion cases.

Do not set the textarea value manually in keydown. Let its existing `onChange` publish the native edit so insertion in the middle of text, selection replacement, undo and IME do not diverge.

Use the shared media hook at render time so resizing the viewport updates the handler policy without remounting input. Saved settings must be unchanged when the viewport crosses 768/769px.

### 2.2.1 Preserve a working phone wizard submission path

The current panel receives `onAdvance={wizardAdvance}` at :2825. That callback calls `wizard.advance()` and clears the shared draft without `wizard.setTyped` (:1756-1767). Only `wizardSubmitFromInput` (:1806-1812) currently commits the text; removing its Enter trigger without replacing the explicit action would lose phone answers.

For phone explicit advance, clear any pending auto-advance timer and invoke the existing `wizardSubmitFromInput` commit-then-advance sequence. Wire the panel's advance prop and the unfocused panel Enter path (:2471-2475) to the same phone action; retain their desktop paths. Commit the trimmed current text even when empty, so a prior typed override cannot leak into an option-only answer. `Wizard.answers()` already gives nonempty typed text precedence over picked options (`lib/wizard.ts:127-138`). Preserve internal newlines, existing option selection, page navigation and button eligibility. Do not add another button.

Record this callback change as part of the phone behavior, not as a claim that the existing wizard button already submitted typed input. Verify one advance per action, including when an option auto-advance timer was pending.

### 2.3 Layout, children, motion and copy

| Aspect | Contract | Source |
| --- | --- | --- |
| Layout / children | Existing shared textarea and existing actions/wizard controls; no added button or wrapper | `composer.tsx:2722-2745,2820-2827,2862-2920` |
| Input hint | Phone textarea gets `enterKeyHint="enter"`; desktop remains as currently rendered | User report 8; MDN enterkeyhint |
| Composer growth | Native newline triggers existing onChange and expansion rules | `composer.tsx:2733,794-805`; `lib/composer-flip.ts` |
| Motion | Existing expansion and reduced-motion behavior only; no new animation or timing constant | `composer.tsx:836-850` |
| Text | Existing labels and placeholders unchanged; no new settings or notices | Current composer render |
| RPC | Enter newline causes no submission-related request | User report 8 |

### 2.4 Data and ownership (verbatim research table)

| Concern | Contract | Source |
| --- | --- | --- |
| Draft ownership | Controlled textarea plus existing per-chat drafts; no chat/storage migration | `composer.tsx:488-509,2727-2745` |
| Settings | Read existing `ComposerSendBehavior`; phone override is derived in memory, never saved over the user's desktop preference | `composer.tsx:326-328,2256-2263`; `lib/composer-send.ts:181-194` |
| Phone detection | Import existing `useIsPhone` from `state/media.ts`; no UA sniffing or new pointer heuristic | `state/media.ts:25-46` |
| Send operations | Native newline invokes no Run, QueueMessage, queue-edit finish, wizard response, optimistic echo or attachment upload | User report 8; current submission dispatch at `composer.tsx:2411,2424,2435` |
| Layout calculation | Existing content/strip measurement and live dock frame; DOM styling only, no engine RPC | `composer.tsx:725-938` |
| Native input | Reuse the same textarea through compact/expanded and borrowed wizard states; preserve composition and selection | `composer.tsx:2722-2745`; `composer.rs:8045-8088` |

Layout rows are invariants from 74. This ticket implements no geometry changes.

## 3. Pure logic to port and regression design

No phone behavior exists to port from Rust. Preserve its desktop policy while adding the user-directed web exception.

The existing `messageEnterBindings(behavior, modifierCombo)` returns exactly two bindings: one bare Enter action and one modified submit. Its callers/tests may rely on that shape, so preserve its default signature/semantics if it is reused.

A small pure Enter-action resolver in `lib/composer-send.ts` is acceptable to make this precedence testable without mocking a giant Composer. If used, the production Enter block must call it as the single decision owner. Its inputs must represent phone state, composition, selected completion, wizard context, modifiers and saved behavior. Outputs should distinguish native handling from consumed actions, so the renderer cannot accidentally prevent native newline while selecting the correct action. Do not create an unused test-only helper.

| Test case | Observable result |
| --- | --- |
| Phone + saved enter + bare Enter | Native handling; submit/queue/edit/wizard callbacks remain untouched |
| Phone + saved modEnter + bare Enter | Same native handling |
| Phone + selected completion | Exactly one completion acceptance; no send and no second newline action |
| Any width + composing | No app action; composition owns event |
| Phone + focused wizard textarea + bare Enter | Native newline, zero advance/response calls |
| Phone + multiline wizard draft + explicit advance | Commit current text, advance once, final answer preserves internal newline |
| Phone + selected option + typed override + explicit advance | Typed answer wins; draft is committed before page movement |
| Phone + emptied typed override + explicit advance | Clear stored typed override and use selected option, with no stale text |
| Phone + unfocused wizard panel Enter | Same commit-before-advance action as the explicit button; one advance |
| Phone + Shift/Alt Enter without completion | Native handling remains |
| Any width + Ctrl/Cmd Enter with content | Existing modified submit |
| Any width + Ctrl/Cmd Enter truly empty | Existing activate-latest-queued action, never Stop |
| Desktop + saved enter / modEnter | Existing submit / newline distinction |
| Live media boundary 768 -> 769 -> 768 | Policy updates without mutating saved setting or clearing text |

Existing desktop regression names must remain green:

- Rust `message_enter_bindings_cover_both_platform_modifiers` (:8330) -> existing web `the setting picks the bare-Enter policy; the modifier combo is verbatim` (`tests/composer-send.test.ts:108`).
- Rust `message_enter_never_adds_extra_modifier_bindings` (:8373) -> existing web `exactly two bindings, no shift/alt variants` (:127).
- Rust `enter_accepts_a_completion_before_submit_or_newline` (:8384) -> preserve the Enter-completion precedence in the phone matrix.
- Existing web `content submits; a truly empty composer activates the latest queued row` (:92) must remain unchanged.
- Existing `wizard.test.ts:111` test `a trimmed typed answer wins; back pages are bounded` remains green. Add coverage of the production phone advance action, not just a test that manually calls setTyped correctly.

The package uses Vitest node and `tests/*.test.ts` (`vitest.config.ts:4-5`). Do not assert native default insertion using a synthetic KeyboardEvent in node/jsdom: browsers perform that edit as part of real keyboard input. Use an actual running browser/device for the next section. No new DOM dependency is required for the pure policy tests.

## 4. Gaps this ticket closes

| Item | Kind | Desktop value | Web value (file:line) | Fix |
| --- | --- | --- | --- | --- |
| Phone Enter | Confirmed behavior mismatch with user directive | Desktop uses configured Enter binding; no native phone counterpart | `composer.tsx:2430-2435` submits bare Enter at every width | Phone-only native-newline branch before submit/wizard dispatch, retaining composition/completion precedence |
| Phone virtual-key label | Browser hint | Not applicable to native desktop | Textarea at `composer.tsx:2727-2745` has no enterKeyHint | Add phone `enter` hint alongside actual event-policy change |
| Shared borrowed textarea | Required consistency | Desktop wizard Enter advances | `composer.tsx:2400-2413` advances on bare Enter even on phone | Focused phone textarea inserts newline; explicit wizard controls still advance |
| Wizard explicit advance after newline change | Confirmed companion requirement | Existing desktop Enter commits typed input | `composer.tsx:1756-1767,2825` button path advances without committing text | Phone button/unfocused panel Enter must commit text before advance; preserve option fallback and cancel duplicate auto-advance |

## 5. Do not

- Do not persist `modEnter` into user settings to approximate a phone-only override.
- Do not use Enter keyCode 13/229 or UA sniffing as the primary mobile/IME policy.
- Do not append a newline manually, move the selection yourself or remount the textarea.
- Do not intercept Enter globally; buttons, picker navigation and other text fields retain their own behavior.
- Do not route newline through submit/queue or change their payloads, idempotency or optimistic echoes.
- Do not change Rust desktop behavior or invent a new mobile preference screen.
- Do not fix composer padding/motion here; 74 owns it.
- Do not start a server/browser/long-running process from an implementation subagent. Runtime evidence comes from an already authorized app or coordinator capture.

## 6. Acceptance

- [ ] On a phone soft keyboard, pressing Enter in a multiline composer inserts exactly one newline and sends no message.
- [ ] Caret in the middle of a draft and replacement of a selected range behave like a native textarea; undo remains correct.
- [ ] An empty draft, attachment-only draft and comment-only draft do not submit on phone bare Enter.
- [ ] While a run is working, bare Enter edits the draft and does not queue, stop, steer or activate a queued message.
- [ ] Queue-edit mode remains open after Enter; only explicit finish/submit controls finish the edit.
- [ ] Focused wizard borrowed input inserts newline; explicit advance and unfocused panel Enter commit typed multiline answers before advancing, with no stale option/typed override or duplicate advance.
- [ ] IME confirm does not send; selected completion acceptance remains a single action.
- [ ] Tapping Send/Queue still performs its existing action exactly once; Stop behavior remains unchanged.
- [ ] At desktop widths both saved Enter preferences and Ctrl/Cmd+Enter still work as before.
- [ ] Media changes at 768/769px update policy without changing saved settings, draft, focus or selection.
- [ ] Phone keyboard displays an appropriate return/newline label where the browser honors enterKeyHint; label-only success is not counted as functional success.
- [ ] Record browser/OS, viewport and keyboard for Android Chrome and iOS Safari when available; mark any missing platform as pending, not tested. Include one real soft keyboard, not only desktop emulation.
- [ ] Add meaningful policy tests to `composer-send.test.ts` and retain existing desktop binding tests.
- [ ] From `web/`: `pnpm -r build`; from `web/packages/app/`: `pnpm test`.
- [ ] No implementation/runtime verification is claimed solely from this ticket's source research.

## Comments

### 2026-09-20 — implemented on `wp-fu/75`

**What landed:**
- `lib/composer-send.ts`: new `resolveEnterAction(context)` — the Enter branch's single decision owner, taking phone/composing/completion-selected/wizard/modifiers/saved-behavior and returning `imeNative | acceptCompletion | nativeNewline | wizardSuppress | wizardSubmit | modifiedSubmit | submit`, in §2.2's exact precedence. A phone bare Enter resolves `nativeNewline` before both the wizard and message submit branches, at every saved preference; the saved `ComposerSendBehavior` is read, never mutated. `messageEnterBindings`/`platformModifierCombo` signatures and semantics unchanged (desktop guards stay green).
- `lib/wizard.ts`: new `wizardCommitThenAdvance(wizard, typedText, advance)` — the commit-then-advance sequence (composer.rs:5984-5992's `on_submit` wizard arm): trimmed text committed to the CURRENT page (even empty, so a stale typed override cannot leak into an option-only answer), then the host advance runs exactly once; internal newlines preserved.
- `composer.tsx` (owned regions only): `useIsPhone()` from `state/media.ts` read at render (live 768px match; resize re-arms the policy without remounting the input or touching the draft/saved setting); the Enter branch now switches on `resolveEnterAction` — the phone bare-Enter arm returns WITHOUT `preventDefault` (native textarea default performs the newline/selection-replacement/undo) and only `stopPropagation()`s to isolate the wizard panel; desktop `enter`/`modEnter`, completion acceptance (exactly once), IME guard, wizard Mod+Enter suppression and ModifiedSubmit (submit content / activate latest queued, never Stop) are behavior-identical to before. The removed `messageEnterBindings` memo folded into the resolver (its desktop guards remain as direct lib tests). Textarea gets `enterKeyHint={isPhone ? "enter" : undefined}` (label hint only; desktop renders no attribute, as before).
- Phone wizard explicit advance (§2.2.1): new `wizardAdvanceCommit` = `clearAdvanceTimer()` + `wizardSubmitFromInput` (which now routes through `wizardCommitThenAdvance`); wired to the panel's `onAdvance` (`isPhone ? wizardAdvanceCommit : wizardAdvance`) and the unfocused panel Enter — desktop paths retained verbatim. No new button/UI.
- Tests: `composer-send.test.ts` 17 → 27 (new table-driven phone/desktop matrix: phone bare Enter at both saved preferences, focused-wizard phone Enter, queue-edit invariance, Shift/Alt native at phone, Mod+Enter preserved at any width, mod+alt drop, composing at any width, completion precedence on phone, wizard suppression both widths, desktop wizard submit, desktop saved-preference distinction, live 768↔769 policy flip with the saved setting untouched). `wizard.test.ts` 14 → 18 (production-helper regressions: multiline commit preserving internal newlines with nothing leaked forward, typed override beating a picked option, emptied commit clearing a stale override so the option answers, exactly one page move with an option auto-advance pending).

**Verification:** `pnpm -r build` (web/) passes; `pnpm test` (web/packages/app) passes: 90 files, 1477 tests (baseline 1463 + 14 new). `tests/registry.test.ts`'s known 1ms parallel-load flake appeared once in a full run (`expected 41 to be less than 40`) and passed in isolation and in the final full run — recorded as the known flake.

**Pending runtime evidence (not claimed):** per the hard rules no dev server, browser or device was started. Real soft-keyboard behavior — single native newline insertion per return press, caret-mid-draft insertion, selected-range replacement, undo — is unverified on Android Chrome and iOS Safari (viewport/OS/keyboard matrix pending, not tested); synthetic keydown in node cannot prove the browser's native default edit. `enterKeyHint="enter"` label honoring is browser/IME-dependent and likewise pending. IME-confirm and selected-completion single-action behavior on real phone keyboards pending. The 768/769px policy flip is covered at the pure-decision level; live re-render with retained draft/focus/selection across the boundary is pending a browser. Boundary honesty: a narrow desktop window ≤768px gets the phone newline layer and a phone landscape viewport >768px gets the desktop submit policy — the shared `useIsPhone` contract, unchanged by this ticket.
