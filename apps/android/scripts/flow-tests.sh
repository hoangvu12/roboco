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

# apps/android/scripts -> the repo root is three levels up.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$ROOT/apps/android"

MAIN_PORT=27701
SLOW_PORT=27702
QUESTION_PORT=27703
MAIN_CONTROL=27711
SLOW_CONTROL=27712
QUESTION_CONTROL=27713
# android-next#02: the Tailcat smoke's fixture. Its ENGINE port is never
# adb-reversed — the device reaches it only over Tailcat.
TAILCAT_PORT=27704
TAILCAT_CONTROL=27714

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

# android-next#02: the Tailcat smoke, only when the Go pieces were built
# (CI's emulator job builds the adapter and the local DERP relay and
# exports ROBOCO_TAILCAT_ADAPTER / ROBOCO_TAILCAT_TESTDERP). The relay is
# all on 127.0.0.1; its DERP port is TCP, so adb reverse brings it to the
# device, which then reaches the engine through the WireGuard tunnel.
TESTDERP_PID=""
TAILCAT_PID=""
DERP_PORT=""
if [[ -x "${ROBOCO_TAILCAT_ADAPTER:-}" && -x "${ROBOCO_TAILCAT_TESTDERP:-}" ]]; then
  "$ROBOCO_TAILCAT_TESTDERP" --until-killed >"$ARTIFACTS/testderp.log" 2>"$ARTIFACTS/testderp.err" &
  TESTDERP_PID=$!
  deadline=$((SECONDS + 30))
  until grep -q '"derpMap"' "$ARTIFACTS/testderp.log" 2>/dev/null; do
    if (( SECONDS >= deadline )); then
      echo "flow-tests: the local DERP relay did not start:" >&2
      cat "$ARTIFACTS/testderp.err" >&2
      exit 3
    fi
    sleep 1
  done
  DERP_MAP=$(sed -n 's/.*"derpMap":"\([^"]*\)".*/\1/p' "$ARTIFACTS/testderp.log" | head -1)
  DERP_PORT=$(sed -n 's/.*"derpPort":\([0-9]*\).*/\1/p' "$ARTIFACTS/testderp.log" | head -1)
  "$FIXTURE_BIN" --profile main --port "$TAILCAT_PORT" --control-port "$TAILCAT_CONTROL" \
    --tailcat-derp-map "$DERP_MAP" >"$ARTIFACTS/fixture-tailcat.log" 2>&1 &
  TAILCAT_PID=$!
else
  echo "flow-tests: no Tailcat pieces (ROBOCO_TAILCAT_ADAPTER / ROBOCO_TAILCAT_TESTDERP); the Tailcat smoke skips"
fi

cleanup() {
  for pid in "$MAIN_PID" "$SLOW_PID" "$QUESTION_PID" "$TAILCAT_PID" "$TESTDERP_PID"; do
    [[ -n "$pid" ]] && kill "$pid" 2>/dev/null || true
  done
  [[ -n "${LOGCAT_PID:-}" ]] && kill "$LOGCAT_PID" 2>/dev/null || true
  [[ -n "${HOSTMON_PID:-}" ]] && kill "$HOSTMON_PID" 2>/dev/null || true
  # A device lost mid-suite made every adb call below block until the
  # job's 60-minute timeout: each one is bounded, and the device-loss
  # evidence (adb's view, the kernel's OOM lines, the emulator's crash
  # database) is kept first.
  timeout 15 adb devices -l >"$ARTIFACTS/adb-devices.txt" 2>&1 || true
  sudo -n dmesg 2>/dev/null | grep -iE "oom|killed process|qemu|out of memory" >"$ARTIFACTS/dmesg-oom.txt" || true
  cp /tmp/android-runner/emu-crash* "$ARTIFACTS/" 2>/dev/null || true
  # Give the processes a beat to exit, then harvest regardless.
  sleep 1
  timeout 60 adb logcat -d >"$ARTIFACTS/logcat.txt" 2>/dev/null || true
  # The tests' PNG/mp4/JSON artefacts live in the app's external files dir.
  timeout 120 adb pull /sdcard/Android/data/sh.roboco.android/files "$ARTIFACTS/app-files" >/dev/null 2>&1 || true
  timeout 30 adb shell ls /sdcard/roboco-tour-*.mp4 >/dev/null 2>&1 && \
    timeout 120 adb pull /sdcard/roboco-tour-*.mp4 "$ARTIFACTS/" >/dev/null 2>&1 || true
}
trap cleanup EXIT

