#!/usr/bin/env bash
# Smoke-test a Linux release tarball the way a user installs it (release.yml's
# linux-smoke job, ci-speed 21). Run as root in a clean ubuntu:22.04 container
# (glibc 2.35, the floor scripts/package-linux.sh guards) that has only the
# runtime libraries the docs list:
#   1. every library `roboco` needs resolves (ldd), and no GLIBC_* version is
#      missing;
#   2. the tarball's install.sh installs it into a scratch HOME;
#   3. the installed `roboco --version` and `roboco status` run; status, with
#      ROBOCO_IPC_PORT=0, reports the scratch data dir and no running engine
#      (the Windows CLI smoke's contract).
#   usage: smoke-linux-tarball.sh roboco-<version>-linux-<arch>.tar.gz
set -euo pipefail
tarball="$1"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

echo "== $(ldd --version | head -1)"
tar -xzf "$tarball" -C "$work"
dir="$(find "$work" -mindepth 1 -maxdepth 1 -type d -name 'roboco-*')"
[ -x "$dir/roboco" ] || { echo "::error::$tarball has no roboco binary"; exit 1; }

echo "== ldd roboco"
ldd "$dir/roboco" 2>&1 | tee "$work/ldd.txt"
if grep -qE 'not found' "$work/ldd.txt"; then
  echo "::error::roboco does not load on $(ldd --version | head -1) with the documented runtime libraries:"
  grep -E 'not found' "$work/ldd.txt"
  exit 1
fi

home="$work/home"
mkdir -p "$home"
run() { env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin HOME="$home" ROBOCO_IPC_PORT=0 "$@"; }

echo "== install.sh"
run "$dir/install.sh"
bin="$home/.local/bin/roboco"

echo "== roboco --version"
run timeout 20 "$bin" --version

echo "== roboco status"
out="$(run timeout 20 "$bin" status)"
echo "$out"
grep -qxF "Data dir: $home/.roboco" <<<"$out" \
  || { echo "::error::roboco status did not select \$HOME/.roboco"; exit 1; }
grep -qxF 'Engine: not running' <<<"$out" \
  || { echo "::error::an isolated roboco status must report no running engine"; exit 1; }
echo "smoke ok: $(basename "$tarball")"
