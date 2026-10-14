#!/usr/bin/env bash
# The instrumented flow suite's runner (android-app#30 §7): starts the three
# fixture engines, adb-reverses their ports, runs connectedDebugAndroidTest
# against them, and always harvests the artefacts. CI's
# `reactivecircus/android-emulator-runner` runs every `script:` line as a
# separate shell command — so all of the logic lives in this file.
#
# Usage (an API 36 emulator must be attached):
#   apps/android/scripts/flow-tests.sh
#
# The fixture binary is built by the caller (CI's emulator job):
#   cargo build --locked -p roboco-engine --example android_fixture
# Local (Linux/KVM or Windows/WHPX): the same, then this script.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT/apps/android"

MAIN_PORT=27701
SLOW_PORT=27702
QUESTION_PORT=27703
MAIN_CONTROL=27711
SLOW_CONTROL=27712
QUESTION_CONTROL=27713

FIXTURE_BIN="$ROOT/target/debug/examples/android_fixture"
ARTIFACTS="app/build/flow-artifacts"

# The mock-harness env knobs (§5's table) are set by the fixture binary
# itself, per profile — they are process-global, so each engine process
# owns its own set.

mkdir -p "$ARTIFACTS"

if [[ ! -x "$FIXTURE_BIN" ]]; then
  echo "flow-tests: $FIXTURE_BIN is missing — build it first:" >&2
  echo "  cargo build --locked -p roboco-engine --example android_fixture" >&2
  exit 2
fi

# 1. Start the three fixture engines; wait for each ANDROID_FIXTURE line
#    (printed only after the seeding settled) with a 120 s budget.
start_fixture() {
  local profile="$1" port="$2" control="$3"
  "$FIXTURE_BIN" --profile "$profile" --port "$port" --control-port "$control" \
    >"$ARTIFACTS/fixture-$profile.log" 2>&1 &
  echo $!
}

wait_for_fixture() {
  local log="$1" profile="$2"
  local deadline=$((SECONDS + 120))
  until grep -q '^ANDROID_FIXTURE ' "$log" 2>/dev/null; do
    if (( SECONDS >= deadline )); then
      echo "flow-tests: the $profile fixture did not announce readiness within 120 s:" >&2
      cat "$log" >&2
      return 1
    fi
    sleep 1
  done
  grep '^ANDROID_FIXTURE ' "$log"
}

MAIN_PID=$(start_fixture main "$MAIN_PORT" "$MAIN_CONTROL")
SLOW_PID=$(start_fixture slow "$SLOW_PORT" "$SLOW_CONTROL")
QUESTION_PID=$(start_fixture question "$QUESTION_PORT" "$QUESTION_CONTROL")

cleanup() {
  for pid in "$MAIN_PID" "$SLOW_PID" "$QUESTION_PID"; do
    kill "$pid" 2>/dev/null || true
  done
  # Give the processes a beat to exit, then harvest regardless.
  sleep 1
  adb logcat -d >"$ARTIFACTS/logcat.txt" 2>/dev/null || true
  # The tests' PNG/mp4/JSON artefacts live in the app's external files dir.
  adb pull /sdcard/Android/data/sh.roboco.android/files "$ARTIFACTS/app-files" >/dev/null 2>&1 || true
  adb shell ls /sdcard/roboco-tour-*.mp4 >/dev/null 2>&1 && \
    adb pull /sdcard/roboco-tour-*.mp4 "$ARTIFACTS/" >/dev/null 2>&1 || true
}
trap cleanup EXIT

wait_for_fixture "$ARTIFACTS/fixture-main.log" main || exit 3
wait_for_fixture "$ARTIFACTS/fixture-slow.log" slow || exit 3
wait_for_fixture "$ARTIFACTS/fixture-question.log" question || exit 3

# 2. Device-side 127.0.0.1:<port> reaches the host through adb reverse
#    (cleartext loopback is allowed by ticket 13's network-security-config).
for port in "$MAIN_PORT" "$SLOW_PORT" "$QUESTION_PORT" "$MAIN_CONTROL" "$SLOW_CONTROL" "$QUESTION_CONTROL"; do
  adb reverse "tcp:$port" "tcp:$port"
done

# 3. The suite. The @Tour tests (motion recordings) run in the tablet job
#    (FLOW_INCLUDE_TOUR=1); the physical-device bench runs on a real
#    device, never here.
TOUR_ARGS=()
if [[ "${FLOW_INCLUDE_TOUR:-0}" != "1" ]]; then
  TOUR_ARGS=(-Pandroid.testInstrumentationRunnerArguments.notAnnotation=sh.roboco.android.flows.Tour)
fi
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.fixtureMain="http://127.0.0.1:$MAIN_CONTROL" \
  -Pandroid.testInstrumentationRunnerArguments.fixtureSlow="http://127.0.0.1:$SLOW_CONTROL" \
  -Pandroid.testInstrumentationRunnerArguments.fixtureQuestion="http://127.0.0.1:$QUESTION_CONTROL" \
  "${TOUR_ARGS[@]}"
status=$?

echo "flow-tests: connectedDebugAndroidTest exit $status (artefacts in $ARTIFACTS)"
exit $status