wait_for_fixture "$ARTIFACTS/fixture-main.log" main || exit 3
wait_for_fixture "$ARTIFACTS/fixture-slow.log" slow || exit 3
wait_for_fixture "$ARTIFACTS/fixture-question.log" question || exit 3
if [[ -n "$TAILCAT_PID" ]]; then
  wait_for_fixture "$ARTIFACTS/fixture-tailcat.log" tailcat || exit 3
fi

# 2. Device-side 127.0.0.1:<port> reaches the host through adb reverse
#    (cleartext loopback is allowed by ticket 13's network-security-config).
for port in "$MAIN_PORT" "$SLOW_PORT" "$QUESTION_PORT" "$MAIN_CONTROL" "$SLOW_CONTROL" "$QUESTION_CONTROL"; do
  adb reverse "tcp:$port" "tcp:$port"
done
# The Tailcat fixture: its control port and the DERP relay — NOT its
# engine port ($TAILCAT_PORT stays host-only).
TAILCAT_ARGS=()
if [[ -n "$TAILCAT_PID" ]]; then
  adb reverse "tcp:$TAILCAT_CONTROL" "tcp:$TAILCAT_CONTROL"
  adb reverse "tcp:$DERP_PORT" "tcp:$DERP_PORT"
  TAILCAT_ARGS=("-Pandroid.testInstrumentationRunnerArguments.fixtureTailcat=http://127.0.0.1:$TAILCAT_CONTROL")
fi

# 3. The suite. The @Tour tests (motion recordings) run in the tablet job
#    (FLOW_INCLUDE_TOUR=1); the physical-device bench runs on a real
#    device, never here.
#    The device filters live in the app's RobocoTestRunner (an assumption
#    failure would be reported as a failure here, not a skip): it drops
#    @TabletOnly below 600 dp, @PhysicalDeviceOnly on an emulator, @Ignore
#    rows, and @Tour unless flowIncludeTour=true. A comma-separated
#    notAnnotation argument arrived cut at its first comma on CI, so only
#    this single-value flag crosses the instrumentation boundary.
TOUR_ARGS=()
if [[ "${FLOW_INCLUDE_TOUR:-0}" == "1" ]]; then
  TOUR_ARGS=("-Pandroid.testInstrumentationRunnerArguments.flowIncludeTour=true")
fi
# ADR 0011 (ticket 04): the room rig — the engines' own adb-reversed ports
# cross too, because a room book dials members by address (the control URLs
# alone do not carry them). The fixture's /room-invite + /room-join are the
# rig's room mint/join control endpoints.
#
# Evidence that survives a lost device: logcat streamed to the host as it
# happens, and the host's memory/CPU every 15 s (an OOM-killed or starved
# emulator shows only as "device not found/offline" in the Gradle log).
adb logcat -v threadtime >"$ARTIFACTS/logcat-live.txt" 2>&1 &
LOGCAT_PID=$!
(
  while true; do
    date -u +%T
    free -m
    uptime
    ps -eo pid,rss,pcpu,etime,comm --sort=-rss | head -10
    echo
    sleep 15
  done
) >"$ARTIFACTS/host-mon.txt" 2>&1 &
HOSTMON_PID=$!
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.fixtureMain="http://127.0.0.1:$MAIN_CONTROL" \
  -Pandroid.testInstrumentationRunnerArguments.fixtureSlow="http://127.0.0.1:$SLOW_CONTROL" \
  -Pandroid.testInstrumentationRunnerArguments.fixtureQuestion="http://127.0.0.1:$QUESTION_CONTROL" \
  -Pandroid.testInstrumentationRunnerArguments.fixtureMainPort="$MAIN_PORT" \
  -Pandroid.testInstrumentationRunnerArguments.fixtureSlowPort="$SLOW_PORT" \
  -Pandroid.testInstrumentationRunnerArguments.fixtureQuestionPort="$QUESTION_PORT" \
  "${TAILCAT_ARGS[@]}" \
  "${TOUR_ARGS[@]}"
status=$?

echo "flow-tests: connectedDebugAndroidTest exit $status (artefacts in $ARTIFACTS)"
exit $status
