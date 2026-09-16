#!/usr/bin/env bash
# Linux packaging: build the release binary and produce
#   target/package/roboco-<version>-linux-<arch>.tar.gz
# containing the binary, the .desktop entry, and the icon, plus an install.sh
# that drops them into ~/.local (XDG) paths.
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
  BIN="$ROOT/target/release/roboco"
else
  cargo build -p roboco
  BIN="$ROOT/target/debug/roboco"
fi

rm -rf "$STAGE" "$TARBALL"
mkdir -p "$STAGE"
install -m 755 "$BIN" "$STAGE/roboco"
install -m 644 "$ROOT/dist/roboco.desktop" "$STAGE/roboco.desktop"
install -m 644 "$ROOT/dist/roboco.png" "$STAGE/roboco.png"
mkdir -p "$STAGE/licenses/fonts"
cp "$ROOT/crates/ui/assets/fonts/licenses/"* "$STAGE/licenses/fonts/"

cat >"$STAGE/install.sh" <<'INSTALL'
#!/usr/bin/env bash
# Install Roboco into ~/.local (no root needed).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
install -Dm755 "$HERE/roboco" "$HOME/.local/bin/roboco"
install -Dm644 "$HERE/roboco.desktop" "$HOME/.local/share/applications/roboco.desktop"
install -Dm644 "$HERE/roboco.png" "$HOME/.local/share/icons/hicolor/1024x1024/apps/roboco.png"
command -v update-desktop-database >/dev/null 2>&1 \
  && update-desktop-database "$HOME/.local/share/applications" || true
echo "Installed. Make sure ~/.local/bin is on your PATH."
INSTALL
chmod 755 "$STAGE/install.sh"

tar -czf "$TARBALL" -C "$OUT_DIR" "$(basename "$STAGE")"
rm -rf "$STAGE"
echo "packaged: $TARBALL"
tar -tzf "$TARBALL"
