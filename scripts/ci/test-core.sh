#!/usr/bin/env bash
# ui-tests.yml `core-tests` (ci-speed 06): every Linux Rust test that is not UI
# or mobile core, as ONE build and ONE nextest run. Separate `cargo test`
# invocations (engine-local-regressions, preview-tests.yml, android.yml's
# asset/xtask step) each re-resolved features and relinked the same graph.
#
#   roboco-harness   every test binary (the Linux job ran opencode, codex,
#                    claude, pi and the lib's opencode:: tests);
#                    native-fixture is on for the whole build — it only adds
#                    the fixture binaries and gates pi_rpc/windows_native
#   roboco-engine    lib + the integration tests named below. The engine has
#                    many more binaries (live agents, Windows-only suites)
#                    that no Linux job ran, so they are listed, not globbed,
#                    to avoid compiling them
#   roboco-sync, roboco-update   lib only (all they have)
#   roboco-mcp       lib (all it has)
#   roboco-preview   every test (preview-tests.yml)
#   roboco-doc       lib + attachments_roundtrip
#   roboco-android-assets, roboco-xtask   lib/bins + tests (android.yml)
#
# nextest does not run doctests; these crates have none (their doc comments
# hold no Rust code blocks). Extra arguments go to nextest (the nightly
# release backstop adds `--release`).
set -euo pipefail
cd "$(dirname "$0")/../.."

tests=()
for f in crates/harness/tests/*.rs crates/preview/tests/*.rs crates/doc/tests/*.rs \
  crates/android-assets/tests/*.rs; do
  tests+=(--test "$(basename "$f" .rs)")
done
for t in pi_resume pi_steering pi_forks \
  account_failover account_sign_in_state agent_origin codex_subagents completion_notices \
  continue_on_harness engine_local harness_switch limited_chats local_profiles \
  project_action_commands project_actions_remote project_actions_rpc remote_settings \
  restart_resume resume_at_reset side_chats rooms; do
  tests+=(--test "$t")
done

exec cargo nextest run -P ci --locked --no-fail-fast \
  -p roboco-harness -p roboco-engine -p roboco-sync -p roboco-update -p roboco-mcp \
  -p roboco-preview -p roboco-doc -p roboco-android-assets -p roboco-xtask \
  --features roboco-harness/native-fixture \
  --lib --bins "${tests[@]}" "$@"
