# Roboco for Android

The native Android client: Kotlin + Jetpack Compose over the Rust **mobile
core** ([ADR 0009](../../docs/adr/0009-android-client.md)). Rust lays out the
transcript; Kotlin paints it with the same Geist font bytes. The app pairs
with engines over the LAN (`roboco://` deep links land in ticket 19).

This is the scaffold stage: the Gradle project, the core loading, the fonts,
the icon pipeline, cleartext LAN networking and a placeholder screen. Later
tickets add the real UI on top.

## Building

Windows (PowerShell) and Linux alike — no bash, WSL or Python on the build
path:

```powershell
cd apps\android
.\gradlew.bat :app:assembleDebug          # debug APK (both ABIs)
.\gradlew.bat :app:testDebugUnitTest       # JVM tests (Robolectric incl.)
.\gradlew.bat :app:installDebug            # install on a connected device
```

```bash
cd apps/android
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

First build compiles the Rust core for `arm64-v8a` + `x86_64` through
`cargo xtask android-core` (ticket 10) — expect ~15 minutes on a warm sccache,
seconds afterwards. Toolchain setup (JDKs, SDK, NDK, Rust targets,
cargo-ndk): [docs/reference/android-development.md](../../docs/reference/android-development.md).

Release APKs (R8, the stripped `mobile-dist` core, ABI splits, optional
signing): `./gradlew :app:assembleRelease` — unsigned unless
`-ProbocoKeystoreProperties` or the `ROBOCO_ANDROID_KEYSTORE*` env vars are
set. CI builds the signed ones on `v*` tags (`release.yml`); see
[docs/reference/android-development.md](../../docs/reference/android-development.md)
→ Release builds / Release signing.

## Gradle properties and environment

| Property / env | Default | Effect |
|---|---|---|
| `-ProbocoAbis=arm64-v8a,x86_64` / `ROBOCO_ANDROID_ABIS` | both | ABIs the core builds; also the ABI splits (one APK per ABI, no universal APK — android-app#31). Unit-test-only invocations default to `x86_64` (the host emulator ABI) so day-to-day test runs never cross-compile arm64 |
| `-ProbocoSkipTailcat` / `ROBOCO_ANDROID_TAILCAT=0` | off | build without `libroboco_tailcat.so`, the Go Tailcat transport (android-next#02); pairing a `roboco-tailcat:` invite then reports Tailcat as unavailable. Without it the build needs Go on `PATH` |
| `-ProbocoSkipCore` | off | skip the Rust core build **while** the bindings, the host lib (when built, see below) and every requested ABI's `.so` exist; a missing output still builds (no `UnsatisfiedLinkError`) |
| `-ProbocoCoreProfile=<mobile\|mobile-dist>` | `mobile` (debug), `mobile-dist` (release) | the xtask profile; outputs land in `target/android-core/<profile>` so switching build types never thrashes one directory |
| `-ProbocoCoreLanguages=<mobile\|all>` | `mobile` | grammar subset for syntax highlighting |
| `-ProbocoVersionName` / `-ProbocoVersionCode` | from the workspace `Cargo.toml` | version overrides; `versionCode` = `major × 1_000_000 + minor × 1_000 + patch` |
| `-ProbocoKeystoreProperties=<path>` | none | a Java properties file (`storeFile`/`storePassword`/`keyAlias`/`keyPassword`, resolved against `app/`) that signs `assembleRelease`; without it the release APKs are unsigned, never debug-signed |
| `ROBOCO_ANDROID_KEYSTORE` (+`_PASSWORD`), `ROBOCO_ANDROID_KEY_ALIAS` (+`_KEY_PASSWORD`) | none | the CI path for the same signing config (release.yml maps the GitHub secrets to these) |

## Project layout

```
apps/android/
├── buildSrc/                 task classes + version derivation (pure Kotlin)
│   ├── AndroidCoreBuild      cargo xtask android-core (one per build type)
│   ├── AndroidAssetsGen      roboco-android-assets (icons)
│   ├── FontsSync             the 12 Geist faces + licenses
│   └── LicensesSync          repo LICENSE + THIRD_PARTY_NOTICES.md (APK assets)
├── icons/                    committed SVG sources imported from upstream iOS
└── app/
    ├── proguard-rules.pro    R8 keep rules for JNA/UniFFI (release build only)
    ├── src/main/             manifest, res, Kotlin (placeholder + core loader)
    └── src/test/             JVM tests + committed Roborazzi goldens
