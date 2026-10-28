# 11 — Triage the red suites (emulator flows, tablet, engine-local storage)

**What to build:** A triage, not the fixes. Main has been red on Android for most of the measured window. Failed runs burned more Android runner-minutes (1,264) than green ones (1,098) in 5 days, and every red run costs a rerun or a re-push. For each suite below:

1. pull the failing test names from the last ~10 failures (`gh run view <id> --log-failed`, or the uploaded `android-flow-artifacts`/test reports);
2. classify each as a real bug, a flake (passes on rerun, timing) or an environment problem;
3. propose an action: fix ticket, nextest/Gradle retry, quarantine (tagged, still run nightly, not blocking), or delete.

| suite | failures in window | where |
|---|---|---|
| `android-emulator` "Run the flow suite" | 12 of 30 android.yml runs | `apps/android/scripts/flow-tests.sh` |
| `android-emulator-tablet` | 11 of 14 main pushes (never green) | same script, `FLOW_INCLUDE_TOUR=1`, pixel_tablet |
| `engine-local-regressions` "Engine-local storage and recovery" | 10 of 30 ui-tests runs | `cargo test -p roboco-engine --lib --test …` |
| `mobile-core` "Mobile core tests" (Tailcat e2e) | 4 | `crates/mobile-client/tests/tailcat_engine.rs` |
| `android` JVM suite | ≈ 8 (ubuntu + windows) | `:app:testDebugUnitTest` |

Run IDs are in `.scratch/ci-speed/data/` (`python -I fails.py data android.yml ui-tests.yml`). Note that some failures predate fixes already on main (the removed `linux-browser` and `android-screenshots` jobs); count only suites that still exist.

