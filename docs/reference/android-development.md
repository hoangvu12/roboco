# Android development

The native Android client lives in [`apps/android`](../../apps/android/)
([ADR 0009](../adr/0009-android-client.md)). This page is the toolchain
setup and the day-to-day workflow; the project's own README covers the
Gradle properties and layout.

## Toolchain

| Item | Version | Note |
|---|---|---|
| JDK | 17 (build) + 21 (Robolectric test JVMs) | Temurin; `./gradlew` picks 17, the `Test` tasks request a 21 toolchain launcher |
| Android SDK | `platforms;android-37.0`, build-tools 35 | `ANDROID_HOME` points at the SDK |
| NDK | 28.2.13676358 | r28+ aligns ELF to 16 KB by default; `ndkVersion` is pinned in `app/build.gradle.kts` |
| Rust | stable (`rust-toolchain.toml`) | targets `aarch64`/`x86_64-linux-android` |
| cargo-ndk | 4.1.2 | `cargo xtask android-core` calls it |
| Gradle | 9.6.0 (wrapper) | AGP 9.4.1, Kotlin 2.4.21, Compose BOM 2026.09.00 |

## Windows setup

PowerShell (verify each command against your machine):

```powershell
# JDK 17 (Temurin) on PATH / JAVA_HOME, plus JDK 21 for Robolectric test JVMs;
# ANDROID_HOME = %LOCALAPPDATA%\Android\Sdk
winget install EclipseAdoptium.Temurin.21.JDK
$sdk = "$env:ANDROID_HOME\cmdline-tools\latest\bin"
& "$sdk\sdkmanager.bat" --licenses
& "$sdk\sdkmanager.bat" "platform-tools" "platforms;android-37.0" "ndk;28.2.13676358" `
    "emulator" "system-images;android-36;google_apis;x86_64"
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk --version 4.1.2 --locked
# Emulator acceleration: Windows Hypervisor Platform (admin, then reboot). AEHD is sunset
# 2026-12-31; HAXM is unused since emulator 36.2. WHPX coexists with Hyper-V/WSL2.
Enable-WindowsOptionalFeature -Online -FeatureName HypervisorPlatform -All
& "$env:ANDROID_HOME\emulator\emulator.exe" -accel-check
& "$sdk\avdmanager.bat" create avd -n roboco-api36 -k "system-images;android-36;google_apis;x86_64" -d pixel_8   # see: avdmanager list device
& "$env:ANDROID_HOME\emulator\emulator.exe" -avd roboco-api36 -gpu host
cd apps\android; .\gradlew.bat :app:installDebug
```

sccache: `cargo xtask android-core` builds into the workspace `target/`,
so the AGENTS.md sccache note applies unchanged (add new worktree roots to
`basedirs`, restart the server once).

## Linux setup

Same list without the winget/HypervisorPlatform parts: JDK 17 + 21, the SDK
pieces through `sdkmanager`, the Rust targets, cargo-ndk. KVM instead of
WHPX for the emulator.

## Build, install, run

```bash
cd apps/android
./gradlew :app:assembleDebug          # one APK per ABI in app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest
./gradlew :app:installDebug           # installs the split matching the device's ABI
adb shell am start -n sh.roboco.android/.MainActivity
adb logcat -s Roboco                  # core load/version handshake logs
```

ABI splits (android-app#31) apply to every variant, so debug APKs are named
after their ABI too: `app-arm64-v8a-debug.apk`, `app-x86_64-debug.apk`. A
local single-ABI build stays single: `./gradlew
-ProbocoAbis=arm64-v8a :app:assembleDebug`.

Rebuilds with no source changes run no Rust work: the core/assets tasks are
`UP-TO-DATE`, and `-ProbocoSkipCore` skips them (unless an output is
missing). Release builds (R8 + the `mobile-dist` core) are heavyweight and
mostly a CI concern — see [Release builds](#release-builds) for the local
path.

## Release builds

Release APKs are built by CI on a `v*` tag (`.github/workflows/release.yml`):
R8 plus resource shrinking with the JNA/UniFFI keep rules
(`apps/android/app/proguard-rules.pro`), the stripped `mobile-dist` Rust
profile, ABI splits (`arm64-v8a` and `x86_64`, no universal APK), and the
release signing key from GitHub secrets. Local release builds work the same
way, unsigned by default (never debug-signed):

```bash
cd apps/android
./gradlew :app:assembleRelease                    # unsigned, every split
./gradlew -ProbocoAbis=arm64-v8a :app:assembleRelease   # one split only
```

Outputs: `app/build/outputs/apk/release/app-<abi>-release.apk` (signed) or
`app-<abi>-release-unsigned.apk` (unsigned), and the R8 mapping at
`app/build/outputs/mapping/release/mapping.txt`. Unsigned and signed
builds produce the same R8 output — signing never branches the build type.

Signing locally (a throwaway key is fine for a device test):

```powershell
keytool -genkeypair -keystore $env:TEMP\t.jks -storetype PKCS12 -alias roboco `
    -keyalg RSA -keysize 2048 -validity 1 -dname "CN=test" -storepass testtest -keypass testtest
Set-Content -Encoding ascii $env:TEMP\k.properties `
    "storeFile=$($env:TEMP -replace '\\','/')/t.jks`nstorePassword=testtest`nkeyAlias=roboco`nkeyPassword=testtest"
