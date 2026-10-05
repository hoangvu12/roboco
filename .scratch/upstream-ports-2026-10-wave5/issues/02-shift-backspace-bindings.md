# 02 — Shift+Backspace bindings in composer and palette inputs

**What to build:** Port upstream `2a884777` (#757): Shift+Backspace
deletes backwards in the message composer input and the palette search
input. Roboco binds only the bare `backspace` keystroke, so the
shift-qualified one matches no binding and is a dead key in both
inputs.

**Roboco gap (verified):** `input_bindings`
(`crates/ui/src/composer.rs:1487-1496`, `KeyBinding::new("backspace",
Backspace, ctx)` at :1493) and the palette search bindings
(`composer.rs:1604-1625`, binding at :1612) both lack any
shift-qualified backspace; zero `shift-backspace` hits repo-wide.

**How:** add the binding(s) in both lists, mirroring how
`cmd-backspace`/`alt|ctrl-backspace` already sit alongside; the
`backspace` word-delete handler exists at `composer.rs:2905-2910`.
Upstream is one file, +50 lines including its test.

**Web:** nothing — browsers handle Shift+Backspace natively in text
inputs.

**Blocked by:** None.

**Status:** ready-for-agent

**Upstream SHAs:** `2a884777` (#757) — `crates/ui/src/composer.rs` →
same path here (ui crate un-prefixed). Source:
`.scratch/upstream-drift/2026-10-05.md` § #757.

**Verification budget:** deferred — wave-final batched pass:
`cargo nextest run -p roboco-ui --lib` (the new regression test beside
the existing input tests at `composer.rs:12070+`).

- [ ] `shift-backspace` bound in the composer input bindings
- [ ] `shift-backspace` bound in the palette search bindings
- [ ] Regression test added next to the existing `input.backspace` tests
- [ ] Port commit records the upstream SHA