```

Generated sources (never committed):

| Output | Task | Land under |
|---|---|---|
| UniFFI Kotlin bindings | `buildDebugCore` | `target/android-core/mobile/kotlin` |
| `jniLibs/<abi>/libroboco_mobile.so` | `buildDebugCore` | `target/android-core/mobile/jniLibs` |
| host core for Robolectric (JNA) | `buildDebugCore`, only when the invocation may run JVM tests (`HostCore`) | `target/android-core/mobile/host` |
| VectorDrawables + icon PNGs | `generateRobocoAssets` | `app/build/generated/roboco-icons` |
| fonts + licenses | `syncRobocoFonts` | `app/build/generated/roboco-fonts` |
| repo `LICENSE` + `THIRD_PARTY_NOTICES.md` | `syncRobocoLicenses` | `app/build/generated/roboco-licenses` |

The launcher mipmaps are **not** Gradle-generated: `scripts/generate-app-icons.py`
derives them from `dist/roboco-artwork.png` and the outputs are committed
(CI never needs Pillow).

## Testing

- `:app:testDebugUnitTest` — Robolectric (SDK 36, JDK 21 test JVM) against
  the **host** core via JNA, plus plain JVM tests for the version/asset/network
  contracts.
- `:app:recordRoborazziDebug` / `:app:verifyRoborazziDebug` — the
  screenshot goldens in `app/src/test/screenshots/`; ubuntu is the source
  of truth for recording (font rasterisation differs across hosts —
  Windows gets `:app:compareRoborazziDebug` for local feedback). Record
  locally (the `android-screenshots` CI job is currently removed) — see `parity/README.md`.
- `:app:parityManifest` — stub manifest entries for unlisted goldens, and
  the sign-off mode (`--sign=… --reviewer=… --date=…`); `parity/README.md`.
- `:app:parityReport` — the iOS parity board (`build/parity/`), G5 of the
  parity gate; see `parity/` below.
- `cargo test -p roboco-android-assets` (repo root) — the icon pipeline.

### The iOS parity gate (android-app#30)

Every committed golden is pinned in `parity/manifest.json` by a human
sign-off (SHA-256, reviewer, date — `ParityManifestTest`, G2), the
ratchet entries carry an SSIM floor against their iOS reference
(`IosParitySimilarityTest`, G4), `IosParityMetricsTest` (G3) asserts the
measured metric table, and `verifyRoborazziDebug` (G1) gates the pixels.
The reviewer's walk is `parity/CHECKLIST.md`; the board to open is the
`android-parity` CI artifact (`:app:parityReport`, uploaded on every run).
The iOS references live in `parity/ios/` with their sources in
`parity/ios/SOURCES.md` (upstream is MIT — keep the attribution).

### The instrumented flow suite (android-app#30)

`scripts/flow-tests.sh` (Linux/KVM) and `scripts/flow-tests.ps1`
(Windows/WHPX) start three `android_fixture` engines (the seeded
`main` / paced `slow` / question-asking `question` profiles), `adb
reverse` their ports, and run `:app:connectedDebugAndroidTest` with the
`fixture*` instrumentation arguments — each test mints a fresh pairing
URL from `/mint` and pairs through the debug `pair` launch extra
(`FixtureRule`). The `@Tour` motion recordings run in the tablet CI job
(`FLOW_INCLUDE_TOUR=1`), the transcript bench on a physical device. A
failed test listed in `scripts/flaky-retry.txt` (with its reason) gets
one logged retry in `flow-tests.sh`, when it is the only kind of failure.

**Linux (KVM):** install the emulator + system image
(`sdkmanager "emulator" "platform-tools"
"system-images;android-36;google_apis;x86_64"`), create the AVD
(`avdmanager create avd -n roboco36 -k … -d pixel_7`), build the fixture
(`cargo build --locked -p roboco-engine --example android_fixture`),
then `apps/android/scripts/flow-tests.sh`.

**Windows (WHPX):** enable Windows Hypervisor Platform (elevated
PowerShell, then reboot — it coexists with Hyper-V, WSL2 and Core
Isolation; AEHD is sunset 2026-12-31, HAXM is unused from emulator
36.2): `Enable-WindowsOptionalFeature -Online -FeatureName
HypervisorPlatform`. Then the same SDK pieces, the AVD, and
`emulator -accel-check` must report WHPX. `rustup target add
x86_64-linux-android`, `cargo install cargo-ndk` (NDK r28+;
30.0.16248370 since 2026-10-29), build the fixture, and run
`apps\android\scripts\flow-tests.ps1`. Builds go through sccache —
add worktree roots to sccache's `basedirs`
([docs/reference/windows-development.md](../../docs/reference/windows-development.md)).

## Launch extras

Debug builds (`FLAG_DEBUGGABLE`) honour these intent extras (launch-time
`adb shell am start … --es key value`), all gated to debug — a hostile
caller cannot use them in release:

| Extra | Values | Effect |
|---|---|---|
| `route` | `chat:<id>` \| `new` \| `more` \| `search` | the deep-link route the shell opens once composed (always parsed, like iOS's `-route`) |
| `wallpaper` / `wallpaper-effect` | path \| `none` / a key | imports the wallpaper / sets the effect at launch (iOS's `-wallpaper` pair) |
| `harness` | verbatim | pins the new-session draft's harness (iOS's `-harness mock` — never a real agent in tests) |
| `measureopen` | `true` | ticket 75's open-measurement flag |
| `pair` | comma-separated URLs | the flow rig's pairing URLs, redeemed before the shell shows (android-app#30) |