**Blocked by:** none (best done before 08 decides the tablet job's future)

**Status:** triaged (2026-10-28); follow-ups 22–24; measurement after they land

**Parent:** `.scratch/ci-speed/spec.md`

- [x] Per-suite table: failing tests, classification, evidence (run IDs), proposed action
- [ ] User decision recorded per suite (orchestrator defaults recorded 2026-10-28, awaiting the user's OK); follow-up fix tickets filed (22–24)
- [ ] Measured before/after (after the follow-ups land): failure share of runner-min for android.yml and ui-tests.yml (baseline 50 % and 12 %)

## Comments

### 2026-10-28 — triage (orchestrator side chat `76731858`, read-only)

Test names come from the uploaded `android-flow-artifacts` / `android-tablet-artifacts` HTML reports (the Gradle log doesn't name them). The removed `linux-browser` and `android-screenshots` jobs are excluded from every count.

**`android-emulator` "Run the flow suite"**: 12 failed runs; the last 4 green (incl. main 40589753504). Only 5 of the 12 would still be red against today's main, all from the flakes below.

| failing test | runs | class | evidence (run IDs) | action |
|---|---|---|---|---|
| `RoomFlowTest.aMemberOfflineAtJoinTimeHealsOnTheNextNetworkChange` | 4 | real bug (fixture `/listener/stop` had no effect) | 40256496025, 40256510722, 40261124785, 40261127410 | already fixed on main (5cabff4bb) |
| `PreviewFlowTest.previewOpensTheRelayedDevServerInApp` | 3 | real test bug (hit baseline read after load) | 40472984016, 40473003282, 40545477841 | already fixed on main (5843e3444) |
| `:app:compileDebugKotlin` | 2 | compile break in one commit | 40064201591, 40534000233 | none |
| `KeyboardFlowTest.interactiveDismissClosesPastHalfAndReopensAboveIt` | 3 | flake (10 s `waitUntil`) | 39919545157, 40256496025, 40545477841 | ticket 22 |
| `SessionFlowTest.newSessionFromAccessory` | 1 (+1 tablet) | probably real race (`CalledFromWrongThreadException` from `DefaultDispatcher-worker`) | 39881479628, 39889382804 | ticket 22 |
| `LiveEngineRoundTripTest.sendRoundTripsThroughRealEngine` | 1 | flake (Compose harness `runDetachLifecycle`) | 40427324853 | retry; watch |
| `WallpaperSettingsFlowTest.wallpaperSettingsFlow` | 1 | flake (`performMeasureAndLayout` during layout) | 40256510722 | retry; watch |

**`android-emulator-tablet`**: never green; all 12 runs with reports have 11–13 failures of 53–56 tests (latest main 40589753504 still red). Tours (`TransitionTourTest`) and `TabletLayoutTest` pass.

| failing test | runs | class | evidence | action |
|---|---|---|---|---|
| `TailcatPairingFlowTest.aTailcatInvitePairsAndTheSessionsLoad` | 12/12 | environment: the job never builds the Tailcat smoke pieces (`android.yml:420-480`); the assumption failure is reported as a failure (`RobocoTestRunner.kt:15-17`) | e.g. 40589753504, 40473003282, 39884973551 | ticket 23 |
| 9 × `SessionFlowTest` (`cancelledSwipeBackKeepsStreaming`, `…_gesture`, `forgottenEngineChatRouteReturnsToSessions`, `unknownChatRouteReturnsToSessions`, `longDraftRestsAsWholeLinePreview`, `queueSurvivesNavigatingAway`, `toolGroupsRenderLikeDesktop`, `searchTabRoundTrip`, `tabsAndSearch`) | 12/12 | deterministic: phone-shaped tests on a ≥600 dp shell (no `@PhoneOnly`). Some may be real tablet bugs (guess) | same runs | ticket 23 |
| `KeyboardFlowTest.interactiveDismiss…` | 12/12 | deterministic on tablet: IME-hide wait times out at 1 s (`KeyboardFlowTest.kt:77`) | same runs | ticket 23 |
| `TabletLayoutTest.sidebarOpensSessionsBesideIt` | 5/12 | flake (5 s timeout) | 39889382804, 40065780847, 40067795681, 40261127410, 40473003282 | retry; watch after 23 |
| Room / Preview / RunWatchService / newSessionFromAccessory | 1–2 each | same causes as the phone suite | 40256510722, 40261127410, 40473003282, 39889382804 | fixed or flake |

**`engine-local-regressions` "Engine-local storage and recovery"**: 8 failed runs; the live flake left (last 10-27 08:21).

| failing test | runs | class | evidence | action |
|---|---|---|---|---|
| `completion_notices::capped_outcomes_merge_into_the_one_parked_notice_row` | 4 | flake (30 s `wait_for`, `completion_notices.rs:291`; passed on rerun 40067795761 attempt 2; still fails after 46f37cb7b raised it to 30 s) | 40065780800, 40256495998, 40261127256, 40472984050 | ticket 24; nextest retry meanwhile (06) |
| `completion_notices::a_working_parent_receives_the_notice_after_its_turn_as_its_next_turn` | 2 | flake | 39879365920, 40067795761 (attempt 1) | ticket 24 |
| `completion_notices::a_deleted_parent_is_owed_nothing` | 1 | flake | 40256510710 | ticket 24 |
| `completion_notices::three_children_finishing_while_the_parent_works_merge_into_one_notice` | 1 | flake | 40261127256 | ticket 24 |
| `agent_origin::run_commands_stamp_their_origin_on_the_user_entry` | 1 | unclear (origin `None`; possible read-before-write race) | 40122161245 | watch; fold into 24 if it recurs |
| OpenCode step `error[E0308]` | 1 | compile break on a PR intermediate commit | 40534000734 | none |

**`mobile-core` "Mobile core tests"**: 4 failed runs, 1 of them the Tailcat e2e. Close the Tailcat row.

| failing test | runs | class | evidence | action |
|---|---|---|---|---|
| `tailcat_engine::a_tailcat_engine_pairs_and_reconnects_from_its_stored_record` | 1 | real, PR branch | 39879365920 | already fixed (7f91cf663, PR #28) |
| `new_session::tests::create_session_projectless_runs_on_the_host` | 1 | real test drift | 39884973629 | already fixed (b6d0c91e4, PR #28) |
| compile `E0063 missing field running_subagents` | 1 | compile break | 39919545145 | none |
| `session_engine::busy_sends_queue_steer_and_stop_and_send` | 1 | probable flake | 40427324875 | retry; watch |

**`android` JVM suite**: ≈ 8 failed runs, none open on main.

| failure | runs | class | evidence | action |
|---|---|---|---|---|
| 5 rooms tests (`EngineMetaJsonTest` ×2, `PairPageModelTest.aPartialRoomJoinIsASuccessAndCloses`, `PairPageTest.theCardsFootersAndCapsuleSitAtTheTicketsOffsets`, `PairedEngineStoreTest.aRoomBlobNeverDecryptsAsACredential`) | 4 | real | 40256496025, 40256510722, 40261124785, 40261127410 | already fixed (5cabff4bb) |
| `LimitedBannerTest.a_switch_lands_the_notice_and_reloads_the_ranks` | 1 | real | 40065780847 | already fixed (ad1ed3fe3) |
| `compileDebugKotlin` | 2 | compile break | 40064201591, 40534000233 | none |
| Windows checkout `invalid path 'apps/android/?/.android/analytics.settings'` | 2 | repo hygiene (stray `?/` from merge 8930f07c3) | 40472984016, 40473003282 | removed on main (1ccaa3a8b); `.gitignore` guard in 22 |
| `SessionMenuTest.copyBuildsTheTextOffMainClosesTheFreshWrapperAndSetsTheClip` | 1 | flake (exact thread-name compare vs coroutine debug suffix; mechanism a guess) | 39884973551 | ticket 22 (`startsWith`) |

Outside the table: `ui-tests` `path_field_tests::tab_completes_the_folder…` (40534000734, 40543580346) is fixed by 77a8e30a6.

**Follow-ups filed:** 22 (phone flakes + hygiene), 23 (tablet suite: rig + phone-only tagging), 24 (`completion_notices` timeouts).

**Decisions (orchestrator, within recorded user decisions; the user may overrule):**
1. Tablet suite goes nightly as decided, non-blocking until 3 green nights; ticket 23's cheap fixes ride with the move (08). Not deleted.
2. nextest retries on in the Linux `-P ci` profile (06), with flaky-pass reporting on; ticket 24 keeps a retries-off acceptance check.
3. Phone emulator flakes: a JUnit retry rule on the named flaky tests only (no quarantine), ticket 22.
4. Android follow-ups live under `.scratch/ci-speed/issues/` (no Android feature dir on disk).
