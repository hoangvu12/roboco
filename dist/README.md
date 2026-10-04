# Packaging

## Linux (implemented)

```sh
scripts/package-linux.sh            # release build (thin LTO, stripped)
PROFILE=debug scripts/package-linux.sh   # fast smoke package
```

Produces `target/package/roboco-<version>-linux-<arch>.tar.gz` containing:

- `roboco` — the binary (headed by default; `roboco headless` runs the engine alone)
- `roboco.desktop` — XDG desktop entry template (`Exec=roboco` for packagers;
  the installer rewrites `Exec`, `TryExec`, and `Icon` to absolute paths under
  `~/.roboco/app/current`, since `~/.local/bin` is often not on a desktop
  session's `PATH`)
- `roboco.png` — 512×512 Roboco app icon
- `install.sh` — installs into `~/.roboco/app/<version>` behind a `current`
  symlink (the layout the in-app updater manages), links `~/.local/bin/roboco`
  to it, and writes the desktop entry and icon under `$XDG_DATA_HOME`
  (default `~/.local/share`); `scripts/test-linux-desktop-entry.sh` checks it
  offline (upstream's curl-installer copy of the entry code lives in
  `edge/src/install.sh`, which Roboco removed — this is the single copy)

The release profile in the root `Cargo.toml` sets `lto = "thin"` and
`strip = "symbols"` for distribution builds.

## macOS

```sh
scripts/package-macos.sh    # → target/package/roboco-<version>-macos-<arch>.dmg
```

Builds the release binary, assembles `Roboco.app` (Info.plist + icns), ad-hoc
signs it (set `CODESIGN_IDENTITY` for a real Developer ID), and wraps it in a
dmg. The auto-update tarball retains an internal `Roboco.app` path so older
installed builds can update into Roboco. CI runs this on tags
(`.github/workflows/release.yml`). The manual steps it automates, for reference
(run on a macOS host — gpui needs Metal; no cross-build from Linux):

1. Build the universal (or per-arch) binary:
   ```sh
   cargo build --release -p roboco --target aarch64-apple-darwin
   cargo build --release -p roboco --target x86_64-apple-darwin
   lipo -create -output roboco \
     target/aarch64-apple-darwin/release/roboco \
     target/x86_64-apple-darwin/release/roboco
   ```
2. Assemble the bundle:
   ```sh
   mkdir -p Roboco.app/Contents/{MacOS,Resources}
   cp roboco Roboco.app/Contents/MacOS/roboco
   sed "s/__VERSION__/$(grep -m1 '^version' Cargo.toml | sed 's/.*"\(.*\)".*/\1/')/" \
     dist/macos/Info.plist > Roboco.app/Contents/Info.plist
   ```
3. Icon: generate `roboco.icns` from `dist/macos/icon-1024.png` (the macOS-shaped
   variant of the artwork — squircle mask, margins, and shadow pre-baked, since
   `sips` can't apply an alpha mask) and place it at
   `Roboco.app/Contents/Resources/roboco.icns`:
   ```sh
   mkdir roboco.iconset && sips -z 256 256 dist/macos/icon-1024.png --out roboco.iconset/icon_256x256.png
   iconutil -c icns roboco.iconset -o Roboco.app/Contents/Resources/roboco.icns
   ```
4. Sign + notarize (required for distribution):
   ```sh
   codesign --deep --force --options runtime --sign "Developer ID Application: …" Roboco.app
   xcrun notarytool submit Roboco.zip --keychain-profile … --wait
   xcrun stapler staple Roboco.app
   ```
5. Ship as a `.dmg` (`hdiutil create -volname Roboco -srcfolder Roboco.app -ov -format UDZO Roboco.dmg`).

## Windows

```powershell
./scripts/package-windows.ps1 -ReleasesUrl https://github.com/hoangvu12/roboco/releases/latest/download
```

Produces, under `target/package/`:

- `roboco-<version>-windows-<arch>-setup.exe` — the per-user installer built
  from `dist/windows/roboco.iss` with Inno Setup 6
- `roboco-<version>-windows-<arch>.zip` — the portable package
- `roboco-<version>-windows-<arch>.exe` — the bare executable the in-app
  updater downloads

The installer and the zip both carry `roboco-update.json`, the marker that lets
the app update itself in place. The GitHub Release also ships `install.ps1`
(`scripts/install-windows.ps1`, the one-liner installer) for the same layout,
without the uninstall entry and `roboco://` registration. CI runs
`scripts/test-windows-installer.ps1` against the setup on every Windows build.
