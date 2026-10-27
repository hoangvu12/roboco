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

**Status:** ready-for-agent

**Source:** `pass3-ios-1.md` G1, #981, #1014; `pass2-features-android.md` N7;
`2026-10-28.md` A35, §C; `pass2-big-2.md` gap 10.

- [ ] `chooseRoot` test: a landscape phone gets Phone and a tablet gets Split
- [ ] Width change while following stays pinned (TranscriptViewport test)
- [ ] Tablet composer level with the toolbar (tablet golden)
- [ ] #942 done or skip recorded
- [ ] Toast golden shows the capsule and a 2-line label
- [ ] Status pill copy + tests updated
- [ ] Commit records the SHAs; wave-9 21 closed (#980 present, #1010 no target)

## Comments