cd apps\android
.\gradlew.bat -ProbocoKeystoreProperties=$env:TEMP\k.properties :app:assembleRelease
```

`storeFile` in the properties file resolves against `apps/android/app`. In
CI, the four `ROBOCO_ANDROID_KEYSTORE*`/`ROBOCO_ANDROID_KEY_PASSWORD`
environment variables fill the same signing config.

Verify a release APK (build-tools from `$ANDROID_HOME`):

```powershell
$bt = Get-ChildItem "$env:ANDROID_HOME\build-tools" | Sort-Object Name | Select-Object -Last 1
& "$($bt.FullName)\apksigner.bat" verify --print-certs app\build\outputs\apk\release\app-arm64-v8a-release.apk
& "$($bt.FullName)\zipalign.exe" -c -P 16 -v 4 app\build\outputs\apk\release\app-x86_64-release.apk
```

`release.yml` gates all of this per APK on the tag: package name,
`tag == versionName`, exactly one ABI, `zipalign -c -P 16`, a stripped
`libroboco_mobile.so` (no `.debug_*` sections, 16 KB `LOAD` alignment), the
signer SHA-256 pin, and the size budget (25 MiB arm64 / 30 MiB x86_64).

## Release signing

One-time, offline, by the maintainer — the key that signs every public APK:

```bash
keytool -genkeypair -v -keystore roboco-release.jks -storetype PKCS12 \
  -alias roboco -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Roboco, O=hoangvu12"
keytool -list -v -keystore roboco-release.jks -alias roboco   # note the SHA-256 fingerprint
base64 -w0 roboco-release.jks > roboco-release.jks.b64
# PowerShell: [Convert]::ToBase64String([IO.File]::ReadAllBytes("roboco-release.jks"))
```

| GitHub setting | Kind | Value |
|---|---|---|
| `ANDROID_KEYSTORE_BASE64` | secret | the base64 keystore |
| `ANDROID_KEYSTORE_PASSWORD` | secret | store password |
| `ANDROID_KEY_ALIAS` | secret | `roboco` |
| `ANDROID_KEY_PASSWORD` | secret | key password (with PKCS12, equal to the store password) |
| `ANDROID_CERT_SHA256` | **variable** (public) | the signer certificate SHA-256, colon-free lowercase hex, as printed by `apksigner verify --print-certs` |

- Keep the keystore and passwords in a password manager **and** an offline
  backup. **Losing the key means no update can ever install over existing
  installs.** Users would have to uninstall — which deletes their
  paired-engine credentials — and re-pair every engine.
- Never commit the keystore. The root `.gitignore` already ignores `/ignore/`
  for local credentials; keep it there or outside the repo.
- Publish the certificate SHA-256 in the README's Android install section
  (keep it in sync if the key is ever rotated). `release.yml` pins the digest
  via `ANDROID_CERT_SHA256` and fails the release on a key swap.
- A tag push without the secrets fails before publishing; a
  `workflow_dispatch` run builds unsigned APKs instead.

## Install a release

1. On the phone, open `https://github.com/hoangvu12/roboco/releases/latest`
   and download `roboco-<version>-android-arm64-v8a.apk`. The `x86_64` APK
   is only for emulators and x86 Chromebooks or tablets.
