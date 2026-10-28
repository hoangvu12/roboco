#!/usr/bin/env bash
# Install a pinned cargo-nextest release (x86_64 Linux) after verifying its
# SHA-256, instead of trusting a third-party install action (ci-speed 06, from
# upstream scripts/ci/install-nextest.sh). Same version as windows.yml's
# install-action pin. To bump: change VERSION and SHA256 (the release's
# cargo-nextest-<version>-x86_64-unknown-linux-gnu.sha256 asset).
set -euo pipefail
VERSION=0.9.144
SHA256=8a4f726272b0a1c499bd87ca3978bfbb1a8c20bb08ccf075b9996e2081bd1e1e

[ "$(uname -m)" = x86_64 ] || { echo "install-nextest.sh: x86_64 only" >&2; exit 1; }
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
curl --proto '=https' --tlsv1.2 -fsSL --retry 5 -o "$tmp/nextest.tar.gz" \
  "https://github.com/nextest-rs/nextest/releases/download/cargo-nextest-$VERSION/cargo-nextest-$VERSION-x86_64-unknown-linux-gnu.tar.gz"
echo "$SHA256  $tmp/nextest.tar.gz" | sha256sum -c -
mkdir -p "$HOME/.cargo/bin"
tar -xzf "$tmp/nextest.tar.gz" -C "$HOME/.cargo/bin" cargo-nextest
cargo nextest --version
