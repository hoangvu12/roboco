#!/bin/sh
set -eu
REPO_ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")/../../.." && pwd)
mkdir -p "$REPO_ROOT/target/tailcat"
cd "$REPO_ROOT/adapters/roboco-tailcat"
# gomobile/gobind must match the golang.org/x/mobile pin in go.mod.
exec "${GOMOBILE:-gomobile}" bind -target=android -androidapi 24 -trimpath \
  -o "$REPO_ROOT/target/tailcat/RobocoTailcat.aar" .
