#!/usr/bin/env bash
# Build and run an isolated macOS development bundle. Roboco.app may remain
# open: this bundle has a separate LaunchServices/TCC identity, data directory,
# and engine IPC port.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
command -v cargo >/dev/null 2>&1 || PATH="$HOME/.cargo/bin:$PATH"
VERSION="$(grep -m1 '^version' "$ROOT/Cargo.toml" | sed 's/.*"\(.*\)".*/\1/')"
DEV_ROOT="$ROOT/target/macos-dev"
APP="$DEV_ROOT/Roboco Dev.app"
CONTENTS="$APP/Contents"
DATA_DIR="${ROBOCO_DEV_DATA_DIR:-$DEV_ROOT/data}"
IPC_PORT="${ROBOCO_DEV_IPC_PORT:-49777}"

if pgrep -f -x "$CONTENTS/MacOS/roboco" >/dev/null 2>&1; then
  echo "Roboco Dev is already running. Quit it before rebuilding the signed bundle." >&2
  exit 1
fi

cd "$ROOT"
cargo build -p roboco

mkdir -p "$CONTENTS/MacOS" "$CONTENTS/Resources" "$DATA_DIR"
install -m 755 "$ROOT/target/debug/roboco" "$CONTENTS/MacOS/roboco"
sed "s/__VERSION__/$VERSION/g" "$ROOT/dist/macos/Info-dev.plist" >"$CONTENTS/Info.plist"
plutil -replace LSEnvironment.ROBOCO_DATA_DIR -string "$DATA_DIR" "$CONTENTS/Info.plist"
plutil -replace LSEnvironment.ROBOCO_IPC_PORT -string "$IPC_PORT" "$CONTENTS/Info.plist"

if [[ ! -f "$CONTENTS/Resources/roboco.icns" ]]; then
  ICONSET="$DEV_ROOT/roboco-dev.iconset"
  mkdir -p "$ICONSET"
  for size in 16 32 128 256 512; do
    sips -z "$size" "$size" "$ROOT/dist/macos/icon-1024.png" --out "$ICONSET/icon_${size}x${size}.png" >/dev/null
    retina=$((size * 2))
    sips -z "$retina" "$retina" "$ROOT/dist/macos/icon-1024.png" --out "$ICONSET/icon_${size}x${size}@2x.png" >/dev/null
  done
  iconutil -c icns "$ICONSET" -o "$CONTENTS/Resources/roboco.icns"
fi

# A real Apple Development identity gives TCC a stable signing requirement
# across rebuilds. Set ROBOCO_DEV_CODESIGN_IDENTITY explicitly when more than
# one identity is installed; otherwise fall back to an ad-hoc signature.
IDENTITY="${ROBOCO_DEV_CODESIGN_IDENTITY:-}"
if [[ -z "$IDENTITY" ]]; then
  IDENTITY="$(security find-identity -v -p codesigning 2>/dev/null | sed -n 's/.*"\(Apple Development:[^"]*\)".*/\1/p' | head -1)"
fi
if [[ -n "$IDENTITY" ]]; then
  codesign --force --sign "$IDENTITY" --identifier sh.roboco.app.dev "$APP"
else
  codesign --force --sign - --identifier sh.roboco.app.dev "$APP"
  echo "warning: no Apple Development signing identity found; macOS may ask for permissions again after a rebuild" >&2
fi

echo "running Roboco Dev (bundle sh.roboco.app.dev, data $DATA_DIR, IPC $IPC_PORT)" >&2
# LaunchServices must own the process. Launching Contents/MacOS/roboco directly
# makes TCC attribute Screen Recording to the terminal (Warp, Terminal, etc.).
# -W keeps the script attached until the app exits. Runtime logs remain in the
# isolated data directory (`target/macos-dev/data/logs/roboco-headed.log`).
OPEN_ENV=(
  --env "ROBOCO_AUTO_UPDATE=0"
  --env "ROBOCO_DATA_DIR=$DATA_DIR"
  --env "ROBOCO_IPC_PORT=$IPC_PORT"
)
if [[ -n "${ROBOCO_OPEN_ROUTE:-}" ]]; then
  OPEN_ENV+=(--env "ROBOCO_OPEN_ROUTE=$ROBOCO_OPEN_ROUTE")
fi
exec open -W "${OPEN_ENV[@]}" "$APP" --args "$@"
