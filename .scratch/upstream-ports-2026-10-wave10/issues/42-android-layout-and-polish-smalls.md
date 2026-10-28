# 42 — Android layout and polish: landscape phone, width-change pin, tablet composer level, side insets, toast, "Sending to host…" (I1, #981, #1014, #942, N7, A35)

**Lane:** 3 Android parity. **Effort:** M (six S pieces).

**What to build:**
1. **I1: a phone in landscape gets the tablet split** (Pixel 7 ≈ 915 dp
   wide, ~400 dp tall). Android decides by width alone
   (`shell/RootKind.kt:42-53`). `chooseRoot` should take the height too:
   below a compact-height threshold (e.g. < 480 dp) → `RootKind.Phone`.
   Pass `maxHeight` from `shell/AppRoot.kt:118,139`. Add a `chooseRoot`
   unit test. (Spec question 4: should a portrait tablet stay on the
   phone layout? Keep today's 840 dp rule until the user answers.)
2. **#981 (1): pin to the tail on a width change while following,**
   instead of springing (`transcript/TranscriptViewport.kt:133-141`
   `onFrameApplied`). This also helps phone rotation. Not taken: #981
   (2), keeping the live session across the 840 dp switch without a
   rebuild (M; `CarryOver.kt` re-pushes on the next frame). Record it as
   a follow-up if wanted.
3. **#1014 (wave-9 21's remaining item): the tablet composer rests level
   with the sidebar toolbar.** Centre the resting composer on
   `SidebarToolbar`'s row instead of the fixed 8 dp gap
   (`session/BottomStackMetrics.kt:30`, `SessionBottomStack.kt:235`).
4. **#942: uneven side insets.** Lay the transcript out between uneven
   horizontal insets (Duo/landscape;
   `Transcript/TranscriptListView.swift:300-302`). Low value at 840 dp.
   Do it only if it's under ~30 lines; otherwise record the skip (spec
   question 4).
5. **N7: toast restyle (#966).** The action becomes a small filled
   capsule in the text colour (SemiBold 14, insets 6/12), and the label
   wraps to 2 lines (`design/Toast.kt:413,427,452,474`). Skip the
   `above:` anchor (cloud-only); ticket 37 handles the bar anchor.
6. **A35 Android half:** "Sending…" → "Sending to host…"
   (`session/StatusPill.kt:111` + `StatusPillContentTest.kt`,
   `StatusPillScreenshotTest.kt`).

Wave-9 21's other items are closed: #980 is already safe on Android (no
device = no crash). #1010's chip icon lives in the declined
CompanionStrip and has no Android target.

**Upstream:** `c526d6f7c` (#942), `6f2721fab` (#981), `2ea4e3a05`
(#1014), `cc7da5c2a` (#966 toast), `e799aecc1` (#777 copy). iOS files
are behaviour spec only.

**Roboco files:** `apps/android/.../{shell,transcript,session,design}/*`.

**Excluded:** iOS code; #981 (2) unless requested.

**Proto change:** no.

**Blocked by:** 01.

**Status:** done (2026-10-28, `wave10/android`) — Kotlin compiles in CI only

**Source:** `pass3-ios-1.md` G1, #981, #1014; `pass2-features-android.md` N7;
`2026-10-28.md` A35, §C; `pass2-big-2.md` gap 10.

- [x] `chooseRoot` test: a landscape phone gets Phone and a tablet gets Split
- [x] Width change while following stays pinned (TranscriptViewport test)
- [x] Tablet composer level with the toolbar (metrics test; no golden flow on CI)
- [x] #942 done or skip recorded
- [x] Toast shows the capsule and a 2-line label (ToastHostTest geometry; no golden flow on CI)
- [x] Status pill copy + tests updated
- [x] Commit records the SHAs; wave-9 21 closed (#980 present, #1010 no target)

## Comments

2026-10-28 (wave10/android, lane 3): carried `6f2721fab` (#981 part 1),
`2ea4e3a05` (#1014), `cc7da5c2a` (#966 toast), `e799aecc1` (#777 copy)
by intent, plus I1 and the user's 2026-10-28 layout decision.

1. **Root rule.** User decision: a tablet held upright follows the iPad
   (split when wide enough). `SplitBreakpoint` 840 → **744 dp** (the
   narrowest iPad that splits in portrait, the iPad mini), and I1's
   `CompactHeight` 480 dp: a compact-height window (a landscape phone,
   ~915 × 400) stays on the phone layout. `chooseRoot(hasEngines, width,
   height)`, `AppRoot` passes `maxHeight`. At 744 dp the detail is still
   ≥ 424 dp beside the 320 dp sidebar minimum. `RootKindTest` (landscape
   phone, upright and sideways tablet, a 600 dp tablet, the compact
   edge), `AppRootTest.exactly744DpSplitsAnd743DpIsThePhone`.
2. **#981 (1).** `TranscriptViewport.noteWidthChange` (set by
   `TranscriptState.setViewport` on a real width change) makes the next
   frame pin to the tail while following instead of springing from the
   old geometry; not following is untouched (130's anchor).
   `TranscriptViewportTest.aWidthChangeWhileFollowingPinsToTheTail…`.
   #981 (2) (keeping the live session across the breakpoint without a
   rebuild) not taken, as the ticket says.
3. **#1014.** `LocalSidebarToolbarMid` (provided by the split's detail
   pane while the sidebar shows: the sidebar insets' `barBottom` + half
   the 44 dp toolbar) → `BottomStackMetrics.stackBottomBesideToolbar`
   centres the resting 50 dp composer on the toolbar's row; the keyboard
   lifts it smoothly once the IME passes that rest.
   `BottomStackMetricsTest.besideTheSidebarTheRestingComposerCentres…`.
   On Android's own toolbar placement (`nav + 12`) this moves the
   composer by ~1 dp; the rule now holds by construction.
4. **#942 skipped** (user decision 2026-10-28).
5. **N7.** The toast's action is a small filled capsule in the text
   colour (SemiBold 14 in `Palette.background`, insets 6/12) and the
   label wraps to two lines. The `above:` anchor stays skipped
   (cloud-only); ticket 37 anchors toasts on the one-row bar.
   `ToastHostTest` geometry updated (the row takes the action capsule's
   height; the long label is two lines, 63 dp).
6. **A35.** "Sending…" → "Sending to host…" (`StatusPill`, its content
   and screenshot tests).

Wave-9 21 closed: #980 already safe on Android; #1010's chip icon has no
Android target (the declined CompanionStrip); #1014 is item 3 here.
Goldens: no CI record flow (dropped in `33e5590da`).

2026-10-28 (PR #38 review fixes, one batch):
- `PageSheetDefaults.TabletBreakpoint` follows `SplitBreakpoint` (744 dp),
  so a split window never gets phone sheets.
- Goldens: CI's record job is gone (`33e5590da`), so the session menu,
  status pill and toast goldens can't be re-recorded here — listed in the
  PR as needing a re-record.
