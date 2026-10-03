# Roboco

Control your coding agents (Claude Code, Codex, Cursor, Devin, Grok, Hermes, Pi, Antigravity) on your own machines. Sessions and files belong to the engine that runs them.

*English | [简体中文](README.zh-CN.md)*

Every device runs an engine that stores its own sessions. The desktop app starts directly with your local engine, without a Roboco account.

Roboco is a native Windows and Linux product derived from [zeronsh/zeron](https://github.com/zeronsh/zeron). Selected upstream changes are ported through a pristine mirror; see [the port workflow](docs/reference/upstream-ports.md).

## Install

### Windows

```powershell
irm https://github.com/hoangvu12/roboco/releases/latest/download/install.ps1 | iex
```

Installs to `%LOCALAPPDATA%\Programs\Roboco` with a Start Menu shortcut. The installed app checks for updates and applies them in-app from then on. Roboco's data lives in `%LOCALAPPDATA%\Roboco` and is never touched by installs or updates.

Manual option: download the portable release ZIP from the [releases page](https://github.com/hoangvu12/roboco/releases), extract it anywhere writable, and run `roboco.exe` — keep `roboco-update.json` beside it for in-app updates.

### Linux

Download a [release tarball](https://github.com/hoangvu12/roboco/releases) and run its `install.sh` (no root needed). It installs into `~/.roboco/app/<version>` behind a `current` symlink and links `~/.local/bin/roboco`, the desktop entry, and the icon to it — the same layout the in-app updater manages, so the install updates itself from then on. Linux requires the system ALSA runtime (`libasound.so.2`), including for headless mode because it shares the desktop executable. The installer checks that the binary starts before activating it and reports missing runtime libraries.

## Build from source

```bash
git clone https://github.com/hoangvu12/roboco
cd roboco
cargo run -p roboco
```

Windows development notes: [docs/reference/windows-development.md](docs/reference/windows-development.md). The daemon keeps running across reboots once installed. No account configuration is required.

The desktop sidebar browser also needs the [Linux browser runtime](docs/reference/linux-browser.md).

Day-to-day:

```bash
roboco status      # local engine status
roboco update      # update to the latest release
roboco daemon start|stop|restart|status
```

## Updates

The desktop app checks for a new release when it starts, every hour while it runs (on a wall-clock schedule, so a laptop that slept catches up on wake), and when you come back to its window. A new version downloads in the background; the sidebar then offers **Update ready — restart to apply**, and if you don't restart, it installs the next time you quit Roboco. Check by hand with **Check for updates** in the account menu (bottom of the sidebar), or **Roboco → Check for Updates…** on macOS. Set `ROBOCO_AUTO_UPDATE=0` to be notified without the background download. When the app runs its engine in-process it shares the engine's checker (one schedule per process); when it attaches to a separate daemon it runs its own report-only checker, since the daemon's status describes the daemon's binary.

Linux installs from the release tarball's `install.sh` use the self-updating `~/.roboco/app` layout, so they update in place too. A daemon installed as a service restarts into a newer installed version once no agent run or terminal is active; `roboco update` updates headless installs on demand.

## Remote access

Remote access uses direct engine pairing. Each engine owns its data; the desktop client connects to each paired engine separately. There is no account service, cloud relay, or cross-engine synchronization. The [remote access specification](.scratch/remote-access/spec.md) and its tickets track the implementation.

See the [development notes](docs/reference/windows-development.md) for Windows source builds.

---

See [ARCHITECTURE.md](ARCHITECTURE.md) for the engine, storage, and client boundaries.

Licensed under the [MIT License](LICENSE).
