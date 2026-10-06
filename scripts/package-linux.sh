#!/usr/bin/env bash
# Linux packaging: build the release binary and produce
#   target/package/roboco-<version>-linux-<arch>.tar.gz
# containing the binary, the .desktop entry, and the icon, plus an install.sh
# that installs them into the self-updating ~/.roboco/app layout and links
# ~/.local (XDG) paths to it.
#
# Usage: scripts/package-linux.sh
# Env:   PROFILE=debug for a fast unoptimized package (CI smoke); default release.
#        SKIP_WEB_BUILD=1 to skip the pnpm build step (use a pre-existing
#        web/packages/app/dist or set ROBOCO_WEB_DIST to point elsewhere).

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
command -v cargo >/dev/null 2>&1 || PATH="$HOME/.cargo/bin:$PATH"
PROFILE="${PROFILE:-release}"
ARCH="$(uname -m)"
VERSION="$(grep -m1 '^version' "$ROOT/Cargo.toml" | sed 's/.*"\(.*\)".*/\1/')"
BUILD_DIR="${CARGO_TARGET_DIR:-$ROOT/target}"
[[ "$BUILD_DIR" = /* ]] || BUILD_DIR="$ROOT/$BUILD_DIR"
OUT_DIR="$ROOT/target/package"
STAGE="$OUT_DIR/roboco-$VERSION-linux-$ARCH"
TARBALL="$STAGE.tar.gz"

cd "$ROOT"
# The engine embeds the built web client (rust-embed, staged by
# crates/engine/build.rs). Build it before cargo so the embed has bytes
# to bake. Skippable for CI caches that already have a fresh dist.
if [[ "${SKIP_WEB_BUILD:-0}" != "1" ]]; then
  command -v pnpm >/dev/null 2>&1 || { echo "pnpm is required to build the embedded web client (or set SKIP_WEB_BUILD=1)" >&2; exit 1; }
  ( cd "$ROOT/web" && corepack enable >/dev/null 2>&1 || true )
  ( cd "$ROOT/web" && pnpm install --frozen-lockfile )
  ( cd "$ROOT/web" && pnpm --filter "@roboco/app" run build )
fi

if [[ "$PROFILE" == "release" ]]; then
  cargo build --release -p roboco
  BIN="$BUILD_DIR/release/roboco"
else
  cargo build -p roboco
  BIN="$BUILD_DIR/debug/roboco"
fi

# Build the managed transport from pinned Go sources for this host architecture.
case "$ARCH" in
  x86_64) GO_ARCH=amd64 ;;
  aarch64|arm64) GO_ARCH=arm64 ;;
  *) echo "unsupported Linux package architecture: $ARCH" >&2; exit 1 ;;
esac
ADAPTER="$ROOT/target/roboco-tailcat-linux-$ARCH"
( cd "$ROOT/adapters/roboco-tailcat" && GOTOOLCHAIN=auto CGO_ENABLED=0 GOOS=linux GOARCH="$GO_ARCH" go build -mod=readonly -trimpath -o "$ADAPTER" ./cmd/roboco-tailcat )

rm -rf "$STAGE" "$TARBALL"
mkdir -p "$STAGE"
install -m 755 "$BIN" "$STAGE/roboco"
install -m 755 "$ADAPTER" "$STAGE/roboco-tailcat"
install -m 644 "$ROOT/dist/roboco.desktop" "$STAGE/roboco.desktop"
install -m 644 "$ROOT/dist/roboco.png" "$STAGE/roboco.png"
mkdir -p "$STAGE/licenses/fonts"
cp "$ROOT/crates/ui/assets/fonts/licenses/"* "$STAGE/licenses/fonts/"
cp "$ROOT/crates/voice/NOTICE.md" "$STAGE/licenses/parakeet-v3.txt"

mkdir -p "$STAGE/licenses/tailcat"
cp -R "$ROOT/adapters/roboco-tailcat/licenses/bundle/." "$STAGE/licenses/tailcat/"
cp "$ROOT/adapters/roboco-tailcat/LICENSE.kratos" "$STAGE/licenses/tailcat/LICENSE.kratos"
cp "$ROOT/THIRD_PARTY_NOTICES.md" "$STAGE/THIRD_PARTY_NOTICES.md"
cp "$ROOT/LICENSE" "$STAGE/LICENSE"
cat >"$STAGE/install.sh" <<'INSTALL'
#!/usr/bin/env bash
# Install Roboco for this user (no root needed), in the layout the in-app
# updater manages: ~/.roboco/app/<version> behind a `current` symlink — the
# same layout the updater's managed-install path expects — with
# ~/.local/bin/roboco and the desktop entry pointing through it.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERSION="__VERSION__"
APP_ROOT="$HOME/.roboco/app"
DEST="$APP_ROOT/$VERSION"
mkdir -p "$APP_ROOT"
if [ ! -x "$DEST/roboco" ] || [ ! -x "$DEST/roboco-tailcat" ]; then
  # Copy beside the final name, then rename: an interrupted install never
  # leaves a half-copied version the updater would trust.
  STAGE="$(mktemp -d "$APP_ROOT/.install-$VERSION-XXXXXX")"
  cp -R "$HERE/." "$STAGE/"
  rm -rf "$DEST"
  mv "$STAGE" "$DEST"
fi
if ! "$DEST/roboco" --version >/dev/null; then
  echo "Roboco could not start; see the loader error above. Install the missing runtime libraries (including ALSA, libasound.so.2), then retry." >&2
  exit 1
fi
ln -sfn "$DEST" "$APP_ROOT/current"
mkdir -p "$HOME/.local/bin"
ln -sfn "$APP_ROOT/current/roboco" "$HOME/.local/bin/roboco"

# Launchers list Roboco through a per-user .desktop entry. The one in the tarball
# says `Exec=roboco` and `TryExec=roboco`, which only resolve when ~/.local/bin is
# on the PATH of the desktop session (often not, e.g. a bare Wayland + fuzzel
# setup) and TryExec then hides the entry outright. So write it with absolute
# paths through the `current` symlink, which keeps working across updates. The
# icon is referenced by path too: the only artwork is 1024x1024, a size the
# hicolor theme doesn't index, so a name lookup alone can come up empty.
# (Upstream duplicates this function in edge/src/install.sh, the curl
# installer; Roboco removed edge, so this single copy is the whole surface —
# scripts/test-linux-desktop-entry.sh checks it offline.)
install_desktop_entry() {
  src="$1"
  app="$2"
  [ -f "$src/roboco.desktop" ] && [ -f "$src/roboco.png" ] || return 1
  case "${XDG_DATA_HOME:-}" in
    /*) data_home="$XDG_DATA_HOME" ;;
    *) data_home="$HOME/.local/share" ;;
  esac
  apps_dir="$data_home/applications"
  icon_dir="$data_home/icons/hicolor/1024x1024/apps"
  bin="$app/current/roboco"
  icon="$app/current/roboco.png"
  # Desktop Entry `Exec` quoting: double-quote an argument with reserved
  # characters, backslash-escape ", `, $ and \ inside, then double every
  # backslash again for the file's own string escaping. `%` must be `%%`.
  case "$bin" in
    *[!A-Za-z0-9_./-]*)
      exec_bin="\"$(printf '%s' "$bin" | sed -e 's/\\/\\\\\\\\/g' -e 's/["`$]/\\\\&/g' -e 's/%/%%/g')\""
      ;;
    *) exec_bin="$bin" ;;
  esac
  try_bin="$(printf '%s' "$bin" | sed 's/\\/\\\\/g')"
  icon_val="$(printf '%s' "$icon" | sed 's/\\/\\\\/g')"

  mkdir -p "$apps_dir" "$icon_dir" || return 1
  # Write beside the final name, then rename, so a launcher watching the
  # directory never reads a half-written entry (a leading dot is ignored).
  # Not `tmp`: sh has no `local`, and the curl installer's EXIT trap removes
  # its download dir through `$tmp` (kept as the rule even though Roboco's
  # only copy runs from an extracted tarball).
  entry_tmp="$apps_dir/.roboco.desktop.$$"
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
      Exec=*) printf 'Exec=%s %%u\n' "$exec_bin" ;;
      TryExec=*) printf 'TryExec=%s\n' "$try_bin" ;;
      Icon=*) printf 'Icon=%s\n' "$icon_val" ;;
      *) printf '%s\n' "$line" ;;
    esac
  done <"$src/roboco.desktop" >"$entry_tmp" || { rm -f "$entry_tmp"; return 1; }
  mv -f "$entry_tmp" "$apps_dir/roboco.desktop" || { rm -f "$entry_tmp"; return 1; }
  cp "$src/roboco.png" "$icon_dir/.roboco.png.$$" \
    && mv -f "$icon_dir/.roboco.png.$$" "$icon_dir/roboco.png" || return 1

  # Best-effort cache refresh; both tools are optional. The icon cache is only
  # refreshed, never created: a user-level hicolor cache nobody else maintains
  # would hide icons other apps later install there, and the entry above
  # references the icon by path anyway.
  command -v update-desktop-database >/dev/null 2>&1 \
    && update-desktop-database "$apps_dir" >/dev/null 2>&1 || true
  [ -f "$data_home/icons/hicolor/icon-theme.cache" ] \
    && command -v gtk-update-icon-cache >/dev/null 2>&1 \
    && gtk-update-icon-cache -q -t -f "$data_home/icons/hicolor" >/dev/null 2>&1 || true
  return 0
}
install_desktop_entry "$HERE" "$APP_ROOT" \
  || echo "warn: could not install the desktop entry — Roboco won't appear in application launchers"

echo "Installed Roboco $VERSION. It updates itself from now on."
case ":$PATH:" in
  *":$HOME/.local/bin:"*) ;;
  *) echo "Add ~/.local/bin to your PATH to run \`roboco\` from a terminal." ;;
esac
INSTALL
sed -i "s/__VERSION__/$VERSION/" "$STAGE/install.sh"
chmod 755 "$STAGE/install.sh"

tar -czf "$TARBALL" -C "$OUT_DIR" "$(basename "$STAGE")"
rm -rf "$STAGE"
echo "packaged: $TARBALL"
tar -tzf "$TARBALL"
