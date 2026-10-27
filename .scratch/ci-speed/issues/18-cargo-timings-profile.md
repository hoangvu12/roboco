# 18 — `cargo --timings` reports from main runs (where do the Rust minutes go?)

**What to build:** R1 measured *steps*; nobody has measured *crates*. Add a main-only step that produces Cargo's [`--timings`](https://doc.rust-lang.org/cargo/reference/timings.html) HTML for each Rust test graph and uploads it as an artifact. This is the evidence for ticket 19 (test-binary consolidation) and for any later crate split. Research: `research-web-2026-10-28.md` §2.2.

- In each Rust test job (Windows `tests`, Windows `app`, Linux `core-tests`/`ui-tests`/`mobile-core`, the Android fixture-engine build), run the following **on push to main and `workflow_dispatch` only**, before the nextest step: `cargo test --no-run --timings <exactly the same -p/--features/--profile/--locked flags as the nextest invocation>`.
  - Nextest builds through `cargo test --no-run`, so with identical flags the following nextest step must find everything fresh. Verify with the log: no `Compiling` lines in the nextest step.
  - For `app`, use `cargo build --release -p roboco --timings` in place of the packaging build, or pass `--timings` through the script if it allows extra args.
- Upload `target/cargo-timings/cargo-timing-*.html` (retention 14 days).
- Write a short findings note in `.scratch/ci-speed/timings-<date>.md` from the first reports:
  - top 15 units by duration;
  - build-script and proc-macro units > 20 s;
  - total time in test-binary units of `roboco-engine`/`roboco-harness` (≈ link time, since the test crates are small);
  - the concurrency graph: periods with < 4 units active.
- No PR cost: the step is skipped on `pull_request`.

**Blocked by:** none (better after 04/06 so the graphs are the final ones; it can run on today's jobs via `workflow_dispatch` to inform 19 early)

**Status:** ready-for-agent

**Parent:** `.scratch/ci-speed/spec.md`

- [ ] Reports attached to a main run for every Rust test graph
- [ ] The nextest step after the timings step compiles nothing (log evidence), so main-run p50 grows by < 0.5 min
- [ ] `timings-<date>.md` written with the four findings above, and a recommendation for ticket 19 (go / no-go with the measured link share)

## Comments
