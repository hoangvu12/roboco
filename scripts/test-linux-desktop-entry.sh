#!/usr/bin/env bash
# Offline check that the Linux tarball installer (the install.sh that
# scripts/package-linux.sh puts in the release tarball) writes a launcher
# entry with absolute paths plus the icon, idempotently, under a throwaway
# HOME. Nothing touches the network, systemd, or the real home.
#
# Upstream runs the same checks against a second copy of the entry code in
# edge/src/install.sh (the curl installer) and asserts the two copies stay
# identical; Roboco removed edge, so the tarball's install.sh is the single
# copy and that assertion is deliberately dropped.
#
# Usage: scripts/test-linux-desktop-entry.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
VERSION=9.9.9
fail() { echo "FAIL: $*" >&2; exit 1; }

# A fake release: the tarball layout package-linux.sh produces, minus the real
# binary.
PKG="roboco-$VERSION-linux-x86_64"
mkdir -p "$WORK/pkg/$PKG"
printf '#!/bin/sh\nexit 0\n' >"$WORK/pkg/$PKG/roboco"
chmod 755 "$WORK/pkg/$PKG/roboco"
cp "$ROOT/dist/roboco.desktop" "$WORK/pkg/$PKG/roboco.desktop"
printf 'not-really-a-png' >"$WORK/pkg/$PKG/roboco.png"

# The tarball's install.sh lives in a heredoc inside package-linux.sh.
sed -n "/<<'INSTALL'/,/^INSTALL\$/p" "$ROOT/scripts/package-linux.sh" | sed '1d;$d' \
  | sed "s/__VERSION__/$VERSION/" >"$WORK/pkg/$PKG/install.sh"
chmod 755 "$WORK/pkg/$PKG/install.sh"

# Each run gets its own TMPDIR, which must be empty again afterwards (kept
# from upstream, whose curl installer removed its download dir through
# $TMPDIR; the tarball installer must leave nothing behind either).
mkdir -p "$WORK/tmp"
run_tarball() {
  local home="$1"; shift
  env -i HOME="$home" USER=tester PATH="/usr/bin:/bin" TMPDIR="$WORK/tmp" "$@" \
    bash "$WORK/pkg/$PKG/install.sh" >"$WORK/out.log" 2>&1 \
    || { cat "$WORK/out.log" >&2; fail "tarball installer exited non-zero"; }
  [ -z "$(ls -A "$WORK/tmp")" ] || fail "tarball installer left files in TMPDIR: $(ls -A "$WORK/tmp")"
}

# check HOME DATA_HOME
check() {
  local home="$1" data="$2" entry="$2/applications/roboco.desktop"
  [ -f "$entry" ] || fail "missing $entry"
  [ -f "$data/icons/hicolor/1024x1024/apps/roboco.png" ] || fail "missing hicolor icon"
  # `$(...)` strips nothing needed here: paths in these tests have no newlines.
  grep -qxF "TryExec=$home/.roboco/app/current/roboco" "$entry" || fail "TryExec: $(grep '^TryExec' "$entry")"
  grep -qxF "Icon=$home/.roboco/app/current/roboco.png" "$entry" || fail "Icon: $(grep '^Icon' "$entry")"
  grep -qxF "StartupWMClass=roboco" "$entry" || fail "StartupWMClass changed"
  [ "$(grep -c '^\[Desktop Entry\]' "$entry")" = 1 ] || fail "duplicated entry"
  [ "$(grep -c '^Exec=' "$entry")" = 1 ] || fail "Exec lines"
  [ -z "$(find "$data" -name '.roboco*')" ] || fail "temp files left behind"
  # A user-level icon cache is only ever refreshed, never created.
  [ ! -e "$data/icons/hicolor/icon-theme.cache" ] || fail "created a hicolor icon cache"
  if command -v desktop-file-validate >/dev/null 2>&1; then
    desktop-file-validate "$entry" || fail "desktop-file-validate"
  fi
}

# Default XDG location, then a re-run (how updates are installed) is stable.
home="$WORK/tarball-a/home"; mkdir -p "$home"
run_tarball "$home"
check "$home" "$home/.local/share"
grep -qxF "Exec=$home/.roboco/app/current/roboco %u" "$home/.local/share/applications/roboco.desktop" \
  || fail "Exec line"
before="$(cat "$home/.local/share/applications/roboco.desktop")"
run_tarball "$home"
check "$home" "$home/.local/share"
[ "$before" = "$(cat "$home/.local/share/applications/roboco.desktop")" ] || fail "re-run changed the entry"

# XDG_DATA_HOME wins when absolute; a relative value is ignored per the spec.
home="$WORK/tarball-b/home"; mkdir -p "$home"
run_tarball "$home" XDG_DATA_HOME="$WORK/tarball-b/xdg"
check "$home" "$WORK/tarball-b/xdg"
[ ! -e "$home/.local/share/applications" ] || fail "wrote outside XDG_DATA_HOME"
home="$WORK/tarball-c/home"; mkdir -p "$home"
run_tarball "$home" XDG_DATA_HOME=relative/dir
check "$home" "$home/.local/share"

# A home with a space and characters the Exec key must quote and escape.
home="$WORK/tarball-d/h o\$me\"x%y"; mkdir -p "$home"
run_tarball "$home"
check "$home" "$home/.local/share"
# Spec: quote the argument, `\` before " and $ (doubled again for the file's
# string escaping), and `%%` for a literal `%`.
want="Exec=\"$WORK/tarball-d/"'h o\\$me\\"x%%y'"/.roboco/app/current/roboco\" %u"
grep -qxF "$want" "$home/.local/share/applications/roboco.desktop" \
  || fail "Exec quoting: $(grep '^Exec=' "$home/.local/share/applications/roboco.desktop")"

echo "ok: tarball installer"
