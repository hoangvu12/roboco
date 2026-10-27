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

**Status:** ready-for-agent

**Verification budget:** the linux browser ui-test suite
(`ui-tests.yml` lane). Source: `.scratch/upstream-drift/2026-10-25.md` § B5.

- [ ] Fail-fast + no-helper-spawn ported; browser suite deterministic
- [ ] Commit records upstream SHA `ea3dcc811` (#1026, slice)

## Comments
