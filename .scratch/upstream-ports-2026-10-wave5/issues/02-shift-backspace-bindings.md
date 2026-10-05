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

**Status:** claimed

**Upstream SHAs:** `2a884777` (#757) — `crates/ui/src/composer.rs` →
same path here (ui crate un-prefixed). Source:
`.scratch/upstream-drift/2026-10-05.md` § #757.

**Verification budget:** deferred — wave-final batched pass:
`cargo nextest run -p roboco-ui --lib` (the new regression test beside
the existing input tests at `composer.rs:12070+`).

- [x] `shift-backspace` bound in the composer input bindings
- [x] `shift-backspace` bound in the palette search bindings
- [x] Regression test added next to the existing `input.backspace` tests
- [x] Port commit records the upstream SHA

## Comments

Ported upstream `2a884777` (#757) 1:1 — no adaptation needed beyond
file placement (same path, ui crate un-prefixed).

- `shift-backspace` -> `Backspace` added to `input_bindings` (composer
  input, `crates/ui/src/composer.rs:1494`) and to the palette-search
  bindings (`composer.rs:1614`), beside the bare `backspace` entries,
  mirroring the existing `cmd-backspace` neighbors.
- Regression test `shift_backspace_deletes_text_and_selection_in_all_
  input_contexts` ported verbatim (minus a non-ASCII literal spelling),
  placed beside the existing `input.backspace` tests (`crlf_navigation_
  deletion_and_newlines_keep_the_pair_intact`); covers all three key
  contexts (generic composer, message composer, palette search),
  deletion of a char, a selection, and on empty text.
- Web: nothing to do (browsers handle Shift+Backspace natively), per
  the ticket.
- Verification deferred to the wave-final batched pass per spec.
