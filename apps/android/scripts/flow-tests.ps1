# The instrumented flow suite's runner on Windows (android-app#30 §8):
# the same steps as flow-tests.sh, against a WHPX emulator.
#
# Prerequisites (§8):
#   1. Windows Hypervisor Platform (elevated PowerShell, then reboot; it
#      coexists with Hyper-V, WSL2 and Core Isolation; AEHD is sunset
#      2026-12-31, HAXM is unused from emulator 36.2):
#        Enable-WindowsOptionalFeature -Online -FeatureName HypervisorPlatform
#   2. SDK pieces: emulator, platform-tools, and the system image:
#        sdkmanager "emulator" "platform-tools" "system-images;android-36;google_apis;x86_64"
#   3. The AVD:
#        avdmanager create avd -n roboco36 -k "system-images;android-36;google_apis;x86_64" -d pixel_7
#   4. `emulator -accel-check` must report WHPX, then
#        emulator -avd roboco36 -no-snapshot-save
#   5. The Rust core toolchain (the fixture is a host binary):
#        rustup target add x86_64-linux-android   # for the app's core
#        cargo install cargo-ndk                  # NDK r28+ (28.2.13676358)
#   6. Build the fixture and the app core (sccache picks both up; add this
#      worktree's root to sccache's basedirs, docs/reference/windows-development.md):
#        cargo build --locked -p roboco-engine --example android_fixture
#
# Usage (the emulator must be attached):
#   apps\android\scripts\flow-tests.ps1

$ErrorActionPreference = 'Stop'

# apps\android\scripts -> the repo root is three levels up.
$Root = Resolve-Path (Join-Path $PSScriptRoot '..\..\..')
Set-Location (Join-Path $Root 'apps\android')

$MainPort = 27701
$SlowPort = 27702
$QuestionPort = 27703
$MainControl = 27711
$SlowControl = 27712
$QuestionControl = 27713

$FixtureBin = Join-Path $Root 'target\debug\examples\android_fixture.exe'
$Artifacts = 'app\build\flow-artifacts'
New-Item -ItemType Directory -Force -Path $Artifacts | Out-Null

if (-not (Test-Path $FixtureBin)) {
  Write-Error "flow-tests: $FixtureBin is missing - build it first: cargo build --locked -p roboco-engine --example android_fixture"
}

# The mock-harness env knobs (§5's table) are set by the fixture binary
# itself, per profile - they are process-global, so each engine process
# owns its own set.

# 1. Start the three fixture engines and wait for each ANDROID_FIXTURE
#    line (printed only after the seeding settled), 120 s budget.
$processes = @()
foreach ($spec in @(
    @{ Profile = 'main'; Port = $MainPort; Control = $MainControl },
    @{ Profile = 'slow'; Port = $SlowPort; Control = $SlowControl },
    @{ Profile = 'question'; Port = $QuestionPort; Control = $QuestionControl }
  )) {
  $log = Join-Path $Artifacts ("fixture-{0}.log" -f $spec.Profile)
  $processes += Start-Process -FilePath $FixtureBin `
    -ArgumentList @('--profile', $spec.Profile, '--port', $spec.Port, '--control-port', $spec.Control) `
    -RedirectStandardOutput $log -RedirectStandardError (Join-Path $Artifacts ("fixture-{0}.err.log" -f $spec.Profile)) `
    -PassThru -WindowStyle Hidden
}

try {
  foreach ($spec in @('main', 'slow', 'question')) {
    $log = Join-Path $Artifacts ("fixture-{0}.log" -f $spec)
    $deadline = (Get-Date).AddSeconds(120)
    while (-not ((Get-Content $log -ErrorAction SilentlyContinue) -match '^ANDROID_FIXTURE ')) {
      if ((Get-Date) -gt $deadline) {
        Write-Error "flow-tests: the $spec fixture did not announce readiness within 120 s (see $log)"
      }
      Start-Sleep -Seconds 1
    }
    (Get-Content $log | Where-Object { $_ -match '^ANDROID_FIXTURE ' }) | Write-Output
  }

  # 2. Device-side 127.0.0.1:<port> reaches the host through adb reverse.
  foreach ($port in @($MainPort, $SlowPort, $QuestionPort, $MainControl, $SlowControl, $QuestionControl)) {
    adb reverse "tcp:$port" "tcp:$port"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
  }

  # 3. The suite. The device filters (@Tour, @TabletOnly, @PhysicalDeviceOnly,
  #    @Ignore) live in the app's RobocoTestRunner: a comma-separated
  #    notAnnotation argument arrived cut at its first comma on CI.
  .\gradlew.bat :app:connectedDebugAndroidTest `
    "-Pandroid.testInstrumentationRunnerArguments.fixtureMain=http://127.0.0.1:$MainControl" `
    "-Pandroid.testInstrumentationRunnerArguments.fixtureSlow=http://127.0.0.1:$SlowControl" `
    "-Pandroid.testInstrumentationRunnerArguments.fixtureQuestion=http://127.0.0.1:$QuestionControl"
  exit $LASTEXITCODE
}
finally {
  # Best-effort harvest: under 'Stop', Windows PowerShell 5.1 turns a native
  # command's stderr line (no tour mp4 on a normal run) into a terminating
  # error that would skip the rest and replace the Gradle exit code.
  $ErrorActionPreference = 'Continue'
  foreach ($process in $processes) {
    if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
  }
  Start-Sleep -Seconds 1
  adb logcat -d > (Join-Path $Artifacts 'logcat.txt') 2>$null
  adb pull /sdcard/Android/data/sh.roboco.android/files (Join-Path $Artifacts 'app-files') 2>$null
  adb shell "ls /sdcard/roboco-tour-*.mp4" 2>$null
  if ($LASTEXITCODE -eq 0) {
    adb pull /sdcard/roboco-tour-*.mp4 $Artifacts 2>$null
  }
}
