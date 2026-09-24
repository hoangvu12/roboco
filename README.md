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

Download a [release tarball](https://github.com/hoangvu12/roboco/releases) and run its `install.sh` (installs into `~/.local` without root).

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

## Remote access

Remote access uses direct engine pairing. Each engine owns its data; the desktop client connects to each paired engine separately. There is no account service, cloud relay, or cross-engine synchronization. The [remote access specification](.scratch/remote-access/spec.md) and its tickets track the implementation.

See the [development notes](docs/reference/windows-development.md) for Windows source builds.

---

See [ARCHITECTURE.md](ARCHITECTURE.md) for the engine, storage, and client boundaries.

Licensed under the [MIT License](LICENSE).
