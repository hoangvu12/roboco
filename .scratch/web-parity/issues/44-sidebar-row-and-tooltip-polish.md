# 44 — Sidebar row and tooltip polish: archived-row pill reveal, view-options tooltip dismissal

**What to build:** After this ticket, the archived row's Unarchive pill
appears only while the row is hovered — exactly one right-slot child at a
time, the same reveal the active chat rows already use and the desktop has
always had — so it no longer paints permanently on touch-primary devices
(`hover: none`) or stays pinned after a click (Link focus). The "Sidebar
view options" tooltip still shows after a 350ms hover, but now dismisses
when the pointer leaves (plus Escape and other defensive paths) instead of
sticking forever.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

**Research:** `../research-2026-09-19/spaces-sidebar-mirroring.md` S4 (all
four parts — §a web trace, §b desktop reference, §c root cause, §d gap
rows), S6 (all four parts), "Desktop-only items NOT to port" (the gpui
tooltip library and the in-memory `archived_hover`/`chat_status_hover`
bullets), "Open design questions" #1 (the touch affordance — this ticket
ships the desktop-parity default it describes).

**Desktop reference (for lookups only):**
Archived row — `crates/ui/src/shell/spaces.rs:1654` (the per-row
`archived_hover` state), `:1669-1712` (the right slot: ONE element chosen at
render), `:1677-1704` (the Unarchive pill's geometry), `:1729-1739` (the
row's hover listener). Active row — `crates/ui/src/shell.rs:4461`
(`chat_status_hover`), `:4522-4560` (the Archive pill), `:4622-4641` (the
corner wrapper: the 14px height pin, the NO-occlude decision, the click
handler attached only while hovered), `:4677-4690` (the row hover listener
driving both the wash blend and the swap). Tooltip —
`crates/ui/src/shell/spaces.rs:63-80` (`SidebarViewOptionsTooltip`, the
label card), `:1101-1156` (the 29×29 trigger), `:1150-1151` (gpui's
`.tooltip(…)` with `.tooltip_show_delay(350ms)` — auto-dismiss on leave).

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/components/archived-section.tsx` | edit | `ArchivedRow`'s right slot (:137-169) — per-row hover state, exactly one child; rewrite the stale both-mounted comment (:137-141) |
| `web/packages/app/src/styles/app.css` | edit | **delete** the `:focus-within` swap (:9337-9346) and the `@media (hover: none)` pin (:9352-9360); `.arch-row-unarchive`'s base (:9320-9335) and `.chat-row-archive*` (:2297-2313) stay untouched |
| `web/packages/app/src/components/space-filter.tsx` | edit | `SidebarViewMenu`'s tooltip state (:388-402, :494-517) — the timer ref, the dismissal handlers, drop `onFocus` |
| `web/packages/app/tests/archived.test.ts` | edit | the right-slot choice tests |
| `web/packages/app/tests/sidebar-tooltip.test.ts` | new | the tooltip show/hide controller tests (fake timers) |

---

## 1. Context a fresh session needs

- **Vocabulary**: "space" in code == "project" in user-facing strings;
  "chat", not session; "harness", not provider.
- The sidebar has two row families with two different reveal mechanisms
  today. **Active rows** (chat-list.tsx:474-497) are already correct: the
  row wrapper tracks `onMouseEnter`/`onMouseLeave` → `hovered`, and the
  corner swaps to the Archive pill only while hovered — no CSS pin. **Verify
  only; no change.**
- **Archived rows** are the bug: the component keeps BOTH right-slot
  children mounted and lets CSS pick — the time span and the Unarchive
  button at `archived-section.tsx:137-169` (the comment at `:137-141`
  documents the invented design), with the CSS swap on `.arch-row:hover` +
  `:focus-within` (`app.css:9337-9346`) and the permanent pin
  `@media (hover: none)` (`app.css:9352-9360`). The pill's visible
  `rgb(var(--rb-wash) / 0.1)` fill (`app.css:9330`) is the "outline" the
  user reports seeing at rest: it paints whenever the browser reports
  `hover: none` (touch-primary pointers — Windows tablets, touch laptops,
  mobile web) or whenever the row's Link holds focus after a click
  (`:focus-within`; TanStack Links keep focus on navigation).
- The archived row's context menu is already wired
  (`archived-section.tsx:122`, `useChatMenu`) — right-click/long-press opens
  the same chat menu the active rows use. That is the touch/keyboard
  unarchive path once the pins die.
- The pill geometry itself already MATCHES the desktop on both families
  (research S4(d) "Pill visuals" row): h 18, gap 4, px 4, mr −4, radius 5,
  wash 0.10 → 0.18, icon 11 `text_muted`, label 10px `text_muted`
  (`app.css:9320-9350`, `:2297-2313`).
- The stuck tooltip: `SidebarViewMenu` (`space-filter.tsx:386-531`) keeps
  `tooltip` state (:390); `showTooltip` (:396-402) is written in a
  cleanup-returning style, but React event handlers ignore return values —
  **nothing ever calls the returned closure**. Only `onMouseEnter` and
  `onFocus` arm it (:499-500); there is no `onMouseLeave`, no `onBlur`, and
  no other `setTooltip(false)` anywhere. Every re-enter arms *another*
  timer, none tracked. The `!open` suppression (:512) hides the label only
  while the view menu is open; Escape and outside-press do nothing (the
  label is not a popover and is not on the escape ladder, `state/escape.ts`).
  The 350ms delay (`TOOLTIP_VIEW_OPTIONS_MS`, `components/ui/Tooltip.tsx:27`)
  is why quick pointer passes don't trigger it but resting or tabbing does.
- The label card's CSS (`.space-filter-sort-tooltip`, `app.css:1386`) and
  the trigger's geometry/keyboard landed with ticket 08 and already match
  the desktop (`spaces.rs:63-80`, `:1101-1156`) — **not touched**.
- Tests in this package are pure-logic (vitest, no component rendering
  infra); the tooltip fix therefore extracts a small testable controller
  seam (§2.2), and the row's one-child choice gets a pure predicate (§2.1).

## 2. Spec

### 2.1 Archived-row right slot — exactly one child, hover-only

**The desktop's reveal, copied verbatim** (`spaces.rs:1654-1712` + the row's
hover listener at `:1729-1739`):

| element | value | source |
| --- | --- | --- |
| Hover state | `let hovered = self.archived_hover.as_deref() == Some(id.as_str());` — per-row state set by the row's listener: enter sets it (and notifies → re-render), leave clears it (and notifies) | `spaces.rs:1654`, `:1729-1739` |
| Right slot | `let right: AnyElement = if hovered { …Unarchive pill… } else { …time_ago… };` — **one element, chosen at render**; the two are never mounted together. The slot-choice comment: "Right slot: time at rest; the Unarchive affordance takes its place on row hover" | `spaces.rs:1669-1672`, `:1706-1712` |
| Pill geometry | flex row, items-center, gap 4, **h 18, px 4, mr −4** (the padding bleeds right into the row's padding so the label right-aligns exactly where the time sat), **radius 5**, bg `wash(0.10)` → `wash(0.18)` on the pill's own hover; icon `ARCHIVE_UP_MINIMALISTIC` **11px** `text_muted`; label `ui_rems(10.0)` `text_muted`, string `"Unarchive"`; click `stop_propagation` → unarchive | `spaces.rs:1677-1704`, `:1689-1692` |
| Rest slot | `ui_rems(11.0)`, `text_muted.opacity(0.55)`, the `timeAgo` string | `spaces.rs:1707-1711` |
| Reveal visibility/opacity/timing | **none** — the swap is a plain re-render on hover enter/leave; no fade, no transition on the slot swap (only the pill's own background rides the row family's hover-fade transition) | `spaces.rs:1729-1739` |
| Focus | never pins the pill — no focus involvement in the slot choice at all | (absence; research S4(b)) |
| Touch | no touch variant — the pill is hover-only; the row's context menu is the only non-hover unarchive affordance | (absence; research S4(b), gap row 1) |

**Web fix** — `ArchivedRow` copies the active rows' pattern:

- Track per-row hover state exactly like `ChatListRow`
  (`chat-list.tsx:474-475`): `onMouseEnter={() => setHovered(true)}` /
  `onMouseLeave={() => setHovered(false)}` on the row element, and render
  the time span OR the pill — never both. (The pill sits inside the row's
  `Link`, so hovering the pill keeps the row hovered — no flicker; this is
  the desktop's NO-occlude rationale, `shell.rs:4622-4629`, and why the pill
  must remain a row child, not an overlay.)
- Keep the existing `<button type="button" className="arch-row-unarchive"
  aria-label="Unarchive chat">` and its handler
  (`archived-section.tsx:124-135`, `:161-169`) verbatim — only its mounting
  condition changes.
- Rewrite the comment at `:137-141` (it documents the invented
  both-mounted/CSS-swap design this ticket deletes).
- **Delete** the CSS swap + both pins: `app.css:9337-9346` (the
  `:focus-within` swap) and `:9352-9360` (the `@media (hover: none)` pin),
  including their comments. `.arch-row-unarchive`'s base block
  (`:9320-9335`) stays — geometry MATCHES.
- Export the slot choice as a tiny pure predicate (e.g.
  `archivedRightSlot(hovered)` → `"time" | "pill"`) so the
  pure-logic test suite can pin it without component-rendering infra.

**States**

| state | condition | right slot renders |
| --- | --- | --- |
| rest | row not hovered | the time-ago span, only |
| hovered | pointer within the row (pill included) | the Unarchive pill, only |
| touch (`hover: none`) | hover never fires | the time-ago span, only — unarchive via the row's context menu |
| keyboard focus | the Link holds focus after click/Tab | the time-ago span, only — focus never pins |

**Interactions**: hover enter/leave (row-level); pill click →
`preventDefault` + `stopPropagation` + unarchive (existing handler, rides
the row's owning engine off its scoped id); row click opens the chat;
right-click → the chat context menu at the pointer (existing `useChatMenu`
wrap).

**Text** (verbatim, unchanged): `"Unarchive"` (pill label),
`"Unarchive chat"` (aria-label).

### 2.2 "Sidebar view options" tooltip — 350ms hover, dismiss on leave

**The desktop's contract, copied verbatim** (`spaces.rs:63-80`, `:1150-1151`):

| element | value | source |
| --- | --- | --- |
| Label card | px 8, py 6, radius 6, `border_1 border_strong`, bg `surface_raised`, `shadow_md`, text `ui_rems(11.0)` `theme.text`, string `"Sidebar view options"` | `spaces.rs:63-80` |
| Show | after a **350ms hover delay**, while hovered | `spaces.rs:1150-1151` |
| Dismiss | the pointer leaves the element, or the element unmounts — gpui library-managed (`gpui's built-in .tooltip(…)` with `.tooltip_show_delay(350ms)`; "dismisses when the pointer leaves the element or the element unmounts; keyboard focus does not pin it") | `spaces.rs:1150-1151` |
| Focus | never shows or pins the label | (absence; research S6(b)) |
| Re-arm | library-managed — one tooltip per element, no stacking | `spaces.rs:1150-1151` |

**Web fix** — `SidebarViewMenu` (`space-filter.tsx:388-402, :494-517`):

- Hold the timer in a **ref** (a single timer; clear the previous one
  before arming — re-arm hygiene).
- `onMouseEnter` arms the 350ms timer (`TOOLTIP_VIEW_OPTIONS_MS` from
  `components/ui/Tooltip.tsx:27` — the delay stays imported, never
  inlined).
- `onMouseLeave` → `clearTimeout` + `setTooltip(false)`.
- **Drop `onFocus`** (`space-filter.tsx:500`): the desktop's label is
  hover-only and the trigger already carries
  `aria-label="Sidebar view options"` (`:498`) for assistive tech — the
  research's "Focus behavior" row is explicit ("focus never shows/pins the
  label").
- Defensive dismissals beyond the desktop's library-managed leave (the
  hand-rolled span has no library, so spell them out): an `onBlur` clear
  (moot once `onFocus` is gone, but keeps the invariant total), Escape
  while the label is visible (a local keydown on the trigger — the label is
  NOT a popover, so do not register it on the shell's escape ladder,
  `state/escape.ts`), and the existing `!open` suppression stays (`:512`).
