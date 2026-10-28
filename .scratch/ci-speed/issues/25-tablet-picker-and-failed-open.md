# 25 — Tablet split: the model picker stays up after a pick; a failed open leaves a transcript

**What to find out:** two tablet-only failures from the nightly tablet suite that ticket 23 could not explain from the code. They may be real split-shell bugs or phone-shaped test assumptions. Until this ticket decides, the tests are tagged `@PhoneOnly("suspected tablet bug, ci-speed ticket 25: …")`, so the tablet job stays green. The phone job still runs them.

- `SessionFlowTest.modelPickerInSession`: on the split, the `model-picker` sheet was still in the tree 5 s after tapping "Mock 1" (`SessionFlowTest.kt:862` in run 40725884775, main 3395065c0). On the phone the sheet closes itself after the pick (wave-10 38, upstream #966). Check whether the split presents the picker differently (popover/dialog) and whether a pick closes it there.
- `SessionFlowTest.unknownChatRouteReturnsToSessions` / `forgottenEngineChatRouteReturnsToSessions`: after the failed open the toast and "Sessions" showed, but a `transcript` node was still present 2 s later (`failedOpenReturnsToSessions`, `SessionFlowTest.kt:1045`). Check what the split's detail column shows at launch and after a failed `chat:` route. If it keeps a previously selected chat on purpose, make the assertion shape-aware (no transcript *for the missing chat*). If it presents something for the missing chat, that is the bug.

Evidence: `android-tablet-artifacts` of run 40725884775 (HTML report, `flow-artifacts` screenshots).

**Blocked by:** 23 (tablet job rig + tags)

**Status:** needs-triage

**Parent:** `.scratch/ci-speed/spec.md` (from ticket 23)

- [ ] Each of the three is classified (bug / phone-shaped test) with a screenshot or log from a tablet run
- [ ] A bug gets a fix; a phone-shaped test gets a tablet-shaped variant or keeps `@PhoneOnly` with a non-ticket reason

## Comments
