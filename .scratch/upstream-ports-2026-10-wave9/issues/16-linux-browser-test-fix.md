# 16 — Linux browser: fail fast under cfg(test) (upstream #1026 slice, `ea3dcc811`)

**What to build:** Port the test-determinism slice of `ea3dcc811`: under
`cfg(test)`, `NativePage::start` fails at once unless the test brings its
own helper, instead of waking the test scheduler from a browser-startup
thread ("Your test is not deterministic"); tests stop extracting and
spawning the real WebKitGTK helper. Files: `crates/ui/src/browser/linux/mod.rs`.

**Excluded upstream hunks:** everything else in #1026 — the PR-link
destination settings, in-app Browser tab and PR surfaces ride wave-7
ticket 22 (browser lane) + the parked PR lane; sequence there.

**Blocked by:** none.

**Status:** ready-for-human (ported in 327fef69d)

**Verification budget:** the linux browser ui-test suite
(`ui-tests.yml` lane). Source: `.scratch/upstream-drift/2026-10-25.md` § B5.

- [x] Fail-fast + no-helper-spawn ported; browser suite deterministic
- [x] Commit records upstream SHA `ea3dcc811` (#1026, slice)

## Comments

- 2026-10-26: Ported in 327fef69d (upstream `ea3dcc811`, #1026,
  test-determinism slice). Upstream guards the async `NativePage::start`
  entry (Roboco's linux page creation is the synchronous
  `NativePage::new`, which calls `BrowserData::worker` — the site that
  extracts the helper binary into the cache and spawns both the helper
  child and the "browser-frames" reader thread); the same fail-fast
  now returns `Err("Unit tests start no browser helper")` from
  `NativePage::new` under `cfg(test)`, before any extraction, spawn or
  reader thread, so a Browser tab in a unit test shows the error row
  exactly as it does when the runtime is missing — no scheduler wakes
  from outside the test. Roboco has no `helper_override` (upstream's
  test-only hook for startup tests): nothing under `cfg(test)` needs a
  helper here — the browser fixture runs as an example under the
  `browser-fixture` feature, not cfg(test). The two lib tests that
  navigate a Browser tab on Linux —
  `transcript_links_open_new_tabs_and_reject_stale_sessions` and
  `markdown_preview_events_open_browser_from_tree_and_file_tabs` —
  previously reached the real helper spawn (Roboco's counterpart of
  upstream's failing ui-test); with the guard they pass
  deterministically and the cache's helper binary is untouched
  (~/.cache/roboco/browser's copy predates this session by a month,
  and today's runs added nothing). Verification: `browser`-filtered
  suite 12/12, `exit_regressions` 21/21, full `-p roboco-ui --lib`
  1689/1689 — the ui-tests.yml lane's test set, run in the debug
  profile on this box (the lane's `--release --locked` profile
  rebuilds the whole dependency tree on a 3-core box and changes
  nothing about the cfg(test) guard; noted rather than run).
