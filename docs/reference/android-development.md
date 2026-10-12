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
./gradlew :app:assembleDebug          # APK in app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest
./gradlew :app:installDebug           # adb install -r equivalent
adb shell am start -n sh.roboco.android/.MainActivity
adb logcat -s Roboco                  # core load/version handshake logs
```

Rebuilds with no source changes run no Rust work: the core/assets tasks are
`UP-TO-DATE`, and `-ProbocoSkipCore` skips them (unless an output is
missing). Never run `assembleRelease` locally — release signing and R8 are
CI-only (ticket 31).

## The emulator

Create an API 36 x86_64 AVD (above). The emulator has no engine; the app
still launches to the placeholder and shows `core <version>`. For pairing a
real engine see below.

## Physical devices

```powershell
adb devices
adb pair <ip:port>                    # wireless pairing, first time
adb install -r apps\android\app\build\outputs\apk\debug\app-debug.apk
```

USB debugging or wireless ADB both work; the app is a plain sideload.

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
