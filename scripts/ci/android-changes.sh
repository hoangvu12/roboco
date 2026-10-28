#!/usr/bin/env bash
# android.yml's `changes` job (ci-speed 08): which Android tiers this run needs.
#
#   full             push to main, nightly, dispatch, or a PR labelled `ci:full`
#                    (read live from the API, so re-running after adding the
#                    label picks it up; the event payload would be stale)
#   android_relevant the PR touches what the APK or the emulator rig is built
#                    from: the emulator flow suite runs
#   gradle_host      the PR touches the Gradle host build: the Windows
#                    bash-free leg runs
#
# Off-PR, or with `ci:full`, everything is true. Fails open: if the PR's file
# list cannot be read completely, every output is true.
#
# Usage in CI: android-changes.sh (GITHUB_EVENT_NAME, GITHUB_REPOSITORY,
# PR_NUMBER, PR_CHANGED_FILES, GH_TOKEN; writes $GITHUB_OUTPUT).
# Dry run:     printf '%s\n' <paths> | android-changes.sh --classify
set -uo pipefail

# The APK links the mobile core and its workspace closure
# (`cargo tree -p roboco-mobile -e normal,build --target aarch64-linux-android`:
# doc, engine-client, markdown, mobile, mobile-client, preview-relay, proto,
# rpc, syntax, text), the bindings generator, the Go Tailcat transport and
# the UI font/icon assets; xtask and android-assets build it.
android_relevant() {
  case "$1" in
    apps/android/*) return 0 ;;
    crates/mobile/* | crates/mobile-client/* | crates/mobile-bindgen/*) return 0 ;;
    crates/text/* | crates/markdown/* | crates/doc/* | crates/syntax/*) return 0 ;;
    crates/engine-client/* | crates/preview-relay/* | crates/proto/* | crates/rpc/*) return 0 ;;
    crates/xtask/* | crates/android-assets/*) return 0 ;;
    adapters/roboco-tailcat/*) return 0 ;;
    crates/ui/assets/fonts/* | crates/ui/assets/icons/* | crates/ui/assets/file-icons/*) return 0 ;;
    Cargo.lock | rust-toolchain.toml | .cargo/*) return 0 ;;
    .github/workflows/android.yml | scripts/ci/android-changes.sh) return 0 ;;
  esac
  return 1
}

# What the Windows leg proves: Gradle and the xtask build the APK with no
# bash. (`*` in a case pattern also matches `/`.)
gradle_host() {
  case "$1" in
    apps/android/gradle/* | apps/android/buildSrc/*) return 0 ;;
    apps/android/*.gradle.kts | apps/android/gradlew* | apps/android/gradle.properties) return 0 ;;
    crates/xtask/*) return 0 ;;
    .github/workflows/android.yml | scripts/ci/android-changes.sh) return 0 ;;
  esac
  return 1
}

# Reads paths on stdin, prints `android_relevant=…` and `gradle_host=…`.
classify() {
  local relevant=false gradle=false path
  while IFS= read -r path; do
    [ -n "$path" ] || continue
    android_relevant "$path" && relevant=true
    gradle_host "$path" && gradle=true
  done
  echo "android_relevant=$relevant"
  echo "gradle_host=$gradle"
}

if [ "${1:-}" = --classify ]; then
  classify
  exit 0
fi

out=${GITHUB_OUTPUT:-/dev/stdout}
emit() {
  printf '%s\n' "$@" >>"$out"
  printf '%s\n' "$@"
  [ -z "${GITHUB_STEP_SUMMARY:-}" ] || printf -- '- `%s`\n' "$@" >>"$GITHUB_STEP_SUMMARY"
}
everything() {
  echo "android-changes: $1"
  emit "full=$2" android_relevant=true gradle_host=true
  exit 0
}

[ "${GITHUB_EVENT_NAME:-}" = pull_request ] || everything "${GITHUB_EVENT_NAME:-local} run: the full tier" true

labels=$(gh api "repos/$GITHUB_REPOSITORY/issues/$PR_NUMBER/labels" --jq '.[].name') ||
  everything "could not read the PR's labels; running everything" false
if printf '%s\n' "$labels" | grep -qx 'ci:full'; then
  everything "the PR is labelled ci:full: the full tier" true
fi

# One row per changed file: its path and, for a rename, its old path (both
# count). The REST API lists at most 3000 files.
rows=$(gh api --paginate "repos/$GITHUB_REPOSITORY/pulls/$PR_NUMBER/files?per_page=100" \
  --jq '.[] | [.filename, (.previous_filename // "")] | @tsv') ||
  everything "could not list the PR's files; running everything" false
listed=$(printf '%s\n' "$rows" | grep -c .)
if [ "$listed" -lt "${PR_CHANGED_FILES:-0}" ]; then
  everything "the API listed $listed of ${PR_CHANGED_FILES} files; running everything" false
fi

echo "android-changes: $listed changed files"
# shellcheck disable=SC2046 # one output per line
emit full=false $(printf '%s\n' "$rows" | tr '\t' '\n' | classify)
