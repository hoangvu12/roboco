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

**Status:** ready-for-human (quarantine/delete is a product call)

**Parent:** `.scratch/ci-speed/spec.md`

- [ ] Per-suite table: failing tests, classification, evidence (run IDs), proposed action
- [ ] User decision recorded per suite; follow-up fix tickets filed under the owning feature's `.scratch/` dir
- [ ] Measured before/after (after the follow-ups land): failure share of runner-min for android.yml and ui-tests.yml (baseline 50 % and 12 %)

## Comments