2. Optional checks: the file's SHA-256 equals
   `files["roboco-<version>-android-arm64-v8a.apk"].sha256` in that
   release's `manifest.json`, and `apksigner verify --print-certs <apk>`
   shows the published signer certificate SHA-256 (README, "Install →
   Android").
3. Allow your browser or file manager to install apps (Settings → Apps →
   Special app access → Install unknown apps), open the APK and install.
   Play Protect may warn about an app from an unknown developer.
4. Updating: install the newer APK over the old one. Same key, so data and
   pairings are kept. Never install a locally built debug APK over a release
   (different key: Android refuses). Uninstalling removes your paired
   engines.
5. Update tracking without a store: [Obtainium](https://github.com/ImranR98/Obtainium)
   can follow `https://github.com/hoangvu12/roboco` with the APK filter
   `android-arm64-v8a\.apk$`. Releases also carry desktop assets.

## The emulator

Create an API 36 x86_64 AVD (above). The emulator has no engine; the app
still launches to the placeholder and shows `core <version>`. For pairing a
real engine see below.

## Physical devices

```powershell
adb devices
adb pair <ip:port>                    # wireless pairing, first time
adb install -r apps\android\app\build\outputs\apk\debug\app-arm64-v8a-debug.apk
```

USB debugging or wireless ADB both work; the app is a plain sideload.
(`./gradlew :app:installDebug` picks the APK matching the device's ABI
itself.)

## Testing against a local engine

Run an engine with a pairing code on your dev machine, then pair from the
app. **From the emulator, the host machine is `10.0.2.2`**, not
`localhost`:

```
http://10.0.2.2:27655/pair#token=<pairing code>
```

A physical device needs the host's LAN IP. Engines on a LAN speak plain
`http://`/`ws://` by design — `res/xml/network_security_config.xml` permits
platform-level cleartext, and the app warns before dialing a plain-public
host (ticket 18).

## Troubleshooting

- **"no Android NDK found"** from the xtask: set `ANDROID_NDK_HOME` or
  install `ndk;28.2.13676358` under `$ANDROID_HOME/ndk`. Gradle passes the
  AGP-resolved NDK directory automatically.
- **`cargo-ndk is missing`**: `cargo install cargo-ndk --version 4.1.2 --locked`.
- **`UnsatisfiedLinkError` or a UniFFI checksum mismatch** at startup (or in
  `CoreLoadTest`): stale `jniLibs` — the APK's core does not match the
  generated bindings. Rebuild without `-ProbocoSkipCore`.
- **Long paths**: AGP intermediates can exceed 260 characters —
  `git config core.longpaths true` and keep the checkout path short.
- **PowerShell binary redirection**: never redirect binary output with `>`
  in PowerShell 5.1 (it writes UTF-16 and corrupts files); use `git
  cat-file blob <rev> > file` from Git Bash.
- **sccache misses**: add the worktree root to `basedirs` in
  `%APPDATA%\Mozilla\sccache\config\config` and restart the server once.
- **RUSTFLAGS / CARGO_ENCODED_RUSTFLAGS set**: the xtask refuses to run (they
  would override the 16 KB ELF-alignment rustflags). Unset them.
