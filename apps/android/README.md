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
| `-ProbocoSkipCore` | off | skip the Rust core build **while** the bindings, the host lib and every requested ABI's `.so` exist; a missing output still builds (no `UnsatisfiedLinkError`) |
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
| host core for Robolectric (JNA) | `buildDebugCore` | `target/android-core/mobile/host` |
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
  screenshot goldens in `app/src/test/screenshots/` (the seed for ticket 14).
- `cargo test -p roboco-android-assets` (repo root) — the icon pipeline.

## Launch extras

*(Placeholder: ticket 30 fills this — CI emulator runs and the extended
screenshot suite.)*