- Clear the timer on unmount.
- Untouched: the trigger's geometry and keyboard-open behavior (tickets
  08/10), the label card's CSS (`.space-filter-sort-tooltip`,
  `app.css:1386`), the 350ms constant.
- Extract the show/hide timer controller as a small exported factory (e.g.
  `createViewOptionsTooltip(setVisible)` returning
  `{ enter, leave, blur, escape, dispose }`) so the pure-logic suite can
  drive it with fake timers; the component wires the handlers to it.

**States**

| state | condition | label |
| --- | --- | --- |
| hidden | not hovered / pointer left / menu open / Escape / unmounted | not rendered |
| pending | hover started, < 350ms | not rendered (timer armed) |
| visible | hover ≥ 350ms, still hovered, menu closed | rendered |

## 3. Pure logic to port

None — the research's §4 pure-logic list is entirely ticket 39's
send-path/namespace domain, and no desktop unit test pins either behavior
(gpui manages the tooltip; the pill is a render-structure choice, not a
function). Write new web tests (fresh names, no Rust mirrors):

- `archivedRowRightSlotRendersExactlyOneChild` — the pure predicate: rest →
  time, hovered → pill, never both (drive the hover state through the
  predicate's input, not the DOM).
- `viewOptionsTooltipAppearsAfterDelayAndDismissesOnLeave` — fake timers:
  `enter` + 350ms → visible; `leave` before or after firing → hidden, timer
  cleared.
- `viewOptionsTooltipReArmDoesNotStackTimers` — `enter`, `leave`, `enter`:
  exactly one pending timer; the first arm's firing never leaks a visible
  label after a leave.
- `viewOptionsTooltipNeverArmsOnFocus` — the controller has no focus-arm
  path (or `blur` clears), pinning the desktop's hover-only contract.

## 4. Gaps this ticket closes

S4's rows, verbatim from research §S4(d):

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
| --- | --- | --- | --- | --- |
| Touch-pointer pill pin | INVENTED | no touch variant — pill is hover-only (`spaces.rs:1654,1706-1712`) | `@media (hover: none)` always displays the pill (`app.css:9353-9360`) | delete the pin; on `hover: none` devices surface unarchive via the row's context menu (already ported, `archived-section.tsx:122` `useChatMenu`) like the desktop's only affordance set |
| Focus keeps the pill | INVENTED | pill unmounts on un-hover; focus never pins it (`shell.rs:4522-4560`) | `:focus-within` pins it after a click until focus leaves (`app.css:9338-9346`) | restrict to `:focus-visible` (keyboard) or drop |
| Pill visuals | MATCHES | h18/px4/mr−4/r5/wash .10→.18 (`shell.rs:4523-4559`) | same (`app.css:9320-9350`, `2297-2313`) | none |

S6's rows, verbatim from research §S6(d):

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
| --- | --- | --- | --- | --- |
| Tooltip dismissal | MISSING | gpui hover tooltip auto-dismisses on pointer leave (`spaces.rs:1150-1151`) | no leave/blur handler; cleanup never invoked (`space-filter.tsx:396-402,499-500`) | keep the timer in a ref; bind `onMouseLeave`/`onBlur` to `clearTimeout + setTooltip(false)`; clear on unmount |
| Focus behavior | WRONG BEHAVIOR | focus never shows/pins the label | `onFocus` arms it (`:500`) and it never clears | drop `onFocus` (the trigger has `aria-label`; the visual label is hover-only on the desktop) |
| Re-arm hygiene | MISSING | n/a (library-managed) | repeated enters stack timers, none tracked (`:397`) | single ref, cleared before re-arm |

(For the "Focus keeps the pill" row this ticket takes the "drop" arm, per
§2.1's one-child render — once the slot choice is React hover state, there
is no CSS left to pin. The `:focus-visible` alternative is the fallback only
if the one-child port is blocked.)

## 5. Do not

- Do not re-add the `@media (hover: none)` pin or the `:focus-within` pin —
  both are INVENTED (research S4(d)); the desktop has no touch or focus
  variant for the pill.
- Do not add a new rest-state touch affordance — the row's context menu
  (already wired, `archived-section.tsx:122`) is the unarchive path on
  touch, matching the desktop's only affordance set. (The research's open
  question 1 floats a "visually quiet" touch affordance; that is a human
  decision for a future ticket if wanted — this ticket ships the
  desktop-parity default.)
