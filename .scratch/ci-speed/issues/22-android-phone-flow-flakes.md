# 22 — Android flow suite: the remaining phone flakes and two hygiene fixes

**What to build:** With the room (5cabff4bb) and preview (5843e3444) fixes on main, the phone `android-emulator` suite's remaining red is intermittent, and each one turns a whole ≈ 35-min run red (ticket 11 triage):

- `KeyboardFlowTest.interactiveDismissClosesPastHalfAndReopensAboveIt`: 10 s `waitUntil` timeout (39919545157, 40256496025, 40545477841).
- `SessionFlowTest.newSessionFromAccessory`: `CalledFromWrongThreadException`, a view touched from `DefaultDispatcher-worker` (39881479628, 39889382804). Looks like a real UI-thread bug in the accessory's new-session path.
- Compose-harness errors in `LiveEngineRoundTripTest` (40427324853), `WallpaperSettingsFlowTest` (40256510722) and `RunWatchServiceTest`.
- Hygiene: `SessionMenuTest.copyBuildsTheTextOffMain…` compares an exact thread name, and coroutine debug mode can append ` @coroutine#N` (39884973551). Nothing stops a stray `apps/android/?/` directory (it broke Windows checkout: 40472984016, 40473003282) from being committed again.

Decision (ticket 11): no quarantine. A small JUnit retry rule applies only to the named flaky tests, so PRs keep end-to-end coverage of the keyboard and new-session paths.

**Blocked by:** none

**Status:** ready-for-agent

**Parent:** `.scratch/ci-speed/spec.md` (from ticket 11)

- [ ] `newSessionFromAccessory`'s root cause is fixed, or disproven with evidence
- [ ] `KeyboardFlowTest` uses a deterministic wait, or carries the retry rule with a reason
- [ ] The retry rule exists, is opt-in per test, and logs when a retry happened
- [ ] `SessionMenuTest` checks the thread name with `startsWith`
- [ ] `.gitignore` covers `/?/` (and `apps/android/?/`)
- [ ] 10 consecutive green `android-emulator` runs on main

## Comments