- Do not port gpui's tooltip library or convert the label span into a
  portal/popup — port the CONTRACT (hover-only, 350ms delay,
  auto-dismiss), not the mechanism. The span-inside-its-trigger stays the
  documented exception (`components/ui/Tooltip.tsx:15-19`).
- Do not register the label on the shell's escape ladder
  (`state/escape.ts`) — it is a label, not a popover; Escape is a local
  keydown on the trigger.
- Do not touch the active rows' archive pill (`chat-list.tsx:486-497`,
  `app.css:2297-2313`) — already desktop-correct (the "Pill visuals
  MATCHES" row); verify only.
- Do not touch the trigger button's geometry, keyboard-open behavior, or
  the view menu's contents (tickets 08/10), nor the label card's CSS
  (`app.css:1386`).
- Do not store hover state in a sidebar store — per-row React state is the
  web equivalent of the desktop's in-memory `archived_hover`/
  `chat_status_hover` fields (research "Desktop-only items NOT to port");
  only the two CSS pins were invented.
- Do not touch send-path/namespace surfaces (the projectless cwd, the
  scoped-id mint, the not-found page) — ticket 39.

## 6. Acceptance

- [ ] Hover a chat row in the active list → the archive pill appears;
      unhover → gone (verify only; no change to that family).
- [ ] Hover an archived row → the time-ago swaps to the Unarchive pill;
      unhover → back to the time-ago; exactly one right-slot child exists
      at every instant.
- [ ] Touch device (devtools `hover: none` emulation) → the pill never
      paints at rest on any archived row; the row's context menu offers
      Unarchive (desktop touch parity).
- [ ] Click an archived row (the Link retains focus) → no pill pinned
      while focused; Tab away and back → none either.
- [ ] "Sidebar view options" tooltip: rest on the button ≥350ms → the
      label appears; move the pointer off → the label dismisses; a quick
      pass (<350ms) shows nothing.
- [ ] Tab to / click-focus the trigger → no label ever appears from
      focus.
- [ ] Hover → open the view menu → the label is suppressed; close the
      menu → no stuck label reappears; re-hover re-arms cleanly.
- [ ] Repeated enter/leave never stacks timers (no zombie 350ms firing
      after a leave); unmounting the sidebar clears the pending timer.
- [ ] Unit tests: `archivedRowRightSlotRendersExactlyOneChild`,
      `viewOptionsTooltipAppearsAfterDelayAndDismissesOnLeave`,
      `viewOptionsTooltipReArmDoesNotStackTimers`,
      `viewOptionsTooltipNeverArmsOnFocus` →
      `web/packages/app/tests/archived.test.ts` +
      `web/packages/app/tests/sidebar-tooltip.test.ts`.
- [ ] Screenshot pairs, desktop vs web, states: (a) archived row at rest;
      (b) archived row hovered (pill); (c) the same row with its Link
      focused after a click (no pill); (d) the view-options tooltip visible
      after a 350ms rest.
- [ ] `pnpm -r build` green; package vitest green.
- [ ] No new literal hex/px where a `--rb-*` token exists.

## Comments

(empty; appended during implementation)
